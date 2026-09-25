# M3.4 design: selective extract through the transfer queue

**Rev 2** (2026-09-25), after the architecture review of rev 1 (commit `42e52c8`); §7 lists the
findings and their disposition. Design for MASTER_PLAN M3.4, written from
`SURVEY-M34-SELECTIVE-EXTRACT.md` (the read-only fact sheet at HEAD `bd00911`; "today" claims are
verified there with file:line evidence) on top of `DESIGN-M32-SEEKABLE-PFD.md` rev 2 (as landed in
`a418a9c`) and `DESIGN-M33-ARCHIVE-BROWSING.md` rev 2 (the archive provider, `ArchiveCatalog`/
`ArchiveTree`, ordinal ids, `DecoderClient.callStreaming`, pipes as the bulk channel) and part 3's
`extract()`. M3.4 is implemented **after M3.3**. Paths are relative to the repository root.
`UNVERIFIED` marks a claim the implementing agent must confirm and record.

The plan's text, in full, and the acceptance lines it must meet:

> **M3.4 Selective extract.** Actions: Extract here; Extract to `<name>/`; Extract to…; Extract
> selected entries. Every extract runs through the transfer queue (`TransferWorker`), so it gets
> progress, cancel, staging, verification and conflict handling.
>
> A 5 GB 7z extracts through the queue with verification. Hostile fixtures (zip-slip, bombs, symlink
> escapes, oversized headers) are refused.

## 0. Scope

**In:** (a) four extract actions in the registry; (b) an EXTRACT operation carried by `TransferWorker`
with the journal, staging, conflict, verification, progress, cancel, recovery and retry behaviour copy
has, plus a defined lifecycle for an operation written before it is enqueued; (c) **one** extraction
pass in `:decoders` that streams every selected entry through a **single framed pipe** into staged
destinations (no cache tree, no archive staging for seekable sources, no `ArchiveEntryCache`
materialisation, no callback Binder from the isolated process); (d) extraction limits that are
**destination-aware with explicit consent** above the old totals, so the 5 GB acceptance case passes
while the hostile-fixture acceptance line keeps holding; (e) the Kotlin `ArchiveExtractionPolicy`
deleted, as M3.2 §0 scheduled; (f) the `ArchiveToolsOverlay` extract path routed through the queue;
(g) CRC verification surfaced from the engine (ZIP, 7z) as structured warnings; (h) a headless
planner mode for Addendum §C4/§C5's `extract` step (enqueue with a conflict policy, no sheets).

**Out (and where it goes):** passwords (M3.9) — an encrypted ZIP keeps today's zip4j `extractZip`
path (whole-archive only, password dialog, decision from the Rust inspection of the staged bytes),
and selective extraction from an encrypted archive is refused naming M3.9; encrypted 7z read (M3's
table assigns it to the 7-Zip `.so`; M3.9 gives it the prompt); removing zip4j/commons-compress
(M3.10); a per-entry conflict UI (conflicts are resolved for **top-level items**, as copy does);
pause/resume; split/multi-volume archives; mtime preservation (SAF has no setter; logged); resuming
a stream-format pass after a fatal error (§2.4 says what is retried and what is not).

## 1. Constraints (short; the survey has the evidence)

- The queue is COPY/MOVE-shaped end to end: `TransferWorker`'s type gate, its `Data` input (`String[]`
  of Uris; WorkManager `Data.MAX_DATA_BYTES = 10,240`), `OperationRunner.recover`'s filter (and
  recovery covers PREFLIGHT/RUNNING/PAUSED only — never QUEUED), `OperationRetryPolicy.retryableTypes`,
  `FylzAppShell`'s retry `when`. WorkManager re-runs unfinished work after process death and on
  `Result.retry()`, with the same input. `OperationsDao.put` deletes and re-inserts the operation and
  **every item row** on each write; `remove`/`clearFinished`/`enforceRecordLimit` (200 records) delete
  operations. `FylzDatabase` has no `onUpgrade` test.
- Copy's unit of work is one top-level source → one `OperationItem`; a directory is one item recursed
  under a staged `.fylz-part-<op>-<idx>-<name>` directory; the item is verified (staged file re-read)
  **then** finalised by rename with `finalizeTarget` (replace-with-recycle for `REPLACE`).
  `resolveTargetPlan`, `finalizeTarget`, `uniqueName`, `TargetPlan`, `verifyFile` are private to
  `FileOperationService`. Conflicts are resolved before enqueue for top-level items only.
  `findChild`/`uniqueName` list the destination once per call.
- Today's EXTRACT stages the archive, extracts a cache tree, copies into the provider (three writes),
  runs in `operationRunner.run` with no progress, skips preflight and conflicts, and has a second copy
  in `ArchiveToolsOverlay` that runs in a composition scope.
- Rust `extract(fd, &Selection, &ExtractLimits, dest)` writes each entry's blocks from memory into the
  fd `DestinationProvider::open` returned (`lib.rs:779-782`); `Selection::Paths` is case-folded,
  writes **every** matching header, full pass; ZIP CRC/size mismatches are `ARCHIVE_FAILED`
  (recoverable: the next header still reads), ZIP inflate errors are `ARCHIVE_FATAL` (sticky); 7z CRC
  mismatch is `ARCHIVE_WARN` and swallowed; a 7z LZMA error is `FAILED` and corrupts the rest of its
  solid folder; there is no per-block hook and no cancel flag. A cdylib does not ignore SIGPIPE.
- The policy's 2 GiB / 1 GiB / 4 GiB caps refuse a 5 GB 7z; with a seekable source and a free-space
  check the archive-size cap protects nothing, but the **total** cap is the only defence against a
  ratio-200 archive writing until the disk is full, because per-entry compressed sizes are unknown.
  `REVIEW_QUEUE.md`'s M3.1-part-3 entry records "refuse the whole archive" as the chosen rule for
  links/paths and asks the owner whether to switch to "extract minus the link".
