# Licences

Policy: MIT, Apache-2.0, BSD or ISC only. No GPL/AGPL. LGPL only for a separately lazy-loaded optional plugin, flagged here.
The core is open source (Apache-2.0).

| Package | Licence | Used by |
|---|---|---|
| preact | MIT | shell |
| fflate | MIT | zip/gzip, docx/pptx parsing |
| codemirror 6 + @codemirror/* + @lezer/* | MIT | text, markdown, json |
| markdown-it | MIT | markdown preview |
| dompurify | Apache-2.0 (dual MPL-2.0; we use it under Apache-2.0) | sanitising HTML/SVG |
| xlsx (SheetJS CE, from cdn.sheetjs.com) | Apache-2.0 | sheet |
| sql.js | MIT | sqlite (phase 3) |
| pdf-lib (dev + runtime for PDF edit) | MIT | pdf editing |
| pngjs (dev only) | MIT | fixture generation |
| pdfjs-dist | Apache-2.0 | pdf |
| mammoth | BSD-2-Clause | docx |
| exifr | MIT | image EXIF, RAW previews |
| music-metadata | MIT | media tags |
| opentype.js | MIT | font |
| libarchive.js (+ libarchive WASM, BSD) | MIT | 7z/rar/bz2/xz archives |
| yaml | ISC | YAML validation / tree |
| smol-toml | BSD-3-Clause | TOML validation / tree |
| three | MIT | 3D models |

Flagged (LGPL, lazy-loaded, optional):

| Package | Licence | Where | Notes |
|---|---|---|---|
| libheif-js (libheif compiled to WASM) | **LGPL-3.0** | only in the lazily loaded `heic` plugin chunk (`src/plugins/heic/view.ts`) | Not part of the initial bundle. Source is unmodified from npm; the chunk is a separate file in `dist/assets/` that can be replaced by a relinked build. Remove the plugin from `src/plugins/index.ts` to ship without it. |

Run `npx license-checker --summary` (or inspect `package-lock.json`) when adding dependencies.
