# Fylz

Fylz is an open-source, local-first Android file workspace for phones, tablets, foldables and desktop-style Android environments.

> Status: **v1 alpha / pre-release**. The current branch is feature-rich enough for testing, but remains draft until real-device and provider acceptance is complete.

## Implemented

### Workspace

- Storage Access Framework folder access with persisted user grants.
- Multiple folder tabs, breadcrumbs, parent navigation, filtering and refresh.
- Adaptive list, grid and details views.
- Collapsible navigation and docked or floating preview panes.
- Phone, landscape and larger-window layouts.
- System, light and dark themes, accent presets and optional dynamic colour.

### Files and recovery

- Create files and folders.
- Rename, duplicate, copy and move.
- Recycle, restore and explicit permanent deletion.
- Durable operation journal with progress, cancellation and process-death recovery states.
- Conflict policies for skip, keep-both and guarded replacement.
- Dedicated recovery when a move copied successfully but the provider refused to remove the original.

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

### Additional foundations

- Guarded ZIP creation and extraction, including password-capable engine foundations.
- Scan-to-PDF foundation.
- Duplicate detection, batch rename and saved-library metadata foundations.
- Optional WebDAV, local-model and BYOK adapter foundations kept separate from deterministic file operations.

## Still required before stable v1

- Real-device/provider testing across Android local storage, SD/USB and cloud DocumentsProviders.
- Doze, reboot, revoked permission, low-storage, process-death and long-running backup acceptance.
- Final archive password UX, disk-space preflight and malformed/fuzz fixtures.
- Accessibility, keyboard/mouse, large-text, tablet and foldable visual QA.
- Signed release/upgrade testing, dependency and licence review, screenshots and release notes.

See [the roadmap](docs/ROADMAP.md) for acceptance work and longer-term features.

## Principles

1. **Local by default.** File contents do not leave the device unless the user explicitly invokes a remote provider or model.
2. **Least privilege.** Fylz starts with Android's Storage Access Framework rather than blanket file access.
3. **Reversible operations.** Destructive actions are explicit and recoverable where the provider permits it.
4. **Minimal surface, deep workspace.** Everyday browsing stays quiet while power tools remain available.
5. **Provider neutrality.** Local, removable, cloud and future network providers share capability-aware abstractions.
6. **No private-source leakage.** No private Fonebrew Studio code, assets, secrets or internal documentation belong in this repository.

## Build

Requirements:

- JDK 17
- Android SDK 36
- Gradle 8.11.1, or the version provisioned by CI

```bash
gradle --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

GitHub Actions uploads the debug APK as the `fylz-debug-apk` artifact after a successful build.

## Documentation

- [Product research and feature gaps](docs/PRODUCT_RESEARCH.md)
- [Architecture and security boundaries](docs/ARCHITECTURE.md)
- [Delivery roadmap](docs/ROADMAP.md)
- [Contributing](CONTRIBUTING.md)
- [Security policy](SECURITY.md)

## License

Apache License 2.0. See [LICENSE](LICENSE).
