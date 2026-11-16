import type { FileInfo, FileSource } from "./types";

export interface Signature {
  mime: string;
  ext: string;
  offset: number;
  /** hex bytes, "??" = wildcard */
  magic: string;
}

const S = (mime: string, ext: string, magic: string, offset = 0): Signature => ({ mime, ext, offset, magic });

/** Hand-maintained magic-byte table. Order matters: first match wins; more specific first. */
export const SIGNATURES: Signature[] = [
  // images
  S("image/png", "png", "89 50 4E 47 0D 0A 1A 0A"),
  S("image/jpeg", "jpg", "FF D8 FF"),
  S("image/gif", "gif", "47 49 46 38 37 61"),
  S("image/gif", "gif", "47 49 46 38 39 61"),
  S("image/bmp", "bmp", "42 4D ?? ?? ?? ?? 00 00 00 00"),
  S("image/vnd.microsoft.icon", "ico", "00 00 01 00"),
  S("image/x-cursor", "cur", "00 00 02 00"),
  S("image/tiff", "tif", "49 49 2A 00"),
  S("image/tiff", "tif", "4D 4D 00 2A"),
  S("image/vnd.adobe.photoshop", "psd", "38 42 50 53"),
  S("image/webp", "webp", "52 49 46 46 ?? ?? ?? ?? 57 45 42 50"),
  S("image/avif", "avif", "?? ?? ?? ?? 66 74 79 70 61 76 69 66"),
  S("image/avif", "avif", "?? ?? ?? ?? 66 74 79 70 61 76 69 73"),
  S("image/heic", "heic", "?? ?? ?? ?? 66 74 79 70 68 65 69 63"),
  S("image/heic", "heic", "?? ?? ?? ?? 66 74 79 70 68 65 69 78"),
  S("image/heic", "heic", "?? ?? ?? ?? 66 74 79 70 68 65 76 63"),
  S("image/heif", "heif", "?? ?? ?? ?? 66 74 79 70 6D 69 66 31"),
  S("image/heif", "heif", "?? ?? ?? ?? 66 74 79 70 6D 73 66 31"),
  S("image/jp2", "jp2", "00 00 00 0C 6A 50 20 20 0D 0A 87 0A"),
  S("image/x-exr", "exr", "76 2F 31 01"),
  S("image/vnd.radiance", "hdr", "23 3F 52 41 44 49 41 4E 43 45"),
  S("image/x-canon-cr2", "cr2", "49 49 2A 00 10 00 00 00 43 52"),
  S("image/x-fuji-raf", "raf", "46 55 4A 49 46 49 4C 4D"),
  S("image/x-olympus-orf", "orf", "49 49 52 4F"),
  S("image/x-panasonic-rw2", "rw2", "49 49 55 00"),
  // video / audio (ISO base media first)
  S("video/quicktime", "mov", "?? ?? ?? ?? 66 74 79 70 71 74 20 20"),
  S("audio/mp4", "m4a", "?? ?? ?? ?? 66 74 79 70 4D 34 41 20"),
  S("video/3gpp", "3gp", "?? ?? ?? ?? 66 74 79 70 33 67 70"),
  S("video/mp4", "mp4", "?? ?? ?? ?? 66 74 79 70"),
  S("video/webm", "webm", "1A 45 DF A3"), // EBML; refined to mkv below
  S("video/x-msvideo", "avi", "52 49 46 46 ?? ?? ?? ?? 41 56 49 20"),
  S("audio/wav", "wav", "52 49 46 46 ?? ?? ?? ?? 57 41 56 45"),
  S("audio/mpeg", "mp3", "49 44 33"),
  S("audio/mpeg", "mp3", "FF FB"),
  S("audio/mpeg", "mp3", "FF F3"),
  S("audio/mpeg", "mp3", "FF F2"),
  S("audio/aac", "aac", "FF F1"),
  S("audio/aac", "aac", "FF F9"),
  S("audio/ogg", "ogg", "4F 67 67 53"),
  S("audio/flac", "flac", "66 4C 61 43"),
  S("audio/midi", "mid", "4D 54 68 64"),
  S("audio/aiff", "aiff", "46 4F 52 4D ?? ?? ?? ?? 41 49 46 46"),
  S("audio/x-ms-wma", "wma", "30 26 B2 75 8E 66 CF 11"),
  S("video/x-flv", "flv", "46 4C 56 01"),
  S("video/mpeg", "mpg", "00 00 01 BA"),
  // documents
  S("application/pdf", "pdf", "25 50 44 46 2D"),
  S("application/postscript", "ps", "25 21 50 53"),
  S("application/rtf", "rtf", "7B 5C 72 74 66"),
  S("application/x-ole-storage", "doc", "D0 CF 11 E0 A1 B1 1A E1"), // legacy Office (xls/doc/ppt), refined by ext
  S("application/vnd.ms-outlook", "msg", "D0 CF 11 E0 A1 B1 1A E1 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 3E 00"),
  // archives
  S("application/zip", "zip", "50 4B 03 04"),
  S("application/zip", "zip", "50 4B 05 06"),
  S("application/zip", "zip", "50 4B 07 08"),
  S("application/gzip", "gz", "1F 8B 08"),
  S("application/x-bzip2", "bz2", "42 5A 68"),
  S("application/x-xz", "xz", "FD 37 7A 58 5A 00"),
  S("application/x-7z-compressed", "7z", "37 7A BC AF 27 1C"),
  S("application/vnd.rar", "rar", "52 61 72 21 1A 07 01 00"),
  S("application/vnd.rar", "rar", "52 61 72 21 1A 07 00"),
  S("application/x-tar", "tar", "75 73 74 61 72", 257),
  S("application/zstd", "zst", "28 B5 2F FD"),
  S("application/x-lzip", "lz", "4C 5A 49 50"),
  S("application/x-lzh", "lzh", "?? ?? 2D 6C 68", 0),
  S("application/x-compress", "z", "1F 9D"),
  S("application/x-cpio", "cpio", "30 37 30 37 30 31"),
  S("application/x-archive", "a", "21 3C 61 72 63 68 3E 0A"),
  S("application/x-rpm", "rpm", "ED AB EE DB"),
  S("application/vnd.debian.binary-package", "deb", "21 3C 61 72 63 68 3E 0A 64 65 62 69 61 6E"),
  S("application/x-iso9660-image", "iso", "43 44 30 30 31", 0x8001),
  S("application/x-apple-diskimage", "dmg", "78 01 73 0D 62 62 60"),
  S("application/vnd.microsoft.portable-executable", "exe", "4D 5A"),
  S("application/x-msi", "msi", "D0 CF 11 E0 A1 B1 1A E1"),
  S("application/x-cab", "cab", "4D 53 43 46 00 00 00 00"),
  // executables / binaries
  S("application/x-elf", "elf", "7F 45 4C 46"),
  S("application/x-mach-binary", "macho", "CF FA ED FE"),
  S("application/x-mach-binary", "macho", "CE FA ED FE"),
  S("application/x-mach-binary", "macho", "CA FE BA BE"),
  S("application/wasm", "wasm", "00 61 73 6D"),
  S("application/java-vm", "class", "CA FE BA BE"),
  S("application/vnd.android.dex", "dex", "64 65 78 0A"),
  // fonts
  S("font/ttf", "ttf", "00 01 00 00 00"),
  S("font/otf", "otf", "4F 54 54 4F"),
  S("font/woff", "woff", "77 4F 46 46"),
  S("font/woff2", "woff2", "77 4F 46 32"),
  S("application/vnd.ms-fontobject", "eot", "?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? 4C 50", 0),
  S("font/collection", "ttc", "74 74 63 66"),
  // databases / data
  S("application/vnd.sqlite3", "sqlite", "53 51 4C 69 74 65 20 66 6F 72 6D 61 74 20 33 00"),
  S("application/vnd.apache.parquet", "parquet", "50 41 52 31"),
  S("application/x-hdf5", "h5", "89 48 44 46 0D 0A 1A 0A"),
  S("application/x-pcap", "pcap", "D4 C3 B2 A1"),
  S("application/x-pcap", "pcap", "A1 B2 C3 D4"),
  // 3d / misc
  S("model/gltf-binary", "glb", "67 6C 54 46"),
  S("model/stl", "stl", "73 6F 6C 69 64 20"), // ascii stl "solid " (text; also matched by text sniff)
  S("application/x-pem-file", "pem", "2D 2D 2D 2D 2D 42 45 47 49 4E"),
  S("application/pgp-encrypted", "pgp", "2D 2D 2D 2D 2D 42 45 47 49 4E 20 50 47 50"),
  S("application/x-shockwave-flash", "swf", "46 57 53"),
  S("application/x-bittorrent", "torrent", "64 38 3A 61 6E 6E 6F 75 6E 63 65"),
  S("application/x-dosexec-lnk", "lnk", "4C 00 00 00 01 14 02 00"),
  S("application/x-plist-binary", "plist", "62 70 6C 69 73 74 30 30"),
  S("application/x-git-pack", "pack", "50 41 43 4B"),
  S("application/vnd.tcpdump.pcapng", "pcapng", "0A 0D 0D 0A"),
  S("application/x-ole-storage", "doc", "0E 11 FC 0D D0 CF 11 0E"),
  S("application/vnd.adobe.flash.movie", "swf", "43 57 53"),
  S("image/x-xcf", "xcf", "67 69 6D 70 20 78 63 66"),
  S("image/vnd.dwg", "dwg", "41 43 31 30"),
  S("application/x-tar", "tar", "30 30 30 30 30 30 00", 257),
];

