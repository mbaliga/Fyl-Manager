import { el } from "./format";
import { VirtualList } from "./vlist";

export interface GridOptions {
  parent: HTMLElement;
  rows: string[][];
  editable: boolean;
  hasHeader: boolean;
  onChange?(r: number, c: number | null): void; // c === null → structural change
  onSelect?(sel: { r: number; c: number } | null): void;
  signal?: AbortSignal;
}

export const colName = (c: number) => { let s = "", n = c; do { s = String.fromCharCode(65 + (n % 26)) + s; n = Math.floor(n / 26) - 1; } while (n >= 0); return s; };

/** Virtualized, optionally editable grid shared by CSV and spreadsheet plugins. */
export class DataGrid {
  rows: string[][];
  cols: number;
  hasHeader: boolean;
  sel: { r: number; c: number } | null = null;
  readonly el: HTMLElement;
  private header: HTMLElement;
  private list: VirtualList;
  private widths: number[] = [];
  private input: HTMLInputElement | null = null;

  constructor(private readonly o: GridOptions) {
    this.rows = o.rows;
    this.hasHeader = o.hasHeader;
    this.cols = 1;
    this.header = el("div", { class: "fv-grid-head" });
    const host = el("div", { class: "fv-pane" });
    this.el = el("div", { class: "fv-csv" }, this.header, host);
    o.parent.append(this.el);
    this.list = new VirtualList(host, {
      rowHeight: 24, count: 0, label: "Table rows", className: "fv-gridlist",
      render: (i, row) => this.renderRow(i, row),
    });
    this.list.el.addEventListener("scroll", () => { this.header.style.transform = `translateX(${-this.list.el.scrollLeft}px)`; }, { passive: true });
    this.list.el.addEventListener("click", (e) => void this.clickCell(e));
    this.header.addEventListener("click", (e) => void this.clickCell(e));
    this.list.el.addEventListener("dblclick", (e) => { const t = this.clickCell(e); if (t) this.openInput(t); });
    this.header.addEventListener("dblclick", (e) => { const t = this.clickCell(e); if (t && this.hasHeader) this.openInput(t); });
    this.list.el.addEventListener("keydown", (e) => {
      if (!this.sel || this.input) return;
      if (e.key === "Enter" || e.key === "F2") { const t = this.cellEl(this.sel.r, this.sel.c); if (t) { e.preventDefault(); this.openInput(t); } }
      else if (e.key === "Tab") { e.preventDefault(); this.select(this.sel.r, Math.max(0, Math.min(this.cols - 1, this.sel.c + (e.shiftKey ? -1 : 1)))); }
    });
    o.signal?.addEventListener("abort", () => this.closeInput(false));
    this.relayout();
  }

  get dataStart() { return this.hasHeader ? 1 : 0; }
  private total() { return 48 + this.widths.reduce((a, b) => a + b, 0); }

  computeWidths() {
    this.cols = Math.max(1, ...this.rows.slice(0, 5000).map((r) => r.length));
    for (let c = 0; c < this.cols; c++) {
      let m = 3;
      for (let r = 0; r < Math.min(this.rows.length, 200); r++) m = Math.max(m, (this.rows[r]?.[c] ?? "").length);
      this.widths[c] = Math.max(60, Math.min(320, m * 8 + 16));
    }
    this.widths.length = this.cols;
  }

  private cell(r: number, c: number, text: string, extra = "") {
    const s = el("span", { class: `fv-cell ${extra}`, "data-r": r, "data-c": c, text });
    s.style.width = `${this.widths[c]}px`;
    return s;
  }
  private cellEl(r: number, c: number) { return this.el.querySelector<HTMLElement>(`.fv-cell[data-r="${r}"][data-c="${c}"]`); }

  private renderRow(i: number, row: HTMLElement) {
    const r = i + this.dataStart;
    row.style.width = `${this.total()}px`;
    const data = this.rows[r] ?? [];
    const idx = el("span", { class: "fv-cell fv-cell-idx", text: String(i + 1) });
    idx.style.width = "48px";
    const cells: Node[] = [idx];
    for (let c = 0; c < this.cols; c++) cells.push(this.cell(r, c, data[c] ?? "", this.sel && this.sel.r === r && this.sel.c === c ? "fv-cell-sel" : ""));
    row.replaceChildren(...cells);
  }

