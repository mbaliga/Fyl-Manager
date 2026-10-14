# M3.5 design: Create — the Compress… sheet, through the queue

**Rev 2** (2026-09-25), after the architecture review of rev 1 (commit `ed9dc4e`); §7 lists the
findings and their disposition. Design for MASTER_PLAN M3.5, written from `SURVEY-M35-CREATE.md`
(facts at HEAD `07fa3b1`, file:line; libarchive facts from the vendored v3.8.9, some now confirmed by
the reviewer building and running small C programs against the vendored host static library) on top
of `DESIGN-M34-SELECTIVE-EXTRACT.md` rev 3 (the plan-in-journal pattern, the claim/tag/reconcile
lifecycle, flag-cancel and the `Result.success()` rule, `TargetPlanning.kt`, the dedicated isolated
instance, the framed pipe and its bounded reader) and `DESIGN-M33-ARCHIVE-BROWSING.md` rev 2
(`callStreaming`, pipes as the bulk channel). M3.5 is implemented **after M3.4** and reuses its
machinery. Paths are relative to the repository root. `UNVERIFIED` marks a claim the implementing
agent must confirm and record.

The plan's text, in full:

> **M3.5 Create.** A **Compress…** sheet with: format: zip, 7z, tar.gz, tar.xz, tar.zst; level;
> password (M5); split size (off, 100 MB, 700 MB, 4 GB for FAT32, custom); "store paths relative to
> selection"; folders included recursively (today folders are rejected). It runs in the queue.

## 0. Scope

**In:** (a) a Compress sheet opened by `fylz.compress`, with format, level, split size, the
relative-paths toggle, an archive name and a destination; (b) folders included recursively;
(c) a CREATE operation carried by `TransferWorker` with the M3.4 lifecycle (plan in the journal,
claim/tag/reconcile, flag-cancel, `Result.success()`), staging of the output document(s),
conflict handling for the archive name and for split sets as a unit, progress by source bytes,
cancel, recovery and retry with a bounded restart count; (d) the archive written by libarchive in a
**dedicated isolated instance** of the decoder service, with source bytes framed **in** through one
pipe and archive bytes streamed **out** through another, each on its own thread that cannot be
starved by browsing or extraction traffic, so no native code runs in the app process; (e) split
volumes as raw `.001/.002/…` parts produced by the app-side drain, named and replaced as one set;
(f) a verification pass that re-reads every staged output and, when it is seekable and unsplit, also
re-lists it; (g) both existing create entry points (the selection bar and the Archive Tools overlay)
routed through the sheet; (h) path sanitisation and bounds on the source walk so Fylz cannot write an
archive it would refuse to read back.

