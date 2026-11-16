/** Minimal Android binary XML (AXML) reader: enough to summarise an APK's AndroidManifest.xml. */
export interface AxmlAttr { ns?: string; name: string; value: string | number | boolean; type: "string" | "int" | "bool" | "ref" | "other" }
export interface AxmlElement { name: string; attrs: AxmlAttr[]; children: AxmlElement[] }

export function parseAxml(buf: Uint8Array): AxmlElement {
  const dv = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
  if (dv.getUint16(0, true) !== 0x0003) throw new Error("Not a binary XML file");
  let pos = dv.getUint16(2, true);
  let strings: string[] = [];
  const root: AxmlElement = { name: "#root", attrs: [], children: [] };
  const stack: AxmlElement[] = [root];
  const str = (i: number) => (i === 0xffffffff ? undefined : strings[i]);

  while (pos + 8 <= buf.length) {
    const type = dv.getUint16(pos, true), hdr = dv.getUint16(pos + 2, true), size = dv.getUint32(pos + 4, true);
    if (size < 8) break;
    if (type === 0x0001) {
      const count = dv.getUint32(pos + 8, true), flags = dv.getUint32(pos + 16, true), start = dv.getUint32(pos + 20, true);
      const utf8 = (flags & 0x100) !== 0;
      strings = [];
      for (let i = 0; i < count; i++) {
        let o = pos + start + dv.getUint32(pos + hdr + i * 4, true);
        if (utf8) {
          let n = buf[o++]!; if (n & 0x80) o++;               // utf16 length (skip)
          let bl = buf[o++]!; if (bl & 0x80) { bl = ((bl & 0x7f) << 8) | buf[o++]!; }
          n = bl; strings.push(new TextDecoder().decode(buf.subarray(o, o + n)));
        } else {
          let n = dv.getUint16(o, true); o += 2;
          if (n & 0x8000) { n = ((n & 0x7fff) << 16) | dv.getUint16(o, true); o += 2; }
          strings.push(new TextDecoder("utf-16le").decode(buf.subarray(o, o + n * 2)));
        }
      }
    } else if (type === 0x0102) {
      const b = pos + 16;
      const name = str(dv.getUint32(b + 4, true)) ?? "?";
      const attrStart = dv.getUint16(b + 8, true), attrSize = dv.getUint16(b + 10, true), attrCount = dv.getUint16(b + 12, true);
      const el: AxmlElement = { name, attrs: [], children: [] };
      for (let i = 0; i < attrCount; i++) {
        const a = b + attrStart + i * attrSize;
        const ns = str(dv.getUint32(a, true)), an = str(dv.getUint32(a + 4, true)) ?? "?";
        const raw = dv.getUint32(a + 8, true), dt = buf[a + 15]!, data = dv.getUint32(a + 16, true);
        let value: AxmlAttr["value"], t: AxmlAttr["type"] = "other";
        if (dt === 0x03) { value = str(raw) ?? str(data) ?? ""; t = "string"; }
        else if (dt === 0x10 || dt === 0x11) { value = dt === 0x11 ? data : dv.getInt32(a + 16, true); t = "int"; }
        else if (dt === 0x12) { value = data !== 0; t = "bool"; }
        else if (dt === 0x01) { value = `@0x${data.toString(16)}`; t = "ref"; }
        else value = data;
        el.attrs.push({ ns, name: an, value, type: t });
      }
      stack[stack.length - 1]!.children.push(el);
      stack.push(el);
    } else if (type === 0x0103) stack.pop();
    pos += size;
  }
  return root.children[0] ?? root;
}

export const attr = (e: AxmlElement, n: string) => e.attrs.find((a) => a.name === n)?.value;
export function findAll(e: AxmlElement, n: string): AxmlElement[] { return [...(e.name === n ? [e] : []), ...e.children.flatMap((c) => findAll(c, n))]; }
