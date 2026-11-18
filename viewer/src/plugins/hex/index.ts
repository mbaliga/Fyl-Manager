import type { FormatPlugin } from "../../core/types";

export const hexPlugin: FormatPlugin = {
  id: "hex",
  name: "Hex",
  maxLevel: 1,
  formats: ["all files (fallback)"],
  match: () => ({ confidence: 0.01, level: 1, label: "Hex dump + strings" }),
  load: () => import("./view").then((m) => m.default),
};
