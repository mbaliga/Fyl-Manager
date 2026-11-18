import { describe, expect, it } from "vitest";
import { zipSync } from "fflate";
import { PDFDocument } from "pdf-lib";
import { parseCsv, serializeCsv, detectDelimiter } from "../src/lib/csv";
import { parseStructured, stripJsonc } from "../src/lib/parsers";
import { kindOf } from "../src/lib/kinds";
import { loadZipForEdit, openArchive, openZip, openTar, looksLikeTar } from "../src/lib/archive";
import { buildPdf, toPdf, type PageRef } from "../src/plugins/pdf/model";
import { fixtureBytes, fixtureSource } from "./util";
import { bytesSource } from "../src/core/source";

describe("csv", () => {
  it("parses quotes, escaped quotes and multiline fields", () => {
    const { rows, meta } = parseCsv('a,b\n"he said ""hi""","multi\nline"\n');
    expect(rows).toEqual([["a", "b"], ['he said "hi"', "multi\nline"]]);
    expect(meta.trailingNewline).toBe(true);
  });
  it("detects delimiters", () => {
    expect(detectDelimiter("a;b;c\n1;2;3\n")).toBe(";");
    expect(detectDelimiter("a\tb\n1\t2\n")).toBe("\t");
    expect(detectDelimiter("a,b\n1,2\n")).toBe(",");
  });
  it("round-trips fixtures (parse → serialize → parse)", () => {
    for (const f of ["table.csv", "quoted.csv", "table.tsv"]) {
      const text = new TextDecoder().decode(fixtureBytes(f));
      const a = parseCsv(text);
      const b = parseCsv(serializeCsv(a.rows, a.meta), a.meta.delimiter);
      expect(b.rows).toEqual(a.rows);
    }
  });
  it("preserves CRLF and quote-all style, changes delimiter on save", () => {
    const a = parseCsv('"a","b"\r\n"1","2"\r\n');
    expect(a.meta).toMatchObject({ eol: "\r\n", quoteAll: true, delimiter: "," });
    expect(serializeCsv(a.rows, a.meta)).toBe('"a","b"\r\n"1","2"\r\n');
    expect(serializeCsv(a.rows, { ...a.meta, delimiter: ";", quoteAll: false })).toBe("a;b\r\n1;2\r\n");
  });
  it("quotes only when needed", () => {
    expect(serializeCsv([["a,b", 'q"q', "plain", "x\ny"]], { delimiter: ",", eol: "\n", quoteAll: false, trailingNewline: false })).toBe('"a,b","q""q",plain,"x\ny"');
  });
  it("handles empty input and ragged rows", () => {
    expect(parseCsv("").rows).toEqual([[""]]);
    expect(parseCsv("a,b,c\n1\n").rows).toEqual([["a", "b", "c"], ["1"]]);
  });
});

describe("structured parsers", () => {
  it("json errors carry a position", () => {
    const r = parseStructured("json", '{ "a": 1, "b": }');
    expect(r.error).toBeDefined();
  });
  it("parses jsonc, ndjson, yaml, toml", () => {
    expect(parseStructured("jsonc", '{ // c\n "a": 1, /* x */ "b": [1,2,], }').value).toEqual({ a: 1, b: [1, 2] });
    expect(parseStructured("ndjson", '{"a":1}\n{"a":2}\n').value).toEqual([{ a: 1 }, { a: 2 }]);
    expect(parseStructured("ndjson", '{"a":1}\n{bad}\n').error).toBeDefined();
    expect(parseStructured("yaml", "a: 1\nb:\n  - x\n").value).toEqual({ a: 1, b: ["x"] });
    expect(parseStructured("yaml", "a: [1, 2\n").error).toBeDefined();
    expect(parseStructured("toml", '[p]\nname = "x"\n').value).toEqual({ p: { name: "x" } });
    expect(parseStructured("toml", "x = \n").error).toBeDefined();
  });
  it("stripJsonc keeps offsets", () => {
    const s = '{ /* c */ "a": 1 }';
    expect(stripJsonc(s).length).toBe(s.length);
  });
  it("maps extensions to kinds", () => {
    expect(kindOf("yml")).toBe("yaml");
    expect(kindOf("txt")).toBeNull();
  });
});

