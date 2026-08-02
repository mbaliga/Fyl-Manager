# Fylz

Fylz is an open-source, local-first Android file workspace: deliberately minimal at first glance, but designed to grow into a capable cross-provider file manager for phones, tablets, foldables, and desktop-style Android environments.

> Status: **0.1 foundation / pre-release**. The current branch is a usable architectural slice, not a claim of Finder/File Explorer parity.

## What is working in this foundation

- Storage Access Framework folder access with persisted permissions; no blanket storage or network permission.
- Multiple folder tabs, breadcrumbs, parent navigation, filtering, and refresh.
- Adaptive workspace:
  - collapsible or overlaid left navigation;
  - list, grid, and details views in the centre;
  - docked right-side preview on wide screens;
  - movable and resizable floating preview pane;
  - traditional and immersive interface modes.
- Compact, comfortable, and detailed information densities.
- Markdown preview, including headings, lists, task lists, quotes, rules, and fenced code.
- Preview support for common AI-agent artifacts such as Markdown, JSON, YAML, TOML, diffs, patches, prompts, instruction files, Mermaid text, logs, and source code.
- Lightweight UTF-8 text editor with in-place save through the selected document provider.
- Image preview, first-page PDF preview, and external open for other supported types.
- System/light/dark themes, four accent presets, and optional Android dynamic color.
- Provider-neutral AES-256 ZIP creation and guarded ZIP extraction engine. The selection/action UI is not wired yet.
- Unit tests for file-type classification and GitHub Actions build/lint/test/APK workflow.

## Explicitly not shipped yet

Scanning to PDF, OCR/searchable PDF output, copy/move/delete/rename queues, Trash/undo, full archive UI, thumbnail indexing, network providers, tags/smart collections, local-model organization, and BYOK LLM connectors are roadmap work. See [the roadmap](docs/ROADMAP.md).

## Principles

1. **Local by default.** File contents do not leave the device unless the user explicitly invokes a remote provider or model.
2. **Least privilege.** The public build starts with Android's Storage Access Framework. Broad file access, if ever offered, must be isolated and justified.
3. **Reversible operations.** AI and batch tools should propose plans; destructive execution requires review, supports undo where technically possible, and produces an operation log.
4. **Minimal surface, deep workspace.** Everyday browsing stays quiet. Tabs, panes, metadata, and power actions appear only when requested.
5. **Provider neutrality.** Local storage, removable media, cloud DocumentsProviders, and future network providers should use a common capability model rather than hard-coded assumptions.
6. **No private-source leakage.** The floating-pane interaction is an original Fylz implementation inspired by the product pattern used in Fonebrew; no private Fonebrew code or assets are copied here.

## Build

Requirements:

- JDK 17
- Android SDK 35
- Gradle 8.11.1, or use the same version provisioned by CI

```bash
gradle --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The debug APK is uploaded by GitHub Actions as the `fylz-debug-apk` artifact.

## Documentation

- [Product research and feature gaps](docs/PRODUCT_RESEARCH.md)
- [Architecture and security boundaries](docs/ARCHITECTURE.md)
- [Delivery roadmap](docs/ROADMAP.md)
- [Contributing](CONTRIBUTING.md)
- [Security policy](SECURITY.md)

## License

Apache License 2.0. See [LICENSE](LICENSE).
