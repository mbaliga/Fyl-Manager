# Fylz delivery roadmap

Fylz is currently at **1.0.0-alpha01**. The code-level v1 scope is implemented; the remaining stable-v1 gates are recorded device/provider, accessibility and signed-upgrade acceptance rather than another feature-count milestone.

Reliability, data safety and provider behavior remain more important than parity with every desktop file manager.

## Status legend

- **Implemented** — present in the current v1 alpha and covered by automated validation where feasible.
- **Acceptance gate** — implemented but requires real Android devices, providers, accessibility tooling or maintainer-controlled signing material.
- **Post-v1** — intentionally outside the stable-v1 release gate.
- **Research gate** — requires performance, licensing, security or platform validation before commitment.

## V1 alpha — implemented

### Workspace

- SAF folder selection and persisted user grants.
- Multiple roots and tabs, breadcrumbs, parent navigation, refresh and in-folder filtering.
- List, grid and details views.
- Adaptive phone, landscape, tablet/foldable and desktop-style window layouts.
- Docked and floating previews.
- Files and Recovery root destinations.
- Light, dark, system and optional dynamic-colour themes.

### File operations and recovery

- Multi-selection.
- Create files and folders.
- Rename, duplicate, copy and move.
- Recycle, restore and explicit permanent deletion.
- Keep-both, skip and guarded replacement conflict policies.
- Durable operation journal with progress, cancellation and process-death recovery states.
- Conservative retries that exclude already completed items.
- Cleanup-only **Finish move** recovery when destination copy succeeded but source deletion failed.
- Share and external-open journeys.

### Preview and editing

- Bounded text/source preview and UTF-8 editing.
- Markdown, JSON, YAML, TOML, prompts, instructions, logs, diffs and patches.
- Images including SVG, GIF and animated WebP; freehand annotation; JPEG/PNG/WebP conversion; lasso, magic-wand and magnetic-lasso selection with crop, cut-out and copy.
- Paged PDF preview; merge, split, page export, images-to-PDF, pages-to-images, on-device OCR into a searchable PDF; freehand ink burned into a page's content stream.
- Audio/video playback with subtitle and audio-track selection, speed and an equalizer; media metadata.
- Office (Word, Excel, PowerPoint) and font previews; 3D/CAD wireframes (OBJ, STL, PLY, OFF, glTF/GLB, DXF) with DXF annotation written as real drawing entities.
- Archive browsing for ZIP, 7z and the tar family with per-entry extraction; unknown-file inspection.
- Pre-write file-history capture for supported writes.

### File history

- Opt-in local version history.
- Configurable versions per file, maximum source-file size and total storage quota.
- SHA-256 snapshots and duplicate suppression.
- Verified restore after preserving the current version.
- Automatic rollback when restored bytes fail verification.
- Process-wide locking and atomic metadata commits.
- Backup-manifest recovery and URI identity migration support.

### Backups

- Multiple named backup plans.
- Manual execution and scheduled daily windows.
- Trigger after a configurable count of newly observed images/videos.
- Charging, device-idle, battery, storage and network constraints.
- Transactional staging and per-file SHA-256 manifests.
- Path/topology validation and nested-destination rejection.
- Snapshot retention and verified restore into a new folder.
- Per-plan execution leases preventing overlapping manual/daily/media runs.
- Foreground WorkManager execution for long-running backups.
- Rediscovery/import of external backups after reinstall or app-data loss.

### Archives

- Standard ZIP creation.
- AES-256 password-protected ZIP creation with confirmation and short-lived password handling.
- Archive inspection before extraction.
- Input-size, entry-count, path, depth, per-file, total expansion and compression-ratio limits.
- Temporary and provider-space preflight where capacity is reported.
- Per-entry bounded streaming and declared-versus-actual size verification.
- Transactional provider output with rollback after failure or cancellation.
- Hostile archive metadata and randomized traversal fixtures.

### Search, index and organization

- Opt-in local index with explicit folder scope, pause, rebuild and delete controls; smart collections with rule predicates, listable and applicable from the index screen.
- Indexed text sampling of PDF and Office files (bounded per file), so search and "Contains text" collections match document contents.
- Favourites, tags and saved searches, with metadata export/import.
- Duplicate detection (report), batch rename, scan-to-PDF.

### Remote providers and connectors

- SFTP (pinned host keys), SMB, WebDAV (HTTPS only) and S3-compatible object storage (HTTPS only); secrets only in the Keystore-encrypted vault.
- BYOK AI organization proposals behind an explicit transmission approval; no autonomous destructive operations.

### Desktop, widgets and system integration

- Desktop (widgets, recents, favourites), canvas and stacks views; three home-screen widgets; launcher shortcuts; share-to-Fylz inbox; live wallpaper; crash recovery surfaced on next launch.

### Release engineering

