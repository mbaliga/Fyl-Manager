import type { FormatPlugin } from "../../core/types";

export const epubPlugin: FormatPlugin = {
  id: "epub",
  name: "EPUB book",
  maxLevel: 3,
  sizeLimits: { view: 256 * 1024 * 1024 },
  formats: ["epub"],
  match(info) {
    return info.detected === "application/epub+zip" ? { confidence: 0.95, level: 3, label: "Chapters + table of contents" } : null;
  },
  load: () => import("./view").then((m) => m.default),
};
