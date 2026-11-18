import { buildSrcdoc } from "../../core/security";
import { bytesSource } from "../../core/source";
import { el, formatBytes } from "../../lib/format";
import { parseMessage, splitMbox, type Message } from "../../lib/mime";
import type { PluginModule, ViewContext, ViewHandle } from "../../core/types";

const mod: PluginModule = {
  async mount(ctx: ViewContext): Promise<ViewHandle> {
    const bytes = new Uint8Array(await (await ctx.file.blob()).arrayBuffer());
    const isMbox = ctx.info.ext === "mbox" || /^From \S+/.test(new TextDecoder().decode(bytes.subarray(0, 8)));
    const raws = isMbox ? splitMbox(bytes) : [bytes];
    const msgs: Message[] = raws.map(parseMessage);
    let theme = ctx.theme;
    const root = el("div", { class: "fv-mail" });
    const list = el("nav", { class: "fv-mail-list", hidden: msgs.length < 2, "aria-label": "Messages", "data-testid": "mail-list" });
    const view = el("div", { class: "fv-mail-view" });
    root.append(list, view);
    ctx.container.append(root);
    let current = -1;
    const show = (i: number) => {
      current = i;
      const m = msgs[i]!;
      list.querySelectorAll("button").forEach((b, k) => b.classList.toggle("fv-btn-on", k === i));
      const hdr = el("dl", { class: "fv-kv fv-mail-hdr", "data-testid": "mail-headers" });
      for (const k of ["from", "to", "cc", "date", "subject"]) if (m.headers[k]) hdr.append(el("dt", { text: k[0]!.toUpperCase() + k.slice(1) }), el("dd", { text: m.headers[k]! }));
      view.replaceChildren(hdr);
      if (m.attachments.length) {
        const att = el("div", { class: "fv-mail-att", "data-testid": "attachments" }, el("strong", { text: `${m.attachments.length} attachment${m.attachments.length === 1 ? "" : "s"}` }));
        m.attachments.forEach((a) => {
          const open = el("button", { class: "fv-btn", type: "button", text: "Open", "data-act": "open-att" });
          open.onclick = () => ctx.openNested?.(bytesSource(a.bytes, a.name, a.type));
          att.append(el("div", { class: "fv-att-row" }, el("span", { text: `📎 ${a.name} (${formatBytes(a.bytes.length)})` }), open));
        });
        view.append(att);
      }
      if (m.html) {
        // cid: images and remote content are stripped by the sanitizer; nothing is fetched.
        view.append(el("iframe", { class: "fv-sandbox", sandbox: "", title: "Message body", referrerpolicy: "no-referrer", srcdoc: buildSrcdoc(m.html, theme), "data-testid": "mail-body" }));
      } else view.append(el("pre", { class: "fv-mail-text", text: m.text ?? "(no body)", "data-testid": "mail-body" }));
    };
    msgs.forEach((m, i) => {
      const b = el("button", { class: "fv-btn", type: "button", text: `${m.headers.subject ?? "(no subject)"} — ${m.headers.from ?? ""}` });
      b.onclick = () => show(i);
      list.append(b);
    });
    if (msgs.length) show(0); else view.append(el("p", { text: "No messages found." }));
    return { isDirty: () => false, onDirtyChange: () => {}, setTheme(t) { theme = t; if (current >= 0) show(current); }, unmount: () => root.remove() };
  },
};
export default mod;
