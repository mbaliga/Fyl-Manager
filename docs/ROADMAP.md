# Fylz delivery roadmap

This roadmap treats cross-platform file-manager parity as a direction, not a version-one promise. Reliability, data safety, and provider behavior come before feature count.

## Status legend

- **Working** — usable in the current foundation.
- **Engine staged** — underlying service exists but no complete user journey yet.
- **Planned** — specified but not implemented.
- **Research gate** — requires performance, licensing, security, or platform validation before commitment.

## 0.1 — workspace foundation

### Working

- SAF folder picker and persisted tree permissions.
- Restore previously granted roots.
- Folder traversal, breadcrumbs, parent navigation, refresh, and in-folder filtering.
- Multiple folder tabs and open-folder-in-new-tab.
- List, grid, and details views.
- Compact, comfortable, and detailed density.
- Adaptive left navigation + centre browser + docked right preview on wide windows.
- Draggable/resizable floating preview.
- Traditional and immersive shells.
- Markdown and agent-text preview.
- Bounded text reading and lightweight UTF-8 editing.
- Image preview, first-page PDF preview, and external open.
- System/light/dark, accents, and dynamic color.
- File-type unit tests and CI build/lint/test/APK job.

### Engine staged

- Ordinary and AES-256 ZIP creation.
- Password-protected ZIP extraction with path-traversal and symlink safeguards.

### Exit criteria

- Debug build, unit tests, and lint pass in GitHub Actions.
- APK artifact available from CI.
- No high-severity dependency or secret finding.
- Manual smoke test on phone portrait, phone landscape, and at least one tablet/foldable-size emulator.
- Large folder and large text-file limits documented.

## 0.2 — trustworthy file operations

### Planned

- Multi-select and selection mode.
- Create folder, create text/Markdown file.
- Rename and duplicate.
- Copy and move within/cross provider.
- Delete with provider-aware Trash/undo or explicit permanent-delete path.
- Share, open-with, and properties inspector.
- Collision choices: keep both, replace, skip, decide individually.
- Durable operation queue with item progress, cancel, retry, and failure details.
- Operation history/journal with sensitive-data minimization.
- Background/foreground execution appropriate to Android constraints.
- Drag and drop, keyboard selection, and mouse context actions.

### Tests and gates

- Cross-provider tests using local storage, removable-media simulation, and at least two document providers.
- Power loss/process death during copy/move.
- Name collision, insufficient space, revoked permission, offline provider, and partial-write cases.
- No delete-after-copy until destination verification succeeds.

## 0.3 — archive and document utility journeys

### Planned

- Wire ZIP create/extract UI to the staged engine.
- Password prompt with strength/confirmation, short-lived password handling, and no logging.
- Archive contents preview without extraction.
- Expanded-size, ratio, entry-count, depth, and disk-space guards.
- Cancellation, overwrite handling, and cleanup.
- PDF page thumbnails and navigation.
- PDF merge, split, rotate, reorder, and metadata editing where the selected open-source PDF stack is safe and license-compatible.
- Batch rename, checksums, duplicate detection, and folder-size calculation.

### Research gate

- Additional formats (7z, tar, gzip, rar read-only) only after native-library size, ABI, CVE response, and licensing review.

## 0.4 — scan to PDF

### Planned

- CameraX capture.
- Automatic edge detection with manual corner adjustment.
- Perspective correction, rotation, grayscale/B&W/color filters.
- Multi-page review, reorder, retake, delete.
- Quality/size presets and export through SAF.
- Optional OCR adapter and searchable text layer.
- Clear offline/remote processing disclosure.

### Exit criteria

- Searchable-PDF claim validated in independent PDF readers.
- Legibility and file-size benchmarks across receipts, documents, books, and uneven lighting.
- No captured page retained after user cancellation unless explicitly saved as a draft.
- Accessibility for crop handles and page reordering.

## 0.5 — organization layer

### Planned

- Favourites and pinned roots.
- Tags with app-private metadata plus export/import.
- Saved searches and smart collections.
- Rules: extension, name, date, size, provider, tag, and metadata predicates.
- Batch rename templates and dry-run preview.
- Duplicate groups with hash confidence and cautious deletion workflow.
- Workspace/session restoration and tab history.
- Gallery view and richer metadata inspector.
- Background local index with explicit scope, pause, rebuild, and delete controls.

## 0.6 — local AI model packs

### Planned

- Model/runtime adapter API.
- Signed model-pack manifests, checksums, licenses, size/memory requirements, and removal controls.
- Initial narrowly-scoped tasks:
  - semantic search embeddings;
  - suggested tags;
  - filename suggestions;
  - cluster/smart-folder suggestions;
  - short local summaries for text/Markdown/PDF OCR content.
- Structured proposal schema with confidence and rationale.
- Review screen showing before/after location/name/tag changes.
- No autonomous destructive operations.

### Research gate

Benchmark candidate runtimes and models on representative low-, mid-, and high-end Android hardware. A model is not accepted because it runs on a flagship alone. Measure APK/runtime size, pack size, first-token/embedding latency, sustained thermals, battery, memory, and output quality.

## 0.7 — optional BYOK model connectors

### Planned

- Provider-neutral remote model adapter.
- Android Keystore-backed credentials.
- User-configurable endpoint/model with TLS-only default.
- Per-request data preview and local-only override.
- Redaction and maximum-content controls.
- Cost/token estimate where provider APIs allow it.
- No telemetry containing file content, prompts, keys, or model outputs.
- Import/export settings without secrets.

## 0.8 — provider and desktop-grade expansion

### Planned / research gate

- SMB, SFTP, WebDAV, and object-storage adapters evaluated as separate optional modules.
- Better cloud-provider offline/cache status.
- Dual-pane transfer mode as an opt-in power layout.
- Full keyboard shortcut map, focus model, multi-window, freeform-window, Chromebook, and desktop-mode polish.
- Media, font, office-document, and archive preview plug-ins.
- Checksummed release builds, F-Droid readiness, and Play distribution review.

## 1.0 criteria

Fylz 1.0 is not defined by checking every Finder/Files/Explorer box. It requires:

- reliable core operations across representative providers;
- operation progress, cancellation, collision handling, and recovery;
- no known high-severity data-loss or traversal/parser vulnerability;
- accessible phone/tablet/foldable/desktop-window journeys;
- documented backup/export path for Fylz metadata;
- stable theme and preview extension contracts;
- reproducible signed releases and dependency/license disclosure;
- clear privacy behavior for every optional model/provider feature;
- migration compatibility for workspace, tag, and operation data.

## Backlog categories for community issues

Use these labels when the repository is configured:

- `area:browser`
- `area:operations`
- `area:preview`
- `area:editor`
- `area:archive`
- `area:scan-pdf`
- `area:organize`
- `area:local-ai`
- `area:connectors`
- `area:theme`
- `platform:phone`
- `platform:tablet-foldable`
- `platform:desktop-chromebook`
- `security`
- `accessibility`
- `performance`
- `good first issue`
