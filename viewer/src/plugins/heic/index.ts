import type { FormatPlugin } from "../../core/types";

export const heicPlugin: FormatPlugin = {
  id: "heic",
  name: "HEIC / HEIF image",
  maxLevel: 3,
  sizeLimits: { view: 128 * 1024 * 1024 },
  formats: ["heic", "heif"],
  flagged: "LGPL-3.0 (libheif-js, lazy-loaded)",
  match(info) {
    return info.detected === "image/heic" || info.detected === "image/heif" ? { confidence: 0.95, level: 3, label: "Decoded with libheif (WASM)" } : null;
  },
  load: () => import("./view").then((m) => m.default),
};
