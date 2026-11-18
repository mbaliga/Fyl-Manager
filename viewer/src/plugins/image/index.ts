import type { FormatPlugin } from "../../core/types";

const EDITABLE = new Set(["image/png", "image/jpeg", "image/webp"]);
const VIEWABLE = new Set([...EDITABLE, "image/gif", "image/avif", "image/bmp", "image/vnd.microsoft.icon", "image/x-cursor", "image/svg+xml"]);

export const imagePlugin: FormatPlugin = {
  id: "image",
  name: "Image",
  maxLevel: 4,
  sizeLimits: { view: 256 * 1024 * 1024, edit: 40 * 1024 * 1024 },
  formats: ["png", "jpg", "gif", "webp", "avif", "bmp", "ico", "svg (sandboxed)"],
  match(info) {
    const d = info.detected;
    if (!d || !VIEWABLE.has(d)) return null;
    return { confidence: 0.9, level: EDITABLE.has(d) ? 4 : 3, label: "Image viewer" };
  },
  load: () => import("./view").then((m) => m.default),
};
