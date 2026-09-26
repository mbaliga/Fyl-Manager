# M3.4 design: selective extract through the transfer queue

**Rev 3** (2026-09-25), after the focused re-review of rev 2 (commit `7b25ed2`); §7 lists rev 1 → 2
→ 3 dispositions. Design for MASTER_PLAN M3.4, written from `SURVEY-M34-SELECTIVE-EXTRACT.md` (facts at
HEAD `bd00911`, file:line) on top of `DESIGN-M32-SEEKABLE-PFD.md` rev 2 (as landed in `a418a9c`) and
`DESIGN-M33-ARCHIVE-BROWSING.md` rev 2 **plus the five M3.3a/b amendments sent during its
implementation** (§1, "M3.3 contract"). M3.4 is implemented after M3.3. Paths are relative to the
repository root. `UNVERIFIED` marks a claim the implementing agent must confirm and record.

The plan's text and the acceptance lines it must meet:

> **M3.4 Selective extract.** Actions: Extract here; Extract to `<name>/`; Extract to…; Extract
> selected entries. Every extract runs through the transfer queue (`TransferWorker`), so it gets
> progress, cancel, staging, verification and conflict handling.
>
> A 5 GB 7z extracts through the queue with verification. Hostile fixtures (zip-slip, bombs, symlink
> escapes, oversized headers) are refused.

## 0. Scope

**In:** (a) four extract actions in the registry; (b) an EXTRACT operation carried by `TransferWorker`
with the journal, staging, conflict, verification, progress, cancel, recovery and retry behaviour copy
has, and a defined lifecycle for an operation written before it is enqueued; (c) **one** extraction
pass in a **dedicated isolated instance** of the decoder service, streaming every selected entry through
a **single framed pipe** into staged destinations (no cache tree, no archive staging for seekable
sources, no `ArchiveEntryCache` materialisation, no callback Binder); (d) extraction limits that are
destination-aware **with explicit consent** above the old totals; (e) the Kotlin `ArchiveExtractionPolicy`
deleted; (f) the `ArchiveToolsOverlay` extract path routed through the queue; (g) CRC verification
surfaced from the engine as structured failure kinds; (h) a headless planner mode for Addendum §C4/§C5.

**Out (and where it goes):** passwords (M3.9; encrypted ZIP keeps the zip4j whole-archive path, §2.7;
selective extraction from encrypted archives refused); encrypted 7z read (the 7-Zip pack per M3's
table; M3.9 gives it the prompt); removing zip4j/commons-compress (M3.10); per-entry conflict UI;
pause/resume; split/multi-volume; mtime preservation (SAF has no setter; logged); resuming a
stream-format pass after an engine fatal (§2.3 step 7); fixing the same chain-poisoning cancel for
COPY/MOVE (pre-existing; logged, §2.3 step 8).

## 1. Constraints (short; the survey has the evidence)

- The queue is COPY/MOVE-shaped end to end (type gate; `Data` ≤ 10,240 bytes; `recover` filter and
  recovery covering PREFLIGHT/RUNNING/PAUSED only; `OperationRetryPolicy` — its `retryableStates` are
  FAILED/PARTIAL/CANCELLED, and its `plan` returns a `Transfer` for any retryable type; `FylzAppShell`'s
  retry `when`). WorkManager 2.11.2 **cancels or fails every dependent** of a cancelled/failed request in
  an `APPEND_OR_REPLACE` chain (`CancelWorkRunnable.iterativelyCancelWorkAndDependents`,
  `WorkerWrapper.iterativelyFailWorkAndDependents`), so today a cancelled or `Result.failure` copy
  silently kills every transfer queued behind it. `ListenableWorker.isStopped` is true for a user cancel
  too; `getStopReason() == STOP_REASON_CANCELLED_BY_APP` (value 1) tells them apart.
  `OperationState.PAUSED_BY_SYSTEM` exists, unused, documented for a system stop; `INTERRUPTED` exists
  (terminal, retryable). `OperationsDao.put` deletes and re-inserts the operation and every item row;
  `OperationJournal.refreshOperations()` reloads every operation with all items on each write;
  `remove`/`clearFinished`/`enforceRecordLimit` delete operations. No `onUpgrade` test exists.
- Copy's unit of work is one top-level item, staged under `.fylz-part-<op>-<idx>-<name>`, verified by
  re-reading the **staged** file, then finalised by `finalizeTarget` (replace-with-recycle). Its
  helpers (`resolveTargetPlan`, `finalizeTarget`, `uniqueName`, `TargetPlan`) are private to
  `FileOperationService` and each call `findChild` (one child listing). Conflicts are resolved before
  enqueue for top-level items; `ConflictedItem` carries a `DocNode` and hashes on demand.
- Rust `extract` writes blocks from memory into an fd (`lib.rs:779-782`); `stream_current_entry_data`
  is tied to `&mut File`, writes zeros for sparse holes, and returns on `check` failure (`:761`);
  `Reader::check` is shared by `next_header` and data reads; the `./` root is `continue`d before the
  entry is counted (`:965`). ZIP CRC and size mismatches are `ARCHIVE_FAILED` **returned with the
  final block** (`zip.c:3474-3486`, errno `ARCHIVE_ERRNO_MISC` for both); ZIP inflate errors are
  `ARCHIVE_FATAL` (sticky); 7z CRC mismatch is `ARCHIVE_WARN` with the final block (`7zip.c:1136-1153`);
  a 7z LZMA error is `FAILED` and the automatic skip of the next header re-decodes the broken stream, so
  `next_header` returns `FAILED` for a **valid** following header (`archive_read.c:650-664, 719`); a
  failed header enters `DATA_RECOVERY`, and reading data there makes the archive `FATAL`
  (`archive_check_magic.c:32-46`). `policy::evaluate` rule order: archive size → entry count (`>`, so
  exactly 10,000 passes) → per entry path/dup/link/unknown-size/per-file → total → archive ratio;
  `validate_path` refuses `.` segments (M3.3a changes this, §1 below).
- A `ParcelFileDescriptor` passed over Binder shares its **open file description** (the offset) with
  the sender; `pfd.dup()` too. libarchive's fd reader uses `read` + `lseek(SEEK_CUR)`. Two concurrent
  engine calls on dups of one pfd interleave silently.
