import { zipSync, type Zippable } from "fflate";
import { bytesSource } from "../../core/source";
import { el, formatBytes } from "../../lib/format";
import { VirtualList } from "../../lib/vlist";
import { loadZipForEdit, openArchive, MAX_EXTRACT, type ArchiveEntry, type ArchiveReader } from "../../lib/archive";
import type { Command, PluginModule, ViewContext, ViewHandle } from "../../core/types";

const COMPRESSED = /\.(zip|gz|png|jpe?g|gif|webp|mp3|mp4|webm|ogg|opus|flac|7z|rar|xz|bz2|pdf|docx|xlsx|pptx|woff2?)$/i;
const base = (p: string) => p.replace(/\/$/, "").split("/").pop() || "file";

function download(bytes: Uint8Array, name: string) {
  const a = el("a", { href: URL.createObjectURL(new Blob([bytes as BlobPart])), download: name });
  document.body.append(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 10_000);
}

const mod: PluginModule = {
  async mount(ctx: ViewContext): Promise<ViewHandle> {
    const editing = ctx.mode === "edit" && ctx.level >= 4 && ctx.info.detected === "application/zip";
    let reader: ArchiveReader | null = null;
    let files: Map<string, { data: Uint8Array }> | null = null; // edit model
    let entries: ArchiveEntry[];

    if (editing) {
      files = loadZipForEdit(new Uint8Array(await (await ctx.file.blob()).arrayBuffer()));
      entries = [];
    } else {
      reader = await openArchive(ctx.file, ctx.info.detected);
      entries = reader.entries;
    }
    const rebuild = () => {
      if (files) entries = [...files.entries()].map(([path, f]) => ({ path, size: f.data.length, dir: path.endsWith("/") }));
    };
    rebuild();

    let version = 0, saved = 0;
    const dirtyCbs: ((d: boolean) => void)[] = [];
    const touch = () => { version++; rebuild(); applyFilter(); dirtyCbs.forEach((c) => c(true)); };

    const root = el("div", { class: "fv-archive" });
    const bar = el("div", { class: "fv-toolbar2" });
    const filter = el("input", { class: "fv-input", type: "search", placeholder: "Filter entries", "aria-label": "Filter entries" });
    const openBtn = el("button", { class: "fv-btn", type: "button", text: "Open", "data-act": "open", disabled: true });
    const extractBtn = el("button", { class: "fv-btn", type: "button", text: "Extract", "data-act": "extract", disabled: true });
    bar.append(filter, openBtn, extractBtn);
    const status = el("div", { class: "fv-statusbar", role: "status" });
    const host = el("div", { class: "fv-pane" });
    root.append(bar, host, status);
    ctx.container.append(root);

    let shown: ArchiveEntry[] = entries;
    let selected = -1;
    const list = new VirtualList(host, {
      rowHeight: 24, count: 0, label: "Archive entries", className: "fv-arclist",
      render(i, row) {
        const e = shown[i];
        if (!e) return;
        row.classList.toggle("fv-row-sel", i === selected);
        row.replaceChildren(
          el("span", { class: "fv-arc-name", text: `${e.dir ? "📁" : "📄"} ${e.path}` }),
          el("span", { class: "fv-arc-size", text: e.dir ? "" : formatBytes(e.size) }),
          el("span", { class: "fv-arc-date fv-muted", text: e.mtime ? new Date(e.mtime).toLocaleDateString() : "" }),
        );
        row.onclick = () => { selected = i; syncButtons(); list.refresh(); };
        row.ondblclick = () => void openSel();
      },
    });
    const syncButtons = () => {
      const e = shown[selected];
      openBtn.disabled = !e || e.dir; extractBtn.disabled = !e || e.dir;
      status.replaceChildren(el("span", { text: `${entries.length.toLocaleString()} entries` }), el("span", { text: `${formatBytes(entries.reduce((a, e) => a + e.size, 0))} uncompressed` }), el("span", { text: reader?.kind ?? "zip" }), el("span", { text: editing ? "Editing" : "Read-only" }), ...(e ? [el("span", { text: e.path })] : []));
    };
    function applyFilter() {
      const q = filter.value.toLowerCase();
      shown = q ? entries.filter((e) => e.path.toLowerCase().includes(q)) : entries;
      selected = Math.min(selected, shown.length - 1);
      list.setCount(shown.length); list.refresh(); syncButtons();
    }
    filter.addEventListener("input", applyFilter);

    const bytesOf = async (e: ArchiveEntry): Promise<Uint8Array> => {
      if (files) return files.get(e.path)!.data;
      if (e.size > MAX_EXTRACT) throw new Error("Entry is too large to open in the browser");
      return reader!.extract(e);
    };
    const openSel = async () => {
      const e = shown[selected];
      if (!e || e.dir) return;
      try { ctx.openNested?.(bytesSource(await bytesOf(e), base(e.path))); } catch (err) { ctx.host.log("error", String(err)); throw err; }
    };
    openBtn.onclick = () => void openSel().catch((e) => showError(e));
    extractBtn.onclick = async () => { const e = shown[selected]; if (e && !e.dir) try { download(await bytesOf(e), base(e.path)); } catch (err) { showError(err); } };
    const showError = (e: unknown) => status.replaceChildren(el("span", { class: "fv-danger", role: "alert", text: e instanceof Error ? e.message : String(e) }));
    applyFilter();

    const need = () => { const e = shown[selected]; if (!e) throw new Error("Select an entry first"); return e; };
    const picker = el("input", { type: "file", multiple: true, hidden: true, "data-testid": "archive-add" });
    root.append(picker);
    picker.onchange = async () => {
      for (const f of Array.from(picker.files ?? [])) files!.set(f.name, { data: new Uint8Array(await f.arrayBuffer()) });
      picker.value = ""; touch();
    };
    const commands = (): Command[] => editing ? [
      { id: "zip-add", label: "Add files…", run: () => picker.click() },
      { id: "zip-remove", label: "Remove selected", run: () => { const e = need(); files!.delete(e.path); if (e.dir) for (const k of [...files!.keys()]) if (k.startsWith(e.path)) files!.delete(k); selected = -1; touch(); } },
      { id: "zip-rename", label: "Rename selected…", run: () => {
        const e = need();
        const inp = el("input", { class: "fv-cell-input", type: "text", "aria-label": "New name", "data-testid": "rename-input" });
        inp.value = e.path;
        Object.assign(inp.style, { position: "fixed", left: "50%", top: "40%", transform: "translateX(-50%)", width: "min(480px,90vw)", height: "34px" });
        document.body.append(inp); inp.focus(); inp.select();
        let done = false;
        const fin = (ok: boolean) => {
          if (done) return; done = true;
          const v = inp.value.trim(); inp.remove();
          if (!ok || !v || v === e.path) return;
          if (files!.has(v)) { showError(new Error(`"${v}" already exists`)); return; }
          const rebuilt = new Map<string, { data: Uint8Array }>();
          for (const [k, f] of files!) rebuilt.set(k === e.path ? v : k, f);
          files = rebuilt; touch();
        };
        inp.onkeydown = (ev) => { if (ev.key === "Enter") fin(true); else if (ev.key === "Escape") fin(false); };
        inp.onblur = () => fin(true);
      } },
    ] : [];

    return {
      isDirty: () => version !== saved,
      onDirtyChange: (cb) => void dirtyCbs.push(cb),
      markSaved: () => { saved = version; dirtyCbs.forEach((c) => c(false)); },
      commands,
      async serialize() {
        const z: Zippable = {};
        for (const [k, f] of files!) z[k] = k.endsWith("/") ? [new Uint8Array(0), { level: 0 }] : [f.data, { level: COMPRESSED.test(k) ? 0 : 6 }];
        return new Blob([zipSync(z) as BlobPart], { type: "application/zip" });
      },
      unmount() { list.destroy(); reader?.close(); root.remove(); },
    };
  },
};
export default mod;
