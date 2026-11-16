import { unzipSync, strFromU8 } from "fflate";
import { el, formatBytes } from "../../lib/format";
import type { PluginModule, ViewContext, ViewHandle } from "../../core/types";

interface Slide { n: number; paragraphs: string[]; notes: string[]; images: string[] }
const MAX_PART = 30 * 1024 * 1024;
const MIME: Record<string, string> = { png: "image/png", jpg: "image/jpeg", jpeg: "image/jpeg", gif: "image/gif", webp: "image/webp", bmp: "image/bmp" };

const parseXml = (b: Uint8Array) => new DOMParser().parseFromString(strFromU8(b), "application/xml");
function paras(doc: Document, paraTag: string, textTag: string): string[] {
  const out: string[] = [];
  doc.querySelectorAll(paraTag).forEach((p) => {
    const t = Array.from(p.querySelectorAll(textTag)).map((x) => x.textContent ?? "").join("");
    if (t.trim()) out.push(t.trim());
  });
  return out;
}

const mod: PluginModule = {
  async mount(ctx: ViewContext): Promise<ViewHandle> {
    const bytes = new Uint8Array(await (await ctx.file.blob()).arrayBuffer());
    const isOdp = ctx.info.detected === "application/vnd.oasis.opendocument.presentation";
    const files = unzipSync(bytes, { filter: (f) => f.originalSize <= MAX_PART && (isOdp ? /^(content\.xml|Pictures\/)/.test(f.name) : /^ppt\/(slides|notesSlides)\/.*\.(xml|rels)$|^ppt\/(presentation\.xml|_rels\/presentation\.xml\.rels)$|^ppt\/media\//.test(f.name)) });
    const slides: Slide[] = [];
    const urls: string[] = [];
    const imgUrl = (path: string): string | null => {
      const f = files[path];
      const ext = path.split(".").pop()!.toLowerCase();
      if (!f || !MIME[ext]) return null;
      const u = URL.createObjectURL(new Blob([f as BlobPart], { type: MIME[ext] }));
      urls.push(u);
      return u;
    };

    if (isOdp) {
      const doc = parseXml(files["content.xml"] ?? new Uint8Array());
      doc.querySelectorAll("page").forEach((pg, i) => {
        slides.push({ n: i + 1, paragraphs: paras(pg as unknown as Document, "p", "p"), notes: [], images: Array.from(pg.querySelectorAll("image")).map((im) => imgUrl(im.getAttribute("xlink:href") ?? "") ?? "").filter(Boolean) });
      });
    } else {
      // order: presentation.xml sldIdLst → rels; fallback numeric
      const slidePaths = Object.keys(files).filter((p) => /^ppt\/slides\/slide\d+\.xml$/.test(p));
      let ordered = slidePaths.sort((a, b) => Number(/(\d+)\.xml/.exec(a)![1]) - Number(/(\d+)\.xml/.exec(b)![1]));
      try {
        const pres = files["ppt/presentation.xml"], rels = files["ppt/_rels/presentation.xml.rels"];
        if (pres && rels) {
          const rmap = new Map<string, string>();
          parseXml(rels).querySelectorAll("Relationship").forEach((r) => rmap.set(r.getAttribute("Id")!, "ppt/" + r.getAttribute("Target")!.replace(/^\//, "").replace(/^ppt\//, "")));
          const ids = Array.from(parseXml(pres).getElementsByTagName("p:sldId")).map((s) => rmap.get(s.getAttribute("r:id") ?? "")).filter((p): p is string => !!p && p in files);
          if (ids.length) ordered = ids;
        }
      } catch { /* keep numeric order */ }
      ordered.forEach((path, i) => {
        const doc = parseXml(files[path]!);
        const relPath = path.replace("slides/", "slides/_rels/") + ".rels";
        const images: string[] = [];
        const notes: string[] = [];
        const rel = files[relPath];
        if (rel) parseXml(rel).querySelectorAll("Relationship").forEach((r) => {
          const t = r.getAttribute("Target") ?? "";
          const full = new URL(t, "http://x/" + path).pathname.slice(1);
          if (/\/image$/.test(r.getAttribute("Type") ?? "")) { const u = imgUrl(full); if (u) images.push(u); }
          else if (/notesSlide$/.test(r.getAttribute("Type") ?? "") && files[full]) notes.push(...paras(parseXml(files[full]), "a\\:p, p", "a\\:t, t"));
        });
        slides.push({ n: i + 1, paragraphs: paras(doc, "a\\:p, p", "a\\:t, t"), notes, images });
      });
    }

    const root = el("div", { class: "fv-pptx" });
    root.append(el("div", { class: "fv-banner fv-banner-notice", role: "note", text: `Text outline only (${slides.length} slides, ${formatBytes(ctx.info.size)}) — slides are not rendered.` }));
    const list = el("ol", { class: "fv-slides", "data-testid": "slides" });
    for (const s of slides) {
      const li = el("li", { class: "fv-slide", "data-slide": s.n });
      li.append(el("h3", { text: `Slide ${s.n}${s.paragraphs[0] ? ` — ${s.paragraphs[0]}` : ""}` }));
      s.paragraphs.slice(1).forEach((p) => li.append(el("p", { text: p })));
      if (s.notes.length) li.append(el("p", { class: "fv-muted" }, el("em", { text: "Notes: " + s.notes.join(" ") })));
      if (s.images.length) li.append(el("div", { class: "fv-slide-imgs" }, ...s.images.map((u) => el("img", { src: u, alt: "Slide image" }))));
      list.append(li);
    }
    if (!slides.length) list.append(el("li", { class: "fv-muted", text: "No slides found." }));
    root.append(list);
    ctx.container.append(root);
    return { isDirty: () => false, onDirtyChange: () => {}, unmount() { urls.forEach((u) => URL.revokeObjectURL(u)); root.remove(); } };
  },
};
export default mod;
