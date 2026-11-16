import { gunzipSync, inflateSync, unzipSync, strFromU8 } from "fflate";
import type { FileSource } from "../core/types";

export interface ArchiveEntry {
  path: string;
  size: number;
  packed?: number;
  mtime?: number;
  dir: boolean;
  /** reader-private */
  ref?: unknown;
}

export interface ArchiveReader {
  kind: string;
  entries: ArchiveEntry[];
  extract(e: ArchiveEntry): Promise<Uint8Array>;
  close(): void;
}

export const MAX_EXTRACT = 512 * 1024 * 1024;
export const MAX_ENTRIES = 200_000;

const dec = new TextDecoder();

// ------------------------------------------------------------------ zip (central directory, range reads)

export async function openZip(src: FileSource): Promise<ArchiveReader | null> {
  if (src.size < 22) return null;
  const tailLen = Math.min(src.size, 65557);
  const tail = await src.read(src.size - tailLen, tailLen);
  let eocd = -1;
  for (let i = tail.length - 22; i >= 0; i--) if (tail[i] === 0x50 && tail[i + 1] === 0x4b && tail[i + 2] === 5 && tail[i + 3] === 6) { eocd = i; break; }
  if (eocd < 0) return null;
  const dv = new DataView(tail.buffer, tail.byteOffset, tail.byteLength);
  const count = dv.getUint16(eocd + 10, true), cdSize = dv.getUint32(eocd + 12, true), cdOff = dv.getUint32(eocd + 16, true);
  if (cdOff === 0xffffffff || count === 0xffff || cdOff + cdSize > src.size) return null; // zip64 → libarchive fallback
  const cd = await src.read(cdOff, cdSize);
  const v = new DataView(cd.buffer, cd.byteOffset, cd.byteLength);
  const entries: ArchiveEntry[] = [];
  let p = 0;
  while (p + 46 <= cd.length && entries.length < MAX_ENTRIES && v.getUint32(p, true) === 0x02014b50) {
    const flags = v.getUint16(p + 8, true), method = v.getUint16(p + 10, true);
    const time = v.getUint16(p + 12, true), date = v.getUint16(p + 14, true);
    const csize = v.getUint32(p + 20, true), size = v.getUint32(p + 24, true);
    const nl = v.getUint16(p + 28, true), el = v.getUint16(p + 30, true), cl = v.getUint16(p + 32, true);
    const off = v.getUint32(p + 42, true);
    const utf8 = (flags & 0x800) !== 0;
    const nameBytes = cd.subarray(p + 46, p + 46 + nl);
    const path = utf8 ? dec.decode(nameBytes) : new TextDecoder("latin1").decode(nameBytes);
    const mtime = new Date(((date >> 9) & 0x7f) + 1980, ((date >> 5) & 0xf) - 1, date & 0x1f, (time >> 11) & 0x1f, (time >> 5) & 0x3f, (time & 0x1f) * 2).getTime();
    entries.push({ path, size, packed: csize, mtime, dir: path.endsWith("/"), ref: { method, off, csize, encrypted: (flags & 1) !== 0 } });
    p += 46 + nl + el + cl;
  }
  return {
    kind: "zip",
    entries,
    async extract(e) {
      const r = e.ref as { method: number; off: number; csize: number; encrypted: boolean };
      if (r.encrypted) throw new Error("This entry is encrypted");
      if (e.size > MAX_EXTRACT) throw new Error("Entry is too large to extract in the browser");
      const lh = await src.read(r.off, 30);
      const l = new DataView(lh.buffer, lh.byteOffset, 30);
      const data = await src.read(r.off + 30 + l.getUint16(26, true) + l.getUint16(28, true), r.csize);
      if (r.method === 0) return data;
      if (r.method === 8) return inflateSync(data, { out: new Uint8Array(e.size) });
      throw new Error(`Unsupported zip compression method ${r.method}`);
    },
    close() {},
  };
}

// ------------------------------------------------------------------ tar

interface Rd { size: number; read(o: number, n: number): Promise<Uint8Array> }

function octal(b: Uint8Array): number {
  if (b[0]! & 0x80) { let n = 0; for (let i = 1; i < b.length; i++) n = n * 256 + b[i]!; return n; } // base-256
  const s = dec.decode(b).replace(/\0.*$/, "").trim();
  return s ? parseInt(s, 8) : 0;
}
const cstr = (b: Uint8Array) => { const i = b.indexOf(0); return dec.decode(i < 0 ? b : b.subarray(0, i)); };

export function looksLikeTar(head: Uint8Array): boolean {
  if (head.length < 512) return false;
  let sum = 0;
  for (let i = 0; i < 512; i++) sum += i >= 148 && i < 156 ? 32 : head[i]!;
  const stored = octal(head.subarray(148, 156));
  return sum === stored && stored > 0;
}

