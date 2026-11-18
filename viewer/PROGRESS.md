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

- **Phase 4** — PWA (manifest with file_handlers + share_target, generated `dist/sw.js` precaching all 370+ files incl. WASM, launchQueue,
  share-target handler), Fylz bridge (`FylzHost`, android + parent-window transports), mock host page (`dev/fylz-host.html`, `window.mockHost`)
  with e2e (range reads, chunked saves, failed save, close/dirty prompt, theme, read-only), offline e2e (pdf, xlsx, 7z wasm, hashing, docx with network off),
  `docs/HOST_BRIDGE.md`, `docs/FYLZ_INTEGRATION.md`, `docs/WRITING_A_PLUGIN.md`, Kotlin stub `app/.../viewer/FylzViewerBridge.kt` (no new deps).

- **Phase 3** — heic (libheif WASM, LGPL, flagged), sqlite (sql.js, read-only SQL console), epub, 3D (glb/gltf/obj/stl/ply), RAW preview + EXIF,
  geo (geojson/gpx/kml SVG plot), ics/vcf cards, ipynb, eml/mbox (attachments open recursively), apk/jar summary. Fixtures + unit + e2e for each.

## Next
- Nothing blocking. Ideas: HTML sandbox preview, RAR fixture, password-protected PDF/zip prompts, real-device validation of the Android bridge.
- Phase 3 plugins (heic, sqlite, epub, 3d, raw, geo, ics/vcf, ipynb, eml/mbox, apk/jar).
- Phase 4: PWA (manifest, service worker, launchQueue, share target), mock Fylz host page + e2e, docs, Kotlin stub.

## Known issues
- No RAR fixture (cannot generate one here); RAR support relies on libarchive.js and is untested.
- sample.7z is a committed hand-made fixture (py7zr); libarchive's 7z writer output was unreadable.