- `DecoderClient` is one connection: a browse call's timeout `dropConnection()`s and the platform reaps
  `:decoders`. `bindIsolatedService(intent, flags, instanceName, executor, conn)` (API 29+; minSdk 31)
  binds a **separate process instance** of an `isolatedProcess` service.
- `FylzV1App.kt` (2,287) and `FylzAppShell.kt` (112) sit on their ratchets. `VolumeInfo.filesystemType`
  is null outside the Fylz provider; `FAT_FAMILY` includes exFAT. `NotificationCompat.setProgress` takes
  `int`. No `work-testing` dependency.
- **M3.3 contract** (DESIGN-M33 rev 2 + the amendments sent to its implementer; the M3.3 report must
  confirm each, and M3.4a starts by verifying them in code): (i) `EntryMetadata.ordinal` = the 0-based
  index of the raw `archive_read_next_header` call, counting the `./` root, links, `Other` and failed
  headers, from ONE shared counter used by listing and `extract_entry_at`; (ii) SIGPIPE ignored once at
  FFI init; (iii) every `:decoders` call opens its **own** descriptor (the handle pins the `Uri` or the
  staged `File`, never a pfd); (iv) the listing summary is persisted next to the `.fzl`
  (`<key>.summary.json`) and carries `structural_refusal: Option<String>` (a second `evaluate` with
  `max_archive_bytes`/`max_file_bytes`/`max_total_uncompressed_bytes = u64::MAX`, `max_entries =
  usize::MAX`, `max_compression_ratio = f64::INFINITY`, so only path, duplicate-key, link, unknown-size
  and sum-overflow rules can fire); a `.fzl` without a readable summary is re-listed; (v) `validate_path`
  and `normalized_path_key` treat `.` segments and a leading `./` as no-ops (`..` still refused), so
  `tar -C d -cf x.tar .` archives are no longer refused whole (a logged deviation from Kotlin parity).

## 2. Decisions

### 2.1 The four actions, through the registry

| Action id | Placement | `visibleWhen` / `enabledWhen` | Does |
|---|---|---|---|
| `fylz.extract` | `SelectionBar(90)` | visible `HAS_SELECTION && locationKind != ARCHIVE`; enabled `selection.size == 1 && BrowsableArchiveFormats.matches(name)` | opens the **Extract sheet** (`MenuId.EXTRACT`, `ui/actions/ExtractSheet.kt`) |
| `fylz.extract.here` | `Menu(MenuId.EXTRACT, 10)` | as `fylz.extract` | destination `tab.current.uri`; items = the selection's root entries |
| `fylz.extract.folder` | `Menu(MenuId.EXTRACT, 20)` | as `fylz.extract` | destination `tab.current.uri`; one item, the folder `extractionFolderBaseName(archive)` |
| `fylz.extract.to` | `Menu(MenuId.EXTRACT, 30)` | as `fylz.extract` | `DestinationChooserSheet` (archive tabs filtered; "Other location…"), then as `.folder` |
| `fylz.extract.selected` | `SelectionBar(90)` | visible `HAS_SELECTION && locationKind == ARCHIVE`; enabled `selection.isNotEmpty()` | `DestinationChooserSheet`, then items = the selected entries |

Exactly one slot-90 action renders (mutually exclusive by `locationKind`). Per-entry facts (links,
encryption) are enforced by the planner. Golden test: oracle rules + two fixtures. `NoHardCodedMenusTest`
satisfied by `ui/actions/`.

`ui/actions/ExtractFlow.kt` hosts the flow state, its **own** `PreflightSheet` and `ConflictSheet`
instances (fed `ConflictedItem`s built from a new descriptor-only constructor — name, size, isDirectory
— with hash-on-demand disabled for archive items), a "Reading archive…" dialog with Cancel for planning,
and the confirm sheet (§2.2 step 7). `FylzV1App` keeps one line per action, calls
`ensureNotificationPermissionRequested` for extract too; net negative; ratchet lowered. The overlay's
extract calls the same flow (its "Inspect and extract ZIP" widens to every browsable format; logged).

### 2.2 Planning: selection, rules, preflight, conflicts, consent — before enqueue

`ExtractRequest(archive, selection: All | Entries(ids), destinationFolder, layout: Here | IntoFolder(name))`;
`ExtractPlanner.plan(request, ui: PlannerUi | Headless(conflictPolicy, allowLarge, largeHereAsFolder = true))`.

