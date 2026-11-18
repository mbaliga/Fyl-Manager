import { buildSrcdoc } from "../../core/security";
import { el } from "../../lib/format";
import type { PluginModule, ViewContext, ViewHandle } from "../../core/types";

const mod: PluginModule = {
  async mount(ctx: ViewContext): Promise<ViewHandle> {
    const mammoth = (await import("mammoth/mammoth.browser")).default;
    const buf = await (await ctx.file.blob()).arrayBuffer();
    const { value, messages } = await mammoth.convertToHtml({ arrayBuffer: buf });
    const root = el("div", { class: "fv-docx" });
    const banner = el("div", { class: "fv-banner fv-banner-notice", role: "note", "data-testid": "docx-note" });
    banner.textContent = `Converted to HTML for reading — layout and some formatting may differ.${messages.length ? ` (${messages.length} conversion warnings)` : ""}`;
    if (ctx.host.openExternal) {
      const b = el("button", { class: "fv-btn", type: "button", text: "Open in another app" });
      b.onclick = () => ctx.host.openExternal?.();
      banner.append(b);
    }
    const frame = el("iframe", { class: "fv-sandbox", sandbox: "", title: "Document preview", referrerpolicy: "no-referrer" });
    let theme = ctx.theme;
    const render = () => { frame.srcdoc = buildSrcdoc(value, theme, { css: "body{max-width:820px;margin:16px auto}" }); };
    render();
    root.append(banner, frame);
    ctx.container.append(root);
    return { isDirty: () => false, onDirtyChange: () => {}, setTheme(t) { theme = t; render(); }, unmount: () => root.remove() };
  },
};
export default mod;
