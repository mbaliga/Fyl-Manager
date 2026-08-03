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
- Images including SVG, GIF and animated WebP.
- First-page PDF preview, media metadata, archive browsing and unknown-file inspection.
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

### Additional v1 utilities

- Scan-to-PDF through the platform scanner integration and SAF export.
- Duplicate detection.
- Batch rename planning/execution foundations.
- Favourites, tags and saved-library metadata.
- Optional WebDAV, local-model and BYOK adapter foundations isolated from deterministic file operations.

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

- representative Android versions from API 26 through the release target;
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

## Post-v1 backlog

### Document and media utilities

- PDF page thumbnails and navigation.
- PDF merge, split, rotate, reorder and metadata editing after choosing a safe licence-compatible stack.
- Optional OCR/searchable PDF with explicit local/remote disclosure.
- Richer media/font/office-document preview plug-ins.
- Gallery view and deeper metadata inspection.

### Organization and indexing

- Export/import for tags and workspace metadata.
- Smart collections and rule predicates.
- Expanded batch-rename templates and dry-run review.
- Duplicate cleanup workflow with cautious deletion.
- Background local index with explicit scope, pause, rebuild and delete controls.

### Optional local models

- Signed model-pack manifests, checksums, licences and removal controls.
- Semantic-search embeddings, suggested tags/names/clusters and bounded summaries.
- Structured proposal review with confidence/rationale.
- No autonomous destructive operations.

### Optional remote-model connectors

- Provider-neutral connector catalogue.
- Per-request transmission preview and local-only override.
- Redaction and maximum-content controls.
- Cost/token estimate where APIs allow it.
- Settings export without secrets.

### Provider and desktop expansion

- Dedicated SMB, SFTP, WebDAV and object-storage modules after security/maintenance review.
- Better cloud offline/cache status.
- Optional dual-pane transfer mode.
- Full keyboard shortcut map and Chromebook/desktop-mode polish.
- F-Droid readiness and Play distribution review.

## Research gates

- Additional archive formats such as 7z, tar/gzip and RAR read-only require ABI-size, CVE-response and licence review.
- Local model runtimes require benchmarks on low-, mid- and high-end devices covering memory, thermals, battery, package size, latency and quality.
- Broad file access is not added merely for convenience; any future request requires an isolated architecture and explicit platform-policy justification.

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
