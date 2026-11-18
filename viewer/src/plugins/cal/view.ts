import { el } from "../../lib/format";
import { all, fmtDate, get, parseCal, type Component } from "./parse";
import type { PluginModule, ViewContext, ViewHandle } from "../../core/types";

const row = (k: string, v?: string) => (v ? [el("dt", { text: k }), el("dd", { text: v })] : []);

function card(c: Component): HTMLElement {
  const dl = el("dl", { class: "fv-kv" });
  let title = "";
  if (c.type === "VCARD") {
    title = get(c, "FN") ?? get(c, "N")?.split(";").filter(Boolean).reverse().join(" ") ?? "Contact";
    const org = get(c, "ORG")?.replace(/;/g, ", ");
    dl.append(...row("Organisation", org), ...row("Title", get(c, "TITLE")));
    for (const p of all(c, "TEL")) dl.append(...row(`Phone${p.params.TYPE ? ` (${p.params.TYPE.toLowerCase()})` : ""}`, p.value));
    for (const p of all(c, "EMAIL")) dl.append(...row(`Email${p.params.TYPE ? ` (${p.params.TYPE.toLowerCase()})` : ""}`, p.value));
    for (const p of all(c, "ADR")) dl.append(...row("Address", p.value.split(";").filter(Boolean).join(", ")));
    dl.append(...row("Birthday", get(c, "BDAY") && fmtDate(get(c, "BDAY")!)), ...row("URL", get(c, "URL")), ...row("Note", get(c, "NOTE")));
  } else if (c.type === "VEVENT" || c.type === "VTODO" || c.type === "VJOURNAL") {
    title = get(c, "SUMMARY") ?? c.type.slice(1).toLowerCase();
    const s = get(c, "DTSTART"), e = get(c, "DTEND") ?? get(c, "DUE");
    dl.append(...row("Starts", s && fmtDate(s)), ...row("Ends", e && fmtDate(e)), ...row("Location", get(c, "LOCATION")), ...row("Repeats", get(c, "RRULE")), ...row("Status", get(c, "STATUS")),
      ...row("Organiser", get(c, "ORGANIZER")?.replace(/^mailto:/i, "")));
    for (const a of all(c, "ATTENDEE")) dl.append(...row("Attendee", a.params.CN ? `${a.params.CN} <${a.value.replace(/^mailto:/i, "")}>` : a.value.replace(/^mailto:/i, "")));
    dl.append(...row("Description", get(c, "DESCRIPTION")));
  } else title = c.type;
  return el("article", { class: "fv-card", "data-type": c.type }, el("h3", { text: title }), dl);
}

const mod: PluginModule = {
  async mount(ctx: ViewContext): Promise<ViewHandle> {
    const comps = parseCal(await (await ctx.file.blob()).text());
    const cards: Component[] = [];
    const walk = (c: Component) => { if (["VCARD", "VEVENT", "VTODO", "VJOURNAL"].includes(c.type)) cards.push(c); else c.children.forEach(walk); };
    comps.forEach(walk);
    const root = el("div", { class: "fv-cards", "data-testid": "cards" }, el("div", { class: "fv-muted", text: `${cards.length} item${cards.length === 1 ? "" : "s"}` }), ...cards.map(card));
    if (!cards.length) root.append(el("p", { text: "No events or contacts found. Use Open as → Text to see the source." }));
    ctx.container.append(root);
    return { isDirty: () => false, onDirtyChange: () => {}, unmount: () => root.remove() };
  },
};
export default mod;
