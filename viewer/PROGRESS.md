# Progress

Resume from here. Work lives in `viewer/`. Check: `npm run build && npm test && npm run test:e2e` (build first: e2e serves `dist/`).

## Done
- **Phase 0** — scaffold, core interfaces, detection (80+ signatures, zip refinement, text sniff), registry, session (dirty/save/save-as,
  sanity check), standalone + Fylz host classes, security helpers, Preact shell, Info/Hex/Text plugins, fixtures script, unit + e2e
  tests (incl. 1 GB sparse Hex), size check.

- **Phase 1** — all Tier 1 viewers: markdown, json/yaml/toml/xml (+tree), csv, image (+EXIF), media (+tags), pdf (pdf.js, thumbnails, search),
  archive (zip/tar/gz/tgz own readers; bz2/xz/7z/rar via libarchive.js WASM), docx (mammoth → sandbox), sheet (SheetJS in worker), pptx outline, font.

- **Phase 2** — light editing, all with e2e save round-trips: text/markdown/json (lint, format), csv (cells/rows/cols/delimiter),
  image (crop/rotate/flip/resize/annotate, JPEG quality), pdf (reorder/rotate/delete/extract/merge/annotate, flattened via pdf-lib),
  sheet (values only, xlsx/ods), zip (add/rename/remove). docx/pptx stay read-only.

## Next
- Phase 4 (done before Phase 3 because it is a required deliverable): PWA, mock Fylz host + e2e, FYLZ_INTEGRATION.md, Kotlin stub.
- Phase 3 plugins (heic, sqlite, epub, 3d, raw, geo, ics/vcf, ipynb, eml/mbox, apk/jar).
- Phase 4: PWA (manifest, service worker, launchQueue, share target), mock Fylz host page + e2e, docs, Kotlin stub.

## Known issues
- No RAR fixture (cannot generate one here); RAR support relies on libarchive.js and is untested.
- sample.7z is a committed hand-made fixture (py7zr); libarchive's 7z writer output was unreadable.
