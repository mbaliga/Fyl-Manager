# Architecture

## Current shape

The foundation is a single Android application module using Kotlin, Jetpack Compose and unidirectional state.

```text
UI (adaptive workspace, panes, editor)
        |
BrowserViewModel (tabs, history, selection, operation state)
        |
SafFileRepository (DocumentsContract / ContentResolver)
        |
Android document providers selected by the user
```

The first commit intentionally has no network or broad-storage permission.

## Storage strategy

### Default distribution: SAF-first

The user selects a tree with `ACTION_OPEN_DOCUMENT_TREE`. Fylz persists read/write access and operates on provider URIs. This supports internal folders, removable media and providers while minimizing blanket access.

### Future full-access distribution

A file manager is an eligible use of `MANAGE_EXTERNAL_STORAGE`, but Google Play requires a permissions declaration and proof that privacy-friendly alternatives are insufficient. A later `full` flavor may use it for device-wide search and automation. The default app remains functional without it.

## Planned modules

```text
:app                 shipping shell and composition root
:core:model          provider-neutral file, tab, query and operation models
:storage:saf         Android Storage Access Framework adapter
:storage:full        optional all-files adapter
:operations          copy/move/delete queue, conflicts, undo journal
:preview              preview registry and safe decoders
:editor               text/Markdown editing
:archive              ZIP/7z/tar inspection and operations
:scanner              capture, crop, enhancement, OCR and PDF export
:remote               SMB/SFTP/WebDAV adapters
:organize             deterministic rules and virtual collections
:ai:api               provider-neutral model contracts
:ai:local             downloadable on-device runtimes
:ai:cloud             optional user-configured providers
:theme                token contract and import/export
```

Modules should be introduced when their boundaries are exercised; the repository should not be split merely to look architectural.

## File operations

Every copy/move/archive/scan operation will be represented by a durable job with:

- stable identifier and source/destination snapshots
- progress measured in files and bytes
- cancellable checkpoints
- conflict policy: ask, replace, skip, keep both or apply to remaining
- per-item results and partial-failure summary
- resumability where provider semantics permit it
- an undo journal for reversible operations

Direct destructive operations remain confirm-gated until trash/undo support lands.

## Preview registry

Preview selection is based on MIME type plus extension fallback. Renderers have byte/page limits and never execute document content. The initial registry supports Markdown, structured text, images and first-page PDFs. Future renderers include archives, audio waveforms, video thumbnails, office documents and metadata inspectors.

## Scanner

The open-source default should use CameraX, local edge/corner detection, perspective correction, enhancement and Android `PdfDocument`; OCR is optional and local. Google Play services' document scanner can exist only as an explicitly labeled optional adapter because it is not the fully open, provider-independent core.

The scan session is persisted after each accepted page so an interruption cannot discard a long document. Export writes to a user-selected provider URI and uses atomic temporary output where supported.

## Archives

- ZIP creation/extraction and AES password protection through a reviewed library such as Zip4j
- tar/7z/compressor coverage through Apache Commons Compress where its format limitations fit
- inspect-before-extract UI
- zip-slip/path traversal rejection
- decompression-bomb limits and available-space checks
- passwords kept in memory only by default, never logged

## Local and cloud AI

`OrganizerEngine` accepts a bounded metadata/content snapshot and returns suggestions, never direct filesystem mutations.

```kotlin
interface OrganizerEngine {
    suspend fun propose(request: OrganizeRequest): OrganizeProposal
}
```

The deterministic engine (rules, extensions, dates, duplicate hashes) is always available. Local model adapters can use downloadable GGUF runtimes such as llama.cpp or platform inference APIs. Cloud adapters are optional, user configured and visibly mark exactly which files or excerpts will be sent.

AI-created organization is represented as tags, saved searches and virtual collections. Applying physical moves is a separate preview-and-confirm operation.

## Design-system boundary

Fylz may consume public Hyle token outputs or map compatible tokens, but it must remain independently buildable. Private Fonebrew Studio code and assets are out of scope. The floating pane is implemented from the behavioral requirement, not copied implementation.