1. **Listing.** `ArchiveCatalog.open(archive)` (single-flight; disk-first; the summary now loads with
   it). "Reading archive…" with Cancel covers an unbrowsed `tar.*`. The handle's **source** (Uri or
   staged file) and catalog key go into the plan. Encrypted entries: ZIP + `All` → legacy path (§2.7);
   otherwise refused (M3.9). `partial` → refused ("This archive is damaged; its readable part can be
   browsed, not extracted yet").
2. **Structural verdict, fail closed.** `summary.structuralRefusal != null` → refuse everything with
   that reason; **summary or verdict unavailable → refuse** ("could not verify this archive; refresh and
   try again"). Structural = paths (`..`, absolute, drive letters, depth, length, control characters),
   **duplicate keys** (kept as a whole-archive refusal, Kotlin parity: a case collision is a FAT
   overwrite trick; `tar -r` archives therefore browse but do not extract — logged, and REVIEW_QUEUE's
   open owner question on refuse-whole vs extract-minus is unchanged), escaping links, unknown sizes.
   `zip-slip.zip`, `absolute-path.zip`, `symlink-escape.tar` are refused here whatever the selection.
3. **Selection expansion** over the `ArchiveTree`: `All` → the tree's ordinals; `Entries` → files'
   ordinals and directories' descendants. `Symlink`/`Other` → skipped and counted. `Hardlink` → the
   plan maps its **target's ordinal** to one more destination path (the link's); a target that is
   unselected or belongs to a skipped item is still read (its ordinal enters the ranges) and written
   only under the link's path. Result: a **bitmap of ordinals** (≤ 200,000 bits ≈ 25 KiB) and, per
   top-level item, its root path.
4. **Top-level items:** `Here` → one per root entry; `IntoFolder` → one; `Entries` → one per selected
   entry (unique names by construction: the tree's children are unique and case-only variants are a
   structural duplicate). `expectedBytes` from the tree. `Here` with more than `HERE_ROOT_THRESHOLD =
   200` roots asks "Extract 3,412 items directly here, or into `photos/`?" with **`photos/`
   preselected**; headless mode uses `largeHereAsFolder`.
5. **Preflight** via the existing machinery for the top-level items, plus per-entry rules: when
   `filesystemType == "vfat"` (only vfat has the 4 GiB − 1 limit), every selected entry over it is a
   preflight problem whose Skip removes its ordinal; on any FAT-family volume every **path component**
   is sanitised (`PreflightPolicy.sanitizedName`), post-sanitisation collisions uniquified, count
   reported. Picker destinations have no `filesystemType`: the vfat rule cannot apply and `EFBIG` fails
   that entry at write time (§2.3 step 4; logged).
6. **Conflicts** for the top-level items: **one** destination listing at planning into a case-aware
   `NameIndex` feeds `findConflicts`, the sheet and Keep-both probing; per-item resolutions and name
   overrides go into `extract_plan_items`. Fallback `SKIP`. Headless: the request's policy.
7. **Limits and consent** (§2.4): the confirm sheet shows entries, expanded size and ratio; an explicit
   tick is required when expanded size > 4 GiB or entries > 10,000; the plan records `consent`.
   Headless refuses above the thresholds unless `allowLarge`.

Persisted **atomically** (§2.5 DAO): `FileOperation(EXTRACT, QUEUED, items…)`, `extract_plans(operation_id,
archive_uri, staged_path?, catalog_key, layout, folder_name, ordinals BLOB (bitmap), limits JSON,
consent BOOL, sanitize BOOL, cancel_requested BOOL)`, `extract_plan_items(operation_id, item_index,
root_path, requested_name, conflict_policy, name_override)`, `extract_entry_digests(operation_id,
ordinal, sha256)` (filled at run time when verifying). `Data` = `KEY_TYPE = EXTRACT`, `KEY_OPERATION_ID`.
`OperationRunner.enqueueExtract(id)` mirrors `enqueueTransfer` (unique `"fylz-transfers"`,
`APPEND_OR_REPLACE`), **tags** the request `op:<id>`, and — because `recover` at startup and
`enqueueUniqueWork` are both asynchronous — treats a QUEUED row with no tagged work as `NEVER_RAN` only
after the tag lookup says so (the window between plan write and enqueue is closed by writing the tag
into the plan row first and enqueuing second; reconciliation ignores rows younger than 60 s).

### 2.3 One pass in an isolated extraction instance, one framed pipe, drained into staged targets

`TransferWorker.doWork` accepts `EXTRACT` and calls `ArchiveExtractor.run(opId, stopReason)`:

1. **Claim** — one SQLite transaction: `QUEUED | PAUSED_BY_SYSTEM | NEEDS_ATTENTION(PROCESS_INTERRUPTED)
   → RUNNING`; a `RUNNING` row is claimable only when the tag lookup (outside the transaction) says its
   work is not running (a stale row from a crash); otherwise the run **returns `Result.success()`**
   (never `failure`, §step 8) and logs. After the claim, outside the transaction: recompute the total cap
   (`min(plan total, current free − margin)`, §2.4), re-check the catalog key (`FAILED /
   ARCHIVE_CHANGED` if the archive changed), delete recorded staging of its own items (SAF calls), skip
   `SUCCEEDED` items, build the run-time `NameIndex` from **one** destination listing.
2. **Source:** the plan's Uri (Direct) or staged file (Staged) — the archive is **opened afresh for this
   call** (`openFileDescriptor(uri, "r")` / `ParcelFileDescriptor.open(file)`), never a pfd shared with
   browsing (M3.3 contract iii). The app's `ArchiveSource` instance (2 GiB staging cap) does any staging.
   Nested archives resolve through M3.3's provider (512 MiB cap, outer decision; logged).
3. **One call on a dedicated instance:** `DecoderClient` gains a second connection bound with
   `bindIsolatedService(intent, BIND_AUTO_CREATE, "extract", executor, conn)` → process
   `:decoders:extract`, so a browse call's timeout never reaps a running extraction and vice versa; it
   is unbound when the extraction ends (no idle policy needed). The call is
   `IDecoderService.extractRanges(archive, limits, ordinalsBitmap, sink)` through `callStreaming`
   (client-owned pipe, closes its write end in the transaction job's `finally`, Robolectric drain rule
   in the `InputStream` wrapper — the frame reader never treats −1 as final itself), running on
   `Dispatchers.IO` (elastic; a fixed pool would starve the next queued extraction while an abandoned
   transaction holds a thread). **No transparent retry** for this call: a transport loss (`Failed`,
   `TimedOut`, EOF without a terminal frame) is handled by step 7. `DecoderService` converts the bitmap
   to exact `Selection::Ranges` in-process (no Binder-size cap to merge around) and calls
   `archive_extract_ranges(fd, ranges, limits, sink_fd)`: the policy header pass (§2.4), then the
   extraction pass writing frames.
4. **Frames** (little-endian; the `ArchiveExtractResult` returned by the call is the **authority** for the
   outcome, frames are data):

   ```
   MAGIC  "FZX1"
   BEGIN  0x01 ordinal:u32 declared:i64(-1 unknown) kind:u8(1 file,2 dir,3 symlink,4 hardlink,5 other) path_len:u32(≤65536) raw_path
   DATA   0x02 ordinal:u32 len:u32(1..=1 MiB; the framer splits larger libarchive blocks) bytes
   END    0x03 ordinal:u32 bytes:u64 warn:u8(0 none,1 other) msg_len:u16 msg      (msg_len always present)
   FAIL   0x04 ordinal:u32 kind:u8(1 crc,2 size,3 decode,0 other) msg_len:u16 msg (may follow BEGIN+DATA, or stand alone for a header-level failure)
   DONE   0x05 entries:u32 bytes:u64 failed:u32
   ABORT  0x06 msg_len:u16 msg
   ```

   Reader rules (untrusted input; `ExtractFrameReader`): a **non-OK result with an empty stream** (no
   magic) is not a protocol error — REFUSED/UNSUPPORTED/NOT_SEEKABLE/LIMIT_EXCEEDED produce no frames;
   with an OK or CORRUPT result the stream must start with the magic; ordinals must be in the plan and
   strictly increasing; one `BEGIN` per ordinal; `DATA` only between its `BEGIN` and its `END`/`FAIL`;
   cumulative bytes ≤ declared when known and ≤ the plan total; nothing after `DONE`/`ABORT`; EOF without
   `DONE`/`ABORT`, or mid-frame, is `TRANSPORT_LOSS` (handled like `ABORT`: completed entries stand, the
   open entry fails); a bounds violation is `PROTOCOL_ERROR` (the call is abandoned, the connection
   dropped, the operation `FAILED / PROTOCOL_ERROR`). `warn = 1` (`Warning::Other`, e.g. "Pathname
   cannot be converted") is logged, never fatal. CRC mismatches are `FAIL kind = 1`: Rust classifies
   them (ZIP: `ARCHIVE_FAILED` with a message containing "bad CRC" → crc, else size; 7z: the data
   `WARN` is only ever the CRC → the entry is **failed** with kind crc, not ended with a warning).
5. **Demux → staged targets** (a structured child coroutine of the extractor on `Dispatchers.IO`, never
   Main): `BEGIN` → compare the raw path byte-exact with the plan's for that ordinal (`PROTOCOL_ERROR`
   otherwise); map to the planned destination path(s) (item root + relative path, sanitised per plan;
   a hardlink target may have two); create the item's staged root at its first `BEGIN`
   (`stagingName(opId, idx, name)`, `stagingUri` recorded before the first byte — so `REFUSED` really
   leaves nothing behind); create directories on demand with final names; create files as
   `application/octet-stream`; check the created name equals the requested one (uniquify via the
   `NameIndex` otherwise); open `openOutputStream(target, "w")`. `DATA` → write and feed a SHA-256;
   while inside a provider call the demuxer sets a `busy` flag the watchdog honours (§step 6). `END` →
   `bytes == written == declared` (when known) else `SIZE_MISMATCH`. `FAIL` → delete the partial
   document, `ARCHIVE_CRC_MISMATCH` / `SIZE_MISMATCH` / `ARCHIVE_ENTRY_UNREADABLE`. An app-side write
   error (`ENOSPC`, `EFBIG`, a provider exception) → the demuxer **discards the rest of that entry's
   frames** and fails the entry (`INSUFFICIENT_SPACE`, `FILE_TOO_LARGE`, `PERMISSION_DENIED`); the pass
   continues. A failed entry fails its item (staged root deleted at the end, `errorCode` = first
   failure); other items proceed. Journal writes go through a single-row `OperationsDao.updateItem`
   and the worker's journal instance **does not `refreshOperations()` per write** (a throttled refresh
   every 250 ms / 8 MiB and at the end — the per-write reload of every operation is quadratic).
