import type { FormatPlugin } from "../../core/types";

const RAW_EXT = new Set(["cr2", "cr3", "nef", "nrw", "arw", "srf", "sr2", "dng", "orf", "rw2", "raf", "pef", "srw", "x3f", "3fr", "iiq", "erf", "mrw"]);

export const rawPlugin: FormatPlugin = {
  id: "raw",
  name: "RAW photo",
  maxLevel: 3,
  sizeLimits: { view: 512 * 1024 * 1024 },
  formats: ["cr2", "nef", "arw", "dng", "orf", "rw2", "raf", "…"],
  match(info) {
    return RAW_EXT.has(info.ext) ? { confidence: 0.95, level: 3, label: "Embedded JPEG preview + EXIF" } : null;
  },
  load: () => import("./view").then((m) => m.default),
};
