/** Reports gzipped size of the initial bundle (everything index.html loads eagerly). Fails above 150 KB. */
import { readFileSync, existsSync } from "node:fs";
import { gzipSync } from "node:zlib";
import { join } from "node:path";

const dist = join(import.meta.dirname, "..", "dist");
const html = readFileSync(join(dist, "index.html"), "utf8");
const refs = [...html.matchAll(/(?:src|href)="\.\/(assets\/[^"]+\.(?:js|css))"/g)].map((m) => m[1]!);
let total = 0;
for (const r of refs) {
  const f = join(dist, r);
  if (!existsSync(f)) continue;
  const gz = gzipSync(readFileSync(f)).length;
  total += gz;
  console.log(`${(gz / 1024).toFixed(1).padStart(7)} KB gz  ${r}`);
}
console.log(`initial bundle: ${(total / 1024).toFixed(1)} KB gzipped (limit 150)`);
if (total > 150 * 1024) { console.error("initial bundle too large"); process.exit(1); }