  private renderHeader() {
    this.header.replaceChildren();
    this.header.style.width = `${this.total()}px`;
    const corner = el("span", { class: "fv-cell fv-cell-idx", text: "#" });
    corner.style.width = "48px";
    this.header.append(corner);
    for (let c = 0; c < this.cols; c++) this.header.append(this.cell(0, c, this.hasHeader ? this.rows[0]?.[c] ?? "" : colName(c), this.hasHeader ? "fv-cell-h" : "fv-cell-h fv-muted"));
  }

  relayout() {
    this.computeWidths();
    this.list.setCount(Math.max(0, this.rows.length - this.dataStart));
    this.renderHeader();
    this.list.refresh();
  }

  setRows(rows: string[][]) { this.rows = rows; this.sel = null; this.relayout(); }
  setHeader(b: boolean) { this.hasHeader = b; this.relayout(); }

  select(r: number, c: number) { this.sel = { r, c }; this.list.refresh(); this.o.onSelect?.(this.sel); }
  private clickCell(e: Event) {
    const t = (e.target as HTMLElement).closest(".fv-cell[data-c]") as HTMLElement | null;
    if (!t) return null;
    this.select(Number(t.dataset.r), Number(t.dataset.c));
    return t;
  }

  private closeInput(commit: boolean) {
    if (!this.input || !this.sel) return;
    const inp = this.input; this.input = null;
    const { r, c } = this.sel;
    const v = inp.value;
    inp.remove();
    if (commit && (this.rows[r]?.[c] ?? "") !== v) {
      const row = this.rows[r]!;
      while (row.length <= c) row.push("");
      row[c] = v;
      this.relayout();
      this.o.onChange?.(r, c);
    }
    this.list.refresh();
  }
  private openInput(target: HTMLElement) {
    if (!this.o.editable || !this.sel) return;
    this.closeInput(true);
    const rect = target.getBoundingClientRect();
    const inp = el("input", { class: "fv-cell-input", type: "text", "aria-label": "Edit cell" });
    inp.value = this.rows[this.sel.r]?.[this.sel.c] ?? "";
    Object.assign(inp.style, { position: "fixed", left: `${rect.left}px`, top: `${rect.top}px`, width: `${Math.max(rect.width, 120)}px`, height: `${rect.height}px` });
    document.body.append(inp);
    this.input = inp;
    inp.focus(); inp.select();
    inp.addEventListener("keydown", (e) => {
      if (e.key === "Enter") { e.preventDefault(); this.closeInput(true); this.list.el.focus(); }
      else if (e.key === "Escape") { this.closeInput(false); this.list.el.focus(); }
      else if (e.key === "Tab") { e.preventDefault(); this.closeInput(true); if (this.sel) this.select(this.sel.r, Math.min(this.cols - 1, this.sel.c + 1)); this.list.el.focus(); }
    });
    inp.addEventListener("blur", () => this.closeInput(true));
  }

  private need() { if (!this.sel) throw new Error("Select a cell first"); return this.sel; }
  insertRow(below: boolean) {
    const at = this.sel ? (below ? this.sel.r + 1 : Math.max(this.sel.r, this.dataStart)) : this.rows.length;
    this.rows.splice(at, 0, new Array(this.cols).fill(""));
    this.relayout(); this.o.onChange?.(at, null);
  }
  deleteRow() {
    const s = this.need();
    if (this.rows.length <= 1) return;
    this.rows.splice(s.r, 1);
    this.sel = { r: Math.min(s.r, this.rows.length - 1), c: s.c };
    this.relayout(); this.o.onChange?.(s.r, null);
  }
  insertCol() {
    const s = this.need();
    this.rows.forEach((r) => { while (r.length < this.cols) r.push(""); r.splice(s.c + 1, 0, ""); });
    this.relayout(); this.o.onChange?.(s.r, null);
  }
  deleteCol() {
    const s = this.need();
    if (this.cols <= 1) return;
    this.rows.forEach((r) => r.splice(s.c, 1));
    this.sel = { r: s.r, c: Math.min(s.c, this.cols - 2) };
    this.relayout(); this.o.onChange?.(s.r, null);
  }
  destroy() { this.closeInput(false); this.list.destroy(); this.el.remove(); }
}
