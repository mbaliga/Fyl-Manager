import { el } from "../../lib/format";
import { VirtualList } from "../../lib/vlist";
import type { PluginModule, ViewContext, ViewHandle } from "../../core/types";

const SAMPLE = "The quick brown fox jumps over the lazy dog. 0123456789";
const PER_ROW = 16;

const mod: PluginModule = {
  async mount(ctx: ViewContext): Promise<ViewHandle> {
    const buf = await (await ctx.file.blob()).arrayBuffer();
    const family = `fv-font-${Math.random().toString(36).slice(2)}`;
    const face = new FontFace(family, buf);
    await face.load();
    document.fonts.add(face);

    // metadata + glyph list via opentype.js (not WOFF2)
    let meta: [string, string][] = [];
    let codepoints: number[] = [];
    if (ctx.info.detected !== "font/woff2" && ctx.info.detected !== "font/collection") {
      try {
        const opentype = (await import("opentype.js")).default;
        const f = opentype.parse(buf);
        const nm = f.names as unknown as Record<string, Record<string, Record<string, string>>>;
        const tables = [nm.windows, nm.unicode, nm.macintosh, nm as unknown as Record<string, Record<string, string>>].filter(Boolean);
        const n = (k: string) => { for (const t of tables) { const v = t![k]; const s = v?.en ?? (v ? Object.values(v)[0] : undefined); if (s && s.trim()) return s.trim(); } return undefined; };
        meta = ([["Family", n("fontFamily")], ["Style", n("fontSubfamily")], ["Full name", n("fullName")], ["Version", n("version")], ["Designer", n("designer")], ["Copyright", n("copyright")], ["Licence", n("license")],
          ["Glyphs", String(f.glyphs.length)], ["Units per em", String(f.unitsPerEm)], ["Ascender / descender", `${f.ascender} / ${f.descender}`]] as [string, string | undefined][]).filter((r): r is [string, string] => !!r[1]);
        const set = new Set<number>();
        for (let i = 0; i < Math.min(f.glyphs.length, 20000); i++) { const g = f.glyphs.get(i); if (g.unicode !== undefined) set.add(g.unicode); for (const u of g.unicodes ?? []) set.add(u); }
        codepoints = [...set].sort((a, b) => a - b);
      } catch (e) { ctx.host.log("warn", "opentype parse failed", String(e)); meta = [["Metadata", "could not be read"]]; }
    } else {
      meta = [["Metadata", ctx.info.detected === "font/woff2" ? "not available for WOFF2 (rendering only)" : "font collection"]];
    }
    if (!codepoints.length) { for (let c = 0x20; c < 0x250; c++) if (c < 0x7f || c > 0x9f) codepoints.push(c); }

    const root = el("div", { class: "fv-font" });
    const info = el("dl", { class: "fv-kv", "data-testid": "font-meta" });
    for (const [k, v] of meta) info.append(el("dt", { text: k }), el("dd", { text: v }));
    const sampleIn = el("input", { class: "fv-input", type: "text", value: SAMPLE, "aria-label": "Sample text", style: "flex:1;min-width:200px" });
    const size = el("input", { type: "range", min: "10", max: "120", value: "36", "aria-label": "Font size" });
    const bar = el("div", { class: "fv-toolbar2" }, sampleIn, size);
    const sample = el("div", { class: "fv-font-sample", "data-testid": "font-sample", text: SAMPLE });
    sample.style.fontFamily = `"${family}"`;
    const updateSample = () => { sample.textContent = sampleIn.value; sample.style.fontSize = `${size.value}px`; };
    sampleIn.oninput = updateSample; size.oninput = updateSample; updateSample();
    const glyphHost = el("div", { class: "fv-pane", style: "min-height:240px" });
    root.append(info, bar, sample, el("div", { class: "fv-muted", style: "padding:4px 12px", text: `${codepoints.length.toLocaleString()} characters` }), glyphHost);
    ctx.container.append(root);
    const list = new VirtualList(glyphHost, {
      rowHeight: 44, count: Math.ceil(codepoints.length / PER_ROW), label: "Glyph grid", className: "fv-glyphlist",
      render(i, row) {
        row.replaceChildren(...codepoints.slice(i * PER_ROW, (i + 1) * PER_ROW).map((cp) => {
          const s = el("span", { class: "fv-glyph", title: `U+${cp.toString(16).toUpperCase().padStart(4, "0")}`, text: String.fromCodePoint(cp) });
          s.style.fontFamily = `"${family}"`;
          return s;
        }));
      },
    });
    return { isDirty: () => false, onDirtyChange: () => {}, unmount() { list.destroy(); document.fonts.delete(face); root.remove(); } };
  },
};
export default mod;
