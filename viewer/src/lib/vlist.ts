import { el } from "./format";

export interface VListOptions {
  rowHeight: number;
  count: number;
  render(index: number, row: HTMLElement): void;
  /** Called (debounced to animation frames) whenever the visible window changes. */
  onWindow?(first: number, last: number): void;
  label?: string;
  className?: string;
}

/** Max pixel height we allow for the scroll spacer; beyond it scrolling is rescaled. */
const MAX_PX = 8_000_000;

/**
 * Virtualized fixed-row-height list. Handles counts far beyond the browser's maximum element height
 * by remapping scroll position (the hex viewer relies on this for multi-GB files).
 */
export class VirtualList {
  readonly el: HTMLElement;
  private win: HTMLElement;
  private spacer: HTMLElement;
  private rows: HTMLElement[] = [];
  private count: number;
  private first = 0;
  private viewH = 0;
  private scaled = false;
  private programmatic = false;
  private raf = 0;
  private ro: ResizeObserver;
  private lastWindow = "";

  constructor(parent: HTMLElement, private readonly o: VListOptions) {
    this.count = o.count;
    this.spacer = el("div", { class: "fv-vl-spacer" });
    this.win = el("div", { class: "fv-vl-win" });
    this.el = el("div", { class: `fv-vl ${o.className ?? ""}`, tabindex: "0", role: "list", "aria-label": o.label ?? "list" }, this.win, this.spacer);
    parent.append(this.el);
    this.el.addEventListener("scroll", () => this.onScroll(), { passive: true });
    this.el.addEventListener("wheel", (e) => this.onWheel(e), { passive: false });
    this.el.addEventListener("keydown", (e) => this.onKey(e));
    this.ro = new ResizeObserver(() => this.layout());
    this.ro.observe(this.el);
    this.layout();
  }

  get firstIndex(): number { return this.first; }
  get visibleCount(): number { return Math.max(1, Math.floor(this.viewH / this.o.rowHeight)); }
  get length(): number { return this.count; }

  setCount(n: number) {
    this.count = n;
    this.layout();
    this.setFirst(Math.min(this.first, this.maxFirst()));
  }

  refresh() { this.draw(); }

  destroy() { this.ro.disconnect(); cancelAnimationFrame(this.raf); this.el.remove(); }

  scrollToIndex(i: number, align: "start" | "center" = "center") {
    const vis = this.visibleCount;
    this.setFirst(align === "center" ? i - Math.floor(vis / 2) : i);
  }

  private maxFirst(): number { return Math.max(0, this.count - this.visibleCount); }

  private layout() {
    this.viewH = this.el.clientHeight;
    const total = this.count * this.o.rowHeight;
    this.scaled = total > MAX_PX;
    this.spacer.style.height = `${Math.min(total, MAX_PX)}px`;
    this.win.style.height = `${this.viewH}px`;
    this.win.style.marginBottom = `${-this.viewH}px`;
    const need = Math.ceil(this.viewH / this.o.rowHeight) + 1;
    while (this.rows.length < need) { const r = el("div", { class: "fv-vl-row", role: "listitem" }); r.style.height = `${this.o.rowHeight}px`; this.rows.push(r); this.win.append(r); }
    while (this.rows.length > need) this.rows.pop()!.remove();
    this.draw();
  }

  private scrollRange(): number { return Math.max(1, this.spacerHeight() - this.viewH); }
  private spacerHeight(): number { return Math.min(this.count * this.o.rowHeight, MAX_PX); }

  private setFirst(f: number) {
    this.first = Math.max(0, Math.min(Math.round(f), this.maxFirst()));
    // reflect in scrollTop
    this.programmatic = true;
    if (this.scaled) {
      const mf = this.maxFirst();
      this.el.scrollTop = mf === 0 ? 0 : (this.first / mf) * this.scrollRange();
    } else this.el.scrollTop = this.first * this.o.rowHeight;
    requestAnimationFrame(() => { this.programmatic = false; });
    this.draw();
  }

  private onScroll() {
    if (this.programmatic) return;
    if (this.scaled) {
      const mf = this.maxFirst();
      this.first = Math.round((this.el.scrollTop / this.scrollRange()) * mf);
    } else this.first = Math.min(Math.floor(this.el.scrollTop / this.o.rowHeight), this.maxFirst() + 1);
    this.scheduleDraw();
  }

  private onWheel(e: WheelEvent) {
    if (!this.scaled) return;
    e.preventDefault();
    let d = e.deltaMode === 1 ? e.deltaY : e.deltaY / this.o.rowHeight;
    if (d !== 0 && Math.abs(d) < 1) d = Math.sign(d);
    this.setFirst(this.first + Math.round(d));
  }

  private onKey(e: KeyboardEvent) {
    const vis = this.visibleCount;
    const t = e.target as HTMLElement;
    if (t.tagName === "INPUT") return;
    let nf: number | null = null;
    switch (e.key) {
      case "ArrowDown": nf = this.first + 1; break;
      case "ArrowUp": nf = this.first - 1; break;
      case "PageDown": nf = this.first + vis - 1; break;
      case "PageUp": nf = this.first - vis + 1; break;
      case "Home": nf = 0; break;
      case "End": nf = this.maxFirst(); break;
    }
    if (nf !== null) { e.preventDefault(); this.setFirst(nf); }
  }

  private scheduleDraw() {
    if (this.raf) return;
    this.raf = requestAnimationFrame(() => { this.raf = 0; this.draw(); });
  }

  private draw() {
    const rh = this.o.rowHeight;
    let sub = 0;
    if (!this.scaled) sub = this.el.scrollTop - this.first * rh;
    this.win.style.setProperty("--fv-sub", `${-Math.max(0, Math.min(sub, rh))}px`);
    this.rows.forEach((r, k) => {
      const i = this.first + k;
      if (i < this.count) { r.hidden = false; this.o.render(i, r); } else { r.hidden = true; r.replaceChildren(); }
    });
    const last = Math.min(this.count - 1, this.first + this.rows.length - 1);
    const key = `${this.first}:${last}`;
    if (key !== this.lastWindow) { this.lastWindow = key; this.o.onWindow?.(this.first, last); }
  }
}
