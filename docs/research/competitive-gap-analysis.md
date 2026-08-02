# Fylz competitive gap analysis

**Research date:** 2 August 2026
**Scope:** Android file management, Files by Google, Finder, Windows File Explorer, iOS/iPadOS Files, and established open-source Android file managers.

## Executive conclusion

The original opportunity statement needs one correction: **scan-to-PDF, ordinary ZIP creation/extraction, Trash and basic PDF tools are no longer meaningful gaps in Files by Google.** Google now documents searchable multi-page scanning, ZIP creation, ZIP-only extraction, Trash restoration, PDF search/annotation and a limited Gemini PDF workflow.

Fylz should therefore not position itself as “Files by Google plus a scanner.” Its defensible position is:

> **A minimal, open-source file workspace for Android: persistent multi-folder context, desktop-grade panes, agent-document literacy, trustworthy operations, deep theming, provider-neutral storage and optional local intelligence.**

The first shipping priority is not AI. It is reliable file work: selection, copy/move, conflict handling, progress, cancellation, recovery and previews that never leave the user’s current context.

## What a serious file manager must do

The essential feature set falls into six layers. A credible v1 does not need every advanced adapter, but it must be structurally ready for all six.

### 1. Trustworthy file operations — P0

- Create folders and files; open, rename, duplicate, copy, move, share and delete.
- Multi-select and batch actions.
- Progress in both files and bytes for long-running work.
- Cancel, retry and explicit partial-failure reporting.
- Conflict choices: replace, skip, keep both, compare, or apply the choice to remaining items.
- Trash/restore where the provider supports it; otherwise clear destructive-action warnings.
- An operation journal so the app can explain what happened after interruption or process death.
- Preserve timestamps and metadata where the source and destination providers allow it.

**Why P0:** file managers are trusted with irreplaceable data. A beautiful browser with ambiguous copy/move behavior is not a viable file manager.

### 2. Navigation and workspace — P0

- Multiple folder tabs with independent back/forward history.
- Breadcrumbs or an equivalent visible location path.
- Bookmarks, recents and pinned locations.
- Left navigation, central content and optional right preview on wide screens.
- Preview without leaving the folder.
- Traditional and immersive modes, with an obvious escape route from immersive mode.
- Keyboard, mouse, touch, D-pad and screen-reader support.
- State restoration after rotation and process recreation.

### 3. Discovery and presentation — P0/P1

- Name search, filters, sort, grouping and folders-first control.
- List, grid/gallery and metadata-rich detail views.
- Multiple density levels rather than one phone-sized row everywhere.
- File properties and provider capabilities.
- Saved searches and virtual collections once indexing exists.
- Device-wide search only when the permission and indexing model can justify it.

### 4. Content work — P1

- Safe previews for images, PDFs, text, Markdown, source code, logs, JSON/JSONL, YAML, TOML, diffs and patches.
- A lightweight text/Markdown editor with explicit save and unsaved-change protection.
- External-open fallback for unsupported formats.
- Archive inspection before extraction.
- Audio/video thumbnails or metadata without auto-playing content.
- Agent-artifact conventions: `README.md`, `AGENTS.md`, `CLAUDE.md`, plans, reports, patches, logs and machine-readable manifests.

### 5. Locations and interoperability — P1

- Android Storage Access Framework providers.
- Internal storage, SD card and USB locations selected by the user.
- SMB, SFTP and WebDAV adapters.
- Android share/open intents and “open in Fylz” deep links.
- Offline availability state for provider-backed files.
- Credential storage using Android Keystore-backed encryption.

### 6. Organization and intelligence — P2

- Tags, favorites, saved searches and virtual collections before generative AI.
- Deterministic local rules: type, date, size, location, naming pattern, EXIF and document metadata.
- Optional downloadable local models for semantic labels, clustering, duplicate review and natural-language saved views.
- Optional BYOK cloud providers behind a common adapter.
- Suggestions must be reviewable and reversible; the default output is a virtual view, not a silent physical move or rename.

## Files by Google baseline in 2026

Google’s current help documentation establishes a stronger baseline than older comparisons imply.

| Capability | Documented behavior | Product implication for Fylz |
| --- | --- | --- |
| Document scan | Auto/manual capture, crop/rotate, filters, object cleanup, multi-page capture and searchable PDF output. | Match quality and multi-page flow; do not market scanning alone as differentiation. |
| Compression | Select files/folders and create a named ZIP on Android 8+. | Ordinary ZIP creation is baseline. |
| Extraction | Preview and extract `.zip`; Google explicitly says only ZIP is supported. | Broader formats, password handling and safe archive browsing remain opportunities. |
| Trash | Move to Trash, restore, and eventual deletion; provider/path exceptions may apply. | Fylz needs a provider-aware trash model and honest fallback behavior. |
| PDF handling | Open, zoom, share, star, copy/move, file info, text search, print and annotations. The documented “Save copy” flow uploads the annotated copy to Google Drive. | Fylz should keep local PDF work local by default and clearly distinguish local from cloud actions. |
| Gemini PDF questions | Restricted to Gemini Advanced, Android 15+, PDFs under 25 MB; protected PDFs are unsupported and the file is included with the prompt. | Fylz can differentiate with provider-neutral local-first analysis and explicit disclosure of what leaves the device. |

