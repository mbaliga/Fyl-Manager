import { el, hex } from "../../lib/format";
import { VirtualList } from "../../lib/vlist";
import { newScanState, scanAscii, scanUtf16, type FoundString } from "../../lib/strings";
import type { PluginModule, ViewContext, ViewHandle } from "../../core/types";

const BPR = 16; // bytes per row
const ROW_H = 18;
const CHUNK = 1 << 20;
const MAX_STRINGS = 200_000;

const mod: PluginModule = {
  async mount(ctx: ViewContext): Promise<ViewHandle> {
    const { file } = ctx;
    const root = el("div", { class: "fv-hex" });
    const bar = el("div", { class: "fv-toolbar2" });
    const gotoIn = el("input", { type: "text", placeholder: "Go to offset (0x…)", "aria-label": "Go to offset", class: "fv-input", size: "16" });
    const findIn = el("input", { type: "text", placeholder: "Find text or hex (e.g. 89 50 4e)", "aria-label": "Find", class: "fv-input", size: "22" });
    const findBtn = el("button", { class: "fv-btn", type: "button", text: "Find next" });
    const tabHex = el("button", { class: "fv-btn fv-btn-on", type: "button", text: "Hex", "aria-pressed": "true" });
    const tabStr = el("button", { class: "fv-btn", type: "button", text: "Strings", "aria-pressed": "false" });
    const pos = el("span", { class: "fv-muted", "data-k": "hexpos" });
    bar.append(tabHex, tabStr, gotoIn, findIn, findBtn, pos);
    const hexPane = el("div", { class: "fv-pane" });
    const strPane = el("div", { class: "fv-pane", hidden: true });
    root.append(bar, hexPane, strPane);
    ctx.container.append(root);

    const offW = file.size > 0xffffffff ? 12 : 8;
    const totalRows = Math.max(1, Math.ceil(file.size / BPR));
    let cache: { start: number; bytes: Uint8Array } = { start: -1, bytes: new Uint8Array(0) };
    let hl: { start: number; len: number } | null = null;
    let token = 0;

    const renderRow = (i: number, row: HTMLElement) => {
      const off = i * BPR;
      const rel = off - cache.start;
      const have = cache.start >= 0 && rel >= 0 && rel < cache.bytes.length;
      const n = Math.max(0, Math.min(BPR, file.size - off));
      let hx = "", as = "";
      const hlHere = hl && off < hl.start + hl.len && off + BPR > hl.start;
      const mark: Node[] = [];
      for (let k = 0; k < BPR; k++) {
        const sep = k === 8 ? "  " : " ";
        if (k < n && have && rel + k < cache.bytes.length) {
          const b = cache.bytes[rel + k]!;
          const h = hex(b);
          const a = b >= 0x20 && b < 0x7f ? String.fromCharCode(b) : ".";
          if (hlHere && off + k >= hl!.start && off + k < hl!.start + hl!.len) mark.push(el("mark", { text: h }), document.createTextNode(sep));
          else mark.push(document.createTextNode(h + sep));
          as += a;
        } else {
          mark.push(document.createTextNode(k < n ? "·· " : "   ".slice(0, 2) + sep.slice(0, 1)));
          if (k < n) as += " ";
        }
      }
      void hx;
      row.replaceChildren(el("span", { class: "fv-hex-off", text: off.toString(16).toUpperCase().padStart(offW, "0") + "  " }), ...mark, el("span", { class: "fv-hex-asc", text: " " + as }));
    };

    const load = async (first: number, last: number) => {
      const my = ++token;
      const start = first * BPR;
      const len = (last - first + 1) * BPR;
      try {
        const bytes = await file.read(start, len);
        if (my !== token || ctx.signal.aborted) return;
        cache = { start, bytes };
        list.refresh();
      } catch (e) {
        ctx.host.log("warn", "hex read failed", String(e));
      }
    };

    const list = new VirtualList(hexPane, {
      rowHeight: ROW_H, count: totalRows, label: "Hex dump", className: "fv-hexlist",
      render: renderRow,
      onWindow: (f, l) => {
        pos.textContent = `0x${(f * BPR).toString(16).toUpperCase()} / ${file.size.toLocaleString()} bytes`;
        void load(f, l);
      },
    });

    const jump = (offset: number, len = 0) => {
      offset = Math.max(0, Math.min(offset, Math.max(0, file.size - 1)));
      hl = len ? { start: offset, len } : null;
      list.scrollToIndex(Math.floor(offset / BPR));
      void load(list.firstIndex, list.firstIndex + list.visibleCount + 1);
    };
    gotoIn.addEventListener("keydown", (e) => {
      if (e.key !== "Enter") return;
      const v = gotoIn.value.trim();
      const n = /^0x/i.test(v) ? parseInt(v, 16) : /^[0-9a-f]+$/i.test(v) && /[a-f]/i.test(v) ? parseInt(v, 16) : parseInt(v, 10);
      if (Number.isFinite(n)) jump(n);
    });

    // ---- find
    let lastHit = -1;
    const patternOf = (s: string): Uint8Array | null => {
      const t = s.trim();
      if (!t) return null;
      if (/^([0-9a-f]{2}\s*)+$/i.test(t) && /\s|^[0-9a-f]{2}$/i.test(t)) return new Uint8Array(t.split(/\s+/).flatMap((h) => h.match(/../g) ?? []).map((h) => parseInt(h, 16)));
      return new TextEncoder().encode(t);
    };
    const findNext = async () => {
      const pat = patternOf(findIn.value);
      if (!pat) return;
      findBtn.disabled = true;
      const from = lastHit + 1;
      const scan = async (a: number, b: number) => {
        for (let off = a; off < b; off += CHUNK) {
          if (ctx.signal.aborted) return -1;
          const buf = await file.read(off, Math.min(CHUNK + pat.length, b - off + pat.length));
          outer: for (let i = 0; i + pat.length <= buf.length && off + i < b; i++) {
            for (let j = 0; j < pat.length; j++) if (buf[i + j] !== pat[j]) continue outer;
            return off + i;
          }
        }
        return -1;
      };
      let hit = await scan(from, file.size);
      if (hit < 0 && from > 0) hit = await scan(0, Math.min(file.size, from + pat.length));
      findBtn.disabled = false;
      if (hit >= 0) { lastHit = hit; jump(hit, pat.length); } else pos.textContent = "no match";
    };
    findBtn.onclick = () => void findNext();
    findIn.addEventListener("keydown", (e) => { if (e.key === "Enter") void findNext(); });
    findIn.addEventListener("input", () => { lastHit = -1; });

    // ---- strings
    const found: FoundString[] = [];
    let filtered: FoundString[] = found;
    const sBar = el("div", { class: "fv-toolbar2" });
    const sFilter = el("input", { type: "text", class: "fv-input", placeholder: "Filter strings", "aria-label": "Filter strings" });
    const sStatus = el("span", { class: "fv-muted", "data-k": "strstatus" });
    const sStop = el("button", { class: "fv-btn", type: "button", text: "Stop" });
    sBar.append(sFilter, sStatus, sStop);
    const sListHost = el("div", { class: "fv-pane" });
    strPane.append(sBar, sListHost);
    const sList = new VirtualList(sListHost, {
      rowHeight: 20, count: 0, label: "Extracted strings", className: "fv-strlist",
      render(i, row) {
        const s = filtered[i];
        if (!s) { row.textContent = ""; return; }
        row.replaceChildren(
          el("span", { class: "fv-hex-off", text: s.offset.toString(16).toUpperCase().padStart(offW, "0") + " " }),
          el("span", { class: "fv-badge", text: s.enc === "ascii" ? "A" : "U" }),
          " " + s.text.slice(0, 300),
        );
        row.onclick = () => { setTab("hex"); jump(s.offset, s.text.length * (s.enc === "utf16" ? 2 : 1)); };
      },
    });
    let scanning = false;
    let scanAbort: AbortController | null = null;
    const applyFilter = () => {
      const q = sFilter.value.toLowerCase();
      filtered = q ? found.filter((s) => s.text.toLowerCase().includes(q)) : found;
      sList.setCount(filtered.length);
      sList.refresh();
    };
    sFilter.addEventListener("input", applyFilter);
    sStop.onclick = () => scanAbort?.abort();
    const startScan = async () => {
      if (scanning || found.length) return;
      scanning = true;
      scanAbort = new AbortController();
      const sig = scanAbort.signal;
      ctx.signal.addEventListener("abort", () => scanAbort?.abort());
      const st = newScanState();
      let off = 0;
      try {
        while (off < file.size && found.length < MAX_STRINGS && !sig.aborted) {
          const buf = await file.read(off, CHUNK);
          if (!buf.length) break;
          const a = scanAscii(buf, off, st, 4, off + buf.length >= file.size);
          const u = scanUtf16(buf, off, 4);
          for (const s of [...a, ...u].sort((x, y) => x.offset - y.offset)) if (found.length < MAX_STRINGS) found.push(s);
          off += buf.length;
          sStatus.textContent = `scanning ${Math.floor((off / file.size) * 100)}% · ${found.length.toLocaleString()} strings`;
          applyFilter();
          await new Promise((r) => setTimeout(r, 0));
        }
      } finally {
        scanning = false;
        sStop.hidden = true;
        sStatus.textContent = `${sig.aborted ? "stopped" : "done"} · ${found.length.toLocaleString()} strings${found.length >= MAX_STRINGS ? " (capped)" : ""}`;
      }
    };

    const setTab = (t: "hex" | "str") => {
      hexPane.hidden = t !== "hex";
      strPane.hidden = t !== "str";
      tabHex.classList.toggle("fv-btn-on", t === "hex");
      tabStr.classList.toggle("fv-btn-on", t === "str");
      tabHex.setAttribute("aria-pressed", String(t === "hex"));
      tabStr.setAttribute("aria-pressed", String(t === "str"));
      gotoIn.hidden = findIn.hidden = findBtn.hidden = t !== "hex";
      if (t === "str") { sList.refresh(); void startScan(); } else list.refresh();
    };
    tabHex.onclick = () => setTab("hex");
    tabStr.onclick = () => setTab("str");

    return {
      isDirty: () => false,
      onDirtyChange: () => {},
      unmount() { scanAbort?.abort(); token++; list.destroy(); sList.destroy(); root.remove(); },
    };
  },
};
export default mod;
