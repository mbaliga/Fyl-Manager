# Changelog

All notable user-visible and security-relevant changes to Fylz are recorded here.

## Unreleased — v1 alpha

### Workspace

- Added SAF-scoped multi-root browsing with tabs, breadcrumbs, search, list/grid/details views and adaptive preview layouts.
- Added file/folder creation, rename, duplicate, copy, move, recycle, restore and explicit permanent deletion.
- Added durable, SQLite-backed operation journaling, progress, cancellation, process-death recovery and conservative retry rules; a failed batch item no longer aborts the items queued after it, and failed/partial operations can be retried.
- Added cleanup-only recovery when a move committed its destination but could not remove the original.
- Added preflight checks (free space, filesystem name limits, case-insensitive collisions) and per-item auto-rename/skip before a copy or move starts.
- Added a real per-item conflict-resolution picker (replace, replace-if-newer, keep both, skip) for copy and move, replacing the previous always-keep-both behavior.
- Added optional SHA-256 verification of transferred content, automatic for removable and network destinations.
- Added a faster local-to-local transfer path (direct rename/kernel-level copy) alongside the existing provider-neutral streaming fallback.
- Added cut/copy/paste with an in-app destination chooser (open tabs and storage roots).
- Added tabs/selection/sort/view/preview/search state persistence across rotation, foldable hinge changes and process death.
- Added streamed listing for large folders (results appear before a 100,000-entry folder finishes reading), external-change notifications, and moved remaining main-thread file-history/backup/operation-log I/O off the UI thread.
- Added a local SQLite-backed index (name/path/content search, background rebuilds tied to MediaStore's change generation) that the main search uses when a folder is covered, falling back to the live walk otherwise; replaced the previous separate JSON-file-backed index/organize implementations with this one.
- Hardened the app's own broad-storage `DocumentsProvider` so only Fylz itself, not another app, can obtain a persisted tree grant over one of its roots through the system picker.

### Preview and editing

- Added bounded text and source previews, Markdown and common agent-artifact support.
- Added SVG, animated GIF/WebP, image, first-page PDF, media metadata, archive and unknown-file preview routes.
- Added lightweight text editing with pre-write version capture.

### File history

- Added opt-in local version history with per-file version count, maximum file size and total storage quota.
- Added SHA-256 snapshots, verified restore and rollback.
- Added process-wide locking, atomic metadata commits, backup-manifest recovery and URI identity migration support.

### Backups

- Added manual and scheduled backup plans.
- Added daily windows, new-media thresholds and charging/idle/battery/storage/network constraints.
- Added transactional staging, per-file hashes, manifest validation, retention and restore into a new folder.
- Added per-plan execution leases and foreground WorkManager execution.
- Added backup rediscovery/import after reinstall or app-data loss.

### Archives

- Added standard and AES-256 password-protected ZIP creation UX.
- Added archive inspection before extraction.
- Added input, entry, path-depth, per-file, total-expanded-size and compression-ratio limits.
- Added temporary and provider storage preflight when capacity is available.
- Replaced unbounded bulk extraction with per-entry bounded streaming and actual-size verification.
- Added rollback of provider output after extraction failure or cancellation.
- Added hostile metadata and randomized traversal fixtures.

### PDF tools

- Added PDF page inspection, page-range extraction with per-page rotation, and merge, rendered
  DPI-aware rather than at a fixed size.
- Added an optional on-device OCR pass that embeds real searchable/selectable text into the
  rasterized output.

### Navigation and accessibility foundations

- Consolidated operations, file history, backups, backup import and archive tools into a primary Recovery destination.
- Removed independent global floating recovery controls.
- Added explicit labels and content descriptions for recovery actions.

### Release engineering

- Added debug CI and release-readiness workflows.
- Added release lint, R8 release assembly and dependency graph artifacts.
- Added third-party notices, signing/upgrade procedure and real-device/provider acceptance matrix.

### Known pre-release requirements

- Execute and record the real-device/provider matrix.
- Complete TalkBack, large-text, keyboard/mouse, tablet and foldable acceptance.
- Produce and verify a maintainer-signed release candidate using an external signing key.
- Capture final screenshots and release evidence from accepted devices.