- Debug CI: unit tests, debug lint and debug APK.
- Release readiness: unit tests, release lint, R8/resource-shrunk release assembly and dependency graph.
- Direct dependency/licence inventory.
- Changelog, release/signing procedure and device/provider acceptance matrix.
- Exclusion of file-history and backup metadata from Android cloud backup/device transfer.

## Stable v1 acceptance gates

Stable v1 is blocked until the following evidence is completed and linked from the release record.

### Device and provider integrity

Execute [`DEVICE_ACCEPTANCE.md`](DEVICE_ACCEPTANCE.md) across:

- representative Android versions from API 31 (the minSdk) through API 36 (the target);
- local storage;
- SD and USB storage where supported;
- at least one cloud DocumentsProvider;
- providers that refuse rename/delete or report unknown sizes/capacity.

Acceptance includes process death, revoked grants, offline/disconnected providers, low storage, interrupted transfers and recovery journeys.

### Background execution

Record behavior for:

- Doze and battery optimization;
- reboot and app upgrade;
- timezone changes;
- charging/idle/network transitions;
- long-running backup foreground notifications;
- stale lease recovery and overlapping trigger rejection.

### Accessibility and adaptive UI

Complete:

- TalkBack traversal;
- 200% font scale;
- keyboard-only operation;
- mouse/pointer interaction;
- portrait, landscape, split-screen, tablet and foldable layouts;
- visible focus and non-colour-only status communication.

### Signed release and migration

Follow [`RELEASE.md`](RELEASE.md):

- review release lint and dependency artifacts;
- sign with a maintainer-controlled Fylz key kept outside the repository;
- verify certificate and artifact SHA-256;
- clean-install and upgrade-test from the previous signed candidate;
- confirm persisted metadata, schedules, grants and private credential records migrate safely.

A failure involving data loss, unsafe overwrite/delete, provider-boundary bypass, credential exposure, parser traversal, incomplete rollback or unrecoverable migration blocks release.

## Known limitations and deferred work

Implemented behaviour that is deliberately narrower than it could be, or scaffolding that is built but not wired. Each is a real decision still open, not an oversight.

### Annotation and editing

- Annotate overlays fit the whole image/page/drawing to one fixed canvas — no zoom or pan while drawing. Deliberate, so a screen point maps to exactly one document point; precision markup on large images is the trade-off.
- Multi-layer, shape and text annotation tools are a later step; today's tools are freehand pen only.
- The text editor assumes UTF-8; encoding detection for legacy files is not implemented.

### Archives and formats

- Whole-archive extraction is ZIP only; 7z and the tar family extract per entry.
- RAR (read-only) and DWG remain out: RAR needs a licence-compatible decoder, and the only capable DWG library (LibreDWG) is GPL, which this Apache-licensed app does not link.
- EPUB has no dedicated preview.

### Architecture

- `core-vfs.CapabilityPolicy` and `core-model.ItemCapability` are declared by both storage backends but no command consults them yet — visibility is still decided by `SelectionActionPolicy` per entry kind.
- Recycle-bin records, batch-rename plans and duplicate groups still identify items by `Uri`; the `ItemRef` migration covers the operation journal only.
- `ItemIdentity.compareVersions` is built and tested but unwired: whether an unreadable size should block or allow **Finish move** is a product decision.
- The operation journal has no last-known-good backup; a corrupt payload is decoded per record so one bad entry no longer drops the rest.
- Undo over the operation journal, folder rows as drop targets with spring-loading, and pick-up-more mid-drag are the ranked desktop-parity gaps (docs/product/desktop-parity-study.md).
- `KeyboardShortcutPolicy` and the dual-pane models exist without a key handler or a second pane; a full shortcut map is Chromebook/desktop polish.

### Optional local models

- Signed model-pack manifests, a signed catalogue and a local model manager are implemented as libraries with tests but have no UI and load no runtime. Shipping them needs the research gate below.

## Post-v1 backlog

- Gallery view and deeper metadata inspection.
- Expanded batch-rename templates and dry-run review; duplicate cleanup with cautious deletion (detection is report-only today).
- Better cloud offline/cache status; remote-to-local transfers through a destination picker.
- Optional dual-pane transfer mode; full keyboard shortcut map and Chromebook/desktop-mode polish.
- Semantic-search embeddings, suggested tags/names/clusters and bounded summaries over the local index.
- Connector catalogue, per-request local-only override and settings export without secrets for the BYOK connectors.
- Translations: every user-facing string is English-only today; resource extraction is in progress.
- F-Droid readiness (fastlane metadata, reproducible build) and the Play `MANAGE_EXTERNAL_STORAGE` declaration and review.

## Research gates

- RAR read-only and any further archive format require ABI-size, CVE-response and licence review.
- Local model runtimes require benchmarks on low-, mid- and high-end devices covering memory, thermals, battery, package size, latency and quality.
- Broad file access was adopted deliberately for the file-manager use case; any widening beyond it requires an isolated architecture and explicit platform-policy justification.

## Community issue labels

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
