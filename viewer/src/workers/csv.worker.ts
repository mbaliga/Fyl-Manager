/// <reference lib="webworker" />
import { parseCsv } from "../lib/csv";
self.onmessage = (e: MessageEvent<{ text: string; delimiter?: string }>) => {
  try { (self as unknown as Worker).postMessage({ ok: true, result: parseCsv(e.data.text, e.data.delimiter) }); }
  catch (err) { (self as unknown as Worker).postMessage({ ok: false, error: String(err) }); }
};
