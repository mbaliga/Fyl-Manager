import { unzipSync, strFromU8 } from "fflate";
import { buildSrcdoc } from "../../core/security";
import { el } from "../../lib/format";
import type { PluginModule, ViewContext, ViewHandle } from "../../core/types";

const MIME: Record<string, string> = { png: "image/png", jpg: "image/jpeg", jpeg: "image/jpeg", gif: "image/gif", webp: "image/webp", svg: "image/svg+xml" };
const xml = (b: Uint8Array) => new DOMParser().parseFromString(strFromU8(b), "application/xml");
const dirname = (p: string) => p.split("/").slice(0, -1).join("/");
const resolve = (base: string, rel: string) => {
  const parts = (base ? base + "/" : "").concat(decodeURIComponent(rel.split("#")[0]!)).split("/");
  const out: string[] = [];
  for (const p of parts) { if (p === "..") out.pop(); else if (p && p !== ".") out.push(p); }
  return out.join("/");
};

const mod: PluginModule = {
  async mount(ctx: ViewContext): Promise<ViewHandle> {
    const bytes = new Uint8Array(await (await ctx.file.blob()).arrayBuffer());
    const files = unzipSync(bytes, { filter: (f) => f.originalSize < 40 * 1024 * 1024 });
    const container = files["META-INF/container.xml"];
    if (!container) throw new Error("Not a valid EPUB (no META-INF/container.xml)");
    const opfPath = xml(container).querySelector("rootfile")?.getAttribute("full-path");
    if (!opfPath || !files[opfPath]) throw new Error("EPUB package document missing");
    const opf = xml(files[opfPath]!);
    const base = dirname(opfPath);
    const manifest = new Map<string, { href: string; type: string; props: string }>();
    opf.querySelectorAll("manifest > item").forEach((i) => manifest.set(i.getAttribute("id")!, { href: resolve(base, i.getAttribute("href")!), type: i.getAttribute("media-type") ?? "", props: i.getAttribute("properties") ?? "" }));
    const spine = Array.from(opf.querySelectorAll("spine > itemref")).map((r) => manifest.get(r.getAttribute("idref")!)).filter((m): m is NonNullable<typeof m> => !!m && files[m.href] !== undefined);
    const title = opf.querySelector("metadata > title, metadata title")?.textContent?.trim() ?? ctx.info.name;
    const author = opf.querySelector("metadata creator")?.textContent?.trim();

    // table of contents: EPUB3 nav, else NCX
    const toc: { label: string; href: string }[] = [];
    const nav = [...manifest.values()].find((m) => m.props.includes("nav"));
    const ncx = [...manifest.values()].find((m) => m.type === "application/x-dtbncx+xml");
    try {
      if (nav && files[nav.href]) {
        const d = new DOMParser().parseFromString(strFromU8(files[nav.href]!), "text/html");
        d.querySelectorAll('nav[epub\\:type="toc"] a, nav#toc a, nav a').forEach((a) => toc.push({ label: (a.textContent ?? "").trim(), href: resolve(dirname(nav.href), a.getAttribute("href") ?? "") }));
      } else if (ncx && files[ncx.href]) {
        xml(files[ncx.href]!).querySelectorAll("navPoint").forEach((np) => toc.push({ label: np.querySelector("navLabel > text")?.textContent?.trim() ?? "", href: resolve(dirname(ncx.href), np.querySelector("content")?.getAttribute("src") ?? "") }));
      }
    } catch { /* toc is optional */ }
    if (!toc.length) spine.forEach((s, i) => toc.push({ label: `Section ${i + 1}`, href: s.href }));

    const root = el("div", { class: "fv-epub" });
    const side = el("nav", { class: "fv-epub-toc", "aria-label": "Table of contents", "data-testid": "toc" }, el("strong", { text: title }), author ? el("div", { class: "fv-muted", text: author }) : "");
    const pane = el("div", { class: "fv-epub-pane" });
    const bar = el("div", { class: "fv-toolbar2" });
    const prev = el("button", { class: "fv-btn", type: "button", text: "◀ Prev", "data-act": "prev" });
    const next = el("button", { class: "fv-btn", type: "button", text: "Next ▶", "data-act": "next" });
    const where = el("span", { class: "fv-muted", "data-k": "chapter" });
    bar.append(prev, next, where);
    const frame = el("iframe", { class: "fv-sandbox", sandbox: "", title: "Chapter", referrerpolicy: "no-referrer", "data-testid": "chapter" });
    pane.append(bar, frame);
    root.append(side, pane);
    ctx.container.append(root);

    let idx = 0, theme = ctx.theme;
    const dataUrl = (path: string): string | null => {
      const f = files[path]; const ext = path.split(".").pop()!.toLowerCase();
      if (!f || !MIME[ext] || f.length > 8 * 1024 * 1024) return null;
      let bin = ""; for (let i = 0; i < f.length; i += 0x8000) bin += String.fromCharCode(...f.subarray(i, i + 0x8000));
      return `data:${MIME[ext]};base64,${btoa(bin)}`;
    };
    const render = () => {
      const item = spine[idx];
      if (!item) return;
      const doc = new DOMParser().parseFromString(strFromU8(files[item.href]!), "text/html");
      doc.querySelectorAll("img").forEach((im) => { const u = dataUrl(resolve(dirname(item.href), im.getAttribute("src") ?? "")); if (u) im.setAttribute("src", u); else im.removeAttribute("src"); });
      frame.srcdoc = buildSrcdoc(doc.body?.innerHTML ?? "", theme, { css: "body{max-width:42em;margin:16px auto;font-family:Georgia,serif}" });
      where.textContent = `${idx + 1} / ${spine.length}`;
      side.querySelectorAll("a").forEach((a) => a.classList.toggle("fv-toc-cur", a.dataset.href === item.href));
    };
    toc.forEach((t) => {
      const a = el("a", { href: "#", class: "fv-toc-link", text: t.label || "(untitled)", "data-href": t.href });
      a.onclick = (e) => { e.preventDefault(); const i = spine.findIndex((s) => s.href === t.href); if (i >= 0) { idx = i; render(); } };
      side.append(a);
    });
    prev.onclick = () => { if (idx > 0) { idx--; render(); } };
    next.onclick = () => { if (idx < spine.length - 1) { idx++; render(); } };
    render();
    return { isDirty: () => false, onDirtyChange: () => {}, setTheme(t) { theme = t; render(); }, unmount: () => root.remove() };
  },
};
export default mod;