**Out (and where it goes):** **7z creation** — libarchive's 7z writer buffers every entry's data in a
temp file it creates by path (`__archive_mktemp`), which an isolated process cannot do: it has no
`CAP_DAC_OVERRIDE`-free path to create a file anywhere the sandbox lets it write, `/data/local/tmp`
belongs to `shell`, and a passed directory descriptor does not help because `mkstemp`/`O_TMPFILE`
still need create permission in that directory, not just an open one; a `memfd` would hold the whole
compressed payload in RAM, against the 256 MB target. The only route inside the sandbox is a vendored
patch that lets `__archive_mktemp` accept a caller-supplied regular-file descriptor instead of a path
— gated on `DEVICE_CHECKS.md` §18 item 7 (whether `isolated_app` may open an app-private file it is
handed) and recorded as the unblock path, not built now. 7z creation in the app process is the
rejected exception (it would load the native library where REPORT-M2's cold-start argument says it
never runs, for a writer that is always solid, LZMA1 by default, with no encryption, and untested
7-Zip compatibility). The plan's own table assigns 7z write to the 7-Zip `.so`, which needs a
seekable output of its own and inherits the same staging question; so 7z stays greyed in the sheet:
"7z creation arrives with the 7-Zip pack" (§4.4 add-on packs). **Passwords** — the plan says "(M5)";
the vendored crypto backend is a stub (ZipCrypto would link, AES will not until a real backend is
enabled), so the sheet's password field stays disabled with the M5 note; the overlay's AES-256
"Create ZIP" keeps zip4j until a crypto backend lands, which also gates M3.9's AES read, M5, and
M3.10 (§2.7). Editing an archive in place (M3.6), testing (M3.8), removing zip4j (M3.10). PKWARE
spanned ZIPs (disk numbers): libarchive cannot write them; parts are raw splits, and Fylz cannot yet
*read* the split sets it writes (`.zip.001…` needs M3's later multi-volume read support) — logged, not
silently promised by the plan's read/write table.

## 1. Constraints (short; the survey has the evidence, corrected where the review found otherwise)

- Today: `createZip` stages every source into cache, rejects folders by its own `require`, uses
  zip4j deflate level NORMAL, caps by the *extraction* limits, writes through `openOutputStream` with
  no rollback, runs in a **composition scope** with no progress or cancel, journals `ARCHIVE` with one
  item per source; two entry points (`fylz.compress` → `CreateDocument("application/zip")`;
  the overlay's `fylz.protect` → `OpenMultipleDocuments` → password dialog → `CreateDocument`).
- libarchive write is compiled in. The ZIP writer **always streams** (data descriptors; never seeks);
  a **shortfall** against a declared size is benign (the data descriptor and central directory record
  the actual byte count — confirmed by building and running the writer: 3 of 5 declared bytes yields a
  valid 3-byte entry); only writing **more** than declared is an error worth aborting for. pax fixes
  the size at header time and zero-pads a shortfall (confirmed): a source whose size shrinks under it
  is silently corrupted, so tar sources need their actual byte count known before the header, not
  merely at plan time.
- **`archive_write_free` finalises the archive even when the write failed midway**, unless the writer
  state is `FATAL`: it calls `archive_write_close`, which for a partially-written entry zero-pads the
  rest (pax) and always writes a valid central directory or trailer (confirmed: freeing a zip writer
  after 3 of 5 declared bytes, or after any entry, produces a well-formed archive holding whatever was
  written; only a callback that itself returns an error forces `FATAL` and suppresses this). So
  "abort" is not simply "stop and free" — the writer must be put into a poisoned state that makes its
  next callback fail, or `free` quietly ships a truncated-but-valid archive.
- **Charset**: iconv is off and libarchive's UTF-8 conversion checks the *calling thread's* current
  locale (`nl_langinfo(CODESET)`), not a fixed default. In the C locale (which a Rust test binary or a
  freshly spawned `:decoders:write` thread runs in unless something sets it), `zip:hdrcharset=UTF-8`
  and `pax:hdrcharset=UTF-8` both return `ARCHIVE_FATAL`, and writing a non-ASCII pathname without
  `hdrcharset` fails the header outright for both formats (confirmed by running the writer under the
  C locale and again after `uselocale(newlocale(LC_CTYPE_MASK, "C.UTF-8", 0))`, which succeeds and
  sets ZIP's UTF-8 flag, bit 11). This is not "mojibake on read" — it is a **write failure** for any
  non-ASCII name until the write path pins its own thread's locale.
- No writer seek callback → splitting is the **drain's** job (raw byte split), not libarchive's.
  Today's `uniqueName`/`finalizeTarget`/`RecycleBinService` operate on one document at a time and
  split at the last dot, so a naive reuse turns `name.zip.001` into `name.zip (2).001` on conflict —
  the parts stop matching each other's base name.
- `:decoders` cannot open Uris or paths; pipes are the one certain channel in either direction.
- `ArchiveSource` (the M3.2 helper any verification re-read would use) copies a **non-seekable**
  destination again under its own 2 GiB cap; `ArchiveCatalog`/`archive_inspect` have a 200,000-entry
  bound. A staged output is exactly the kind of document whose descriptor may or may not be seekable
  depending on the provider, and it is not a document a persisted `.fzl`/summary sidecar should ever
  be keyed on (its Uri disappears at rename).
- `cacheDir` is evictable, and `ArchiveCacheSweeper` sweeps by **age**; a large queued create can
  legitimately wait longer than the sweep window, so a plan's bulk data must not live only in
  `cacheDir` under an age-based sweep.
- The Fylz provider's own path resolution only checks for an *escape from the root*, not for
  directory symlink loops, so a source tree containing a link to an ancestor can be walked
  effectively without bound; SAF display names are not guaranteed free of `/`, `\`, `.`, `..`, or
  control characters from every third-party provider.
- The queue and journal are as M3.4 leaves them: plan tables, `claim`-style claims, tags,
  `Result.success()`, flag-cancel, `TargetPlanner(nameIndex)`, `updateItem`, the `busy`/progress hooks
  on `callStreaming`, `bindIsolatedService` instances (which key a separate process **record** by
  instance name but keep the manifest's `android:process` name — finding the write instance by pid,
  not by a distinct process name, is `UNVERIFIED` and recorded for the device check). `DecoderClient`'s
  browsing connection runs its streaming drains and transactions on a **fixed 4-thread pool**; reusing
  that pool for a create's feeder/drain/transaction is how a concurrent browse fill and a compress can
  deadlock each other permanently, since the liveness watchdog is paused whenever a call reports itself
  "busy" (exactly what a slow provider read looks like) — this is not a hypothetical, it is the direct
  consequence of "shares `callStreaming`'s machinery" read literally.
- `FylzV1App.kt` and `FylzAppShell.kt` sit on their ratchets after M3.4 (M3.4 lowers both; M3.5 must
  not raise them). `fylz.compress` is enabled inside archives (sources read through the archive
  provider: one materialisation per entry, 512 MiB cap, links/encrypted refused — but the archive
  provider's SAF columns do not expose link/encryption flags, so a naive walk only discovers the
  refusal when the fill fails mid-archive, after everything already written is thrown away). The app
  declares a `dataSync` foreground service; on Android 15 that service class is capped at 6 hours of
  execution per rolling 24 hours, so an unbounded "restart from scratch on every system stop" retry
  loop for a very large create can be stopped at the same point every time and never finish.

## 2. Decisions

### 2.1 The sheet and the actions

`fylz.compress` (selection bar slot 80, unchanged) opens the **Compress sheet**
(`ui/actions/CompressSheet.kt`, reached as a dialog the action opens directly — **not** a registry
`Menu`, since a `MenuId.COMPRESS` with no registered menu-actions would render nothing and
`NoHardCodedMenusTest` only requires the file to live under `ui/actions/`, which this satisfies
without inventing menu items that do not exist). Fields:

| Field | Values | Default |
|---|---|---|
| Format | `zip`, `tar.gz`, `tar.xz`, `tar.zst` (also `tar.bz2`, `tar.lz4` under "More" — beyond the plan's list, logged); `7z` shown disabled: "7z creation arrives with the 7-Zip pack" | `zip` |
| Level | Fast / Normal / Best, mapped per format (§2.4; every tier is a **distinct** value per format) | Normal |
| Password | disabled field: "Password protection arrives with M5" | — |
| Split size | Off, 100 MB, 700 MB, 4 GB (FAT32: 4 GiB − 1), Custom (MB) | Off, or **4 GB preselected** when the destination is vfat and the estimated size (with a per-entry overhead margin, §2.2) exceeds it, shown as a dismissible suggestion, not a hard requirement |
| Store paths relative to selection | on: entries are named from the path below the tree's grant root (or the in-archive path, when compressing from inside an archive); off: prefixed with that same root-relative path of the *parent* folder the selection sits in | on |
| Archive name | text; default `<folder name>` when the whole listing or several items are selected, `<item name>` for one item, plus the format's extension | |
| Destination | the current folder (when writable); "Choose…" opens `DestinationChooserSheet` (archive tabs filtered) or "Save as…" (the system picker, unsplit output only, for a destination with no tree grant such as Drive) | current folder, or "Choose…" required inside an archive |

Inside an archive location the destination cannot be the current folder (read-only): the sheet opens
with "Choose…"/"Save as…" required. The overlay's `fylz.protect` splits: its "Create ZIP" without the
AES switch opens this sheet with the picked sources; with the switch on it keeps the legacy
`createZip` path (§2.7). `FylzV1App`'s `archiveCreator`, `compress()` and the sources plumbing move to
`ui/actions/CompressFlow.kt` (the flow state, the sheet, the preflight/conflict sheets it hosts,
"Scanning…" while the source walk runs); `FylzV1App` keeps one line. Golden test: `fylz.compress`'s
`enabledWhen` is unchanged, so no fixture changes; the sheet is UI.

### 2.2 Planning: sources, names, sizes, preflight, conflicts

`CompressRequest(sources: List<Uri>, format, level, split: Long?, relativeToSelection: Boolean,
archiveName, destinationFolder)`; `CompressPlanner.plan(request, ui | Headless(conflictPolicy))`
(headless serves Addendum §C4/§C5's `compress` step, if and when that step is built; the seam exists
now with defined semantics — see below — rather than being deferred wholesale).

1. **Walk**, bounded: `DocNode.children` recursively, keeping a **visited set of document ids** and a
   **depth cap of 64** to stop a directory-symlink loop; entries named `.fylz-part-*`, `.fylz-trash`,
   or otherwise recognised as Fylz's own staging/trash convention are skipped, matching the search
   engine's own exclusion. Sources inside an archive location are planned from the **`ArchiveCatalog`
   tree**, not from SAF columns: kind, `encrypted`, `uncompressed` against `min(maxFileBytes,
   512 MiB)`, and the archive's own `policyAllowed` are all known there and checked **at plan time**,
   so links, encrypted entries, oversized entries or a refused archive fail the plan with the specific
   entry named, before any byte is written; a source drawn from a stream-format or solid-7z archive
   with more than a small entry count (`ARCHIVE_ENTRY_SOURCE_LIMIT = 200`) is refused with "Extract it
   first" rather than accepted at O(N) full-decompression cost. Every regular file becomes a manifest
   entry `{ordinal, archivePath, sourceUri, mtimeMillis}` — **no size here** (§2.2 step 3 explains
   why); each directory a directory entry. The walk shows "Scanning…" with Cancel; entries beyond
   `maxListingEntries` (200,000) or beyond 10,000 (a warning, not a refusal — see §2.2 step 4) are
   reported in the summary.
2. **Paths.** `archivePath` = the path relative to the selection's common root when
   `relativeToSelection`, else that root's own path prefixed. The "root" is **not** `FolderTab`'s
   display-name stack (those are UI labels, not real path segments); it is derived from the source's
   own **document id** where the provider exposes a `root:relative/path` shape (Fylz's own provider
   and most SAF providers do), falling back to the tree grant's root document when it does not; inside
   an archive it is the in-archive normalised path the tree already carries. The overlay's
   multi-picked files (which share no folder) and headless requests always get flat, uniquified names
   regardless of the toggle. Every path is then **sanitised**: `/` and `\` mapped to `_` (a
   provider's display name is not guaranteed free of them), a leading `.`/`..` or an empty segment
   prefixed with `_`, C0 control characters stripped, and names uniquified **case-insensitively within
   each directory** (so two providers' same-cased and differently-cased files never collide) —
   independently, every path also passes the engine's `policy::validate_path` before it becomes an
   `ENTRY` frame (§2.5), so a hostile or malformed name can never reach the writer.
3. **Sizes are read at feed time, not planned.** A `COLUMN_SIZE` at plan time can be hours old by the
   time a queued create actually runs, and is `null` or stale for some third-party and every
   archive-provider source; the manifest therefore carries no size, and the feeder (§2.3 step 4) opens
   each source immediately before sending its bytes and uses **that** open's authoritative length
   (`statSize` for a regular-file descriptor, else a fresh `COLUMN_SIZE` query, else "unknown"). The
   **planning-time size estimate** used for the space check and the split/vfat suggestion is a
   best-effort `Σ COLUMN_SIZE` (or a recursive walk for directories, as `PreflightGathering` already
   does) plus a **fixed 5% + 1 KiB-per-entry overhead margin** (per-entry container overhead is real:
   roughly 76 B + 2×name + extras + a descriptor for zip, 512 B + padding for tar); it is only ever a
   guide, never a written-format guarantee, since compression is unknown until it happens and
   incompressible data can grow slightly under deflate.
4. **A source of genuinely unknown size** (no `COLUMN_SIZE`, not a regular file) is allowed for `zip`
   (streaming tolerates it) and for `tar.*` is **spooled to a bounded temp file in `cacheDir` first**
   (so its exact size is known before the tar header is written; pax's zero-pad-on-shortfall behaviour
   makes "hope the declared size matches" unacceptable for tar) — capped at `maxFileBytes`, and a
   source that exceeds the cap while spooling fails the plan for that entry, named. This is a plan-time
   decision (which sources need spooling) even though the size itself is read at feed time.
5. **Preflight** for the output: the archive name (and, if split, the whole numbered set) through
   `PreflightPolicy` (FAT characters, length; VFAT 4 GiB − 1: when the destination is vfat and the
   estimate exceeds it and split is Off, a **warning**, not a hard block, since the estimate is only
   ever approximate); a destination inside one of the selected source folders is refused outright
   (compressing a folder into itself).
6. **Conflicts** for the output are resolved **as one unit**, never per part: a candidate base name is
   free only when **no** `base`, `base.001`, `base.002`, … exists at the destination; Keep-both
   produces `base (2)[.ext]` and every part is named from that new base; Replace recycles **every**
   existing `base` **and** every existing `base.\d{3}` (an old, longer split set is not left partially
   overwritten and partially stale) before the new set is finalised. Resolutions go into the plan, fed
   through the M3.4 `NameIndex` (one destination listing). Headless: the request's policy.
7. **Manifest persistence in SQLite, not a cache file.** The manifest is written as rows —
   `create_manifest(operation_id, ordinal, kind, path, source_uri, needs_spooling, spooled_path)` —
   in the **same transaction** as `create_plans(operation_id, format, level, split, relative,
   archive_name, destination_uri, total_estimate, entry_count, catalog_key?)` and
   `create_plan_items(operation_id, item_index, requested_name, conflict_policy, name_override)`
   (§2.5 adds the columns a run needs). A cache file is evictable and swept by age, which a long queue
   wait can outlive; rows share the operation's own lifetime, are read with a cursor rather than
   loaded whole by `refreshOperations`, and are deleted wherever the operation is. The journal's
   `OperationItem`s are the **top-level sources** (one per selected item, as today's ARCHIVE type,
   `expectedBytes` = the plan-time estimate for that item), so the history dialog shows what the user
   selected; the operation's `destination` is the archive's final Uri (or the first part's, when
   split) after finalise. `Data` = `KEY_TYPE = ARCHIVE`, `KEY_OPERATION_ID`; the request is tagged
   `op:<id>`; `enqueueCreate(id)` mirrors `enqueueExtract`.

### 2.3 One write pass in `:decoders:write`, frames in, archive out — with a stated concurrency invariant

`TransferWorker.doWork` accepts `ARCHIVE` and calls `ArchiveCreator.run(opId, stopReason)`:

1. **Claim** exactly as M3.4 (`claim(op, fromStates)`, tag lookup, `Result.success()` on refusal,
   **plan rows missing or unreadable → `FAILED / PLAN_UNREADABLE`, not retried**), recompute free
   space, build the `NameIndex`. A prior spooled temp file for any manifest row is checked and
   re-spooled if missing.
2. **Output document(s):** for each `create_plan_items` row create the staged document
   (`stagingName(opId, idx, name)`, `application/octet-stream`, created-name check), **record its
   staging Uri in `create_plan_items` before its first byte** (P0.6's own rule, generalised — this is
   the column rev 1's schema was missing). Split parts are opened lazily as the byte count crosses
   each boundary and inherit the base name resolved in §2.2 step 6, so `name.zip.001`, `.002`, … stay
   a matched set regardless of conflict resolution.
3. **The concurrency invariant, stated explicitly** (this replaces rev 1's "shares `callStreaming`'s
   machinery" and is the fix for the review's deadlock finding): the feeder, the drain, and the Binder
   transaction each run on their **own dedicated executor** — a small fixed pool used **only** for
   create operations, never the browsing pool `callStreaming` uses for `inspectArchive`/`listArchive`/
   `extractEntry` and never the extraction pool M3.4 dedicates to `extractRanges`. Concretely:
   (a) the feeder writes into `in` until it sends `FINISH` or `ABORT`, then closes its write end of
   `in`, and stops early only on `EPIPE` (the engine died) or a signalled cancellation — it never waits
   on the drain; (b) the drain reads `out` until EOF or a protocol violation, and never stops reading
   without closing its read end of `out` — it never waits on the feeder; (c) neither the feeder's
   source reads nor the drain's destination writes may run on a pool shared with anything that can
   itself be "busy" waiting on `:decoders`, because the create call's own liveness watchdog is paused
   whenever *it* reports "busy", and two independently-busy calls sharing one small pool is exactly
   how they starve each other forever; (d) **no transparent retry** of the create call itself (unlike
   `callStreaming`'s at-most-once retry for browsing, which would produce a second, interleaved
   `writeArchive` transaction into the same demuxer); (e) a source read is opened with
   `openFileDescriptor(uri, "r", cancellationSignal)` so a genuinely hung third-party provider can be
   interrupted by Cancel rather than blocking the feeder forever, and a **10-minute cap on cumulative
   time spent "busy" on a single source** fails that entry with `SOURCE_UNREADABLE` rather than
   hanging indefinitely; (f) every exit path — success, `ABORT`, cancellation, a drain failure, the
   engine dying — closes **all four** client-side pipe ends (its own copies of both read and write
   ends on both pipes) in the transaction job's `finally`, exactly as the existing single-pipe
   `streamOnce` path already does; nothing is "closed after the transaction is issued" from a second
   thread, which would race the Binder call's own descriptor duplication.
4. **Feeder** (its own executor thread per §step 3): writes `FZW1` frames into `in`. Frame protocol,
   made precise (§2.5 restates it in the engine's terms):

   ```
   MAGIC  "FZW1"
   ENTRY  0x01 ordinal:u32 kind:u8(1 file,2 dir) mtime:i64(epoch ms; 0 or absent -> "now", clamped to a 32-bit time_t) mode:u32(masked to 0o7777; SAF has none, so 0o644/0o755 by kind) path_len:u32(≤65536, including a directory's trailing slash) utf8_path
   DATA   0x02 ordinal:u32 len:u32(1..=1 MiB) bytes                          -- only between an open file ENTRY and its END; a directory ENTRY takes no DATA/END
   END    0x03 ordinal:u32 bytes:u64                                        -- bytes MUST equal the sum of that ordinal's DATA lengths
   FINISH 0x04                                                              -- only when no entry is open; closes the archive normally
   ABORT  0x05 msg_len:u16 msg                                              -- only when no entry is open (an in-flight entry is finished with END first); the engine returns Cancelled and poisons the writer (§2.5) before closing
   ```

   Ordinals strictly increasing; anything else (`DATA`/`END` with no open `ENTRY`, `FINISH`/`ABORT`
   while an entry is open, bytes after `FINISH`, an `END` whose count disagrees with what was sent) is
   a **protocol violation** on the reading side, mapped to `ArchiveError::Failed("bad frame")` — never
   silently tolerated. For each manifest entry, in order: `ENTRY` using the size-at-open-time rule of
   §2.2 step 3/4 (unknown size sends no size field at all — the engine unsets it for zip and refuses it
   outright for tar, per format, rather than the feeder guessing), then `DATA` in 512 KiB blocks
   counted against the **opened** length, then `END`. **A byte count that exceeds the size the feeder
   itself observed at open aborts that entry** (a source growing while it is read is the one case
   worth stopping for — a shortfall is not, since zip records the true count and tar sources were
   already spooled to a known size); an unreadable source or an archive-entry refusal discovered
   mid-read also aborts that entry. Whether one entry's abort fails the whole archive or only that
   entry is a build-time choice recorded in §2.3 step 6 (kept as "one entry's abort fails the whole
   archive", for the same reason M3.5 has no partial-success create: a compress that silently drops a
   file the user selected is a worse outcome than restarting).
5. **Engine** (Rust `write_frames(in_fd, out_fd, &FormatOptions)`; §2.5): **pins the calling thread's
   locale to `C.UTF-8`** for the duration of the call (bionic and modern glibc both provide it),
   asserting `nl_langinfo(CODESET) == "UTF-8"` afterward and failing fast with `Unsupported` if it does
   not — this is what makes `hdrcharset=UTF-8` (and any non-ASCII name at all, for zip and pax alike)
   actually work rather than failing every time, as it does in the unpinned C locale; the previous
   locale is restored and the new one freed when the call returns. It parses frames with bounds (the
   feeder is trusted code but the parser is defensive anyway: malformed structure is `Failed`, never a
   panic), configures the writer from `options` (`FormatOptions { format, level, threads = 1 }`), and
   for each `ENTRY` sets pathname (UTF-8; **`policy::validate_path` runs here too**, defence in depth
   against a manifest built from a stale plan), size (or unset, per format rule), filetype, perm,
   mtime, calls `archive_write_header`, streams `DATA` through `archive_write_data`,
   `archive_write_finish_entry` on `END`; `FINISH` → `archive_write_close`. **On `ABORT`, a protocol
   violation, or any internal error, the writer is explicitly poisoned before it is freed**: a
   `poisoned: Cell<bool>` makes the `open2` writer callback return `-1` on its next call regardless of
   what `close`/`free` would otherwise try to flush, which is what stops `archive_write_free`'s
   unconditional close-on-drop from quietly emitting a well-formed truncated archive — confirmed by the
   review as the actual, tested failure mode of a naive "just free it" abort. The `open2` writer
   callback itself `write_all`s every block to `out_fd` (`bytes_per_block = 0`, pass-through); `EPIPE`
   from that write is mapped to `ArchiveError::Cancelled` and also poisons the writer. Returns
   `ArchiveWriteReportRecord { entries, bytes_in, bytes_out }` or an error.
6. **Drain** (its own executor thread per §step 3): reads `out` into the current staged document,
   counting bytes and feeding a SHA-256 per part; at each `splitSize` boundary closes the part and
   opens the next (§step 2's lazily-opened, pre-resolved names); records `Σ bytesOut == the engine's
   reported `bytes_out`` as an integrity check independent of the per-part digests. Progress = **source
   bytes fed** (the feeder's count) **/** the plan-time estimate (compressed output size is unknown in
   advance), throttled (`ProgressWriteThrottle`), the notification bar 0..1000 with "N of M files ·
   X of Y MB".
7. **After the pass:** verification. **Primary check, always run when `VerifySettings.shouldVerify`
   applies:** every staged part is re-read and its SHA-256 compared with the drain's own digest for
   that part — copy's model, and the one check that works uniformly for a single archive or a split
   set, on any destination, seekable or not, small or large. **Secondary check, only for an unsplit
   output whose staged descriptor is seekable and whose total entry count is within
   `maxListingEntries`:** a direct `listArchive` call on the write instance into a **temporary** sink
   (never through `ArchiveCatalog`, which must never key a persisted `.fzl`/summary on a staging Uri
   that disappears at rename) checks the listing's entry count and per-entry sizes against the
   manifest, ignoring the archive's own `policyAllowed` (irrelevant here). A mismatch in either check
   is `VERIFICATION_FAILED`. Then `finalizeTarget` for the output document(s), **sequentially, last
   part first down to `.001` last** (so a reader never sees a `.001` without every later part also
   present), rolling back any already-finalised part if a later one fails to finalise.
8. **Cancel, stops, recovery, retry.** Flag-cancel (`create_plans.cancel_requested`, the
   notification's Cancel receiver), `Result.success()` for every journaled outcome. A system stop
   (`PAUSED_BY_SYSTEM`, `Result.retry()`) causes a full restart from scratch (an archive cannot be
   resumed mid-stream); **restarts are bounded to 3** (`create_plans.restart_count`), after which the
   operation is `FAILED / TOO_MANY_INTERRUPTIONS` rather than looping — a `dataSync` foreground
   service's own platform-imposed execution cap means an unbounded restart of a very large create
   could otherwise be stopped at the same point on every attempt and never finish; a foreground-service
   timeout specifically is mapped to `FAILED` with that message rather than silently retried past the
   bound. Engine death mid-call (`DeadObjectException`) is treated as one restart attempt, then
   `ARCHIVE_WRITE_FAILED`. An app-side feeder/drain failure takes precedence over the engine's own
   `Cancelled` when both occur (the app-side reason is more specific). `recover` treats ARCHIVE-with-plan
   like EXTRACT (staging for `create_plan_items`, not `OperationItem`, is what gets deleted); retry
   (`ReclaimCreate(opId)`) re-queues from the plan (which, being a full restart, resets progress);
   legacy ARCHIVE rows (zip4j, no plan) are excluded from all of this.

### 2.4 Formats and levels

| Format | libarchive setup | Fast / Normal / Best | Ceiling and why |
|---|---|---|---|
| `zip` | `set_format_zip`, `zip:compression=deflate`, `zip:hdrcharset=UTF-8` | 1 / 6 / 9 | none needed; 64 KiB buffer |
| `tar.gz` | `pax_restricted` + `gzip`, `pax:hdrcharset=UTF-8` | 1 / 6 / 9 | none |
| `tar.xz` | `pax_restricted` + `xz`, `xz:threads=1` | 1 / 6 / 9 | level 6 ≈ 93 MiB, 7 ≈ 185 MiB, 9 ≈ 673 MiB (`lzma_easy_encoder_memusage`, measured against the vendored xz); `:decoders`' 256 MB target caps the offered range at 6 |
| `tar.zst` | `pax_restricted` + `zstd` | 1 / 3 / 19 | level 19 ≈ 89.5 MiB, 20 ≈ 193.5 MiB, 22 ≈ 833.6 MiB (`ZSTD_estimateCStreamSize`, measured against the vendored zstd); MT is compiled out (`ZSTD_MULTITHREAD_SUPPORT OFF`) so `threads` is not offered |
| `tar.bz2` | + `bzip2` | 1 / 9 / 9 | none |
| `tar.lz4` | + `lz4` | 1 / **3** / 9 | Fast/Normal were both 1 in rev 1; Normal now uses level 3 (the first HC tier) so the three tiers are distinct |

Every format's three tiers map to three **distinct** values (rev 1 collapsed xz's Normal/Best and
bzip2's Normal/Best to the same number, and lz4's Fast/Normal; xz and bzip2 keep the collapse where
the format itself has no finer knob at the top end — xz stops offering above 6 for the memory reason
above, so "Best" for xz is deliberately equal to "Normal" and the sheet labels it "Best (memory
limited)" rather than hiding a tier). Options are applied with `archive_write_set_options` from a
string the Rust side builds from typed values, never from user text. A **level 0 / store** path
exists for the headless API only, defined as: no compression, still through `set_format_zip` with
`compression=store` — used by nothing in the sheet's own UI.

### 2.5 Engine, FFI, IPC and persistence amendments

`fylz-archive` gains `write.rs`: write-side `sys` externs (`archive_write_new/free/close/header/
data/finish_entry/open2/set_bytes_per_block/set_options/set_format_zip/set_format_pax_restricted/
add_filter_{gzip,xz,zstd,bzip2,lz4}`, entry setters, plus `uselocale`/`newlocale`/`freelocale`/
`nl_langinfo` for the locale pin), a `Writer` over `open2` whose writer callback checks a
`poisoned: Cell<bool>` first and `write_all`s into a caller fd otherwise, mapping `EPIPE` to
`ArchiveError::Cancelled` and poisoning on any error; `FormatOptions` → option string;
`Writer::add_entry(&EntryMeta)`, `write_data(&[u8])`, `finish_entry()`, `abort()` (sets `poisoned`,
then frees), `close()` (only reachable when not poisoned). `write_frames(in_fd, out_fd,
&FormatOptions) -> Result<WriteReport, ArchiveError>` pins the thread locale (above), parses `FZW1`
per the precise grammar of §2.3 step 4 (bounded; any violation → `Failed("bad frame")`), and exposes
`write_frames_io<R: Read, W: Write>` as the testable core with the fd-based function as a thin
adapter — a fd-based fuzz entry point would otherwise need a real pipe (which deadlocks a
single-threaded fuzzer above 64 KiB) or a slow temp file. Round-trip tests per format through the
existing `inspect`/`extract`: byte-exact content, non-ASCII names verified **on a thread whose locale
the test does not itself set** (proving the engine's own pin, not the test harness's), mtimes, empty
directories, unknown size on zip (and its refusal on tar), the shortfall-is-benign / excess-aborts
size rule, ABORT producing an output that **fails to parse** as an archive (the poisoning test),
levels producing smaller output than store on compressible content, cancellation via a closed `out_fd`
→ `Cancelled`. `build.rs`'s incorrect "bundled AES" comment is corrected in the same commit that
touches `build.rs` for any other reason, or on its own if none does.

`fylz-ffi-android`: `archive_write_frames(in_fd, out_fd, options: WriteOptionsRecord) ->
Result<ArchiveWriteReportRecord, ArchiveEngineError>`; `WriteOptionsRecord { format: WriteFormatRecord,
level: u32 }`.

AIDL: `ArchiveWriteResult writeArchive(in ParcelFileDescriptor input, in ArchiveWriteOptions options,
in ParcelFileDescriptor output)`; Parcelables `ArchiveWriteOptions(format, level)`,
`ArchiveWriteResult(outcome, message, entries, bytesIn, bytesOut)`.

`DecoderClient.writer()` (a distinct `bindIsolatedService` instance, `"write"`) and `callTwoPipes(...)`
run on the **dedicated create executor** of §2.3 step 3 — a new, small fixed pool distinct from the
browsing pool and from M3.4's extraction pool — never sharing `callStreaming`'s existing pool; both
pipes are client-created and client-closed on every exit path per §2.3 step 3(f). If a device shows
`bindIsolatedService` instance names do not yield a distinguishable process for RSS measurement
(§2.9, `UNVERIFIED`), the fallback recorded for M3.4's extraction instance (a second manifest
`<service>` entry with its own `android:process` suffix) applies here too.

Persistence (schema v3 → v4, additive): `create_plans` (with `restart_count`), `create_plan_items`
(now with `staging_uri`, `sha256`, `bytes_written`, per-part `state`), `create_manifest` (§2.2 step 7).
`OperationsDao` generalisations of M3.4's methods (`putWithPlan`, `claim`, `retry`,
`setCancelRequested`) to both plan kinds; rows are deleted with their operation and are never subject
to `ArchiveCacheSweeper`'s age-based sweep (they are not cache; `create_manifest`'s `spooled_path`
files under `cacheDir/archive-work/` *are* cache and are swept, but a run in progress re-spools a
missing one rather than treating its absence as fatal, per §2.3 step 1). `onUpgrade` test v3 → v4.

### 2.6 Verification, stated precisely

As §2.3 step 7: a SHA-256 re-read of every staged part (always, when `VerifySettings` applies) is the
primary, universal check; a structural re-listing (entry count and sizes) is a secondary check limited
to an unsplit, seekable, small-enough output, run directly against the write instance rather than
through the persistent catalog. ZIP CRCs were computed by libarchive while writing and are checked
only by a future extraction (M3.8's "Test archive"); this design does not add a CRC self-check on
create. Split sets get the primary check only (logged).

### 2.7 The legacy path shrinks to AES-encrypted ZIP creation

`ArchiveService.createZip` stays only for the overlay's AES-256 "Create ZIP" (zip4j) until a real
crypto backend lands; it keeps its cache staging and **its own** folder refusal (unchanged behaviour,
against the plan's "folders included recursively" for the *new* path only — the AES path's limitation
is logged, not silently inherited as acceptable forever). Its numbers come from `ArchiveLimits`.
Everything else creates through the queue.

### 2.8 Fixtures and tests

Rust: round trips for every format and level tier; Unicode names verified on an unpinned-locale
thread; directories and nested paths; `relative` prefixes; unknown size on zip / refused on tar; the
shortfall-benign / excess-aborts rule; the poisoning test (ABORT's output does not parse); protocol
violations (`DATA` with no open entry, `FINISH` mid-entry, bytes after `FINISH`, an `END` whose count
disagrees) → `Failed`, never a panic; cancellation via `out_fd` closure; a `write_frames` fuzz target
over the `io` entry point (not the fd one) with cheap default options (store or an unfiltered pax, to
keep xz's 93 MiB per-iteration allocation out of the default fuzz loop), 60 s in CI's smoke step with
the job timeout raised to account for it; a committed golden `tree.zip.created` (fixed mtimes, mode,
no uid/gid, level 6, locale pinned) compared byte-exact to catch writer drift — a `tar.gz` golden
additionally disables the filter's embedded timestamp (`gzip:!timestamp`) for determinism.

Kotlin (Robolectric, with the file-backed-pipe caveat below applied throughout):
`CompressPlannerTest` (bounded walk with a synthetic symlink loop fixture, staging/trash names
skipped, relative-vs-prefixed paths derived from a document id, archive-provider sources refused for
links/encryption/oversize/refused-archive/stream-format-entry-count at plan time, name sanitisation
and case-insensitive uniquification, unknown-size spooling for tar, split/vfat as a warning not a
block, headless mode with its defined semantics); `ArchiveFrameWriterTest` (frames byte-exact against
a golden `.fzw`); `ArchiveCreatorTest` (a fake decoder stub that parses received frames and writes a
deterministic pseudo-archive to `out`; asserts staging → finalise, `KEEP_BOTH`/`REPLACE`/`SKIP` applied
to a whole split set at once with old stale parts recycled on Replace, parts finalised last-to-first
with rollback on a later failure, an excess-over-observed-size abort deletes every staged output, an
unreadable source aborts, cancel via the flag with all four pipe ends closed, a system stop restarting
from scratch up to the bound then failing, progress by fed bytes, per-part SHA-256 plus the
`Σ bytesOut` check, the secondary listing check skipped for split/oversized/non-seekable outputs,
`Result.success()` on every outcome). **Robolectric's `createPipe()` is file-backed** (writes never
block, reads return −1 before data arrives): the fake decoder and the client-side stream wrappers
apply the same retry-on-premature-EOF rule M3.3/M3.4 already established, and the genuine two-pipe
deadlock, EPIPE-on-engine-death, and drain-failure paths are **not** provable under Robolectric — a
plain-JVM test using small, blocking `PipedInputStream`/`PipedOutputStream` pairs (no Android
dependency) is added specifically to exercise both pipes full at once, a drain failure mid-stream, the
engine returning early, and cancel while everything is blocked, behind the same stream seams
`write_frames_io` exposes on the Rust side and an equivalent Kotlin seam on the client side.
`TransferWorkerCreateTest` (`TestListenableWorkerBuilder`); `onUpgrade` v3 → v4; recover/retry for
ARCHIVE-with-plan including the restart bound; golden test unchanged; `FylzV1AppSizeTest` under the
ratchets.

### 2.9 Device checks (`DEVICE_CHECKS.md` §20, "M3.5 — Compress")

1. Compress a folder tree to `zip`, `tar.gz`, `tar.xz`, `tar.zst` into Downloads; open each with a
   third-party app and with Fylz (M3.3 browsing); names with non-ASCII characters intact — this is
   the direct proof that the engine's own locale pin works on a device, not just against the host
   build; mtimes within a few seconds.
2. A 6 GB folder to `zip` on internal storage: progress by bytes; confirm whether the write instance
   is distinguishable by pid/RSS from the browsing `:decoders` process (`UNVERIFIED`, §1) and record
   how it was found; try xz Best (level 6) on another run and confirm RSS stays under the 256 MB
   target.
3. Split at 700 MB: parts named `.001…`; `cat` them back together and open; a vfat card with a
   > 4 GiB source and split Off shows the warning, not a block; create the same name twice with
   Keep-both and confirm the second set's parts share one new base; Replace an existing longer split
   set with a shorter one and confirm no stale trailing part remains.
4. Cancel mid-way, including while a source read is genuinely slow (a throttled network share):
   confirm Cancel actually interrupts it (the `CancellationSignal` path) rather than waiting out the
   10-minute cap; no staged parts remain; the queue behind it runs.
5. Kill the app mid-create: on relaunch the operation restarts from scratch; force enough restarts to
   hit the bound and confirm it fails cleanly rather than looping; if reachable, force a foreground-
   service execution-limit stop on a very long create and confirm the mapped failure.
6. Compress from inside a browsed archive, including a folder containing an encrypted or oversized
   entry (confirm it is refused at plan time, before any output exists, not mid-archive); compress
   into another tab's folder via "Choose…" and into an unmounted-tree destination via "Save as…".
7. The overlay's AES "Create ZIP" still works (zip4j) and still refuses folders; its plain
   "Create ZIP" opens the sheet.
8. `adb logcat | grep avc`: no denials on the two pipes or the write instance.

## 3. Sequencing and gates

Three commits, each green on the full gate (Gradle test/lint/assemble; the `core` gate incl.
`cargo +nightly fuzz build`; three-ABI `cargo ndk`):

- **M3.5a (engine):** `write.rs`, the locale pin, `write_frames`/`write_frames_io`, the poisoning
  mechanism, options, the size rules, tests, the `write_frames` fuzz target added to CI in this same
  commit (with the job timeout raised), `build.rs` comment fix, FFI `archive_write_frames`.
- **M3.5b (queue):** AIDL `writeArchive` + Parcelables; `DecoderService`; `DecoderClient.writer()` +
  `callTwoPipes` on the dedicated create executor; `CompressPlanner` (+ headless, + the bounded walk,
  + archive-source planning against `ArchiveCatalog`); `create_plans`/`create_plan_items`/
  `create_manifest` + DAO generalisation + v4 upgrade test; `ArchiveCreator` (including the
  poisoning-aware abort path, the restart bound, the split-set-as-one-unit conflict handling);
  `TransferWorker` ARCHIVE; enqueue/recover/retry; the plain-JVM two-pipe concurrency test; all
  non-UI Kotlin tests. (5b compiles and its tests pass without 5c's UI.)
- **M3.5c (UI, legacy, docs):** `CompressSheet`, `CompressFlow` out of `FylzV1App` (ratchet
  lowered), the overlay split, `createZip`'s folder-refusal limitation logged, `ARCHITECTURE.md`
  (create path, the write instance and its concurrency invariant, levels/memory table, split
  semantics, the locale requirement), `DEVICE_CHECKS.md` §20, `REVIEW_QUEUE.md`, PROGRESS, PR #19.

`REVIEW_QUEUE.md` entry for M3.5 (log-and-continue): 1. 7z creation deferred to the 7-Zip pack, with
the reasoning restated precisely: an isolated process cannot create a temp file by path anywhere the
sandbox permits, a passed directory descriptor does not grant create rights, and a `memfd` breaches
the memory target; the only in-sandbox route is a vendored libarchive patch accepting a caller fd for
`__archive_mktemp`, gated on the same open question `DEVICE_CHECKS.md` §18 item 7 already tracks.
2. Password disabled until a real crypto backend exists; the same gap blocks M3.9's AES read, M5, and
M3.10. 3. Native writing runs in an isolated instance with two client-owned pipes on their own
executor, distinct from both the browsing and the M3.4 extraction pools — sharing either would
deadlock a concurrent browse/extract against a compress. 4. Level ceilings (xz 6, zstd 19) against the
256 MB target, with measured figures. 5. Split volumes are raw `.001` parts for every format, resolved
and finalised as one set; **Fylz cannot yet open the split sets it writes** — a read-side gap the
plan's own table does not flag, named here for the milestone that closes it. 6. "Relative to
selection" is derived from the source's document id or the archive's in-archive path, never from
`FolderTab`'s display-name stack. 7. A source that grows past its opened size aborts that entry; a
shortfall is benign for zip and is prevented for tar by spooling sources of unknown size first.
8. Archives created above 10,000 entries, 4 GiB, or the ratio limit need consent to *extract* in Fylz
under M3.4's own rules; above 200,000 entries Fylz cannot even list what it just wrote. 9. Compressing
from inside an archive costs one materialisation per source entry (512 MiB cap) and is refused above a
small entry count for stream/solid-format sources. 10. `tar.bz2`/`tar.lz4` exceed the plan's named
format list. 11. A destination with no tree grant (Drive) is reachable only through "Save as…",
unsplit. 12. The crypto stub gates three later milestones (item 2). 13. Non-ASCII names depend on the
engine pinning its own thread's locale to `C.UTF-8` at write time — without that pin, every non-ASCII
name fails to write at all, in every locale, not merely as mojibake; verified against the host build.
14. Directory symlinks are followed by the Fylz provider's own path check; the walk keeps its own
visited-set/depth bound rather than relying on the provider. 15. A restart after a system stop starts
the whole create over, bounded to 3 attempts. 16. Headless `compress` mode exists with defined
semantics but no consumer yet. 17. Schema v4.

## 4. Risks

- **The locale pin is process-wide `uselocale`, thread-scoped by design**, but if any other code on
  the same isolated process's threads assumes the C locale between the pin and its restore, that
  assumption breaks for the duration of one create call; the write instance's only job is this call,
  so the blast radius is contained by construction — record this reasoning rather than re-deriving it
  under review.
- **Bounded restarts still cost real work**: three full restarts of a 6 GB create is real time and
  battery; if device testing shows system stops are common enough to matter, the honest fix is a
  smaller foreground-service execution budget check that warns the user before starting a create that
  is unlikely to finish in one window, not a workaround inside this design.
- **7z absence** is visible in the sheet with a reason; the 7-Zip pack owns it, and this design's own
  frame protocol and queue plumbing are written so that pack can reuse them (a 7z writer is a second
  `WriteFormatRecord` variant and, if the sandbox question resolves favourably, no protocol change).
- **Split-read gap** (item 5): flagged so the milestone that adds multi-volume ZIP/7z *read* support
  does not discover this as a surprise regression report.

## 5. Amendments this design makes to earlier designs

- Part 3 / M3.4: `fylz-archive` gains a write side (`write.rs`); `ArchiveError::Cancelled` is now used
  by the writer too, with an explicit poisoning mechanism it did not previously need; the fuzz set
  gains `write_frames` over an `io`-based entry point (a pattern the extract-side fuzz targets did not
  need, recorded for consistency).
- M3.4: `DecoderClient` gains `writer()` and `callTwoPipes` on a **new, third** dedicated executor
  (browsing / extraction / creation, each isolated from the others); the plan-table pattern is
  generalised (`putWithPlan`, `claim`, `retry` for both kinds); `ReclaimCreate`; the restart-bound
  concept is new to the queue (M3.4's extraction had no equivalent, since extraction is resumable
  where creation is not).
- M3.3: `ArchiveCacheSweeper` is explicitly **not** the mechanism for `create_manifest`/plan-table
  durability (only its `spooled_path` cache files are swept); the archive-provider's own tree/catalog
  is now a planning-time input, not only a browsing-time one.

## 6. Recorded upgrades (not in M3.5)

- 7z creation, once the sandbox question resolves (either the vendored temp-file patch, or a decision
  to accept 7z creation in the app process as a documented exception).
- A real crypto backend, unblocking AES-256 ZIP creation on the new path, M3.9's AES read, and M3.10.
- Reading the split sets M3.5 writes.
- A smaller, adaptive foreground-service budget warning ahead of a create unlikely to finish in one
  execution window.

## 7. Review findings and disposition (rev 1 → rev 2)

Blockers, both adopted: (1) **locale/charset** — the engine now pins the calling thread's locale to
`C.UTF-8` for the duration of `write_frames` and asserts it took effect, rather than relying on the
platform's ambient locale; every claim in rev 1 that non-ASCII names would come out as "mojibake" is
corrected to "fail to write at all" and the fix is stated as an engine responsibility, tested on an
unpinned-locale thread. (6) **the deadlock** — rev 1's "shares `callStreaming`'s machinery" is replaced
by an explicit concurrency invariant (§2.3 step 3) and a **third**, dedicated executor for creation,
distinct from both the browsing pool and M3.4's extraction pool, with cancellable source reads and a
cap on time spent "busy" on one source.

Should-fix, all adopted: (2) the writer is explicitly poisoned before `free` on every abort path,
since `archive_write_free` otherwise finalises a valid truncated archive; (3) pipe-end ownership and
closure moved into the transaction job's own `finally`, matching the existing single-pipe pattern,
with the closing rationale corrected; (7) split-set conflicts resolved and finalised as one unit,
last-part-first with rollback; (8) sizes read at feed time rather than trusted from planning, with
tar's zero-pad hazard closed by spooling unknown-size sources first; (9) the manifest moves from a
cache file to SQLite rows sharing the operation's lifetime; (10) the plan-item schema gains staging
Uri, digest, byte-count and per-part state columns; (11) verification's primary check becomes a
universal staged-file re-read, with structural re-listing demoted to a secondary check limited to
seekable, unsplit, small-enough output, run outside the persistent catalog; (12) path sanitisation and
case-insensitive uniquification added at plan time, plus a defence-in-depth `validate_path` check in
the engine; (13) the walk gains a visited-set and depth bound against directory symlink loops, and
skips Fylz's own staging/trash names; (14) archive-provider sources are planned against the
`ArchiveCatalog` tree (kind, encryption, size, policy) at plan time instead of failing mid-archive,
with stream/solid-format sources above a small count refused outright; (15) "relative to selection"
is redefined from a source's document id or in-archive path rather than UI display-name stacks; (16)
the frame protocol's edge cases (directory `DATA`, `FINISH`/`ABORT` mid-entry, post-`FINISH` bytes,
`END` count mismatches, ordinal ordering) are all specified as protocol violations; (17) restarts are
bounded and a foreground-service timeout is mapped to a distinct failure rather than retried forever.

Nits, adopted: (4) the memory figures are the reviewer's measured ones, cited directly; (5) finding the
write instance's RSS by pid rather than by a distinct process name is now a stated `UNVERIFIED` with
the same manifest-entry fallback M3.4 recorded; (18) the "compression can only help" claim is replaced
by an explicit per-entry overhead margin and the vfat suggestion is a warning, not a requirement; (19)
"Save as…" is kept for destinations with no tree grant; (20) the sheet is reached directly from the
action rather than through a nonexistent `MenuId.COMPRESS`; (21) every format's three level tiers are
now distinct values, or explicitly and visibly not (xz's memory-limited top end). Test findings (23,
24, 25) are folded into §2.8 (the plain-JVM two-pipe test, the `io`-based fuzz entry point, the raised
CI timeout, golden-file determinism including `gzip:!timestamp`). REVIEW_QUEUE additions (26a–j) are
folded into §3's entry above. Alternatives (27) confirmed the isolated write instance and SQLite rows
as the right choices; both stand unchanged in shape, corrected in the details above.

Not adopted: none.
