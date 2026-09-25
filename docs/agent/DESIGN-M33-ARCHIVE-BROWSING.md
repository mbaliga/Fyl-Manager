# M3.3 design: archive browsing as folders

**Rev 1** (2026-09-25), for architecture review. Design for MASTER_PLAN M3.3, written from
`SURVEY-M33-ARCHIVE-BROWSING.md` (the read-only fact sheet; every "today" claim below is verified
there with file:line evidence) and on top of `DESIGN-M32-SEEKABLE-PFD.md` rev 2 (M3.2: the
application-scoped `ArchiveInspector`, `ArchiveSource`, `inspectArchive(pfd, limits, maxRows)` with at
most 500 rows, `DecoderCall`, the corrected `DecoderClient` timeout) and
`DESIGN-M31-PART3-EXTRACT-AND-POLICY.md` §6 (`inspect`, `extract()`, `EntryKind`, the link rule).
Paths are relative to the repository root. `UNVERIFIED` marks a claim the implementing agent must
confirm and record.

The plan's text, in full:

> **M3.3 Archive browsing as folders.** Opening an archive pushes a virtual location in the tab:
> breadcrumb `Downloads › photos.zip › 2024`. Preview, copy-out, share and drag-out work on entries.
> **Nested archives** (an archive inside an archive) open the same way, with bounded recursion depth 4
> and extraction to cache.

## 0. Scope

**In:** (a) an archive opened from a tab becomes a location on that tab's stack, its folders are
further locations, and the existing breadcrumb shows `Downloads / photos.zip / 2024`; (b) entries
list, sort, filter, select, preview (every renderer, including the two that need a seekable
descriptor), share, "Open with…" and copy out (clipboard copy → paste, Copy to…) exactly as files do;
(c) nested archives open the same way to depth 4, with the inner archive materialised to cache;
(d) the full listing of an archive leaves `:decoders` through a channel that is not bounded by the
Binder transaction buffer (the transport M3.2 deferred here); (e) entry bytes leave `:decoders` the
same way; (f) write actions are disabled inside an archive, through the registry, with the golden
test updated in lockstep; (g) preview of archive files widens from the ZIP family to every format
libarchive reads.

**Out (and where it goes):** extracting selections through the transfer queue with progress and
cancel (M3.4 — copy-out in M3.3 goes through the existing copy path and is correct but not
optimised: the entry is materialised to cache, then copied); creating, editing, testing,
passwords (M3.5–M3.9; encrypted entries list but cannot be opened, with a message naming M3.9);
filename charset detection (M3.7; lossy names show as M3.2 leaves them); **drag-out** — the plan
names it, but the app has no drag substrate at all today (no `dragAndDropSource`, `ClipData` or
`DragEvent` anywhere; the plan itself assigns drag and drop to M12.2). M3.3 gives every entry the
one thing drag needs, a grantable `content://` Uri; the gesture lands with M12.2. Logged as a
deviation, not silently dropped. Search inside an archive (the recursive search engine walks
`DocumentsContract` trees; the archive location is not a tree) — M8's query layer; disabled inside
archives meanwhile, logged. An idle-unbind policy for `:decoders` (M3.2 left it to M3.3): decided
in §2.9.

## 1. The constraints that decide the shape (short; survey §1–§10 has the evidence)

- A location **is** a `Uri` everywhere: `FolderLocation(uri, name)`, `FolderTab(treeUri, locations)`,
  `FileEntry.uri`, `selectedUris`, LazyColumn keys, the clipboard, `OperationItem.source`,
  `TransferWorker`'s `String[]`, `DocNode.load(resolver, uri)`, favourites, the session codec, every
  preview (`openInputStream`/`openFileDescriptor(entry.uri)`), share (`ACTION_SEND` + `EXTRA_STREAM`
  + `FLAG_GRANT_READ_URI_PERMISSION`) and Open with (`ACTION_VIEW` on `entry.uri`). Anything that is
  not a `content://` Uri a `ContentResolver` can service fails at each of those consumers.
- The app already chose this once: the File backend was built as a `DocumentsProvider` "because every
  service speaks `DocumentsContract` and `FileEntry` is keyed by `Uri`" (`FylzFilesDocumentsProvider.kt:29-41`).
- `FylzV1App.kt` is at its 2,287-line ratchet; every hook M3.3 needs (`openEntry`, the listing
  effect, the `BrowserState` snapshot, `ActionContext`, breadcrumb, share) lives there. New UI goes in
  new files; lines added there must be paid for by lines removed.
