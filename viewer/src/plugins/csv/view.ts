import { el } from "../../lib/format";
import { DataGrid } from "../../lib/grid";
import { parseCsv, serializeCsv, type CsvMeta, type CsvParsed } from "../../lib/csv";
import { decode, encode, normalizeEncoding } from "../../lib/textcodec";
import type { Command, PluginModule, ViewContext, ViewHandle } from "../../core/types";

async function parse(text: string): Promise<CsvParsed> {
  if (text.length < 200_000) return parseCsv(text);
  const w = new Worker(new URL("../../workers/csv.worker.ts", import.meta.url), { type: "module" });
  try {
    return await new Promise<CsvParsed>((res, rej) => {
      w.onmessage = (e) => (e.data.ok ? res(e.data.result) : rej(new Error(e.data.error)));
      w.onerror = (e) => rej(new Error(e.message));
      w.postMessage({ text });
    });
  } finally { w.terminate(); }
}

const DELIMS: [string, string][] = [[",", "comma"], [";", "semicolon"], ["\t", "tab"], ["|", "pipe"]];
const dname = (d: string) => DELIMS.find((x) => x[0] === d)?.[1] ?? d;

const mod: PluginModule = {
  async mount(ctx: ViewContext): Promise<ViewHandle> {
    const enc = normalizeEncoding(ctx.info.encoding);
    const bytes = new Uint8Array(await (await ctx.file.blob()).arrayBuffer());
    const parsed = await parse(decode(bytes, enc));
    const meta: CsvMeta = { ...parsed.meta };
    const editing = ctx.mode === "edit";
    const header0 = parsed.rows.length > 1 && parsed.rows[0]!.every((c) => c !== "" && isNaN(Number(c)));

    let version = 0, saved = 0;
    const dirtyCbs: ((d: boolean) => void)[] = [];
    const cmdCbs: (() => void)[] = [];
    const touch = () => { version++; dirtyCbs.forEach((cb) => cb(true)); updateStatus(); };

    const root = el("div", { class: "fv-csv-root", style: "display:flex;flex-direction:column;flex:1;min-height:0" });
    const status = el("div", { class: "fv-statusbar", role: "status" });
    ctx.container.append(root);
    const grid = new DataGrid({ parent: root, rows: parsed.rows, editable: editing, hasHeader: header0, onChange: touch, onSelect: () => updateStatus(), signal: ctx.signal });
    root.append(status);
    function updateStatus() {
      status.replaceChildren(
        el("span", { text: `${grid.rows.length.toLocaleString()} rows × ${grid.cols} cols` }),
        el("span", { text: `delimiter: ${dname(meta.delimiter)}` }),
        el("span", { text: grid.sel ? `cell ${grid.sel.r + 1}:${grid.sel.c + 1}` : "" }),
        el("span", { text: editing ? "Editing" : "Read-only" }),
      );
    }
    updateStatus();

    const bump = () => cmdCbs.forEach((c) => c());
    const headerCmd: Command = { id: "header", label: grid.hasHeader ? "First row is data" : "First row is header", run: () => { grid.setHeader(!grid.hasHeader); bump(); } };
    const commands = (): Command[] => editing ? [
      { id: "row-below", label: "Insert row below", run: () => grid.insertRow(true) },
      { id: "row-above", label: "Insert row above", run: () => grid.insertRow(false) },
      { id: "row-del", label: "Delete row", run: () => grid.deleteRow() },
      { id: "col-right", label: "Insert column right", run: () => grid.insertCol() },
      { id: "col-del", label: "Delete column", run: () => grid.deleteCol() },
      { id: "delim", label: `Delimiter on save: ${dname(meta.delimiter)}`, run: () => { const i = DELIMS.findIndex((d) => d[0] === meta.delimiter); meta.delimiter = DELIMS[(i + 1) % DELIMS.length]![0]; touch(); bump(); } },
      { ...headerCmd, label: grid.hasHeader ? "First row is data" : "First row is header" },
    ] : [{ ...headerCmd, label: grid.hasHeader ? "First row is data" : "First row is header" }];

    return {
      isDirty: () => version !== saved,
      onDirtyChange: (cb) => void dirtyCbs.push(cb),
      onCommandsChange: (cb) => void cmdCbs.push(cb),
      markSaved: () => { saved = version; dirtyCbs.forEach((cb) => cb(false)); },
      async serialize() { return new Blob([encode(serializeCsv(grid.rows, meta), enc) as BlobPart]); },
      commands,
      unmount() { grid.destroy(); root.remove(); },
    };
  },
};
export default mod;
