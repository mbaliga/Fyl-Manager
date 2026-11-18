import initSqlJs, { type Database } from "sql.js";
import { el } from "../../lib/format";
import { DataGrid } from "../../lib/grid";
import type { PluginModule, ViewContext, ViewHandle } from "../../core/types";

const ROW_CAP = 100_000;
const q = (id: string) => `"${id.replace(/"/g, '""')}"`;
const READONLY_SQL = /^\s*(select|with|pragma|explain|values)\b/i;

const mod: PluginModule = {
  async mount(ctx: ViewContext): Promise<ViewHandle> {
    const SQL = await initSqlJs({ locateFile: (f) => new URL(`./sqljs/${f}`, document.baseURI).href });
    const bytes = new Uint8Array(await (await ctx.file.blob()).arrayBuffer());
    const db: Database = new SQL.Database(bytes);
    db.run("PRAGMA query_only = ON");

    const objs = (db.exec("SELECT type, name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type DESC, name")[0]?.values ?? []) as [string, string, string | null][];
    const root = el("div", { class: "fv-sqlite" });
    const side = el("nav", { class: "fv-sql-side", "aria-label": "Tables", "data-testid": "sql-tables" });
    const mainEl = el("div", { class: "fv-sql-main" });
    const schema = el("pre", { class: "fv-sql-schema", "data-testid": "sql-schema" });
    const gridHost = el("div", { class: "fv-pane", "data-testid": "sql-grid", style: "min-height:160px" });
    const consoleEl = el("div", { class: "fv-sql-console" });
    const ta = el("textarea", { class: "fv-input", rows: "2", "aria-label": "SQL", spellcheck: false, placeholder: "SELECT * FROM … (read-only)" });
    const run = el("button", { class: "fv-btn fv-btn-primary", type: "button", text: "Run", "data-act": "run-sql" });
    const msg = el("div", { class: "fv-statusbar", role: "status", "data-testid": "sql-status" });
    consoleEl.append(ta, run);
    mainEl.append(schema, consoleEl, gridHost, msg);
    root.append(side, mainEl);
    ctx.container.append(root);

    let grid: DataGrid | null = null;
    const show = (cols: string[], rows: unknown[][], note = "") => {
      grid?.destroy();
      const strs = [cols, ...rows.map((r) => r.map((v) => (v === null ? "NULL" : v instanceof Uint8Array ? `<blob ${v.length} B>` : String(v))))];
      grid = new DataGrid({ parent: gridHost, rows: strs, editable: false, hasHeader: true });
      msg.textContent = `${rows.length.toLocaleString()} row${rows.length === 1 ? "" : "s"}${note}`;
    };
    const exec = (sql: string, note = "") => {
      try {
        const res = db.exec(sql);
        const r = res[res.length - 1];
        if (!r) { show(["(no rows)"], [], note); return; }
        show(r.columns, r.values.slice(0, ROW_CAP), r.values.length > ROW_CAP ? ` (first ${ROW_CAP.toLocaleString()} shown)` : note);
      } catch (e) { msg.textContent = `Error: ${(e as Error).message}`; msg.classList.add("fv-danger"); return; }
      msg.classList.remove("fv-danger");
    };

    const select = (type: string, name: string, sql: string | null) => {
      schema.textContent = sql ?? `-- ${type} ${name}`;
      side.querySelectorAll("button").forEach((b) => b.classList.toggle("fv-btn-on", b.dataset.name === name));
      if (type === "table" || type === "view") {
        const n = db.exec(`SELECT COUNT(*) FROM ${q(name)}`)[0]?.values[0]?.[0];
        exec(`SELECT * FROM ${q(name)} LIMIT ${ROW_CAP}`, ` of ${Number(n).toLocaleString()}`);
      } else show(["(not a table)"], []);
    };
    for (const [type, name, sql] of objs) {
      const b = el("button", { class: "fv-btn", type: "button", "data-name": name, "data-type": type, text: `${type === "table" ? "▦" : type === "view" ? "◫" : "·"} ${name}` });
      b.onclick = () => select(type, name, sql);
      side.append(b);
    }
    run.onclick = () => {
      const s = ta.value.trim();
      if (!READONLY_SQL.test(s) || /;\s*\S/.test(s.replace(/;\s*$/, ""))) { msg.textContent = "Only a single read-only statement (SELECT, WITH, PRAGMA, EXPLAIN) is allowed."; msg.classList.add("fv-danger"); return; }
      exec(s);
    };
    ta.addEventListener("keydown", (e) => { if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) run.click(); });
    const first = objs.find((o) => o[0] === "table");
    if (first) select(first[0], first[1], first[2]); else { show(["(empty database)"], []); }

    return { isDirty: () => false, onDirtyChange: () => {}, unmount() { grid?.destroy(); db.close(); root.remove(); } };
  },
};
export default mod;