6. **Liveness:** the watchdog counts sink bytes read, the archive descriptor's offset, **and demuxer
   progress**, and is paused while `busy` is set (a slow provider `write`/`close` — cloud, OTG, SD card
   fsync — must not look like a hung engine). During a `tar.*` header pass nothing is written and the
   offset advances; the notification says "Reading archive…" until the first `BEGIN`.
7. **After the pass** (also after `TRANSPORT_LOSS`/`ABORT`): completed entries stand. If the result is
   `CORRUPT` (engine fatal) or the stream was lost, the extractor **re-issues** `extractRanges` **once**
   for the ordinals after the last completed one, with the limits reduced by what was already written
   (bytes and entries) — for every format (a seekable ZIP/7z/ISO resumes cheaply; a stream format
   re-reads from the start, which is the only way to resume it; the retry is skipped when the outcome
   was `LIMIT_EXCEEDED`). Then hardlinks that were mapped to an already-written target are copied from
   it; items are verified in copy's order — staged file re-read when `VerifySettings.shouldVerify` —
   then `finalizeTarget` with the item's conflict plan; re-reads run after the pass and count as
   activity. Items with no completed entry after the retry are `FAILED / ARCHIVE_FATAL`; the operation
   ends SUCCEEDED / PARTIAL / FAILED.
8. **Cancel and stops.** `OperationRunner.cancel(id)` for EXTRACT sets `extract_plans.cancel_requested`
   (not `WorkManager.cancelWorkById`, which would cancel every transfer queued behind it — the chain
   problem is pre-existing for COPY/MOVE and logged, not fixed here); the notification's Cancel action
   is a `PendingIntent` to a small `BroadcastReceiver` that does the same. The demuxer polls the flag
   between frames and on timer; on cancel it stops reading, `callStreaming` closes the read end (EPIPE
   for the engine; and Rust polls the sink fd for `POLLERR|POLLHUP` every 64 headers during the header
   pass, so a cancel during a long `tar.xz` header walk also ends the call), the extractor **joins the
   demuxer** (structured child), waits up to 5 s for the transaction, then **unbinds the extraction
   instance** (which reaps `:decoders:extract` if it is still running) before deleting staged roots and
   writing `CANCELLED / USER_CANCELLED`. The worker then returns `Result.success()`. A **system stop**
   (`isStopped && stopReason != STOP_REASON_CANCELLED_BY_APP`) leaves items `PAUSED_BY_SYSTEM`, keeps
   staging, and returns `Result.retry()`; the re-run claims and resumes unfinished items. **Every**
   outcome already written to the journal (SUCCEEDED, PARTIAL, FAILED, CANCELLED) makes the worker return
   `Result.success()`, so a queued transfer behind it is never poisoned.
