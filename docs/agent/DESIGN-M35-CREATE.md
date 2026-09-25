# M3.5 design: Create — the Compress… sheet, through the queue

**Rev 1** (2026-09-25), for architecture review. Design for MASTER_PLAN M3.5, written from
`SURVEY-M35-CREATE.md` (facts at HEAD `07fa3b1`, file:line; libarchive facts from the vendored
v3.8.9) on top of `DESIGN-M34-SELECTIVE-EXTRACT.md` rev 3 (the plan-in-journal pattern, the
claim/tag/reconcile lifecycle, flag-cancel and the `Result.success()` rule, `TargetPlanning.kt`, the
dedicated isolated instance, the framed pipe and its bounded reader) and `DESIGN-M33-ARCHIVE-BROWSING.md`
rev 2 (`callStreaming`, pipes as the bulk channel). M3.5 is implemented **after M3.4** and reuses its
machinery; nothing here duplicates it. Paths are relative to the repository root. `UNVERIFIED` marks a
claim the implementing agent must confirm and record.

The plan's text, in full:

> **M3.5 Create.** A **Compress…** sheet with: format: zip, 7z, tar.gz, tar.xz, tar.zst; level;
> password (M5); split size (off, 100 MB, 700 MB, 4 GB for FAT32, custom); "store paths relative to
> selection"; folders included recursively (today folders are rejected). It runs in the queue.

## 0. Scope

**In:** (a) a Compress sheet opened by `fylz.compress`, with format, level, split size, the
relative-paths toggle, an archive name and a destination; (b) folders included recursively;
(c) a CREATE operation carried by `TransferWorker` with the M3.4 lifecycle (plan in the journal,
claim/tag/reconcile, flag-cancel, `Result.success()`), staging of the output document(s),
conflict handling for the archive name, progress by source bytes, cancel, recovery and retry;
(d) the archive written by libarchive in a **dedicated isolated instance** of the decoder service,
with source bytes framed **in** through one pipe and archive bytes streamed **out** through another,
so no native code runs in the app process; (e) split volumes as raw `.001/.002/…` parts produced by
the app-side drain; (f) a verification pass that re-lists the written archive; (g) both existing
create entry points (the selection bar and the Archive Tools overlay) routed through the sheet.

