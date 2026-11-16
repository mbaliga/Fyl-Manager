/**
 * Generates small test fixtures into test/fixtures/. Run: npm run fixtures
 * Everything here is generated programmatically; only fonts/media come from tools (see bottom).
 */
import { mkdirSync, writeFileSync, existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { execFileSync } from "node:child_process";
import { zipSync, gzipSync, strToU8 } from "fflate";
import { PNG } from "pngjs";
import { PDFDocument, StandardFonts } from "pdf-lib";
import * as XLSX from "xlsx";

const here = dirname(fileURLToPath(import.meta.url));
export const FIX = join(here, "..", "test", "fixtures");
mkdirSync(FIX, { recursive: true });
const out = (name: string, data: string | Uint8Array) => {
  mkdirSync(dirname(join(FIX, name)), { recursive: true });
  writeFileSync(join(FIX, name), data);
};

// ---- text family
out("hello.txt", "Hello, Fylz Viewer.\nSecond line with unicode: héllo — 你好.\n");
out("crlf.txt", "line one\r\nline two\r\nline three\r\n");
out("latin1.txt", Buffer.from("caf\xe9 cr\xe8me br\xfbl\xe9e\n", "latin1"));
out("utf16le.txt", Buffer.concat([Buffer.from([0xff, 0xfe]), Buffer.from("UTF-16 text file\nsecond line\n", "utf16le")]));
out("code.ts", `export function add(a: number, b: number): number {\n  return a + b; // adds\n}\n`);
out("notes.md", "# Title\n\nSome *emphasis* and a [link](https://example.com).\n\n- one\n- two\n\n```js\nconsole.log(1)\n```\n\n<script>alert(1)</script>\n");
out("data.json", JSON.stringify({ name: "fylz", list: [1, 2, 3], nested: { ok: true, nothing: null } }, null, 2) + "\n");
out("broken.json", '{ "a": 1, "b": }\n');
out("config.yaml", "name: fylz\nitems:\n  - a\n  - b\nnested:\n  key: value\n");
out("config.toml", '[package]\nname = "fylz"\nversion = "1.0"\n');
out("log.ndjson", '{"a":1}\n{"a":2}\n{"a":3}\n');
out("feed.xml", '<?xml version="1.0"?>\n<root><item id="1">one</item><item id="2">two</item></root>\n');
out("page.html", "<!doctype html><html><body><h1>Hi</h1><script>alert(1)</script><p onclick=\"x()\">p</p></body></html>\n");
let csv = "id,name,note\n";
for (let i = 1; i <= 300; i++) csv += `${i},name ${i},"quoted, with comma ${i}"\n`;
out("table.csv", csv);
out("table.tsv", "a\tb\tc\n1\t2\t3\n4\t5\t6\n");
out("quoted.csv", 'a,b\n"he said ""hi""","multi\nline"\n');

// ---- binary / unknown
const bin = new Uint8Array(4096);
for (let i = 0; i < bin.length; i++) bin[i] = (i * 31 + 7) & 0xff;
bin.set(strToU8("FYLZ-MARKER-STRING"), 100);
out("unknown.bin", bin);

// ---- images
{
  const png = new PNG({ width: 64, height: 48 });
  for (let y = 0; y < 48; y++) for (let x = 0; x < 64; x++) {
    const i = (y * 64 + x) * 4;
    png.data[i] = x * 4; png.data[i + 1] = y * 5; png.data[i + 2] = 160; png.data[i + 3] = 255;
  }
  out("image.png", PNG.sync.write(png));
  out("vector.svg", '<svg xmlns="http://www.w3.org/2000/svg" width="80" height="60" viewBox="0 0 80 60"><rect width="80" height="60" fill="#4a90d9"/><circle cx="40" cy="30" r="18" fill="#fff"/><script>alert(1)</script></svg>\n');
  const bmp = Buffer.alloc(54 + 4 * 4 * 3);
  bmp.write("BM", 0); bmp.writeUInt32LE(bmp.length, 2); bmp.writeUInt32LE(54, 10); bmp.writeUInt32LE(40, 14);
  bmp.writeInt32LE(4, 18); bmp.writeInt32LE(4, 22); bmp.writeUInt16LE(1, 26); bmp.writeUInt16LE(24, 28);
  for (let i = 54; i < bmp.length; i++) bmp[i] = (i * 13) & 0xff;
  out("image.bmp", bmp);
  out("anim.gif", Buffer.from("R0lGODlhAQABAIAAAP///wAAACH5BAEAAAAALAAAAAABAAEAAAICRAEAOw==", "base64"));
}

// ---- audio (wav by hand)
{
  const sr = 8000, n = sr / 2;
  const buf = Buffer.alloc(44 + n * 2);
  buf.write("RIFF", 0); buf.writeUInt32LE(36 + n * 2, 4); buf.write("WAVEfmt ", 8); buf.writeUInt32LE(16, 16);
  buf.writeUInt16LE(1, 20); buf.writeUInt16LE(1, 22); buf.writeUInt32LE(sr, 24); buf.writeUInt32LE(sr * 2, 28);
  buf.writeUInt16LE(2, 32); buf.writeUInt16LE(16, 34); buf.write("data", 36); buf.writeUInt32LE(n * 2, 40);
  for (let i = 0; i < n; i++) buf.writeInt16LE(Math.round(Math.sin((i / sr) * 2 * Math.PI * 440) * 12000), 44 + i * 2);
  out("tone.wav", buf);
}

// ---- archives
const files = { "a.txt": strToU8("alpha\n"), "dir/b.txt": strToU8("bravo\n"), "dir/sub/c.json": strToU8('{"c":3}\n') };
out("sample.zip", zipSync(files));
out("sample.txt.gz", gzipSync(strToU8("gzip payload text\n".repeat(20))));
{
  // minimal tar built by hand
  const blocks: Buffer[] = [];
  const add = (name: string, content: string) => {
    const h = Buffer.alloc(512);
    h.write(name, 0); h.write("0000644\0", 100); h.write("0000000\0", 108); h.write("0000000\0", 116);
    h.write(content.length.toString(8).padStart(11, "0") + "\0", 124); h.write("00000000000\0", 136);
    h.write("        ", 148); h.write("0", 156); h.write("ustar\0", 257); h.write("00", 263);
    let sum = 0; for (const b of h) sum += b;
    h.write(sum.toString(8).padStart(6, "0") + "\0 ", 148);
    blocks.push(h);
    const body = Buffer.alloc(Math.ceil(content.length / 512) * 512);
    body.write(content); blocks.push(body);
  };
  add("one.txt", "tar one\n"); add("two.txt", "tar two\n");
  blocks.push(Buffer.alloc(1024));
  out("sample.tar", Buffer.concat(blocks));
}

// ---- Office-like zips (docx/pptx/xlsx)
{
  const ct = (extra: string) => `<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>${extra}</Types>`;
  const rels = (target: string, type: string) => `<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/${type}" Target="${target}"/></Relationships>`;
  const docx = zipSync({
    "[Content_Types].xml": strToU8(ct('<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>')),
    "_rels/.rels": strToU8(rels("word/document.xml", "officeDocument")),
    "word/document.xml": strToU8(`<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Fixture Heading</w:t></w:r></w:p><w:p><w:r><w:t>Hello from a generated docx.</w:t></w:r></w:p><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Bold text</w:t></w:r></w:p></w:body></w:document>`),
  });
  out("doc.docx", docx);
  const slide = (n: number, t: string) => `<?xml version="1.0"?><p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>${t}</a:t></a:r></a:p><a:p><a:r><a:t>Slide ${n} body</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>`;
  out("deck.pptx", zipSync({
    "[Content_Types].xml": strToU8(ct('<Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>')),
    "_rels/.rels": strToU8(rels("ppt/presentation.xml", "officeDocument")),
    "ppt/presentation.xml": strToU8('<?xml version="1.0"?><p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"/>'),
    "ppt/slides/slide1.xml": strToU8(slide(1, "First slide title")),
    "ppt/slides/slide2.xml": strToU8(slide(2, "Second slide title")),
    "ppt/slides/slide10.xml": strToU8(slide(10, "Tenth slide title")),
  }));
}

// ---- xlsx via SheetJS
{
  const wb = XLSX.utils.book_new();
  const rows: (string | number)[][] = [["id", "name", "score"]];
  for (let i = 1; i <= 50; i++) rows.push([i, `row ${i}`, i * 1.5]);
  XLSX.utils.book_append_sheet(wb, XLSX.utils.aoa_to_sheet(rows), "Data");
  XLSX.utils.book_append_sheet(wb, XLSX.utils.aoa_to_sheet([["a", "b"], ["x", "y"]]), "Second");
  out("book.xlsx", XLSX.write(wb, { type: "buffer", bookType: "xlsx" }) as Buffer);
  out("book.ods", XLSX.write(wb, { type: "buffer", bookType: "ods" }) as Buffer);
}

// ---- PDF via pdf-lib
{
  const pdf = await PDFDocument.create();
  const font = await pdf.embedFont(StandardFonts.Helvetica);
  for (let i = 1; i <= 3; i++) {
    const page = pdf.addPage([300, 200]);
    page.drawText(`Fylz test page ${i}`, { x: 30, y: 100, size: 20, font });
  }
  out("sample.pdf", await pdf.save());
  const p2 = await PDFDocument.create();
  p2.addPage([300, 200]).drawText("Other document", { x: 30, y: 100, size: 20, font: await p2.embedFont(StandardFonts.Helvetica) });
  out("other.pdf", await p2.save());
}

// ---- more archives: tar.gz / tar.bz2 / tar.xz (sample.7z is committed, made once with py7zr: libarchive's own 7z writer produced unreadable output)
{
  const tarPath = join(FIX, "sample.tar");
  out("sample.tar.gz", gzipSync(new Uint8Array(readFileSync(tarPath))));
  for (const [tool, ext] of [["bzip2", "bz2"], ["xz", "xz"]] as const) {
    writeFileSync(join(FIX, `sample.tar.${ext}`), execFileSync(tool, ["-c", tarPath], { maxBuffer: 1 << 24 }));
  }
}

// ---- font (generated with opentype.js)
{
  const opentype = (await import("opentype.js")).default;
  const box = (w: number) => { const p = new opentype.Path(); p.moveTo(50, 0); p.lineTo(50, 700); p.lineTo(w - 50, 700); p.lineTo(w - 50, 0); p.close(); return p; };
  const tri = () => { const p = new opentype.Path(); p.moveTo(50, 0); p.lineTo(300, 700); p.lineTo(550, 0); p.close(); return p; };
  const glyphs = [
    new opentype.Glyph({ name: ".notdef", unicode: 0, advanceWidth: 600, path: box(600) }),
    new opentype.Glyph({ name: "space", unicode: 32, advanceWidth: 300, path: new opentype.Path() }),
    new opentype.Glyph({ name: "A", unicode: 65, advanceWidth: 600, path: tri() }),
    new opentype.Glyph({ name: "B", unicode: 66, advanceWidth: 600, path: box(600) }),
    new opentype.Glyph({ name: "C", unicode: 67, advanceWidth: 600, path: box(500) }),
  ];
  const font = new opentype.Font({ familyName: "FylzTest", styleName: "Regular", unitsPerEm: 1000, ascender: 800, descender: -200, glyphs });
  out("test.otf", new Uint8Array(font.toArrayBuffer()));
}

// ---- media via ffmpeg when available (committed; regenerate only on demand)
if (process.env.FIX_MEDIA || !existsSync(join(FIX, "clip.webm"))) {
  try {
    const f = (args: string[]) => execFileSync("ffmpeg", ["-y", "-loglevel", "error", ...args], { stdio: "inherit" });
    f(["-f", "lavfi", "-i", "sine=frequency=440:duration=1", "-c:a", "libopus", "-b:a", "16k", join(FIX, "tone.opus")]);
    f(["-f", "lavfi", "-i", "sine=frequency=440:duration=1", "-c:a", "libmp3lame", "-b:a", "16k", "-metadata", "title=Fixture Tone", "-metadata", "artist=Fylz", join(FIX, "tone.mp3")]);
    f(["-f", "lavfi", "-i", "color=c=blue:s=64x48:d=1:r=10", "-f", "lavfi", "-i", "sine=duration=1", "-c:v", "libvpx", "-b:v", "50k", "-c:a", "libvorbis", "-shortest", join(FIX, "clip.webm")]);
  } catch (e) {
    console.warn("ffmpeg unavailable; skipping media fixtures", String(e));
  }
}
console.log("fixtures written to", FIX);
