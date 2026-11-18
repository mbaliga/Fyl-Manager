export interface FoundString { offset: number; text: string; enc: "ascii" | "utf16" }

export interface ScanState { start: number; text: string }
export const newScanState = (): ScanState => ({ start: -1, text: "" });

const printable = (b: number) => (b >= 0x20 && b < 0x7f) || b === 9;

/**
 * Incremental ASCII scanner. Feed consecutive chunks; the state carries a string across chunk boundaries.
 * Call with `flush=true` on the final chunk.
 */
export function scanAscii(chunk: Uint8Array, base: number, st: ScanState, minLen: number, flush = false): FoundString[] {
  const out: FoundString[] = [];
  const end = () => {
    if (st.text.length >= minLen) out.push({ offset: st.start, text: st.text, enc: "ascii" });
    st.text = ""; st.start = -1;
  };
  for (let i = 0; i < chunk.length; i++) {
    const b = chunk[i]!;
    if (printable(b)) {
      if (st.start < 0) st.start = base + i;
      st.text += String.fromCharCode(b);
      if (st.text.length >= 4096) end();
    } else if (st.start >= 0) end();
  }
  if (flush) end();
  return out;
}

/**
 * UTF-16LE (ASCII-range code units) scan over one buffer. Strings straddling a chunk boundary may be
 * split in two; that is an accepted limitation (see DECISIONS.md).
 */
export function scanUtf16(chunk: Uint8Array, base: number, minLen: number): FoundString[] {
  const out: FoundString[] = [];
  for (const phase of [0, 1]) {
    let start = -1, s = "";
    for (let i = phase; i + 1 < chunk.length; i += 2) {
      const lo = chunk[i]!, hi = chunk[i + 1]!;
      if (hi === 0 && printable(lo)) { if (start < 0) start = base + i; s += String.fromCharCode(lo); }
      else { if (s.length >= minLen) out.push({ offset: start, text: s, enc: "utf16" }); start = -1; s = ""; }
    }
    if (s.length >= minLen) out.push({ offset: start, text: s, enc: "utf16" });
  }
  return out.sort((a, b) => a.offset - b.offset);
}
