# Progress

Resume from here. Work lives in `viewer/`. Check: `npm run build && npm test && npm run test:e2e` (build first: e2e serves `dist/`).

## Done
- **Phase 0** — scaffold, core interfaces, detection (80+ signatures, zip refinement, text sniff), registry, session (dirty/save/save-as,
  sanity check), standalone + Fylz host classes, security helpers, Preact shell, Info/Hex/Text plugins, fixtures script, unit + e2e
  tests (incl. 1 GB sparse Hex), size check.

- **Phase 1** — all Tier 1 viewers: markdown, json/yaml/toml/xml (+tree), csv, image (+EXIF), media (+tags), pdf (pdf.js, thumbnails, search),
  archive (zip/tar/gz/tgz own readers; bz2/xz/7z/rar via libarchive.js WASM), docx (mammoth → sandbox), sheet (SheetJS in worker), pptx outline, font.
  Edit-capable code for csv/image/pdf/sheet/zip/text/json/markdown is written (Phase 2 code) but only text edit is e2e-tested so far.

## Next
- Phase 2: e2e + unit round-trip tests for every editor (csv, image, pdf, sheet, zip, markdown, json), fix what they find.
- Phase 3 plugins (heic, sqlite, epub, 3d, raw, geo, ics/vcf, ipynb, eml/mbox, apk/jar).
- Phase 4: PWA (manifest, service worker, launchQueue, share target), mock Fylz host page + e2e, docs, Kotlin stub.

## Known issues
- No RAR fixture (cannot generate one here); RAR support relies on libarchive.js and is untested.
- sample.7z is a committed hand-made fixture (py7zr); libarchive's 7z writer output was unreadable.
