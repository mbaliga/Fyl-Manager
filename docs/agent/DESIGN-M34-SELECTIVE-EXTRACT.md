# M3.4 design: selective extract through the transfer queue

**Rev 1** (2026-09-25), for architecture review. Design for MASTER_PLAN M3.4, written from
`SURVEY-M34-SELECTIVE-EXTRACT.md` (the read-only fact sheet at HEAD `bd00911`; every "today" claim
below is verified there with file:line evidence) on top of `DESIGN-M32-SEEKABLE-PFD.md` rev 2 and
`DESIGN-M33-ARCHIVE-BROWSING.md` rev 2 (the archive provider, `ArchiveCatalog`/`ArchiveTree`, ordinal
ids, `DecoderClient.callStreaming`, pipes as the bulk channel) and part 3's `extract()` as landed in
`a849282`. M3.4 is implemented **after M3.3**; it depends on its provider Uris, tree and streaming
client. Paths are relative to the repository root. `UNVERIFIED` marks a claim the implementing agent
must confirm and record.

The plan's text, in full, and the acceptance line it must meet:

> **M3.4 Selective extract.** Actions: Extract here; Extract to `<name>/`; Extract to…; Extract
> selected entries. Every extract runs through the transfer queue (`TransferWorker`), so it gets
> progress, cancel, staging, verification and conflict handling.
>
> A 5 GB 7z extracts through the queue with verification.

## 0. Scope

**In:** (a) four extract actions in the registry, three of them for a selected archive file and one
for selected entries inside a browsed archive; (b) an EXTRACT operation type carried by
`TransferWorker` with the same journal, staging, conflict, verification, progress, cancel, recovery
and retry behaviour copy has; (c) a single extraction pass in `:decoders` that streams every selected
entry straight into its staged destination (no cache tree, no archive staging for seekable sources,
no `ArchiveEntryCache` materialisation); (d) extraction limits that are **destination-aware**, so the
5 GB acceptance case is met without weakening the bomb defences; (e) the Kotlin
`ArchiveExtractionPolicy` deleted, as M3.2 §0 scheduled; (f) the `ArchiveToolsOverlay` extract path
routed through the same queue; (g) CRC verification surfaced from the engine for ZIP and 7z, and a
digest computed on the fly for the journal's verification field.

**Out (and where it goes):** passwords (M3.9) — an encrypted ZIP keeps today's zip4j `extractZip`
path (whole-archive only, password dialog, its policy decision now taken from the Rust inspection),
and selective extraction from an encrypted archive is refused with a message naming M3.9; encrypted
7z (the 7-Zip pack, M5); removing zip4j/commons-compress (M3.10); a per-entry conflict UI (conflicts
are resolved for **top-level items** exactly as copy does; inside a staged tree nothing can conflict);
pause/resume (the queue has none); split/multi-volume archives (M3's table, later task).

## 1. Constraints (short; the survey has the evidence)

- The queue is COPY/MOVE-shaped end to end: `TransferWorker`'s type gate, its `Data` input (`String[]`
  of Uris; WorkManager `Data.MAX_DATA_BYTES = 10,240`), `OperationRunner.recover`'s filter,
  `OperationRetryPolicy.retryableTypes`, `FylzAppShell`'s retry. The worker builds its own
  `FileOperationService`/`OperationJournal`; journal instances share the SQLite database but not a
  live flow.
- Copy's unit of work is one top-level source → one `OperationItem`; a directory is one item recursed
  under a staged `.fylz-part-<op>-<idx>-<name>` directory whose children get their final names; the
  item is finalised by rename with `finalizeTarget` (replace-with-recycle for `REPLACE`). Conflicts
  are resolved **before** enqueue for top-level items only (`findConflicts` → `ConflictSheet`).
  Verification = size equality always, plus SHA-256 of source and destination when `VerifySettings`
  says so, top-level files only.
- Today's EXTRACT stages the archive to cache, extracts a cache tree, then copies into the provider
  (three writes), runs in `operationRunner.run` with no progress, skips preflight and conflicts, and
  has a second independent copy in `ArchiveToolsOverlay` that runs in a composition scope.
