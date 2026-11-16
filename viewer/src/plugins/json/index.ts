import { kindOf } from "../../lib/kinds";
import type { FormatPlugin } from "../../core/types";

export const jsonPlugin: FormatPlugin = {
  id: "json",
  name: "Structured data",
  maxLevel: 5,
  sizeLimits: { edit: 20 * 1024 * 1024 },
  formats: ["json", "jsonc", "ndjson", "yaml", "toml", "xml"],
  match(info) {
    if (!info.isText) return null;
    const k = kindOf(info.ext);
    if (!k) return info.detected === "application/json" ? { confidence: 0.85, level: 5, label: "JSON editor + tree" } : null;
    if (info.ext === "svg" || info.ext === "gpx" || info.ext === "kml" || info.ext === "ipynb" || info.ext === "geojson") return null; // handled by dedicated plugins
    return { confidence: 0.9, level: 5, label: `${k.toUpperCase()} editor + tree` };
  },
  load: () => import("./view").then((m) => m.default),
};
