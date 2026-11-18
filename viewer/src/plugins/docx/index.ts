import type { FormatPlugin } from "../../core/types";

export const docxPlugin: FormatPlugin = {
  id: "docx",
  name: "Word document",
  maxLevel: 3,
  sizeLimits: { view: 64 * 1024 * 1024 },
  formats: ["docx"],
  match(info) {
    return info.detected?.includes("wordprocessingml.document") ? { confidence: 0.9, level: 3, label: "Document (converted to HTML)" } : null;
  },
  load: () => import("./view").then((m) => m.default),
};
