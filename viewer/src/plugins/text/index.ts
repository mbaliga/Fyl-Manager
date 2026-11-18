import type { FormatPlugin } from "../../core/types";

const CODE_EXT = new Set(("txt log ini env conf cfg properties gitignore editorconfig sh bash zsh fish bat cmd ps1 py rb php pl lua r jl " +
  "js mjs cjs ts tsx jsx java kt kts scala groovy gradle c h cc cpp cxx hpp hh cs go rs swift m mm dart zig nim " +
  "css scss sass less html htm xhtml vue svelte sql graphql gql proto tf hcl dockerfile makefile mk cmake " +
  "yaml yml toml xml json jsonc ndjson csv tsv md markdown mdx rst tex bib srt vtt diff patch lock").split(" "));

export const textPlugin: FormatPlugin = {
  id: "text",
  name: "Text",
  maxLevel: 5,
  sizeLimits: { edit: 20 * 1024 * 1024 },
  formats: ["txt", "log", "ini", "env", "100+ code languages"],
  match(info) {
    if (!info.isText) return null;
    const known = CODE_EXT.has(info.ext) || /^(dockerfile|makefile|readme|license)$/i.test(info.name);
    return { confidence: known ? 0.6 : 0.5, level: 5, label: "Text / code editor" };
  },
  load: () => import("./view").then((m) => m.default),
};
