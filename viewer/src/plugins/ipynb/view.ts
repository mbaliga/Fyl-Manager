import MarkdownIt from "markdown-it";
import { buildSrcdoc } from "../../core/security";
import { el } from "../../lib/format";
import type { PluginModule, ViewContext, ViewHandle } from "../../core/types";

const md = new MarkdownIt({ html: false, linkify: true });
const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]!);
const src = (v: unknown): string => (Array.isArray(v) ? v.join("") : String(v ?? ""));
const ansi = (s: string) => s.replace(/\u001b\[[0-9;]*[A-Za-z]/g, "");
const B64 = /^[A-Za-z0-9+/=\s]+$/;

function output(o: Record<string, unknown>): string {
  const t = o.output_type;
  if (t === "stream") return `<pre class="out">${esc(src(o.text))}</pre>`;
  if (t === "error") return `<pre class="out err">${esc(ansi(src(o.traceback) || `${o.ename}: ${o.evalue}`))}</pre>`;
  const data = (o.data ?? {}) as Record<string, unknown>;
  if (data["text/html"]) return `<div class="out">${src(data["text/html"])}</div>`; // sanitised later by buildSrcdoc
  if (data["image/svg+xml"]) return `<div class="out">${src(data["image/svg+xml"])}</div>`;
  for (const m of ["image/png", "image/jpeg", "image/gif"]) if (data[m] && B64.test(src(data[m]))) return `<img class="out" alt="output" src="data:${m};base64,${src(data[m]).replace(/\s/g, "")}">`;
  if (data["text/plain"]) return `<pre class="out">${esc(src(data["text/plain"]))}</pre>`;
  return "";
}

const mod: PluginModule = {
  async mount(ctx: ViewContext): Promise<ViewHandle> {
    const nb = JSON.parse(await (await ctx.file.blob()).text());
    const cells: Record<string, unknown>[] = nb.cells ?? (nb.worksheets?.[0]?.cells ?? []);
    const lang = nb.metadata?.language_info?.name ?? nb.metadata?.kernelspec?.language ?? "";
    let html = "";
    for (const c of cells) {
      if (c.cell_type === "markdown") html += `<section class="cell md">${md.render(src(c.source))}</section>`;
      else if (c.cell_type === "code") {
        html += `<section class="cell code"><div class="in">In [${c.execution_count ?? " "}]:</div><pre class="src"><code>${esc(src(c.source ?? c.input))}</code></pre>` +
          ((c.outputs as Record<string, unknown>[] | undefined) ?? []).map(output).join("") + `</section>`;
      } else html += `<section class="cell raw"><pre>${esc(src(c.source))}</pre></section>`;
    }
    const css = ".cell{margin:10px 0;padding:6px 10px;border-left:3px solid transparent}.code{border-left-color:#8884}.in{font:11px monospace;opacity:.6}.out{margin:4px 0}.err{color:#d33}.src{margin:2px 0}pre{white-space:pre-wrap}";
    const root = el("div", { class: "fv-ipynb" });
    root.append(el("div", { class: "fv-banner fv-banner-notice", role: "note", text: `Read-only notebook · ${cells.length} cells${lang ? ` · ${lang}` : ""} · outputs are sanitised and nothing is executed.`, "data-testid": "nb-note" }));
    const frame = el("iframe", { class: "fv-sandbox", sandbox: "", title: "Notebook", referrerpolicy: "no-referrer" });
    let theme = ctx.theme;
    const render = () => { frame.srcdoc = buildSrcdoc(html, theme, { css }); };
    render();
    root.append(frame);
    ctx.container.append(root);
    return { isDirty: () => false, onDirtyChange: () => {}, setTheme(t) { theme = t; render(); }, unmount: () => root.remove() };
  },
};
export default mod;
