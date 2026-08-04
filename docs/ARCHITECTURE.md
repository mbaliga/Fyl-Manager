# Fylz architecture and security boundaries

## Current foundation

Fylz is a single Android application module using Kotlin, Jetpack Compose, Material 3, and the Android Storage Access Framework (SAF).

```text
Compose workspace
  ├─ tabs / navigation / responsive panes
  ├─ file views and preview/editor surfaces
  └─ explicit user actions
          │
          ▼
DocumentRepository
  ├─ DocumentsContract queries
  ├─ persisted URI permissions
  ├─ bounded text reads
  └─ provider streams for writes
          │
          ▼
Android DocumentsProvider implementations
(local, removable, cloud, or third-party)
```

The application manifest declares `MANAGE_EXTERNAL_STORAGE`. Fylz is a file manager, and the owner's
requirement is explicit: it needs full filesystem access from the start. It declares no legacy
`READ_/WRITE_EXTERNAL_STORAGE` permission.

## Storage backends

Fylz has two storage sources behind one capability adapter (`storage.StorageProvider`), and picks
between them at **runtime**, not at build time:

1. **File backend (primary).** `storage.FileStorageProvider`, backed by
   `StorageManager.getStorageVolumes()` and `java.io.File`. Active whenever
   `Environment.isExternalStorageManager()` is true. It puts internal storage, removable volumes and
   the standard shared folders on the launch surface immediately, with no picker in the happy path.
2. **SAF backend (secondary).** `storage.SafStorageProvider`. Active always.

`storage.StorageAccess` makes that choice on every call, because the user can grant or revoke "All
files access" from Settings while the app is running.

### Why SAF is kept

SAF is not merely a fallback for a declined permission, though it is that too. `MANAGE_EXTERNAL_STORAGE`
covers shared local volumes and nothing else, so SAF remains the **only** route to cloud, USB and
third-party `DocumentsProvider` roots. Both providers contribute rows to the same home surface when
broad access is granted.

SAF is not a perfect filesystem abstraction:

- providers expose different flags and capabilities;
- paths may be virtual or unavailable;
- random access, rename, move, delete, and thumbnail support differ;
- a document ID is provider-specific and must not be treated as a path;
- provider latency and offline behavior vary;
- tree access has platform restrictions for some roots.

All UI actions must therefore be capability-driven. Unsupported actions should be disabled with an explanation, not attempted optimistically and failed later.

## Package boundaries proposed for the next refactor

```text
:app                  Compose shell and navigation only
:core:model           Provider-neutral file, operation, tag, and preview models
:core:storage         SAF repository and capability discovery
:core:operations      Durable copy/move/delete/rename/archive queue
:core:preview         Preview registry and sandboxed/size-bounded renderers
:core:database        Room metadata, tags, workspace state, operation journal
:feature:browser      Folder and search views
:feature:editor       Lightweight text/Markdown editor
:feature:scan         Camera scan and PDF construction
:feature:archive      Archive UI and hardened engines
:feature:organize     Rules, duplicate tools, batch rename, smart collections
:feature:models       Local model lifecycle and inference adapters
:feature:connectors   Optional remote LLM/provider adapters
```

The current single-module slice is intentional for bootstrap speed, but new feature work should not accumulate in `FylzApp.kt`.

## Storage and operation model

### Stable identity

Use a provider-scoped identity such as `(authority, documentId)` plus URI and observed metadata. Do not key records only by display path or filename.

### Capability snapshot

Each selected document should expose normalized capabilities derived from provider flags and safe probes:

- readable / writable;
- creates children;
- renames;
- deletes;
- moves within provider;
- copies within provider;
- virtual document;
- thumbnail/random access availability.

Operations crossing providers should degrade to streamed copy + verified destination + optional source deletion.

### Durable operation queue

Copy, move, delete, restore, archive, extract, scan export, and AI-applied organize plans should become durable operations with:

- operation and item IDs;
- source/destination snapshots;
- preflight capability and free-space checks;
- collision policy per item;
- progress bytes/items;
- cancellation signal;
- terminal and retryable error classes;
- verification state;
- undo record when feasible;
- user-readable audit trail without file-content logging.

Use WorkManager for deferrable long-running operations and foreground services only where Android requires them. Small direct edits can remain immediate but should still use atomic-write patterns when the provider supports them.

## Preview safety

Previewing a file is active processing and should be treated as untrusted input.

- Text reads are bounded; the foundation caps the initial read at 512 KiB.
- Detect encoding rather than assuming UTF-8 in the mature editor.
- Images must be sampled to the viewport, not decoded at original resolution by default.
- PDFs, archives, office documents, fonts, and media parsers must be kept current and isolated behind renderer interfaces.
- HTML/Markdown must not execute arbitrary scripts or load remote resources by default.
- Mermaid or diagram rendering must use a sandboxed/offline renderer.
- Archive browsing/extraction must enforce entry-count, expanded-size, compression-ratio, path, symlink, and nesting limits.
- Thumbnail/indexing work should never block folder browsing.

## Archive boundary

The foundation contains a provider-neutral Zip4j service for ordinary or AES-256 ZIP creation and password-protected ZIP extraction. It stages data only in app-private cache and validates canonical extraction paths before writing.

Before exposing it as a finished feature:

1. wire a multi-selection UI and destination picker;
2. keep passwords in short-lived character arrays and clear them after use;
3. add expanded-size and compression-ratio limits;
4. surface overwrite and filename-conflict policy;
5. stream where the archive library allows it to reduce staging space;
6. test malformed, nested, Unicode, encrypted, and decompression-bomb fixtures;
7. make cancellation and cleanup reliable;
8. never log passwords or filenames unnecessarily.

