import type { FormatPlugin } from "../../core/types";

const ZIP_FAMILY = ["application/zip", "application/java-archive", "application/vnd.android.package-archive", "application/epub+zip"];
const NATIVE = new Set(["application/zip", "application/gzip", "application/x-tar", "application/x-bzip2", "application/x-xz", "application/x-7z-compressed", "application/vnd.rar"]);

export const archivePlugin: FormatPlugin = {
  id: "archive",
  name: "Archive",
  maxLevel: 4,
  sizeLimits: { edit: 128 * 1024 * 1024 },
  formats: ["zip (edit)", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar"],
  match(info) {
    const d = info.detected ?? "";
    if (NATIVE.has(d)) return { confidence: 0.9, level: d === "application/zip" ? 4 : 2, label: "Archive listing" };
    if (ZIP_FAMILY.includes(d) || d.includes("openxml") || d.includes("opendocument")) return { confidence: 0.3, level: 2, label: "Archive listing" };
    return null;
  },
  load: () => import("./view").then((m) => m.default),
};
