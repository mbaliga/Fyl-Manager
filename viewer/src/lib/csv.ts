export interface CsvMeta {
  delimiter: string;
  eol: "\n" | "\r\n";
  quoteAll: boolean;
  trailingNewline: boolean;
}
export interface CsvParsed { rows: string[][]; meta: CsvMeta }

export function detectDelimiter(text: string): string {
  const cands = [",", "\t", ";", "|"];
  const lines: string[] = [];
  let cur = "", inQ = false;
  for (let i = 0; i < text.length && lines.length < 8; i++) {
    const c = text[i]!;
    if (c === '"') inQ = !inQ;
    if (!inQ && (c === "\n")) { lines.push(cur); cur = ""; } else cur += c;
  }
  if (cur) lines.push(cur);
  let best = ",", bestScore = -1;
  for (const d of cands) {
    const counts = lines.map((l) => { let n = 0, q = false; for (const ch of l) { if (ch === '"') q = !q; else if (ch === d && !q) n++; } return n; });
    if (!counts.length || counts[0] === 0) continue;
    const consistent = counts.every((c) => c === counts[0]);
    const score = counts[0]! * (consistent ? 10 : 1);
    if (score > bestScore) { bestScore = score; best = d; }
  }
  return best;
}

export function parseCsv(text: string, delimiter?: string): CsvParsed {
  const d = delimiter ?? detectDelimiter(text);
  const rows: string[][] = [];
  let row: string[] = [];
  let field = "";
  let inQ = false;
  let quotedFields = 0, totalFields = 0;
  let quotedThis = false;
  let crlf = 0, lf = 0;
  const endField = () => { row.push(field); totalFields++; if (quotedThis) quotedFields++; field = ""; quotedThis = false; };
  const endRow = () => { endField(); rows.push(row); row = []; };
  const n = text.length;
  for (let i = 0; i < n; i++) {
    const c = text[i]!;
    if (inQ) {
      if (c === '"') { if (text[i + 1] === '"') { field += '"'; i++; } else inQ = false; }
      else field += c;
    } else if (c === '"' && field === "") { inQ = true; quotedThis = true; }
    else if (c === d) endField();
    else if (c === "\r") { if (text[i + 1] === "\n") { i++; crlf++; } endRow(); }
    else if (c === "\n") { lf++; endRow(); }
    else field += c;
  }
  const trailingNewline = n > 0 && (text[n - 1] === "\n" || text[n - 1] === "\r");
  if (field !== "" || row.length > 0 || quotedThis) endRow();
  if (rows.length === 0) rows.push([""]);
  return { rows, meta: { delimiter: d, eol: crlf > lf ? "\r\n" : "\n", quoteAll: totalFields > 0 && quotedFields === totalFields, trailingNewline } };
}

export function serializeCsv(rows: string[][], meta: CsvMeta): string {
  const d = meta.delimiter;
  const q = (f: string) => {
    const need = meta.quoteAll || f.includes(d) || f.includes('"') || f.includes("\n") || f.includes("\r") || /^\s|\s$/.test(f) && f !== "";
    return need ? `"${f.replace(/"/g, '""')}"` : f;
  };
  const body = rows.map((r) => r.map(q).join(d)).join(meta.eol);
  return meta.trailingNewline ? body + meta.eol : body;
}
