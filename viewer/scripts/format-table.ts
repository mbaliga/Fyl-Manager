/** Regenerates the supported-formats table in README.md from plugin descriptors. */
import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { ALL_PLUGINS } from "../src/plugins";
import { LEVEL_NAMES } from "../src/core/types";

const rows = ALL_PLUGINS.map((p) => {
  const lim = p.sizeLimits ? [p.sizeLimits.view && `view ≤ ${mb(p.sizeLimits.view)}`, p.sizeLimits.edit && `edit ≤ ${mb(p.sizeLimits.edit)}`].filter(Boolean).join(", ") : "—";
  return `| ${p.name} | ${(p.formats ?? []).join(", ")} | L${p.maxLevel} ${LEVEL_NAMES[p.maxLevel]} | ${lim} | ${p.flagged ?? "—"} |`;
});
function mb(n: number) { return n >= 1 << 30 ? `${n / (1 << 30)} GB` : `${n / (1 << 20)} MB`; }
const table = ["| Plugin | Formats | Max level | Size limits | Licence flag |", "|---|---|---|---|---|", ...rows].join("\n");

const path = join(import.meta.dirname, "..", "README.md");
const md = readFileSync(path, "utf8");
const next = md.replace(/<!-- formats:start -->[\s\S]*<!-- formats:end -->/, `<!-- formats:start -->\n${table}\n<!-- formats:end -->`);
writeFileSync(path, next);
console.log(`README table updated (${rows.length} plugins)`);
