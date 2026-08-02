# Fylz product research

_Last checked: 2 August 2026_

## Executive finding

A credible Android file manager should no longer position scanning, ordinary ZIP creation, PDF viewing, basic sharing, or storage cleanup as unique. Files by Google already documents scanning documents into searchable PDFs, creating and extracting ordinary ZIP archives, PDF viewing/editing/annotation, Trash retention, Safe Folder, search, sharing, and storage-cleanup workflows.

Fylz's strongest opportunity is therefore **a local-first file workspace** rather than another cleaner: desktop-grade navigation and previews, first-class text/Markdown/agent artifacts, reversible power operations, provider neutrality, strong theming, encrypted archives, and optional on-device semantic organization.

Absence claims below are deliberately conservative. “Gap” means a capability is not presented as a first-class workflow in the public product documentation reviewed; it does not prove that no device/version/experiment exposes it.

## Essential capabilities

### 1. Trustworthy file operations

The non-negotiable core is browse, select, create folder/file, rename, duplicate, copy, move, share, open-with, delete, restore, inspect properties, sort, filter, and search. Operations must:

- work across local and removable storage plus compatible Android document providers;
- expose progress and failure at item level;
- handle collisions explicitly;
- survive activity/process recreation where possible;
- support cancellation and retry;
- be reversible through Trash/undo when the provider and operation allow it;
- never silently replace, delete, or reorganize user content.

### 2. Workspace navigation

- Multiple folder tabs.
- Breadcrumbs and navigation history.
- Fixed or collapsible left navigation on wide screens; temporary overlay on small screens.
- List, grid, details/table, and gallery-oriented views.
- Compact, comfortable, and detailed densities.
- Docked preview/inspector in landscape, tablet, foldable, and desktop-sized windows.
- Movable/resizable floating preview for immersive work.
- Keyboard, mouse, drag-and-drop, multi-select, and Android back behavior.
- Saved roots, favourites, recent locations, tags, and smart collections.

### 3. Preview and lightweight creation

- Markdown, plain text, source code, JSON, JSONL, YAML, TOML, XML, CSV/TSV, logs, diffs/patches, prompts, instruction files, Mermaid text, and common agent handoff artifacts.
- Images, audio/video metadata, PDFs, archives, fonts, and common office formats where an open renderer is practical.
- Safe large-file limits, streaming, encoding detection, and hex fallback rather than unbounded reads.
- Lightweight text editing, new-file creation, autosave/recovery, explicit encoding and line-ending controls later.
- Quick metadata inspector: path/provider, type, size, dates, checksum, permissions/capabilities, dimensions/duration, EXIF, and archive contents.

### 4. Document and archive tools

- Camera scan with edge detection, perspective correction, rotation, filters, page reordering, and export.
- OCR as an optional stage; searchable PDF requires a real text layer, not merely OCR text stored beside page images.
- PDF merge, split, reorder, rotate, annotate, redact safely, and compress in later phases.
- ZIP create/extract as baseline.
- Password-protected archive creation and extraction, with explicit algorithm choice and secure password handling.
- Zip-slip/path-traversal, symlink, decompression-bomb, and overwrite protections.

### 5. Organization without surrendering control

- Conventional organization first: tags, saved searches, rules, duplicate detection, batch rename, templates, and metadata filters.
- Optional downloadable local model packs for semantic naming, clustering, summaries, OCR post-processing, and suggested filing.
- Optional BYOK remote model adapters, disabled until configured.
- Every AI action produces a visible plan/diff before applying file operations.
- Destructive or broad moves require confirmation; recommendations must be explainable and reversible.
- Local embeddings/indexes are encrypted or held in app-private storage and can be deleted independently.

## What Files by Google already covers

The official Files by Google help material reviewed documents these areas:

- browsing, search, sharing, favourites, storage analysis, duplicate/junk/large-file cleanup;
- Trash with a retention window;
- Safe Folder protected by a PIN or pattern;
- scanning documents and saving searchable PDFs;
- PDF viewing and editing/annotation tools;
- ordinary ZIP creation and extraction;
- nearby/Quick Share integration.

Fylz should not spend its positioning budget pretending these are novel. It should make them composable inside a better workspace.

## High-value gaps relative to the desired product

| Opportunity | Why it matters | Fylz direction |
|---|---|---|
| Folder tabs and persistent workspaces | Mobile users increasingly work across projects, external drives, and desktop-sized Android windows. | Tabs are in the foundation; add session restore and per-tab history. |
| Adaptive three-pane layout | A list without a persistent preview wastes large/foldable screens. | Left nav + centre browser + right inspector on wide windows. |
| Floating preview | Keeps context visible while browsing or editing elsewhere. | Original draggable/resizable Fylz pane; never copy private Fonebrew source. |
| Markdown and agent-artifact literacy | AI tools commonly produce plans, patches, logs, JSON/YAML, prompts, and handoffs that generic viewers treat poorly. | Preview and edit these as first-class local documents. |
| Encrypted archives | Ordinary ZIP support is insufficient for many transfer/archive workflows. | AES-256 ZIP in the engine; wire reviewed UI and password hygiene. |
| Reversible batch operations | Power operations are dangerous when progress, collision, and undo are weak. | Durable operation queue, transaction journal, Trash adapters, undo where possible. |
| Tags, rules, and smart collections | Folder hierarchy alone does not scale. | Local metadata layer with exportable sidecar format and provider-safe fallbacks. |
| Local semantic organization | Remote AI creates privacy, cost, and connectivity concerns. | Downloadable model adapters, bounded indexing, explicit plan review. |
| BYOK model choice | Users should not be trapped in one hosted AI service. | Provider-neutral adapter SDK and Android Keystore-backed credentials. |
| Deep theming and density | Accessibility and personal workflows require more than light/dark. | Theme tokens, accents, dynamic color, density, immersive/traditional shells. |
| Provider capability awareness | Android document providers differ in rename/move/delete/write support. | Capability matrix per document/provider; disable unsupported actions honestly. |