9. **Recovery, reconciliation, retry.** `OperationRunner.recover(workStatus: (tag) -> WorkStatus?)`
   (injected lookup): EXTRACT operations **with a plan** whose tagged work is running are left alone;
   `RUNNING`/`PAUSED_BY_SYSTEM` rows whose work is gone become `NEEDS_ATTENTION / PROCESS_INTERRUPTED`
   with recorded staging deleted (a **conditional** DAO update, not the stale `journal.put(copy)`
   snapshot); `QUEUED` rows older than 60 s with no tagged work become `FAILED / NEVER_RAN` (a cancel
   while queued writes `CANCELLED` directly). Legacy EXTRACT rows (the encrypted-ZIP path, no plan) are
   excluded from all of this and from retry. `OperationRetryPolicy`: EXTRACT is retryable when a plan
   exists, with a new plan type `ReclaimExtract(opId)`; the retry moves `FAILED/PARTIAL/CANCELLED →
   QUEUED` (items not `SUCCEEDED` → `QUEUED`) in one transaction and re-enqueues; `RetryDispatcher.kt`
   takes the dispatch out of `FylzAppShell` (ratchet lowered).

### 2.4 Policy scope, destination-aware limits, and consent

**Structural rules over the whole archive** come from the persisted listing summary (§2.2 step 2; fail
closed). **Size rules over the selection** run in Rust at the start of `archive_extract_ranges`: a
header pass collects `EntryMetadata` for the selected ordinals (cheap for ZIP/7z/ISO; a decompression
pass for `tar.*`, recorded) and `policy::evaluate_selection(archive_bytes, &selected, &limits)` applies
`max_file_bytes`, `max_total_uncompressed_bytes`, `max_entries`, the within-selection duplicate rule
(defence in depth; structurally impossible after step 2) and the ratio `Σ selected / archive_bytes`;
`extract_blocks` additionally re-validates each selected entry's path and link at extraction time
(defence in depth against a stale plan). Refusal → `REFUSED` before any frame. The Kotlin planner is
the gate for structural rules; Rust is the gate for size rules; both are tested.

`ArchiveLimits.forExtraction(volume: VolumeInfo?, consent: Boolean)`:
- `maxArchiveBytes = HARD_CEILING (256 GiB)` (not staged; `ArchiveSource` keeps 2 GiB for staging).
- `maxFileBytes = if (filesystemType == "vfat") 4 GiB − 1 else HARD_CEILING` (runtime cap stays
  `min(limit, declared)`).
- `maxTotalUncompressedBytes`: free known and `consent` → `free − max(5 %, LOW_STORAGE_THRESHOLD)`,
  **recomputed at claim** against current free space; free known, no consent → `min(4 GiB, that)`;
  free unknown → **4 GiB**. The ratio rule (200) can never be consented past.
- `maxEntries = if (consent) 200,000 else 10,000`; ratio 200, depth 64, name 255 unchanged.

What changes, exactly: with consent, a ratio-≤200 archive may write to the free-space margin (100 MB →
~19.8 GB) after the user has seen those numbers; without consent, the old caps hold; with least
knowledge, the old cap holds. Hostile fixtures: `zip-slip`, `absolute-path`, `symlink-escape` are
structural (refused whole); `many-entries.tar.zst` (10,001) is refused without consent and for any
selection over 10,000 — not for a sub-selection, which is the intended meaning of a selection; a
**bomb fixture** (ratio > 200, generated at test time) and an **oversized-header fixture** (a 300-byte
name and a depth-70 path, generated at test time) are refused structurally / by the ratio rule in every
mode. Logged verbatim in REVIEW_QUEUE.

### 2.5 Engine, FFI, IPC and persistence amendments

`fylz-archive`:
- The **ordinal counter** sits above the root `continue` in the shared header loop (M3.3 contract i);
  one function serves listing, `extract_entry_at` and `extract_blocks`; a cross-test asserts listing
  ordinals equal `BEGIN` ordinals for every fixture.
