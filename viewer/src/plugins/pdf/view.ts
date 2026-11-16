import * as pdfjs from "pdfjs-dist";
import type { PDFDocumentProxy, PDFPageProxy } from "pdfjs-dist";
import workerUrl from "pdfjs-dist/build/pdf.worker.min.mjs?url";
import "pdfjs-dist/web/pdf_viewer.css";
import { el } from "../../lib/format";
import { buildPdf, type Ann, type PageRef } from "./model";
import type { Command, PluginModule, ViewContext, ViewHandle } from "../../core/types";
import { ViewerError } from "../../core/types";

pdfjs.GlobalWorkerOptions.workerSrc = workerUrl;
const base = () => new URL("./pdfjs/", document.baseURI).href;

async function openDoc(bytes: Uint8Array): Promise<PDFDocumentProxy> {
  try {
    return await pdfjs.getDocument({
      data: bytes.slice(), enableXfa: false,
      cMapUrl: `${base()}cmaps/`, cMapPacked: true, standardFontDataUrl: `${base()}standard_fonts/`,
    }).promise;
  } catch (e) {
    if ((e as Error).name === "PasswordException") throw new ViewerError("This PDF is password-protected. Password-protected PDFs are not supported yet.");
    throw e;
  }
}

type Tool = "none" | "text" | "ink" | "sign";

