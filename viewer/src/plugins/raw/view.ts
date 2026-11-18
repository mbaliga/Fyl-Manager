import { bytesSource } from "../../core/source";
import { el, formatBytes } from "../../lib/format";
import { findEmbeddedJpegs } from "./jpeg";
import { ViewerError, type PluginModule, type ViewContext, type ViewHandle } from "../../core/types";

const mod: PluginModule = {
  async mount(ctx: ViewContext): Promise<ViewHandle> {
    const jpegs = await findEmbeddedJpegs(ctx.file, ctx.signal);
    if (!jpegs.length) throw new ViewerError("No embedded JPEG preview found in this RAW file");
    const best = jpegs[0]!;
    const bytes = await ctx.file.read(best.offset, best.length);
    const blob = new Blob([bytes as BlobPart], { type: "image/jpeg" });
    const url = URL.createObjectURL(blob);
    const root = el("div", { class: "fv-image fv-raw" });
    root.append(el("div", { class: "fv-banner fv-banner-notice", role: "note", "data-testid": "raw-note", text: `Showing the embedded JPEG preview (${formatBytes(best.length)}${jpegs.length > 1 ? `, largest of ${jpegs.length}` : ""}). The RAW sensor data is not developed.` }));
    const img = el("img", { class: "fv-img", alt: "RAW preview", draggable: false });
    const stage = el("div", { class: "fv-image-stage fv-image-fit" }, img);
    const meta = el("aside", { class: "fv-image-meta", "data-testid": "exif" }, el("strong", { text: "Metadata" }));
    root.append(el("div", { class: "fv-image-main" }, stage, meta));
    ctx.container.append(root);
    await new Promise<void>((res, rej) => { img.onload = () => res(); img.onerror = () => rej(new ViewerError("The embedded preview could not be decoded")); img.src = url; });
    try {
      const exifr = (await import("exifr")).default;
      const whole = ctx.file.size <= 64 * 1024 * 1024 ? await ctx.file.blob() : blob;
      const ex = (await exifr.parse(whole, { tiff: true, exif: true, gps: true, translateValues: true, reviveValues: true })) as Record<string, unknown> | undefined;
      const dl = el("dl", { class: "fv-kv" });
      for (const [k, v] of Object.entries(ex ?? {}).slice(0, 80)) dl.append(el("dt", { text: k }), el("dd", { text: (v instanceof Date ? v.toISOString() : typeof v === "object" ? JSON.stringify(v) : String(v)).slice(0, 160) }));
      meta.append(ex && Object.keys(ex).length ? dl : el("p", { class: "fv-muted", text: "No EXIF metadata found." }));
    } catch { meta.append(el("p", { class: "fv-muted", text: "EXIF could not be read." })); }
    return {
      isDirty: () => false,
      onDirtyChange: () => {},
      commands: () => [{ id: "open-preview", label: "Open preview as image", run: () => ctx.openNested?.(bytesSource(bytes, ctx.info.name.replace(/\.[^.]+$/, "") + "-preview.jpg", "image/jpeg")) }],
      unmount() { URL.revokeObjectURL(url); root.remove(); },
    };
  },
};
export default mod;
