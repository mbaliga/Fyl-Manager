# Changelog

All notable user-visible and security-relevant changes to Fylz are recorded here.

## Unreleased — v1 alpha

### Workspace

- Added SAF-scoped multi-root browsing with tabs, breadcrumbs, search, list/grid/details views and adaptive preview layouts.
- Added file/folder creation, rename, duplicate, copy, move, recycle, restore and explicit permanent deletion.
- Added durable operation journaling, progress, cancellation, process-death recovery and conservative retry rules.
- Added cleanup-only recovery when a move committed its destination but could not remove the original.

### Network locations

- Added downloading of files from saved WebDAV, SFTP, SMB and S3 locations to a user-chosen save location, with provider-verified atomic transfer, cleanup of failed output and no plaintext credential handling.

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

### Navigation and accessibility foundations

- Consolidated operations, file history, backups, backup import and archive tools into a primary Recovery destination.
- Removed independent global floating recovery controls.
- Added explicit labels and content descriptions for recovery actions.

### Home-screen widgets

- Added six resizable home-screen widgets for any launcher: Space, Backups, Recovery, In progress, Quick folders and Index. Each has four size layouts, from 2x1 to 4x3 and taller.
- The widgets show counts, sizes, times and standard folder names only: no file names, paths, previews, backup plan names or volume labels. Quick folders shows no item counts, and without All files access it says so at every size instead of drawing pads.
- Fylz pushes widget updates when the journal, recycle manifest, backup state or index changes, and once at midnight, a backup window or a backup lease's end; nothing polls. If Fylz's process dies mid-copy, a watchdog redraw 15 minutes after the last journal write lets the journal's recovery mark the copy interrupted. The widgets cache nothing, need no new permission and add no dependency.
- Compiled and unit-tested only. Device rendering, taps and refresh timing are not yet verified; see [docs/widgets.md](docs/widgets.md).

### Release engineering

- Added debug CI and release-readiness workflows.
- Added release lint, R8 release assembly and dependency graph artifacts.
- Added third-party notices, signing/upgrade procedure and real-device/provider acceptance matrix.

### Known pre-release requirements

- Execute and record the real-device/provider matrix.
- Complete TalkBack, large-text, keyboard/mouse, tablet and foldable acceptance.
- Produce and verify a maintainer-signed release candidate using an external signing key.
- Capture final screenshots and release evidence from accepted devices.