export async function openTar(rd: Rd, kind = "tar"): Promise<ArchiveReader> {
  const entries: ArchiveEntry[] = [];
  let off = 0, longName: string | null = null, paxPath: string | null = null;
  while (off + 512 <= rd.size && entries.length < MAX_ENTRIES) {
    const h = await rd.read(off, 512);
    if (h.every((b) => b === 0)) break;
    const size = octal(h.subarray(124, 136));
    const type = String.fromCharCode(h[156] || 48);
    let name = cstr(h.subarray(0, 100));
    const prefix = dec.decode(h.subarray(257, 262)) === "ustar" ? cstr(h.subarray(345, 500)) : "";
    if (prefix) name = `${prefix}/${name}`;
    const dataOff = off + 512;
    const next = dataOff + Math.ceil(size / 512) * 512;
    if (type === "L") { longName = cstr(await rd.read(dataOff, size)); }
    else if (type === "x") {
      const t = dec.decode(await rd.read(dataOff, size));
      const m = /\d+ path=([^\n]*)\n/.exec(t);
      if (m) paxPath = m[1]!;
    } else if (type === "g" || type === "K") { /* skip */ }
    else {
      const path = paxPath ?? longName ?? name;
      longName = paxPath = null;
      if (type === "0" || type === "\0" || type === "5" || type === "7") {
        entries.push({ path, size: type === "5" ? 0 : size, mtime: octal(h.subarray(136, 148)) * 1000, dir: type === "5" || path.endsWith("/"), ref: dataOff });
      }
    }
    off = next;
  }
  return {
    kind,
    entries,
    async extract(e) {
      if (e.size > MAX_EXTRACT) throw new Error("Entry is too large to extract in the browser");
      return rd.read(e.ref as number, e.size);
    },
    close() {},
  };
}

// ------------------------------------------------------------------ gzip

export async function openGzip(src: FileSource, name: string): Promise<ArchiveReader> {
  if (src.size > 256 * 1024 * 1024) throw new Error("gzip file too large to unpack in the browser (limit 256 MB)");
  const raw = gunzipSync(new Uint8Array(await (await src.blob()).arrayBuffer()));
  if (looksLikeTar(raw.subarray(0, 512))) return openTar({ size: raw.length, read: async (o, n) => raw.subarray(o, o + n) }, "tar.gz");
  const inner = name.replace(/\.(gz|gzip)$/i, "") || "data";
  return { kind: "gzip", entries: [{ path: inner, size: raw.length, packed: src.size, dir: false }], extract: async () => raw, close() {} };
}

// ------------------------------------------------------------------ libarchive (7z, rar, bz2, xz, …)

type LA = { getFilesArray(): Promise<{ file: { name: string; size: number; lastModified?: number; extract?: () => Promise<File> } | File; path: string }[]>; close(): Promise<void> };

export async function openLibarchive(src: FileSource): Promise<ArchiveReader> {
  if (src.size > 512 * 1024 * 1024) throw new Error("Archive too large for the in-browser extractor (limit 512 MB)");
  const { Archive } = await import("libarchive.js");
  Archive.init({ getWorker: () => new Worker(new URL("./libarchive/worker-bundle.js", document.baseURI), { type: "module" }) } as never);
  const blob = await src.blob();
  const ar = (await Archive.open(new File([blob], src.name))) as unknown as LA;
  const list = await ar.getFilesArray();
  const entries: ArchiveEntry[] = list.slice(0, MAX_ENTRIES).map((f) => {
    const file = f.file as { name: string; size: number; lastModified?: number };
    return { path: `${f.path}${file.name}`, size: file.size ?? 0, mtime: file.lastModified ? (file.lastModified < 1e11 ? file.lastModified * 1000 : file.lastModified) : undefined, dir: false, ref: f.file };
  });
  return {
    kind: "libarchive",
    entries,
    async extract(e) {
      if (e.size > MAX_EXTRACT) throw new Error("Entry is too large to extract in the browser");
      const f = e.ref as { extract?: () => Promise<File> };
      if (!f.extract) throw new Error("Entry cannot be extracted");
      return new Uint8Array(await (await f.extract()).arrayBuffer());
    },
    close() { void ar.close(); },
  };
}

// ------------------------------------------------------------------ dispatcher

export async function openArchive(src: FileSource, detected: string | undefined): Promise<ArchiveReader> {
  const head = await src.read(0, 512);
  const zipLike = detected !== undefined && (detected === "application/zip" || detected.includes("openxml") || detected.includes("opendocument") || detected.includes("epub") || detected.includes("java-archive") || detected.includes("android.package"));
  if (zipLike) { const z = await openZip(src); if (z) return z; }
  if (detected === "application/gzip") return openGzip(src, src.name);
  if (detected === "application/x-tar" || looksLikeTar(head)) return openTar(src);
  return openLibarchive(src);
}

/** Zip editing model: whole zip in memory (bounded by the plugin's edit limit). */
export function loadZipForEdit(bytes: Uint8Array): Map<string, { data: Uint8Array; mtime?: number }> {
  const files = unzipSync(bytes);
  const names = peekNamesOrder(bytes);
  const m = new Map<string, { data: Uint8Array }>();
  for (const n of names.length ? names : Object.keys(files)) if (n in files) m.set(n, { data: files[n]! });
  return m;
}
function peekNamesOrder(bytes: Uint8Array): string[] {
  const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let e = -1;
  for (let i = bytes.length - 22; i >= Math.max(0, bytes.length - 65557); i--) if (dv.getUint32(i, true) === 0x06054b50) { e = i; break; }
  if (e < 0) return [];
  const n = dv.getUint16(e + 10, true);
  let p = dv.getUint32(e + 16, true);
  const out: string[] = [];
  for (let i = 0; i < n && dv.getUint32(p, true) === 0x02014b50; i++) {
    const nl = dv.getUint16(p + 28, true), el = dv.getUint16(p + 30, true), cl = dv.getUint16(p + 32, true);
    out.push(strFromU8(bytes.subarray(p + 46, p + 46 + nl)));
    p += 46 + nl + el + cl;
  }
  return out;
}
