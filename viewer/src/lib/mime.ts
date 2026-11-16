/** Minimal MIME / RFC 5322 parser for .eml and .mbox (no external deps). */
export interface Attachment { name: string; type: string; bytes: Uint8Array }
export interface Message {
  headers: Record<string, string>;
  text?: string;
  html?: string;
  attachments: Attachment[];
}

const dec = (b: Uint8Array, charset?: string) => {
  try { return new TextDecoder((charset ?? "utf-8").toLowerCase().replace(/^"|"$/g, "")).decode(b); } catch { return new TextDecoder().decode(b); }
};
const latin1 = (s: string) => Uint8Array.from(s, (c) => c.charCodeAt(0) & 0xff);

export function decodeQP(s: string): Uint8Array {
  const t = s.replace(/=\r?\n/g, "");
  const out: number[] = [];
  for (let i = 0; i < t.length; i++) {
    if (t[i] === "=" && /^[0-9A-Fa-f]{2}$/.test(t.slice(i + 1, i + 3))) { out.push(parseInt(t.slice(i + 1, i + 3), 16)); i += 2; }
    else out.push(t.charCodeAt(i) & 0xff);
  }
  return Uint8Array.from(out);
}
export function decodeB64(s: string): Uint8Array {
  try { const bin = atob(s.replace(/[^A-Za-z0-9+/=]/g, "")); return Uint8Array.from(bin, (c) => c.charCodeAt(0)); } catch { return new Uint8Array(0); }
}

/** RFC 2047 encoded-words in headers. */
export function decodeWords(v: string): string {
  return v.replace(/=\?([^?]+)\?([bBqQ])\?([^?]*)\?=/g, (_m, cs: string, enc: string, txt: string) =>
    dec(enc.toLowerCase() === "b" ? decodeB64(txt) : decodeQP(txt.replace(/_/g, " ")), cs)).replace(/\?=\s+=\?/g, "");
}

function splitHeaders(raw: string): { head: Record<string, string>; body: string } {
  const m = /\r?\n\r?\n/.exec(raw);
  const headText = m ? raw.slice(0, m.index) : raw;
  const body = m ? raw.slice(m.index + m[0].length) : "";
  const head: Record<string, string> = {};
  for (const line of headText.replace(/\r?\n[ \t]+/g, " ").split(/\r?\n/)) {
    const i = line.indexOf(":");
    if (i > 0) { const k = line.slice(0, i).toLowerCase(); if (!(k in head)) head[k] = line.slice(i + 1).trim(); }
  }
  return { head, body };
}

function params(v: string): { value: string; p: Record<string, string> } {
  const [value, ...rest] = v.split(";");
  const p: Record<string, string> = {};
  for (const r of rest) { const i = r.indexOf("="); if (i > 0) p[r.slice(0, i).trim().toLowerCase()] = r.slice(i + 1).trim().replace(/^"|"$/g, ""); }
  return { value: (value ?? "").trim().toLowerCase(), p };
}

function walk(raw: string, msg: Message, top: boolean): void {
  const { head, body } = splitHeaders(raw);
  if (top) for (const [k, v] of Object.entries(head)) msg.headers[k] = decodeWords(v);
  const ct = params(head["content-type"] ?? "text/plain; charset=us-ascii");
  const cte = (head["content-transfer-encoding"] ?? "7bit").trim().toLowerCase();
  const disp = params(head["content-disposition"] ?? "");
  if (ct.value.startsWith("multipart/") && ct.p.boundary) {
    const b = "--" + ct.p.boundary;
    const segs = body.split(b).slice(1);
    for (const s of segs) { if (s.startsWith("--")) break; walk(s.replace(/^\r?\n/, ""), msg, false); }
    return;
  }
  const bytes = cte === "base64" ? decodeB64(body) : cte === "quoted-printable" ? decodeQP(body) : latin1(body);
  const name = decodeWords(disp.p.filename ?? ct.p.name ?? "");
  if (ct.value === "message/rfc822" && !name) { walk(dec(bytes), msg, false); return; }
  if (disp.value === "attachment" || name || (!ct.value.startsWith("text/") && ct.value !== "message/rfc822")) {
    if (ct.value.startsWith("text/") && !name && disp.value !== "attachment") { /* fallthrough to body */ }
    else { msg.attachments.push({ name: name || `part-${msg.attachments.length + 1}`, type: ct.value, bytes }); return; }
  }
  if (ct.value === "text/html") msg.html = (msg.html ?? "") + dec(bytes, ct.p.charset);
  else if (ct.value === "text/plain" || ct.value.startsWith("text/")) msg.text = (msg.text ?? "") + dec(bytes, ct.p.charset);
  else msg.attachments.push({ name: name || "message.eml", type: ct.value, bytes });
}

export function parseMessage(bytes: Uint8Array): Message {
  const raw = Array.from({ length: Math.ceil(bytes.length / 0x8000) }, (_, i) => String.fromCharCode(...bytes.subarray(i * 0x8000, (i + 1) * 0x8000))).join("");
  const msg: Message = { headers: {}, attachments: [] };
  walk(raw, msg, true);
  return msg;
}

/** Split an mbox into raw message byte ranges ("From " separator lines). */
export function splitMbox(bytes: Uint8Array): Uint8Array[] {
  const text = new TextDecoder("latin1").decode(bytes);
  const idx: number[] = [];
  const re = /(^|\n)From [^\n]*\n/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(text))) idx.push(m.index + m[0].length);
  const starts = idx;
  const out: Uint8Array[] = [];
  starts.forEach((s, i) => {
    const sepLen = (text.slice(0, s).match(/(^|\n)From [^\n]*\n$/) ?? [""])[0].length;
    const e = i + 1 < starts.length ? starts[i + 1]! - ((text.slice(0, starts[i + 1]!).match(/(^|\n)From [^\n]*\n$/) ?? [""])[0].length) : text.length;
    void sepLen;
    out.push(bytes.subarray(s, e));
  });
  return out;
}
