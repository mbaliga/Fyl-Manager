import { describe, expect, it } from "vitest";
import { detect, extOf, matchMagic, SIGNATURES, sniffText, peekZipEntries, refineZip } from "../src/core/detect";
import { BlobSource, bytesSource } from "../src/core/source";
import { fixtureSource } from "./util";

const CASES: [string, string, boolean][] = [
  ["hello.txt", "text/plain", true],
  ["code.ts", "text/typescript", true],
  ["notes.md", "text/markdown", true],
  ["data.json", "application/json", true],
  ["config.yaml", "application/yaml", true],
  ["feed.xml", "application/xml", true],
  ["table.csv", "text/csv", true],
  ["page.html", "text/html", true],
  ["vector.svg", "image/svg+xml", true],
  ["image.png", "image/png", false],
  ["image.bmp", "image/bmp", false],
  ["anim.gif", "image/gif", false],
  ["tone.wav", "audio/wav", false],
  ["tone.mp3", "audio/mpeg", false],
  ["tone.opus", "audio/ogg", false],
  ["clip.webm", "video/webm", false],
  ["sample.pdf", "application/pdf", false],
  ["sample.zip", "application/zip", false],
  ["sample.txt.gz", "application/gzip", false],
  ["sample.tar", "application/x-tar", false],
  ["doc.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", false],
  ["deck.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", false],
  ["book.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", false],
  ["book.ods", "application/vnd.oasis.opendocument.spreadsheet", false],
];

describe("detect: fixtures", () => {
  for (const [name, mime, isText] of CASES) {
    it(`${name} → ${mime}`, async () => {
      const { info } = await detect(fixtureSource(name));
      expect(info.detected).toBe(mime);
      expect(info.isText).toBe(isText);
    });
  }
  it("unknown binary has no detected type and is not text", async () => {
    const { info } = await detect(fixtureSource("unknown.bin"));
    expect(info.detected).toBeUndefined();
    expect(info.isText).toBe(false);
  });
  it("ignores a lying extension (png named .txt)", async () => {
    const png = fixtureSource("image.png");
    const lying = new BlobSource(await png.blob(), "image.txt");
    const { info } = await detect(lying);
    expect(info.detected).toBe("image/png");
    expect(info.isText).toBe(false);
  });
});

describe("detect: signatures and sniffing", () => {
  it("has 80+ signatures", () => expect(SIGNATURES.length).toBeGreaterThanOrEqual(80));
  it("matches wildcard ftyp boxes", () => {
    const b = new Uint8Array([0, 0, 0, 0x20, 0x66, 0x74, 0x79, 0x70, 0x68, 0x65, 0x69, 0x63]);
    expect(matchMagic(b)?.mime).toBe("image/heic");
  });
  it("matches tar at offset 257", () => {
    const b = new Uint8Array(512);
    b.set(new TextEncoder().encode("ustar"), 257);
    expect(matchMagic(b)?.mime).toBe("application/x-tar");
  });
  it("sniffs encodings", () => {
    expect(sniffText(new TextEncoder().encode("héllo")).encoding).toBe("utf-8");
    expect(sniffText(new Uint8Array([0xff, 0xfe, 0x41, 0])).encoding).toBe("utf-16le");
    expect(sniffText(new Uint8Array([0xef, 0xbb, 0xbf, 0x41])).encoding).toBe("utf-8-bom");
    expect(sniffText(new Uint8Array([0x63, 0x61, 0x66, 0xe9, 0x0a])).encoding).toBe("windows-1252");
    expect(sniffText(new Uint8Array([1, 2, 0, 3, 4])).isText).toBe(false);
    expect(sniffText(new Uint8Array(0)).isText).toBe(true);
  });
  it("extOf handles odd names", () => {
    expect(extOf("a.TAR.GZ")).toBe("tgz");
    expect(extOf("Makefile")).toBe("");
    expect(extOf(".env")).toBe("env");
    expect(extOf("x.y.csv")).toBe("csv");
  });
  it("lists utf-16 and latin1 fixtures as text", async () => {
    const a = (await detect(fixtureSource("utf16le.txt"))).info;
    const b = (await detect(fixtureSource("latin1.txt"))).info;
    expect([a.isText, a.encoding]).toEqual([true, "utf-16le"]);
    expect([b.isText, b.encoding]).toEqual([true, "windows-1252"]);
  });
});

describe("zip peeking", () => {
  it("reads central directory names", async () => {
    const names = await peekZipEntries(fixtureSource("sample.zip"));
    expect(names).toEqual(["a.txt", "dir/b.txt", "dir/sub/c.json"]);
  });
  it("returns null for non-zip", async () => {
    expect(await peekZipEntries(bytesSource(new Uint8Array(100), "x"))).toBeNull();
  });
  it("refines jar / apk / epub / odt", () => {
    expect(refineZip(["META-INF/MANIFEST.MF", "a.class"]).ext).toBe("jar");
    expect(refineZip(["AndroidManifest.xml", "classes.dex"]).ext).toBe("apk");
    expect(refineZip(["mimetype", "META-INF/container.xml", "c.xhtml"], "application/epub+zip").ext).toBe("epub");
    expect(refineZip(["mimetype"], "application/vnd.oasis.opendocument.text").ext).toBe("odt");
    expect(refineZip(["x.txt"]).ext).toBe("zip");
  });
});
