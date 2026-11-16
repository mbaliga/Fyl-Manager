import MarkdownIt from "markdown-it";
import { buildSrcdoc } from "../../core/security";
import { el } from "../../lib/format";
import { createTextCore } from "../text/core";
import type { PluginModule, ViewContext, ViewHandle } from "../../core/types";

const md = new MarkdownIt({ html: false, linkify: true, typographer: false });

type Layout = "editor" | "split" | "preview";

const mod: PluginModule = {
  async mount(ctx: ViewContext): Promise<ViewHandle> {
    let theme = ctx.theme;
    let timer = 0;
    const frame = el("iframe", { class: "fv-sandbox fv-md-preview", sandbox: "", title: "Markdown preview", referrerpolicy: "no-referrer" });
    const render = () => { frame.srcdoc = buildSrcdoc(md.render(core.getText()), theme); };
    const core = await createTextCore(ctx, { languageName: "x.md", onDoc: () => { clearTimeout(timer); timer = window.setTimeout(render, 150); } });
    core.body.append(frame);
    let layout: Layout = ctx.mode === "edit" ? "split" : "preview";
    const apply = () => {
      core.editorHost.hidden = layout === "preview";
      frame.hidden = layout === "editor";
      core.editorHost.style.flex = "1"; frame.style.flex = "1";
    };
    apply(); render();
    const h = core.handle;
    const base = h.commands!;
    const cycle = (l: Layout) => () => { layout = l; apply(); core.addCommands([]); };
    return {
      ...h,
      commands: () => [
        { id: "md-editor", label: `${layout === "editor" ? "● " : ""}Show editor`, run: cycle("editor") },
        { id: "md-split", label: `${layout === "split" ? "● " : ""}Split view`, run: cycle("split") },
        { id: "md-preview", label: `${layout === "preview" ? "● " : ""}Preview only`, run: cycle("preview") },
        ...base(),
      ],
      setTheme(t) { theme = t; render(); },
      unmount() { clearTimeout(timer); h.unmount(); },
    };
  },
};
export default mod;
