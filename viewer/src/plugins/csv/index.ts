import type { FormatPlugin } from "../../core/types";

export const csvPlugin: FormatPlugin = {
  id: "csv",
  name: "Table (CSV)",
  maxLevel: 4,
  sizeLimits: { view: 64 * 1024 * 1024, edit: 16 * 1024 * 1024 },
  formats: ["csv", "tsv"],
  match(info) {
    if (!info.isText) return null;
    if (info.ext === "csv" || info.ext === "tsv") return { confidence: 0.92, level: 4, label: "CSV grid" };
    return null;
  },
  load: () => import("./view").then((m) => m.default),
};