## Scan-to-PDF boundary

Recommended pipeline:

```text
CameraX capture
  → edge/quad detection
  → user-adjustable crop
  → perspective transform
  → rotation/filter
  → page review/reorder
  → PDF page-image encoding
  → optional OCR
  → searchable text layer with coordinate mapping
  → metadata + final export through SAF
```

A “searchable PDF” claim is allowed only when selectable/searchable text is embedded in the PDF. OCR text in an app database or sidecar file is not equivalent.

Keep OCR as a replaceable adapter. A fully open-source build may choose an open OCR engine; a convenience distribution may offer an optional platform/service adapter if its license and data behavior are disclosed.

## Local AI and BYOK boundary

### Core rule

AI never obtains raw filesystem authority. It receives a bounded, typed view and returns an **operation proposal**. The deterministic operation engine validates and executes approved actions.

```text
User-selected scope
  → deterministic metadata/text extraction
  → redaction and size policy
  → local or explicitly selected remote model
  → structured proposal schema
  → validation and conflict preview
  → user approval
  → durable operation queue
```

### Local model packs

Each model pack needs:

- signed manifest and checksum;
- model/license/source metadata;
- size, memory, supported ABI/device requirements;
- input/output schema and maximum context;
- capability declaration (embed, classify, summarize, OCR, rename suggestions);
- versioned adapter;
- explicit download/delete controls;
- offline test vectors.

Model download is optional and separate from the no-network core build. Hardware acceleration should be adapter-based so LiteRT, ONNX Runtime, llama.cpp, or future engines can be evaluated without coupling file operations to one runtime.

### Remote/BYOK adapters

- No remote provider is configured by default.
- Show exactly which files/fields will be sent before the first request and for broad scopes.
- Keys belong in Android Keystore-backed encrypted storage, never source control, logs, analytics, backups, or exported settings.
- Support endpoint allow-listing and certificate/TLS defaults; reject cleartext transport.
- Provider adapters return the same structured proposal schema as local models.
- A global network kill switch and per-operation local-only option are required.

## Metadata, tags, and smart collections

Do not attempt to write arbitrary tags into every provider's files. Use a layered strategy:

1. provider-native metadata when explicitly supported;
2. app-private Room records keyed by provider document identity;
3. optional portable sidecar/export format for user-controlled migration;
4. resilient relinking heuristics using parent identity, filename, size, modified time, and checksum where appropriate.

Never hide that app-private tags may be lost or disconnected when files move outside Fylz.

## Theme architecture

The foundation exposes system/light/dark modes, accents, optional dynamic color, density, and immersive/traditional shells. Mature theming should move to semantic tokens rather than raw component colors:

- surfaces and elevations;
- emphasis tiers;
- selection/focus/drag targets;
- file-kind and status semantics;
- typography scale and monospace family;
- spacing/density scale;
- shape and motion tokens;
- high-contrast and reduced-motion modes.

Community themes should be data-only token bundles. They must not execute code or load remote assets silently.

## Broad storage access

`MANAGE_EXTERNAL_STORAGE` **is** part of the foundation. This is a deliberate reversal of the
earlier position, made by the project owner: a file manager that cannot see the filesystem until the
user picks a folder is not a file manager.

An earlier revision of this document proposed isolating broad access behind a separate build flavor.
That was implemented and then withdrawn — a compile-time split produced two apps to reason about,
two sets of Gradle variant task names, and no benefit to the person actually running the app. The
capability adapter it called for was kept; only the flavor dimension was dropped, and the choice
became a runtime one.

Obligations that come with the permission, and how they are met:

- **The user must be able to decline.** `StorageAccess` degrades to the SAF source, which still
  reaches granted subtrees, cloud and third-party providers. Nothing crashes and no screen is empty.
- **The grant flow must be honest.** `MANAGE_EXTERNAL_STORAGE` is a special access permission with no
  runtime dialog. `storage.FullAccessPermission` sends the user to the system "All files access"
  screen with a stated rationale, and the home surface re-checks on return.
- **Google Play eligibility.** Distributing on Play with this permission requires the Permissions
  Declaration Form and acceptance under the file-manager use case. Sideload and F-Droid builds are
  unaffected. This is a release-process obligation, not a code one.
- **Restricted areas.** `Android/data`, `Android/obb` and similar remain protected by the platform
  regardless of this permission; the File backend does not attempt to work around that.

### How the File backend reaches the rest of the app

`storage.FylzFilesDocumentsProvider` is a `DocumentsProvider` owned by this app, serving
`java.io.File` under the authority `io.github.mbaliga.fylz.files`, guarded by signature-level
`MANAGE_DOCUMENTS` (which same-UID Fylz bypasses and other apps cannot).

This is the load-bearing decision. `FileOperationService`, `RecycleBinService`, `ArchiveService`,
`BackupService`, `FileHistoryStore` and `DocumentRepository` all speak `DocumentsContract` against
tree URIs, and `model.FileEntry` is keyed by `Uri`. Serving broad storage *through a provider* means
those services need no second backend and no capability branching: they keep receiving ordinary
`content://` document URIs. Copy, move, rename, recycle, archive, backup and history all keep working
unchanged, and the recycle-bin contract in `docs/product/preview-and-recycle-bin-contract.md` holds
on both backends for the same reason.

## Open-source hygiene

- No production keys, signing material, user file samples, model API keys, or private Fonebrew/Studio content in the repository.
- Generate SBOM and dependency/license reports in release CI.
- Pin GitHub Actions to trusted versions or commit SHAs as the project hardens.
- Enable dependency review, code scanning, secret scanning, and signed release artifacts.
- Use reproducible release instructions and publish checksums.
- Security-sensitive parsers and operation code require tests and review.
