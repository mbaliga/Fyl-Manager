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

Flagged (LGPL, lazy-loaded, optional): _none yet_ — libheif-js will be listed here when added.

Run `npx license-checker --summary` (or inspect `package-lock.json`) when adding dependencies.
