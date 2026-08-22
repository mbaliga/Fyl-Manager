# Fylz

Fylz is an open-source, local-first Android file workspace for phones, tablets, foldables and desktop-style Android environments.

> Status: **1.0.0-alpha01 / pre-release**. Code-level v1 scope is implemented and automated debug/release validation is required on every pull request. Stable release remains blocked on recorded real-device/provider, accessibility and signed-upgrade acceptance.

## Implemented

### Workspace

- Full filesystem access on launch: storage volumes, removable media and standard folders, no picker required.
- Storage Access Framework folder access with persisted user grants, for cloud, USB and third-party providers.
- Multiple folder tabs, breadcrumbs, parent navigation, filtering and refresh.
- Adaptive list, grid and details views.
- Collapsible navigation and docked or floating preview panes.
- Phone, landscape and larger-window layouts.
- System, light and dark themes, accent presets and optional dynamic colour.
- A spatial shell with four rooms parked off the browser's edges — locations left, tools and settings right, **details** up, **actions** down — rather than tab bars, chip rows and an overflow menu.

### Files and recovery

- Create files and folders.
- Rename, duplicate, copy and move.
- Recycle, restore and explicit permanent deletion.
- Durable operation journal with progress, cancellation and process-death recovery states.
- Conflict policies for skip, keep-both and guarded replacement.
- Dedicated cleanup-only recovery when a move copied successfully but the provider refused to remove the original; finishing the move never copies again.

### Preview and editing

- Text, source code, Markdown and common agent artifacts such as JSON, YAML, TOML, prompts, instructions, logs, diffs and patches.
- Images including SVG, GIF and animated WebP.
- PDF first-page preview, media metadata, archive browsing and unknown-file inspection.
- Lightweight UTF-8 text editing with pre-write history capture.

### File history

- Opt-in version history.
- Configurable versions per file, maximum file size and total storage quota.
- SHA-256 snapshots before supported writes and restore operations.
- Verified restore with automatic rollback when restored bytes do not match.
- Atomic metadata commits and process-wide locking.
- URI identity migration support for provider rename and move operations.

### Backups

- Multiple named manual and scheduled backup plans.
- Daily time windows and backup after a configurable count of newly observed images/videos.
- Charging, device-idle, battery, storage and network constraints.
- Transactional staging, per-file SHA-256 manifests and retention.
- Protection against nested destinations, traversal paths, duplicate/case-colliding paths and malformed manifests.
- Verified restore into a new folder with rollback of partial output.
- Durable per-plan execution leases preventing overlapping runs.
- Foreground WorkManager execution and progress notifications for long backups.
- Rediscovery/import of manifest-bearing backup folders after reinstall or app-data loss.

### Archives

- Standard and AES-256 password-protected ZIP creation.
- Archive inspection before extraction.
- Limits for archive input, entries, paths, depth, per-file size, total expansion and compression ratio.
- Temporary and destination storage preflight when the device/provider reports capacity.
- Per-entry bounded extraction with declared-versus-actual size verification.
- Transactional provider output and rollback after failure or cancellation.
- Hostile metadata and randomized traversal fixtures.

### Additional foundations

- Scan-to-PDF.
- Duplicate detection, batch rename and saved-library metadata.
- Optional remote-provider foundations — WebDAV, SFTP, SMB and S3-compatible — plus local-model and BYOK adapter foundations, kept separate from deterministic file operations.
- PDF page tools (inspect, per-page export with rotation, merge, split) and on-device OCR for searchable PDFs.

## Required before stable v1

These gates require actual devices, providers or maintainer-controlled signing material and cannot be truthfully completed by repository code alone:

- Execute the [device and provider acceptance matrix](docs/DEVICE_ACCEPTANCE.md) across local storage, SD/USB and cloud DocumentsProviders.
- Record Doze, reboot, revoked-permission, low-storage, process-death and long-running backup results.
- Complete TalkBack, keyboard/mouse, large-text, tablet and foldable acceptance.
- Produce and verify a maintainer-signed release candidate and in-place upgrade using the [release procedure](docs/RELEASE.md).
- Capture final screenshots and release evidence from accepted devices.

See [the roadmap](docs/ROADMAP.md) for longer-term work beyond stable v1.

## Principles

1. **Local by default.** File contents do not leave the device unless the user explicitly invokes a remote provider or model.
2. **Full access, honestly asked for.** Fylz is a file manager and requests `MANAGE_EXTERNAL_STORAGE` up front, with a plain rationale and a working degraded mode if you decline. It does not pretend a folder picker is a filesystem. The Storage Access Framework path is kept as the route to cloud, USB and third-party providers. (This reverses an earlier least-privilege-first stance; see docs/ARCHITECTURE.md.)
3. **Reversible operations.** Destructive actions are explicit and recoverable where the provider permits it.
4. **Minimal surface, deep workspace.** Everyday browsing stays quiet while power tools remain available.
5. **Provider neutrality.** Local, removable, cloud and future network providers share capability-aware abstractions.
6. **No private-source leakage.** No private Fonebrew Studio code, assets, secrets or internal documentation belong in this repository.

## Build

Requirements:

- JDK 17
- Android SDK 36
- Gradle 8.14.3 (the version the wrapper and CI both pin)

```bash
gradle --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
gradle --no-daemon :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

GitHub Actions uploads the debug APK after the Android CI workflow and an unsigned release candidate plus dependency graph after Release readiness. Unsigned artifacts are validation outputs, not production releases.

## Documentation

- [Changelog](CHANGELOG.md)
- [Product research and feature gaps](docs/PRODUCT_RESEARCH.md)
- [Architecture and security boundaries](docs/ARCHITECTURE.md)
- [Delivery roadmap](docs/ROADMAP.md)
- [Device and provider acceptance](docs/DEVICE_ACCEPTANCE.md)
- [Release and signing procedure](docs/RELEASE.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)
- [Contributing](CONTRIBUTING.md)
- [Security policy](SECURITY.md)

## License

Apache License 2.0. See [LICENSE](LICENSE).
