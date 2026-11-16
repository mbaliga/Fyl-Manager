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

// ---- phase 3 fixtures
{
  // sqlite via sql.js
  const initSqlJs = (await import("sql.js")).default;
  const SQL = await initSqlJs();
  const db = new SQL.Database();
  db.run("CREATE TABLE people (id INTEGER PRIMARY KEY, name TEXT NOT NULL, age INTEGER, bio TEXT);");
  db.run("CREATE TABLE notes (id INTEGER PRIMARY KEY, person_id INTEGER REFERENCES people(id), body TEXT);");
  db.run("CREATE VIEW adults AS SELECT * FROM people WHERE age >= 18;");
  for (let i = 1; i <= 120; i++) db.run("INSERT INTO people (name, age, bio) VALUES (?, ?, ?)", [`Person ${i}`, 10 + (i % 60), i % 7 === 0 ? null : `bio of ${i}`]);
  db.run("INSERT INTO notes (person_id, body) VALUES (1, 'first note'), (2, 'second note')");
  out("data.sqlite", db.export());
  db.close();

  // epub (mimetype stored first)
  const xhtml = (t: string, body: string) => `<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>${t}</title></head><body><h1>${t}</h1>${body}</body></html>`;
  const png = new Uint8Array(readFileSync(join(FIX, "image.png")));
  out("book.epub", zipSync({
    mimetype: [strToU8("application/epub+zip"), { level: 0 }],
    "META-INF/container.xml": strToU8('<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>'),
    "OEBPS/content.opf": strToU8('<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Fixture Book</dc:title><dc:creator>Fylz Tests</dc:creator><dc:identifier id="id">x</dc:identifier></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/><item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/><item id="img" href="img/pic.png" media-type="image/png"/></manifest><spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>'),
    "OEBPS/nav.xhtml": strToU8('<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol><li><a href="ch1.xhtml">Chapter One</a></li><li><a href="text/ch2.xhtml">Chapter Two</a></li></ol></nav></body></html>'),
    "OEBPS/ch1.xhtml": strToU8(xhtml("Chapter One", '<p>It was a quiet start.</p><img src="img/pic.png" alt="pic"/><script>alert(1)</script>')),
    "OEBPS/text/ch2.xhtml": strToU8(xhtml("Chapter Two", "<p>The story continues.</p>")),
    "OEBPS/img/pic.png": png,
  }));

  // geo
  out("shapes.geojson", JSON.stringify({ type: "FeatureCollection", features: [
    { type: "Feature", properties: { name: "Home" }, geometry: { type: "Point", coordinates: [77.59, 12.97] } },
    { type: "Feature", properties: { name: "Route" }, geometry: { type: "LineString", coordinates: [[77.59, 12.97], [77.62, 13.0], [77.66, 13.02]] } },
    { type: "Feature", properties: { name: "Park" }, geometry: { type: "Polygon", coordinates: [[[77.60, 12.95], [77.63, 12.95], [77.63, 12.98], [77.60, 12.98], [77.60, 12.95]]] } },
  ] }));
  out("track.gpx", `<?xml version="1.0"?><gpx version="1.1" creator="fixture" xmlns="http://www.topografix.com/GPX/1/1"><wpt lat="12.97" lon="77.59"><name>Start</name></wpt><trk><name>Morning run</name><trkseg>${[0, 1, 2, 3, 4].map((i) => `<trkpt lat="${12.97 + i * 0.01}" lon="${77.59 + i * 0.01}"/>`).join("")}</trkseg></trk></gpx>\n`);
  out("places.kml", `<?xml version="1.0"?><kml xmlns="http://www.opengis.net/kml/2.2"><Document><Placemark><name>Cafe</name><Point><coordinates>77.6,12.9,0</coordinates></Point></Placemark><Placemark><name>Loop</name><LineString><coordinates>77.6,12.9 77.7,13.0 77.8,12.95</coordinates></LineString></Placemark></Document></kml>\n`);

  // calendar / contacts
  out("events.ics", "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nSUMMARY:Team sync\r\nDTSTART:20240115T093000Z\r\nDTEND:20240115T100000Z\r\nLOCATION:Room 4\r\nDESCRIPTION:Weekly sync\\nBring notes\r\nATTENDEE;CN=Ada Lovelace:mailto:ada@example.com\r\nEND:VEVENT\r\nBEGIN:VEVENT\r\nSUMMARY:Holiday\r\nDTSTART;VALUE=DATE:20240501\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n");
  out("people.vcf", "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Grace Hopper\r\nORG:Navy\r\nTEL;TYPE=CELL:+1 555 0100\r\nEMAIL:grace@example.com\r\nEND:VCARD\r\nBEGIN:VCARD\r\nVERSION:3.0\r\nFN:Alan Turing\r\nEMAIL:alan@example.com\r\nEND:VCARD\r\n");

  // notebook
  out("analysis.ipynb", JSON.stringify({ nbformat: 4, nbformat_minor: 5, metadata: { language_info: { name: "python" } }, cells: [
    { cell_type: "markdown", metadata: {}, source: ["# Notebook title\n", "Some *markdown*."] },
    { cell_type: "code", metadata: {}, execution_count: 1, source: ["print('hi')"], outputs: [{ output_type: "stream", name: "stdout", text: ["hi\n"] }, { output_type: "display_data", data: { "text/html": ["<b>bold html</b><script>alert(1)</script>"] }, metadata: {} }] },
    { cell_type: "code", metadata: {}, execution_count: 2, source: ["1/0"], outputs: [{ output_type: "error", ename: "ZeroDivisionError", evalue: "division by zero", traceback: ["\u001b[31mZeroDivisionError\u001b[0m: division by zero"] }] },
  ] }, null, 1));

  // mail
  const b64 = (s: string) => Buffer.from(s).toString("base64").replace(/(.{76})/g, "$1\r\n");
  const eml = ["From: =?UTF-8?B?" + Buffer.from("Ada Lovelace").toString("base64") + "?= <ada@example.com>", "To: bob@example.com", "Subject: =?UTF-8?Q?Hello_=E2=9C=93_fixture?=", "Date: Mon, 15 Jan 2024 09:30:00 +0000", "MIME-Version: 1.0", 'Content-Type: multipart/mixed; boundary="MIX"', "",
    "--MIX", 'Content-Type: multipart/alternative; boundary="ALT"', "", "--ALT", "Content-Type: text/plain; charset=utf-8", "", "Plain body text", "--ALT", "Content-Type: text/html; charset=utf-8", "Content-Transfer-Encoding: quoted-printable", "", "<p>HTML body =E2=9C=93 <b>bold</b></p><script>alert(1)</script><img src=3D\"http://evil.example/x.png\">", "--ALT--",
    "--MIX", 'Content-Type: text/plain; name="note.txt"', 'Content-Disposition: attachment; filename="note.txt"', "Content-Transfer-Encoding: base64", "", b64("attachment content\n"), "--MIX--", ""].join("\r\n");
  out("message.eml", eml);
  out("inbox.mbox", `From a@example.com Mon Jan 15 09:30:00 2024\nFrom: a@example.com\nSubject: First\n\nbody one\n\nFrom b@example.com Tue Jan 16 09:30:00 2024\nFrom: b@example.com\nSubject: Second\n\nbody two\n`);

  // jar / apk
  out("lib.jar", zipSync({ "META-INF/MANIFEST.MF": strToU8("Manifest-Version: 1.0\r\nMain-Class: com.example.Main\r\nImplementation-Version: 1.2.3\r\n"), "com/example/Main.class": new Uint8Array([0xca, 0xfe, 0xba, 0xbe, 0, 0, 0, 52]), "com/example/util/Helper.class": new Uint8Array([0xca, 0xfe, 0xba, 0xbe, 0, 0, 0, 52]) }));
  const { buildAxml } = await import("./axml");
  const manifest = buildAxml({ tag: "manifest", attrs: { package: "com.example.fixture", versionCode: 7, versionName: "1.2.3" }, children: [
    { tag: "uses-sdk", attrs: { minSdkVersion: 24, targetSdkVersion: 34 } }, { tag: "uses-permission", attrs: { name: "android.permission.INTERNET" } }, { tag: "uses-permission", attrs: { name: "android.permission.CAMERA" } },
    { tag: "application", children: [{ tag: "activity", attrs: { name: ".Main" } }, { tag: "service", attrs: { name: ".Sync" } }] }] });
  out("app.apk", zipSync({ "AndroidManifest.xml": manifest, "classes.dex": strToU8("dex\n035\0"), "resources.arsc": new Uint8Array(8), "lib/arm64-v8a/libfoo.so": new Uint8Array(4) }));

  // 3D
  out("cube.obj", "o cube\nv 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\nv 0 0 1\nv 1 0 1\nv 1 1 1\nv 0 1 1\nf 1 2 3\nf 1 3 4\nf 5 6 7\nf 5 7 8\nf 1 2 6\nf 1 6 5\nf 2 3 7\nf 2 7 6\nf 3 4 8\nf 3 8 7\nf 4 1 5\nf 4 5 8\n");
  out("pyramid.stl", "solid pyramid\n" + [[[0, 0, 0], [1, 0, 0], [0, 1, 0]], [[0, 0, 0], [1, 0, 0], [0, 0, 1]], [[1, 0, 0], [0, 1, 0], [0, 0, 1]], [[0, 0, 0], [0, 1, 0], [0, 0, 1]]].map((t) => `facet normal 0 0 1\n outer loop\n${t.map((v) => `  vertex ${v.join(" ")}`).join("\n")}\n endloop\nendfacet\n`).join("") + "endsolid pyramid\n");
  out("tri.ply", "ply\nformat ascii 1.0\nelement vertex 4\nproperty float x\nproperty float y\nproperty float z\nelement face 2\nproperty list uchar int vertex_indices\nend_header\n0 0 0\n1 0 0\n1 1 0\n0 1 0\n3 0 1 2\n3 0 2 3\n");
  {
    const bin = new Float32Array([0, 0, 0, 1, 0, 0, 0, 1, 0]);
    const json = JSON.stringify({ asset: { version: "2.0" }, scene: 0, scenes: [{ nodes: [0] }], nodes: [{ mesh: 0 }], meshes: [{ primitives: [{ attributes: { POSITION: 0 } }] }], accessors: [{ bufferView: 0, componentType: 5126, count: 3, type: "VEC3", min: [0, 0, 0], max: [1, 1, 0] }], bufferViews: [{ buffer: 0, byteLength: 36 }], buffers: [{ byteLength: 36 }] });
    const jb = Buffer.from(json + " ".repeat((4 - (json.length % 4)) % 4));
    const glb = Buffer.alloc(12 + 8 + jb.length + 8 + 36);
    glb.writeUInt32LE(0x46546c67, 0); glb.writeUInt32LE(2, 4); glb.writeUInt32LE(glb.length, 8);
    glb.writeUInt32LE(jb.length, 12); glb.writeUInt32LE(0x4e4f534a, 16); jb.copy(glb, 20);
    const o = 20 + jb.length; glb.writeUInt32LE(36, o); glb.writeUInt32LE(0x004e4942, o + 4); Buffer.from(bin.buffer).copy(glb, o + 8);
    out("tri.glb", glb);
  }

  // RAW-like: a minimal TIFF (DNG-style) with Make/Model/ExifIFD and an embedded JPEG preview
  {
    const tmp = join(process.env.TMPDIR ?? "/tmp", "fv-preview.jpg");
    execFileSync("ffmpeg", ["-y", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=size=480x320:rate=1:duration=1", "-frames:v", "1", "-q:v", "3", tmp]);
    const jpg = readFileSync(tmp);
    const buf = Buffer.alloc(120 + jpg.length);
    buf.write("II", 0); buf.writeUInt16LE(42, 2); buf.writeUInt32LE(8, 4);
    buf.writeUInt16LE(3, 8);
    const entry = (i: number, tag: number, type: number, count: number, val: number) => { const o = 10 + i * 12; buf.writeUInt16LE(tag, o); buf.writeUInt16LE(type, o + 2); buf.writeUInt32LE(count, o + 4); buf.writeUInt32LE(val, o + 8); };
    entry(0, 0x010f, 2, 13, 50); entry(1, 0x0110, 2, 14, 64); entry(2, 0x8769, 4, 1, 80);
    buf.writeUInt32LE(0, 46);
    buf.write("Fylz Cameras\0", 50); buf.write("FX-1 RAW Test\0", 64);
    buf.writeUInt16LE(1, 80); buf.writeUInt16LE(0x9003, 82); buf.writeUInt16LE(2, 84); buf.writeUInt32LE(20, 86); buf.writeUInt32LE(100, 90); buf.writeUInt32LE(0, 94);
    buf.write("2024:05:06 07:08:09\0", 100);
    jpg.copy(buf, 120);
    out("shot.dng", buf);
  }
}

// ---- media via ffmpeg when available (committed; regenerate only on demand)
if (process.env.FIX_MEDIA || !existsSync(join(FIX, "photo.webp"))) {
  try {
    const f = (args: string[]) => execFileSync("ffmpeg", ["-y", "-loglevel", "error", ...args], { stdio: "inherit" });
    f(["-f", "lavfi", "-i", "testsrc=size=96x64:rate=1:duration=1", "-frames:v", "1", "-q:v", "5", join(FIX, "photo.jpg")]);
    f(["-f", "lavfi", "-i", "testsrc=size=96x64:rate=1:duration=1", "-frames:v", "1", join(FIX, "photo.webp")]);
    f(["-f", "lavfi", "-i", "sine=frequency=440:duration=1", "-c:a", "libopus", "-b:a", "16k", join(FIX, "tone.opus")]);
    f(["-f", "lavfi", "-i", "sine=frequency=440:duration=1", "-c:a", "libmp3lame", "-b:a", "16k", "-metadata", "title=Fixture Tone", "-metadata", "artist=Fylz", join(FIX, "tone.mp3")]);
    f(["-f", "lavfi", "-i", "color=c=blue:s=64x48:d=1:r=10", "-f", "lavfi", "-i", "sine=duration=1", "-c:v", "libvpx", "-b:v", "50k", "-c:a", "libvorbis", "-shortest", join(FIX, "clip.webm")]);
  } catch (e) {
    console.warn("ffmpeg unavailable; skipping media fixtures", String(e));
  }
}
console.log("fixtures written to", FIX);