- `:decoders` has no storage and returns at most ~1 MB per transaction; it can read and write
  descriptors it is handed. `PdfRenderer` and `Typeface.Builder` need a **seekable** descriptor; a
  pipe cannot feed them.
- Nothing pops the location stack on system Back; Up is a registry action. The breadcrumb is a
  joined `Text` of the stack's names — pushing locations with the right names *is* the breadcrumb.
- `BrowserState` has no `location.kind`; MC.0's design deferred it to MC.2, and the golden test
  pins every `enabledWhen`.

## 2. Decisions

### 2.1 An archive is a `DocumentsProvider` tree: `ArchiveDocumentsProvider`

New `storage/ArchiveDocumentsProvider.kt`, authority `${applicationId}.archives`, declared
`exported="false"` with `grantUriPermissions="true"` (the `FileProvider` pattern: no other app can
query it, but a Uri handed out with `FLAG_GRANT_READ_URI_PERMISSION` — share, Open with — works).
Same-UID callers (the app, its `TransferWorker`) need no grant.

Every archive location and entry is a document of this provider, so **every consumer in §1 works
unchanged**: `FileEntry.uri` is a real content Uri; previews open it; share and Open with grant it;
`DocNode.load` queries it; `DocumentsTransfer.streamCopy` reads it; the session codec persists it;
LazyColumn keys and selection sets hold it. The tab's `treeUri` stays the outer tree (so restore's
persisted-grant filter and "one tab per tree" are untouched); only the location stack holds archive
Uris.

**Document id** = URL-safe base64 (no padding) of a compact JSON `{"v":1,"src":"<content Uri of
the archive file>","p":"<entry path, "" for the archive root>"}`. The `src` of a nested archive is
itself an archive-provider document Uri, so nesting is representable without a second scheme; depth
= the number of times `src` unwraps to another archive document, and **ids with depth > 4 are refused
at construction** (`IllegalArgumentException` → the open action shows "Archives nested deeper than 4
levels cannot be browsed"). Ids are opaque to callers; `ArchiveDocumentId.parse`/`encode` live in
one file with tests. Entry paths in ids are the engine's normalised paths (forward slashes, no
leading `/`, no `.`/`..` segments — the policy has already refused anything else before the archive
lists).

Provider surface (read-only):

| Method | Behaviour |
|---|---|
| `queryRoots` | empty cursor — archives are never SAF roots; they are reached only through a tab |
| `queryDocument(id)` | root: `DISPLAY_NAME` = archive file name, `MIME_TYPE_DIR`, `SIZE` = archive bytes, `FLAGS` = 0. Entry: name = last path segment, MIME = `MIME_TYPE_DIR` for `Directory` kind else `MimeTypeMap` by extension (`application/octet-stream` fallback), `SIZE` = uncompressed (null when unknown), `LAST_MODIFIED` = mtime × 1000 (null when unknown), `FLAGS` = 0 (nothing writable, no thumbnails in M3.3) |
| `queryChildDocuments(parentId, projection, sort)` | the catalog's children of `p` (§2.3), one row each, cursor order = archive order; `EXTRA_LOADING` when the catalog is still listing (§2.3); notification Uri set so a finished listing refreshes the cursor |
| `openDocument(id, mode, signal)` | `"r"` only (`UnsupportedOperationException` otherwise); returns a **regular-file** descriptor of the materialised entry (§2.4) |
| `isChildDocument(parent, child)` | path-prefix check within the same `src` |
| everything else | `UnsupportedOperationException` — M3.6 (edit in place) decides what becomes writable |

### 2.2 Bulk data leaves `:decoders` through pipes, drained in the UI process

Two new AIDL methods:

```aidl
// Both descriptors caller-owned. `archive` is seekable (ArchiveSource guarantees it); `sink` is the
// write end of a pipe the caller drains concurrently. The service writes the full entry table into
// `sink` in ArchiveListingCodec's format and returns the summary with `rows` empty.
ArchiveInspection listArchive(in ParcelFileDescriptor archive, in ArchiveLimits limits, in ParcelFileDescriptor sink);
// Streams one entry's bytes into `sink`. Returns bytes written, or a failure outcome in the result.
ArchiveExtractResult extractEntry(in ParcelFileDescriptor archive, String entryPath, in ArchiveLimits limits, in ParcelFileDescriptor sink);
```

