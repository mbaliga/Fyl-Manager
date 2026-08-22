# Changelog

All notable user-visible and security-relevant changes to Fylz are recorded here.

## Unreleased — v1 alpha

### Data safety

- Removed `library/LocalFileIndex`, an unreferenced second on-device index that wrote to the same `files.json` path as the real one with a different schema — a latent data hazard for whichever store read second. The `index` package is now the only rule engine and on-device index. Two further dead smart-collection engines and their tests were removed with it.

### Workspace

- Added SAF-scoped multi-root browsing with tabs, breadcrumbs, search, list/grid/details views and adaptive preview layouts.
- Added file/folder creation, rename, duplicate, copy, move, recycle, restore and explicit permanent deletion.
- Added durable operation journaling, progress, cancellation, process-death recovery and conservative retry rules.
- Added cleanup-only recovery when a move committed its destination but could not remove the original.
- Finish move now compares version evidence captured when the source delete first failed: a provably modified source or destination blocks the delete and is reported (`MOVE_SOURCE_MODIFIED`, `MOVE_DESTINATION_UNVERIFIED`); providers that report no evidence keep the previous direct size cross-check.
- Selection actions now consult the storage provider's declared capabilities (copy, move, recycle, rename, batch rename): an action a backend cannot perform is withheld instead of failing after the tap. Both shipping backends declare full support, so nothing visible changes until a more limited provider arrives.

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
- Added a **details** room on the previously reserved top edge: a tree of where the selected file or folder lives, plus kind, size, modified time, MIME type, path and tags. Sizes and timestamps a provider declines to report are stated as not reported rather than shown as `0 B` or 1970.
- Turned the bottom room into **actions**: the selection's actions, the folder's own (new folder, new text file, scan to PDF, find duplicates, AI organize proposal), and storage & recovery as its last section.
- Replaced the eleven-button horizontally scrolling selection bar with a one-line summary that opens the actions room, and retired the file browser's overflow menu.
- Actions now appear only when they apply, decided by a testable `SelectionActionPolicy`: rename needs one entry, batch rename two or more, extract exactly one archive, PDF tools an all-PDF selection.
- Stopped offering Share for selections containing a folder; `ACTION_SEND` cannot deliver a directory, so the share sheet appeared and nothing arrived.
- Corrected entry-kind wording throughout the browser — "Folder" and "PDF" rather than "Directory" and "Pdf".
- Rooms now arrive scaled from 0.97 and reach full size as the drag completes, instead of sliding in at full size under the lifting file browser.
- Made the top bar's folder title a 48dp target that opens the details room, for anyone who would rather tap than drag.
- Added the cluster drag: press-hold a selected row to gather the selection under the finger, with organic corner bulges as drop targets — clipboard/move/new-folder/compress top-left, trash bottom-right. The trash tilts, lifts and opens its lid as files approach; dropping pours them in with a genie squeeze; the clipboard snaps them visibly aboard.
- Added persistent tray bulges while staged content exists: tap to browse in an endlessly looped strip, pull a card down (or use its accessibility action) to unstage, "Paste here"/"Move here" commits into the folder on screen via the journaled operations — nested folders resolved by walking display names, never by parsing provider IDs.
- Added the session trash bulge with Put back and **Shred**. Shredding permanently deletes through the existing recycle-bin gate with honest copy: no secure-erase claims, ever — flash storage makes them a lie.
- Subfolder-destination operations refuse journal replay rather than risk replaying into the tree root (`OperationRetryPolicy.isReplayableDestination`).

### Release engineering

- Added debug CI and release-readiness workflows.
- Made Android CI manually triggerable (`workflow_dispatch`).
- Lockstepped Kotlin with the constellation (2.1.20 → 2.1.0, matching both submodules), with a guard test that fails the build on AGP/Kotlin/Compose-plugin drift across the composite.
- Added a theme-ownership guard test: `MaterialTheme(...)` outside `FylzTheme` fails the build — the bare-MaterialTheme bug shipped three times and is now structurally impossible.
- Added crash-injection tests simulating process death at every journaled operation transition; recovery now marks QUEUED items of an interrupted operation as interrupted instead of leaving them looking "still waiting" forever.
- Added a behavioral contract test suite that both storage backends must pass, with capability-gated skips — the gate every future provider must clear.
- Added release lint, R8 release assembly and dependency graph artifacts.
- Added third-party notices, signing/upgrade procedure and real-device/provider acceptance matrix.

### Internal architecture (Phase 1)

- Extracted four pure-JVM Gradle modules — `core-model`, `core-vfs`, `core-operations`, `core-format` — carrying opaque item identity, the capability vocabulary, the operation-journal model and policies, and the format registry, so their tests run off-device.
- Replaced ad hoc `Uri`-based identity in the operation journal with `ItemRef`, an opaque provider/location/item triple; added the one adapter (`ItemRefs.kt`) allowed to know what a SAF `Uri`'s segments mean.
- Fixed a latent retry bug where two differently-spelled URIs naming the same destination tree root compared unequal and wrongly blocked a valid batch retry.
- Versioned the operation journal's on-disk format (`schemaVersion`), with shape-based decoding so old, unversioned records keep loading after the upgrade.
- Expanded the eight-value storage capability enum into `core-model`'s grouped `ItemCapability` vocabulary; both storage backends now declare their capabilities against it. Not yet consulted by any command — that wiring is Phase 2.
- Wired previously-dormant identity-preserving migration into rename: file history and library favorites/tags now follow a renamed item instead of orphaning under its old URI.

### Known pre-release requirements

- Execute and record the real-device/provider matrix.
- Complete TalkBack, large-text, keyboard/mouse, tablet and foldable acceptance.
- Produce and verify a maintainer-signed release candidate using an external signing key.
- Capture final screenshots and release evidence from accepted devices.
