import type { FormatPlugin } from "../../core/types";

export const packagePlugin: FormatPlugin = {
  id: "package",
  name: "App package",
  maxLevel: 2,
  sizeLimits: { view: 512 * 1024 * 1024 },
  formats: ["apk", "jar"],
  match(info) {
    const d = info.detected;
    return d === "application/vnd.android.package-archive" || d === "application/java-archive" ? { confidence: 0.95, level: 2, label: "Manifest summary + contents" } : null;
  },
  load: () => import("./view").then((m) => m.default),
};
