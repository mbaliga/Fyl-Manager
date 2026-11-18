# Decisions

Judgement calls made while building, newest last. The owner reviews these at the end.

1. **Location.** Only `mbaliga/Fyl-Manager` was available in the session, so the viewer lives in `viewer/` inside it
   as a self-contained npm project (own `package.json`, no Gradle coupling). It can be split into its own repo with
   `git subtree split`. Per the brief, the only changes outside `viewer/` are `docs/FYLZ_INTEGRATION.md` pointers and a stub Kotlin class.
2. **Capability ladder.** The Fyl-Manager repo has no existing "capability ladder 0–5" definition (searched), so the
   level names from the brief are used verbatim (`LEVEL_NAMES` in `src/core/types.ts`).
3. **"Initial bundle".** The brief says the initial bundle holds core + shell + Hex/Text/Info *plugins* but also that every
   plugin is loaded via `import()`. Resolution: the initial bundle holds core, shell and all plugin *descriptors*; the Hex/Text/Info
   *views* are separate lazy chunks (precached by the service worker). `scripts/size-check.ts` enforces < 150 KB gz on what `index.html` loads.
4. **Interface extensions** (all optional, backwards compatible):
   - `FileInfo.detectedExt`, `FileInfo.lastModified`.
   - `FormatPlugin.formats` / `flagged` (feed the README table and licence flags).
   - `ViewHandle.markSaved()`, `onCommandsChange()`, `setTheme()` — the session needs to reset the dirty baseline after save.
   - `ViewContext.openNested()` / `openAs()` — archives and email attachments open entries recursively.
5. **Edit mode.** Plugins whose effective level ≥ 4 start in *view* mode; the toolbar "Edit" toggle remounts the plugin in `edit`
   mode. Rationale: nothing is writable by accident; "Save" only exists in edit mode.
6. **Save sanity check.** After `serialize()`, the blob is re-detected. Allowed: text→text, or identical detected MIME. Anything else is refused.
7. **"Open as Text" on binary files** is view-only (level 3) so a lossy decode can never be saved over a binary file.
8. **Text encodings.** UTF-8, UTF-8 BOM, UTF-16 LE/BE (BOM required), windows-1252 fallback for non-UTF-8 text. Files > 20 MB use the
   read-only chunked viewer, which decodes as UTF-8 only (UTF-16 > 20 MB is shown lossy).
9. **Strings extraction** runs on the main thread in 1 MB slices (yielding between slices, cancellable) rather than a worker, because
   host-backed sources can only be read from the main thread. UTF-16 strings straddling a 1 MB boundary may be split.
   SHA-256 *does* run in a worker with backpressure (own streaming implementation; WebCrypto has no incremental API).
10. **Virtual scrolling** remaps scroll position when the spacer would exceed 8,000,000 px, so 4 GB files work; wheel/keyboard move by rows, the scrollbar is proportional.
11. **Host bridge extras.** Added `saved {saveId, ok, error?}` (host → viewer, completes a save) and `closed {fileId, ok}` (viewer → host,
    answer to `close`), plus `protocol` version in `ready`. Binary payloads on Android are a JSON header with `binary:true` followed by an `ArrayBuffer` message.
    See `docs/HOST_BRIDGE.md`.
12. **CSP** is injected only into production builds (the dev server needs inline HMR). `font-src` allows `blob:`/`data:` (needed for the font plugin) in addition to the brief's list.
13. **Chromium for e2e** is the preinstalled `/opt/pw-browsers/chromium` (Playwright 1.63 pinned to match). H.264/AAC are not available there, so media e2e uses webm/opus/mp3 fixtures.
14. **Phase order.** Phase 4 (PWA + host bridge + Android integration doc) is built before Phase 3 because it contains required deliverables; Phase 3 plugins are "as time allows".
15. **Grid selection** toggles CSS classes in place instead of re-rendering rows: replacing the cell under the pointer swallowed `dblclick`.
16. **Inputs for in-canvas text** (image/PDF annotations) open on `click`, not `pointerdown`, because the pointer's default focus change blurs a just-created input.
17. **`showSaveFilePicker` / `showOpenFilePicker`** are skipped when `navigator.webdriver` is set so e2e runs fall back to download / file input.
18. **Phase 3 delivered in full** (heic, sqlite, epub, 3D, RAW, geo, ics/vcf, ipynb, eml/mbox, apk/jar), each with a generated fixture (HEIC and 7z are the two committed hand-made ones) and an e2e test.
19. **RAW** support extracts the largest embedded JPEG by scanning the file (works for TIFF-based and RAF/CR3-style containers alike) rather than walking each vendor's IFD layout; EXIF via exifr. The sensor data is never developed.
20. **APK manifests** are decoded with a small AXML reader (`plugins/package/axml.ts`), validated against a generated AXML fixture. Real-world APKs with unusual string-pool encodings are untested.
21. **SQLite** opens read-only in practice: `PRAGMA query_only=ON` plus a single-statement SELECT/WITH/PRAGMA/EXPLAIN filter for the console. Tables show the first 100,000 rows in the virtualized grid.
22. **HTML files** open in the text editor (source). A rendered preview would need the sandbox pipeline; "Open as…" does not offer it yet.
23. **WebGL** is required for 3D; without it the session falls back to Hex with a notice.