- Rust `extract(fd, &Selection, &ExtractLimits, dest)` calls `DestinationProvider::open(entry)`
  synchronously per entry in archive order and writes into the returned fd; `Selection::Paths` is
  case-folded, first-match, full pass, missing → `ExtractReport.missing`; ZIP bad CRC is
  `ARCHIVE_FAILED` (surfaced as `Fatal`, stops everything); 7z bad CRC is `ARCHIVE_WARN` and is
  **swallowed**; there is no per-block hook and no cancel flag.
- The policy's `max_archive_bytes` 2 GiB, `max_file_bytes` 1 GiB and `max_total_uncompressed_bytes`
  4 GiB refuse a 5 GB 7z three ways; the same numbers sit in `ArchiveSource` (staging cap) and
  `ArchiveEntryCache` (open-in-place cap). They were sized for the staging model (archive + tree in
  cache); with a seekable source and a destination free-space check, the archive-size cap protects
  nothing and the total cap is the destination's free space.
- Inside a browsed archive a selected `FileEntry.uri` is an archive-provider Uri whose id carries
  `{src, n, o, p}`; `ArchiveTree` maps a directory path to its descendants' ordinals and has already
  applied last-member-wins and quarantine.
- `FylzV1App.kt` sits at its ratchet (2,287) with ~55 extract-specific lines; `FylzAppShell.kt` at 112.
- No `work-testing` dependency; `TransferWorker.doWork` has never been executed by a test.

## 2. Decisions

### 2.1 The four actions, through the registry

| Action id | Placement | Visible / enabled | Does |
|---|---|---|---|
| `fylz.extract` | `SelectionBar(90)` (unchanged slot) | `HAS_SELECTION`; enabled when `selection.size == 1 && BrowsableArchiveFormats.matches(name) && locationKind != ARCHIVE`… **or** a nested archive file entry inside an archive | opens the **Extract sheet** (`MenuId.EXTRACT`, rendered by `ui/actions/ExtractSheet.kt`) |
| `fylz.extract.here` | `Menu(MenuId.EXTRACT, 10)` | as `fylz.extract`, and the current location is writable (`locationKind != ARCHIVE`) | destination = `tab.current.uri`, top-level items = the archive's root entries |
| `fylz.extract.folder` | `Menu(MenuId.EXTRACT, 20)` | as `fylz.extract` | destination = `tab.current.uri`, one top-level item: a folder named `extractionFolderBaseName(archive)` containing everything |
| `fylz.extract.to` | `Menu(MenuId.EXTRACT, 30)` | as `fylz.extract` | `DestinationChooserSheet` (open tabs + "Other location…" = system picker), then as `fylz.extract.folder` into the chosen folder |
| `fylz.extract.selected` | `SelectionBar(90)`, `visibleWhen = HAS_SELECTION && locationKind == ARCHIVE` | enabled when every selected entry is a file or directory (not a link/special) and none is `encrypted` | `DestinationChooserSheet`, then items = the selected entries (files and folders) placed at the destination under their base names |

`fylz.extract` and `fylz.extract.selected` share slot 90 and are mutually exclusive by `visibleWhen`,
so the selection bar shows one of them. The gate moves from `isZipFamilyArchive` (five extensions) to
M3.3's `BrowsableArchiveFormats` (the engine's set). `ActionResolverGoldenTest`: the oracle gains
these rules, fixtures gain an "archive selected outside" and "entries selected inside" case;
`NoHardCodedMenusTest` is satisfied because the sheet lives under `ui/actions/`.

The flow code (today's ~55 lines in `FylzV1App.kt`: `pendingArchiveUri`, `extractPassword`,
`extractPasswordDialog`, the EXTRACT branches of `beginTransfer`/`runDestinationAction`, the
password dialog) moves to `ui/actions/ExtractFlow.kt` (a small state holder + composables) and
`archive/ExtractRequest.kt`; `FylzV1App` keeps a one-line call per action. Net negative; the
ratchet is lowered. The `ArchiveToolsOverlay` extract path (`extractDestination` → `service.extractZip`
in a composition scope) is replaced by the same `ExtractFlow`, so both entry points queue.

### 2.2 The request, preflight and conflicts happen in the UI process before enqueue

`ExtractRequest(archive: Uri, selection: ExtractSelection, destinationFolder: Uri, layout: Layout)`
with `ExtractSelection = All | Entries(List<ArchiveDocumentId>)` and `Layout = Here | IntoFolder(name)`.

