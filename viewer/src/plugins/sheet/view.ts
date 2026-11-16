import { el } from "../../lib/format";
import { DataGrid } from "../../lib/grid";
import type { SheetData } from "../../workers/sheet.worker";
import type { PluginModule, ViewContext, ViewHandle } from "../../core/types";

const mod: PluginModule = {
  async mount(ctx: ViewContext): Promise<ViewHandle> {
    const editing = ctx.mode === "edit";
    const buf = await (await ctx.file.blob()).arrayBuffer();
    const w = new Worker(new URL("../../workers/sheet.worker.ts", import.meta.url), { type: "module" });
    const sheets = await new Promise<SheetData[]>((res, rej) => {
      w.onmessage = (e) => (e.data.ok ? res(e.data.sheets) : rej(new Error(e.data.error)));
      w.onerror = (e) => rej(new Error(e.message));
      w.postMessage({ buf, withRaw: editing }, [buf]);
    }).finally(() => w.terminate());

    const root = el("div", { class: "fv-sheet", style: "display:flex;flex-direction:column;flex:1;min-height:0" });
    const banner = el("div", { class: "fv-banner fv-banner-notice", role: "note", "data-testid": "sheet-banner", text: editing ? "Only cell values are edited. Formulas, formatting and charts may be lost when saving." : "" });
    banner.hidden = !editing;
    const tabs = el("div", { class: "fv-tabs", role: "tablist" });
    const status = el("div", { class: "fv-statusbar", role: "status" });
    root.append(banner, tabs);
    ctx.container.append(root);

    // working copies (strings) per sheet; originals kept for type-preserving serialization
    const work = sheets.map((s) => s.fmt.map((r) => r.slice()));
    const touched = new Set<number>();
    let cur = 0, version = 0, saved = 0;
    const dirtyCbs: ((d: boolean) => void)[] = [];
    const grid = new DataGrid({
      parent: root, rows: work[0] ?? [[""]], editable: editing, hasHeader: false,
      onChange: () => { touched.add(cur); version++; dirtyCbs.forEach((cb) => cb(true)); },
      onSelect: () => st(), signal: ctx.signal,
    });
    root.append(status);
    const st = () => {
      const s = sheets[cur];
      status.replaceChildren(el("span", { text: `${grid.rows.length.toLocaleString()} rows × ${grid.cols} cols` }), el("span", { text: s?.truncated ? "truncated to 500,000 rows" : "" }), el("span", { text: editing ? "Editing values" : "Read-only" }));
    };
    const show = (i: number) => {
      cur = i;
      grid.setRows(work[i] ?? [[""]]);
      tabs.querySelectorAll("button").forEach((b, k) => { b.classList.toggle("fv-btn-on", k === i); b.setAttribute("aria-selected", String(k === i)); });
      st();
    };
    sheets.forEach((s, i) => {
      const b = el("button", { class: "fv-btn", type: "button", role: "tab", text: s.name, "data-sheet": s.name });
      b.onclick = () => show(i);
      tabs.append(b);
    });
    show(0);

    return {
      isDirty: () => version !== saved,
      onDirtyChange: (cb) => void dirtyCbs.push(cb),
      markSaved: () => { saved = version; dirtyCbs.forEach((cb) => cb(false)); },
      async serialize() {
        const XLSX = await import("xlsx");
        const wb = XLSX.utils.book_new();
        sheets.forEach((s, i) => {
          const rows = work[i]!;
          const aoa = rows.map((r, ri) => r.map((v, ci) => {
            const orig = s.raw?.[ri]?.[ci];
            if (v === (s.fmt[ri]?.[ci] ?? "")) return orig === undefined || orig === "" ? (v === "" ? null : v) : orig;
            if (v === "") return null;
            if (typeof orig === "number" && Number.isFinite(Number(v))) return Number(v);
            if (typeof orig === "boolean" && /^(true|false)$/i.test(v)) return v.toLowerCase() === "true";
            return v;
          }));
          XLSX.utils.book_append_sheet(wb, XLSX.utils.aoa_to_sheet(aoa), s.name.slice(0, 31));
        });
        const bookType = ctx.info.ext === "ods" ? "ods" : "xlsx";
        const out = XLSX.write(wb, { type: "array", bookType, compression: true }) as ArrayBuffer;
        return new Blob([out]);
      },
      unmount() { grid.destroy(); root.remove(); },
    };
  },
};
export default mod;
