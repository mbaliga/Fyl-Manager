import type { FileSource } from "../../core/types";

export interface Embedded { offset: number; length: number }

/** Find the end of a JPEG starting at `start` within `buf`. Returns the length or 0. */
export function jpegLength(buf: Uint8Array, start: number): number {
  let p = start + 2;
  while (p + 2 <= buf.length) {
    if (buf[p] !== 0xff) return 0;
    const m = buf[p + 1]!;
    if (m === 0xd9) return p + 2 - start;
    if (m === 0xff) { p++; continue; }
    if ((m >= 0xd0 && m <= 0xd7) || m === 0x01) { p += 2; continue; }
    if (p + 4 > buf.length) return 0;
    const len = (buf[p + 2]! << 8) | buf[p + 3]!;
    p += 2 + len;
    if (m === 0xda) { // entropy-coded data: scan to the next real marker
      while (p + 1 < buf.length) {
        if (buf[p] === 0xff && buf[p + 1] !== 0x00 && !(buf[p + 1]! >= 0xd0 && buf[p + 1]! <= 0xd7)) break;
        p++;
      }
    }
  }
  return 0;
}

/** Scan a (possibly huge) file for embedded JPEG streams; returns them largest first. */
export async function findEmbeddedJpegs(src: FileSource, signal?: AbortSignal): Promise<Embedded[]> {
  const found: Embedded[] = [];
  const CH = 4 * 1024 * 1024;
  let skipUntil = 0;
  for (let off = 0; off < src.size; off += CH) {
    if (signal?.aborted) break;
    const buf = await src.read(off, CH + 4);
    for (let i = 0; i + 3 < buf.length; i++) {
      if (buf[i] === 0xff && buf[i + 1] === 0xd8 && buf[i + 2] === 0xff && off + i >= skipUntil && (buf[i + 3]! === 0xe0 || buf[i + 3]! === 0xe1 || buf[i + 3]! === 0xdb || buf[i + 3]! === 0xee)) {
        const abs = off + i;
        const region = await src.read(abs, Math.min(48 * 1024 * 1024, src.size - abs));
        const len = jpegLength(region, 0);
        if (len > 4096) { found.push({ offset: abs, length: len }); skipUntil = abs + len; }
      }
    }
  }
  return found.sort((a, b) => b.length - a.length);
}