- M3.3's `ArchiveTree` maps a directory to its descendants' ordinals, has applied last-member-wins
  and quarantine, and resolves hardlinks to their target; the ordinal is the raw
  `archive_read_next_header` index (counting the `./` root, links, `Other` and failed headers) and
  sits on `EntryMetadata` (M3.3a). `ArchiveEntryCache` caps open-in-place at 512 MiB and refuses
  entries of a policy-refused archive.
- `FylzV1App.kt` (2,287) and `FylzAppShell.kt` (112) sit exactly on their ratchets; `FylzAppShell`'s
  retry `when` must change for a new type. Binder nested-transaction routing runs a callback on the
  thread blocked in the outbound call. `VolumeInfo.filesystemType` is null for every destination
  outside the Fylz provider; `FAT_FAMILY` includes exFAT. `NotificationCompat.setProgress` takes `int`.
  No `work-testing` dependency.

## 2. Decisions

### 2.1 The four actions, through the registry

| Action id | Placement | `visibleWhen` / `enabledWhen` | Does |
|---|---|---|---|
| `fylz.extract` | `SelectionBar(90)` | visible `HAS_SELECTION && locationKind != ARCHIVE`; enabled `selection.size == 1 && BrowsableArchiveFormats.matches(name)` | opens the **Extract sheet** (`MenuId.EXTRACT`, `ui/actions/ExtractSheet.kt`) |
| `fylz.extract.here` | `Menu(MenuId.EXTRACT, 10)` | as `fylz.extract` | destination = `tab.current.uri`; items = the archive's root entries (§2.2) |
| `fylz.extract.folder` | `Menu(MenuId.EXTRACT, 20)` | as `fylz.extract` | destination = `tab.current.uri`; one item, the folder `extractionFolderBaseName(archive)` |
| `fylz.extract.to` | `Menu(MenuId.EXTRACT, 30)` | as `fylz.extract` | `DestinationChooserSheet` (open tabs, archive tabs filtered, "Other location…"), then as `.folder` |
| `fylz.extract.selected` | `SelectionBar(90)` | visible `HAS_SELECTION && locationKind == ARCHIVE`; enabled `selection.isNotEmpty()` | `DestinationChooserSheet`, then items = the selected entries |

The two slot-90 actions are mutually exclusive by `locationKind`, so exactly one renders. Per-entry
facts `FileEntry` does not carry (links, encryption) are enforced by the planner (§2.2), which reports
them in the confirm sheet, not by `enabledWhen`. `ActionResolverGoldenTest`: oracle rules added,
fixtures gain "archive selected outside" and "entries selected inside" cases; `NoHardCodedMenusTest`
is satisfied by the sheet living under `ui/actions/`.