const compiled = SIGNATURES.map((s) => {
  const parts = s.magic.split(" ");
  const bytes = new Uint8Array(parts.map((p) => (p === "??" ? 0 : parseInt(p, 16))));
  const mask = new Uint8Array(parts.map((p) => (p === "??" ? 0 : 1)));
  return { s, bytes, mask };
}).filter((c) => c.s.mime !== "application/vnd.ms-fontobject"); // EOT magic needs wildcards over 34 bytes; keep ext-only

function matchAt(head: Uint8Array, bytes: Uint8Array, mask: Uint8Array, offset: number): boolean {
  if (head.length < offset + bytes.length) return false;
  for (let i = 0; i < bytes.length; i++) if (mask[i] && head[offset + i] !== bytes[i]) return false;
  return true;
}

export function matchMagic(head: Uint8Array): Signature | null {
  for (const c of compiled) if (matchAt(head, c.bytes, c.mask, c.s.offset)) return c.s;
  return null;
}

/** Extension fallback table: ext → [mime, isTextHint]. */
export const EXT_MIME: Record<string, string> = {
  txt: "text/plain", log: "text/plain", md: "text/markdown", markdown: "text/markdown", mdx: "text/markdown",
  json: "application/json", jsonc: "application/json", ndjson: "application/x-ndjson", jsonl: "application/x-ndjson",
  yaml: "application/yaml", yml: "application/yaml", toml: "application/toml", xml: "application/xml",
  csv: "text/csv", tsv: "text/tab-separated-values", html: "text/html", htm: "text/html", xhtml: "application/xhtml+xml",
  svg: "image/svg+xml", css: "text/css", js: "text/javascript", mjs: "text/javascript", ts: "text/typescript",
  tsx: "text/typescript", jsx: "text/javascript", ini: "text/plain", env: "text/plain", conf: "text/plain", cfg: "text/plain",
  png: "image/png", jpg: "image/jpeg", jpeg: "image/jpeg", gif: "image/gif", webp: "image/webp", avif: "image/avif",
  bmp: "image/bmp", ico: "image/vnd.microsoft.icon", tif: "image/tiff", tiff: "image/tiff", heic: "image/heic", heif: "image/heif",
  mp3: "audio/mpeg", m4a: "audio/mp4", ogg: "audio/ogg", oga: "audio/ogg", opus: "audio/ogg", flac: "audio/flac", wav: "audio/wav",
  aac: "audio/aac", mp4: "video/mp4", m4v: "video/mp4", webm: "video/webm", mkv: "video/x-matroska", mov: "video/quicktime",
  avi: "video/x-msvideo", pdf: "application/pdf",
  zip: "application/zip", tar: "application/x-tar", gz: "application/gzip", tgz: "application/gzip", bz2: "application/x-bzip2",
  xz: "application/x-xz", "7z": "application/x-7z-compressed", rar: "application/vnd.rar",
  docx: MIME_DOCX(), xlsx: MIME_XLSX(), pptx: MIME_PPTX(),
  doc: "application/msword", xls: "application/vnd.ms-excel", ppt: "application/vnd.ms-powerpoint",
  odt: "application/vnd.oasis.opendocument.text", ods: "application/vnd.oasis.opendocument.spreadsheet",
  odp: "application/vnd.oasis.opendocument.presentation", epub: "application/epub+zip",
  jar: "application/java-archive", apk: "application/vnd.android.package-archive",
  ttf: "font/ttf", otf: "font/otf", woff: "font/woff", woff2: "font/woff2",
  sqlite: "application/vnd.sqlite3", db: "application/vnd.sqlite3", sqlite3: "application/vnd.sqlite3",
  glb: "model/gltf-binary", gltf: "model/gltf+json", obj: "model/obj", stl: "model/stl", ply: "model/ply",
  geojson: "application/geo+json", gpx: "application/gpx+xml", kml: "application/vnd.google-earth.kml+xml",
  ics: "text/calendar", vcf: "text/vcard", ipynb: "application/x-ipynb+json", eml: "message/rfc822", mbox: "application/mbox",
  cr2: "image/x-canon-cr2", nef: "image/x-nikon-nef", arw: "image/x-sony-arw", dng: "image/x-adobe-dng",
  orf: "image/x-olympus-orf", rw2: "image/x-panasonic-rw2", raf: "image/x-fuji-raf",
  sh: "text/x-shellscript", py: "text/x-python", rb: "text/x-ruby", java: "text/x-java", kt: "text/x-kotlin",
  c: "text/x-c", h: "text/x-c", cpp: "text/x-c++", go: "text/x-go", rs: "text/x-rust", sql: "text/x-sql",
};
function MIME_DOCX() { return "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; }
function MIME_XLSX() { return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; }
function MIME_PPTX() { return "application/vnd.openxmlformats-officedocument.presentationml.presentation"; }

/** Canonical extension for a mime (reverse of the above, magic table first). */
export function extForMime(mime: string): string | undefined {
  const sig = SIGNATURES.find((s) => s.mime === mime);
  if (sig) return sig.ext;
  for (const [e, m] of Object.entries(EXT_MIME)) if (m === mime) return e;
  return undefined;
}

export function extOf(name: string): string {
  const base = name.split(/[\\/]/).pop() ?? name;
  const lower = base.toLowerCase();
  if (lower.endsWith(".tar.gz")) return "tgz";
  const i = lower.lastIndexOf(".");
  if (i <= 0) return i === 0 ? lower.slice(1) : "";
  return lower.slice(i + 1);
}

// ---------------------------------------------------------------- text sniff

export interface TextSniff { isText: boolean; encoding?: string }

export function sniffText(head: Uint8Array): TextSniff {
  if (head.length === 0) return { isText: true, encoding: "utf-8" };
  // BOMs
  if (head.length >= 3 && head[0] === 0xef && head[1] === 0xbb && head[2] === 0xbf) return { isText: true, encoding: "utf-8-bom" };
  if (head.length >= 2 && head[0] === 0xff && head[1] === 0xfe) return { isText: true, encoding: "utf-16le" };
  if (head.length >= 2 && head[0] === 0xfe && head[1] === 0xff) return { isText: true, encoding: "utf-16be" };
  let nul = 0, ctrl = 0;
  for (let i = 0; i < head.length; i++) {
    const b = head[i]!;
    if (b === 0) nul++;
    else if (b < 32 && b !== 9 && b !== 10 && b !== 13 && b !== 12 && b !== 27 && b !== 8) ctrl++;
  }
  if (nul > 0) return { isText: false };
  if (ctrl / head.length > 0.02) return { isText: false };
  try {
    new TextDecoder("utf-8", { fatal: true }).decode(head, { stream: true });
    return { isText: true, encoding: "utf-8" };
  } catch {
    // Not UTF-8: accept as legacy single-byte text only if mostly printable.
    let high = 0;
    for (const b of head) if (b >= 0x80) high++;
    if (high / head.length < 0.3) return { isText: true, encoding: "windows-1252" };
    return { isText: false };
  }
}

// ---------------------------------------------------------------- zip peek

export interface ZipEntryName { name: string; }

/** Read the zip central directory names via range reads (no inflate). Returns null if not a readable zip. */
export async function peekZipEntries(src: FileSource, limit = 4000): Promise<string[] | null> {
  if (src.size < 22) return null;
  const tailLen = Math.min(src.size, 65557);
  const tail = await src.read(src.size - tailLen, tailLen);
  let eocd = -1;
  for (let i = tail.length - 22; i >= 0; i--) {
    if (tail[i] === 0x50 && tail[i + 1] === 0x4b && tail[i + 2] === 5 && tail[i + 3] === 6) { eocd = i; break; }
  }
  if (eocd < 0) return null;
  const dv = new DataView(tail.buffer, tail.byteOffset, tail.byteLength);
  const count = dv.getUint16(eocd + 10, true);
  const cdSize = dv.getUint32(eocd + 12, true);
  const cdOff = dv.getUint32(eocd + 16, true);
  if (cdOff === 0xffffffff || cdSize > 16 << 20 || cdOff + cdSize > src.size) return null; // zip64 / absurd: not refined
  const cd = await src.read(cdOff, cdSize);
  const cdv = new DataView(cd.buffer, cd.byteOffset, cd.byteLength);
  const names: string[] = [];
  let p = 0;
  const dec = new TextDecoder();
  while (p + 46 <= cd.length && names.length < Math.min(count, limit) && cdv.getUint32(p, true) === 0x02014b50) {
    const nl = cdv.getUint16(p + 28, true), el = cdv.getUint16(p + 30, true), cl = cdv.getUint16(p + 32, true);
    names.push(dec.decode(cd.subarray(p + 46, p + 46 + nl)));
    p += 46 + nl + el + cl;
  }
  return names;
}

export function refineZip(names: string[], firstEntryData?: string, extHint = ""): { mime: string; ext: string } {
  const has = (n: string) => names.includes(n);
  const starts = (p: string) => names.some((n) => n.startsWith(p));
  if (has("AndroidManifest.xml") && (has("classes.dex") || has("resources.arsc"))) return { mime: "application/vnd.android.package-archive", ext: "apk" };
  if (has("[Content_Types].xml")) {
    if (starts("word/")) return { mime: MIME_DOCX(), ext: "docx" };
    if (starts("xl/")) return { mime: MIME_XLSX(), ext: "xlsx" };
    if (starts("ppt/")) return { mime: MIME_PPTX(), ext: "pptx" };
  }
  if (has("mimetype") || firstEntryData) {
    const m = firstEntryData ?? "";
    if (m.startsWith("application/epub+zip") || has("META-INF/container.xml") && names.some((n) => /\.x?html?$/.test(n))) return { mime: "application/epub+zip", ext: "epub" };
    if (m.startsWith("application/vnd.oasis.opendocument.text")) return { mime: "application/vnd.oasis.opendocument.text", ext: "odt" };
    if (m.startsWith("application/vnd.oasis.opendocument.spreadsheet")) return { mime: "application/vnd.oasis.opendocument.spreadsheet", ext: "ods" };
    if (m.startsWith("application/vnd.oasis.opendocument.presentation")) return { mime: "application/vnd.oasis.opendocument.presentation", ext: "odp" };
  }
  if (has("content.xml") && has("META-INF/manifest.xml") && ["odt", "ods", "odp"].includes(extHint)) {
    const m = { odt: "text", ods: "spreadsheet", odp: "presentation" }[extHint as "odt"];
    return { mime: `application/vnd.oasis.opendocument.${m}`, ext: extHint };
  }
  if (has("META-INF/MANIFEST.MF") || starts("META-INF/")) return { mime: "application/java-archive", ext: "jar" };
  return { mime: "application/zip", ext: "zip" };
}

// ---------------------------------------------------------------- main entry

export const HEAD_SIZE = 64 * 1024;

export interface Detection { info: FileInfo; head: Uint8Array }

/** Read just enough of `src` to build FileInfo. */
export async function detect(src: FileSource): Promise<Detection> {
  const head = await src.read(0, HEAD_SIZE);
  const info = await detectFromHead(src, head);
  return { info, head };
}

export async function detectFromHead(src: FileSource, head: Uint8Array): Promise<FileInfo> {
  const ext = extOf(src.name);
  const info: FileInfo = { name: src.name, ext, size: src.size, mimeHint: src.mimeHint, lastModified: src.lastModified, isText: false };
  let mime: string | undefined;
  let dext: string | undefined;

  const sig = matchMagic(head);
  if (sig) {
    mime = sig.mime; dext = sig.ext;
    if (mime === "application/zip") {
      try {
        const names = await peekZipEntries(src);
        if (names) {
          let first: string | undefined;
          if (names[0] === "mimetype" && head.length > 38) {
            const nl = head[26]! | (head[27]! << 8), el = head[28]! | (head[29]! << 8);
            first = new TextDecoder().decode(head.subarray(30 + nl + el, 30 + nl + el + 60));
          }
          const r = refineZip(names, first, ext);
          mime = r.mime; dext = r.ext;
        }
      } catch { /* keep plain zip */ }
    } else if (mime === "video/webm") {
      const s = new TextDecoder("latin1").decode(head.subarray(0, 64));
      if (s.includes("matroska")) { mime = "video/x-matroska"; dext = "mkv"; }
    } else if (mime === "application/x-ole-storage") {
      const m = ({ doc: "application/msword", xls: "application/vnd.ms-excel", ppt: "application/vnd.ms-powerpoint", msg: "application/vnd.ms-outlook" } as Record<string, string>)[ext];
      if (m) { mime = m; dext = ext; }
    } else if (mime === "application/java-vm" || mime === "application/x-mach-binary") {
      // CAFEBABE collides between java class and fat mach-o: version field < 45 ⇒ fat binary count
      if (head[0] === 0xca && head[4] === 0 && head[5] === 0 && head[7]! < 45 && head[6] === 0) { mime = "application/x-mach-binary"; dext = "macho"; }
      else if (head[0] === 0xca) { mime = "application/java-vm"; dext = "class"; }
    } else if (mime === "image/tiff" && ["cr2", "nef", "arw", "dng", "orf", "rw2"].includes(ext)) {
      mime = EXT_MIME[ext]; dext = ext;
    } else if (mime === "audio/mp4" || mime === "video/mp4") {
      if (ext === "m4v" || ext === "mp4") { mime = "video/mp4"; dext = ext; }
    } else if (mime === "audio/ogg" && ext === "opus") {
      dext = "opus";
    } else if (mime === "image/x-cursor") {
      dext = "cur";
    } else if (mime === "application/vnd.microsoft.portable-executable" && ext === "dll") {
      dext = "dll";
    }
  }
  // text sniff
  const sn = sniffText(head);
  if (sig && mime && !sn.isText) { /* binary signature */ }
  else if (sig && (mime === "model/stl" || mime === "application/x-pem-file" || mime === "application/pgp-encrypted")) {
    // textual signatures: let the sniff + extension decide below
    mime = undefined; dext = undefined;
  }
  if (!mime) {
    if (EXT_MIME[ext]) { mime = EXT_MIME[ext]; dext = ext; }
    else if (sn.isText && head.length > 0) { mime = "text/plain"; dext = "txt"; }
  } else if (sig === null && EXT_MIME[ext]) {
    mime = EXT_MIME[ext];
  }
  // An unreadable "text" claim over binary-signature bytes keeps the binary mime.
  info.detected = mime;
  info.detectedExt = dext;
  info.isText = sn.isText && (!sig || mime === undefined || mime.startsWith("text/") || /json|xml|yaml|toml|svg|calendar|vcard|x-ipynb|geo|gpx|kml|javascript|x-pem|obj|stl|ply/.test(mime ?? ""));
  if (info.isText) info.encoding = sn.encoding;
  // SVG sniff for extension-less files
  if (!mime && sn.isText) info.detected = "text/plain";
  if (info.isText && (info.detected === "text/plain" || !info.detected)) {
    const t = new TextDecoder("utf-8").decode(head.subarray(0, 512)).trimStart();
    if (/^<svg[\s>]|^<\?xml[^>]*\?>\s*<svg/i.test(t)) { info.detected = "image/svg+xml"; info.detectedExt = "svg"; }
    else if (/^<!doctype html|^<html[\s>]/i.test(t)) { info.detected = "text/html"; info.detectedExt = "html"; }
    else if (/^<\?xml/i.test(t)) { info.detected = "application/xml"; info.detectedExt = "xml"; }
    else if (/^\s*[{[]/.test(t) && ext === "") { info.detected = "application/json"; info.detectedExt = "json"; }
  }
  return info;
}

/** True if the two infos describe the same family of file (used by the save sanity check). */
export function sameFamily(a: FileInfo, b: FileInfo): boolean {
  if (a.isText && b.isText) return true;
  if (!a.detected || !b.detected) return a.isText === b.isText;
  if (a.detected === b.detected) return true;
  // Same extension-declared type, different sniff (e.g. xlsx vs generic zip): be strict on zip→non-zip.
  return false;
}