**Why pipes, not a cache-file descriptor or `SharedMemory`:** a pipe passed over Binder is the one
channel an isolated process is unquestionably allowed to write (it is how the platform's own
isolated renderers move data); a write descriptor to an app-private file relies on an SELinux
`isolated_app` → `app_data_file` allowance that is plausible but `UNVERIFIED`, and a descriptor to a
foreign provider's file (M3.4's destinations: `fuse`/`sdcardfs`) would be a different question again.
One channel for listings, entry bytes and (M3.4) extraction keeps the SELinux surface at "pipes",
at the cost of one drain thread and one memcpy per stream in the UI process. `SharedMemory` needs
the size in advance (two passes) and fits bitmaps, not tables.

The drain is the liveness signal. `DecoderClient` gains
`suspend fun <T> callStreaming(sink: ParcelFileDescriptor /* read end */, drain: (InputStream) -> Unit,
inactivityMillis = STREAM_INACTIVITY_MILLIS /* 30_000 */, block): DecoderCall<T>`: the Binder
transaction runs in the client-owned job (M3.2's fix); the drain runs on `Dispatchers.IO` and stamps
`lastByteAt` on every read; a watchdog abandons the call (unbind → the platform reaps `:decoders` →
the pipe's write end closes → the drain sees EOF) when **no byte has arrived for
`inactivityMillis`**. A multi-GB `.tar.xz` whose header pass takes minutes therefore lists fine as
long as entries keep flowing; a genuinely hung process is still killed in 30 s. This replaces the
flat 30 s structure budget for streaming calls only; `inspectArchive` keeps M3.2's budget. Both
budgets are stated in `ARCHITECTURE.md` as the section 4.4 numbers *as applied*.

Rust: no new engine function for listing — `archive_inspect(fd, limits, max_rows =
limits.max_listing_entries)` already returns every row (up to the memory bound) into `:decoders`,
where Kotlin encodes them into the pipe. One new FFI function for entry bytes:

```rust
#[uniffi::export] pub fn archive_extract_entry(fd: i32, path: String, sink_fd: i32, limits: ArchiveLimitsRecord) -> Result<u64, ArchiveEngineError>;
```

over part 3's `extract(fd, &Selection::Paths(vec![path]), &limits.into(), &mut SingleSink { fd })`
where `SingleSink` is a `DestinationProvider` that hands out `sink_fd` once and returns `Ok(None)`
for anything else. A missing path is `ArchiveEngineError::Corrupt { detail: "no such entry" }`... no:
a **new** variant `NotFound { path }` (uniffi flat enum variant), mapped to `ArchiveExtractResult.outcome
= NOT_FOUND`. The runtime caps of part 3 apply (`max_file_bytes` stops a lying header).

`ArchiveListingCodec` (`archive/ArchiveListingCodec.kt`, Kotlin on both sides; rev 1 of M3.2 sketched
it, the review's points are applied): magic `FZL1`; **entry count up front** (`Int`, written before
the first record — Kotlin in `:decoders` has the full list); per record a tag byte `0x01`; path as
`Int`-length-prefixed UTF-8 bytes (no truncation — anything the policy allowed fits; the codec
never splits a code point because it never truncates); `kind: Byte`; `flags: Byte` (`ENCRYPTED_DATA`,
`ENCRYPTED_METADATA`, `SIZE_UNKNOWN`, `MTIME_UNKNOWN`, `NAME_LOSSY`, `HAS_LINK_TARGET`); `uncompressed:
Long`; `mtimeEpochSeconds: Long`; `mode: Int`; optional link target (same encoding as path). Trailer
tag `0xFF` + the same count. `write(entries, OutputStream)` flushes and **never closes** (the caller
owns the descriptor); `read(InputStream): ArchiveListing` validates magic and count eagerly, reads
every record, and checks the trailer — a short or corrupt stream is `ArchiveListingCorrupt` and the
catalog **rebuilds** rather than shows a partial tree.

### 2.3 `ArchiveCatalog`: one listing per archive, on disk and in memory

`archive/ArchiveCatalog.kt`, application-scoped next to `ArchiveInspector` (§2.9).

- **Key:** `sha256(src.toString() + "|" + size + "|" + lastModified)` where size/mtime come from
  `queryDocument(src)` (or, for a nested `src`, from the inner entry's listing row). A changed
  archive gets a new key; the old listing is garbage.
- **Listing file:** `cacheDir/archive-listings/<key>.fzl`, produced by
  `ArchiveSource.resolve(src).use { client.callStreaming(pipe, drain = copy into the file) {
  it.listArchive(pfd, limits, writeEnd) } }`. Written to `<key>.fzl.part` and renamed on success
  (the codec's eager validation on read is the second line of defence).
- **In-memory tree:** `ArchiveTree` built from the listing: entries in archive order plus a
  `HashMap<parentPath, IntArray of indices>`; implicit directories (a `dir/file` with no `dir/` row —
  common in ZIPs) are synthesised as `Directory` rows with unknown mtime. Bounded: an LRU of **2**
  trees in memory (`max_listing_entries` 200,000 × ~100 B ≈ 20 MB each at the bound; the survey's
  eighty-thousand-entry tarball is ~8 MB), everything else re-read from its `.fzl` on demand
  (~50 ms for 10 MB; measured and recorded).
- **Cache budget:** `archive-listings/` is capped at 64 MB, LRU by file mtime; swept together with
  M3.2's 24 h sweep of `archive-work/`, both in one `ArchiveCacheSweeper` that M3.2c introduces the
  first half of (§3 says how the two milestones share it).
- **API:** `suspend fun open(src: Uri): ArchiveHandle` (lists if needed; `ArchiveHandle.summary`,
  `children(path): List<ArchiveTreeEntry>`, `entry(path)`, `depth`); `fun peek(src): ArchiveHandle?`
  (no I/O, for the provider's synchronous `queryDocument`). The provider's `queryChildDocuments`
  uses `peek`; on a miss it returns an empty cursor with `EXTRA_LOADING = true`, kicks off `open` on
  the application scope, and calls `notifyChange` on the children Uri when done — the same
  loading→notify contract `DocumentRepository.listChildren`'s cursor already follows for slow
  providers (`UNVERIFIED`: that `listChildren` re-queries on the notification rather than treating
  the first empty cursor as final; if it does not, the repository gains that behaviour for the
  archive authority only, since a `Flow<ListingBatch>` can emit a second batch).
- **Failure surface:** `open` maps `DecoderCall.TimedOut` → `ArchiveCatalogException.TookTooLong`,
  `Failed` → `Unavailable`, a non-`OK` outcome → `Refused(outcome, message)`, `ArchiveSourceException`
  → `SourceFailed`, and the provider turns these into `FileNotFoundException(message)` from
  `queryChildDocuments`/`openDocument`, which `DocumentRepository.listChildren` surfaces as an empty
  `complete` batch plus a one-line `Log.w` (today's behaviour for a vanished folder) — the location
  stays on the stack and Up still works. The overlay/preview show the message (§2.7).

### 2.4 Entry bytes: materialise to cache, return a regular-file descriptor

`openDocument(entryId, "r")` must return a descriptor synchronously, and two previews need it
seekable. So `ArchiveEntryCache` (`archive/ArchiveEntryCache.kt`, application-scoped):

- **Path:** `cacheDir/archive-entries/<listingKey>/<sha256(entry path)>` (`.part` while writing).
- **Fill:** `ArchiveSource.resolve(src).use { client.callStreaming(pipe, drain = copy into the .part
  file) { it.extractEntry(pfd, path, limits, writeEnd) } }`; on `Ok(result)` with `outcome == OK`
  and `bytes == file length` → rename; anything else → delete `.part` and throw
  `FileNotFoundException(message)`. Concurrent opens of the same entry share one fill (a
  `Mutex`-guarded `Deferred` per key).
- **Bounds:** an entry larger than `min(limits.maxFileBytes /* 1 GiB */, ENTRY_CACHE_BUDGET /* 512
  MiB */)` or larger than `ArchiveSpacePolicy` says fits in the cache volume with headroom is refused
  before any byte moves: `FileNotFoundException("This entry is too large to open in place. Extract
  it instead.")` (M3.4's extract path has no such cap). The directory is LRU-evicted to
  `ENTRY_CACHE_BUDGET` by mtime, never while a descriptor was handed out in the last 60 s (`UNVERIFIED`
  whether a simpler "never evict entries younger than 60 s" suffices; the agent measures).
- **Return:** `ParcelFileDescriptor.open(file, MODE_READ_ONLY)` — seekable; `PdfRenderer`,
  `Typeface.Builder`, ExoPlayer and Coil all read it as an ordinary document.
- **Encrypted entries** (`ENCRYPTED_DATA` flag): refused before extraction with
  `FileNotFoundException("This entry is password protected. Opening protected entries arrives with
  the password prompt (M3.9).")`.

`openDocument` runs on a Binder thread of the app process (or in-process through
`localContentProvider`), never the main thread; it blocks for the fill, which is what providers that
fetch on demand do. The `CancellationSignal` is honoured: it abandons the streaming call (unbind)
and deletes the `.part`.

**Copy-out** (Copy → Paste into a real folder; Copy to…) needs nothing new: `FileOperationService.copy`
→ `DocNode.load(entryUri)` (the provider's `queryDocument` supplies name/MIME/size/flags) →
`TransferEngines.forPair` picks `DocumentsTransfer` (`fileFor` is null for the archive authority) →
`streamCopy(openInputStream(entryUri))` → the materialised file. A directory entry copies
recursively through `DocNode.children` → `queryChildDocuments`. Verification (SHA-256 on the
`VerifySettings` path) re-reads the cache file. Cut/Move are disabled (§2.6). M3.4 replaces this
double write with direct extraction through the queue.

**Nested archives:** `ArchiveSource.resolve(innerEntryUri)` → `openFileDescriptor` → the provider
materialises the inner archive (§2.4 bounds apply: an inner archive over 512 MiB says "extract it
first") → a regular file → `Direct` → `:decoders` lists it. Depth is enforced at id construction
(§2.1); the cache key chain means a changed outer archive invalidates the inner listing too.

### 2.5 Opening and navigating

- `openEntry(entry)` (`FylzV1App.kt:919-930`): the push branch becomes `if (entry.isDirectory ||
  entry.isBrowsableArchive)` where `FileEntry.isBrowsableArchive` (new extension in `model/`) is
  `kind == ARCHIVE && FileFormatRegistry.archives.contains(ext) && ext !in NOT_YET_BROWSABLE` with
  `NOT_YET_BROWSABLE = setOf("rar", "cbr")` until the RAR fixture corpus exists (M3 acceptance;
  libarchive does read RAR4/5 unencrypted — the exclusion is about untested formats, logged). The
  pushed `FolderLocation` is `(ArchiveDocumentsProvider.rootUri(entry.uri), entry.name)`. Inside an
  archive, a `Directory` entry pushes `(entry.uri /* already an archive document */, entry.name)`.
  Net line change in `FylzV1App.kt`: zero (the condition changes on its line).
- **Single tap opens the archive as a folder** (this is what the plan's "opening an archive pushes a
  virtual location" says). The archive's *inspection* view (M3.2's widened `ArchivePreview`) is still
  reachable: select the archive and open the preview pane — `focusedEntry` is set by selection, not
  only by tap. `fylz.open-with` on an archive still offers the external chooser.
- `DocumentRepository.listChildren(treeUri, folderUri)`: when `folderUri.authority ==
  ArchiveDocumentsProvider.AUTHORITY` it builds `DocumentsContract.buildChildDocumentsUri(authority,
  docId)` (the non-tree form; same UID, no grant needed) instead of the tree form; the rest of the
  pipeline (projection, batches of 500/5,000, `FileType.classify`) is unchanged. `FileEntry.kind`
  for entries therefore comes from the same classifier as files; `FileEntry.flags` = 0.
- Breadcrumb: unchanged code, now reads `Downloads / photos.zip / 2024` because the names pushed
  are the archive's and the folder's. (The plan writes `›`; the app writes `/`. Not changed here.)
- Up (`fylz.navigate.up`) pops as today. Leaving an archive is popping past its root.
- Location-keyed effects skip archive locations: legacy-bin lookup and `FileObserver` already key on
  the *tree* Uri's authority (no change); `currentFolderIsFavourite` compares Uris (an archive Uri is
  never a favourite; `fylz.favourite.toggle` is disabled inside archives, §2.6); recursive search
  (`searchRecursive`) is forced off while the current location is an archive (one condition in the
  search effect, paid for by §2.8's extraction).
- Session restore: `SessionCodec` round-trips any Uri string, so archive locations persist and
  restore; the catalog re-lists lazily on first query. A vanished source → §2.3's failure surface.

### 2.6 Actions inside an archive: read-only, through the registry

`BrowserState` gains `locationKind: LocationKind = LocationKind.FOLDER` (enum `FOLDER`, `ARCHIVE`;
defaulted, so `BrowserStateFixtures` stay byte-identical — MC.0's rule). It is `ARCHIVE` when
`activeTab.current.uri.authority == ArchiveDocumentsProvider.AUTHORITY`. This is the
`location.kind` predicate the Addendum's MC.2 lists; M3.3 introduces the field, MC.2 exposes it to
customisation.

`enabledWhen` changes (all in `BuiltInActions.kt`, each `&& it.locationKind != ARCHIVE` unless said):

| Action | Inside an archive | Why |
|---|---|---|
| `fylz.cut`, `fylz.move-to`, `fylz.recycle`, `fylz.rename`, `fylz.rename.batch`, `fylz.tags` | disabled | they write the source; the archive is read-only until M3.6 |
| `fylz.paste`, `fylz.new-folder`, `fylz.new-file`, `fylz.scan-to-pdf` | disabled | they write into the current location |
| `fylz.favourite.toggle`, `fylz.ai.organize` | disabled | favourites are tree folders; organise moves files |
| `fylz.copy`, `fylz.copy-to`, `fylz.share`, `fylz.compress`, `fylz.find-duplicates`, `fylz.pdf.tools`, `fylz.select.*`, `fylz.open`, `fylz.open-with`, sort/view, `fylz.navigate.up`, `fylz.refresh` | unchanged | read-only or navigation; compress/pdf.tools read entries through the provider |
| `fylz.extract` | unchanged (ZIP family, one selected archive file) | inside an archive it applies to a nested archive file entry; "Extract selected entries" is M3.4 |

`ActionResolverGoldenTest`: `LegacyOracle` gains the read-only rule as a named function, every
`expected*` row that changes takes it, and `BrowserStateFixtures` gains three fixtures
(`archiveRootNoSelection`, `archiveFolderWithSelection`, `archiveWithClipboard`) — lockstep, as MC.0
requires. `NoHardCodedMenusTest` unchanged. `ShortcutTableTest`'s "no problems" assertion unchanged.

### 2.7 Preview and the inspection view

- `PreviewPane.kt:156`'s gate widens from `ZIP_CONTAINER_EXTENSIONS` to `FileFormatRegistry.archives`
  (minus `NOT_YET_BROWSABLE`); `ZipArchivePreview` is renamed `ArchivePreview` (M3.2 already made it
  format-agnostic through the inspector). `SEMANTIC_ZIP_DOCUMENTS` (OOXML/ODF/EPUB) keep
  `ZipDocumentPreview` — they are documents, not archives, in the plan's own table ("as containers").
- Previewing an **entry** needs no new renderer: the entry's Uri is a document, so `PreviewPane`
  dispatches on `FileFormatRegistry.describe(name, mime, kind)` as for any file and the renderer
  opens the Uri → §2.4 materialises. Text previews read through `repository.readText(uri)` (512 KiB
  bound, unchanged). A refused open (§2.4) surfaces as the existing `UniversalInspectorPreview`
  fallback with the `FileNotFoundException` message.
- The catalog's failure messages (§2.3) reach the user through the listing effect's empty batch plus
  a snackbar from the existing `report`/error channel (`UNVERIFIED`: which channel `FylzV1App` uses
  for listing failures today; if none, the archive root shows an empty folder and the message is
  logged — recorded as a gap for M3.4's progress UI to close).

### 2.8 Paying for the `FylzV1App.kt` ratchet

Lines M3.3 adds to `FylzV1App.kt`: one condition in the search effect (§2.5), one named argument in
the `BrowserState` snapshot (§2.6). Lines it removes: the `BrowserState` construction
(`FylzV1App.kt:1131-1157`, ~27 lines) moves to `actions/BrowserStateBuilder.kt` as
`fun buildBrowserState(...)` taking the same inputs; `FylzV1App` keeps a one-line call.
`FylzV1AppSizeTest`'s ratchet is lowered to the new count (it never goes up).

### 2.9 Decoder-process lifetime (the M3.2 hand-off)

`DecoderClient` gains an **idle unbind after 60 s** without a call in flight: a single
`Job` on the client's scope, restarted on every call's completion, that calls `dropConnection()`
(the platform then reaps `:decoders`; the next call rebinds, paying the ~cold-start cost survey risk
7 describes once per idle gap rather than per call). Tested through the existing seam with an
injected idle time. Recorded in `ARCHITECTURE.md`. M3.2's REVIEW_QUEUE item 12 is closed by this.

### 2.10 Fixtures and tests

Fixtures: M3.2's `core/fixtures/archives/*` are reused. M3.3 adds to `make_archive_fixtures.py`:
`nested-depth-4.zip` (a zip in a zip in a zip in a zip, each one file), `nested-depth-5.zip`
(refused), `implicit-dirs.zip` (`a/b/c.txt` with no directory rows), `sample-entries.zip`
(a PDF, a TTF, a PNG, a `.txt`, a 3 MB zero-filled `.bin` — for the seekable-preview and cache
tests), `mixed-links.tar` (a relative in-tree symlink and a hardlink — listed, not openable). Kotlin
tests do not load native code: the `:decoders` side is faked by an `IDecoderService.Stub` that writes
a known listing / known bytes into the sink (Robolectric pipes are file-backed and sequential
write-then-read works; `UNVERIFIED` that a concurrent drain sees bytes before the writer closes — if
not, the fake writes fully before returning, which is also how the real service behaves for small
inputs).

Kotlin (Robolectric):
- `ArchiveDocumentIdTest`: encode/parse round trip, depth counting, depth-5 refusal, hostile ids
  (not base64, wrong version, `..` paths) refused.
- `ArchiveListingCodecTest`: round trip with every flag, lossy names, link targets, 200,000 entries
  (time recorded), truncated stream → `ArchiveListingCorrupt`, `write` does not close the stream.
- `ArchiveCatalogTest`: listing produced through a fake stub and the pipe drain into `.fzl`; key
  changes with size/mtime; second `open` reads the file without calling the stub; corrupt file →
  rebuilt; LRU of 2 trees; 64 MB cap eviction; implicit directories synthesised; `peek` semantics.
- `ArchiveEntryCacheTest`: fill through a fake stub, seekable descriptor returned (read at an
  offset), concurrent opens share one fill, oversize refused before any byte, encrypted refused,
  `.part` cleaned on failure, LRU eviction with the 60 s guard, cancellation deletes `.part`.
- `ArchiveDocumentsProviderTest` (registered via the existing `ProviderTestSupport`): `queryDocument`
  root and entries (columns), `queryChildDocuments` with a warm catalog and the `EXTRA_LOADING`
  path with `notifyChange` (`UNVERIFIED` that Robolectric delivers the notification; else the test
  asserts the second query is warm), `openDocument` for `"r"` and refusal for `"w"`,
  `isChildDocument`.
- `DocumentRepositoryArchiveListingTest`: `listChildren` over the archive authority yields
  `FileEntry`s in archive order with `kind` classified and `isDirectory` for directory rows; a
  failing catalog → empty complete batch.
- **`ArchiveCopyOutTest`** (the end-to-end proof): `FileOperationService.copy(listOf(entryUri,
  dirEntryUri), realFolderTree)` through the real `DocumentsTransfer` path against the archive
  provider with a fake stub → files byte-identical, directory recursed, journal entries as for any
  copy.
- `DecoderClientTest`: `callStreaming` — a stub writing a byte every 100 ms for 2 s under a 500 ms
  inactivity budget succeeds; a stub silent for longer than the budget → `TimedOut`, unbind called,
  drain sees EOF; idle unbind fires after the injected idle and the next call rebinds.
- `BrowserStateBuilderTest` (moved logic, same outputs on the fixtures), `ActionResolverGoldenTest`
  with the three archive fixtures, `SessionCodecTest` round-trips an archive location,
  `FylzV1AppSizeTest` at the lowered ratchet.

Rust: `archive_extract_entry` round-trips bytes into a temp-file fd; a missing path → `NotFound`;
`max_file_bytes` smaller than the entry → `LimitExceeded` and the partial sink is the caller's to
delete (as part 3 says).

### 2.11 Device checks (`DEVICE_CHECKS.md` §18, "M3.3 — archive browsing")

1. Tap `photos.zip` in Downloads: the list shows its entries; the breadcrumb reads `Downloads /
   photos.zip`; enter `2024`; Up twice returns to Downloads. Repeat for a `.7z`, an `.iso`, a
   `.tar.gz`.
2. Preview a PDF and a font inside the zip (seekable path); play a short video entry; view a PNG.
3. Share a text entry to another app; "Open with…" a PDF entry in an external viewer (grant on the
   non-exported provider).
4. Copy a folder out of the archive into Downloads: files identical (`sha256sum` on both sides).
5. `nested-depth-4.zip`: open to the innermost file; `nested-depth-5.zip`: the fifth level is refused
   with the message.
6. A tarball with 80,000 entries: listing completes; note the time and `:decoders` peak RSS
   (`adb shell dumpsys meminfo <pkg>:decoders`); the tab stays responsive during listing.
7. `adb logcat | grep avc`: no SELinux denials for `isolated_app` on pipe writes.
8. Kill `:decoders` during a listing: the folder shows empty with the message; re-enter the archive:
   it lists. Wait 60 s idle: `:decoders` is gone from `ps`; the next open brings it back.
9. Rotate and background the app inside an archive (Don't keep activities on): the location restores.
10. Cache: `cache/archive-listings` and `cache/archive-entries` stay under their budgets after
    opening twenty archives and thirty entries.

## 3. Sequencing and gates

Four commits, each green on the full gate (`./gradlew --no-daemon :app:testDebugUnitTest
:app:lintDebug :app:assembleDebug`; the `core` gate incl. `cargo +nightly fuzz build`; the three-ABI
`cargo ndk` build of `fylz-ffi-android`):

- **M3.3a (transport):** `ArchiveListingCodec`, `DecoderClient.callStreaming` + idle unbind, AIDL
  `listArchive`/`extractEntry` + `ArchiveExtractResult` Parcelable, `DecoderService` mapping through
  the `engine` seam, Rust `archive_extract_entry` (+ `NotFound`), `ArchiveCatalog`,
  `ArchiveEntryCache`, `ArchiveCacheSweeper` (absorbing M3.2c's sweep), their tests. No UI change.
- **M3.3b (provider):** `ArchiveDocumentId`, `ArchiveDocumentsProvider` + manifest entry,
  `DocumentRepository` archive listing, `ArchiveCopyOutTest`, provider/repository tests.
- **M3.3c (UI and registry):** `isBrowsableArchive`, `openEntry`, `BrowserStateBuilder` extraction +
  `locationKind` + `enabledWhen` changes + golden fixtures, search-off-in-archive, `ArchivePreview`
  rename and the widened preview gate, the ratchet lowered.
- **M3.3d (docs):** `ARCHITECTURE.md` (a new "Archives as documents" section: provider, caches,
  pipes, budgets), `DEVICE_CHECKS.md` §18, `REVIEW_QUEUE.md` entry, PROGRESS row, PR #19 description.
  (Folded into 3c if small.)

`REVIEW_QUEUE.md` entry for M3.3 (log-and-continue):
1. Drag-out deferred to M12.2 (§0) — the plan lists it under M3.3.
2. Search inside archives disabled until M8 (§0, §2.5).
3. RAR/CBR not browsable until the RAR fixtures exist (§2.5).
4. Copy-out is a double write (materialise, then copy) until M3.4 (§2.4).
5. Entries over 512 MiB cannot be opened in place (§2.4); M3.4's extract has no such cap.
6. Encrypted entries list but do not open until M3.9 (§2.4).
7. Pipes as the one bulk channel out of `:decoders` (§2.2); the SELinux question for file
   descriptors is left unanswered rather than answered on a device.
8. Inactivity-based liveness for streaming calls instead of §4.4's flat 30 s (§2.2).
9. Idle unbind after 60 s (§2.9) — closes M3.2's item 12.
10. The breadcrumb separator stays `/` (the plan writes `›`).
11. A vanished archive behind a restored location shows an empty folder plus a log line (§2.3, §2.7).

## 4. Risks

- **`EXTRA_LOADING` re-query** (§2.3 `UNVERIFIED`): if `DocumentRepository.listChildren` does not
  re-query on `notifyChange`, the first entry into a cold archive shows empty until refresh. The
  fallback is stated (the repository re-queries for the archive authority).
- **Materialisation latency:** opening a 300 MB video entry blocks its `openDocument` for the
  extraction time; ExoPlayer shows a spinner. Acceptable for M3.3; M3.4 can add a progress surface.
- **Provider on the main thread:** `localContentProvider` calls from Compose code would run
  `openDocument` on the caller's thread; every existing caller opens documents off the main thread
  already (previews use `produceState`/IO). The provider asserts `Looper.myLooper() !=
  Looper.getMainLooper()` in debug builds to catch a regression.
- **Memory in the UI process:** two trees at the 200,000-entry bound ≈ 40 MB. If the device check
  shows pressure, the LRU drops to 1 and the on-disk read path carries the rest.
- **Robolectric pipes** are file-backed: the drain tests prove the codec and the file, not pipe
  semantics; §18 items 6–8 are the real-device coverage.
