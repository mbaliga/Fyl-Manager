import { linter, type Diagnostic } from "@codemirror/lint";
import { el } from "../../lib/format";
import { kindOf, parseStructured, type StructKind } from "../../lib/parsers";
import { createTextCore } from "../text/core";
import type { PluginModule, ViewContext, ViewHandle } from "../../core/types";

const PAGE = 200;

function renderValue(key: string | null, value: unknown, open: boolean): HTMLElement {
  const isObj = value !== null && typeof value === "object";
  if (!isObj) {
    const t = typeof value;
    return el("div", { class: "fv-tree-row" },
      key !== null ? el("span", { class: "fv-tree-key", text: key }) : "",
      key !== null ? ": " : "",
      el("span", { class: `fv-tree-${value === null ? "null" : t}`, text: t === "string" ? JSON.stringify(value) : String(value) }),
    );
  }
  const isArr = Array.isArray(value);
  const entries: [string, unknown][] = isArr ? (value as unknown[]).map((v, i) => [String(i), v]) : Object.entries(value as object);
  const d = el("details", { class: "fv-tree-node" });
  if (open) d.open = true;
  d.append(el("summary", {}, key !== null ? el("span", { class: "fv-tree-key", text: key }) : "", key !== null ? ": " : "",
    el("span", { class: "fv-muted", text: isArr ? `[${entries.length}]` : `{${entries.length}}` })));
  let rendered = 0;
  const holder = el("div", { class: "fv-tree-children" });
  const more = el("button", { class: "fv-btn", type: "button" });
  const fill = () => {
    const end = Math.min(entries.length, rendered + PAGE);
    for (; rendered < end; rendered++) holder.insertBefore(renderValue(entries[rendered]![0], entries[rendered]![1], false), more);
    more.hidden = rendered >= entries.length;
    more.textContent = `Show more (${entries.length - rendered} left)`;
  };
  holder.append(more);
  more.onclick = fill;
  d.append(holder);
  let init = false;
  const lazy = () => { if (d.open && !init) { init = true; fill(); } };
  d.addEventListener("toggle", lazy);
  if (open) lazy();
  return d;
}

const mod: PluginModule = {
  async mount(ctx: ViewContext): Promise<ViewHandle> {
    const kind: StructKind = kindOf(ctx.info.ext) ?? "json";
    const lint = linter((view) => {
      const text = view.state.doc.toString();
      if (text.length > 2_000_000) return [];
      const r = parseStructured(kind, text);
      const diags: Diagnostic[] = [];
      if (r.error) {
        let from = r.error.from ?? 0;
        if (r.error.line) from = view.state.doc.line(Math.min(view.state.doc.lines, r.error.line)).from + Math.max(0, (r.error.col ?? 1) - 1);
        from = Math.max(0, Math.min(from, view.state.doc.length));
        diags.push({ from, to: Math.min(view.state.doc.length, from + 1), severity: "error", message: r.error.message });
      }
      return diags;
    }, { delay: 300 });

    let treeOn = false;
    let timer = 0;
    const tree = el("div", { class: "fv-tree", hidden: true, "data-testid": "tree" });
    const refreshTree = () => {
      tree.replaceChildren();
      const r = parseStructured(kind, core.getText());
      if (r.error) tree.append(el("div", { class: "fv-banner fv-banner-error", text: `Cannot build tree: ${r.error.message}` }));
      else tree.append(renderValue(null, r.value, true));
    };
    const core = await createTextCore(ctx, { extraExtensions: [lint], onDoc: () => { if (treeOn) { clearTimeout(timer); timer = window.setTimeout(refreshTree, 250); } } });
    core.body.append(tree);
    const apply = () => { tree.hidden = !treeOn; if (treeOn) refreshTree(); core.addCommands([]); };
    const base = core.handle.commands!;
    const h = core.handle;
    return {
      ...h,
      commands: () => [
        { id: "tree", label: treeOn ? "Hide tree" : "Show tree", run: () => { treeOn = !treeOn; apply(); } },
        ...(kind === "json" && ctx.mode === "edit" ? [{
          id: "format", label: "Format JSON",
          run: () => { const r = parseStructured("json", core.getText()); if (r.error) throw new Error(`Cannot format: ${r.error.message}`); core.setText(JSON.stringify(r.value, null, 2)); },
        }] : []),
        { id: "validate", label: "Validate", run: () => { const r = parseStructured(kind, core.getText()); ctx.host.log(r.error ? "warn" : "info", r.error ? r.error.message : "valid"); if (r.error) throw new Error(`Invalid ${kind.toUpperCase()}: ${r.error.message}`); } },
        ...base(),
      ],
      unmount() { clearTimeout(timer); h.unmount(); },
    };
  },
};
export default mod;