- `stream_current_entry_data` is generalised over a writer closure (`&mut dyn FnMut(&[u8]) ->
  io::Result<()>`, zeros included) and distinguishes `Failed` from `Fatal`; `extract()` becomes an
  adapter over `extract_blocks()` whose `failed()` returns `Err` (part 3's abort semantics preserved);
  `extract_entry_at` is the one-range case.
- `trait BlockSink { begin(&EntryMetadata) -> Result<bool>; write(ordinal, &[u8]); end(ordinal, bytes,
  Option<Warning>); failed(ordinal, kind: FailKind, msg) }`, `enum Warning { Other(String) }`,
  `enum FailKind { Crc, Size, Decode, Other }`, `Selection::Ranges(Vec<(u32, u32)>)`,
  `extract_blocks(fd, &Selection, &ExtractLimits, &mut dyn BlockSink)`.
- **Header-level `FAILED` rule:** `next_header` returning `ARCHIVE_FAILED` counts the ordinal, emits
  `failed(ordinal, Decode)` if that ordinal is selected, reads **no data** (a data read in
  `DATA_RECOVERY` turns the archive fatal), and continues; the collateral loss of a valid header after a
  broken solid-7z folder is accepted and recorded. `ARCHIVE_FATAL` → `Fatal` → `ABORT`. `Reader::check`
  is split into `check_header` and `check_data` so listing/inspect keep their current mapping.
- `policy::evaluate_selection`, `inspect_for_extraction`; `structural_refusal` is M3.3's (contract iv).

`fylz-ffi-android`: `archive_extract_ranges(fd, ranges, limits, sink_fd)` with the framer as the
`BlockSink`; `ArchiveEngineError` gains `Failed { detail }` and `Cancelled`; the Rust side polls the
sink fd (`poll`, zero timeout) every 64 headers for `POLLERR|POLLHUP` and returns `Cancelled`.

AIDL: `ArchiveExtractResult extractRanges(in ParcelFileDescriptor archive, in ArchiveLimits limits,
in byte[] ordinalsBitmap, in ParcelFileDescriptor sink)`; `ArchiveExtractResult`: `outcome` (`OK`,
`REFUSED`, `NOT_SEEKABLE`, `UNSUPPORTED`, `CORRUPT`, `LIMIT_EXCEEDED`, `CANCELLED`, `INTERNAL`),
`message`, `entriesWritten`, `bytesWritten`, `entriesFailed`.

`DecoderClient`: a second, on-demand connection via `bindIsolatedService(…, "extract", …)`, exposed as
`extraction(): DecoderConnection` with the same generation/liveness machinery, no idle timer (unbound
when the extraction ends), no transparent retry; `callStreaming` gains the `busy` pause and a
`progress()` hook the demuxer calls (M3.3's `callStreaming` contract is extended, not changed).

Persistence (`FylzDatabase` v2 → v3, additive): `extract_plans`, `extract_plan_items`,
`extract_entry_digests`; no foreign keys; `OperationsDao`: `putWithExtractPlan`, `claimExtract`,
`updateItem`, `updateOperationStateIf(id, from, to)`, `extractPlan(id)`, `setCancelRequested(id)`,
`retryExtract(id)`; plan rows deleted wherever operations are (`remove`, `clearFinished`,
`enforceRecordLimit`). A real `onUpgrade` test (v2 database with an operation → v3 → tables exist,
data intact). Shared helpers: `operations/TargetPlanning.kt` (`TargetPlanner(nameIndex)`:
`resolveTargetPlan`, `finalizeTarget`, `uniqueName`, `TargetPlan`) used by both paths; `verifyFile`
stays in `FileOperationService` (no extraction caller).

### 2.6 Verification, stated precisely

Per extracted file: (1) the engine's integrity check — ZIP and 7z CRC-32 for entries that carry one
(`FAIL kind = crc` → `ARCHIVE_CRC_MISMATCH`); tar/cpio/ar/ISO carry none; (2) `bytes == written ==
declared` when known; (3) SHA-256 over the drained bytes; (4) when `VerifySettings.shouldVerify
(destination)`, a re-read of the **staged** file after the pass compared with (3), before `finalizeTarget`.
`OperationItem.sha256` holds (3) for a top-level file item; `extract_entry_digests` holds every entry's
digest when verification is on, so the "journal carries digests" claim holds for every layout.

### 2.7 The legacy path shrinks to encrypted ZIPs

`ArchiveService.extractZip` stays for **encrypted ZIP files** only until M3.9/M3.10; its decision comes
from `client.inspectArchive(stagedPfd, …)` over the staged copy it extracts (libarchive lists PKWARE-
and WinZip-AES-encrypted entries with sizes; an encrypted central directory has no metadata → refused);
sizes from `ArchiveLimits.forInspection()`; `createZip` takes its numbers from `ArchiveLimits`.
**`ArchiveExtractionPolicy.kt` and its two tests are deleted** in M3.4c. Its journal rows carry no plan
and are excluded from recovery/retry (§2.3 step 9).

### 2.8 Fixtures and tests

Fixtures: `tree.tar.zst`/`tree.zip` (three levels, 40 known files, implicit directory, file before its
directory, relative symlink, hardlink whose target is in another top-level folder); `dot-rooted.tar`
(M3.3a); `crc-bad.zip` (stored member, one byte flipped → FAILED/crc); `inflate-bad.zip` (deflated member
corrupted → FATAL); `crc-bad.7z` (stored member → 7z WARN → FAIL/crc); `solid-bad.7z` (two LZMA2
members in one folder, first corrupted → both fail, next folder succeeds, no hang); `big-stream.tar.zst`
(a 2 MiB member then ten small ones, for early exit); `many-entries.tar.zst` (10,001, refused without
consent) and `many-small-10000.tar.zst` (exactly 10,000, allowed); generated at test time: a ratio bomb
(1 MiB → 300 MiB of zeros) and an oversized-header archive (300-byte name, depth 70).

Rust: `Selection::Ranges` exact match and early exit (fd offset before the end on `big-stream`);
header-level FAILED continues; `crc-bad.zip` → `failed(Crc)` and continue; `inflate-bad.zip` → `ABORT`;
`crc-bad.7z` → `failed(Crc)`; `solid-bad.7z` per the stated scope; `evaluate_selection` (sizes over the
selection; bomb and oversized-header refused in every mode; `many-small-10000` allowed at 10,000);
ordinal cross-test listing vs `BEGIN`; framer golden bytes (`app/src/test/resources/fixtures/archives/
tree.fzx`, `FYLZ_WRITE_GOLDEN=1`); framer splits > 1 MiB blocks; `Cancelled` on a closed sink during the
header pass (poll); SIGPIPE-ignored write → `Err`.

Kotlin (Robolectric; `androidx.work:work-testing:2.11.2` test dependency):
- `ExtractPlannerTest`: expansion to a bitmap; links counted; hardlink target mapping (unselected
  target read once, written under the link's path); every hostile fixture refused through the planner
  (structural, entry cap without consent, bomb by ratio, oversized headers) and `many-small-10000`
  allowed; missing summary → refused; consent thresholds; vfat nested problems; component sanitisation
  + uniquification; `Here` threshold (folder preselected; headless `largeHereAsFolder`); headless mode.
- `ExtractFrameReaderTest`: golden `.fzx`; every frame kind incl. stand-alone `FAIL`; empty stream with
  a non-OK result accepted; each bounds violation → `PROTOCOL_ERROR`; truncation mid-frame and missing
  terminal frame → `TRANSPORT_LOSS`; bytes after `DONE` → `PROTOCOL_ERROR`; raw-path mismatch on `BEGIN`.
- `ArchiveLimitsTest`: `forExtraction` per volume/free/consent; unknown free → 4 GiB; recompute at claim.
- `ArchiveExtractorTest` (fake `IDecoderService.Stub` writing frames from fixture content): `Here`/
  `IntoFolder`/`Entries` byte-identical incl. hardlinks; directories on demand; staged names invisible;
  `finalizeTarget` per item from the plan; `FAIL` isolates its item (PARTIAL); CRC fail deletes the
  file; app-side refusal (`refuseCreate`/`throwAfterBytes`) fails only that entry; `ABORT` and
  `TRANSPORT_LOSS` finalise completed items and re-issue once with reduced limits (the fake asserts the
  limits and the first ordinal of the second call; `LIMIT_EXCEEDED` not retried); cancel via the flag
  mid-item (the fake honours the closed read end) joins the demuxer, leaves nothing under a final name,
  writes `CANCELLED`, returns `Result.success()`; system stop → `PAUSED_BY_SYSTEM` + `Result.retry()`,
  the re-run claims and resumes; progress past 2³¹ with the bar at 0..1000 and throttled; `ALWAYS`
  verification re-reads and fills `extract_entry_digests`; `REFUSED` leaves no document; `Here` ~2,000
  roots: linear journal writes, one destination listing at planning and one at claim; `busy` pauses
  the watchdog (an injected slow provider write of 2× the inactivity budget does not abandon the call);
  each call opens its own descriptor (the fake asserts distinct fds across two calls).
- `TransferWorkerExtractTest` (`TestListenableWorkerBuilder` + `WorkManagerTestInitHelper` +
  `WorkerFactory` injecting the fake client): success; a second run on the same id refuses the claim
  and returns `success`; `NEVER_RAN` reconciliation; a PARTIAL outcome returns `success`.
- `FylzDatabaseUpgradeTest` (v2 → v3); `OperationRunnerRecoverTest` (running tagged work skipped;
  gone → `PROCESS_INTERRUPTED`; conditional update does not clobber a fresh claim); `OperationRetryPolicyTest`
  (`ReclaimExtract`, states → QUEUED; legacy rows not retryable); `RetryDispatcherTest`.
- The 5 GB mechanics at small scale: tiny inspection limits refuse; `forExtraction(consent = true)`
  extracts through the whole Kotlin path; the fake asserts the limits received.
- Golden test with the new fixtures; `DestinationChooserSheetTest`.

### 2.9 Device checks (`DEVICE_CHECKS.md` §19, "M3.4 — selective extract through the queue")

1. Extract here / to `<name>/` / to… for a ZIP, a 7z, a `tar.xz`, an ISO: progress bar and byte line;
   results byte-identical; nothing under `archive-work/` or `archive-entries/`; `ps` shows
   `:decoders:extract` during the run and not after.
2. **Acceptance, "Extract here":** a 5 GB 7z (one 5 GB member at the root, a thousand small ones) to
   internal storage with `VerifySettings.ALWAYS`, consent given: wall time, peak RSS of
   `:decoders:extract`, the item's `sha256`. Repeat to exFAT. On vfat the 5 GB member is a preflight
   problem; Skip extracts the rest. Browse another archive **during** the extraction: both finish
   byte-exact.
3. Cancel mid-way: the staged folder disappears, the journal says cancelled, `:decoders:extract` is gone,
   `:decoders` (browsing) is alive; a transfer queued behind it runs.
4. `kill -9 <pid>` mid-extract: on relaunch the work re-runs and resumes unfinished items; one operation
   in history.
5. `crc-bad.zip`: CRC mismatch on that file, the rest extracts, PARTIAL. `inflate-bad.zip`: abort, one
   re-issue for the remaining ordinals. `solid-bad.7z`: both members of the folder fail, the next folder
   extracts. A ZIP with a bad entry in the middle of a `tar.xz`: re-issue re-reads from the start (time it).
6. Extract selected entries from inside a browsed archive, incl. a folder and a hardlink whose target
   is outside the selection; a symlink is reported as skipped.
7. Encrypted ZIP → password path; "Extract selected" inside it → M3.9 message.
8. `adb logcat | grep avc`: no denials on the pipe or the isolated instance.
9. A 10,000-file `tar.zst` folder: wall time (one pass); `many-entries` refused without consent, then
   allowed with consent (11 s? record it).
10. Two extracts queued back to back: the second runs after the first; cancelling the first via the
    notification leaves the second queued and it runs; a first that ends PARTIAL does not fail the second.

## 3. Sequencing and gates

Three commits, each green on the full gate (`./gradlew --no-daemon :app:testDebugUnitTest
:app:lintDebug :app:assembleDebug`; the `core` gate incl. `cargo +nightly fuzz build`; the three-ABI
`cargo ndk` build). **M3.4a begins by verifying the M3.3 contract (§1) in code** and records any gap as
its first fix.

- **M3.4a (engine and FFI):** §2.5's `fylz-archive` and `fylz-ffi-android` changes, framer, golden
  `.fzx`, fixtures, Rust tests; `archive_entries` fuzz target exercises `Selection::Ranges`.
- **M3.4b (queue):** AIDL `extractRanges` + `ArchiveExtractResult`; `DecoderService` (bitmap → ranges);
  `DecoderClient.extraction()` via `bindIsolatedService` + the `busy`/progress hooks;
  `ArchiveLimits.forInspection/forExtraction`; `ExtractPlanner` (+ headless); plan tables, DAO, v3
  upgrade + test; `TargetPlanning.kt`; `ArchiveExtractor`; `TransferWorker` EXTRACT (+ `Result.success()`
  rule, stopReason); `OperationRunner.enqueueExtract`/flag-cancel/`recover(workStatus)`/reconciliation;
  `ReclaimExtract` + `RetryDispatcher.kt` (shell ratchet lowered); the cancel `BroadcastReceiver`;
  `work-testing`; all non-UI tests.
- **M3.4c (UI, legacy path, docs):** registry actions, `ExtractSheet`, `ExtractFlow` (ratchet lowered),
  overlay rerouted, `extractZip` reduced, **`ArchiveExtractionPolicy.kt` + tests deleted**,
  `ARCHITECTURE.md` (operation engine: EXTRACT lifecycle, flag-cancel, chain rule; archives: extraction
  path, frames, limits and consent, the isolated extraction instance), `DEVICE_CHECKS.md` §19,
  `REVIEW_QUEUE.md`, PROGRESS, PR #19.

`REVIEW_QUEUE.md` entry for M3.4 (log-and-continue): 1. limits and consent, with the exact exposure
(§2.4); 2. entry cap 10,000 → 200,000 with consent; 3. encrypted ZIPs via zip4j until M3.9/M3.10;
selective extraction from encrypted archives refused; 4. `tar.*` costs two passes plus the listing;
5. conflicts top-level only; FAT component sanitisation; picker destinations have no filesystem type
(`EFBIG` at write); 6. per-entry CRC only where the format carries one; 7. 7z CRC mismatches were
swallowed before M3.4a; 8. isolation scope: header/data FAILED continue (one collateral loss at a solid
boundary), FATAL aborts, one re-issue for every format with a stream re-read; 9. §4.4's read-only
descriptor contract: a writable sink pipe and structure in frames; a second isolated instance;
10. hardlinks are copies made after the pass, symlinks/special skipped; 11. mtimes not preserved;
12. EXTRACT retry re-claims the operation (copy retries as a new operation); 13. `work-testing`;
14. notification bar/bytes for EXTRACT only; 15. planning may decompress an unbrowsed `tar.*` in the UI
process ("Reading archive…" with Cancel); 16. the overlay widens to every browsable format; 17. nested
archives via the materialised inner file; 18. structural verdict persisted with the listing, fail
closed; duplicate keys stay a whole-archive refusal (`tar -r` browses, does not extract) — owner
question open; 19. `.` segments are no-ops (M3.3a; Kotlin-parity deviation); 20. headless planner
mode (`allowLarge`, `largeHereAsFolder`); 21. `Here` above 200 roots prompts; 22. **WorkManager chain
poisoning is pre-existing for COPY/MOVE** (a cancelled or failed copy cancels/fails queued transfers
behind it); EXTRACT avoids it with flag-cancel and `Result.success()`; COPY/MOVE follow-up recorded;
23. CRC surfacing is M3.8's groundwork; 24. the extraction instance is unbound after each operation
(no idle policy).

## 4. Risks

- **Frame parser as attack surface** — bounded and plan-driven; hostile stream tests.
- **Re-issue on stream formats re-reads from the start** — honest and bounded to one retry.
- **`bindIsolatedService` on a real device** (`UNVERIFIED`: instance naming with an `isolatedProcess`
  service declared once in the manifest; §19 item 1 and 3). Fallback if refused: a second manifest
  entry `DecoderExtractService` extending `DecoderService` with `process=":decoders:extract"`.
- **Free space and consent are checked at plan and at claim**; a long queue wait between them is
  covered by the recompute.

## 5. Amendments this design makes to earlier designs

- Part 3: `Selection::Ranges`, `BlockSink`/`extract_blocks` with `extract` as an adapter, `FailKind`,
  `Warning::Other`, `ArchiveError::Failed`, `check_header`/`check_data`, `evaluate_selection`,
  `inspect_for_extraction`, the writer-closure `stream_current_entry_data`.
- M3.2: `ArchiveLimits.forInspection/forExtraction(volume, consent)`; `ArchiveEngineError.Failed/Cancelled`.
- M3.3: the five contract items of §1 (sent during implementation); `DecoderClient.extraction()`;
  `callStreaming` gains `busy`/`progress` hooks.
- Operation engine: `TargetPlanning.kt`, `RetryDispatcher.kt`, `ReclaimExtract`, flag-cancel for
  EXTRACT, `recover(workStatus)`, conditional state updates, schema v3, the `Result.success()` rule.

## 6. Recorded upgrades (not in M3.4)

- Flag-cancel and the `Result.success()` rule for COPY/MOVE (chain poisoning).
- `evaluate_selection` fed from the listing over Binder in chunks (removes the second `tar.*` pass).
- Per-entry conflict resolution UI; `openProxyFileDescriptor`-backed entries (M3.3's recorded upgrade).

## 7. Review findings and disposition

**Rev 1 → 2** (three blockers: the per-entry pipe/callback deadlock, the undefined lifecycle of a
pre-enqueued operation, the silent reversal of hostile refusal; plus the should-fix items) — all adopted;
see commit `7b25ed2`'s message.

**Rev 2 → 3.** Blockers, adopted: **shared open file description** → every call opens its own
descriptor; the handle pins the source, not a pfd (sent to M3.3 as contract iii; §2.3 step 2);
**`structuralRefusal` persistence** → the summary is persisted with the listing and carries the verdict;
M3.4 fails closed (contract iv; §2.2 step 2).

Should-fix, adopted: frame termination/truncation/`TRANSPORT_LOSS`, empty stream with non-OK result,
`FAIL` stand-alone, `END` always with `msg_len`, `path_len` bound, `kind` values, block splitting
(§2.3 step 4); header-level `FAILED` rule and `check_header`/`check_data` (§2.5); ZIP CRC arrives as
`FAILED` → `FAIL kind = crc` classified in Rust, 7z WARN → failed entry (§2.3 step 4); `BlockSink` over
the existing loop via a writer closure, `extract` as adapter, counter above the root `continue`, the
raw-path check on `BEGIN` is Kotlin's (§2.5, §2.3 step 5); the drain rule lives in the `InputStream`
wrapper; chain poisoning → flag-cancel + `Result.success()` for every journaled outcome, §19 item 10
corrected (§2.3 step 8); `recover` conditional update, `workStatus` injection (§2.3 step 9); retry
transitions FAILED/PARTIAL/CANCELLED → QUEUED with `ReclaimExtract`, claim states include
`PAUSED_BY_SYSTEM`, legacy rows excluded (§2.3 steps 1, 9); `stopReason` distinguishes a user cancel,
system stops → `PAUSED_BY_SYSTEM` (§2.3 step 8); structural limits enumerated, duplicates resolved as
structural (browses, does not extract), `.` segments decided (contract v), fixture counts corrected and
bomb/oversized fixtures added (§2.4, §2.8); shared connection → a dedicated isolated instance, no
transparent retry, re-issue for every format (§2.3 steps 3, 7); liveness counts demuxer progress and
pauses on `busy` (§2.3 step 6); cancel joins the demuxer, bounded wait, then unbinds the instance;
Rust polls the sink during the header pass; `Dispatchers.IO` instead of a fixed pool (§2.3 step 8);
bitmap instead of capped ranges (§2.2 step 3, §2.5); re-issue only on CORRUPT/loss with reduced limits,
every 7z retried (§2.3 step 7); run-time `NameIndex` injected into `TargetPlanner`, `verifyFile` not
shared (§2.3 step 1, §2.5); journal refresh throttled (§2.3 step 5); free space recomputed at claim and
`consent` a parameter (§2.4). Nits: `Here` default and headless behaviour; hardlink targets outside the
selection; digests table in the schema/DAO/deletes; `Warning::Other` logged; descriptor-only
`ConflictedItem`; `ReclaimExtract`; `recover` signature; "Reading archive…" notification text.
Items claimed adopted in rev 2 but not addressed (ordinal/SIGPIPE, cleanup actor, one listing, retry
states, INTERRUPTED, fixture counts, digests table, `./` normalisation) are each fixed above.
Not adopted: none.
