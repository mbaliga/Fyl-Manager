import type { FormatPlugin } from "../../core/types";

export const ipynbPlugin: FormatPlugin = {
  id: "ipynb",
  name: "Jupyter notebook",
  maxLevel: 3,
  sizeLimits: { view: 64 * 1024 * 1024 },
  formats: ["ipynb"],
  match(info) {
    return info.isText && info.ext === "ipynb" ? { confidence: 0.94, level: 3, label: "Read-only cells" } : null;
  },
  load: () => import("./view").then((m) => m.default),
};