`ExtractPlanner.plan(request): ExtractPlan` (suspend, `Dispatchers.IO`):

1. **Listing:** `ArchiveCatalog.open(archive)` (M3.3; single-flight, disk-first). Its summary gives
   `formatCode`, `hasEncryptedEntries`, `quarantined`, `partial`. An archive with encrypted entries:
   ZIP → hand the whole-archive request to the legacy `extractZip` path (§2.7); anything else, or a
   selective request → refuse with "Password-protected archives extract with M3.9."
2. **Selection expansion** over the `ArchiveTree`: `All` → every non-quarantined member; `Entries` →
   each file's ordinal, and each directory's descendants' ordinals (the tree's `parentPath →
   ordinals` map, recursively). Links and `Other` kinds are dropped from the set and counted
   (`skippedLinks`; shown in the confirm sheet as "N links skipped"). The result is a sorted
   `IntArray` of ordinals plus, per top-level item, its relative root path.
3. **Top-level items** (the journal's `OperationItem`s, exactly like copy's): `Here` → one per root
   entry of the selection (a root file or a root directory); `IntoFolder(name)` → **one** item, the
   folder; `Entries` at the destination → one per selected entry. `expectedBytes` = the sum of the
   selected descendants' `uncompressed` (null if any is unknown), from the tree.
4. **Preflight** through the existing machinery, fed synthetic `PreflightItem`s (name, size, isDirectory)
   for the top-level items: `PreflightPolicy.evaluate` (FAT name rules, VFAT 4 GiB−1, 255-byte
   names, collisions among items, free space with the 5 % margin) → `PreflightSheet` (auto-rename /
   skip / cancel) as copy has. In addition, because entry names inside a staged tree are never
   preflighted by that machinery: when the destination volume is FAT (`VolumeInfoResolver`), every
   selected entry's **leaf name** is passed through `PreflightPolicy.sanitizedName` at extraction
   time and the count of adjusted names is reported in the operation's final toast ("3 names adjusted
   for this volume").
5. **Conflicts** for the top-level items only, through `findConflicts` → `ConflictSheet` →
   `conflictResolutions`, as copy does; the batch fallback is `SKIP`. `IntoFolder` therefore asks
   once ("`photos/` already exists": Replace / Keep both → `photos (2)` / Skip).
6. **Limits** (§2.4) are computed from the destination now and stored in the plan.

The plan is written to the journal **before** enqueue: `OperationJournal.put(FileOperation(type =
EXTRACT, state = QUEUED, items = top-level items with source = the entry's archive-provider Uri
(root: the archive's root document Uri), destination = destinationFolder))`, plus a new
`extract_plans` table row `(operation_id, archive_uri, ordinals BLOB, layout, folder_name,
limits JSON, sanitize_names BOOL)` (schema v3, migration additive). `TransferWorker`'s input `Data`
for EXTRACT is then just `KEY_TYPE = EXTRACT`, `KEY_OPERATION_ID`, `KEY_CONFLICT_POLICY` — far inside
the 10 KB bound whatever the selection size; passwords never enter `Data` (there are none on this
path). `OperationRunner.enqueueExtract(operationId)` mirrors `enqueueTransfer` (unique work
`"fylz-transfers"`, `APPEND_OR_REPLACE`, tracked `RunningOperation(itemCount)`, awaits `isFinished`).

### 2.3 One pass in `:decoders`, streamed to staged destinations through per-entry pipes

`TransferWorker.doWork` accepts `EXTRACT` and hands the operation to `archive/ArchiveExtractor.kt`:

