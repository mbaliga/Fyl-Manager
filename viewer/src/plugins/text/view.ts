import { el, formatBytes } from "../../lib/format";
import { createTextCore, MAX_EDIT_BYTES } from "./core";
import type { PluginModule, ViewContext, ViewHandle } from "../../core/types";

/** Read-only chunked viewer for files above the editor limit. Uses range reads only. */
async function mountChunked(ctx: ViewContext): Promise<ViewHandle> {
  const { file } = ctx;
  const WIN = 256 * 1024;
  const root = el("div", { class: "fv-text fv-chunked" });
  const bar = el("div", { class: "fv-toolbar2" });
  const prev = el("button", { class: "fv-btn", type: "button", text: "◀ Previous" });
  const next = el("button", { class: "fv-btn", type: "button", text: "Next ▶" });
  const slider = el("input", { type: "range", min: "0", max: "1000", value: "0", "aria-label": "Position" });
  const label = el("span", { class: "fv-muted" });
  const pre = el("pre", { class: "fv-chunk-pre", tabindex: "0" });
  bar.append(prev, slider, next, label);
  root.append(bar, pre, el("div", { class: "fv-statusbar" }, el("span", { text: `${formatBytes(file.size)} · read-only chunked viewer (above ${formatBytes(MAX_EDIT_BYTES)})` })));
  ctx.container.append(root);
  let start = 0, used = 0;
  const show = async (offset: number) => {
    let buf = await file.read(offset, WIN);
    let skip = 0;
    if (offset > 0) { const i = buf.indexOf(10); if (i >= 0) skip = i + 1; }
    const lastNl = buf.lastIndexOf(10);
    const end = buf.length === WIN && lastNl > skip ? lastNl + 1 : buf.length;
    buf = buf.subarray(skip, end);
    start = offset + skip; used = buf.length;
    pre.textContent = new TextDecoder().decode(buf);
    pre.scrollTop = 0;
    label.textContent = `${start.toLocaleString()}–${(start + used).toLocaleString()} of ${file.size.toLocaleString()}`;
    slider.value = String(Math.round((start / file.size) * 1000));
  };
  prev.onclick = () => void show(Math.max(0, start - WIN));
  next.onclick = () => { if (start + used < file.size) void show(start + used); };
  slider.oninput = () => void show(Math.floor((Number(slider.value) / 1000) * Math.max(0, file.size - 1)));
  await show(0);
  return { isDirty: () => false, onDirtyChange: () => {}, unmount: () => root.remove() };
}

const mod: PluginModule = {
  async mount(ctx: ViewContext): Promise<ViewHandle> {
    if (ctx.file.size > MAX_EDIT_BYTES) return mountChunked(ctx);
    const core = await createTextCore(ctx);
    return core.handle;
  },
};
export default mod;
