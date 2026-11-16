import type { FormatPlugin } from "../../core/types";

export const pdfPlugin: FormatPlugin = {
  id: "pdf",
  name: "PDF",
  maxLevel: 4,
  sizeLimits: { view: 256 * 1024 * 1024, edit: 64 * 1024 * 1024 },
  formats: ["pdf (page ops, annotate)"],
  match(info) {
    return info.detected === "application/pdf" ? { confidence: 0.95, level: 4, label: "PDF viewer" } : null;
  },
  load: () => import("./view").then((m) => m.default),
};
