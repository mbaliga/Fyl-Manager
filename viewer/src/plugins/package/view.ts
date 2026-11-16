import { strFromU8 } from "fflate";
import { openZip } from "../../lib/archive";
import { el, formatBytes } from "../../lib/format";
import { attr, findAll, parseAxml } from "./axml";
import type { PluginModule, ViewContext, ViewHandle } from "../../core/types";

function parseJarManifest(text: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const line of text.replace(/\r?\n /g, "").split(/\r?\n/)) { const i = line.indexOf(":"); if (i > 0 && !(line.slice(0, i) in out)) out[line.slice(0, i)] = line.slice(i + 1).trim(); }
  return out;
}

const mod: PluginModule = {
  async mount(ctx: ViewContext): Promise<ViewHandle> {
    const zip = await openZip(ctx.file);
    if (!zip) throw new Error("Cannot read this package as a zip archive");
    const names = zip.entries.map((e) => e.path);
    const isApk = ctx.info.detected === "application/vnd.android.package-archive";
    const rows: [string, string][] = [["Entries", String(names.length)], ["Size", formatBytes(ctx.info.size)]];
    const extra: HTMLElement[] = [];
    const read = async (p: string, max = 8 * 1024 * 1024) => { const e = zip.entries.find((x) => x.path === p); return e && e.size <= max ? zip.extract(e) : null; };

    if (isApk) {
      const bin = await read("AndroidManifest.xml");
      if (bin) {
        try {
          const m = parseAxml(bin);
          const sdk = findAll(m, "uses-sdk")[0];
          rows.push(["Package", String(attr(m, "package") ?? "?")], ["Version", `${attr(m, "versionName") ?? "?"} (code ${attr(m, "versionCode") ?? "?"})`]);
          if (sdk) rows.push(["Min / target SDK", `${attr(sdk, "minSdkVersion") ?? "?"} / ${attr(sdk, "targetSdkVersion") ?? "?"}`]);
          const app = findAll(m, "application")[0];
          if (app) rows.push(["Activities", String(findAll(app, "activity").length)], ["Services", String(findAll(app, "service").length)], ["Receivers", String(findAll(app, "receiver").length)]);
          const perms = findAll(m, "uses-permission").map((p) => String(attr(p, "name"))).filter(Boolean);
          if (perms.length) extra.push(el("details", { open: true, "data-testid": "perms" }, el("summary", { text: `${perms.length} permissions` }), el("ul", {}, ...perms.map((p) => el("li", { text: p })))));
        } catch (e) { rows.push(["Manifest", `could not be decoded (${(e as Error).message})`]); }
      }
      rows.push(["DEX files", String(names.filter((n) => /^classes\d*\.dex$/.test(n)).length)], ["Native libs", [...new Set(names.filter((n) => n.startsWith("lib/")).map((n) => n.split("/")[1]))].join(", ") || "none"]);
    } else {
      const mf = await read("META-INF/MANIFEST.MF");
      if (mf) {
        const m = parseJarManifest(strFromU8(mf));
        for (const k of ["Main-Class", "Implementation-Title", "Implementation-Version", "Bundle-Name", "Bundle-SymbolicName", "Created-By", "Automatic-Module-Name", "Multi-Release"]) if (m[k]) rows.push([k, m[k]!]);
      }
      const classes = names.filter((n) => n.endsWith(".class"));
      rows.push(["Classes", String(classes.length)], ["Packages", String(new Set(classes.map((c) => c.split("/").slice(0, -1).join("."))).size)]);
      rows.push(["Signed", names.some((n) => /^META-INF\/.*\.(SF|RSA|DSA|EC)$/.test(n)) ? "yes" : "no"]);
    }
    const root = el("div", { class: "fv-package" });
    const dl = el("dl", { class: "fv-kv", "data-testid": "pkg-summary" });
    for (const [k, v] of rows) dl.append(el("dt", { text: k }), el("dd", { text: v }));
    const browse = el("button", { class: "fv-btn fv-btn-primary", type: "button", text: "Browse contents", "data-act": "browse" });
    browse.onclick = () => ctx.openAs?.("archive");
    root.append(el("h3", { text: isApk ? "Android package" : "Java archive" }), dl, ...extra, browse);
    ctx.container.append(root);
    return { isDirty: () => false, onDirtyChange: () => {}, unmount() { root.remove(); } };
  },
};
export default mod;
