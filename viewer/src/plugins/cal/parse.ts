export interface Prop { name: string; params: Record<string, string>; value: string }
export interface Component { type: string; props: Prop[]; children: Component[] }

const unescape = (v: string) => v.replace(/\\n/gi, "\n").replace(/\\([,;\\])/g, "$1");

/** RFC 5545 / RFC 6350 line unfolding + component tree. */
export function parseCal(text: string): Component[] {
  const lines = text.replace(/\r\n?/g, "\n").replace(/\n[ \t]/g, "").split("\n").filter((l) => l.trim());
  const roots: Component[] = [];
  const stack: Component[] = [];
  for (const line of lines) {
    const colon = line.indexOf(":");
    if (colon < 0) continue;
    const head = line.slice(0, colon), value = line.slice(colon + 1);
    const [name, ...ps] = head.split(";");
    const params: Record<string, string> = {};
    for (const p of ps) { const [k, v] = p.split("="); if (k) params[k.toUpperCase()] = (v ?? "").replace(/^"|"$/g, ""); }
    const up = name!.toUpperCase();
    if (up === "BEGIN") { const c: Component = { type: value.trim().toUpperCase(), props: [], children: [] }; (stack[stack.length - 1]?.children ?? roots).push(c); stack.push(c); }
    else if (up === "END") stack.pop();
    else stack[stack.length - 1]?.props.push({ name: up, params, value: unescape(value) });
  }
  return roots;
}

export function fmtDate(v: string): string {
  const m = /^(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2})(\d{2})?(Z)?)?$/.exec(v);
  if (!m) return v;
  return m[4] ? `${m[1]}-${m[2]}-${m[3]} ${m[4]}:${m[5]}${m[7] ? " UTC" : ""}` : `${m[1]}-${m[2]}-${m[3]}`;
}
export const get = (c: Component, n: string) => c.props.find((p) => p.name === n)?.value;
export const all = (c: Component, n: string) => c.props.filter((p) => p.name === n);
