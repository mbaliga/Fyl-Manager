import type { FormatPlugin } from "../../core/types";

export const sqlitePlugin: FormatPlugin = {
  id: "sqlite",
  name: "SQLite database",
  maxLevel: 3,
  sizeLimits: { view: 128 * 1024 * 1024 },
  formats: ["sqlite", "sqlite3", "db"],
  match(info) {
    return info.detected === "application/vnd.sqlite3" ? { confidence: 0.95, level: 3, label: "Tables, schema, read-only SQL" } : null;
  },
  load: () => import("./view").then((m) => m.default),
};
