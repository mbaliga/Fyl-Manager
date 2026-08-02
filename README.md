# Fylz

**Fylz** is an open-source, local-first file workspace for Android. The repository is named `Fyl-Manager`; **Fylz** is the current working product name.

The goal is not another cleaning utility. Fylz is intended to become the smallest credible Android equivalent of the best parts of Finder, Windows File Explorer, iOS Files and power-user Android file managers, with a calm interface that can switch between a conventional file browser and an immersive workspace.

> **Naming risk:** `fylz.in` currently operates a phone-to-PC file transfer service and `fylz.ai` operates an AI document-management platform. Treat **Fylz** as provisional until trademark, Play listing and app-identifier clearance is completed. See [`docs/research/naming-risk.md`](docs/research/naming-risk.md).

## What works in the foundation

- Privacy-preserving folder access through Android's Storage Access Framework
- Persisted access to internal storage, SD cards, USB storage and document providers selected by the user
- Multiple folder tabs with independent back/forward history
- Long-press folder selection for inspection without navigation
- Adaptive desktop-like layout: navigation on the left, files in the centre and preview on the right in wide layouts
- Draggable and resizable in-app floating preview inspired by the public interaction language of Fonebrew
- List and adaptive grid views
- Compact, comfortable and generous detail densities
- Search within the current folder, sorting and folders-first ordering
- Create folders and text/Markdown files; rename and delete where the provider supports those operations
- Rendered Markdown preview for `README.md`, `AGENTS.md`, `CLAUDE.md` and other agent-produced notes
- Structured text preview for JSON, JSONL, YAML, TOML, logs, diffs, patches and source files
- Bounded image decoding and first-page PDF preview
- Lightweight text/Markdown editor with explicit save and unsaved-change protection
- System, light, dark, OLED and monochrome themes
- Traditional and immersive shells
- No account, telemetry or `INTERNET` permission in the current app

## Honest status

This is a **buildable product foundation**, not a claim of complete Finder/File Explorer parity. The repository deliberately separates working behavior from planned behavior.

| Area | Status |
| --- | --- |
| Local/provider browsing, tabs, preview, editor, themes | Implemented in the foundation |
| Copy/move queues, multi-select, undo/trash and conflict resolution | Next core milestone |
| Multi-page document scan to PDF | Planned as an offline module |
| ZIP/7z/tar browsing, password-protected ZIP creation and extraction | Planned as an archive module |
| SMB/SFTP/WebDAV and provider adapters | Planned; no network permission yet |
| Local AI organization and user-supplied LLM providers | Architecture specified; no model or file content leaves the device in this build |
| Device-wide `MANAGE_EXTERNAL_STORAGE` flavor | Deferred until policy and user-value gates are met |

See [the roadmap](docs/ROADMAP.md), [architecture](docs/ARCHITECTURE.md), [security model](docs/SECURITY.md) and [research](docs/research/competitive-gap-analysis.md).

## Build

Requirements: JDK 17, Android SDK 36 and Gradle 8.11.1.

```bash
gradle --no-daemon testDebugUnitTest lintDebug assembleDebug
```

GitHub Actions installs the pinned Gradle 8.11.1 distribution, validates the manifest permission boundary, runs tests and lint, assembles the app, and uploads a debug APK artifact. A repository-local Gradle wrapper is a release-readiness task and must be generated from a trusted Gradle 8.11.1 installation before the first release tag.

## Design lineage

Fylz is visually independent but compatible with the public principles of [Hyle Design System](https://github.com/mbaliga/Hyle-Design-System): state should be legible through material behavior, dark surfaces avoid pure black by default, and provenance must never rely on colour alone. The floating pane is a clean implementation from the interaction requirement; no private Fonebrew Studio code is copied into this repository.

## License

Apache License 2.0. See [LICENSE](LICENSE).
