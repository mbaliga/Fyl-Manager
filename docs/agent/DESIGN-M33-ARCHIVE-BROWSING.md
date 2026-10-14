# M3.3 design: archive browsing as folders

**Rev 2** (2026-09-25), after the architecture review of rev 1 (commit `14b6dee`); §7 lists the
findings and their disposition. Design for MASTER_PLAN M3.3, written from
`SURVEY-M33-ARCHIVE-BROWSING.md` (the read-only fact sheet; "today" claims are verified there with
file:line evidence) on top of `DESIGN-M32-SEEKABLE-PFD.md` rev 2 (M3.2: the application-scoped
`ArchiveInspector`, `ArchiveSource`, `inspectArchive(pfd, limits, maxRows)`, `DecoderCall`, the
corrected `DecoderClient` timeout) and `DESIGN-M31-PART3-EXTRACT-AND-POLICY.md` §6 as landed in
`a849282` (`inspect`, `extract`, `EntryKind`, the link rule). Paths are relative to the repository
root. `UNVERIFIED` marks a claim the implementing agent must confirm and record.

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
(c) nested archives open the same way to depth 4 (four archive levels), the inner archive
materialised to cache; (d) the full listing of an archive leaves `:decoders` through a channel not
bounded by the Binder transaction buffer (the transport M3.2 deferred here), and entry bytes leave
the same way; (e) write actions are disabled inside an archive through the registry, with the golden
test updated in lockstep; (f) preview of archive files widens to every format libarchive reads here.

**Out (and where it goes):** extracting selections through the transfer queue with progress and
cancel, and bulk copy-out in one pass (M3.4 — M3.3's copy-out is correct but per entry, §2.5);
creating, editing, testing, passwords (M3.5–M3.9; encrypted entries list but do not open, with a
message naming M3.9); filename charset detection (M3.7); thumbnails inside archives (icons instead,
§2.8; logged); **drag-out** — the plan names it, but there is no drag substrate: no
`dragAndDropSource`, `ClipData` or `DragEvent` anywhere, `ITEM_LONG_PRESS` is already bound to
`fylz.select.toggle`, `GestureId` has no drag, and the plan itself assigns drag and drop to M12.2.
M3.3 gives every entry the one thing drag needs, a grantable `content://` Uri; the gesture lands
with M12.2 (logged). Search inside an archive (the recursive search engine walks `DocumentsContract`
trees; an archive location is not a tree) — M8's query layer; forced off inside archives meanwhile
(logged). Single-file compressed streams (`notes.txt.gz`) are not "browsable": libarchive's `raw`
format is not in `support_format_all`, so they keep today's preview (logged against the plan's
table). RAR/CBR wait for the RAR fixture corpus (logged).

## 1. The constraints that decide the shape (short; survey §1–§10 has the evidence)

- A location **is** a `Uri` everywhere: `FolderLocation(uri, name)`, `FolderTab(treeUri, locations)`,
  `FileEntry.uri`, `selectedUris`, LazyColumn keys, the clipboard, `OperationItem.source`,
  `TransferWorker`'s `String[]`, `DocNode.load(resolver, uri)`, favourites, the session codec, every
  preview, share and Open with. The app already chose a `DocumentsProvider` for the File backend for
  exactly this reason (`FylzFilesDocumentsProvider.kt:29-41`).
- A `DocumentsProvider` **must** be exported, grant Uri permissions, and be protected by
  `MANAGE_DOCUMENTS` — `DocumentsProvider.attachInfo` throws otherwise, at process start
  (`ProviderTestSupport.kt:11-13` records the same). "Non-exported provider" is not an option.
- `DocumentsContract`'s tree-form helpers (`buildChildDocumentsUriUsingTree`,
  `buildDocumentUriUsingTree`, `getTreeDocumentId`) throw on a non-tree Uri; `DocNode.children`
  (`DocNode.kt:36,43`) and `DocumentRepository.listChildren`'s row Uris (`DocumentRepository.kt:129`)
  use them today.
- `listChildren` runs one query, closes the cursor, emits `complete = true`; it reads no extras and
  registers no observer (`DocumentRepository.kt:116-153`). `DocumentsProvider.query` swallows a
  `FileNotFoundException` into a null cursor. The listing effect toasts any exception the flow throws
  (`FylzV1App.kt:862-865`).
- `FylzV1App.kt` is at its 2,287-line ratchet. New UI goes in new files; lines added there are paid
  for by lines removed.
- `:decoders` has no storage, returns at most ~1 MB per transaction, and can read and write
  descriptors it is handed. `PdfRenderer`, `Typeface.Builder` and ExoPlayer need a **seekable**
  descriptor; a pipe (`statSize == -1`) serves none of them.
- Part 3's `extract(fd, Selection::Paths, …)` matches through `normalized_path_key` (lower-cased,
  trailing `/` trimmed), hands the descriptor to the **first** match, walks every header to the end,
  and reports a missing path in `ExtractReport.missing`, not as an error (`lib.rs:931-983`,
  `policy.rs:221-223`). Real archives carry `./a`, `dir/`, `/abs`, `a//b`, backslashes (ZIP),
  duplicate members (`tar -r`) and case collisions; `inspect` returns libarchive's raw pathname.
- LazyColumn rows are keyed by `entry.uri.toString()` (`FylzV1App.kt:1868,1874`); two entries with
  one Uri crash the list.
- Robolectric pipes are file-backed: `read()` returns -1 before the first write and never signals
  close (`FaultyDocumentsProvider.kt:46-59,174-181`).

## 2. Decisions

### 2.1 An archive is a `DocumentsProvider` tree: `ArchiveDocumentsProvider`

