import type { FormatPlugin } from "../../core/types";

export const markdownPlugin: FormatPlugin = {
  id: "markdown",
  name: "Markdown",
  maxLevel: 5,
  sizeLimits: { edit: 20 * 1024 * 1024 },
  formats: ["md", "markdown", "mdx"],
  match(info) {
    if (!info.isText) return null;
    if (["md", "markdown", "mdx"].includes(info.ext)) return { confidence: 0.9, level: 5, label: "Markdown editor + preview" };
    return null;
  },
  load: () => import("./view").then((m) => m.default),
};
