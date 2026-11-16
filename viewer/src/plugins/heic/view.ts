// libheif-js is LGPL-3.0: it lives only in this lazily loaded chunk (see LICENSES.md).
import libheif from "libheif-js/wasm-bundle";
import { bytesSource } from "../../core/source";
import { el, formatBytes } from "../../lib/format";
import { ViewerError, type PluginModule, type ViewContext, type ViewHandle } from "../../core/types";

const mod: PluginModule = {
  async mount(ctx: ViewContext): Promise<ViewHandle> {
    const bytes = new Uint8Array(await (await ctx.file.blob()).arrayBuffer());
    let images;
    try { images = new libheif.HeifDecoder().decode(bytes); } catch (e) { throw new ViewerError(`Cannot decode this HEIC file (${String(e)})`); }
    const image = images[0];
    if (!image) throw new ViewerError("The HEIC file contains no images");
    const w = image.get_width(), h = image.get_height();
    if (w * h > 100_000_000) throw new ViewerError("Image is too large to decode in the browser");
    const canvas = el("canvas", { class: "fv-heic-canvas", "data-testid": "heic-canvas" });
    canvas.width = w; canvas.height = h;
    const c2d = canvas.getContext("2d")!;
    const data = c2d.createImageData(w, h);
    await new Promise<void>((res, rej) => image.display({ data: data.data, width: w, height: h }, (r) => (r ? res() : rej(new ViewerError("HEIC decoding failed")))));
    c2d.putImageData(data, 0, 0);
    const root = el("div", { class: "fv-image" });
    root.append(el("div", { class: "fv-image-stage fv-image-fit" }, canvas), el("div", { class: "fv-statusbar", role: "status" }, el("span", { "data-k": "dims", text: `${w} × ${h}` }), el("span", { text: `${formatBytes(ctx.info.size)} · ${images.length} image${images.length === 1 ? "" : "s"}` })));
    canvas.style.maxWidth = "100%"; canvas.style.maxHeight = "100%";
    ctx.container.append(root);
    return {
      isDirty: () => false,
      onDirtyChange: () => {},
      commands: () => [{
        id: "to-png", label: "Open as PNG",
        run: async () => { const b = await new Promise<Blob>((res, rej) => canvas.toBlob((x) => (x ? res(x) : rej(new Error("encode failed"))), "image/png")); ctx.openNested?.(bytesSource(new Uint8Array(await b.arrayBuffer()), ctx.info.name.replace(/\.[^.]+$/, "") + ".png", "image/png")); },
      }],
      unmount: () => root.remove(),
    };
  },
};
export default mod;
