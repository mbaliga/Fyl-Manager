import { sandboxedSvg } from "../../core/security";
import { el, formatBytes } from "../../lib/format";
import type { Command, PluginModule, ViewContext, ViewHandle } from "../../core/types";

const EDITABLE = new Set(["image/png", "image/jpeg", "image/webp"]);

async function readExif(blob: Blob): Promise<Record<string, unknown> | null> {
  try {
    const exifr = (await import("exifr")).default;
    const out = await exifr.parse(blob, { tiff: true, exif: true, gps: true, xmp: false, icc: false, translateValues: true, reviveValues: true });
    return out && Object.keys(out).length ? (out as Record<string, unknown>) : null;
  } catch { return null; }
}

const mod: PluginModule = {
  async mount(ctx: ViewContext): Promise<ViewHandle> {
    const { info } = ctx;
    const blob = await ctx.file.blob();
    if (ctx.mode === "edit" && EDITABLE.has(info.detected ?? "")) {
      const { mountImageEditor } = await import("./editor");
      return mountImageEditor(ctx, blob);
    }

    const root = el("div", { class: "fv-image" });
    const bar = el("div", { class: "fv-toolbar2" });
    const stageEl = el("div", { class: "fv-image-stage" });
    const meta = el("aside", { class: "fv-image-meta", hidden: true, "data-testid": "exif" });
    const status = el("div", { class: "fv-statusbar", role: "status" });
    root.append(bar, el("div", { class: "fv-image-main" }, stageEl, meta), status);
    ctx.container.append(root);

    let url: string | undefined;
    let frame: HTMLIFrameElement | null = null;
    let img: HTMLImageElement | null = null;
    let zoom: "fit" | number = "fit";
    const applyZoom = () => {
      if (!img) return;
      if (zoom === "fit") { img.style.width = ""; img.style.maxWidth = "100%"; img.style.maxHeight = "100%"; }
      else { img.style.maxWidth = "none"; img.style.maxHeight = "none"; img.style.width = `${img.naturalWidth * zoom}px`; }
      stageEl.classList.toggle("fv-image-fit", zoom === "fit");
    };
    const mkBtn = (label: string, fn: () => void, aria: string) => { const b = el("button", { class: "fv-btn", type: "button", text: label, "aria-label": aria }); b.onclick = fn; return b; };

    if (info.detected === "image/svg+xml") {
      const text = await blob.text();
      frame = sandboxedSvg(stageEl, text, ctx.theme);
      stageEl.classList.add("fv-image-fit");
      status.append(el("span", { text: `SVG · ${formatBytes(info.size)} · scripts and external resources are blocked` }));
    } else {
      url = URL.createObjectURL(blob);
      img = el("img", { class: "fv-img", alt: info.name, draggable: false });
      await new Promise<void>((res, rej) => { img!.onload = () => res(); img!.onerror = () => rej(new Error("The browser could not decode this image")); img!.src = url!; });
      stageEl.append(img);
      applyZoom();
      const cur = () => (zoom === "fit" ? img!.clientWidth / img!.naturalWidth : zoom);
      bar.append(
        mkBtn("−", () => { zoom = Math.max(0.05, cur() / 1.25); applyZoom(); }, "Zoom out"),
        mkBtn("+", () => { zoom = Math.min(16, cur() * 1.25); applyZoom(); }, "Zoom in"),
        mkBtn("Fit", () => { zoom = "fit"; applyZoom(); }, "Fit to window"),
        mkBtn("100%", () => { zoom = 1; applyZoom(); }, "Actual size"),
      );
      status.append(el("span", { "data-k": "dims", text: `${img.naturalWidth} × ${img.naturalHeight}` }), el("span", { text: formatBytes(info.size) }), el("span", { text: info.detected ?? "" }));
    }

    let exif: Record<string, unknown> | null | undefined;
    const showMeta = async () => {
      if (!meta.hidden) { meta.hidden = true; return; }
      if (exif === undefined) exif = await readExif(blob);
      meta.replaceChildren(el("strong", { text: "Metadata" }));
      if (!exif) meta.append(el("p", { class: "fv-muted", text: "No EXIF metadata found." }));
      else {
        const dl = el("dl", { class: "fv-kv" });
        for (const [k, v] of Object.entries(exif)) {
          const s = v instanceof Date ? v.toISOString() : v instanceof Uint8Array || (typeof v === "object" && v !== null && !Array.isArray(v)) ? JSON.stringify(v).slice(0, 120) : String(v);
          dl.append(el("dt", { text: k }), el("dd", { text: s.slice(0, 200) }));
        }
        meta.append(dl);
      }
      meta.hidden = false;
    };
    const commands: Command[] = [{ id: "exif", label: "Show / hide metadata (EXIF)", run: showMeta }];
    if (info.detected === "image/svg+xml") commands.length = 0;

    return {
      isDirty: () => false,
      onDirtyChange: () => {},
      commands: () => commands,
      setTheme(t) { if (frame) { frame.remove(); frame = null; void blob.text().then((x) => { frame = sandboxedSvg(stageEl, x, t); }); } },
      unmount() { if (url) URL.revokeObjectURL(url); root.remove(); },
    };
  },
};
export default mod;
