import { describe, expect, it } from "vitest";
import { applyEol, decode, detectEol, encode, normalizeEol, ENCODINGS } from "../src/lib/textcodec";
import { newScanState, scanAscii, scanUtf16 } from "../src/lib/strings";

describe("textcodec", () => {
  const sample = "héllo — wörld\nline2";
  for (const enc of ENCODINGS) {
    it(`round-trips ${enc}`, () => {
      const s = enc === "windows-1252" ? "café €\nok" : sample;
      expect(decode(encode(s, enc), enc)).toBe(s);
    });
  }
  it("detects and applies EOLs", () => {
    expect(detectEol("a\r\nb\r\n")).toBe("\r\n");
    expect(detectEol("a\nb\n")).toBe("\n");
    expect(detectEol("a\rb\r")).toBe("\r");
    expect(applyEol(normalizeEol("a\r\nb\rc\n"), "\r\n")).toBe("a\r\nb\r\nc\r\n");
  });
});

describe("strings", () => {
  it("finds ascii strings across chunk boundaries", () => {
    const st = newScanState();
    const a = new TextEncoder().encode("\x01\x02hello wor");
    const b = new TextEncoder().encode("ld\x00\x00abc\x00done!");
    const out = [...scanAscii(a, 0, st, 4), ...scanAscii(b, a.length, st, 4, true)];
    expect(out.map((s) => s.text)).toEqual(["hello world", "done!"]);
    expect(out[0]!.offset).toBe(2);
  });
  it("finds utf-16le strings", () => {
    const u = new Uint8Array([0, 0, 0x48, 0, 0x65, 0, 0x6c, 0, 0x6c, 0, 0x6f, 0, 0xff]);
    expect(scanUtf16(u, 0, 4).map((s) => [s.text, s.offset])).toEqual([["Hello", 2]]);
  });
});
