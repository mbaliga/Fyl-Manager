export type Encoding = "utf-8" | "utf-8-bom" | "utf-16le" | "utf-16be" | "windows-1252";
export const ENCODINGS: Encoding[] = ["utf-8", "utf-8-bom", "utf-16le", "utf-16be", "windows-1252"];

export function normalizeEncoding(e?: string): Encoding {
  return (ENCODINGS as string[]).includes(e ?? "") ? (e as Encoding) : "utf-8";
}

export function decode(bytes: Uint8Array, enc: Encoding): string {
  switch (enc) {
    case "utf-8-bom": return new TextDecoder("utf-8").decode(bytes.subarray(3));
    case "utf-16le": return new TextDecoder("utf-16le").decode(bytes.subarray(bytes[0] === 0xff ? 2 : 0));
    case "utf-16be": return new TextDecoder("utf-16be").decode(bytes.subarray(bytes[0] === 0xfe ? 2 : 0));
    case "windows-1252": return new TextDecoder("windows-1252").decode(bytes);
    default: return new TextDecoder("utf-8").decode(bytes);
  }
}

const CP1252: Record<number, number> = {
  0x20ac: 0x80, 0x201a: 0x82, 0x0192: 0x83, 0x201e: 0x84, 0x2026: 0x85, 0x2020: 0x86, 0x2021: 0x87, 0x02c6: 0x88,
  0x2030: 0x89, 0x0160: 0x8a, 0x2039: 0x8b, 0x0152: 0x8c, 0x017d: 0x8e, 0x2018: 0x91, 0x2019: 0x92, 0x201c: 0x93,
  0x201d: 0x94, 0x2022: 0x95, 0x2013: 0x96, 0x2014: 0x97, 0x02dc: 0x98, 0x2122: 0x99, 0x0161: 0x9a, 0x203a: 0x9b,
  0x0153: 0x9c, 0x017e: 0x9e, 0x0178: 0x9f,
};

export function encode(text: string, enc: Encoding): Uint8Array {
  switch (enc) {
    case "utf-8-bom": {
      const b = new TextEncoder().encode(text);
      const out = new Uint8Array(b.length + 3);
      out.set([0xef, 0xbb, 0xbf]);
      out.set(b, 3);
      return out;
    }
    case "utf-16le":
    case "utf-16be": {
      const le = enc === "utf-16le";
      const out = new Uint8Array(2 + text.length * 2);
      out[0] = le ? 0xff : 0xfe;
      out[1] = le ? 0xfe : 0xff;
      const dv = new DataView(out.buffer);
      for (let i = 0; i < text.length; i++) dv.setUint16(2 + i * 2, text.charCodeAt(i), le);
      return out;
    }
    case "windows-1252": {
      const out = new Uint8Array(text.length);
      let n = 0;
      for (const ch of text) {
        const c = ch.codePointAt(0)!;
        out[n++] = c < 0x80 || (c >= 0xa0 && c < 0x100) ? c : CP1252[c] ?? 0x3f;
      }
      return out.subarray(0, n);
    }
    default: return new TextEncoder().encode(text);
  }
}

export type Eol = "\n" | "\r\n" | "\r";

export function detectEol(text: string): Eol {
  let crlf = 0, lf = 0, cr = 0;
  const lim = Math.min(text.length, 1 << 20);
  for (let i = 0; i < lim; i++) {
    const c = text.charCodeAt(i);
    if (c === 13) { if (text.charCodeAt(i + 1) === 10) { crlf++; i++; } else cr++; }
    else if (c === 10) lf++;
  }
  if (crlf > lf && crlf >= cr) return "\r\n";
  if (cr > lf && cr > crlf) return "\r";
  return "\n";
}

export function normalizeEol(text: string): string {
  return text.replace(/\r\n?/g, "\n");
}

export function applyEol(text: string, eol: Eol): string {
  return eol === "\n" ? text : text.replace(/\n/g, eol);
}
