import { el, formatBytes, formatDate } from "../../lib/format";
import { sha256 } from "../../lib/hash";
import type { PluginModule, ViewContext, ViewHandle } from "../../core/types";

/** Claimed (browser/host) type vs detected type, for the mismatch warning. */
function mismatch(claimed?: string, detected?: string): boolean {
  if (!claimed || !detected || claimed === "application/octet-stream") return false;
  const norm = (m: string) => m.split(";")[0]!.trim().toLowerCase();
  const a = norm(claimed), b = norm(detected);
  if (a === b) return false;
  if (a.startsWith("text/") && b.startsWith("text/")) return false;
  if (a === "application/zip" || b === "application/zip") return false;
  return true;
}

const AUTO_HASH_LIMIT = 64 * 1024 * 1024;

const mod: PluginModule = {
  async mount(ctx: ViewContext): Promise<ViewHandle> {
    const { info, file, container } = ctx;
    const root = el("section", { class: "fv-info", "aria-label": "File information" });
    const dl = el("dl", { class: "fv-kv" });
    const row = (k: string, v: string | Node, id?: string) => {
      const dd = el("dd", {}, v);
      if (id) dd.dataset.k = id;
      dl.append(el("dt", { text: k }), dd);
      return dd;
    };
    row("Name", info.name, "name");
    row("Size", `${formatBytes(info.size)} (${info.size.toLocaleString()} bytes)`, "size");
    row("Modified", formatDate(info.lastModified), "modified");
    row("Extension", info.ext ? `.${info.ext}` : "none", "ext");
    row("Claimed type", info.mimeHint || "—", "claimed");
    const det = row("Detected type", info.detected ?? "unknown", "detected");
    if (mismatch(info.mimeHint, info.detected)) det.append(" ", el("span", { class: "fv-badge fv-badge-warn", text: "differs from claimed" }));
    row("Text", info.isText ? `yes (${info.encoding ?? "utf-8"})` : "no (binary)", "text");

    const hashCell = el("dd", { "data-k": "sha256" });
    const hashText = el("code", { class: "fv-hash", text: "not computed" });
    const progress = el("progress", { max: "100", value: "0", hidden: true });
    const btn = el("button", { class: "fv-btn", type: "button", text: "Compute SHA-256" });
    const cancel = el("button", { class: "fv-btn", type: "button", text: "Cancel", hidden: true });
    hashCell.append(hashText, " ", btn, cancel, progress);
    dl.append(el("dt", { text: "SHA-256" }), hashCell);
    root.append(dl);
    container.append(root);

    let ac: AbortController | null = null;
    const run = async () => {
      ac?.abort();
      ac = new AbortController();
      const my = ac;
      ctx.signal.addEventListener("abort", () => my.abort());
      btn.hidden = true; cancel.hidden = false; progress.hidden = false;
      hashText.textContent = "computing…";
      try {
        const h = await sha256(file, (d, t) => { progress.value = (d / t) * 100; }, my.signal);
        hashText.textContent = h;
        btn.textContent = "Copy";
        btn.onclick = () => void navigator.clipboard?.writeText(h);
      } catch (e) {
        hashText.textContent = (e as Error).name === "AbortError" ? "cancelled" : `failed: ${(e as Error).message}`;
        btn.textContent = "Compute SHA-256";
        btn.onclick = run;
      } finally {
        btn.hidden = false; cancel.hidden = true; progress.hidden = true;
      }
    };
    btn.onclick = run;
    cancel.onclick = () => ac?.abort();
    if (info.size <= AUTO_HASH_LIMIT) void run();
    else hashText.textContent = "large file — compute on demand";

    return {
      isDirty: () => false,
      onDirtyChange: () => {},
      unmount() { ac?.abort(); root.remove(); },
    };
  },
};
export default mod;