**Out (and where it goes):** **7z creation** — libarchive's 7z writer buffers every entry's data in a
temp file it creates by path (`__archive_mktemp`: `$TMPDIR` or `/tmp`), which an isolated process
cannot open, and libarchive has no API to hand it a descriptor; it is also always solid, LZMA1 by
default, has no encryption, and its 7-Zip compatibility is untested here. The plan's own table
assigns 7z *write* to the 7-Zip `.so` (with AES and encrypted headers), so 7z stays greyed in the sheet
("arrives with the 7-Zip pack") — a logged deviation from M3.5's format list, not a silent one.
**Passwords** — the plan says "(M5)"; the vendored libarchive has a stub crypto backend, so ZIP AES is
impossible with it (`build.rs`'s comment claiming a bundled AES is wrong and is corrected in M3.5a);
ZipCrypto would work but is not worth offering. The sheet shows a disabled password field with the M5
note; the overlay's **AES-256 "Create ZIP"** keeps zip4j until M5/M3.10 (§2.7). Editing an archive in
place (M3.6), testing (M3.8), removing zip4j (M3.10). PKWARE spanned ZIPs (`.z01` + `.zip` with disk
numbers): libarchive cannot write them; parts are raw splits.

## 1. Constraints (short; the survey has the evidence)

- Today: `createZip` stages every source into cache, rejects folders by its own `require`, uses
  zip4j deflate level NORMAL, caps by the *extraction* limits, writes through `openOutputStream` with
  no rollback, runs in a **composition scope** with no progress or cancel, journals `ARCHIVE` with one
  item per source; two entry points (`fylz.compress` → `CreateDocument("application/zip")`;
  the overlay's `fylz.protect` → `OpenMultipleDocuments` → password dialog → `CreateDocument`).
- libarchive write is compiled in (94 `archive_write_*` symbols in the Android static lib):
  `archive_write_new`, `set_format_{zip,pax_restricted,…}`, `add_filter_{gzip,xz,zstd,bzip2,lz4}`,
  `open2(data, opener, writer, closer, freer)` (no seek callback; writer returns bytes written),
  `set_bytes_per_block(0)` for pass-through, `set_options("zip:compression-level=9,xz:threads=1")`,
  entry setters incl. `set_pathname_utf8`, `set_size`/`unset_size`, `set_filetype`, `set_perm`,
  `set_mtime`. The ZIP writer **always streams** (data descriptors; never seeks); size known →
  data beyond it is **silently truncated**; unknown size allowed. pax fixes the size at header
  time: extra data dropped, shortfall zero-padded. Unicode: bit 11 set when `hdrcharset=UTF-8`
  *and* the current charset is UTF-8 (iconv OFF; bionic's `nl_langinfo(CODESET)` `UNVERIFIED`).
  Filters: gzip 0–9 (default 6), xz 0–9 (default 6; 6 ≈ 94 MiB, 9 ≈ 674 MiB, MT multiplies per
  thread), zstd −N..22 (default 3; 20–22 need 32–128 MiB windows; MT compiled out), bzip2 1–9, lz4 1–9.
- No writer seek callback → splitting is the **drain's** job (raw byte split), not libarchive's.
- `:decoders` cannot open Uris or paths; pipes are the one certain channel in either direction.
- The queue and journal are as M3.4 leaves them: plan tables, `claimExtract`-style claims, tags,
  `Result.success()`, flag-cancel, `TargetPlanner(nameIndex)`, `updateItem`, the `busy`/progress
  hooks on `callStreaming`, `bindIsolatedService` instances. `FylzV1App.kt` and `FylzAppShell.kt`
  sit on their ratchets after M3.4 (M3.4 lowers both; M3.5 must not raise them).
- `fylz.compress` is enabled inside archives (sources read through the archive provider: one
  materialisation per entry, 512 MiB cap, links/encrypted refused).

## 2. Decisions

### 2.1 The sheet and the actions

`fylz.compress` (selection bar slot 80, unchanged) now opens the **Compress sheet**
(`ui/actions/CompressSheet.kt`, rendered from `MenuId.COMPRESS` so `NoHardCodedMenusTest` holds; the
sheet's controls are ordinary Compose widgets, not registry actions). Fields:

| Field | Values | Default |
|---|---|---|
| Format | `zip`, `tar.gz`, `tar.xz`, `tar.zst` (also `tar.bz2`, `tar.lz4` under "More"); `7z` shown disabled: "7z creation arrives with the 7-Zip pack" | `zip` |
| Level | Fast / Normal / Best, mapped per format (§2.4): zip 1/6/9; gzip 1/6/9; xz 1/6/**6**; zstd 1/3/**19**; bzip2 1/9/9; lz4 1/1/9 | Normal |
| Password | disabled field: "Password protection arrives with M5" | — |
| Split size | Off, 100 MB, 700 MB, 4 GB (FAT32: 4 GiB − 1), Custom (MB) | Off, or **4 GB preselected** when the destination is vfat and the estimated size exceeds it |
| Store paths relative to selection | on: entries are named from the current folder (`photo.jpg`, `docs/a.txt`); off: prefixed with the location's path below the tab root (`Downloads/docs/a.txt`) | on |
| Archive name | text; default `<folder name>` when the whole listing or several items are selected, `<item name>` for one item, plus the format's extension | |
| Destination | the current folder; "Choose…" opens `DestinationChooserSheet` (archive tabs filtered) | current folder |

Inside an archive location the destination cannot be the current folder (read-only): the sheet
opens with "Choose…" required. The overlay's `fylz.protect` splits: its "Create ZIP" without a
password opens this sheet with the picked sources; with the AES switch on it keeps the legacy
`createZip` path (§2.7). `FylzV1App`'s `archiveCreator`, `compress()` and the sources plumbing move
to `ui/actions/CompressFlow.kt` (the flow state, the sheet, the preflight/conflict sheets it hosts,
"Scanning…" while sizes are gathered); `FylzV1App` keeps one line. Golden test: `fylz.compress`'s
`enabledWhen` is unchanged (`ALWAYS` under `HAS_SELECTION`), so no fixture changes; the sheet is UI.

### 2.2 Planning: sources, names, sizes, preflight, conflicts

`CompressRequest(sources: List<Uri>, format, level, split: Long?, relativeToSelection: Boolean,
archiveName, destinationFolder)`; `CompressPlanner.plan(request, ui | Headless(conflictPolicy))`
(the headless mode serves Addendum §C4/§C5's `compress` step):

1. **Walk.** Every source is enumerated with `DocNode.children` recursively (the app process; the
   archive provider serves sources inside archives). Each regular file becomes a **manifest entry**
   `{ordinal, archivePath, sourceUri, size: Long?, mtimeMillis: Long?}`; each directory a directory
   entry. `archivePath` = the path relative to the source's parent (the current folder) when
   `relativeToSelection`, else prefixed with the location stack's names below the tab root, joined
   with `/`; components are sanitised only of `/`-embedded names (SAF names cannot contain `/`).
   Symlinks do not exist under SAF; an archive-provider source that is a link or encrypted entry
   fails the plan with the provider's message. The walk shows "Scanning…" with Cancel (large trees).
2. **Sizes.** `size == null` for a regular file is allowed only for `zip` (libarchive streams unknown
   sizes there); for `tar.*` the plan fails: "Cannot archive `<name>`: its size is unknown" (pax fixes
   the size at header time and would silently pad or truncate). The manifest total is the progress
   denominator and the space estimate: `estimate = Σ size` (compression can only help; `store`
   equals it), checked against destination free space with the 5 % margin through `PreflightPolicy`.
3. **Preflight** for the **output**: the archive name (and, split, each part name `name.zip.001`…)
   through `PreflightPolicy` (FAT characters, length, VFAT 4 GiB − 1: when the destination is vfat
   and `estimate > 4 GiB − 1` and split is Off, a preflight problem offers "Split into 4 GB parts");
   the source count and total shown in the sheet's summary line.
4. **Conflicts** for the output document(s) through `findConflicts` → `ConflictSheet` with the M3.4
   `NameIndex` (one listing); resolutions into the plan. Headless: the request's policy.
5. **Manifest persistence.** The manifest can be large (a 100,000-file folder). It is written to a
   plan file `cacheDir/archive-plans/<opId>.fzp` (the M3.3 codec's discipline: versioned, bounded,
   `.part` + rename) rather than the journal, and `create_plans(operation_id, format, level, split,
   relative, archive_name, destination_uri, manifest_path, manifest_sha256, total_bytes, entry_count,
   catalog_key?)` holds the rest; `create_plan_items(operation_id, item_index, requested_name,
   conflict_policy, name_override)` holds one row per output document (1, or N parts when split).
   The journal's `OperationItem`s are **the top-level sources** (as today's ARCHIVE type: one item per
   selected item, `expectedBytes` = its subtree size), so the history dialog and progress row show
   what the user selected; the operation's `destination` is the archive's final Uri after finalise.
   Written atomically with the operation (M3.4's `putWith…` pattern, generalised to
   `putWithPlan(op, plan)`), `Data` = `KEY_TYPE = ARCHIVE`, `KEY_OPERATION_ID`; the request is tagged
   `op:<id>`; `enqueueCreate(id)` mirrors `enqueueExtract`.

### 2.3 One write pass in `:decoders:write`, frames in, archive out

`TransferWorker.doWork` accepts `ARCHIVE` and calls `ArchiveCreator.run(opId, stopReason)`:

1. **Claim** exactly as M3.4 (`claim(op, fromStates)`, tag lookup, `Result.success()` on refusal),
   re-read the manifest (`sha256` checked), recompute free space, build the `NameIndex`.
2. **Output document(s):** for each `create_plan_items` row create the staged document
   (`stagingName(opId, idx, name)`, `application/octet-stream` MIME, created-name check), record
   `stagingUri`, open `openOutputStream(staging, "w")`. Split: parts are opened lazily as the byte
   count crosses each boundary; the last part is finalised at the end; part count is the manifest's
   estimate ± 1 (conflicts were resolved for the estimated names; a surplus part uses the `NameIndex`).
3. **One call on a dedicated instance:** `DecoderClient.writer()` binds `bindIsolatedService(…,
   "write", …)` → process `:decoders:write` (unbound when the operation ends), and calls
   `IDecoderService.writeArchive(in, options, out)` through a two-pipe variant of `callStreaming`:
   the client creates **both** pipes; hands the read end of `in` and the write end of `out` to the
   service (which `use`s both); keeps the write end of `in` for its **feeder** and the read end of
   `out` for its **drain**; closes its own copies of the service's ends after the transaction is
   issued (so the service sees EOF on `in` only when the feeder closes, and the drain sees EOF on
   `out` when the engine closes). Liveness = bytes fed **or** drained, paused while the feeder is
   inside `openInputStream`/`read` on a slow provider or the drain is inside a provider `write`.
4. **Feeder** (app process, `Dispatchers.IO`): writes `FZW1` frames into `in`:

   ```
   MAGIC  "FZW1"
   ENTRY  0x01 ordinal:u32 kind:u8(1 file,2 dir) size:i64(-1 unknown, zip only) mtime:i64(MIN unknown) mode:u32 path_len:u32(≤65536) utf8_path
   DATA   0x02 ordinal:u32 len:u32(1..=1 MiB) bytes
   END    0x03 ordinal:u32 bytes:u64
   FINISH 0x04                                   close the archive normally
   ABORT  0x05 msg_len:u16 msg                   stop; the engine returns Cancelled
   ```

   For each manifest entry in order: `ENTRY`, then the source read through `openInputStream(sourceUri)`
   in 512 KiB blocks → `DATA` frames, counting bytes → `END`. **A byte count that differs from the
   declared size aborts the whole archive** ("`<name>` changed while it was being archived"): pax
   would pad or truncate silently and ZIP would truncate; a partial archive is worse than none. An
   unreadable source (a provider exception, an archive-entry refusal) aborts too. Cancel: the feeder
   stops, sends `ABORT`, closes `in`; the drain is closed after the transaction returns (bounded 5 s
   wait, then unbind the instance).
5. **Engine** (Rust `archive_write_frames(in_fd, out_fd, options)`; §2.5): parses frames with bounds
   (the app is trusted, but the parser is bounded anyway), configures the writer from `options`
   (`FormatOptions { format, level, threads = 1, hdrcharset_utf8 = true }`), and for each `ENTRY`
   sets pathname (UTF-8), size (or unset for zip), filetype, perm, mtime, calls `archive_write_header`,
   streams `DATA` through `archive_write_data`, `archive_write_finish_entry` on `END`; `FINISH` →
   `archive_write_close`; the `open2` writer callback `write_all`s every block to `out_fd`
   (`bytes_per_block = 0`, pass-through), EPIPE → `Cancelled`. Returns `ArchiveWriteReportRecord
   { entries, bytes_in, bytes_out }` or an error.
6. **Drain** (app process): reads `out` into the current staged document, counting bytes and
   feeding a SHA-256 per part; at each `splitSize` boundary closes the part and opens the next
   (§step 2). Progress = **source bytes fed** / manifest total (compressed output size is unknown in
   advance), throttled (`ProgressWriteThrottle`), the notification bar 0..1000 with "N of M files ·
   X of Y MB".
7. **After the pass:** verification — when `VerifySettings.shouldVerify(destination)`, the finished
   (unsplit) staged archive is **re-listed** through `:decoders` (`listArchive`) and the listing's
   entry count and per-entry sizes are compared with the manifest (structure and sizes; CRCs are
   only checked by extraction — M3.8's "Test archive" — and ZIP CRCs were computed by libarchive on
   write); split sets are not re-listed (reassembly is the reader's job; logged) — their per-part
   SHA-256 goes into `create_plan_items.sha256`. Then `finalizeTarget` per output document with its
   conflict plan (rename or replace-with-recycle). Journal: SUCCEEDED, or FAILED with
   `SOURCE_CHANGED` / `SOURCE_UNREADABLE` / `INSUFFICIENT_SPACE` / `FILE_TOO_LARGE` (an `EFBIG` on a
   part) / `ARCHIVE_WRITE_FAILED` (engine error, message) / `VERIFICATION_FAILED`; any failure
   deletes every staged output. There is no partial success for create.
8. **Cancel, stops, recovery, retry:** flag-cancel (`create_plans.cancel_requested`, the
   notification's Cancel receiver), `Result.success()` for every journaled outcome, `PAUSED_BY_SYSTEM`
   on a system stop with `Result.retry()` — but a resumed create **starts over** (an archive cannot be
   resumed mid-stream; staging is deleted at claim); `recover` treats ARCHIVE-with-plan like EXTRACT;
   retry (`ReclaimCreate(opId)`) re-queues from the plan; legacy ARCHIVE rows (zip4j, no plan) are
   excluded.

### 2.4 Formats, levels and memory

| Format | libarchive setup | Fast / Normal / Best | Ceiling and why |
|---|---|---|---|
| `zip` | `set_format_zip`, `zip:compression=deflate`, `zip:hdrcharset=UTF-8` | 1 / 6 / 9 | none needed; 64 KiB buffer |
| `tar.gz` | `pax_restricted` + `gzip`, `pax:hdrcharset=UTF-8` | 1 / 6 / 9 | none |
| `tar.xz` | `pax_restricted` + `xz`, `xz:threads=1` | 1 / 6 / **6** | level 7–9 need 186–674 MiB; `:decoders`' target is 256 MB; MT multiplies per thread |
| `tar.zst` | `pax_restricted` + `zstd` | 1 / 3 / **19** | 20–22 need 32–128 MiB windows; MT compiled out (`ZSTD_MULTITHREAD_SUPPORT OFF`) so `threads` is not offered |
| `tar.bz2` | + `bzip2` | 1 / 9 / 9 | none |
| `tar.lz4` | + `lz4` | 1 / 1 / 9 | none (HC from 3) |

Options are applied with `archive_write_set_options` from a string the Rust side builds from typed
values (never from user text). `zip:compression=store` when level 0 is requested by a script; the
sheet offers Fast/Normal/Best only. `pax_restricted` is the tar flavour (long names via pax records;
ustar would refuse them). A **level 0 / store** path exists for the headless API only.

### 2.5 Engine, FFI, IPC and persistence amendments

`fylz-archive` gains `write.rs`: write-side `sys` externs (`archive_write_new/free/close/header/
data/finish_entry/open2/set_bytes_per_block/set_options/set_format_zip/set_format_pax_restricted/
add_filter_{gzip,xz,zstd,bzip2,lz4}/set_error?`, entry setters), a `Writer` over `open2` whose writer
callback `write_all`s into a caller fd and maps `EPIPE` to `ArchiveError::Cancelled`; `FormatOptions`
→ option string; `Writer::add_entry(&EntryMeta)`, `write_data(&[u8])`, `finish_entry()`, `close()`.
`write_frames(in_fd, out_fd, &FormatOptions) -> Result<WriteReport, ArchiveError>` parses `FZW1`
(bounded; malformed → `ArchiveError::Failed("bad frame")`). Round-trip tests per format through the
existing `inspect`/`extract`: byte-exact content, names (incl. non-ASCII → bit 11 checked by parsing
the local header flags of the produced ZIP), mtimes (2 s for ZIP DOS + UT extra), directories,
unknown size on zip, size mismatch → the writer's own guard (`write_data` beyond `size` → `Failed`,
so the engine never relies on libarchive's silent truncation), levels produce smaller output than
store, cancellation via a closed `out_fd` → `Cancelled` (SIGPIPE is already ignored). `build.rs`'s
wrong "bundled AES" comment is corrected.

`fylz-ffi-android`: `archive_write_frames(in_fd, out_fd, options: WriteOptionsRecord) ->
Result<ArchiveWriteReportRecord, ArchiveEngineError>`; `WriteOptionsRecord { format: WriteFormatRecord,
level: u32 }`.

AIDL: `ArchiveWriteResult writeArchive(in ParcelFileDescriptor input, in ArchiveWriteOptions options,
in ParcelFileDescriptor output)`; Parcelables `ArchiveWriteOptions(format, level)`,
`ArchiveWriteResult(outcome, message, entries, bytesIn, bytesOut)`.

`DecoderClient.writer()` (instance `"write"`, `bindIsolatedService`), `callTwoPipes(...)` sharing the
generation/liveness machinery with `callStreaming` (both pipes client-owned; `busy` from feeder and
drain).

Persistence (schema v3 → v4, additive): `create_plans`, `create_plan_items`; `OperationsDao`
generalisations of M3.4's methods (`putWithPlan`, `claim`, `retry`, `setCancelRequested`) to both
plan kinds; plan files under `cacheDir/archive-plans/` are deleted with their operation and swept by
`ArchiveCacheSweeper` (24 h). `onUpgrade` test v3 → v4.

### 2.6 Verification, stated precisely

A created (unsplit) archive is re-listed through `:decoders` when `VerifySettings` says so; the
listing must contain exactly the manifest's entries with the manifest's sizes; ZIP CRCs were computed
by libarchive while writing and are checked only by a future extraction (M3.8). Split sets: per-part
SHA-256 recorded, no re-listing (logged). This is weaker than copy's byte-for-byte re-read and is
stated as such in the sheet's confirm text ("Verified: structure and sizes").

### 2.7 The legacy path shrinks to AES-encrypted ZIP creation

`ArchiveService.createZip` stays only for the overlay's AES-256 "Create ZIP" (zip4j) until M5/M3.10;
it keeps its cache staging and folder refusal (unchanged behaviour, unchanged tests: there are none);
its numbers come from `ArchiveLimits`. Everything else creates through the queue.

### 2.8 Fixtures and tests

Rust: round trips for every format and level tier; Unicode names; directories and nested paths;
`relative` prefixes; unknown size on zip; size-mismatch guard; cancellation; frame parser bounds
(malformed frames → `Failed`, never a panic); a fuzz target `write_frames` (arbitrary bytes as frames →
must not panic; output to `/dev/null`), 60 s in CI's smoke step; produced archives cross-checked by
`inspect`/`extract`, and a committed golden `tree.zip.created` compared byte-exact (deterministic:
fixed mtimes, level 6) to catch writer drift.

Kotlin (Robolectric): `CompressPlannerTest` (walk over `TreeFixtures.buildTree`, relative vs prefixed
paths, unknown size refused for tar / allowed for zip, name defaults, vfat split suggestion, headless);
`ArchiveFrameWriterTest` (frames byte-exact against a golden `.fzw`); `ArchiveCreatorTest` (fake
decoder stub that parses the frames it receives and writes a deterministic pseudo-archive to `out`;
asserts staging → finalise, `KEEP_BOTH`/`REPLACE`/`SKIP`, split parts at exact boundaries with
lazily created parts and a surplus part named through the `NameIndex`, size-mismatch abort deletes
every staged output, unreadable source aborts, cancel via the flag, system stop → `PAUSED_BY_SYSTEM`
and a restart from scratch, progress by fed bytes, per-part SHA-256, re-listing verification via the
fake, `Result.success()` on every outcome); `TransferWorkerCreateTest` (`TestListenableWorkerBuilder`);
upgrade v3 → v4; recover/retry for ARCHIVE-with-plan; golden test unchanged (no `enabledWhen` change);
`FylzV1AppSizeTest` under the ratchets.

### 2.9 Device checks (`DEVICE_CHECKS.md` §20, "M3.5 — Compress")

1. Compress a folder tree to `zip`, `tar.gz`, `tar.xz`, `tar.zst` into Downloads; open each with a
   third-party app and with Fylz (M3.3 browsing); names with non-ASCII characters intact (bit 11 on a
   device — the `nl_langinfo` question); mtimes within 2 s.
2. A 6 GB folder to `zip` on internal storage: progress by bytes, `:decoders:write` RSS (xz Best on
   another run: under 256 MB); the result opens.
3. Split at 700 MB: parts named `.001…`; `cat` them back together and open; a vfat card with a > 4 GiB
   source and split Off shows the preflight suggestion.
4. Cancel mid-way: no staged parts remain; the queue behind it runs.
5. Kill the app mid-create: on relaunch the operation restarts from scratch (no partial archive).
6. Compress from inside a browsed archive (materialisation per entry); compress into another tab's
   folder via "Choose…".
7. The overlay's AES "Create ZIP" still works (zip4j); its plain "Create ZIP" opens the sheet.
8. `adb logcat | grep avc`: no denials on the two pipes or the write instance.

## 3. Sequencing and gates

Three commits, each green on the full gate (Gradle test/lint/assemble; the `core` gate incl.
`cargo +nightly fuzz build`; three-ABI `cargo ndk`):

- **M3.5a (engine):** `write.rs`, `write_frames`, options, guard, tests, the `write_frames` fuzz target
  in CI, `build.rs` comment fix, FFI `archive_write_frames`.
- **M3.5b (queue):** AIDL `writeArchive` + Parcelables; `DecoderService`; `DecoderClient.writer()` +
  `callTwoPipes`; `CompressPlanner` (+ headless), plan file codec, `create_plans`/`create_plan_items`
  + DAO generalisation + v4 upgrade test; `ArchiveCreator`; `TransferWorker` ARCHIVE; enqueue/recover/
  retry; all non-UI tests.
- **M3.5c (UI, legacy, docs):** `CompressSheet`, `CompressFlow` out of `FylzV1App` (ratchet
  lowered), the overlay split, `createZip` reduced to AES, `ARCHITECTURE.md` (create path, the write
  instance, levels/memory table, split semantics), `DEVICE_CHECKS.md` §20, `REVIEW_QUEUE.md`,
  PROGRESS, PR #19.

`REVIEW_QUEUE.md` entry for M3.5 (log-and-continue): 1. 7z creation deferred to the 7-Zip pack
(libarchive's writer needs a path-based temp file; also always solid, LZMA1, no AES). 2. Password
disabled until M5; the overlay's zip4j AES path remains the only encrypted writer. 3. Native writing
runs in an isolated instance with both pipes; no native code in the app process (REPORT-M2's
argument kept). 4. Level ceilings: xz 6, zstd 19, for `:decoders`' memory target. 5. Split volumes are
raw `.001` parts for every format (not PKWARE spanned ZIPs); the sheet says "join the parts before
opening"; split sets are not re-listed. 6. "Relative to selection" = relative to the current folder,
the only possible selection root; off = prefixed with the location path below the tab root. 7. A
source whose byte count differs from its declared size aborts the whole archive. 8. tar formats
refuse sources of unknown size; zip streams them. 9. Sources inside archives are allowed at one
materialisation per entry. 10. Verification is structure and sizes, not a byte re-read. 11. A create
resumed after a system stop restarts from scratch. 12. `build.rs`'s bundled-AES comment was wrong.
13. Unicode bit 11 depends on bionic's `nl_langinfo` (`UNVERIFIED`; §20 item 1). 14. Headless
`compress` step for §C4/§C5. 15. Schema v4.

## 4. Risks

- **bionic and `hdrcharset=UTF-8`** (§1): if bionic reports a non-UTF-8 codeset, ZIP names lack bit
  11 and readers assume CP437. Fallback: build libarchive with `HAVE_ICONV` off but force
  `archive_string_default_conversion_for_write` — not possible without a patch; the honest fallback
  is `pax` (always UTF-8) and a note in the sheet for zip. Decided by §20 item 1.
- **Two pipes and one Binder thread**: the service reads `in` and writes `out` on the same thread
  through libarchive; the app's feeder and drain run concurrently, so no deadlock as long as the
  drain always drains (it does; it never waits on the feeder).
- **Large manifests** (100,000 files) as a plan file: bounded reader, sha256-checked; the walk itself
  is the P1.11-class cost the user already accepts for copy preflight.
- **7z absence** is visible in the sheet with a reason; the 7-Zip pack owns it.

## 5. Amendments this design makes to earlier designs

- Part 3 / M3.4: `fylz-archive` gains a write side (`write.rs`) and `ArchiveError::Cancelled` is now
  used by the writer too; the fuzz set gains `write_frames`.
- M3.4: `DecoderClient` gains `writer()` and `callTwoPipes`; the plan-table pattern is generalised
  (`putWithPlan`, `claim`, `retry` for both kinds); `ReclaimCreate`.
- M3.3: `ArchiveCacheSweeper` sweeps `archive-plans/`.