const mod: PluginModule = {
  async mount(ctx: ViewContext): Promise<ViewHandle> {
    const editing = ctx.mode === "edit";
    const sources: Uint8Array[] = [new Uint8Array(await (await ctx.file.blob()).arrayBuffer())];
    const docs: PDFDocumentProxy[] = [await openDoc(sources[0]!)];
    const pageCache = new Map<string, PDFPageProxy>();
    const getPage = async (r: PageRef) => {
      const k = `${r.src}:${r.index}`;
      let p = pageCache.get(k);
      if (!p) { p = await docs[r.src]!.getPage(r.index + 1); pageCache.set(k, p); }
      return p;
    };
    const pages: PageRef[] = [];
    const addDoc = async (si: number) => {
      for (let i = 0; i < docs[si]!.numPages; i++) {
        const p = await docs[si]!.getPage(i + 1);
        pageCache.set(`${si}:${i}`, p);
        const vp = p.getViewport({ scale: 1 });
        pages.push({ src: si, index: i, rot: 0, annots: [], w: vp.width, h: vp.height, intrinsic: p.rotate });
      }
    };
    await addDoc(0);

    let version = 0, saved = 0;
    const dirtyCbs: ((d: boolean) => void)[] = [];
    const cmdCbs: (() => void)[] = [];
    const touch = () => { version++; dirtyCbs.forEach((c) => c(true)); };
    const selected = new Set<PageRef>();

    // ---- DOM
    const root = el("div", { class: "fv-pdf" });
    const bar = el("div", { class: "fv-toolbar2" });
    const pageIn = el("input", { class: "fv-input", type: "number", min: "1", value: "1", "aria-label": "Page number", style: "width:64px" });
    const pageTotal = el("span", { class: "fv-muted", "data-k": "pages" });
    const zoomOut = el("button", { class: "fv-btn", type: "button", text: "−", "aria-label": "Zoom out" });
    const zoomIn = el("button", { class: "fv-btn", type: "button", text: "+", "aria-label": "Zoom in" });
    const search = el("input", { class: "fv-input", type: "search", placeholder: "Search", "aria-label": "Search in PDF", size: "14" });
    const prevHit = el("button", { class: "fv-btn", type: "button", text: "↑", "aria-label": "Previous match" });
    const nextHit = el("button", { class: "fv-btn", type: "button", text: "↓", "aria-label": "Next match" });
    const hitInfo = el("span", { class: "fv-muted", "data-k": "hits" });
    const thumbToggle = el("button", { class: "fv-btn fv-btn-on", type: "button", text: "Pages", "aria-pressed": "true" });
    bar.append(thumbToggle, pageIn, "/", pageTotal, zoomOut, zoomIn, search, prevHit, nextHit, hitInfo);
    const toolBar = el("div", { class: "fv-toolbar2", hidden: !editing, "data-testid": "pdf-tools" });
    const body = el("div", { class: "fv-pdf-body" });
    const thumbs = el("div", { class: "fv-thumbs", "data-testid": "thumbs" });
    const main = el("div", { class: "fv-pdf-main", tabindex: "0", "data-testid": "pdf-main" });
    body.append(thumbs, main);
    const status = el("div", { class: "fv-statusbar", role: "status" });
    root.append(...(editing ? [el("div", { class: "fv-banner fv-banner-notice", role: "note", text: "Annotations are flattened into the page when saved. The original is only replaced when you save." })] : []), bar, toolBar, body, status);
    ctx.container.append(root);

    // ---- state
    let scale = 0;
    let fitW = 0;
    const rotOf = (r: PageRef) => (r.intrinsic + r.rot + 360) % 360;
    const dims = (r: PageRef) => (rotOf(r) % 180 === 0 ? { w: r.w, h: r.h } : { w: r.h, h: r.w });
    const containers: HTMLElement[] = [];
    const rendered = new Map<number, { task?: { cancel(): void } }>();
    let tool: Tool = "none";
    let color = "#d32f2f";
    let sigImage: { bytes: Uint8Array; mime: string; aspect: number } | null = null;

    const ensureScale = () => {
      fitW = Math.max(200, main.clientWidth - 32);
      if (!scale) scale = Math.min(1.6, fitW / Math.max(...pages.map((p) => dims(p).w), 1));
      if (!isFinite(scale) || scale <= 0) scale = 1;
    };

    const drawAnn = (cv: HTMLCanvasElement, r: PageRef) => {
      const c = cv.getContext("2d")!;
      c.clearRect(0, 0, cv.width, cv.height);
      for (const a of r.annots) {
        if (a.kind === "text") { c.fillStyle = a.color; c.font = `${a.size * cv.width}px Helvetica, Arial, sans-serif`; c.textBaseline = "alphabetic"; a.text.split("\n").forEach((l, i) => c.fillText(l, a.u * cv.width, (a.v + (i * a.size * cv.width * 1.2) / cv.height) * cv.height)); }
        else if (a.kind === "ink") { c.strokeStyle = a.color; c.lineWidth = Math.max(1, a.w * cv.width); c.lineCap = "round"; c.lineJoin = "round"; c.beginPath(); a.pts.forEach((p, i) => (i ? c.lineTo(p[0] * cv.width, p[1] * cv.height) : c.moveTo(p[0] * cv.width, p[1] * cv.height))); c.stroke(); }
        else { const im = new Image(); im.onload = () => c.drawImage(im, a.u * cv.width, a.v * cv.height, a.w * cv.width, (a.w * cv.width) / a.aspect); im.src = URL.createObjectURL(new Blob([a.bytes as BlobPart], { type: a.mime })); }
      }
    };

    const renderPage = async (i: number) => {
      if (rendered.has(i)) return;
      const r = pages[i]!, box = containers[i]!;
      const entry: { task?: { cancel(): void } } = {};
      rendered.set(i, entry);
      const page = await getPage(r);
      const vp = page.getViewport({ scale, rotation: rotOf(r) });
      const dpr = Math.min(2, window.devicePixelRatio || 1);
      const canvas = el("canvas", { class: "fv-pdf-canvas" });
      canvas.width = Math.floor(vp.width * dpr); canvas.height = Math.floor(vp.height * dpr);
      canvas.style.width = `${vp.width}px`; canvas.style.height = `${vp.height}px`;
      const task = page.render({ canvasContext: canvas.getContext("2d")!, viewport: vp, transform: dpr !== 1 ? [dpr, 0, 0, dpr, 0, 0] : undefined } as never);
      entry.task = task as unknown as { cancel(): void };
      try { await task.promise; } catch { rendered.delete(i); return; }
      if (!rendered.has(i)) return;
      const tl = el("div", { class: "textLayer" });
      tl.style.setProperty("--scale-factor", String(scale));
      const ov = el("canvas", { class: "fv-pdf-ann" });
      ov.width = Math.floor(vp.width); ov.height = Math.floor(vp.height);
      ov.style.width = `${vp.width}px`; ov.style.height = `${vp.height}px`;
      box.replaceChildren(canvas, tl, ov);
      drawAnn(ov, r);
      attachTool(ov, r);
      try {
        const layer = new pdfjs.TextLayer({ textContentSource: page.streamTextContent(), container: tl, viewport: vp });
        await layer.render();
        markHits(tl);
      } catch { /* text layer is best effort */ }
    };
    const unrender = (i: number) => {
      const e = rendered.get(i);
      if (!e) return;
      e.task?.cancel();
      rendered.delete(i);
      containers[i]?.replaceChildren();
    };

    const io = new IntersectionObserver((ents) => {
      for (const en of ents) {
        const i = Number((en.target as HTMLElement).dataset.i);
        if (en.isIntersecting) void renderPage(i); else unrender(i);
      }
    }, { root: main, rootMargin: "1200px 0px" });
    const pageObserver = new IntersectionObserver((ents) => {
      const best = ents.filter((e) => e.isIntersecting).sort((a, b) => b.intersectionRatio - a.intersectionRatio)[0];
      if (best && document.activeElement !== pageIn) pageIn.value = String(Number((best.target as HTMLElement).dataset.i) + 1);
    }, { root: main, threshold: [0.25, 0.5, 0.75] });

    const layoutPages = () => {
      ensureScale();
      io.disconnect(); pageObserver.disconnect();
      rendered.forEach((e) => e.task?.cancel()); rendered.clear();
      main.replaceChildren(); containers.length = 0;
      pages.forEach((r, i) => {
        const d = dims(r);
        const box = el("div", { class: "fv-pdf-page", "data-i": i, "data-testid": "pdf-page", style: `width:${d.w * scale}px;height:${d.h * scale}px` });
        containers.push(box); main.append(box); io.observe(box); pageObserver.observe(box);
      });
      pageTotal.textContent = String(pages.length);
      status.replaceChildren(el("span", { text: `${pages.length} page${pages.length === 1 ? "" : "s"}` }), el("span", { text: editing ? "Editing" : "Read-only" }));
      buildThumbs();
    };

    // ---- thumbnails (+ page ops in edit mode)
    const thumbIO = new IntersectionObserver((ents) => {
      for (const en of ents) if (en.isIntersecting) { const c = en.target as HTMLCanvasElement; if (!c.dataset.done) { c.dataset.done = "1"; void drawThumb(c, Number(c.dataset.i)); } }
    }, { root: thumbs, rootMargin: "400px" });
    const drawThumb = async (c: HTMLCanvasElement, i: number) => {
      const r = pages[i]; if (!r) return;
      const page = await getPage(r);
      const vp = page.getViewport({ scale: 110 / dims(r).w, rotation: rotOf(r) });
      c.width = Math.floor(vp.width); c.height = Math.floor(vp.height);
      await page.render({ canvasContext: c.getContext("2d")!, viewport: vp } as never).promise.catch(() => {});
    };
    const move = (i: number, d: number) => { const j = i + d; if (j < 0 || j >= pages.length) return; [pages[i], pages[j]] = [pages[j]!, pages[i]!]; touch(); layoutPages(); };
    const buildThumbs = () => {
      thumbIO.disconnect();
      thumbs.replaceChildren();
      pages.forEach((r, i) => {
        const cv = el("canvas", { class: "fv-thumb-canvas", "data-i": i });
        cv.width = 110; cv.height = Math.floor((110 * dims(r).h) / dims(r).w);
        const item = el("div", { class: "fv-thumb", "data-testid": "thumb", "data-i": i }, cv, el("span", { class: "fv-muted", text: String(i + 1) }));
        cv.onclick = () => containers[i]?.scrollIntoView({ block: "start" });
        if (editing) {
          const mk = (label: string, aria: string, fn: () => void) => { const b = el("button", { class: "fv-btn fv-thumb-btn", type: "button", text: label, "aria-label": `${aria} page ${i + 1}`, "data-op": aria }); b.onclick = fn; return b; };
          const chk = el("input", { type: "checkbox", "aria-label": `Select page ${i + 1}`, "data-op": "select" });
          chk.checked = selected.has(r);
          chk.onchange = () => { if (chk.checked) selected.add(r); else selected.delete(r); };
          item.append(el("div", { class: "fv-thumb-ops" }, chk, mk("↑", "up", () => move(i, -1)), mk("↓", "down", () => move(i, 1)), mk("⟳", "rotate", () => { r.rot = (r.rot + 90) % 360; touch(); layoutPages(); }), mk("🗑", "delete", () => { if (pages.length > 1) { pages.splice(i, 1); selected.delete(r); touch(); layoutPages(); } })));
        }
        thumbs.append(item); thumbIO.observe(cv);
      });
    };

    // ---- annotation tools
    const attachTool = (ov: HTMLCanvasElement, r: PageRef) => {
      if (!editing) return;
      ov.style.pointerEvents = tool === "none" ? "none" : "auto";
      ov.style.touchAction = tool === "ink" ? "none" : "auto";
      const pt = (e: PointerEvent): [number, number] => { const b = ov.getBoundingClientRect(); return [(e.clientX - b.left) / b.width, (e.clientY - b.top) / b.height]; };
      let ink: [number, number][] | null = null;
      ov.onpointerdown = (e) => {
        if (tool === "ink") { ov.setPointerCapture(e.pointerId); ink = [pt(e)]; }
        else if (tool === "text") {
          const [u, v] = pt(e);
          const inp = el("input", { class: "fv-cell-input", type: "text", "aria-label": "Annotation text", "data-testid": "pdf-text-input" });
          Object.assign(inp.style, { position: "fixed", left: `${e.clientX}px`, top: `${e.clientY - 14}px`, width: "220px", height: "28px" });
          document.body.append(inp); inp.focus();
          let done = false;
          const fin = (ok: boolean) => { if (done) return; done = true; const t = inp.value; inp.remove(); if (ok && t) { r.annots.push({ kind: "text", u, v, text: t, size: 0.03, color }); touch(); drawAnn(ov, r); } };
          inp.onkeydown = (ev) => { if (ev.key === "Enter") fin(true); else if (ev.key === "Escape") fin(false); };
          inp.onblur = () => fin(true);
        } else if (tool === "sign" && sigImage) {
          const [u, v] = pt(e);
          r.annots.push({ kind: "img", u: Math.min(u, 0.75), v, w: 0.25, aspect: sigImage.aspect, bytes: sigImage.bytes, mime: sigImage.mime } as Ann);
          touch(); drawAnn(ov, r);
        }
      };
      ov.onpointermove = (e) => { if (!ink) return; ink.push(pt(e)); const c = ov.getContext("2d")!; drawAnn(ov, r); c.strokeStyle = color; c.lineWidth = Math.max(1, 0.004 * ov.width); c.lineCap = "round"; c.beginPath(); ink.forEach((p, i) => (i ? c.lineTo(p[0] * ov.width, p[1] * ov.height) : c.moveTo(p[0] * ov.width, p[1] * ov.height))); c.stroke(); };
      ov.onpointerup = () => { if (ink && ink.length > 1) { r.annots.push({ kind: "ink", pts: ink, w: 0.004, color }); touch(); } ink = null; drawAnn(ov, r); };
    };
    const refreshTools = () => { main.querySelectorAll<HTMLCanvasElement>(".fv-pdf-ann").forEach((ov) => { ov.style.pointerEvents = tool === "none" ? "none" : "auto"; ov.style.touchAction = tool === "ink" ? "none" : "auto"; ov.style.cursor = tool === "text" ? "text" : "crosshair"; }); toolBtns.forEach((b, t) => { b.classList.toggle("fv-btn-on", t === tool); b.setAttribute("aria-pressed", String(t === tool)); }); };
    const toolBtns = new Map<Tool, HTMLButtonElement>();
    const sigPicker = el("input", { type: "file", accept: "image/png,image/jpeg", hidden: true, "data-testid": "sig-input" });
    const pdfPicker = el("input", { type: "file", accept: "application/pdf", hidden: true, "data-testid": "merge-input" });
    if (editing) {
      for (const [t, label] of [["text", "Text"], ["ink", "Draw"], ["sign", "Signature image"]] as [Tool, string][]) {
        const b = el("button", { class: "fv-btn", type: "button", text: label, "data-tool": t, "aria-pressed": "false" });
        b.onclick = () => { if (t === "sign" && !sigImage) { sigPicker.click(); return; } tool = tool === t ? "none" : t; refreshTools(); };
        toolBtns.set(t, b); toolBar.append(b);
      }
      const col = el("input", { type: "color", value: color, "aria-label": "Annotation colour" });
      col.oninput = () => { color = col.value; };
      toolBar.append(col, sigPicker, pdfPicker);
      sigPicker.onchange = async () => {
        const f = sigPicker.files?.[0]; if (!f) return;
        const bytes = new Uint8Array(await f.arrayBuffer());
        const bmp = await createImageBitmap(f);
        sigImage = { bytes, mime: f.type === "image/png" ? "image/png" : "image/jpeg", aspect: bmp.width / bmp.height };
        bmp.close(); sigPicker.value = ""; tool = "sign"; refreshTools();
      };
      pdfPicker.onchange = async () => {
        const f = pdfPicker.files?.[0]; if (!f) return;
        const bytes = new Uint8Array(await f.arrayBuffer());
        const d = await openDoc(bytes);
        sources.push(bytes); docs.push(d);
        await addDoc(docs.length - 1);
        pdfPicker.value = ""; touch(); layoutPages();
      };
    }

    // ---- search
    let query = "";
    const hits: { page: number }[] = [];
    let hitIdx = -1;
    const textCache = new Map<string, string>();
    const textOf = async (r: PageRef) => {
      const k = `${r.src}:${r.index}`;
      let t = textCache.get(k);
      if (t === undefined) { t = (await (await getPage(r)).getTextContent()).items.map((it) => ("str" in it ? it.str : "")).join(" ").toLowerCase(); textCache.set(k, t); }
      return t;
    };
    const markHits = (tl: HTMLElement) => {
      if (!query) return;
      tl.querySelectorAll("span").forEach((s) => { if ((s.textContent ?? "").toLowerCase().includes(query)) s.classList.add("fv-hit"); });
    };
    const runSearch = async () => {
      query = search.value.trim().toLowerCase();
      hits.length = 0; hitIdx = -1;
      main.querySelectorAll(".fv-hit").forEach((s) => s.classList.remove("fv-hit"));
      main.querySelectorAll<HTMLElement>(".textLayer").forEach(markHits);
      if (!query) { hitInfo.textContent = ""; return; }
      for (let i = 0; i < pages.length; i++) {
        if (ctx.signal.aborted) return;
        let from = 0; const t = await textOf(pages[i]!);
        while ((from = t.indexOf(query, from)) >= 0) { hits.push({ page: i }); from += query.length; }
        hitInfo.textContent = `${hits.length} match${hits.length === 1 ? "" : "es"}`;
      }
      if (hits.length) go(1);
    };
    const go = (d: number) => { if (!hits.length) return; hitIdx = (hitIdx + d + hits.length) % hits.length; containers[hits[hitIdx]!.page]?.scrollIntoView({ block: "start" }); hitInfo.textContent = `${hitIdx + 1} / ${hits.length}`; };
    search.addEventListener("keydown", (e) => { if (e.key === "Enter") void runSearch(); });
    search.addEventListener("input", () => { if (!search.value) void runSearch(); });
    nextHit.onclick = () => go(1); prevHit.onclick = () => go(-1);

    // ---- misc controls
    pageIn.onchange = () => containers[Math.max(0, Math.min(pages.length - 1, Number(pageIn.value) - 1))]?.scrollIntoView({ block: "start" });
    const rezoom = (f: number) => { scale = Math.max(0.2, Math.min(5, scale * f)); layoutPages(); };
    zoomIn.onclick = () => rezoom(1.25); zoomOut.onclick = () => rezoom(0.8);
    thumbToggle.onclick = () => { thumbs.hidden = !thumbs.hidden; thumbToggle.classList.toggle("fv-btn-on", !thumbs.hidden); thumbToggle.setAttribute("aria-pressed", String(!thumbs.hidden)); };
    if (matchMedia("(max-width: 640px)").matches) thumbs.hidden = true;
    layoutPages();

    const commands = (): Command[] => editing ? [
      { id: "pdf-merge", label: "Merge another PDF…", run: () => pdfPicker.click() },
      { id: "pdf-extract", label: "Extract selected pages…", run: async () => {
        const sel = pages.filter((p) => selected.has(p));
        if (!sel.length) throw new Error("Tick the pages to extract in the sidebar first");
        const out = await buildPdf(sources, sel);
        const name = ctx.info.name.replace(/\.pdf$/i, "") + "-extract.pdf";
        const blob = new Blob([out as BlobPart], { type: "application/pdf" });
        if (ctx.host.saveAs) await ctx.host.saveAs(blob, name);
      } },
      { id: "pdf-sig", label: "Choose signature image…", run: () => sigPicker.click() },
      { id: "pdf-undo-ann", label: "Remove last annotation on current page", run: () => { const i = Number(pageIn.value) - 1; const r = pages[i]; if (r?.annots.length) { r.annots.pop(); touch(); layoutPages(); } } },
    ] : [];
    void cmdCbs;

    return {
      isDirty: () => version !== saved,
      onDirtyChange: (cb) => void dirtyCbs.push(cb),
      onCommandsChange: (cb) => void cmdCbs.push(cb),
      markSaved: () => { saved = version; dirtyCbs.forEach((c) => c(false)); },
      commands,
      async serialize() { return new Blob([(await buildPdf(sources, pages)) as BlobPart], { type: "application/pdf" }); },
      unmount() { io.disconnect(); pageObserver.disconnect(); thumbIO.disconnect(); rendered.forEach((e) => e.task?.cancel()); docs.forEach((d) => void d.loadingTask.destroy()); root.remove(); },
    };
  },
};
export default mod;