New `storage/ArchiveDocumentsProvider.kt`, declared exactly like the File provider
(`AndroidManifest.xml:68-73`): `exported="true"`, `grantUriPermissions="true"`,
`permission="android.permission.MANAGE_DOCUMENTS"`, and **no** `DOCUMENTS_PROVIDER` intent filter, so
DocumentsUI never lists it. Authority hard-coded as `io.github.mbaliga.fylz.archives` (as
`FylzFilesDocumentsProvider.kt:422` does; never `${applicationId}`). Other apps cannot query it
(signature permission); a Uri handed out with `FLAG_GRANT_READ_URI_PERMISSION` — share, Open with —
works because Uri grants bypass the provider permission. Same-UID callers need no grant.

Every archive location and entry is a document of this provider, so every consumer in §1 works
unchanged. The tab's `treeUri` stays the outer tree (restore's persisted-grant filter and "one tab
per tree" untouched); only the location stack holds archive Uris.

**Document id** = URL-safe base64 (no padding) of one **flat** JSON:

```json
{"v":1,"src":"content://…/document/photos.zip","n":["inner1.zip","d/inner2.zip"],"o":1234,"p":"2024/a.jpg"}
```

`src` is the outermost *file* Uri (never an archive-provider Uri); `n` is the chain of nested
archive entry paths from `src` inward (empty for a top-level archive); `o` is the **ordinal** of the
entry among the archive's headers (-1 for the archive root and for implicit directories, §2.3);
`p` is the normalised entry path (`""` for the root). Growth is linear in depth, nothing parses ids
recursively, and depth = `n.size + 1` archive levels, **at most 4** (`nested-depth-4.zip` opens to its
innermost file; the fifth level is refused at id construction with "Archives nested deeper than 4
levels cannot be browsed"). `ArchiveDocumentId.parse/encode` live in one file with tests; hostile
ids (bad base64, wrong version, unnormalised `p`, `o` out of range) are refused. An id identifies
an entry by **position**, so duplicate paths and case collisions are distinct documents and
`extract_entry_at` (§2.2) fetches exactly the header the user clicked.

Every entry Uri is the non-tree form `content://io.github.mbaliga.fylz.archives/document/<id>`;
`DocNode.children` and `listChildren`'s row builder gain the one branch §2.5 describes so non-tree
Uris work through the whole engine. Uri length is ~250 characters at depth 1 and grows ~50 per
level, so an 80,000-entry folder's `FileEntry`s hold ~20 MB of Uri strings — inherent to Uri-keyed
entries, recorded.

Provider surface (read-only; **every method blocks on the catalog**, §2.3 — there is no loading
protocol):

| Method | Behaviour |
|---|---|
| `queryRoots` | empty cursor — archives are never SAF roots |
| `queryDocument(id)` | root: `DISPLAY_NAME` = archive file name, `MIME_TYPE_DIR`, `SIZE` = archive bytes, `FLAGS = 0`. Entry: name = last path segment, MIME = `MIME_TYPE_DIR` for directories else `MimeTypeMap` by extension (`application/octet-stream` fallback), `SIZE` = uncompressed (null when unknown), `LAST_MODIFIED` = mtime × 1000 (null when unknown), `FLAGS = 0` |
| `queryChildDocuments(parentId, …)` | the tree's children of `p`, cursor order = archive order, one row each; on a catalog failure the cursor carries `DocumentsContract.EXTRA_ERROR` = the message (§2.3) and no rows |
| `openDocument(id, mode, signal)` | `"r"` only; a **regular-file** descriptor of the materialised entry (§2.4); refusals are `FileNotFoundException(message)` |
| `isChildDocument(parent, child)` | same `src`/`n` and `p` prefix |
| everything else | `UnsupportedOperationException` — M3.6 decides what becomes writable |

The provider reaches its `ArchiveCatalog` through `FylzApplication` with an `@VisibleForTesting`
override (the `volumeOverride` pattern, `FylzFilesDocumentsProvider.kt:56-57`), and a debug-only
"not on the main looper" check that is injectable so Robolectric (main looper) can run it. In-process
`ContentResolver` calls run the provider on the caller's thread through the local transport; every
existing caller (`listChildren` on IO, `FileOperationService` off-main, previews via `produceState`
+ IO, `TransferWorker`) is already off the main thread.

### 2.2 Bulk data leaves `:decoders` through pipes owned by the client

Two AIDL methods:

```aidl
// `archive` is caller-owned and seekable (ArchiveSource guarantees it). `sink` is the write end of a
// pipe the client created; the service takes ownership of its dup and closes it when done. The full
// entry table is written into `sink` in the ArchiveListingCodec format; the summary comes back with
// `rows` empty (and `partial = true` if the pass stopped on a damaged header after some entries).
ArchiveInspection listArchive(in ParcelFileDescriptor archive, in ArchiveLimits limits, in ParcelFileDescriptor sink);
// Streams the bytes of the entry at header `ordinal` (whose raw path must equal `expectedPath`
// byte for byte) into `sink`. Stops reading the archive as soon as the entry is written.
ArchiveExtractResult extractEntry(in ParcelFileDescriptor archive, int ordinal, String expectedPath, in ArchiveLimits limits, in ParcelFileDescriptor sink);
```

**Why pipes:** a pipe passed over Binder is the one channel an isolated process is certainly allowed
to write. Reading *and* writing an app-private file through a passed descriptor both depend on the
same SELinux allowance for `isolated_app` on `app_data_file` — M3.2's staged path and nested archives
already rely on the read half — and that allowance is `UNVERIFIED` until §18 item 7 runs on a device.
Pipes cost a drain thread and a copy per stream; if §18 confirms the file rule, a cache-file sink
for entry bytes is the recorded upgrade (§6), not a design change. `SharedMemory` needs the size in
advance and fits bitmaps.

**Rust does the listing write** (no Kotlin re-encoding, no per-record objects in `:decoders`):

```rust
#[uniffi::export] pub fn archive_list_into(fd: i32, limits: ArchiveLimitsRecord, sink_fd: i32) -> Result<ArchiveInspectionRecord, ArchiveEngineError>;
#[uniffi::export] pub fn archive_extract_entry_at(fd: i32, ordinal: u32, expected_path: String, limits: ArchiveLimitsRecord, sink_fd: i32) -> Result<u64, ArchiveEngineError>;
```

`archive_list_into` is `inspect`'s header pass with a codec writer attached: each header is encoded
and written to `sink_fd` **as it is read** (bytes flow during the pass, which is the liveness
signal §2.2's client needs) while the `Vec<EntryMetadata>` is kept only for the policy decision (the
~30 MB M3.2 measured, and nothing else — no `RustBuffer`, no Kotlin records). A damaged header after
≥ 1 entry writes the trailer with `partial = 1` and returns `Ok` with `outcome = CORRUPT`-equivalent
fields (`partial: true`, `message`) so the catalog can show what was readable (M3.2 §4's hand-off).
`archive_extract_entry_at` (new in `fylz-archive`: `extract_entry_at(fd, ordinal, expected_path,
limits, dest)`) walks to header `ordinal` (`archive_read_next_header` skips bodies; on a seekable ZIP
this is a central-directory walk), compares the raw pathname byte-exact, streams that one entry
under part 3's runtime caps, and **returns without reading further**. Mismatch → `ArchiveEngineError::
NotFound { ordinal, path }` (a field-carrying variant of M3.2's error enum; uniffi `flat_error`
would drop fields, so it is not flat). Part 3's `extract(Selection::Paths)` is not used for this
(case-folded first match, full pass).

`ArchiveListingCodec` (writer in Rust `fylz-archive/src/listing.rs`, reader in Kotlin
`archive/ArchiveListingCodec.kt`, with a **cross-language golden-bytes test**: a committed `.fzl`
under `app/src/test/resources/fixtures/archives/` produced by the Rust writer from `sample-cd.zip`
and asserted byte-identical by a Rust test, then decoded by the Kotlin test): magic `FZL1`; per
record a tag `0x01`, `ordinal: u32`, raw path (`u32` length + bytes, **as libarchive gave it**; the
tree normalises on read, §2.3), `kind: u8`, `flags: u8` (`ENCRYPTED_DATA`, `ENCRYPTED_METADATA`,
`SIZE_UNKNOWN`, `MTIME_UNKNOWN`, `NAME_LOSSY`, `HAS_LINK_TARGET`), `uncompressed: u64`,
`mtime: i64`, `mode: u32`, optional link target (same encoding); trailer tag `0xFF`, `count: u32`,
`partial: u8`. All integers little-endian. The Kotlin reader treats the file as **untrusted** (a
compromised `:decoders` wrote it): count ≤ `maxListingEntries`, each path and link target ≤ 64 KiB,
cumulative bytes ≤ file length, tree depth ≤ 1,024 segments; a violation or a missing trailer is
`ArchiveListingCorrupt`. Hostile-listing tests feed each violation.

`DecoderClient.callStreaming`:

```kotlin
suspend fun <T> callStreaming(
    archive: ParcelFileDescriptor, inactivityMillis: Long = STREAM_INACTIVITY_MILLIS /* 30_000 */,
    drain: suspend (InputStream) -> Unit,
    block: (IDecoderService, writeEnd: ParcelFileDescriptor) -> T,
): DecoderCall<T>
```

- The client **creates the pipe** (`ParcelFileDescriptor.createPipe()`), hands `(service, writeEnd)`
  to `block` (which runs in the client-owned transaction job, M3.2's fix), and closes **its own**
  write end in that job's `finally` and on abandon — a real pipe reports EOF only when every write
  end is closed, and the service closes its dup with `use`. The drain runs on the client's streaming
  executor and reads until EOF; on cancel or abandon the client closes the read end
  (`FileInputStream.close` wakes a blocked `read`).
- **Drain rule** (Robolectric's file-backed pipes return -1 before the first write): EOF is final
  only once the client has closed its write end *and* the transaction has returned or been
  abandoned; an earlier -1 sleeps 10 ms and retries. Real pipes never take the retry path.
- **Liveness** = sink bytes arriving **or** the archive descriptor's shared file offset advancing:
  the watchdog polls `Os.lseek(archive.fileDescriptor, 0, SEEK_CUR)` on the UI process's own copy
  every second (the offset is shared with the Binder dup — DESIGN-M32 §1); it abandons the call when
  neither has changed for `inactivityMillis`. A multi-GB `.tar.xz` header pass, a solid-7z lead-in,
  a skip over a large body — all move the offset; a hung process moves nothing and is killed in 30 s.
  `inspectArchive` keeps M3.2's flat budget.
- **Executor:** transactions and drains run on a dedicated fixed pool of 4 threads (not
  `Dispatchers.IO`, whose 64 threads Coil/previews/`TransferWorker` can fill while waiting on fills
  that themselves need threads); concurrent fills are capped at 2 (§2.4).
- **Concurrency correctness (M3.3 makes the client concurrent for the first time):** an in-flight
  counter under a lock; `dropConnection` guarded by a **generation number** so a stale victim never
  unbinds a newer connection; a call whose connection was dropped by another call's timeout retries
  once on a fresh binding; the idle timer (§2.9) arms only at zero in flight and is cancelled at
  every call start. Tests: overlapping calls, one timing out while another streams, the idle timer
  racing a new call.

### 2.3 `ArchiveCatalog`: one listing per archive, on disk and in memory, always blocking

`archive/ArchiveCatalog.kt`, application-scoped next to `ArchiveInspector`.

- **Key:** top level `sha256(src | size | mtime)` from `queryDocument(src)`; nested
  `sha256(key(outer) | entryPath | uncompressed | mtime)`, so a changed outer archive invalidates
  every inner key.
- **`open(src, chain): ArchiveHandle` is single-flight and blocking:** one `Deferred` per key; the
  first caller lists, the rest await. Order: the in-memory LRU (2 trees) → the `.fzl` on disk (read
  and validated, ~50 ms for 10 MB, measured) → `:decoders` via `ArchiveSource.resolve(source).use {
  client.callStreaming(pfd, drain = copy into <key>.<nonce>.part) { s, w -> s.listArchive(pfd,
  limits, w) } }`, then rename to `<key>.fzl`. `queryDocument`, `queryChildDocuments` and
  `openDocument` all call `open` and block; there is no `peek`, no `EXTRA_LOADING`, no `notifyChange`
  protocol. A cold process (`TransferWorker` resuming, another app opening a granted Uri, session
  restore) therefore gets the full listing, never an empty folder.
- **Failures are memoised per key** (`TookTooLong`, `Unavailable`, `Refused(outcome, message)`,
  `SourceFailed`) until `fylz.refresh` or the key changes, so the provider never loops against
  `:decoders`; `queryChildDocuments` returns a rowless cursor carrying `EXTRA_ERROR` = the message,
  and `DocumentRepository.listChildren` reads `EXTRA_ERROR` and throws `IOException(message)` — the
  listing effect already toasts it (`FylzV1App.kt:862-865`), so no line in `FylzV1App` changes for
  this. `openDocument` throws `FileNotFoundException(message)`.
- **Pinned sources:** a handle pins one `ArchiveSource.Resolved` for its lifetime — a staged
  (non-seekable) source is copied **once** per open archive, not once per entry fill; a materialised
  inner archive is pinned in the entry cache while any handle on it is open. Handles close on LRU
  eviction and on process death (the disk copies survive; staging does not, by M3.2's rules).
- **`ArchiveTree`** (from the listing, in archive order, with `HashMap<parentPath, IntArray of
  ordinals>`) **normalises** every raw path: strip a leading `./` (repeatedly), leading `/`,
  trailing `/`; collapse `//` and `.` segments; ZIP only: `\` → `/`; a path whose `..` climbs above
  the root is **quarantined** (dropped from the tree, counted in `ArchiveHandle.quarantined`, shown
  in the inspection view); an empty result is quarantined. Duplicates after normalisation: **last
  member wins** (tar semantics) and earlier ordinals are hidden. A file and a directory with the same
  normalised path: the directory wins and the file is quarantined. Implicit directories (`a/b/c.txt`
  with no `a/` row) are synthesised with `o = -1`, unknown mtime. Children of `p` are therefore
  unique by path, and every row Uri is unique. Fixtures cover each case (§2.10).
- **Bounds:** an in-memory LRU of 2 trees; `archive-listings/` capped at 64 MB, LRU by file mtime;
  the sweeper (`ArchiveCacheSweeper`, which 3a **moves out of** `ArchiveSource.resolve()` where
  M3.2c put the 24 h `archive-work/` sweep) skips keys with a `Deferred` in flight and `.part` files
  younger than an hour.

### 2.4 Entry bytes: materialise to cache, return a regular-file descriptor

`openDocument(entryId, "r")` must return a descriptor synchronously and seekable. `ArchiveEntryCache`
(`archive/ArchiveEntryCache.kt`, application-scoped):

- **Refusals first,** before any byte moves, each a `FileNotFoundException` with the message shown:
  the archive's **policy decision is `refused`** ("This archive failed safety checks: <reason>" —
  browsing a refused archive is allowed, opening or copying out of it is not, so a flat bomb that
  passes the per-entry cap is still stopped by `max_total_uncompressed_bytes`/`max_entries`);
  `Symlink`/`Other` kinds ("Links and special files cannot be opened"); `ENCRYPTED_DATA` ("This
  entry is password protected. Opening protected entries arrives with the password prompt (M3.9).");
  size over `min(limits.maxFileBytes, ENTRY_CACHE_BUDGET = 512 MiB)` or over what
  `ArchiveSpacePolicy` says fits the cache volume with headroom ("This entry is too large to open in
  place. Extract it instead." — M3.4's extract has no such cap). A `Hardlink` resolves to its target's
  ordinal (the tree keeps `path → ordinal`) and opens that.
- **Fill:** `cacheDir/archive-entries/<listingKey>/<ordinal>` (`.<nonce>.part` while writing), via
  the handle's pinned source: `client.callStreaming(pfd, drain = copy into .part) { s, w ->
  s.extractEntry(pfd, ordinal, rawPath, limits, w) }`; success = `Ok(result)` with `outcome == OK`
  and `bytes == file length` **and** `bytes == declared uncompressed` when known → rename; anything
  else deletes the `.part` and throws. Concurrent opens of the same entry share one fill through a
  per-key `Deferred` with **reference-counted waiters**: a caller's cancel detaches that caller and
  only the last one's cancel aborts the fill. At most 2 fills run at once (§2.2's executor).
- **Return** `ParcelFileDescriptor.open(file, MODE_READ_ONLY)`: seekable; `PdfRenderer`,
  `Typeface.Builder`, ExoPlayer and Coil read it as an ordinary document. Eviction is LRU to
  `ENTRY_CACHE_BUDGET` by mtime; an open descriptor stays valid after unlink, so no time guard is
  needed; the materialised inner archive of an open handle is pinned (§2.3).
- `CancellationSignal` is honoured when a caller passes one (`openFileDescriptor(uri, mode, signal)`);
  `openInputStream`, `readText`, Coil and `FileOperationService` pass none, so their fills run to
  completion or failure.

**Cost model, stated honestly:** one fill = one `extract_entry_at` pass to the entry. On a seekable
ZIP or non-solid 7z that is a central-directory walk plus one seek (cheap); on `tar.*` and solid 7z
it decompresses the stream up to the entry, so copying N files out of a `tar.gz` costs about N/2
full decompressions. M3.3 accepts this (§18 item 6 records the time for a 1,000-file `tar.gz`
folder); M3.4 routes bulk extraction through one `extract(Selection)` pass. Thumbnails would
otherwise trigger a fill per visible image (`EntryThumbnail` falls back to Coil over `entry.uri`,
`EntryThumbnail.kt:69-93`), so `EntryThumbnail` shows the kind icon for archive-authority Uris (no
thumbnails inside archives; logged). `fylz.find-duplicates` hashes every entry and is disabled inside
archives (§2.6).

**Upgrade recorded for M3.4, not built now:** `StorageManager.openProxyFileDescriptor` (API 26+,
minSdk 31) gives a seekable descriptor immediately whose `onRead` blocks only until the background
fill has passed the requested range, backed by the same cache file, whenever the size is known from
the listing. It removes the materialisation latency without changing this cache.

### 2.5 Opening, listing, navigating, copying out

- `openEntry(entry)` (`FylzV1App.kt:919-930`): the push branch becomes `if (entry.isDirectory ||
  entry.isBrowsableArchive)`; for an archive the pushed location is
  `(ArchiveDocumentsProvider.rootUri(entry), entry.name)` wrapped in `runCatching` whose failure (the
  depth refusal) toasts through the existing error channel (+2 lines, paid for by §2.8). Inside an
  archive a directory entry pushes `(entry.uri, entry.name)`. `fylz.open`/`fylz.open-with`'s double-tap
  split (`BuiltInActions.kt:265,278`) uses the same predicate, so a double-tap on an archive browses
  it instead of opening the external chooser.
- `FileEntry.isBrowsableArchive` (new, `model/`): `BrowsableArchiveFormats.matches(name)` — an
  **explicit, tested set matched on `FileFormatRegistry.compoundExtension`**, with no `kind`
  precondition (many of these classify as `OTHER` today): `zip zipx jar apk cbz 7z cb7 tar tgz tbz
  tbz2 txz tzst tar.gz tar.bz2 tar.xz tar.zst tar.lz4 iso cpio ar deb rpm cab lha lzh warc`. Excluded
  with a reason in the file: `rar cbr` (fixtures pending), `arj img dmg wim` (no libarchive reader
  in `format_all`), `xar` (needs libxml2/expat, both OFF in `build.rs`), single-file `gz bz2 xz zst
  lz4` (`raw` not registered). `FileFormatRegistry.archives` stays private; the preview gate (§2.7)
  becomes `ZIP_CONTAINER_EXTENSIONS ∪ BrowsableArchiveFormats`.
- `DocumentRepository.listChildren(treeUri, folderUri)`: when `!DocumentsContract.isTreeUri(folderUri)`
  — true for every archive Uri — the children Uri is `buildChildDocumentsUri(authority, docId)` and
  each **row** Uri is `buildDocumentUri(authority, childId)` (today both use the outer tree form);
  it reads `EXTRA_ERROR` and throws `IOException`. Everything else (projection, 500/5,000 batches,
  `FileType.classify`) is unchanged. `DocNode.children` (`DocNode.kt:34-48`) gains the same
  non-tree branch, which is what makes directory copy-out and the preflight size walk work.
- **Copy-out** needs nothing else new: `FileOperationService.copy` → `DocNode.load(entryUri)` →
  `TransferEngines.forPair` picks `DocumentsTransfer` → `streamCopy(openInputStream(entryUri))` →
  the materialised file; directories recurse through `DocNode.children`; verification re-reads the
  cache file; the journal records Uris as for any copy. One refused child (a link, an encrypted
  entry) fails the whole directory copy, as today for any unreadable child
  (`FileOperationService.kt:445-447`); logged. Cut/Move are disabled (§2.6).
- **Destination chooser:** `DestinationChooserSheet` lists every open tab's current location
  (`DestinationChooserSheet.kt:69-75`); tabs whose current location is an archive are **filtered
  out** there (they are not writable), with a test.
- Breadcrumb: unchanged code now reads `Downloads / photos.zip / 2024` (the plan writes `›`; not
  changed). Up pops as today; leaving an archive is popping past its root. Recursive search is
  forced off while the current location is an archive (one condition in the search effect).
- Session restore: `SessionCodec` round-trips any Uri string; the catalog re-lists lazily and
  blocking on the first query. A vanished source → §2.3's memoised failure → toast, location stays,
  Up works. A restored `SessionCodecTest` case lists a restored archive location through the
  provider with a fake decoder, not just the string round trip.

### 2.6 Actions inside an archive: read-only, through the registry

`BrowserState` gains `locationKind: LocationKind = LocationKind.FOLDER` (defaulted, so
`BrowserStateFixtures` stay byte-identical). `LocationKind` is Addendum §C3's `location.kind` value
set **as far as M3.3 can tell it**: `FOLDER` (any tree location; MC.2 refines it to
internal/sd/usb/network/recycle-bin when it wires the condition evaluator) and `ARCHIVE`;
`disk-image` arrives with M4. The mapping is documented on the enum. `ARCHIVE` when
`activeTab.current.uri.authority == ArchiveDocumentsProvider.AUTHORITY`.

| Action | Inside an archive | Why |
|---|---|---|
| `fylz.cut`, `fylz.move-to`, `fylz.recycle`, `fylz.rename`, `fylz.rename.batch`, `fylz.tags` | disabled | they write the source; read-only until M3.6 |
| `fylz.paste`, `fylz.new-folder`, `fylz.new-file`, `fylz.scan-to-pdf` | disabled | they write into the current location |
| `fylz.favourite.toggle`, `fylz.ai.organize`, `fylz.find-duplicates` | disabled | favourites are tree folders; organise moves files; duplicates hashes (materialises) every entry |
| `fylz.copy`, `fylz.copy-to`, `fylz.share`, `fylz.compress`, `fylz.pdf.tools`, `fylz.select.*`, `fylz.open`, `fylz.open-with`, sort/view, `fylz.navigate.up`, `fylz.refresh` | unchanged | read-only or navigation |
| `fylz.extract` | unchanged | applies to a nested archive file entry; "Extract selected entries" is M3.4 |

`ActionResolverGoldenTest`: `LegacyOracle` gains the read-only rule as a named function, every
`expected*` row that changes takes it, and `BrowserStateFixtures` gains `archiveRootNoSelection`,
`archiveFolderWithSelection`, `archiveWithClipboard` — lockstep, as MC.0 requires.
`NoHardCodedMenusTest`, `ShortcutTableTest` unchanged.

### 2.7 Preview and the inspection view

- `PreviewPane.kt:156`'s gate becomes `ZIP_CONTAINER_EXTENSIONS ∪ BrowsableArchiveFormats` (so APK,
  JAR, CBZ and 3MF inspection stay); `ZipArchivePreview` is renamed `ArchivePreview` (M3.2 made it
  format-agnostic). `SEMANTIC_ZIP_DOCUMENTS` keep `ZipDocumentPreview`.
- `ArchivePreview` shows the catalog's `quarantined` count and `partial` flag when present ("N
  entries with unsafe paths are hidden"; "damaged after N entries; showing what could be read").
- Previewing an **entry** needs no new renderer: the Uri is a document, `PreviewPane` dispatches on
  `FileFormatRegistry.describe` as for any file, and the renderer opens the Uri → §2.4. A refusal
  surfaces as the existing `UniversalInspectorPreview` fallback with the exception's message.

### 2.8 Paying for the `FylzV1App.kt` ratchet

Added: +2 in `openEntry` (§2.5), +1 in the search effect, +1 named argument in the snapshot call.
Removed: the `BrowserState` construction (`FylzV1App.kt:1131-1157`) moves to
`actions/BrowserStateBuilder.kt` as `buildBrowserState(inputs: BrowserStateInputs)` with a small
input holder (about 18 inputs; positional arguments of the same types would be a bug farm); the
`remember(...)` key list stays. Net saving about 10 lines; `FylzV1AppSizeTest`'s ratchet is lowered
to the new count.

### 2.9 Decoder-process lifetime (the M3.2 hand-off)

`DecoderClient` idle-unbinds after **60 s with nothing in flight**: the timer arms only when the
in-flight counter reaches zero, is cancelled at every call start, and its `dropConnection()` carries
the generation it was armed for (a call that began meanwhile has bumped it, so the stale timer is a
no-op). Tested through the seam with an injected idle time and the two races of §2.2. Recorded in
`ARCHITECTURE.md`; closes M3.2's REVIEW_QUEUE item 12.

### 2.10 Fixtures and tests

Fixtures added to `make_archive_fixtures.py` (deterministic, each under 128 KiB): `nested-depth-4.zip`
(four archive levels; opens), `nested-depth-5.zip` (refused), `implicit-dirs.zip`, `sample-entries.zip`
(a PDF, a TTF, a PNG, a `.txt`, a 3 MB zero-filled `.bin`), `mixed-links.tar` (in-tree symlink,
hardlink, fifo), `messy-paths.tar` (`./a`, `dir/`, `/abs`, `a//b`, a `..` climb-out, a duplicate
member appended with `tarfile` in append mode, `README`/`readme`), `backslash.zip` (a `\`-separated
name), `damaged-after-3.tar` (three good headers then garbage). The Rust listing writer produces
`app/src/test/resources/fixtures/archives/sample-cd.fzl` (golden bytes) and the hostile listings for
the reader's bounds tests are built in-test. Kotlin tests never load native code: `:decoders` is an
`IDecoderService.Stub` fake that writes a known listing / known bytes into the sink **before
returning** (the drain rule of §2.2 makes file-backed pipes deterministic).

Kotlin (Robolectric):
- `ArchiveDocumentIdTest`: encode/parse round trip; depth = `n.size + 1`, level 5 refused; hostile ids.
- `ArchiveListingCodecTest`: decodes the golden `.fzl`; every flag; lossy names; link targets;
  200,000 entries (time recorded); each bounds violation and a missing trailer → `ArchiveListingCorrupt`;
  `partial` read back.
- `ArchiveTreeTest`: every normalisation case from `messy-paths.tar`/`backslash.zip`, last-member-
  wins, file-vs-directory collision, implicit directories, quarantine count, unique children.
- `ArchiveCatalogTest`: single-flight (two concurrent opens → one listing); disk-first on a warm
  `.fzl`; key changes with size/mtime and nested keys chain; corrupt file → rebuilt once; failure
  memoised until refresh; LRU of 2; 64 MB cap; pinned source reused across two fills (the fake
  counts `resolve` calls).
- `ArchiveEntryCacheTest`: fill via fake; descriptor seekable (read at an offset through
  `contentResolver.openFileDescriptor(entryUri)`); shared fill with refcounted cancel; the four
  refusals; hardlink resolves to target; `.part` cleaned on failure; eviction under budget; pinned
  inner archive not evicted.
- `ArchiveDocumentsProviderTest`: **attach with the manifest's `ProviderInfo`**
  (`packageManager.resolveContentProvider(AUTHORITY, 0)`), so the exported/permission contract is
  exercised; `queryDocument` root/entries; `queryChildDocuments` cold (no tree in memory → full
  listing) and with `EXTRA_ERROR`; `openDocument("r")`, `"w"` refused, every write method refused;
  `isChildDocument`.
- `DocumentRepositoryArchiveListingTest`: non-tree rows in archive order with `kind` classified;
  `EXTRA_ERROR` → `IOException`.
- **`ArchiveCopyOutTest`**: `FileOperationService.copy(listOf(entryUri, nestedDirUri), realTree)`
  through the real `DocumentsTransfer` path with the fake decoder → byte-identical files, nested
  directory recursed, journal as for any copy; the same after the tree was evicted from memory.
- `DecoderClientTest` additions: `callStreaming` success with the drain rule; inactivity abandon
  with unbind and a closed read end; offset-progress keeps a silent sink alive; overlapping calls;
  one call's timeout does not kill another (generation); idle timer arms only at zero and loses the
  race to a new call.
- `DestinationChooserSheetTest`: archive tabs excluded. `BrowserStateBuilderTest`; the golden test
  with three archive fixtures; `SessionCodecTest` restore-and-list; `FylzV1AppSizeTest` lowered.

Rust: `extract_entry_at` returns the bytes and **stops after the match** (assert the fd offset is
before the end for a multi-member tar); exact-case match (`README` vs `readme` by ordinal);
mismatched path → `NotFound`; `archive_list_into` golden bytes; `partial` on `damaged-after-3.tar`;
caps still enforced.

### 2.11 Device checks (`DEVICE_CHECKS.md` §18, "M3.3 — archive browsing")

1. Tap `photos.zip` in Downloads: entries list; breadcrumb `Downloads / photos.zip`; enter `2024`;
   Up twice returns. Repeat for `.7z`, `.iso`, `.tar.gz`, a `.deb`.
2. Preview a PDF and a font inside the zip; play a short video entry; view a PNG.
3. Share a text entry to another app; "Open with…" a PDF entry in an external viewer (Uri grant
   through the `MANAGE_DOCUMENTS`-protected provider).
4. Copy a folder out of the archive into Downloads: `sha256sum` identical on both sides.
5. `nested-depth-4.zip` opens to the innermost file; `nested-depth-5.zip`'s fifth level is refused.
6. A `tar.gz` with a 1,000-file folder: copy the folder out; record the time (the per-entry cost
   model, §2.4). A tarball with 80,000 entries: listing time and `:decoders` peak RSS
   (`dumpsys meminfo <pkg>:decoders`); the tab stays responsive.
7. `adb logcat | grep avc`: no SELinux denials for `isolated_app` on the pipe writes **or** on
   reading the staged/materialised cache files (nested archive listing).
8. Kill `:decoders` during a listing: the toast names the failure; re-enter: it lists. Idle 60 s:
   `:decoders` gone from `ps`; the next open brings it back.
9. Rotate and background inside an archive (Don't keep activities on): the location restores and lists.
10. Caches stay under budget after twenty archives and thirty entries; `.part` files never linger.
11. `messy-paths.tar` browses without a crash; the inspection view shows the quarantined count.

## 3. Sequencing and gates

Four commits, each green on the full gate (`./gradlew --no-daemon :app:testDebugUnitTest
:app:lintDebug :app:assembleDebug`; the `core` gate incl. `cargo +nightly fuzz build`; the
three-ABI `cargo ndk` build of `fylz-archive` and `fylz-ffi-android`):

- **M3.3a (transport and catalog):** Rust `listing.rs` writer, `extract_entry_at`, `archive_list_into`,
  `archive_extract_entry_at`, `NotFound`; Kotlin `ArchiveListingCodec` reader + golden `.fzl`;
  `ArchiveDocumentId`; `DecoderClient.callStreaming`, executor, generation/in-flight/idle logic;
  AIDL `listArchive`/`extractEntry` + `ArchiveExtractResult`; `DecoderService` mapping;
  `ArchiveCatalog`, `ArchiveTree`, `ArchiveEntryCache`, `ArchiveCacheSweeper` (moving M3.2c's sweep
  out of `ArchiveSource.resolve()`); fixtures; all their tests. No UI change.
- **M3.3b (provider):** `ArchiveDocumentsProvider` + manifest entry; `DocumentRepository` and
  `DocNode` non-tree branches + `EXTRA_ERROR`; provider, repository and copy-out tests.
- **M3.3c (UI and registry):** `BrowsableArchiveFormats`, `isBrowsableArchive`, `openEntry`, the
  double-tap split, `BrowserStateBuilder` + `locationKind` + `enabledWhen` + golden fixtures,
  search-off, `EntryThumbnail` icons, `DestinationChooserSheet` filter, `ArchivePreview` rename and
  gate, the ratchet lowered.
- **M3.3d (docs):** `ARCHITECTURE.md` ("Archives as documents": provider contract, ids, catalog,
  caches, pipes, budgets, liveness), `DEVICE_CHECKS.md` §18, `REVIEW_QUEUE.md`, PROGRESS row, PR #19.

`REVIEW_QUEUE.md` entry for M3.3 (log-and-continue):
1. Drag-out deferred to M12.2 (§0). 2. In-archive search off until M8. 3. RAR/CBR, `arj img dmg
wim xar`, and single-file compressed streams not browsable (§2.5) — the plan's table says "read: yes"
for several. 4. Copy-out is per-entry (materialise, then copy; N/2 decompressions for `tar.*`) until
M3.4 (§2.4). 5. Entries over 512 MiB cannot be opened in place. 6. Encrypted entries, links and
special files do not open (§2.4). 7. Pipes as the one bulk channel; SELinux for passed *file*
descriptors (read and write alike) unanswered until §18 item 7 (§2.2). 8. Inactivity+offset
liveness instead of §4.4's flat 30 s for streaming calls (§2.2). 9. §4.4's "read-only descriptors,
structure as Parcelables": `:decoders` now receives a writable pipe and structure crosses in a codec
(§2.2). 10. `location.kind` arrives before MC.2 with a two-value set (§2.6). 11. No thumbnails
inside archives (§2.4). 12. Archives over 200,000 entries are not browsable; damaged archives list
partially (§2.2). 13. Listing memory in `:decoders` is the Rust `Vec` only (~30 MB at the bound),
inside §4.4's target (§2.2). 14. Opening entries of a policy-refused archive is refused; browsing is
not (§2.4). 15. Idle unbind after 60 s (§2.9). 16. Breadcrumb separator `/`. 17. A vanished
archive behind a restored location toasts and stays (§2.5). 18. Ids carry no listing key: a
clipboard entry pasted after the archive was replaced copies the new archive's entry at that
position (as a replaced file would). 19. `ACTION_SEND_MULTIPLE` of thousands of ~250-character
Uris can hit the transaction limit and today's `runCatching` swallows it (`FylzV1App.kt:1030`).
20. `.iso` browsing overlaps M4.3 / §C3's `disk-image` kind. 21. Fixtures live under
`core/fixtures/archives/` and the golden `.fzl` under `app/src/test/resources/fixtures/archives/`
(MASTER_PLAN §3.4 asks for both trees).

## 4. Risks

- **The `.fzl` write is a pipe drain in the UI process**; a process death mid-listing leaves a
  `.part` the sweeper removes after an hour and a memoised nothing — the next open re-lists.
- **Materialisation latency:** a 300 MB video entry blocks its `openDocument` for the extraction;
  ExoPlayer shows a spinner. Acceptable; §2.4's recorded upgrade removes it.
- **UI-process memory:** two trees at the 200,000-entry bound ≈ 40 MB plus Uri strings for the
  visible folder. If §18 item 6 shows pressure, the LRU drops to 1.
- **Robolectric** proves the codec, the tree, the provider contract and the copy-out path, not pipe
  or SELinux semantics; §18 items 6–8 are the real-device coverage.

## 5. Amendments this design makes to earlier designs

- M3.2 (§2.3 step 4 / §3): the 24 h `archive-work/` sweep moves from `ArchiveSource.resolve()` into
  `ArchiveCacheSweeper` in M3.3a; M3.2's `ArchiveEngineError` gains `NotFound { ordinal, path }`;
  M3.2's REVIEW_QUEUE item 12 (idle unbind) is closed by §2.9; M3.2 §4's `partial` hand-off is taken.
- Part 3 (§6): `fylz-archive` gains `extract_entry_at` and `listing.rs`; `extract(Selection::Paths)`
  is unchanged and stays for M3.4's bulk path.

## 6. Recorded upgrades (not in M3.3)

- Cache-file sink for entry bytes and listings if §18 item 7 confirms the `isolated_app` file rule:
  removes the drain thread, the copy and the pipe-close protocol; the client API stays.
- `openProxyFileDescriptor`-backed entries (M3.4) for immediate seekable descriptors.
- One-pass bulk copy-out (M3.4).

## 7. Review findings and disposition (rev 1 → rev 2)

Blockers, all adopted: (1) a `DocumentsProvider` cannot be non-exported → exported +
`MANAGE_DOCUMENTS`, no `DOCUMENTS_PROVIDER` filter, hard-coded authority, attach test (§2.1);
(2) tree-form helpers throw on archive Uris → non-tree branches in `DocNode.children` and
`listChildren`'s rows (§2.5); (8) `peek` + `EXTRA_LOADING` silently copied empty folders → every
provider method blocks on a single-flight `open`, failures memoised, `EXTRA_ERROR` for messages
(§2.3); (9) liveness and pipe ownership → the client owns the pipe, closes its write end, drains with
a Robolectric-safe rule, and liveness includes the archive descriptor's offset (§2.2); (10) raw,
duplicate paths crashed the list → `ArchiveTree` normalisation, quarantine, last-member-wins,
ordinals in ids (§2.1, §2.3).

Should-fix, adopted: (3) `EXTRA_LOADING` unanswered → answered, dropped; (4) lost messages →
`EXTRA_ERROR` → `IOException` → existing toast; (5) `Selection::Paths` semantics → `extract_entry_at`
by ordinal with byte-exact path, early exit, `NotFound` with fields; (11) links as empty files →
refused / hardlinks resolved; (12) copy-out bypassing the policy → refused archives cannot be opened
or copied out, `find-duplicates` disabled; (13) quadratic cost → stated, thumbnails disabled, M3.4
bulk path, §18 timing; (14) thread starvation → dedicated pool, fill cap, refcounted waiters;
(15) idle-unbind races → in-flight counter, generation, retry-once; (16) `:decoders` listing memory →
Rust writes the codec, `Vec` only; (17) untrusted listing → bounds; (18) id size/depth → flat ids,
depth = levels ≤ 4; (19) cache lifetimes → single-flight listings, pinned sources and inner archives,
no time guard, chained nested keys; (20) what opens → explicit `BrowsableArchiveFormats`, preview gate
union; (21) destination sheet → archive tabs filtered; (22) test injection → catalog override, looper
check injectable; (23) commit split → `ArchiveDocumentId` in 3a, 3c's files listed; (31) Robolectric
pipe tests → the drain rule; (32) coverage → added; (34–40) → REVIEW_QUEUE items 3, 9–14, 21.

Nits, adopted: (6) SELinux read/write symmetry and §18 wording; (7) threading wording;
(24) ratchet arithmetic; (25) sweeper hand-off; (26) double-tap split; (27) partial flag taken,
stale-clipboard and send-multiple logged; (28) ISO overlap logged; (29) `LocationKind` mapping
documented; (30) drag-out reasons; (33) seekable proof through `openFileDescriptor`.

Alternatives (41, 42): the provider and materialise-to-cache stand, with the `openProxyFileDescriptor`
and cache-file-sink upgrades recorded (§6). Not adopted: none.