The flow (today's ~55 extract lines in `FylzV1App.kt`, the password dialog, and new sheets) moves to
`ui/actions/ExtractFlow.kt`: a state holder plus composables that **host their own** `PreflightSheet`
and `ConflictSheet` instances (the existing composables, re-used with the flow's state — copy's
handlers in `FylzV1App` stay COPY/MOVE-shaped), a "Reading archive…" dialog with Cancel for the
planning step (§2.2 step 1 may decompress an unbrowsed `tar.xz`), and the confirm sheet (§2.2 step 7).
`FylzV1App` keeps one line per action; `ensureNotificationPermissionRequested` runs for extract too.
Net negative in `FylzV1App.kt`; the ratchet is lowered. The `ArchiveToolsOverlay` extract path calls
the same `ExtractFlow`, so both entry points queue, and its "Inspect and extract ZIP" widens to every
browsable format (logged).

### 2.2 Planning: selection, rules, preflight, conflicts, consent — in the UI process, before enqueue

`ExtractRequest(archive: Uri, selection: ExtractSelection, destinationFolder: Uri, layout: Layout)`,
`ExtractSelection = All | Entries(List<ArchiveDocumentId>)`, `Layout = Here | IntoFolder(name)`.
`ExtractPlanner.plan(request, ui: PlannerUi | Headless(conflictPolicy))` — the headless mode is what
Addendum §C4/§C5's `extract` step and the script API call (no sheets; refusals are results).

1. **Listing.** `ArchiveCatalog.open(archive)` (M3.3; single-flight, disk-first; for an unbrowsed
   `tar.*` this is a decompression pass, shown as "Reading archive…" with Cancel). The handle is kept
   for the run (§2.3) and its **catalog key** (source size + mtime) goes into the plan. Encrypted
   entries: ZIP + `All` → the legacy path (§2.7); otherwise refused ("Password-protected archives
   extract with M3.9."). `partial` listings are refused ("This archive is damaged; showing its
   readable part is possible, extracting it is not yet").
2. **Archive-wide rules first, over the whole listing, in Rust** (this is the hostile-fixture
   acceptance line, unchanged from part 3): the catalog summary's policy decision already applied
   `evaluate` with inspection limits over every entry. The planner takes from it the **structural**
   verdict — path rules, the link rule, duplicate keys, unknown sizes — via a new summary field
   `structuralRefusal: String?` (Rust computes `evaluate` twice at listing time: once with the caller's
   limits, once with size limits set to `u64::MAX`; the second is the structural verdict). A
   structural refusal refuses extraction of **anything** from that archive, with the reason: zip-slip,
   absolute paths, escaping links and colliding paths are refused whole, not "minus the bad entry".
   `REVIEW_QUEUE.md`'s open owner question stays open and unchanged.
3. **Selection expansion** over the `ArchiveTree`: `All` → the ordinals the tree shows (winners after
   last-member-wins; quarantined entries cannot exist here — a quarantine is a structural refusal in
   step 2); `Entries` → files' ordinals and directories' descendants. `Symlink`/`Other` entries are
   never extracted (SAF cannot create them) and are counted (`skippedLinks`, shown in the confirm
   sheet); `Hardlink` entries are extracted as **copies of their target** after the pass (§2.3 step 6).
   Result: sorted **ordinal ranges** (Extract all is one range) plus, per top-level item, its relative
   root path.
4. **Top-level items:** `Here` → one per root entry of the selection; `IntoFolder(name)` → one, the
   folder; `Entries` → one per selected entry (names are unique: M3.3's tree makes a folder's children
   unique and the within-selection duplicate rule refuses case-only variants). `expectedBytes` from the
   tree. `Here` with more than `HERE_ROOT_THRESHOLD = 200` roots asks "Extract 3,412 items into this
   folder, or into `photos/`?" (default: the folder) — one item is cheaper for the journal and the
   destination than thousands (§2.3 step 5).
5. **Preflight** through the existing machinery for the top-level items (`PreflightPolicy.evaluate`:
   FAT name rules, VFAT 4 GiB−1, 255-byte names, collisions among items, free space with the 5 %
   margin → `PreflightSheet`), **plus per-entry rules the copy path never needed**: when
   `VolumeInfo.filesystemType == "vfat"` (exFAT has no 4 GiB limit; `FAT_FAMILY` is not the test),
   every selected entry over 4 GiB − 1 is a preflight problem whose Skip removes its ordinal; on any
   FAT-family volume every **path component** (not only leaves) is sanitised with
   `PreflightPolicy.sanitizedName`, collisions after sanitisation are uniquified (`name (2)`), and the
   adjusted count is reported. Destinations outside the Fylz provider have `filesystemType == null`:
   the rules cannot apply there and an `EFBIG` at write time fails that entry (§2.3 step 4), logged.
6. **Conflicts** for the top-level items only: **one** destination listing into a case-aware name map
   feeds `findConflicts`, the `ConflictSheet`, and Keep-both's unique-name probing (no per-item
   `queryChildDocuments`); per-item resolutions and name overrides go into the **plan**, not `Data`
   (`OperationItem` has no policy field; rev 1 would have turned every Replace into SKIP). Fallback
   `SKIP`. Headless mode takes its policy from the request.
7. **Limits and consent** (§2.4): the plan stores `ArchiveLimits.forExtraction(volume)`; the confirm
   sheet shows entries, expanded size and ratio, and requires an explicit tick when the expanded size
   exceeds 4 GiB or the entry count exceeds 10,000 (the old hard caps become consent thresholds).
   Headless mode refuses above the thresholds unless the request says `allowLarge = true`.

The plan is written **atomically with the operation** (§2.5's DAO method, one transaction):
`FileOperation(type = EXTRACT, state = QUEUED, items…)`, `extract_plans(operation_id, archive_uri,
catalog_key, layout, folder_name, ranges BLOB, limits JSON, sanitize BOOL, allow_large BOOL)` and
`extract_plan_items(operation_id, item_index, root_path, requested_name, conflict_policy,
name_override)`. `Data` for EXTRACT carries `KEY_TYPE = EXTRACT` and `KEY_OPERATION_ID` only.
`OperationRunner.enqueueExtract(operationId)` mirrors `enqueueTransfer` (unique work `"fylz-transfers"`,
`APPEND_OR_REPLACE`, tracked `RunningOperation`, awaits `isFinished`) and **tags the request with the
operation id** (`addTag("op:" + id)`).

### 2.3 One pass in `:decoders`, one framed pipe, drained into staged destinations

`TransferWorker.doWork` accepts `EXTRACT` and hands the operation to `archive/ArchiveExtractor.run(opId)`:

1. **Claim** (§2.5): one transaction moves the operation `QUEUED | INTERRUPTED | NEEDS_ATTENTION
   (PROCESS_INTERRUPTED) → RUNNING`, refuses if another claim is live (a second worker run, a Retry
   racing a WorkManager re-run), deletes any recorded staging of its own items, skips items already
   `SUCCEEDED`, and re-checks the catalog key (a changed archive → `FAILED / ARCHIVE_CHANGED`).
2. **Source:** the catalog handle's **pinned** `ArchiveSource.Resolved` (§2.2 step 1; a non-seekable
   source is staged once, by the app's `ArchiveSource` instance with its own 2 GiB staging cap — never
   an instance built from `forExtraction`'s numbers). A nested archive resolves through M3.3's
   provider to its materialised file (512 MiB cap and the outer decision apply; logged).
3. **One call:** `IDecoderService.extractRanges(archive, limits, ranges, sink)` through
   `DecoderClient.callStreaming` (M3.3's: client-owned pipe, closes its write end in the transaction
   job's `finally`, drain rule, liveness from sink bytes **or** the archive descriptor's offset,
   generation-guarded drop) on a **dedicated 2-thread pool for extraction** so a long extraction never
   starves browsing's streaming pool. Rust `archive_extract_ranges(fd, ranges, limits, sink_fd)` runs
   the header pass (§2.4, policy over the selection) and then the extraction pass, writing **frames**:

   ```
   FZX1                                       magic
   BEGIN  { tag 0x01, ordinal u32, declared i64 (-1 unknown), kind u8, path_len u32, raw path }
   DATA   { tag 0x02, ordinal u32, len u32 (≤ 1 MiB), bytes }        zero or more
   END    { tag 0x03, ordinal u32, bytes u64, warning u8 (0 none, 1 CRC mismatch, 2 other + msg) , msg_len u16, msg }
   FAIL   { tag 0x04, ordinal u32, msg_len u16, msg }                 the entry could not be read; the pass continues
   DONE   { tag 0x05, entries u32, bytes u64, failed u32 }            the pass ended normally
   ABORT  { tag 0x06, msg_len u16, msg }                              the pass ended on a fatal engine error
   ```

   The engine's `extract` gains a **block-level provider** (`DestinationProvider::write(entry, block)`
   replaces the fd-returning `open` for this path; part 3's fd-based path stays for M3.3's
   `extract_entry_at`), and the FFI's framer implements it over the sink fd. Little-endian; the Kotlin
   `ExtractFrameReader` treats the stream as **untrusted** (bounds on lengths, ordinals must be in the
   plan and strictly increasing, one `BEGIN` per ordinal, `DATA` only between its `BEGIN` and
   `END`/`FAIL`, cumulative bytes ≤ declared when known and ≤ the plan total; a violation aborts with
   `PROTOCOL_ERROR` and the pass is abandoned). Structured `warning` kinds replace string matching.
4. **Drain → staged targets** (the demuxer runs on the extraction pool, not on Main): on `BEGIN` it
   maps the ordinal to its planned target (item root + relative path from the tree, sanitised per the
   plan), creates directories on demand with final names, creates the file as
   `application/octet-stream` (ExternalStorageProvider appends an extension when the MIME disagrees
   with the name), checks the **created** name equals the requested one (uniquify otherwise), opens
   `openOutputStream(target, "w")`, and — for the item's first entry — creates the staged root
   (`stagingName(opId, idx, requestedName)`, `stagingUri` recorded before the first byte). `DATA`
   writes and feeds a SHA-256. `END` checks `bytes == written == declared` (when known) and the
   warning: a CRC mismatch fails the entry (`ARCHIVE_CRC_MISMATCH`). `FAIL` deletes the partial
   document (`ARCHIVE_ENTRY_UNREADABLE`). An app-side write error (`ENOSPC`, `EFBIG`, a provider
   exception) **discards the rest of that entry's frames** and fails the entry (`INSUFFICIENT_SPACE`,
   `FILE_TOO_LARGE`, `PERMISSION_DENIED`); the pass continues. Failed entries fail their item (its
   staged root deleted, `errorCode` = the first failure); other items proceed.
