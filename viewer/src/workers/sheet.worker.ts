/// <reference lib="webworker" />
import * as XLSX from "xlsx";

export interface SheetData { name: string; fmt: string[][]; raw?: (string | number | boolean | null)[][]; truncated: boolean }
const MAX_ROWS = 500_000;

self.onmessage = (e: MessageEvent<{ buf: ArrayBuffer; withRaw: boolean }>) => {
  try {
    const wb = XLSX.read(new Uint8Array(e.data.buf), { type: "array", cellDates: true, dense: true });
    const sheets: SheetData[] = wb.SheetNames.map((name) => {
      const ws = wb.Sheets[name]!;
      const range = ws["!ref"] ? XLSX.utils.decode_range(ws["!ref"]) : null;
      const truncated = !!range && range.e.r - range.s.r + 1 > MAX_ROWS;
      const opts = { header: 1 as const, blankrows: true, defval: "", ...(truncated ? { range: { s: range!.s, e: { r: range!.s.r + MAX_ROWS - 1, c: range!.e.c } } } : {}) };
      const fmt = XLSX.utils.sheet_to_json<string[]>(ws, { ...opts, raw: false });
      const d: SheetData = { name, fmt: fmt.map((r) => r.map((c) => (c == null ? "" : String(c)))), truncated };
      if (e.data.withRaw) d.raw = XLSX.utils.sheet_to_json<(string | number | boolean | null)[]>(ws, { ...opts, raw: true, cellDates: false } as never);
      return d;
    });
    (self as unknown as Worker).postMessage({ ok: true, sheets });
  } catch (err) {
    (self as unknown as Worker).postMessage({ ok: false, error: String(err) });
  }
};