## Reference feature matrix

This matrix guides parity; it does not imply every item belongs in version 1.

| Capability family | macOS Finder | iOS/iPadOS Files | Windows File Explorer | Files by Google | Fylz target |
|---|---:|---:|---:|---:|---:|
| Tabs | Yes | Limited/window-dependent | Yes | Not a documented primary workflow | Yes |
| Preview/Quick Look | Strong | Yes | Preview/details panes | File-type viewers | Docked + floating inspector |
| Multiple views/densities | Strong | Grid/list | Strong | Grid/list | List/grid/details/gallery + densities |
| Tags | Yes | Yes | Metadata/search dependent | Favourites/categories rather than general tags | Portable local tags + smart collections |
| Cloud/provider roots | Yes | Yes | Yes | Android providers and Google integrations | SAF-first, provider neutral |
| External/removable media | Yes | Yes with supported media | Yes | Yes | Yes |
| Scan to PDF | Via companion workflows | Yes | Not a core Explorer workflow | Yes | Yes, with optional OCR |
| Archive create/extract | ZIP | ZIP | ZIP | ZIP | ZIP first; encrypted ZIP, then more formats |
| Text/Markdown editing | External apps | External apps | External apps | Not a primary file-manager workflow | Built in, intentionally lightweight |
| Reversible operations | Trash/undo patterns | Recently Deleted/provider dependent | Recycle Bin/undo patterns | Trash | Operation journal + provider-aware undo |
| Local AI organization | Platform/search features vary | Platform features vary | Platform features vary | Smart categories/search vary | Optional, transparent, model-pluggable |

## Recommended minimum lovable product

The first public milestone should be narrower than “everything Finder, Files, Explorer, and Files by Google do.” A believable first release is:

1. SAF/provider browsing with reliable basic operations and progress.
2. Tabs, breadcrumbs, history, saved roots, list/grid/details, and adaptive panes.
3. Fast Markdown/text/code/JSON/YAML/CSV/image/PDF preview.
4. Lightweight text editor and new Markdown/text file creation.
5. ZIP create/extract, including password-protected ZIP, with safe defaults.
6. Theme tokens, light/dark/system, accents, density, immersive/traditional modes.
7. Search within current root plus a clearly bounded optional local index.
8. Operation journal, collision handling, cancellation, retry, and undo/Trash adapters.
9. Accessibility, keyboard/mouse, tablet/foldable, and large-directory performance tests.

Camera scanning and AI organization should follow only after file operations are demonstrably robust. They are high-visibility features but enlarge the security, model-distribution, and data-loss surface.

## Naming

- **Fylz** is short, memorable, and distinctive, but its pronunciation and searchability need testing.
- **Fyl Manager** explains the category but is less distinctive and can look like a typo.
- Recommended working product name: **Fylz**.
- Descriptive subtitle: **Local file workspace for Android**.

Before publishing to stores, run trademark, package-name, domain, and app-store availability checks; this document is not a legal clearance.

## Sources reviewed

Official documentation was preferred:

- Files by Google Help: <https://support.google.com/files/>
- Android Storage Access Framework and DocumentsProvider: <https://developer.android.com/guide/topics/providers/document-provider>
- Android all-files access guidance: <https://developer.android.com/training/data-storage/manage-all-files>
- Google Play all-files access policy: <https://support.google.com/googleplay/android-developer/answer/10467955>
- Apple Finder User Guide: <https://support.apple.com/guide/mac-help/welcome/mac>
- Apple iPhone Files User Guide: <https://support.apple.com/guide/iphone/files-iphf2d851b9/ios>
- Microsoft File Explorer guidance: <https://support.microsoft.com/windows/file-explorer-in-windows-ef370130-1cca-9dc5-e0df-2f7416fe1cb1>
- Microsoft ZIP guidance: <https://support.microsoft.com/windows/zip-and-unzip-files-8d28fa72-f2f9-712f-67df-f80cf89fd4e5>

## Research caveats

Feature availability varies by Android version, OEM, region, account state, provider, and staged rollout. Public help pages also lag experiments. Before converting a gap into marketing copy, verify it against the current production builds on representative Pixel, Samsung, tablet/foldable, Chromebook/desktop-window, removable-media, and third-party provider configurations.