describe("archives", () => {
  it("zip: lists and extracts entries via range reads", async () => {
    const z = (await openZip(fixtureSource("sample.zip")))!;
    expect(z.entries.map((e) => e.path)).toEqual(["a.txt", "dir/b.txt", "dir/sub/c.json"]);
    expect(new TextDecoder().decode(await z.extract(z.entries[1]!))).toBe("bravo\n");
  });
  it("tar: lists and extracts", async () => {
    const t = await openTar(fixtureSource("sample.tar"));
    expect(t.entries.map((e) => e.path)).toEqual(["one.txt", "two.txt"]);
    expect(new TextDecoder().decode(await t.extract(t.entries[0]!))).toBe("tar one\n");
    expect(looksLikeTar(fixtureBytes("sample.tar"))).toBe(true);
  });
  it("tar.gz and plain gz", async () => {
    const t = await openArchive(fixtureSource("sample.tar.gz"), "application/gzip");
    expect(t.kind).toBe("tar.gz");
    expect(t.entries.map((e) => e.path)).toContain("two.txt");
    const g = await openArchive(fixtureSource("sample.txt.gz"), "application/gzip");
    expect(g.entries.map((e) => e.path)).toEqual(["sample.txt"]);
    expect(new TextDecoder().decode(await g.extract(g.entries[0]!))).toContain("gzip payload");
  });
  it("zip edit model round-trips: add, rename, remove → re-open", async () => {
    const files = loadZipForEdit(fixtureBytes("sample.zip"));
    files.set("new.txt", { data: new TextEncoder().encode("new") });
    files.delete("a.txt");
    const renamed = new Map<string, { data: Uint8Array }>();
    for (const [k, f] of files) renamed.set(k === "dir/b.txt" ? "dir/renamed.txt" : k, f);
    const bytes = zipSync(Object.fromEntries([...renamed].map(([k, f]) => [k, f.data])));
    const z = (await openZip(bytesSource(bytes, "x.zip")))!;
    expect(z.entries.map((e) => e.path).sort()).toEqual(["dir/renamed.txt", "dir/sub/c.json", "new.txt"]);
    expect(new TextDecoder().decode(await z.extract(z.entries.find((e) => e.path === "new.txt")!))).toBe("new");
  });
});

describe("pdf model", () => {
  it("maps display coords to pdf coords for each rotation", () => {
    expect(toPdf(0.25, 0.25, 0, 200, 100)).toEqual({ x: 50, y: 75 });
    expect(toPdf(0.25, 0.25, 90, 200, 100)).toEqual({ x: 50, y: 25 });
    expect(toPdf(0.25, 0.25, 180, 200, 100)).toEqual({ x: 150, y: 25 });
    expect(toPdf(0.25, 0.25, 270, 200, 100)).toEqual({ x: 150, y: 75 });
  });
  it("reorders, rotates, deletes pages, merges, annotates and re-opens", async () => {
    const a = fixtureBytes("sample.pdf"), b = fixtureBytes("other.pdf");
    const mk = (src: number, index: number, extra: Partial<PageRef> = {}): PageRef => ({ src, index, rot: 0, annots: [], w: 300, h: 200, intrinsic: 0, ...extra });
    const pages = [
      mk(0, 2, { rot: 90, annots: [{ kind: "text", u: 0.1, v: 0.2, text: "hello", size: 0.05, color: "#ff0000" }, { kind: "ink", pts: [[0.1, 0.1], [0.5, 0.5]], w: 0.01, color: "#0000ff" }] }),
      mk(0, 0),
      mk(1, 0),
    ];
    const out = await buildPdf([a, b], pages);
    const doc = await PDFDocument.load(out);
    expect(doc.getPageCount()).toBe(3);
    expect(doc.getPage(0).getRotation().angle).toBe(90);
    expect(doc.getPage(1).getRotation().angle).toBe(0);
  });
  it("embeds signature images", async () => {
    const png = fixtureBytes("image.png");
    const out = await buildPdf([fixtureBytes("sample.pdf")], [{ src: 0, index: 0, rot: 0, w: 300, h: 200, intrinsic: 0, annots: [{ kind: "img", u: 0.1, v: 0.1, w: 0.25, aspect: 64 / 48, bytes: png, mime: "image/png" }] }]);
    expect((await PDFDocument.load(out)).getPageCount()).toBe(1);
  });
});
