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

Copy and move (Phase 1's own stated scope) and, since M3.4, a plain archive's selective extract are
durable operations, backed by a plain `SQLiteOpenHelper` (`data.FylzDatabase`, `operations`/
`operation_items` tables, `v3` adding `extract_plans`/`extract_plan_items`/`extract_entry_digests`)
rather than the SharedPreferences journal the foundation originally used. Delete/restore/scan-export/
AI-organize plans remain the "should" case below, not yet durable in this sense; an encrypted ZIP's
extraction stays on `data.ArchiveService.extractZip` (zip4j, `FileOperationType.EXTRACT` with no
plan row) until M3.9/M3.10:

- operation and item IDs, with per-item state (`operations.OperationState`, including a
  `PARTIAL` state when some items in a batch succeeded and others failed);
- source/destination snapshots per item;
- preflight capability and free-space checks (`operations.PreflightPolicy`/`storage.VolumeInfo`) —
  filesystem-specific filename limits, case-insensitive collisions, free space with a margin — shown
  to the user as a per-item auto-rename/skip/cancel sheet before the durable work starts;
- a per-item collision policy (`operations.ConflictPolicy`/`ConflictSheet`: replace, replace-if-newer,
  keep both, skip), resolved the same way, before the transfer starts;
- progress bytes/items, throttled to at most every 250 ms or 8 MiB of a transfer's own writes so the
  journal isn't rewritten on every buffer read;
- a cancellation signal, and a real `TransferWorker` running copy/move as WorkManager foreground work;
- terminal (`FAILED`) vs. retryable (`PARTIAL`) error classes — `OperationRetryPolicy.plan()` replays
  only a `PARTIAL` operation's own failed items, not the whole batch again;
- optional SHA-256 verification state per file item (`operations.VerifySettings`'s three-way mode,
  automatic for removable/network destinations), stored alongside the item so a mismatch can be
  inspected rather than silently discarded;
- a user-readable audit trail (the Recovery destination's operation history) without file-content
  logging.

One item's own failure no longer aborts the rest of the batch: `FileOperationService.transfer`
records that item as `FAILED` and continues, only rethrowing (as itself, when exactly one item
failed) once every item has had its turn. A `TransferEngine` abstraction (`operations.TransferEngine`,
`LocalFileTransfer`/`DocumentsTransfer`) picks a fast path — direct `File.renameTo`/kernel-level copy
when both ends are files this app's own provider serves, `DocumentsContract.moveDocument`/`copyDocument`
when a foreign provider supports them — before falling back to a provider-neutral stream copy.

`WorkManager` runs the deferrable transfer itself as foreground work; small direct edits (rename,
create) remain immediate. Delete/restore/scan-export and AI-applied organize plans are the still-
outstanding "should" case: they are not yet threaded through this same queue, and an undo record
beyond recycle-bin restore remains future work.

**EXTRACT's lifecycle** (M3.4, `operations.ArchiveExtractor` run by `TransferWorker`) differs from
copy/move's in three ways the code depends on:

- **Planned, not just enqueued.** `operations.ExtractPlanner.plan` runs entirely in the UI process
  before anything is queued — the structural verdict (fail closed from the archive's persisted
  listing summary), the selection expanded to a bitmap of header ordinals, top-level preflight and
  conflicts, and the consent-aware confirm — and writes an `ExtractPlan` beside the `FileOperation`
  in one transaction (`OperationsDao.putWithExtractPlan`) before `OperationRunner.enqueueExtract`
  ever calls `WorkManager`. A `FileOperation(EXTRACT)` with no plan row is the legacy encrypted-ZIP
  path (`ArchiveService.extractZip`) and is excluded from every rule below.
- **Cancel is a flag, never `WorkManager.cancelWorkById`.** `OperationRunner.cancel` sets
  `extract_plans.cancel_requested` (also reachable from the extraction notification's own
  `PendingIntent`, `operations.ExtractCancelReceiver`); the running `ArchiveExtractor` polls it
  between frames and on a liveness timer, stops the pass, deletes staged documents and writes
  `CANCELLED`. **Why this matters:** WorkManager 2.11.2 cancels or fails *every dependent* of a
  cancelled or `Result.failure`-ing request in an `APPEND_OR_REPLACE` unique chain
  (`fylz-transfers`) — calling `cancelWorkById` on one queued extraction would silently kill every
  transfer queued behind it. This is a real, **pre-existing** hazard for copy/move too (recorded in
  `docs/agent/REVIEW_QUEUE.md` under M3.4, not fixed there); EXTRACT is simply the first type built
  to avoid it from day one.
- **Every journaled outcome is `Result.success()`.** `TransferWorker`'s EXTRACT branch returns
  `Result.success()` for SUCCEEDED, PARTIAL, FAILED, CANCELLED and "not claimed" alike — the
  journal, not WorkManager's own terminal state, is the record of what happened — so a queued
  transfer behind a failed or cancelled extraction is never poisoned by it. Only a genuine **system
  stop** (`isStopped` with a reason other than `STOP_REASON_CANCELLED_BY_APP`) returns
  `Result.retry()`, after the extractor has left every unfinished item `PAUSED_BY_SYSTEM` with its
  staging kept, for the re-run to resume; `OperationRunner.recover` reconciles a planned row whose
  tagged work has vanished into `INTERRUPTED` (staging deleted) and a `QUEUED` row older than 60 s
  with no tagged work into `FAILED / NEVER_RAN`. `OperationRetryPolicy.ReclaimExtract` retries by
  moving the **same** operation's unfinished items back to `QUEUED` and re-enqueuing (a copy/move
  retry, by contrast, replays as a brand-new operation).

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

The foundation contains a provider-neutral Zip4j service for ordinary ZIP creation and, since M3.4c,
**only password-protected ZIP extraction** — a plain archive's extraction moved onto the Rust engine
through the transfer queue (see "Selective extract" below); `ArchiveService.extractZip` stages the
whole archive to app-private cache only for that narrowed case, and its structural decision now comes
from the same engine call (`client.inspectArchive` over the staged copy) rather than a Kotlin policy
copy. Since M3.2, *inspecting* an archive no longer stages anything: `archive.ArchiveSource` resolves
the `Uri` to a seekable descriptor (the provider's own, whenever `statSize >= 0`) and only a provider
that can merely stream is copied to app-private cache first, after a space check, with the copy
released as soon as the summary is in hand; the listing itself is done by `fylz-archive` in the
isolated decoder process (see below). Since M3.3 an archive also *browses* as a folder through
`storage.ArchiveDocumentsProvider`, with its listing and each opened entry streamed out of the
decoder process through pipes (see "Archives as documents" below).

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

**A separate, already-implemented feature meets this bar today, for existing PDFs rather than a
fresh camera scan:** `pdf.PdfPageTools` (P1.13; consolidated from an earlier, less capable
`PdfToolService`) inspects, extracts a page range with per-page rotation, and merges PDFs, DPI-aware
rather than a fixed render size, via platform `PdfRenderer`/`PdfDocument` only (no OCR engine is
required for the non-OCR path). Its optional searchable pass runs ML Kit `TextRecognizer` on the
same bitmap `PdfDocument`'s page draws, before rotation, and draws the recognized lines back as a
near-transparent text layer through the identical rotation matrix — real embedded text, not a
sidecar. `OcrEngine`/`OcrEngineFactory` (`pdf/OcrEngine.kt`) is exactly the replaceable-adapter seam
this section calls for, already shared with `SearchablePdfService`; `MlKitOcrEngine` is its only
implementation today, and swapping it is decision D1 in the instruction files, not yet made. The
camera-capture pipeline above (`CameraX capture → … → searchable PDF export`) is the still-outstanding
"recommended pipeline" — `scan.DocumentScanner` exists but does not yet route through
`PdfPageTools`'s OCR pass.

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

## Local index and search (P1.12)

A background scan of a user-chosen scope (a folder tree opted into indexing) populates
`index.IndexDao`'s SQLite tables (`index_files`, a name/path/text FTS4-or-FTS5 virtual table chosen
at open time by a real create-and-drop capability probe, `index_scopes`, `index_smart_collections`,
`index_state`) inside `data.FylzDatabase` — the same database P1.1's operation journal lives in, not
a second store. This replaced four independent JSON files and consolidated what had grown into
several separate candidate indexing/organize implementations into this one.

`search.RecursiveSearchEngine` answers a query from the index instead of walking the provider live
whenever the searched folder's scope is fully indexed; name/metadata matching reuses the exact same
predicates the live walk uses, so an indexed and a live-walk search return identical results for
those two kinds of query. Content search (`content:`/quoted-phrase) is FTS-tokenized rather than an
exact substring match — a deliberately narrower, stated trade-off, and a capability that did not
exist at all before the index. Every result still respects the same folder-scoping and
trash/staged-write exclusion the live walk already enforced.

For internal storage specifically, a rescan is skipped when `MediaStore.getGeneration` hasn't
changed since the scope's last scan (`index.shouldRescan`); a removable or third-party volume, which
carries no such generation, is always rescanned on demand. A genuine per-row `GENERATION_MODIFIED`
delta sync — updating only the rows MediaStore says changed, rather than either walking everything
or skipping everything — remains explicitly out of scope, not attempted.

## Isolated decoder process (M2.4)

`decoder.DecoderService` is the isolation boundary the Preview safety section above calls for:
`android:isolatedProcess="true"`, `android:process=":decoders"`, `android:exported="false"`. It
has no permissions and cannot open a file by path — every input arrives as a
`ParcelFileDescriptor` the caller already holds open, over the `IDecoderService` AIDL interface,
so a crash while parsing a hostile file cannot take the host app down with it, only that one
request. Today its two methods are trivial (`ping()`, and `sniff()` delegating straight to
`core.FylzCore.sniffFile` — M2.2's uniffi stub, replaced for real by M2.5's `fylz-sniff`); future
native parsers (archives, disk images, fonts, media) route through this same process rather than
running in-process, closing the Preview safety section's own "isolated behind renderer interfaces"
requirement one format at a time.

The first real format landed in M3.2: `inspectArchive(archive, limits, maxRows)` hands the process
a `ParcelFileDescriptor` and gets back an `ArchiveInspection` Parcelable -- the archive's format
family, counts, sizes, encryption flags, the Rust extraction policy's verdict and at most `maxRows`
listing rows (500 by default, about 50 KB, because the Binder transaction buffer is 1 MB per
process and a full listing would overflow it around ten thousand entries; since M3.3 the full
listing streams through a pipe instead -- see "Archives as documents" below). The engine (`fylz-archive`, over libarchive) reads through that
descriptor with seeks -- the ZIP central directory, a 7z's trailing header and pack streams, an
ISO's directory extents -- so **the descriptor must refer to a regular file**: the engine `fstat`s
it, refuses anything else as `NOT_SEEKABLE`, and rewinds it to byte 0 first (libarchive seeks
absolutely but treats the first byte read as offset 0, and a Binder dup shares the file offset).
`archive.ArchiveSource` is what upholds that rule on the UI side: it opens the `Uri`'s own
descriptor when the provider gives a seekable one and stages to cache only when it does not.
Errors travel as data (`outcome` + the engine's message), never as exceptions across Binder; a
Kotlin exception in the service maps to `INTERNAL` with the class name. The process runs the
engine under section 4.4's 30 s "structure" budget (`DecoderClient.STRUCTURE_TIMEOUT_MILLIS`),
distinct from the 5 s the two quick calls get, and the client's `DecoderCall { Ok, TimedOut,
Failed }` lets the UI say "took too long to read" for a multi-GB compressed tarball rather than
"could not be read safely" for a dead process. The client and the `archive.ArchiveInspector` that
drives it are application-scoped (`FylzApplication.archiveInspector`); since M3.3 the binding is
dropped after 60 s with nothing in flight (see "Archives as documents" below).

`decoder.DecoderClient` owns the other half of the contract section 4.4 asks for: a per-call
timeout (5 s for `ping`/`sniff`, the 30 s structure budget for `inspectArchive`), and dropping the
connection on either a timeout or a crash so the next call rebinds fresh rather than reusing a
connection to a process that may already be gone. The timeout abandons the call rather than waiting it out: each
Binder transaction runs in a client-owned job outside the caller's coroutine scope, so when the
deadline passes the caller gets its failure value and the client unbinds immediately, while the
hung transaction is left to return on its own (normally with `DeadObjectException` once the process
has been reaped) and is swallowed with one warning; nothing on the client side can interrupt the
Binder thread in `:decoders`, only the platform reaping the process ends it. Losing every binding
to an isolated process is what lets the platform actually kill it — `unbindService` is the "kill"
half of "kill-and-restart," there is no separate `Process.killProcess` call to make from the client
side. `DecoderClientTest` covers this retry/timeout state machine against a fake
`IDecoderService.Stub` bound through a test-injected seam; genuine cross-process crash and timeout
behaviour is real-device-only and is tracked in `docs/agent/DEVICE_CHECKS.md` instead, since
Robolectric runs every "process" in one JVM.

## Archives as documents (M3.3)

Since M3.3 an archive opens as a folder: tapping `photos.zip` pushes a location whose Uri belongs
to `storage.ArchiveDocumentsProvider` (authority `io.github.mbaliga.fylz.archives`), and every
entry inside is a document of that provider -- listed, previewed, shared, opened with another app
and copied out through exactly the code paths a file in any other provider takes. Nothing in the
browser knows what an archive is beyond `model.BrowsableArchiveFormats` (the explicit set of
formats `fylz-archive` reads: `zip zipx jar apk cbz 7z cb7 tar tgz tbz tbz2 txz tzst tar.gz tar.bz2
tar.xz tar.zst tar.lz4 iso cpio ar deb rpm cab lha lzh warc`; `rar`/`cbr`, `arj img dmg wim xar`
and single-file `gz bz2 xz zst lz4` streams are excluded with their reasons in the file) and
`actions.LocationKind` (`FOLDER`/`ARCHIVE`, derived from the current location's authority).
`docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md` is the design; this section is the contract.

**The provider.** Modelled on the File provider's manifest entry: `exported`, `grantUriPermissions`,
protected by `MANAGE_DOCUMENTS`, and **without** a `DOCUMENTS_PROVIDER` intent filter -- it is not a
picker root (`queryRoots` is empty) but a document authority, so the system picker never shows it
while share and "Open with" grants pass through it like any other document Uri. Its Uris are the
non-tree form `content://io.github.mbaliga.fylz.archives/document/<id>`; `data.DocumentRepository
.listChildren` and `operations.DocNode.children` take the non-tree branch whenever
`!DocumentsContract.isTreeUri(folderUri)` (`buildChildDocumentsUri`/`buildDocumentUri` on the
document's own authority), which is what lets the browser, directory copy-out and the preflight
size walk work unchanged. A listing failure travels as `EXTRA_ERROR` on a rowless cursor and both
callers throw it as an `IOException` with the message. `openDocument` is `"r"` only; every write
method throws `UnsupportedOperationException` (the registry disables the writing actions inside an
archive: cut, move, recycle, rename, tags, batch rename, paste, new folder/file, scan to PDF,
favourite, AI organise, find duplicates -- until M3.6). `getDocumentType` answers from the same
extension table the rows use.

**Ids** (`archive.ArchiveDocumentId`) are base64url of `{v, src, n, o, p}`: the source document Uri,
the chain of nested-archive paths (depth at most 4; the fifth level is refused with a toast), the
entry's **ordinal** and its normalised path. The ordinal is the 0-based index of the raw
`archive_read_next_header` call that produced the entry, counting every header (the ISO/tar `.` root
the engine drops, links, special files), from the one counter every engine pass shares; opening an
entry asks the engine for that ordinal *and* the byte-exact path it carried, so an id that no longer
matches the archive (a replaced file behind the same Uri) is "not found", never a different member.
Implicit directories -- parents the archive never stored -- have ordinal `-1` and cannot be opened.

**The listing transport.** A Binder transaction is capped at 1 MB per process, so listings never
travel as Parcelables. `archive_list_into(fd, limits, sink_fd)` writes the `FZL1` codec (one record
per header: ordinal, path, kind, flags, uncompressed size, mtime, mode, link target; a trailer with
the count and a `partial` flag) into a pipe *during* the header pass, and `decoder.DecoderClient
.callStreaming` drains the read end on its own 4-thread pool into `cache/archive-listings/<key>.
<nonce>.part`, renamed into place only when the call returned success and the trailer decoded.
The drain always runs to EOF before the call is judged, so Robolectric's file-backed pipes and a
device's real pipes behave alike. A streaming call has no flat deadline: it is abandoned after
**30 s of inactivity** (`STREAM_INACTIVITY_MILLIS`), where activity is bytes arriving *or* the
engine's read offset moving -- probed once a second through `Os.lseek(SEEK_CUR)` on the caller's
descriptor, which shares the file offset with the Binder dup the engine reads through. A damaged
archive lists partially (`partial` set, "Damaged after N entries") rather than not at all; one over
`ArchiveLimits.maxListingEntries` (200,000) is refused. The Rust `fylz-ffi-android` crate ignores
`SIGPIPE` once per process, since a cdylib gets no `SIG_IGN` from Rust's runtime and a cancelled
drain would otherwise kill `:decoders` instead of failing the write.

**The catalog** (`archive.ArchiveCatalog`) owns one listing per archive, on disk and in memory.
The key is `sha256(src | size | mtime)` from a `queryDocument` of the source, chained for nested
archives (`sha256(outerKey | path | uncompressed | mtime)`) so a changed outer file invalidates
every inner key. `open` is single-flight (one `Deferred` per key) and blocking: memory LRU of two
`ArchiveTree`s, then the `.fzl` plus its `<key>.summary.json` sidecar on disk (format, counts, the
policy verdict, `partial`, `structuralRefusal`; a listing without a readable sidecar fails closed and
is re-listed, because entry opening must never guess the verdict), then `:decoders`. Every provider
method blocks on it -- there is no `EXTRA_LOADING`/`notifyChange` protocol -- so a cold process (a
worker resuming, another app opening a granted Uri, session restore) gets the full listing, never
an empty folder. Failures are memoised per key until `fylz.refresh` (`forgetFailures`) so the
provider never loops against `:decoders`. A handle pins the **source**, never a descriptor:
`PinnedSource` re-opens the document (or the staged/materialised file) for every engine call,
because a Binder dup shares its file offset and two concurrent calls through one descriptor would
interleave. Handles are reference counted, so LRU eviction never cuts off an in-flight fill.

**The tree** (`archive.ArchiveTree`) normalises paths the way the engine's `normalized_path_key`
does (a `./` prefix and `.` segments dropped, ZIP backslashes to `/`, trailing `/` removed), keeps
the last member of a duplicate path in the first one's position, lets a directory beat a file at
the same path, synthesises implicit parents, and quarantines entries the policy refused (`..`,
absolute and drive-qualified paths) -- the folder view hides them and the inspection view shows the
count. `partial` and `structuralRefusal` come from the summary; a policy-refused archive still
browses, but its entries do not open.

**Opening an entry** (`archive.ArchiveEntryCache`) materialises it once: `archive_extract_entry_at
(fd, ordinal, path, limits, sink_fd)` streams the member into a pipe, drained into
`cache/archive-entries/<key>/<ordinal>.part` and renamed, and the caller gets a read-only, seekable
descriptor on that file -- so a video entry scrubs and a PDF entry pages like any local file. Two
fills run concurrently (`MAX_CONCURRENT_FILLS`), the rest wait; a second request for a filling entry
awaits the same fill. Refused, with a message the preview shows: directories and implicit
entries, links and special files (hardlinks resolve to their target first), encrypted entries
(until the password path lands), entries over `min(maxFileBytes, 512 MiB)` or without room in cache
("Extract it instead"). A nested archive is materialised the same way and pinned as the inner
handle's source for as long as the handle lives.

**Budgets and sweeping** (`archive.ArchiveCacheSweeper`, once per process from the first catalog
open or inspection): listings 64 MB LRU by mtime, entries 512 MiB LRU with pinned files exempt,
`.part` files older than an hour removed, `archive-work` staging older than 24 h removed. The
thumbnail pipeline skips archive Uris (a thumbnail would be a fill per visible image), recursive
search is off inside an archive until M8's engine can walk it, and the destination chooser does
not offer an archive location.

**Decoder lifetime.** `DecoderClient` idle-unbinds `:decoders` after **60 s with nothing in
flight**: the timer arms when the in-flight count reaches zero, every call start cancels it, and
its drop carries the binding generation it was armed for, so a call that began in between (and
bumped the generation) is never cut. This closes M3.2's open question; the M3.2 measurements that
motivated it are in `docs/agent/REVIEW_QUEUE.md` under M3.3.

## Selective extract (M3.4)

Extract is four registry actions over one flow, one framed pass in a second isolated decoder
instance, and destination-aware limits with explicit consent. `docs/agent/DESIGN-M34-SELECTIVE-
EXTRACT.md` is the design; this section is the contract.

**The actions and the flow.** `fylz.extract` (a FOLDER location, one selected archive matching
`model.BrowsableArchiveFormats` — every format the queue reads, not the old zip4j-only set) opens
`ui/actions/ExtractSheet.kt`'s three choices (`fylz.extract.here`/`.folder`/`.to`); inside a browsed
archive `fylz.extract.selected` takes the slot instead and goes straight to the destination chooser.
Exactly one of the two selection-bar actions ever renders, gated by `actions.LocationKind`. All five
hand off to `ui/actions/ExtractFlow.kt`, which owns the whole back-and-forth: a "Reading archive…"
dialog with Cancel while `operations.ExtractPlanner.plan` runs, its own `PreflightSheet`/
`ConflictSheet` instances (conflicted archive entries reach `ConflictSheet` through
`operations.DocNode.descriptor` — a synthetic node with no hash-on-demand, since there is nothing a
`ContentResolver` can open at an entry's own "Uri" until it is written), and the consent-aware
confirm sheet. `ArchiveToolsOverlay`'s own "Inspect and extract" button calls the same flow
(`ActionContext.openExtractMenu`) once its own picked archive matches the same browsable set.

**Planning is entirely in the UI process, before anything is queued.** `ExtractPlanner.plan` opens
the archive through the same `archive.ArchiveCatalog` browsing uses (single-flight, disk-first),
reads the persisted listing summary's structural verdict **fail closed** (no verdict, or a refusal,
refuses the whole extraction), expands the selection over the `archive.ArchiveTree` into a bitmap of
header ordinals (a hardlink pulls in its target's ordinal, written only under the link's own path;
symlinks and special files are skipped and counted), lays out top-level items, runs the existing
preflight/conflict machinery over them, and — when the numbers cross 4 GiB expanded or 10,000
entries — requires an explicit consent tick before the caps relax. The result and its `ExtractPlan`
(archive root Uri, catalog key, layout, the ordinal bitmap, limits, consent, per-item conflict
resolutions) are written to `data.FylzDatabase`'s `extract_plans`/`extract_plan_items` tables
alongside the `FileOperation`, atomically, before `OperationRunner.enqueueExtract` ever calls
WorkManager — see "Durable operation queue" above for what happens after that. An archive with
protected entries takes the legacy `ArchiveService.extractZip` path when the whole archive is
selected and it is a ZIP (until M3.9 gives every format a password prompt through this same queue);
any other encrypted selection is refused.

**One pass, one isolated instance, frames.** `operations.ArchiveExtractor` re-opens the archive
through the catalog (never a descriptor shared with browsing — a `ParcelFileDescriptor` dup shares
its file offset, and two concurrent engine calls on one would interleave) and runs **one**
`extractRanges` call on a **second, dedicated** isolated decoder instance
(`decoder.DecoderClient.forExtraction`, `bindIsolatedService(..., "extract", ...)` → process
`:decoders:extract`), so a browse call's own 30 s inactivity timeout never reaps a running extraction
and vice versa; the instance is unbound when the run ends (no idle policy). The engine streams a
little-endian frame protocol (`archive.ExtractFrameReader`/`fylz-ffi-android`'s `frames.rs`: `MAGIC
BEGIN DATA END FAIL DONE ABORT`) through the same kind of pipe the listing uses, demultiplexed
straight into staged destination documents by `ArchiveExtractor` itself (the `ExtractFrameSink`):
a raw-path check against the plan on every `BEGIN`, directories created on demand, a SHA-256 fed as
bytes arrive, an app-side write failure (`ENOSPC`, `EFBIG`, a provider exception) failing only that
entry while the pass continues. A header- or data-level `FAILED` fails the entry and the pass
continues; `FATAL` aborts the whole call, and the extractor **re-issues once** for the ordinals after
the last completed one, with the limits reduced by what already landed (cheap for a seekable ZIP/7z/
ISO; a stream format re-reads from the start, the only way to resume it). Verification
(`operations.VerifySettings`) re-reads the **staged** file and fills `extract_entry_digests` per
entry when it runs; `TargetPlanning.kt`'s `TargetPlanner`/`NameIndex` (shared with copy/move) finalise
each item exactly like a copy does (replace-with-recycle).

**Limits and consent** (`decoder.ArchiveLimits.forExtraction(volume, consent)`): without consent the
old caps hold (4 GiB total, 1 GiB per file except vfat's 4 GiB − 1 rule, 10,000 entries, ratio 200);
with consent a ratio-≤200 archive may write into the destination's free-space margin (`free −
max(5 %, 100 MiB)`, recomputed again at claim against current free space); with the destination's
free space unknown, the old 4 GiB cap holds regardless. The ratio rule can never be consented past.
The Kotlin planner is the gate for **structural** rules (paths, duplicate keys, links, unknown
sizes); the Rust engine is the gate for **size** rules over the selection, re-checked at the start of
`archive_extract_ranges` and again per entry as it is written (defence in depth against a plan gone
stale between planning and the run).

**What device checks and the review queue cover:** `docs/agent/DEVICE_CHECKS.md` section 19 (the
isolated instance appearing and being reaped, SELinux on the pipe, the 5 GB acceptance case, cancel
leaving a queued transfer behind it to run, `kill -9` mid-extract resuming); `docs/agent/
REVIEW_QUEUE.md`'s M3.4 entry (limits and consent's exact exposure, the WorkManager chain-poisoning
hazard COPY/MOVE still has, mtimes not preserved, and every other logged deviation from the design).

## Compress (M3.5)

Compress is the inverse of selective extract: one framed pass into a **third** isolated decoder
instance, planned entirely in the UI process first. `docs/agent/DESIGN-M35-CREATE.md` is the
design; this section is the contract.

**The actions and the flow.** `fylz.compress` (the selection bar) and `ArchiveToolsOverlay`'s own
"Create ZIP" (`ActionContext.openCompressMenu`, its own picked sources) both open
`ui/actions/CompressSheet.kt`: archive name, format, Fast/Normal/Best, an optional split size, and
the "paths relative to the selection" toggle. Two buttons stand in for a single "Next", since each
needs its own picker flow: "Choose folder…" opens the in-app/system folder chooser
`ui/actions/CompressFlow.kt` already owns for every other destination pick in this app; "Save as…"
launches `CreateDocument` for a single output file with no tree grant at all, disabled whenever a
split is chosen (one system-picked document is the only output that destination can ever offer).
The overlay's AES-protected "Create ZIP" (the password dialog's encrypt switch on) still runs the
pre-M3.5 `data.ArchiveService.createZip` (zip4j) path instead — the new engine has no password
support yet (§2.7 below).

**Planning is entirely in the UI process, before any output exists.** `operations.CompressPlanner`
walks every selected source (bounded depth against a directory-symlink loop the provider's own path
check does not catch), sanitising and case-insensitively uniquifying each path component as
discovered, naming entries relative to the selection or prefixed with the parent's root-relative
path per the toggle. A source under the archive provider's authority is planned against
`archive.ArchiveCatalog` directly: a link, an encrypted entry, an oversized entry, or a source
archive that is unreadable, partial or structurally refused is a skippable problem for *that* source
alone, never a whole-plan refusal, mirroring `CompressProblem`'s own per-entry/per-source shape. A
conflicting output name — and, if split, its whole numbered `.001…` set — is resolved **as one
unit**, never per part; sizes are never trusted from planning, only read again at feed time. The
result (`CompressPlan`, `CompressManifestEntry` rows — deliberately no size column) is written to
`data.FylzDatabase`'s `create_plans`/`create_plan_items`/`create_manifest` tables alongside the
`FileOperation`, atomically, before `OperationRunner.enqueueCreate` ever calls WorkManager — see
"Durable operation queue" above for what happens after that. A "Save as…" destination (no
`destinationUri`) never reaches the queue at all: `operations.ArchiveCreator` itself refuses a plan
with no destination folder outright, so `CompressFlow.writeDirectly` runs that one pass itself,
synchronously, with no staging, no split, no verification and no durable retry.

**One pass, a third isolated instance, frames — with a stated concurrency invariant.**
`operations.ArchiveCreator` claims the plan (mirroring `ArchiveExtractor` exactly: plan rows missing
or unreadable fail without retry, a prior spooled temp file is re-spooled if missing) and runs
**one** `writeArchive` call on a **third, dedicated** isolated decoder instance
(`decoder.DecoderClient.forWriter`, `bindIsolatedService(..., "write", ...)` → process
`:decoders:write`). The feeder, the drain and the Binder transaction each run on their **own
dedicated executor** (`DecoderClient.createTransactionDispatcher`) — a small fixed pool used *only*
for create operations, never the browsing pool `callStreaming` uses for `inspectArchive`/
`listArchive`/`extractEntry` and never the extraction pool M3.4 dedicates to `extractRanges`.
Sharing either would deadlock a concurrent browse/extract against a compress: the create call's own
liveness watchdog pauses whenever it reports "busy", and two independently-busy calls sharing one
small pool starve each other forever. The feeder writes `FZW1` frames (`archive.ArchiveFrameWriter`
mirrors `fylz-ffi-android`'s `frames.rs` reader) — `ENTRY`/`DATA`/`END` per manifest row in order,
`FINISH` when done, `ABORT` instead when cancelled or a source could not be read (an entry already
open is closed with `END` first: `ArchiveFrameWriter.abort`'s own contract requires no entry open,
and the engine correctly rejects a violation as a protocol error otherwise, masking the real
failure). The drain reads the archive stream into the current staged part, rotating at each split
boundary into the next, lazily opened part with a running SHA-256. **No transparent retry** of the
create call itself, unlike `callStreaming`'s browsing retry, which would produce a second,
interleaved `writeArchive` transaction into the same demuxer. Every exit path closes all four
client-side pipe ends in the transaction's own `finally` the instant the transaction itself
finishes — not left for a later, single closing pass, which would leave this process's own copy of
the engine's output pipe open for the whole drain phase and the drain's real EOF would never arrive.

**The locale requirement.** The engine pins the calling thread's locale to `C.UTF-8` for the
duration of the call (bionic and modern glibc both provide it), asserting
`nl_langinfo(CODESET) == "UTF-8"` afterward and restoring the previous locale when it returns — this
is what makes any non-ASCII name at all actually write, for zip and pax alike, rather than failing
every time as it does in the unpinned C locale. On `ABORT`, a protocol violation, or any internal
error, the writer is explicitly poisoned (a `poisoned: Cell<bool>` makes the write callback fail on
its next call) before it is freed, which is what stops `archive_write_free`'s unconditional
close-on-drop from quietly emitting a well-formed truncated archive.

**Formats, levels and their memory ceiling** (`operations.CompressFormat`/`levelFor`): `zip`,
`tar.gz`, `tar.xz`, `tar.zst`, `tar.bz2`, `tar.lz4`, each with three distinct Fast/Normal/Best
levels. `tar.xz`'s range is capped at level 6 (≈93 MiB; level 9 would be ≈673 MiB) and `tar.zst`'s
Best is 19 (≈89.5 MiB; level 22 would be ≈833.6 MiB) against the `:decoders` 256 MB memory target;
`tar.xz` has no multi-threading offered (`xz:threads=1`), `tar.zst`'s MT support is compiled out.
7z creation is deferred to a later pack: an isolated process cannot create a temp file by path
anywhere the sandbox permits, a passed directory descriptor grants no create rights, and a `memfd`
breaches the memory target.

**Split semantics.** A split archive is raw `.001`/`.002`/… byte-sliced parts of whichever format
was chosen — libarchive has no multi-volume support of its own here — resolved and finalised **as
one unit**, last part first down to `.001` last, so a reader never sees a `.001` without every later
part also present; a later part failing to finalise rolls back whatever already did. **Fylz cannot
yet open the split sets it writes** — a read-side gap M3.3's own browsing does not close, named here
for the milestone that does.

**What device checks and the review queue cover:** `docs/agent/DEVICE_CHECKS.md` section 20 (the
write instance's own pid/RSS distinguishable from browsing, a 6 GB compress, split parts reassembled
with `cat`, cancel during a genuinely slow source read, `kill -9` mid-create restarting from scratch
bounded to 3 attempts, compressing from inside a browsed archive, the overlay's AES path still
working, SELinux on the two pipes); `docs/agent/REVIEW_QUEUE.md`'s M3.5 entry (the crypto stub, the
level ceilings with measured figures, the split read-side gap, and every other logged deviation from
the design).

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

### Root-level hardening for other apps (P1.9)

`queryRoots` reports `DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD` only when the caller shares
Fylz's own process uid (`Binder.getCallingUid() == Process.myUid()`, `rootFlagsFor(sameProcess)`).
That flag is what lets the system's `ACTION_OPEN_DOCUMENT_TREE` picker offer a *tree* grant over
one of these roots at all; omitting it for every other caller means another app can no longer walk
away with a persisted tree grant over a whole volume or `Download` through Fylz's own provider.

- **What is unaffected:** single-document access (`ACTION_OPEN_DOCUMENT`/`GET_CONTENT`, and any
  document URI another app already legitimately holds) still works exactly as before -- that only
  needs `queryChildDocuments`/`openDocument`, neither of which this touches.
- **Why this matters:** Android 11+ deliberately blocks the system's own `ExternalStorageProvider`
  from handing out whole-volume tree grants for exactly this reason (scoped storage's own intent);
  this closes the same hole for Fylz's own broad-access provider now that P1.8 lets Fylz itself
  reach every one of its roots (tabs and destinations alike) without ever needing the system picker
  for them, so nothing in this app's own flow depends on `FLAG_SUPPORTS_IS_CHILD` being visible to
  anyone else.
- **Whether to let a user re-widen this** (a setting exposing tree grants to other apps again) is
  decision D2 in the instruction files -- deliberately not decided here.

## Open-source hygiene

- No production keys, signing material, user file samples, model API keys, or private Fonebrew/Studio content in the repository.
- Generate SBOM and dependency/license reports in release CI.
- Pin GitHub Actions to trusted versions or commit SHAs as the project hardens.
- Enable dependency review, code scanning, secret scanning, and signed release artifacts.
- Use reproducible release instructions and publish checksums.
- Security-sensitive parsers and operation code require tests and review.