5. **Progress:** cumulative bytes → `onProgress` throttled by `ProgressWriteThrottle` (250 ms / 8 MiB);
   `setProgressAsync` and `setForegroundAsync` are called from the throttled path only; the
   notification gains a determinate bar scaled to 0..1000 and a byte line. Journal writes for EXTRACT
   go through a new single-row `OperationsDao.updateItem` (copy's `put` rewrites every item row each
   time; with thousands of `Here` items that is quadratic).
6. **After the pass:** hardlinks are written as copies of their already-extracted target (the tree
   knows the target ordinal); items are verified in copy's order — the staged file re-read
   (`sha256Hex(staged)` compared with the drain digest) when `VerifySettings.shouldVerify(destination)`,
   then `finalizeTarget` with the item's conflict plan (rename, or replace-with-recycle); re-reads run
   **after** the pass so they never starve the 30 s liveness watchdog. An item whose entries all
   completed before a mid-pass `ABORT` is finalised (copy's PARTIAL semantics); items with no
   completed entries are `FAILED / ARCHIVE_FATAL`. Operation ends SUCCEEDED / PARTIAL / FAILED.
7. **Fatal recovery for seekable formats:** after an `ABORT` on ZIP, non-solid 7z or ISO (a cheap
   header walk), the extractor issues **one** further `extractRanges` for the ordinals after the
   failing one; stream formats and solid 7z do not retry (the rest of the pass is lost, logged).
8. **Cancel:** the worker's coroutine is cancelled → `callStreaming` closes the read end → the
   engine's next `write_all` fails with `EPIPE` (SIGPIPE is ignored at FFI init — M3.3a) → `Fatal` →
   the call returns; cleanup **waits (bounded, 5 s) for the transaction to return** before deleting
   staged roots (the transaction runs in the client's own scope); journal `CANCELLED / USER_CANCELLED`
   for a user cancel; a **system stop** (`isStopped`) leaves items `INTERRUPTED` and returns
   `Result.retry()` — the re-run claims and resumes (§2.5).
9. **Recovery and retry:** `OperationRunner.recover` skips EXTRACT operations whose tagged work is
   unfinished (WorkManager will re-run them) and otherwise deletes recorded staging as for COPY;
   `OperationRetryPolicy.retryableTypes` gains EXTRACT, and a retry **re-claims the same operation**
   for its FAILED/INTERRUPTED items (copy retries as a new operation with Keep-both; logged);
   `FylzAppShell`'s retry dispatch moves out of that file into `operations/RetryDispatcher.kt` (the
   shell is on its ratchet, which is lowered).

**Why one framed pipe rather than per-entry pipes with a callback Binder** (rev 1): the callback
design deadlocked on every entry (the engine called `done` while it still held the pipe's write end),
needed a foreign uniffi trait whose unexpected exceptions panic in 0.32, opened a new Binder direction
from the isolated process into the app, and had no way to skip a failed entry's remaining bytes. The
framed pipe reuses M3.3's `callStreaming`, drain rule and cancel-by-closing; the isolated process
never holds a handle to the user's folders; per-entry failure and cancellation are frame-level; SAF
latency overlaps decompression in the pipe buffer. Its price is a bounded frame parser, for which
M3.3's `.fzl` reader is the precedent. Recorded fallback: none needed.

### 2.4 Policy scope, destination-aware limits, and consent

**Rules over the whole archive** (structural: paths, links, duplicates, unknown sizes) come from the
listing (§2.2 step 2) and refuse extraction of anything. **Size rules over the selection** run in Rust
at the start of `archive_extract_ranges`: a header pass collects `EntryMetadata` for the selected
ordinals (a central-directory/header read for ZIP/7z/ISO; a decompression pass for `tar.*`, recorded)
and `policy::evaluate_selection(archive_bytes, &selected, &limits)` applies `max_file_bytes`,
`max_total_uncompressed_bytes`, `max_entries` and the within-selection duplicate rule; the
archive-level ratio is `Σ selected uncompressed / archive_bytes` (the selection is what will be
written). A refusal is a `REFUSED` result before any frame.

`ArchiveLimits.forExtraction(volume: VolumeInfo?)`:
- `maxArchiveBytes = HARD_CEILING (256 GiB)` — the archive is not staged; `ArchiveSource` keeps its
  2 GiB cap for a **staged** non-seekable source, which is what that number was for.
- `maxFileBytes = if (volume?.filesystemType == "vfat") 4 GiB − 1 else HARD_CEILING`; the runtime cap
  stays `min(limit, declared)` per entry.
- `maxTotalUncompressedBytes`: **free space known** → `free − max(5 % of free, LOW_STORAGE_THRESHOLD
  (StorageManager.getStorageLowBytes, else 500 MB))`, and **above 4 GiB only with the user's explicit
  consent** in the confirm sheet (§2.2 step 7), which shows the expanded size and the ratio; **free
  space unknown** (a cloud provider, a picker Uri whose root reports nothing) → **4 GiB**, the old cap.
- `maxEntries = 10,000` by default, raised to `200,000` (the listing bound) **only with consent**.
- ratio 200, depth 64, name 255 unchanged.

What this changes, precisely, and why it is acceptable: before, a ratio-≤200 archive could write at
most 4 GiB; now, with consent, it can write up to the destination's free space minus the margin,
after the user has been shown "expands to 19.8 GB from 100 MB (ratio 198)". Without consent the old
caps hold, and when the app knows least about the destination the cap is the old one. The
hostile fixtures are refused exactly as before: `zip-slip.zip`, `absolute-path.zip`,
`symlink-escape.tar` by the structural verdict (whole archive), `many-entries.tar.zst` by the entry
cap without consent; tests run every hostile fixture through the selection path. The attack that
remains is a user consenting to a large expansion, which is the point of consent. Logged verbatim in
REVIEW_QUEUE.

### 2.5 Engine, FFI, IPC and persistence amendments

`fylz-archive`:
- `Selection::Ranges(Vec<(u32, u32)>)` (sorted, non-overlapping, inclusive ordinals; Extract all is
  one range) matched by the shared header counter (M3.3a's ordinal); the pass **stops after the last
  selected ordinal** (for stream formats that is where it stops reading; seekable formats already read
  the end first).
- A block-level `DestinationProvider` variant: `trait BlockSink { fn begin(&mut self, e: &EntryMetadata)
  -> Result<bool /* extract? */>; fn write(&mut self, ordinal: u32, block: &[u8]) -> Result<()>;
  fn end(&mut self, ordinal: u32, bytes: u64, warning: Option<Warning>) -> Result<()>; fn failed(&mut
  self, ordinal: u32, reason: &str) -> Result<()>; }` with `pub enum Warning { CrcMismatch, Other(String) }`
  classified in Rust (a 7z data `WARN` is only ever the CRC; a ZIP header-time "Inconsistent CRC32
  values" is `Other`). `extract_blocks(fd, &Selection, &ExtractLimits, &mut dyn BlockSink)` shares the
  header loop with `extract`.
- **Failure isolation, stated exactly:** `ARCHIVE_FAILED` from the data read (ZIP CRC or size
  mismatch, a 7z LZMA error) → `failed(ordinal, msg)`, continue; `ARCHIVE_FATAL` (a ZIP inflate error,
  truncation) → `Fatal`, the pass ends (`ABORT`); a 7z LZMA error corrupts the rest of its solid folder
  (those entries fail too). `Reader::check` maps −25 to a new `ArchiveError::Failed`.
- `EntryMetadata.ordinal` (M3.3a) is the plan's and the frames' ordinal; `begin` compares the raw path
  with the plan's byte-exact — the Kotlin side does the same on `BEGIN`.
- `policy::evaluate_selection` (§2.4); `inspect_for_extraction`; the structural second `evaluate` at
  listing time (`structural_refusal` on `Inspection`/`ArchiveInspectionRecord`/`ArchiveInspection`).
- SIGPIPE ignored once at FFI init (M3.3a already; verify it covers the new function).

`fylz-ffi-android`: `archive_extract_ranges(fd, ranges: Vec<RangeRecord>, limits, sink_fd) ->
Result<ArchiveExtractReportRecord, ArchiveEngineError>` with the framer as the `BlockSink`; no foreign
trait; `ArchiveEngineError` gains `Failed { detail }` and `Cancelled` and implements
`From<uniffi::UnexpectedUniFFICallbackError>` (not needed without a foreign trait, but cheap and
future-proof for M3.9's password callback).

AIDL: `ArchiveExtractResult extractRanges(in ParcelFileDescriptor archive, in ArchiveLimits limits,
in int[] ranges /* flattened pairs; ≤ 2,048 pairs, else the planner merges */, in ParcelFileDescriptor
sink)`; `ArchiveExtractResult` Parcelable: `outcome` (`OK`, `REFUSED`, `NOT_SEEKABLE`, `UNSUPPORTED`,
`CORRUPT`, `LIMIT_EXCEEDED`, `INTERNAL`), `message`, `entriesWritten`, `bytesWritten`, `entriesFailed`.

Persistence (`FylzDatabase` v2 → v3, additive): `extract_plans`, `extract_plan_items` (§2.2), no
foreign keys (`put` deletes and re-inserts operations, which would cascade); `OperationsDao`
gains `putWithExtractPlan(op, plan)` (one transaction), `claimExtract(id, fromStates)`,
`updateItem(opId, index, item)`, `extractPlan(id)`, and deletes plans wherever it deletes operations
(`remove`, `clearFinished`, `enforceRecordLimit`); `OperationState` gains `INTERRUPTED` for a system
stop (rev 1 called it USER_CANCELLED). A real `onUpgrade` test (v2 database with an operation →
open at v3 → plan tables exist, data intact). Startup reconciliation: `OperationRunner.recover` finalises
`QUEUED` EXTRACT rows whose tagged work is absent or finished-without-success as `FAILED /
NEVER_RAN`, and `enqueueExtract` does the same when the work ends without `SUCCEEDED` and the row is
still `QUEUED`.

Shared helpers: `resolveTargetPlan`, `finalizeTarget`, `uniqueName`, `TargetPlan`, `verifyFile` move
from `FileOperationService` to `operations/TargetPlanning.kt` (internal class `TargetPlanner`), used
by both paths — no second replace-with-recycle implementation.

### 2.6 Verification, stated precisely

Per extracted file: (1) the engine's integrity check — ZIP CRC-32 and 7z CRC-32 (for entries that
carry one; `verified: CRC32` is per entry, not per format) surfaced as `Warning::CrcMismatch` →
`ARCHIVE_CRC_MISMATCH`; tar/cpio/ar/ISO carry none (`verified: size only`); (2) `bytes == written ==
declared` when known; (3) a SHA-256 over the drained bytes; (4) when `VerifySettings.shouldVerify
(destination)`, a re-read of the **staged** file after the pass compared with (3), before
`finalizeTarget` (copy's order). `OperationItem.sha256` holds (3) for a top-level **file** item; for
`IntoFolder` and directory items per-entry digests are kept in `extract_plan_items`' companion table
`extract_entry_digests(operation_id, ordinal, sha256)` only when verification is on, so §19 item 2's
"the journal carries digests" holds for every layout.

### 2.7 The legacy path shrinks to encrypted ZIPs

`ArchiveService.extractZip` stays for **encrypted ZIP files** only (whole-archive, password dialog)
until M3.9/M3.10. Its decision comes from `client.inspectArchive(stagedPfd, …)` on the **staged copy
it extracts** (libarchive lists PKWARE- and WinZip-AES-encrypted entries with central-directory
sizes; an archive with an encrypted central directory has no metadata and is refused); its size
checks use `ArchiveLimits.forInspection()` numbers; `createZip` takes its three numbers from
`ArchiveLimits`. **`ArchiveExtractionPolicy.kt` and its two tests are deleted** in M3.4c; the
overlay's `extractionDecision` reads are gone since M3.2c.

### 2.8 Fixtures and tests

Fixtures: `tree.tar.zst` and `tree.zip` (three levels, 40 files of known content, an implicit
directory, a file before its directory, a relative symlink, a hardlink); `dot-rooted.tar` (M3.3a);
`crc-bad.zip` (stored member, one byte flipped → `FAILED`), `inflate-bad.zip` (deflated member
corrupted → `FATAL`), `crc-bad.7z` (stored member → `WARN`), `solid-bad.7z` (two LZMA2 members in one
folder, the first corrupted); `big-stream.tar.zst` (a 2 MiB member followed by ten small ones, for the
early-exit test — larger than the 64 KiB read-ahead); `many-small.tar.zst` (10,000 members, the
entry-cap and `Here` tests); the hostile fixtures are reused.

Rust: `Selection::Ranges` exact match; early exit on `big-stream.tar.zst` (fd offset before the end);
`failed`-and-continue on `crc-bad.zip`; `ABORT` on `inflate-bad.zip`; `CrcMismatch` on `crc-bad.7z`;
`solid-bad.7z` fails both members of the folder, no hang; `evaluate_selection` (sizes over the
selection, structural over the archive, within-selection case collision refused, `tar -r` duplicate
allowed when one is selected); framer golden bytes (`app/src/test/resources/fixtures/archives/
tree.fzx`, regenerated with `FYLZ_WRITE_GOLDEN=1`); SIGPIPE-ignored write into a closed pipe → `Err`.

Kotlin (Robolectric; `androidx.work:work-testing:2.11.2` as a test dependency):
- `ExtractPlannerTest`: `All`/`Entries`/directory expansion to ranges; links counted; structural
  refusal blocks every layout for each hostile fixture (through the planner, not only the policy);
  entry-cap refusal of `many-small` without consent and acceptance with; the consent thresholds; FAT
  component sanitisation and post-sanitisation uniquification; `Here` threshold prompt; headless mode.
- `ExtractFrameReaderTest`: golden `.fzx`; every frame kind; each bounds violation → `PROTOCOL_ERROR`;
  out-of-plan and out-of-order ordinals; over-declared `DATA`.
- `ArchiveLimitsTest`: `forExtraction` per volume/free-space case; unknown free → 4 GiB; consent gates.
- `ArchiveExtractorTest` (the core proof; a fake `IDecoderService.Stub` whose `extractRanges` writes
  frames from fixture content into the sink before returning): `Here`/`IntoFolder`/`Entries` layouts
  byte-identical (`TreeFixtures.diffTrees`, hardlinks included), directories created on demand, staged
  names never visible, `finalizeTarget` with `KEEP_BOTH`/`REPLACE`/`SKIP` per item from the plan, a
  `FAIL` frame fails its item and the batch ends PARTIAL with the others intact, a CRC warning fails
  verification and deletes the file, an app-side write refusal (`FaultyDocumentsProvider.refuseCreate`
  / `throwAfterBytes`) fails only that entry, `ABORT` finalises completed items (PARTIAL) and retries
  once for ZIP (the fake counts calls) and not for tar, cancel mid-item (the fake honours a sink
  refusal) leaves nothing under a final name and waits for the transaction, progress past 2³¹ bytes
  with the bar at 0..1000, `VerifySettings.ALWAYS` re-reads and stores digests, `REFUSED` before any
  document exists (staged roots are created at the first `BEGIN`), `Here` with ~2,000 roots counting
  journal writes (linear) and destination listings (one).
- `TransferWorkerExtractTest` (`TestListenableWorkerBuilder` + `WorkManagerTestInitHelper` + a
  `WorkerFactory` injecting the fake client): `EXTRACT` → `doWork` succeeds; re-entry (the same
  operation run twice → the second run refuses the claim or resumes only unfinished items); a
  QUEUED row never run → reconciled `NEVER_RAN`; the COPY/MOVE gate no longer rejects EXTRACT.
- `FylzDatabaseUpgradeTest` (v2 → v3); `OperationRunnerRecoverTest` (EXTRACT with unfinished tagged
  work skipped; finished → staging deleted); `OperationRetryPolicyTest` (EXTRACT re-claims).
- The 5 GB mechanics at small scale: tiny inspection limits refuse; `forExtraction` with consent
  extracts through the whole Kotlin path; the fake asserts the limits it received.
- Golden test with the new fixtures; `DestinationChooserSheetTest`; `SessionCodec` untouched.

### 2.9 Device checks (`DEVICE_CHECKS.md` §19, "M3.4 — selective extract through the queue")

1. Extract here / to `<name>/` / to… for a ZIP, a 7z, a `tar.xz`, an ISO: progress bar and byte line
   in the notification; results byte-identical; nothing appears under `archive-work/` or
   `archive-entries/` during the run.
2. **The acceptance case, "Extract here":** a 5 GB 7z (one 5 GB member at the root and a thousand
   small ones) to internal storage with `VerifySettings.ALWAYS` and consent given: wall time, peak
   `:decoders` RSS, the journal item's `sha256`. Repeat to an exFAT card. On a vfat card the 5 GB
   member is a preflight problem; Skip extracts the rest.
3. Cancel mid-way: the staged folder disappears, the journal says cancelled, `:decoders` is idle and
   **alive** (no SIGPIPE death).
4. Kill the app mid-extract (`kill -9 <pid>` — `am kill` spares a foreground service): on relaunch the
   work re-runs and resumes unfinished items; the history shows one operation, not two.
5. `crc-bad.zip` (stored member): that file is a CRC mismatch, the rest extracts, PARTIAL. `inflate-bad.zip`:
   the pass aborts and is retried once for the remaining ordinals. `solid-bad.7z`: both members fail.
6. Extract selected entries from inside a browsed archive, including a folder, into another tab via
   the chooser; a link entry is reported as skipped; a hardlink arrives as a copy.
7. An encrypted ZIP takes the password path; "Extract selected" inside it is refused with the M3.9 message.
8. `adb logcat | grep avc`: no denials on the pipe.
9. A 10,000-file `tar.zst` folder: wall time (one pass); `many-entries` refused without consent.
10. Two extracts queued back to back: the second runs after the first (`APPEND_OR_REPLACE`); cancelling
    the first while the second is queued leaves the second `QUEUED → RUNNING`, not orphaned.

## 3. Sequencing and gates

Three commits, each green on the full gate (`./gradlew --no-daemon :app:testDebugUnitTest
:app:lintDebug :app:assembleDebug`; the `core` gate incl. `cargo +nightly fuzz build`; the
three-ABI `cargo ndk` build):

- **M3.4a (engine and FFI):** §2.5's `fylz-archive` and `fylz-ffi-android` changes, the framer, the
  golden `.fzx`, fixtures, Rust tests; the `archive_entries` fuzz target exercises `Selection::Ranges`.
- **M3.4b (queue):** AIDL `extractRanges` + `ArchiveExtractResult`; `DecoderService`; the extraction
  pool in `DecoderClient`; `ArchiveLimits.forInspection/forExtraction`; `ExtractPlanner` (with headless
  mode), plan tables + DAO + v3 upgrade + test; `TargetPlanning.kt` extraction from
  `FileOperationService`; `ArchiveExtractor`; `TransferWorker` EXTRACT; `OperationRunner.enqueueExtract`
  /`recover`/reconciliation; `RetryDispatcher.kt` out of `FylzAppShell` (ratchet lowered);
  `work-testing`; all non-UI tests of §2.8.
- **M3.4c (UI, legacy path, docs):** registry actions, `ExtractSheet`, `ExtractFlow` out of
  `FylzV1App` (ratchet lowered), the overlay rerouted, `extractZip` reduced to encrypted ZIPs on the
  Rust decision over the staged copy, **`ArchiveExtractionPolicy.kt` + tests deleted**,
  `ARCHITECTURE.md` (operation engine: EXTRACT, claim/reconcile lifecycle; archives: the extraction
  path, frames, limits and consent), `DEVICE_CHECKS.md` §19, `REVIEW_QUEUE.md` entry, PROGRESS row,
  PR #19.

`REVIEW_QUEUE.md` entry for M3.4 (log-and-continue):
1. Extraction limits: archive-size cap gone for seekable sources (`ArchiveSource` keeps it for
   staging); per-file cap is the volume's; the **total** cap is 4 GiB without consent and free space
   minus a margin with consent; unknown free space keeps 4 GiB. The exposure, verbatim: a user who
   consents lets a ratio-≤200 archive write to the margin (e.g. 100 MB → ~19.8 GB); without consent
   nothing changed. 2. The entry cap is 10,000 without consent, 200,000 with; the many-entries fixture
   is refused without consent. 3. Encrypted ZIPs keep zip4j until M3.9/M3.10; selective extraction
   from encrypted archives is refused. 4. `tar.*` costs two passes in `:decoders` (policy header pass
   + extraction) plus the browse-time listing. 5. Conflicts for top-level items only; FAT component
   sanitisation with a count; destinations outside the Fylz provider have no filesystem type, so the
   vfat rule cannot apply and `EFBIG` fails the entry at write time. 6. tar/cpio/ar/ISO have no
   per-entry checksum; ZIP/7z verification is per entry that carries one. 7. 7z CRC mismatches were
   swallowed before M3.4a. 8. Failure isolation scope: FAILED continues, FATAL aborts (ZIP inflate
   errors are FATAL); seekable formats retry once from the next ordinal, stream formats and solid 7z do
   not. 9. §4.4's read-only-descriptor contract: the sink pipe is writable (as M3.3), and structure now
   also crosses in frames. 10. Hardlinks are copies made after the pass; symlinks and special files
   are skipped and counted. 11. mtimes are not preserved. 12. EXTRACT retry re-claims the operation;
   copy retries as a new operation. 13. `work-testing` added as a test dependency. 14. The notification
   gains a bar and byte text for EXTRACT; COPY/MOVE keep "Item N of M". 15. Planning may decompress an
   unbrowsed `tar.*` in the UI process ("Reading archive…" with Cancel). 16. The overlay's Inspect
   and extract widens to every browsable format. 17. Nested archives extract via the materialised
   inner file (512 MiB cap, outer decision). 18. `structuralRefusal` is a second `evaluate` at listing
   time; the owner question on refuse-whole vs extract-minus-link stays open. 19. Headless planner
   mode for §C4/§C5; `allowLarge` is the scripted consent. 20. `Here` above 200 roots prompts for a folder.
   21. CRC surfacing is M3.8's groundwork.

## 4. Risks

- **Frame parser as attack surface**: a compromised `:decoders` speaks frames; every bound is checked
  and the plan decides names — the parser cannot be made to write outside planned targets. Tested with
  hostile frame streams.
- **Two passes for `tar.xz`** double a long extraction; the acceptance case is 7z. If §19 shows it
  hurts, `evaluate_selection` can take the listing's entries in chunks over Binder (≤ 1 MB each).
- **`EFBIG` on picker destinations** (no filesystem type): a > 4 GiB entry to a vfat card chosen through
  the system picker fails that entry at write time with a clear code; §19 item 2 covers the Fylz-
  provider case where preflight catches it.
- **`Result.retry()` re-runs and `recover`**: the claim transaction and the work tag are what keep one
  operation from running twice; §19 items 4 and 10 are the real-device proof.

## 5. Amendments this design makes to earlier designs

- Part 3: `Selection::Ranges`, `BlockSink`/`extract_blocks`, `Warning`, `ArchiveError::Failed`,
  `evaluate_selection`, `inspect_for_extraction`; `EntryMetadata.ordinal` (M3.3a) is load-bearing.
- M3.2: `ArchiveLimits.forInspection/forExtraction`; `ArchiveInspection.structuralRefusal`;
  `ArchiveEngineError.Failed/Cancelled`.
- M3.3: `DecoderClient` gains a dedicated extraction pool; `callStreaming` unchanged.
- Operation engine: `TargetPlanning.kt`, `OperationsDao.updateItem`/`claimExtract`/plan methods,
  `OperationState.INTERRUPTED`, `RetryDispatcher.kt`, schema v3.

## 6. Recorded upgrades (not in M3.4)

- `evaluate_selection` fed from the listing over Binder in chunks to remove the second `tar.*` pass.
- Resuming a stream-format pass after a fatal error by re-listing from the failure offset.
- Per-entry conflict resolution UI.

## 7. Review findings and disposition (rev 1 → rev 2)

Blockers, all adopted: (1) `done` deadlocked while the engine held the write end → the per-entry
pipe/callback design is replaced by one framed pipe (§2.3), which also removes findings 2, 3, 12a–c,
13 and 32b; (11) the pre-written operation id had no lifecycle → claim transaction, work tag,
`recover` skipping unfinished tagged work, reconciliation of never-run QUEUED rows, `INTERRUPTED` for
system stops, retry re-claims (§2.3 steps 1, 8, 9; §2.5); (26) hostile refusal silently reversed →
structural rules over the whole archive refuse everything (§2.2 step 2, §2.4), entry cap 10,000
without consent, hostile fixtures tested through the selection path.

Should-fix, adopted: (4) isolation scope stated, FATAL retried once for seekable formats, `solid-bad.7z`
fixture; (5) ordinal = raw header index on `EntryMetadata` (sent to M3.3a), byte-exact path check on
`BEGIN`, `dot-rooted.tar`; (8) bar scaled 0..1000, throttled; (12d–h) app-side write errors fail the
entry and the pass continues, cleanup waits for the transaction, items finalised after the pass,
staged roots created at the first `BEGIN`, PARTIAL semantics stated; (14) plan tables reworked,
per-item policies/overrides, catalog key, single-transaction DAO, deletes with operations, real
upgrade test; (15) `updateItem`, one destination listing, the `Here` threshold, a 2,000-root test;
(16) vfat only, nested entries as preflight problems, every component sanitised, `EFBIG` stated;
(17) copy's verify-then-finalise order, re-reads after the pass, per-entry digests table; (18) action
gating by `locationKind`, planner enforces per-entry facts, `ExtractFlow` hosts its sheets,
notification permission; (19) `TargetPlanning.kt`, `RetryDispatcher.kt`, retry semantics logged;
(20) the app's `ArchiveSource` instance, pinned handle reused, nested limitation stated; (21) ranges
instead of an ordinal array; (27) consent model and the exact exposure; (28) hardlinks copied after
the pass; (31) `WorkerFactory`/`WorkManagerTestInitHelper`, cancel via sink refusal, the listed tests;
(32) REVIEW_QUEUE items added incl. §C4/§C5 headless mode; (33) framed pipe adopted as primary.

Nits, adopted: (6) `Paths` wording; (7) structured `Warning`; (9) encrypted 7z read is M3's table via
the 7-Zip pack; (10) `kill -9`; (22) naming rules (octet-stream MIME, created-name check, uniquify
after sanitisation, `All` = the tree's ordinals); (23) inspect the staged copy, refuse encrypted
central directories; (24) dedicated extraction pool; (25) SIGPIPE at FFI init (M3.3a); (29) "Reading
archive…" with Cancel; (30) M3.8 groundwork logged; (34) plan in the journal kept, with ranges and
per-item rows. Not adopted: none.
