import { el } from "../../lib/format";
import type { Command, ViewContext, ViewHandle } from "../../core/types";

type Tool = "select" | "crop" | "pen" | "arrow" | "rect" | "text";

/** Light image editor: crop, rotate, flip, resize, annotate. Re-encodes to the same format. */
export async function mountImageEditor(ctx: ViewContext, blob: Blob): Promise<ViewHandle> {
  const mime = ctx.info.detected ?? "image/png";
  const lossy = mime === "image/jpeg" || mime === "image/webp";
  const bmp = await createImageBitmap(blob);
  const base = document.createElement("canvas");
  base.width = bmp.width; base.height = bmp.height;
  base.getContext("2d")!.drawImage(bmp, 0, 0);
  bmp.close();
  const overlay = document.createElement("canvas");
  overlay.className = "fv-ov";
  base.className = "fv-base";

  let version = 0, saved = 0;
  const undo: ImageBitmap[] = [];
  const dirtyCbs: ((d: boolean) => void)[] = [];
  const cmdCbs: (() => void)[] = [];
  const bump = () => dirtyCbs.forEach((cb) => cb(version !== saved));

  const root = el("div", { class: "fv-image fv-image-edit" });
  const warn = el("div", { class: "fv-banner fv-banner-notice", role: "note", "data-testid": "exif-warning", text: lossy ? "Saving re-encodes the image and strips EXIF metadata." : "Saving re-encodes the image (metadata is not preserved)." });
  const bar = el("div", { class: "fv-toolbar2", "data-testid": "image-tools" });
  const stageEl = el("div", { class: "fv-image-stage fv-image-fit" });
  const status = el("div", { class: "fv-statusbar", role: "status" });
  const wrap = el("div", { class: "fv-canvas-wrap" }, base, overlay);
  stageEl.append(wrap);
  root.append(warn, bar, stageEl, status);
  ctx.container.append(root);

  const sync = () => {
    overlay.width = base.width; overlay.height = base.height;
    status.replaceChildren(el("span", { "data-k": "dims", text: `${base.width} × ${base.height}` }), el("span", { text: mime }));
  };
  sync();

  const snapshot = async () => { undo.push(await createImageBitmap(base)); if (undo.length > 8) undo.shift()?.close(); };
  const commit = async (fn: () => void | Promise<void>) => { await snapshot(); await fn(); version++; sync(); bump(); cmdCbs.forEach((c) => c()); };

  const replaceCanvas = (w: number, h: number, draw: (c: CanvasRenderingContext2D) => void) => {
    const tmp = document.createElement("canvas");
    tmp.width = w; tmp.height = h;
    draw(tmp.getContext("2d")!);
    base.width = w; base.height = h;
    base.getContext("2d")!.drawImage(tmp, 0, 0);
  };

  // ---- toolbar
  let tool: Tool = "select";
  let color = "#e53935", lw = 4, quality = 0.9;
  const toolBtns = new Map<Tool, HTMLButtonElement>();
  const mkTool = (t: Tool, label: string) => {
    const b = el("button", { class: "fv-btn", type: "button", text: label, "data-tool": t, "aria-pressed": "false" });
    b.onclick = () => { tool = t; toolBtns.forEach((bb, k) => { bb.classList.toggle("fv-btn-on", k === t); bb.setAttribute("aria-pressed", String(k === t)); }); cropRect = null; clearOverlay(); applyBtn.hidden = t !== "crop"; };
    toolBtns.set(t, b);
    return b;
  };
  const act = (label: string, fn: () => void | Promise<void>, id: string) => { const b = el("button", { class: "fv-btn", type: "button", text: label, "data-act": id }); b.onclick = () => void commit(fn); return b; };
  const applyBtn = el("button", { class: "fv-btn fv-btn-primary", type: "button", text: "Apply crop", hidden: true, "data-act": "apply-crop" });
  const clearOverlay = () => overlay.getContext("2d")!.clearRect(0, 0, overlay.width, overlay.height);
  const colorIn = el("input", { type: "color", value: color, "aria-label": "Annotation colour" });
  colorIn.oninput = () => { color = colorIn.value; };
  const lwSel = el("select", { class: "fv-input", "aria-label": "Line width" }, ...[2, 4, 8, 14].map((n) => el("option", { value: n, text: `${n}px`, selected: n === lw })));
  lwSel.onchange = () => { lw = Number(lwSel.value); };
  const wIn = el("input", { class: "fv-input", type: "number", min: "1", value: base.width, "aria-label": "Width", style: "width:80px" });
  const hIn = el("input", { class: "fv-input", type: "number", min: "1", value: base.height, "aria-label": "Height", style: "width:80px" });
  const ratio = () => base.width / base.height;
  wIn.oninput = () => { hIn.value = String(Math.max(1, Math.round(Number(wIn.value) / ratio()))); };
  hIn.oninput = () => { wIn.value = String(Math.max(1, Math.round(Number(hIn.value) * ratio()))); };
  const resizeBtn = el("button", { class: "fv-btn", type: "button", text: "Resize", "data-act": "resize" });
  resizeBtn.onclick = () => {
    const w = Math.max(1, Math.round(Number(wIn.value))), h = Math.max(1, Math.round(Number(hIn.value)));
    if (w * h > 100_000_000) { ctx.host.log("warn", "resize too large"); return; }
    void commit(() => { const src = document.createElement("canvas"); src.width = base.width; src.height = base.height; src.getContext("2d")!.drawImage(base, 0, 0); replaceCanvas(w, h, (c) => { c.imageSmoothingQuality = "high"; c.drawImage(src, 0, 0, w, h); }); });
  };
  const qIn = el("input", { type: "range", min: "0.1", max: "1", step: "0.05", value: String(quality), "aria-label": "Quality", hidden: !lossy });
  qIn.oninput = () => { quality = Number(qIn.value); qLabel.textContent = `Q ${Math.round(quality * 100)}`; };
  const qLabel = el("span", { class: "fv-muted", text: `Q ${Math.round(quality * 100)}`, hidden: !lossy });

  const rotate = (dir: 1 | -1) => () => { const w = base.width, h = base.height; const src = document.createElement("canvas"); src.width = w; src.height = h; src.getContext("2d")!.drawImage(base, 0, 0); replaceCanvas(h, w, (c) => { c.translate(dir === 1 ? h : 0, dir === 1 ? 0 : w); c.rotate((dir * Math.PI) / 2); c.drawImage(src, 0, 0); }); };
  const flip = (h: boolean) => () => { const w = base.width, ht = base.height; const src = document.createElement("canvas"); src.width = w; src.height = ht; src.getContext("2d")!.drawImage(base, 0, 0); replaceCanvas(w, ht, (c) => { if (h) { c.translate(w, 0); c.scale(-1, 1); } else { c.translate(0, ht); c.scale(1, -1); } c.drawImage(src, 0, 0); }); };
  const undoBtn = el("button", { class: "fv-btn", type: "button", text: "Undo", "data-act": "undo" });
  undoBtn.onclick = async () => { const s = undo.pop(); if (!s) return; replaceCanvas(s.width, s.height, (c) => c.drawImage(s, 0, 0)); s.close(); version++; sync(); bump(); wIn.value = String(base.width); hIn.value = String(base.height); };

  bar.append(
    mkTool("select", "Pan"), mkTool("crop", "Crop"), mkTool("pen", "Pen"), mkTool("arrow", "Arrow"), mkTool("rect", "Rectangle"), mkTool("text", "Text"),
    colorIn, lwSel, applyBtn,
    act("⟲", rotate(-1), "rotate-left"), act("⟳", rotate(1), "rotate-right"), act("⇋", flip(true), "flip-h"), act("⇵", flip(false), "flip-v"),
    wIn, "×", hIn, resizeBtn, qIn, qLabel, undoBtn,
  );
  toolBtns.get("select")!.classList.add("fv-btn-on");

  // ---- pointer drawing
  let cropRect: { x: number; y: number; w: number; h: number } | null = null;
  let start: { x: number; y: number } | null = null;
  let path: { x: number; y: number }[] = [];
  const pt = (e: PointerEvent) => { const r = overlay.getBoundingClientRect(); return { x: ((e.clientX - r.left) / r.width) * overlay.width, y: ((e.clientY - r.top) / r.height) * overlay.height }; };
  const drawShape = (c: CanvasRenderingContext2D, a: { x: number; y: number }, b: { x: number; y: number }) => {
    c.strokeStyle = color; c.fillStyle = color; c.lineWidth = lw; c.lineCap = "round"; c.lineJoin = "round";
    if (tool === "rect") c.strokeRect(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.abs(b.x - a.x), Math.abs(b.y - a.y));
    else if (tool === "arrow") {
      const ang = Math.atan2(b.y - a.y, b.x - a.x), head = Math.max(10, lw * 4);
      c.beginPath(); c.moveTo(a.x, a.y); c.lineTo(b.x, b.y); c.stroke();
      c.beginPath(); c.moveTo(b.x, b.y); c.lineTo(b.x - head * Math.cos(ang - 0.45), b.y - head * Math.sin(ang - 0.45)); c.lineTo(b.x - head * Math.cos(ang + 0.45), b.y - head * Math.sin(ang + 0.45)); c.closePath(); c.fill();
    }
  };
  overlay.style.touchAction = "none";
  overlay.addEventListener("pointerdown", (e) => {
    if (tool === "select") return;
    const p = pt(e);
    if (tool === "text") { openText(e, p); return; }
    overlay.setPointerCapture(e.pointerId);
    start = p; path = [p];
  });
  overlay.addEventListener("pointermove", (e) => {
    if (!start) return;
    const p = pt(e);
    const c = overlay.getContext("2d")!;
    clearOverlay();
    if (tool === "pen") {
      path.push(p);
      c.strokeStyle = color; c.lineWidth = lw; c.lineCap = "round"; c.lineJoin = "round";
      c.beginPath(); path.forEach((q, i) => (i ? c.lineTo(q.x, q.y) : c.moveTo(q.x, q.y))); c.stroke();
    } else if (tool === "crop") {
      cropRect = { x: Math.min(start.x, p.x), y: Math.min(start.y, p.y), w: Math.abs(p.x - start.x), h: Math.abs(p.y - start.y) };
      c.fillStyle = "rgba(0,0,0,.5)"; c.fillRect(0, 0, overlay.width, overlay.height); c.clearRect(cropRect.x, cropRect.y, cropRect.w, cropRect.h);
      c.strokeStyle = "#fff"; c.setLineDash([6, 4]); c.lineWidth = 2; c.strokeRect(cropRect.x, cropRect.y, cropRect.w, cropRect.h);
    } else drawShape(c, start, p);
  });
  overlay.addEventListener("pointerup", (e) => {
    if (!start) return;
    const p = pt(e);
    const s = start; start = null;
    if (tool === "crop") return;
    clearOverlay();
    void commit(() => {
      const c = base.getContext("2d")!;
      if (tool === "pen") { c.strokeStyle = color; c.lineWidth = lw; c.lineCap = "round"; c.lineJoin = "round"; c.beginPath(); path.forEach((q, i) => (i ? c.lineTo(q.x, q.y) : c.moveTo(q.x, q.y))); if (path.length === 1) c.lineTo(path[0]!.x + 0.1, path[0]!.y); c.stroke(); }
      else drawShape(c, s, p);
    });
  });
  applyBtn.onclick = () => {
    if (!cropRect || cropRect.w < 2 || cropRect.h < 2) return;
    const r = { x: Math.round(cropRect.x), y: Math.round(cropRect.y), w: Math.round(cropRect.w), h: Math.round(cropRect.h) };
    cropRect = null; clearOverlay();
    void commit(() => { const src = document.createElement("canvas"); src.width = base.width; src.height = base.height; src.getContext("2d")!.drawImage(base, 0, 0); replaceCanvas(r.w, r.h, (c) => c.drawImage(src, r.x, r.y, r.w, r.h, 0, 0, r.w, r.h)); wIn.value = String(r.w); hIn.value = String(r.h); });
  };
  const openText = (e: PointerEvent, p: { x: number; y: number }) => {
    const inp = el("input", { type: "text", class: "fv-cell-input", "aria-label": "Annotation text", "data-testid": "text-input" });
    Object.assign(inp.style, { position: "fixed", left: `${e.clientX}px`, top: `${e.clientY}px`, width: "200px", height: "28px" });
    document.body.append(inp); inp.focus();
    let done = false;
    const finish = (ok: boolean) => {
      if (done) return; done = true;
      const v = inp.value; inp.remove();
      if (!ok || !v) return;
      void commit(() => { const c = base.getContext("2d")!; const size = Math.max(14, lw * 5); c.font = `${size}px system-ui, sans-serif`; c.fillStyle = color; c.textBaseline = "top"; c.fillText(v, p.x, p.y); });
    };
    inp.onkeydown = (ev) => { if (ev.key === "Enter") finish(true); else if (ev.key === "Escape") finish(false); };
    inp.onblur = () => finish(true);
  };

  const cmds: Command[] = [];
  return {
    isDirty: () => version !== saved,
    onDirtyChange: (cb) => void dirtyCbs.push(cb),
    onCommandsChange: (cb) => void cmdCbs.push(cb),
    markSaved: () => { saved = version; bump(); },
    commands: () => cmds,
    async serialize() {
      return await new Promise<Blob>((res, rej) => base.toBlob((b) => (b ? res(b) : rej(new Error("Encoding failed"))), mime, lossy ? quality : undefined));
    },
    unmount() { undo.forEach((u) => u.close()); root.remove(); },
  };
}
