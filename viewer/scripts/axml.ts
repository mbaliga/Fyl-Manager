/** Builds a tiny valid Android binary XML document (for the APK fixture). */
export interface ANode { tag: string; attrs?: Record<string, string | number | boolean>; children?: ANode[] }

export function buildAxml(root: ANode): Uint8Array {
  const strings: string[] = [];
  const si = (s: string) => { let i = strings.indexOf(s); if (i < 0) { strings.push(s); i = strings.length - 1; } return i; };
  const NS = "http://schemas.android.com/apk/res/android";
  const nodes: Uint8Array[] = [];
  const u32 = (v: number) => { const b = new Uint8Array(4); new DataView(b.buffer).setUint32(0, v >>> 0, true); return [...b]; };
  const u16 = (v: number) => [v & 0xff, (v >> 8) & 0xff];
  const chunk = (type: number, header: number[], body: number[]) => { const size = 8 + header.length + body.length; return Uint8Array.from([...u16(type), ...u16(8 + header.length), ...u32(size), ...header, ...body]); };
  const walk = (n: ANode) => {
    const attrs = Object.entries(n.attrs ?? {});
    const body: number[] = [...u32(0xffffffff), ...u32(si(n.tag)), ...u16(0x14), ...u16(0x14), ...u16(attrs.length), ...u16(0), ...u16(0), ...u16(0)];
    for (const [k, v] of attrs) {
      const isStr = typeof v === "string";
      body.push(...u32(si(NS)), ...u32(si(k)), ...u32(isStr ? si(v) : 0xffffffff), ...u16(8), 0, isStr ? 0x03 : typeof v === "boolean" ? 0x12 : 0x10, ...u32(isStr ? si(v) : typeof v === "boolean" ? (v ? 0xffffffff : 0) : v));
    }
    nodes.push(chunk(0x0102, [...u32(1), ...u32(0xffffffff)], body));
    (n.children ?? []).forEach(walk);
    nodes.push(chunk(0x0103, [...u32(1), ...u32(0xffffffff)], [...u32(0xffffffff), ...u32(si(n.tag))]));
  };
  walk(root);
  // string pool (UTF-16)
  const enc: number[][] = strings.map((s) => { const out = [...u16(s.length)]; for (const ch of s) out.push(...u16(ch.charCodeAt(0))); out.push(0, 0); return out; });
  const offsets: number[] = []; let o = 0; for (const e of enc) { offsets.push(o); o += e.length; }
  while (o % 4) { enc.push([0]); o++; }
  const strData = enc.flat();
  const headerLen = 20; // after the 8-byte chunk header: count, styles, flags, stringsStart, stylesStart
  const pool = chunk(0x0001, [...u32(strings.length), ...u32(0), ...u32(0), ...u32(8 + headerLen + strings.length * 4), ...u32(0)], [...offsets.flatMap(u32), ...strData]);
  const all = [pool, ...nodes];
  const total = 8 + all.reduce((a, c) => a + c.length, 0);
  return Uint8Array.from([...u16(0x0003), ...u16(8), ...u32(total), ...all.flatMap((c) => [...c])]);
}
