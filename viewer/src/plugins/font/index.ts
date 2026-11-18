import type { FormatPlugin } from "../../core/types";

export const fontPlugin: FormatPlugin = {
  id: "font",
  name: "Font",
  maxLevel: 3,
  sizeLimits: { view: 64 * 1024 * 1024 },
  formats: ["ttf", "otf", "woff", "woff2"],
  match(info) {
    const d = info.detected ?? "";
    return d === "font/ttf" || d === "font/otf" || d === "font/woff" || d === "font/woff2" || d === "font/collection" ? { confidence: 0.9, level: 3, label: "Font preview" } : null;
  },
  load: () => import("./view").then((m) => m.default),
};