1. Load the operation and plan from the journal; `ArchiveSource.resolve(archiveUri)` **once** (pinned
   for the run; a non-seekable source stages once, with M3.2's space check; a nested archive resolves
   through M3.3's provider to its materialised file).
2. Create the top-level staged targets with copy's own helpers: for each item, `stagingName(opId,
   index, requestedName)` → `createChild(MIME_TYPE_DIR | mime, stagingName)`; record `stagingUri`
   before the first byte (copy's rule). Inside a staged directory, sub-directories are created on
   demand as entries arrive (archive order may put a file before its directory; implicit directories
   come from the tree), with their **final** names.
3. Call `IDecoderService.extractSelection(archive, limits, ordinals, sink)` **once**, through
   `DecoderClient.callWithSink` (a `callStreaming` variant whose liveness counter is fed by the sink's
   drains and by the archive descriptor's shared offset, M3.3 §2.2). The sink is an `IExtractSink`
   Binder implemented in the app process:

   ```aidl
   interface IExtractSink {
       // Return the write end of a pipe for this entry, or null to skip it. `declaredBytes` is -1 when unknown.
       ParcelFileDescriptor open(int ordinal, String path, long declaredBytes);
       // The engine finished the entry: `bytes` written, `warning` non-null for a libarchive warning
       // (a CRC mismatch arrives here for 7z). Blocks until the client has drained to EOF.
       void done(int ordinal, long bytes, String warning);
       // The engine could not read this entry (a ZIP CRC mismatch, a truncated member); the pass continues.
       void failed(int ordinal, String message);
   }
   ```

   `open` maps the ordinal to its planned target (the tree's path → this item's staged root +
   relative path, leaf sanitised if the plan says so), creates the document, opens
   `openOutputStream(target, "w")`, creates a pipe, starts a drain job (read end → stream, counting
   bytes and feeding a SHA-256), and returns the write end (the Binder reply flag closes the app's
   copy; the drain sees EOF when `:decoders` closes its dup after the entry). `done` awaits the
   drain, then checks `bytes == drained == declared` (when known) and `warning` (a CRC warning →
   the entry fails verification: the document is deleted and the entry is recorded as failed);
   `failed` deletes the partial document and records the failure. Per entry: two Binder round trips
   and one pipe — for 10,000 entries that is negligible against the I/O.
4. Progress: the drains add to a running total; `onProgress(itemIndex, itemCount, completedBytes =
   cumulative, totalBytes = plan total)` → `setProgressAsync` + the foreground notification, which
   gains a byte line ("1.2 GB of 5.0 GB") and, for the first time, `setProgress(…)` on the
   notification bar (copy keeps "Item N of M" but benefits from the same builder). The in-app
   `OperationProgressRow` shows a real fraction for the whole operation.
5. Finalisation per item, as copy: size and digest recorded (`OperationItem.sha256` = the drain
   digest for a file item; null for directories), and when `VerifySettings.shouldVerify(destination)`
   the finalised file is re-read and compared (copy's `sha256Hex(target)`), then `finalizeTarget`
   with the item's conflict plan (rename, or replace-with-recycle). An item with any failed entry is
   `FAILED` with `errorCode` = the first failure's code and its staged root deleted (copy's rule for
   a directory with a failed child); other items proceed; the operation ends SUCCEEDED / PARTIAL /
   FAILED. Failure classes: `ARCHIVE_CRC_MISMATCH`, `ARCHIVE_ENTRY_UNREADABLE`, `SIZE_MISMATCH`,
   `PERMISSION_DENIED`, `INSUFFICIENT_SPACE` (an `ENOSPC` on the output stream), `LIMIT_EXCEEDED`.
6. Cancel: WorkManager cancels the worker coroutine → the drain scope is cancelled → `callWithSink`
   closes every open read end → the engine's `write_all` fails with `EPIPE` → `Fatal` → the Binder
   call returns → staged roots deleted, journal `CANCELLED`/`USER_CANCELLED` (copy's path). A hung
   `:decoders` is abandoned by the liveness watchdog and unbound (M3.2/M3.3).
7. Recovery and retry: `OperationRunner.recover` handles EXTRACT like COPY (delete every recorded
   `stagingUri`, mark `INTERRUPTED`); `OperationRetryPolicy.retryableTypes` gains EXTRACT and a retry
   re-enqueues the same operation for its FAILED/INTERRUPTED items (the plan is in the journal);
   `FylzAppShell`'s retry switch gains the case.

**Why per-entry pipes rather than one framed pipe:** the engine writes an entry's bytes between
`open` and `done` with no per-block hook; a single-pipe framing would need chunk lengths the engine
cannot emit without a block-level provider API. Per-entry pipes reuse part 3's `DestinationProvider`
unchanged in shape and give a natural per-entry lifecycle for verification and failure. Two Binder
round trips per entry is the price; it is small. If §18/§19's device checks confirm that
`isolated_app` may write passed cache-file descriptors, nothing here changes (destinations are
foreign-provider documents, for which the answer would be different again).

### 2.4 Destination-aware limits, and the 5 GB case

`ArchiveLimits` (M3.2's Parcelable) gains two factories; the numbers live in one Kotlin file:

- `ArchiveLimits.forInspection()` — today's defaults (10,000 entries, 2 GiB archive, 1 GiB file,
  4 GiB total, ratio 200, depth 64, name 255, 200,000 listing) — used by browsing and the Inspect
  dialog, where output is bounded by cache and Binder.
- `ArchiveLimits.forExtraction(destination: VolumeInfo?, freeBytes: Long?)`:
  `maxEntries = 200_000` (the listing bound; a kernel tarball has 80,000), `maxArchiveBytes =
  HARD_CEILING` (256 GiB — the archive is not staged; `ArchiveSource` still applies the 2 GiB cap to
  a *staged* non-seekable source, which is what that number was for), `maxFileBytes = if FAT then
  4 GiB − 1 else HARD_CEILING`, `maxTotalUncompressedBytes = min(freeBytes × 0.95, HARD_CEILING)`
  (unknown free space → `HARD_CEILING`, since `PreflightPolicy` also cannot check it then), ratio,
  depth and name unchanged. The Rust `Limits` struct is untouched; it is just fed different numbers.

This is how "a 5 GB 7z extracts through the queue with verification" becomes true without weakening
the bomb defences: the ratio rule, the entry cap, the per-entry runtime caps and the destination's
real free space still bound the output; what goes is a total cap that only ever protected cache
space the new path does not use. Logged as a deviation (the Kotlin defaults' history). The M3.3
`ArchiveEntryCache` keeps its own 512 MiB open-in-place cap (cache-bounded).

**Policy over a selection, in Rust.** The listing summary's decision was computed with inspection
limits over the whole archive; extraction needs the decision over the *selected* entries with
extraction limits. `extractSelection` therefore runs, inside `:decoders`, a header pass (`inspect`
— for ZIP/7z/ISO a central-directory/header read; for `tar.*` a decompression pass) and
`policy::evaluate_selection(archive_bytes, &entries, &ordinals, &limits)` — the same rules, with the
size rules applied to the selected entries only, the ratio rule over the whole archive, path/link
rules over the selected entries, and the duplicate-key rule **within the selection** (M3.3's tree
already resolved duplicates by last-member-wins across the archive; two selected ordinals with one
normalised key — a case collision — are still refused). A refusal returns
`ArchiveExtractResult.outcome = REFUSED` with the reason before any output exists. Then the
extraction pass. For `tar.*` this is two passes (plus the listing pass at browse time); recorded in
REVIEW_QUEUE; the acceptance case is a 7z, whose header pass is cheap.

### 2.5 Engine amendments (part 3, `fylz-archive`)

- `Selection::Ordinals(Vec<u32>)` (sorted, deduplicated): matched by header index, exact; the pass
  **stops after the last selected ordinal**; an ordinal past the end is reported in a new
  `ExtractReport.missing_ordinals`.
- `DestinationProvider` gains `fn failed(&mut self, entry: &ArchiveEntry, reason: &str)`, and
  `done` gains `warning: Option<String>`. `ArchiveEntry` gains `ordinal: u32` and `declared: Option<u64>`
  (it already carries `size`; make the unknown case explicit).
- **Entry-level failures do not abort the pass:** `ARCHIVE_FAILED` from `archive_read_data_block`
  (a ZIP CRC mismatch, a bad compressed size) → `dest.failed(entry, archive_error_string)` and the
  pass continues with the next header; `ARCHIVE_FATAL` still stops everything with `Fatal`. A new
  `ArchiveError::Failed(String)` distinguishes the two internally; `Reader::check` maps −25 to it.
- **7z CRC mismatches surface:** after an `ARCHIVE_WARN` from the data read the engine captures
  `archive_error_string`; the entry's `done` carries it as `warning`. The Kotlin side treats a
  warning containing "CRC" as a verification failure (§2.3 step 3). Tests use fixtures built in-test
  by flipping one byte in a stored (`FILTER_COPY`) member of `sample-copy.7z` and in a stored member
  of a `zipfile`-built archive.
- `policy::evaluate_selection` (§2.4) and `inspect_for_extraction(fd, &limits) -> Vec<EntryMetadata>`
  (the header pass without the listing writer).
- `extract` also validates each selected entry's path with `validate_path` and the link rule **at
  extraction time** (defence in depth against a listing that changed under the plan): a violation is
  `failed(entry, reason)`, not an abort.

FFI (`fylz-ffi-android`):

```rust
#[uniffi::export(with_foreign)]
pub trait ExtractSink: Send + Sync {
    fn open(&self, ordinal: u32, path: String, declared: Option<u64>) -> Result<i32, ArchiveEngineError>;  // fd, or -1 to skip
    fn done(&self, ordinal: u32, bytes: u64, warning: Option<String>) -> Result<(), ArchiveEngineError>;
    fn failed(&self, ordinal: u32, message: String) -> Result<(), ArchiveEngineError>;
}
#[uniffi::export]
pub fn archive_extract_selection(fd: i32, ordinals: Vec<u32>, limits: ArchiveLimitsRecord, sink: Arc<dyn ExtractSink>) -> Result<ArchiveExtractReportRecord, ArchiveEngineError>;
```

The FFI adapter implements part 3's `DestinationProvider` over the foreign trait: it owns each fd
from `open` until after `done`/`failed` and closes it (the engine never closes). `DecoderService`'s
`ExtractSink` implementation forwards each call to the `IExtractSink` Binder it received, turning
the returned `ParcelFileDescriptor` into a raw fd with `detachFd()`. The header pass and
`evaluate_selection` run first (`archive_evaluate_selection(fd, ordinals, limits) ->
PolicyDecisionRecord`), so the service can return `REFUSED` without ever calling the sink.

`ArchiveExtractResult` Parcelable: `outcome` (`OK`, `REFUSED`, `NOT_SEEKABLE`, `UNSUPPORTED`,
`CORRUPT`, `LIMIT_EXCEEDED`, `INTERNAL`), `message`, `entriesWritten`, `bytesWritten`,
`entriesFailed`, `entriesSkipped`, `missingOrdinals: IntArray`.

### 2.6 Verification, stated precisely

"With verification" means, per extracted file: (1) the engine's own integrity check — ZIP CRC-32
and 7z CRC-32 are verified by libarchive and now surfaced as failures (§2.5); tar/cpio/ar/ISO carry
no per-entry checksum and this is recorded per format in the result (`verified: CRC32` vs
`verified: size only`); (2) `bytes written == bytes drained == declared size` when known; (3) a
SHA-256 computed on the drained bytes and stored in `OperationItem.sha256` for file items;
(4) when `VerifySettings.shouldVerify(destination)` (default: removable and network destinations),
a re-read of the finalised file compared with (3), exactly copy's rule. A mismatch anywhere marks the
entry (and its top-level item) FAILED with `ARCHIVE_CRC_MISMATCH`/`SIZE_MISMATCH`/`ChecksumMismatch`,
deletes the staged output for a file item, and keeps the staged tree of a directory item only when the
mismatch is a checksum one (copy keeps a staged `.fylz-part-*` for inspection on checksum mismatch;
the same rule applies).

### 2.7 The legacy path shrinks to encrypted ZIPs

`ArchiveService.extractZip` stays for **encrypted ZIP files only** (whole-archive extraction with the
password dialog) until M3.9/M3.10. It no longer calls the Kotlin `ArchiveExtractionPolicy`: its
decision comes from `ArchiveInspector.inspect(archive)` (libarchive reads encrypted ZIP metadata;
the Rust policy's decision is in the summary), and its size checks use `ArchiveLimits.forInspection()`
numbers. `createZip` takes its three numbers from `ArchiveLimits` as well. **`ArchiveExtractionPolicy.kt`
and its two tests are deleted** in M3.4c, as scheduled; `REVIEW_QUEUE.md`'s M3.1-part-3 entry already
records the three rule differences that become user-visible here. `ArchiveSpacePolicy.requirements`
(temp + destination) is used by the legacy path only; the queue path needs `stagingRequirement` (M3.2)
for a non-seekable source and the free-space preflight for the destination.

### 2.8 Fixtures and tests

Fixtures (`make_archive_fixtures.py`): `tree.tar.zst` (a three-level tree with 40 files of known
content, an implicit directory, a file before its directory, a relative symlink, a hardlink);
`tree.zip` (same content, deflate); `crc-bad.zip` and `crc-bad.7z` are built **in-test** by flipping
one byte of a stored member; `many-small.tar` (10,000 empty-ish files; shipped as `.tar.zst`) for the
round-trip cost measurement.

Rust: `Selection::Ordinals` exact match and early exit (fd offset before the end); `failed` on a ZIP
CRC mismatch with the pass continuing; 7z CRC warning surfaced through `done`; `evaluate_selection`
(size rules over the selection only; case collision within the selection refused; duplicate across
the archive allowed when only one is selected); `missing_ordinals`; path/link validation at
extraction; FFI adapter closes fds, `-1` skips, a sink error aborts with the sink's message.

Kotlin (Robolectric; `androidx.work:work-testing:2.11.2` added as a **test** dependency so
`TestListenableWorkerBuilder` can run `TransferWorker.doWork` — Apache-2.0, test-only, noted in
`THIRD_PARTY_NOTICES.md`'s test-dependencies paragraph if one exists, else a one-line note in the
PROGRESS row):
- `ExtractPlannerTest`: `All`/`Entries`/directory expansion over an `ArchiveTree`, links dropped and
  counted, top-level items per layout, `expectedBytes`, encrypted → legacy/refused routing, depth-4
  nested source accepted.
- `ArchiveLimitsTest`: `forExtraction` numbers per volume kind and free space; unknown free space.
- `ArchiveExtractorTest` (the core proof, with a fake `IDecoderService.Stub` whose `extractSelection`
  drives the sink exactly as the service would — `open`/write/`done` per ordinal in archive order,
  writing fixture bytes): `Here` and `IntoFolder` layouts byte-identical (`TreeFixtures.diffTrees`),
  sub-directories created on demand, staged names never visible in the final tree, `finalizeTarget`
  with `KEEP_BOTH`/`REPLACE`/`SKIP` per item, a `failed` entry fails its item and the batch ends
  PARTIAL with the other items intact, a CRC warning fails verification and deletes the file,
  `SIZE_MISMATCH`, FAT leaf sanitisation with the adjusted count, cancel mid-item leaves nothing
  under a final name and the journal says `CANCELLED`, progress reports cumulative bytes,
  `VerifySettings.ALWAYS` re-reads and stores `sha256`, `REFUSED` before any output.
- `TransferWorkerExtractTest` (`TestListenableWorkerBuilder`): `EXTRACT` input → `doWork` succeeds
  against the fake; a missing plan → failure with a message; the COPY/MOVE gate no longer rejects EXTRACT.
- `OperationRunnerRecoverTest`: EXTRACT staged roots deleted on recovery; `OperationRetryPolicyTest`:
  EXTRACT retryable and re-enqueued for FAILED items.
- `ExtractFlowTest`/golden test: the four actions' visibility/enabled rules on the new fixtures; the
  overlay's extract now enqueues (a fake runner records the call).
- `ArchiveService` legacy path: an encrypted ZIP takes the decision from the inspector (fake) and no
  longer references the deleted policy (compilation proves it).

### 2.9 Device checks (`DEVICE_CHECKS.md` §19, "M3.4 — selective extract through the queue")

1. Extract here / to `<name>/` / to… for a ZIP, a 7z, a `tar.xz`, an ISO: progress row and
   notification show bytes; the result is byte-identical (`sha256sum`); no file under `archive-work/`
   or `archive-entries/` appears during the run (no staging, no materialisation).
2. **The acceptance case:** a 5 GB 7z (one 5 GB member and a thousand small ones) extracts to
   internal storage with `VerifySettings.ALWAYS`; record wall time, peak `:decoders` RSS, and that
   the journal row carries `sha256`. Repeat to an exFAT SD card; then a vfat SD card refuses the
   5 GB member at preflight with the VFAT message and extracts the rest after "skip".
3. Cancel mid-way: the staged folder disappears, the journal says cancelled, `:decoders` is idle.
4. Kill the app mid-extract (`am kill`): on relaunch the recovery deletes the staged root and the
   history shows INTERRUPTED; Retry re-runs it.
5. A ZIP with a deliberately corrupted member: that file is reported as a CRC mismatch, the rest
   extracts, the operation is PARTIAL. Same with the 7z.
6. Extract selected entries from inside a browsed archive, including a folder, into another tab's
   folder via the chooser; a link entry is reported as skipped.
7. An encrypted ZIP: the password dialog appears and zip4j extracts as before; "Extract selected"
   inside it is refused with the M3.9 message.
8. `adb logcat | grep avc`: no denials for the per-entry pipes.
9. A 10,000-file `tar.zst` folder: record the wall time (two Binder round trips per entry).

## 3. Sequencing and gates

Three commits, each green on the full gate (`./gradlew --no-daemon :app:testDebugUnitTest
:app:lintDebug :app:assembleDebug`; the `core` gate incl. `cargo +nightly fuzz build`; the
three-ABI `cargo ndk` build):

- **M3.4a (engine and FFI):** §2.5 in `fylz-archive` and `fylz-ffi-android`, fixtures, Rust tests;
  the `archive_entries` fuzz target exercises `Selection::Ordinals` too.
- **M3.4b (queue):** AIDL `IExtractSink` + `extractSelection` + `ArchiveExtractResult`;
  `DecoderService`; `DecoderClient.callWithSink`; `ArchiveLimits.forInspection/forExtraction`;
  `ExtractPlanner`, `ExtractPlan` + the `extract_plans` table (schema v3); `ArchiveExtractor`;
  `TransferWorker` EXTRACT; `OperationRunner.enqueueExtract`/`recover`; retry policy; `work-testing`;
  all tests of §2.8 except the UI ones.
- **M3.4c (UI, legacy path, docs):** registry actions and `ExtractSheet`; `ExtractFlow` out of
  `FylzV1App` (ratchet lowered); the overlay rerouted; `extractZip` reduced to encrypted ZIPs on the
  Rust decision; **`ArchiveExtractionPolicy.kt` + tests deleted**; `ARCHITECTURE.md` (the operation
  engine section gains EXTRACT; the archive section gains the extraction path and limits table);
  `DEVICE_CHECKS.md` §19; `REVIEW_QUEUE.md` entry; PROGRESS row; PR #19.

`REVIEW_QUEUE.md` entry for M3.4 (log-and-continue):
1. Extraction limits are destination-aware (§2.4): the 1 GiB/4 GiB caps of the Kotlin defaults were
   staging-era defences and are replaced by the destination's free space and per-file limit; the
   archive-size cap applies only to staged (non-seekable) sources. This is what makes the 5 GB
   acceptance case pass. 2. Encrypted ZIPs keep zip4j until M3.9/M3.10; selective extraction from
   encrypted archives is refused (§2.7). 3. `tar.*` extraction costs two passes in `:decoders`
   (policy header pass + extraction), three with the browse-time listing (§2.4). 4. Conflicts are
   resolved for top-level items only; inside a staged tree nothing conflicts; FAT leaf names are
   sanitised silently with a count (§2.2). 5. tar/cpio/ar/ISO have no per-entry checksum; their
   "verification" is size + drain digest + optional re-read (§2.6). 6. 7z CRC mismatches were
   silently swallowed by the engine before M3.4a (§2.5). 7. Per-entry pipes: two Binder round trips
   per entry (§2.3, §19 item 9). 8. `Selection::Paths` remains for callers that have no listing;
   the queue uses ordinals. 9. `work-testing` added as a test dependency. 10. The notification gains
   a progress bar and byte text for EXTRACT; COPY/MOVE keep "Item N of M" until a later pass.
   11. Retry re-runs the whole failed item, not the failed entries only.

## 4. Risks

- **`detachFd()` and Binder reply ownership** (§2.3 step 3): the app's write-end copy must not
  survive `open`'s return, or the drain never sees EOF. The `PARCELABLE_WRITE_RETURN_VALUE` path
  closes it; `UNVERIFIED` under Robolectric's in-process Binder — the fake stub writes and closes
  explicitly, and §19 item 1 covers the real transport.
- **Callback Binder from an isolated process**: `:decoders` calling back into the app process
  through a Binder passed in the request is the standard pattern (`UNVERIFIED` that `isolated_app`
  is not restricted here; §19 item 1). Fallback: replace the callback with N pre-opened pipes per
  batch of 64 entries — the sink API in Rust is unchanged.
- **Two passes for `tar.xz`** double a long extraction; the acceptance case is 7z. If this hurts,
  `evaluate_selection` can run on the listing's entries passed in chunks — recorded, not built.
- **Schema v3**: an additive table; the existing migration test pattern in `FylzDatabase` covers it.
