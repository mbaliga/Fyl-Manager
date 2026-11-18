import type { FormatPlugin } from "../../core/types";

export const pptxPlugin: FormatPlugin = {
  id: "pptx",
  name: "Presentation outline",
  maxLevel: 2,
  sizeLimits: { view: 256 * 1024 * 1024 },
  formats: ["pptx", "odp"],
  match(info) {
    const d = info.detected ?? "";
    if (d.includes("presentationml.presentation") || d === "application/vnd.oasis.opendocument.presentation") return { confidence: 0.9, level: 2, label: "Slide text outline" };
    return null;
  },
  load: () => import("./view").then((m) => m.default),
};
