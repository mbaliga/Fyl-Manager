import type { FormatPlugin } from "../../core/types";

export const infoPlugin: FormatPlugin = {
  id: "info",
  name: "Info",
  maxLevel: 0,
  formats: ["all files"],
  match: () => ({ confidence: 0.001, level: 0, label: "Properties" }),
  load: () => import("./view").then((m) => m.default),
};
