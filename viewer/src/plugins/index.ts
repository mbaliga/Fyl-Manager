import { Registry } from "../core/registry";
import { infoPlugin } from "./info";
import { textPlugin } from "./text";
import { hexPlugin } from "./hex";
import { markdownPlugin } from "./markdown";
import { jsonPlugin } from "./json";
import { csvPlugin } from "./csv";
import { imagePlugin } from "./image";
import { mediaPlugin } from "./media";
import { pdfPlugin } from "./pdf";
import { archivePlugin } from "./archive";
import { docxPlugin } from "./docx";
import { sheetPlugin } from "./sheet";
import { pptxPlugin } from "./pptx";
import { fontPlugin } from "./font";
import { heicPlugin } from "./heic";
import { sqlitePlugin } from "./sqlite";
import { epubPlugin } from "./epub";
import { model3dPlugin } from "./model3d";
import { rawPlugin } from "./raw";
import { geoPlugin } from "./geo";
import { calPlugin } from "./cal";
import { ipynbPlugin } from "./ipynb";
import { mailPlugin } from "./mail";
import { packagePlugin } from "./package";

/** All plugin descriptors. Each is tiny; the heavy `view` modules are lazy-loaded on demand. */
export const ALL_PLUGINS = [
  infoPlugin, textPlugin, hexPlugin, markdownPlugin, jsonPlugin, csvPlugin, imagePlugin, mediaPlugin,
  pdfPlugin, archivePlugin, docxPlugin, sheetPlugin, pptxPlugin, fontPlugin,
  heicPlugin, sqlitePlugin, epubPlugin, model3dPlugin, rawPlugin, geoPlugin, calPlugin, ipynbPlugin, mailPlugin, packagePlugin,
];

export function createRegistry(): Registry {
  const r = new Registry();
  for (const p of ALL_PLUGINS) r.register(p);
  return r;
}
