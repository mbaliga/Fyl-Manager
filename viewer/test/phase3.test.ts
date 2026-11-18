import { describe, expect, it } from "vitest";
import { parseMessage, splitMbox, decodeWords, decodeQP } from "../src/lib/mime";
import { parseAxml, attr, findAll } from "../src/plugins/package/axml";
import { buildAxml } from "../scripts/axml";
import { parseCal, fmtDate, get, all } from "../src/plugins/cal/parse";
import { findEmbeddedJpegs, jpegLength } from "../src/plugins/raw/jpeg";
import { createRegistry } from "../src/plugins";
import { detect } from "../src/core/detect";
import { fixtureBytes, fixtureSource } from "./util";

const text = (n: string) => new TextDecoder().decode(fixtureBytes(n));

describe("mime", () => {
  it("decodes encoded words and quoted-printable", () => {
    expect(decodeWords("=?UTF-8?Q?Hello_=E2=9C=93?=")).toBe("Hello ✓");
    expect(decodeWords("=?UTF-8?B?QWRh?= <a@b>")).toBe("Ada <a@b>");
    expect(new TextDecoder().decode(decodeQP("a=3Db=\r\nc"))).toBe("a=bc");
  });
  it("parses the multipart fixture: headers, alternatives, attachment", () => {
    const m = parseMessage(fixtureBytes("message.eml"));
    expect(m.headers.subject).toBe("Hello ✓ fixture");
    expect(m.headers.from).toContain("Ada Lovelace");
    expect(m.text).toContain("Plain body text");
    expect(m.html).toContain("HTML body ✓");
    expect(m.attachments.map((a) => a.name)).toEqual(["note.txt"]);
    expect(new TextDecoder().decode(m.attachments[0]!.bytes)).toBe("attachment content\n");
  });
  it("splits mbox", () => {
    const parts = splitMbox(fixtureBytes("inbox.mbox"));
    expect(parts.length).toBe(2);
    expect(parseMessage(parts[1]!).headers.subject).toBe("Second");
    expect(new TextDecoder().decode(parts[0]!)).not.toContain("From b@example.com");
  });
});

describe("axml", () => {
  it("round-trips a manifest", () => {
    const bin = buildAxml({ tag: "manifest", attrs: { package: "a.b.c", versionCode: 42, versionName: "9.9" }, children: [{ tag: "uses-permission", attrs: { name: "x.PERM" } }, { tag: "application", children: [{ tag: "activity" }, { tag: "activity" }] }] });
    const m = parseAxml(bin);
    expect(m.name).toBe("manifest");
    expect(attr(m, "package")).toBe("a.b.c");
    expect(attr(m, "versionCode")).toBe(42);
    expect(findAll(m, "uses-permission").map((p) => attr(p, "name"))).toEqual(["x.PERM"]);
    expect(findAll(m, "activity").length).toBe(2);
  });
  it("rejects non-axml", () => expect(() => parseAxml(new Uint8Array(16))).toThrow());
});

describe("calendar", () => {
  it("parses events and contacts", () => {
    const ev = parseCal(text("events.ics"))[0]!.children;
    expect(ev.map((e) => get(e, "SUMMARY"))).toEqual(["Team sync", "Holiday"]);
    expect(get(ev[0]!, "DESCRIPTION")).toBe("Weekly sync\nBring notes");
    expect(all(ev[0]!, "ATTENDEE")[0]!.params.CN).toBe("Ada Lovelace");
    expect(fmtDate("20240115T093000Z")).toBe("2024-01-15 09:30 UTC");
    expect(fmtDate("20240501")).toBe("2024-05-01");
    const cards = parseCal(text("people.vcf"));
    expect(cards.map((c) => get(c, "FN"))).toEqual(["Grace Hopper", "Alan Turing"]);
  });
  it("unfolds long lines", () => {
    expect(get(parseCal("BEGIN:VEVENT\r\nSUMMARY:abc\r\n def\r\nEND:VEVENT\r\n")[0]!, "SUMMARY")).toBe("abcdef");
  });
});

describe("raw preview extraction", () => {
  it("finds the embedded JPEG in the DNG-like fixture", async () => {
    const found = await findEmbeddedJpegs(fixtureSource("shot.dng"));
    expect(found.length).toBe(1);
    const bytes = fixtureBytes("shot.dng");
    expect(found[0]!.offset).toBe(120);
    expect(jpegLength(bytes.subarray(120), 0)).toBe(found[0]!.length);
    expect(found[0]!.offset + found[0]!.length).toBe(bytes.length);
  });
});

describe("registry covers every fixture", () => {
  const EXPECT: Record<string, string> = {
    "data.sqlite": "sqlite", "book.epub": "epub", "photo.heic": "heic", "tri.glb": "model3d", "cube.obj": "model3d", "pyramid.stl": "model3d", "tri.ply": "model3d",
    "shot.dng": "raw", "shapes.geojson": "geo", "track.gpx": "geo", "places.kml": "geo", "events.ics": "cal", "people.vcf": "cal", "analysis.ipynb": "ipynb",
    "message.eml": "mail", "inbox.mbox": "mail", "lib.jar": "package", "app.apk": "package",
  };
  for (const [f, id] of Object.entries(EXPECT)) {
    it(`${f} → ${id}`, async () => {
      const d = await detect(fixtureSource(f));
      expect(createRegistry().best(d.info, d.head)?.plugin.id).toBe(id);
    });
  }
});
