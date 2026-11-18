# Changelog

All notable user-visible and security-relevant changes to Fylz are recorded here.

## Unreleased — v1 alpha

### Workspace

- Added SAF-scoped multi-root browsing with tabs, breadcrumbs, search, list/grid/details views and adaptive preview layouts.
- Added file/folder creation, rename, duplicate, copy, move, recycle, restore and explicit permanent deletion.
- Added durable operation journaling, progress, cancellation, process-death recovery and conservative retry rules.
- Added cleanup-only recovery when a move committed its destination but could not remove the original.
- Added an in-app clipboard: Cut and Copy from the selection bar, then Paste into the folder you are in, in any tab. Pasting never overwrites (keep-both naming), so copying into the same folder duplicates the file; a cut into its own folder is a no-op; a folder cannot be pasted into itself or anything inside it. Cut items are drawn faint until they land. The picker-based transfers remain as "Copy to…" and "Move to…".

- Reworked navigation around the places list. The left list now holds Home, the standard folders, Large files, **Recycle bin, Storage & recovery and Settings**; the bottom and right rooms are gone. The content opens as a large rounded card on the right half beside the list, and **moving the wheel onto a row changes the pane to match** (Pictures shows Pictures, Settings shows Settings), with the neighbouring cards peeking above and below and sliding with the wheel. Requires `shared-libraries` at or after `6095357` (the shell's open-park mode).
- The top room now shows **what the folder is** (name, path, contents, size, newest change, largest file, types, free space on its volume) above the tree.
- The landing screen follows the reference design: a Storage card with a coloured breakdown (Photos, Videos, Documents, Audio, APKs), a Downloads card, folder tiles for Documents, Images (with the newest photo peeking out), Videos and APKs that open a search over the whole volume, and a Deleted Files card with a confirmed Shred all. Add folder, Network, cloud roots and Recent sit below.
- Inside a folder: selection controls are hidden until you choose Select (menu) or long-press; selecting swaps the title bar for Select All / count / Done, shows a ring on each row (a tick and fill when selected) and highlights selected grid names. A tap opens the file, a long press is the quick preview with an "Add to selection" action; while selecting a tap toggles and a long press peeks. Settings has "Tap to preview" to swap those roles.
- The always-on alphabet strip is gone. A slim scrollbar sits inside the list area only (so the menu is always tappable); dragging it unfolds the letter / month / size strip with the current one highlighted.
- One "…" menu now holds Select, New folder, Paste, view (Icons / List), sort and the extras. View and sort are remembered per folder (Settings or the menu can switch that to one global view).

### Fixes

- Fixed exported PDFs being named with literal template text (`Fylz-pages-${System.currentTimeMillis()}.pdf`) and PDF progress reading "Writing page $done of $total"; names now carry the timestamp and progress shows real numbers.

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

### Release engineering

- Added debug CI and release-readiness workflows.
- Added release lint, R8 release assembly and dependency graph artifacts.
- Added third-party notices, signing/upgrade procedure and real-device/provider acceptance matrix.

### Known pre-release requirements

- Execute and record the real-device/provider matrix.
- Complete TalkBack, large-text, keyboard/mouse, tablet and foldable acceptance.
- Produce and verify a maintainer-signed release candidate using an external signing key.
- Capture final screenshots and release evidence from accepted devices.