Primary sources:

- [How to scan documents with Files by Google](https://support.google.com/files/answer/15598575?hl=en&ref_topic=7513702)
- [Compress your files](https://support.google.com/files/answer/15679548?hl=en&ref_topic=7513702)
- [Unzip your files](https://support.google.com/files/answer/9048509?hl=en)
- [Move files to Trash and restore files](https://support.google.com/files/answer/10607740?hl=en)
- [Open, manage and edit PDFs in Files by Google](https://support.google.com/files/answer/15949407?hl=en&ref_topic=7513702)
- [Ask questions about a PDF with Gemini](https://support.google.com/files/answer/15880481?hl=en&ref_topic=7513702)

## Meaningful opportunities relative to Files by Google

The safest wording is **“not documented as a first-class workflow in Google’s current help inventory”**, not “Google cannot do this.” Features can vary by device, release and provider.

| Opportunity | Why it matters |
| --- | --- |
| Multiple live folder tabs | Keeps separate work contexts open instead of repeatedly reconstructing navigation. |
| Persistent wide-screen preview | Enables inspect-and-act workflows on tablets, foldables and desktop mode. |
| Rich density and metadata layouts | Makes larger displays materially more useful rather than merely wider. |
| Built-in Markdown/structured-text editing | Treats agent output and developer artifacts as working documents. |
| Tags, saved searches and virtual collections | Organizes without forcing physical relocation. |
| SMB/SFTP/WebDAV | Makes NAS and self-hosted storage first-class locations. |
| Password-protected ZIP creation and extraction | Adds a common power-user workflow absent from Google’s documented ZIP flow. |
| Explicit conflict resolution and durable operation history | Replaces silent or provider-dependent outcomes with inspectable choices. |
| Extensible theme contract | Goes beyond a single light/dark toggle while preserving accessibility. |
| Open, auditable implementation | File access, previewing, archive extraction and AI are unusually sensitive surfaces. |
| Reversible local intelligence | Lets users organize semantically without surrendering control of their filesystem. |

## Patterns to carry forward from desktop and iOS

### Finder

Apple documents a customizable sidebar, Preview pane, icon/list/column/gallery views, sorting/grouping, tags and Smart Folders. Finder’s strongest lesson is that **organization does not always need to move files**: tags, search criteria and Smart Folders create useful virtual structures. The Preview pane also combines content, metadata and Quick Actions while preserving folder context.

Sources:

- [Use the Finder on Mac](https://support.apple.com/en-in/guide/mac-help/mchlp2605/mac)
- [Change how folders are displayed in Finder](https://support.apple.com/en-in/guide/mac-help/mchldaafb302/mac)
- [Use the Preview pane in Finder](https://support.apple.com/en-ie/guide/mac-help/mchl1e4644c2/mac)

### Windows File Explorer

Microsoft documents the familiar three-region structure—navigation, content and an optional details/preview area—plus tabs, history controls, multiple layouts, sorting and multi-selection. This is the clearest precedent for Fylz’s wide-screen shell.

A newer security lesson is equally important: Windows now disables preview for some internet-marked files because a preview could trigger external authentication through embedded references. Fylz previews must therefore be **non-executing and network-silent**: no remote Markdown images, linked fonts, external HTML resources or active document content.

Sources:

- [Navigate File Explorer in Windows](https://support.microsoft.com/en-us/accessibility/windows/use-a-screen-reader-to-explore-and-navigate-file-explorer-in-windows)
- [File Explorer disables preview for files downloaded from the internet](https://support.microsoft.com/en-US/servicing/os/windows/docs/2025/10/file-explorer-automatically-disables-the-preview-feature-for-files-downloaded-from-the-internet)

### Files on iOS/iPadOS

Apple documents create/copy/move/rename/duplicate/delete, ZIP compression/extraction, tags and favorites. iPadOS also presents external storage, file servers and cloud providers through one location model. The product lesson is to make **location a provider-neutral concept** rather than hard-code one cloud service into the information architecture.

Sources:

- [Organize files and folders in Files on iPhone](https://support.apple.com/en-ke/guide/iphone/iphab82e0798/ios)
- [Transfer files to storage devices, servers or cloud on iPad](https://support.apple.com/en-mide/guide/ipad/ipad8139864c/ipados)

## Open-source Android reference set

Two mature projects demonstrate that Android users already expect power features:

- [Material Files](https://github.com/zhanghai/MaterialFiles): breadcrumbs, root support, archives, FTP/SFTP/SMB/WebDAV, themes, Linux-aware metadata and explicit conflict/error handling.
- [Amaze File Manager](https://github.com/TeamAmaze/AmazeFileManager): multiple tabs, themes, history/bookmarks, search, archives, encryption and optional cloud services.

These projects validate demand but also reveal Fylz’s design opportunity: retain depth while reducing visible complexity and making every destructive or remote operation legible.

## Android platform constraints and decisions

### Storage access

Android’s Storage Access Framework lets the user select documents or directory trees from local and provider-backed storage without giving the app broad storage access. Persistable URI permissions allow the app to retain access after restart. That makes SAF the correct default for Fylz.

`MANAGE_EXTERNAL_STORAGE` can be justified for a genuine file manager, but Google Play restricts it and expects developers to show that privacy-friendlier APIs are insufficient. It should therefore be an optional future distribution flavor, not a prerequisite for the core app.

Sources:

- [Access documents and other files from shared storage](https://developer.android.com/training/data-storage/shared/documents-files)
- [Manage all files on a storage device](https://developer.android.com/training/data-storage/manage-all-files)

### Scanning

There are two viable implementations:

1. **Open core:** CameraX capture, local edge/corner detection, perspective correction, enhancement and `PdfDocument` export; optional local OCR.
2. **Convenience adapter:** Google Play services’ document scanner, explicitly labeled because its UI/model components are supplied dynamically rather than being the fully open provider-independent core.

Sources:

- [CameraX overview](https://developer.android.com/media/camera/camerax)
- [`PdfDocument` API](https://developer.android.com/reference/android/graphics/pdf/PdfDocument)
- [ML Kit document scanner](https://developers.google.com/ml-kit/vision/doc-scanner/android)

### Archives

The archive layer should separate broad-format reading from secure ZIP writing:

- Zip4j provides ZIP creation/update/extraction, streams, password-protected ZIPs, AES and standard ZIP encryption, Zip64, split archives and progress monitoring. Its current README lists version 2.11.6.
- Apache Commons Compress can cover additional formats, but its own documentation notes that ZIP encryption is not supported, so it is not sufficient for the password-protected ZIP requirement by itself.

Regardless of library, Fylz must independently enforce canonical destination paths, entry-count limits, decompressed-byte limits, compression-ratio limits, symlink policy, available-space checks and cancellation checkpoints.

Sources:

- [Zip4j](https://github.com/srikanth-lingala/zip4j)
- [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/)

### Local AI and BYOK

Model integration must be an adapter, not a dependency of the file manager. Google’s current mobile guidance supports on-device Gemma through Google AI Edge/MediaPipe, and Android’s 2026 developer material introduces newer on-device GenAI paths. These APIs and device requirements are moving quickly, so Fylz should keep a stable provider-neutral `OrganizerEngine` contract and treat each runtime as replaceable.

Credentials for optional cloud providers must be protected with Android Keystore-backed encryption, never logged, and sent only to the selected provider for a user-initiated operation.

Sources:

- [Deploy Gemma on mobile devices](https://ai.google.dev/gemma/docs/integrations/mobile)
- [Android Keystore system](https://developer.android.com/privacy-and-security/keystore)

## Recommended release sequence

| Release | Outcome | Exit gate |
| --- | --- | --- |
| 0.1 Foundation | SAF browsing, tabs/history, adaptive panes, Markdown/text/image/PDF preview, editor and themes. | Build/lint/tests pass; real-device provider smoke test documented. |
| 0.2 Operations | Multi-select, copy/move/duplicate, durable progress, cancellation, conflicts and operation history. | Interruption and partial-failure tests; no silent overwrite. |
| 0.3 Archives | Archive browser, ZIP create/extract, AES password ZIP, format adapters and bomb/traversal defenses. | Malicious corpus tests and byte/entry caps. |
| 0.4 Scan | Multi-page capture, crop/rotate, enhancement, searchable PDF and filing flow. | Offline export, rotation/process-recreation and large-document tests. |
| 0.5 Locations/search | Bookmarks, recents, saved views, metadata index, SMB/SFTP/WebDAV adapters. | Offline state, credential isolation and provider error recovery. |
| 0.6 Local organization | Deterministic rules, tags, virtual collections, duplicate review and local model adapter. | Suggestions are explainable, reversible and never auto-mutate. |
| 0.7 BYOK | Explicit cloud-provider adapters and per-operation disclosure. | Keystore storage, redacted logs and network-boundary tests. |

## Build decision

The foundation should remain deliberately narrow: no network permission, no broad-storage permission and no placeholder AI SDK. That makes its privacy claim inspectable and gives the next milestone—reliable file operations—a stable base.
