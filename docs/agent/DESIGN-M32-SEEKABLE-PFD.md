# M3.2 design: a seekable descriptor into the decoder process, no whole-archive staging

Design for MASTER_PLAN M3.2. Written 2026-09-25 from `SURVEY-M32-SEEKABLE-PFD.md` (the read-only
fact sheet; every "today" claim below is verified there with file:line evidence) and from
`DESIGN-M31-PART3-EXTRACT-AND-POLICY.md` (part 3 is designed and precedes M3.2 in implementation
order). Paths are relative to the repository root. `UNVERIFIED` marks a claim the implementing
agent must confirm and record.

The plan's text, in full:

> **M3.2 Random access, no whole-archive staging.** Kotlin passes a seekable `ParcelFileDescriptor`
> into the decoder process. ZIP, 7z and ISO are read with seeks. Only non-seekable remote streams
> stage to cache, with a space check.

## 0. Scope

**In:** (a) the `fylz-archive` engine reads through a seekable descriptor and states that contract;
(b) `fylz-ffi-android` exposes archive inspection over uniffi; (c) `DecoderService` gains an archive
inspection call over AIDL that takes the archive descriptor and returns the archive's structure;
(d) a Kotlin `ArchiveSource` turns a `Uri` into a seekable descriptor, staging to cache only when the
provider hands back something that cannot seek, with a space check; (e) the app's **archive
inspection** path (`ArchiveToolsOverlay`'s Inspect and the Extract action's encryption check) moves
off zip4j onto this path, so inspecting an archive no longer copies it; (f) ZIP, 7z and ISO fixtures
and tests that prove seeks are used.

**Out (and where it goes):** extraction through the transfer queue, progress and cancellation
(M3.4 — `ArchiveService.extractZip` keeps zip4j and its own staging until then); browsing entries
as folders and previewing them (M3.3); deleting the Kotlin `ArchiveExtractionPolicy` copy (part 3
decision 2 placed it in "the commit that moves `ArchiveService`'s read path onto the Rust engine";
that is now **M3.4**, because `extractZip` still evaluates the Kotlin policy until extraction moves
— this design changes the schedule, not the rule); AES/ZipCrypto/7z-encrypted reading (M5 and the
7-Zip pack); remote (SFTP/SMB/WebDAV) archives, which have no `Uri` or stream API today (survey §4)
and stay unopenable until the network milestone gives them one (they will stage through §2.3's
`Staged` path when they do — that is the "remote streams" clause of the plan text, and the
staging path is built now so nothing in M3.2 has to change then).

## 1. What today's code does (short; the survey has the evidence)

- `IDecoderService` has `ping()` and `sniff(pfd)`; results are `Boolean`/`String`; no error
  channel; `DecoderClient` times every call out at 5 s and treats timeout and crash alike (drop the
  binding, return `null`). Nothing in `app/src/main` constructs a `DecoderClient` yet.
- `fylz-ffi-android` depends only on `fylz-sniff`; `sniff` wraps the caller's fd in
  `ManuallyDrop<File>` so Rust never closes it.
- `ArchiveService.inspectZip` and `extractZip` each copy the whole archive into
  `cacheDir/archive-work/<uuid>/` first (`stageArchive`), then read it with zip4j; an extract flow
  therefore stages the archive twice and writes the output twice.
- `fylz-archive` opens with `archive_read_open_fd`, which seeks when `fstat` says regular file and
  otherwise reads-and-discards; on a pipe libarchive still *attempts* seeks and gets `ARCHIVE_FAILED`.
  ZIP central directories, 7z pack streams and out-of-order ISO extents need those seeks (survey §5).
- The fd's offset is shared with every Binder dup; `archive_read_open_fd` starts at the current
  offset and never rewinds.
- Nothing in the app calls `ParcelFileDescriptor.getStatSize()`; seekable-fd precedents exist in the
  previews (`openFileDescriptor(uri, "r")`).

## 2. Decisions

### 2.1 The engine accepts only seekable input, and says so

`fylz-archive`'s open path (the `Reader` behind `entries`, `read_entry`, `filter_names`, and part
3's `inspect`/`extract`) gains two steps before `archive_read_open_fd`:

1. `fstat(fd)`; unless `S_ISREG`, return the new `ArchiveError::NotSeekable`. (Pipes, sockets,
   character devices. A regular file on any filesystem seeks.)
2. `lseek(fd, 0, SEEK_SET)`. The dup that arrives over Binder shares its offset with the UI
   process's descriptor; whatever position the caller left it at, the engine starts from byte 0.
   Because a Binder call is synchronous and the service closes its dup on return, nothing else
   reads the same open file description while the engine runs, so the moving shared offset is not
   a race — but the engine must not *depend* on the initial offset, and this makes that a tested
   property rather than a convention.

**Why refuse pipes instead of streaming them:** libarchive can stream tar/gz/xz/zst/lz4 and the
streaming ZIP reader, but with sizes unknown until data descriptors, no 7z data, no ISO out-of-order
extents, and no way to tell the caller which of those it got. The plan's own rule is "only
non-seekable streams stage to cache", so the streaming case never reaches the engine; making that a
hard contract (a typed error, a test) is cheaper than a second code path whose behaviour differs by
format. **Alternative not taken:** custom `archive_read_set_*_callback`s over `pread` so the
shared offset never moves. It is cleaner in theory, but it replaces libarchive's tested
`open_fd.c` with our own read/skip/seek trio for no behaviour the caller can observe (the UI closes
its descriptor after the call). Revisit only if a later milestone keeps one descriptor open across
several engine calls concurrently.

`NotSeekable` also carries the `fstat` mode's file-type bits in its message so a device log says
*what* arrived (`"not seekable (fifo)"`).

### 2.2 Inspection over AIDL: a small Parcelable summary plus a listing sink

`IDecoderService` gains:

```aidl
// Both descriptors are caller-owned. `archive` is read-only and must be seekable (the client
// guarantees that -- section 2.3); `listingSink` is write-only and receives the entry table in
// ArchiveListingCodec's format. Neither stays valid after this call returns.
ArchiveInspection inspectArchive(in ParcelFileDescriptor archive,
                                 in ParcelFileDescriptor listingSink,
                                 in ArchiveLimits limits);
```

with two Parcelables (`kotlin-parcelize` plugin added to `app/build.gradle.kts`; `@Parcelize` data
classes in `decoder/`, declared to AIDL with `parcelable ArchiveLimits;` / `parcelable
ArchiveInspection;` files):

- `ArchiveLimits` mirrors `fylz-archive`'s `policy::Limits` field for field (the same seven numbers
  `ArchiveExtractionLimits` has today, plus `maxListingEntries: Int` — §2.4).
- `ArchiveInspection` is the **summary only**: `outcome` (`OK`, `NOT_SEEKABLE`, `UNSUPPORTED`,
  `CORRUPT`, `LIMIT_EXCEEDED`, `INTERNAL`), `message: String?` (the engine's error text, never a
  stack trace), `format: String?` (libarchive's `archive_format_name`, e.g. `"ZIP 2.0 (deflation)"`,
  `"7-Zip"`, `"ISO9660"`), `filters: List<String>` (`archive_filter_name` per filter, `"none"`
  dropped), `archiveBytes: Long`, `entryCount: Int`, `directoryCount: Int`,
  `totalUncompressedBytes: Long` (-1 when any entry's size is unknown), `hasEncryptedEntries:
  Boolean`, `hasEncryptedMetadata: Boolean`, `policyAllowed: Boolean`, `policyReason: String?`,
  `listingEntries: Int` (how many rows were written to the sink, so the reader can detect a
  truncated file).

**The entry table does not travel inside the Parcelable.** The Binder transaction buffer is 1 MB per
process, shared by every in-flight transaction; at roughly 100 bytes per entry a listing overflows
it around ten thousand entries — a Linux source tarball has eighty thousand, and M3.3 has to browse
those. So the service writes the entries into `listingSink`, a write descriptor the **UI process**
opened on a file it created (`cacheDir/archive-listings/<uuid>.fzl`), and the UI reads it back
lazily. This is a deviation from MASTER_PLAN §4.4's "structure as Parcelables" and is logged in
`REVIEW_QUEUE.md`; the summary *is* a Parcelable, the table is not.

`ArchiveListingCodec` (`archive/ArchiveListingCodec.kt`, Kotlin on both sides, so the format cannot
drift between two implementations) writes with `DataOutputStream` over a `BufferedOutputStream`:
magic `FZL1`, then per entry — UTF-8 path (u16 length prefix; paths over 65,535 bytes are already
rejected by `maxNameLength`, but the codec must not overflow: it writes `0xFFFF` and the first
65,535 bytes and sets a `TRUNCATED_PATH` flag), a flags byte (`DIRECTORY`, `SYMLINK`, `HARDLINK`,
`ENCRYPTED_DATA`, `ENCRYPTED_METADATA`, `SIZE_UNKNOWN`, `TRUNCATED_PATH`), `uncompressed: Long`
(0 when unknown, see flag), `mtimeEpochSeconds: Long` (`Long.MIN_VALUE` = unknown), `mode: Int`
(the permission bits, 0 when the format carries none) — and a trailer `0xFFFFFFFF` + entry count. The reader is a `Sequence<ArchiveListingEntry>`
that validates the trailer against `ArchiveInspection.listingEntries` and throws
`ArchiveListingTruncated` otherwise. No `compressed` field: libarchive has no public per-entry
compressed size (part 3 decision 1), and this design does not add one — the part-3 remark that
"M3.2's seekable ZIP reader can fill it" is **withdrawn**; the archive-level ratio check and the
runtime caps are the defence, as part 3 already decided.

**Encoding in the service, not in Rust:** the Rust side returns the entry table to Kotlin *inside
`:decoders`* as a uniffi `Vec<ArchiveEntryRecord>` (a `RustBuffer` copy — for eighty thousand
entries about 10 MB, well inside the isolated process's budget), and `DecoderService` runs the
codec. Rust stays free of the file format and of any serialisation dependency.

### 2.3 `ArchiveSource`: one `Uri` in, one seekable descriptor out

New package `io.github.mbaliga.fylz.archive` (the engine-facing Kotlin; `decoder/` keeps the IPC
plumbing, `data/ArchiveService` keeps the zip4j code that M3.4–M3.10 retire).

```kotlin
class ArchiveSource(
    private val context: Context,
    private val limits: ArchiveExtractionLimits = ArchiveExtractionLimits(),
    private val isSeekable: (ParcelFileDescriptor) -> Boolean = ::fstatIsRegularFile,
    private val availableCacheBytes: () -> Long? = { StatFs(context.cacheDir.path)... },
) {
    sealed class Resolved : Closeable {
        abstract val pfd: ParcelFileDescriptor
        class Direct(override val pfd: ParcelFileDescriptor) : Resolved()
        class Staged(override val pfd: ParcelFileDescriptor, val workspace: File) : Resolved()
    }
    suspend fun resolve(uri: Uri): Resolved   // throws ArchiveSourceException
}
```

Resolution, in order:

1. `contentResolver.openFileDescriptor(uri, "r")`. If it throws (`FileNotFoundException`,
   `UnsupportedOperationException`, `SecurityException`) → step 3 with `openInputStream`.
2. `isSeekable(pfd)`: default `Os.fstat(pfd.fileDescriptor)` + `OsConstants.S_ISREG(st_mode)`;
   `getStatSize() >= 0` is *not* used as the primary test because it is documented to return the
   size for "regular files and other seekable types" without saying which. Seekable → `Direct(pfd)`.
   Not seekable → close that pfd, step 3 with `openInputStream`. (`UNVERIFIED`: whether
   `Os.fstat` works under Robolectric. The lambda is injectable precisely so unit tests do not
   depend on it; the agent records which default worked in the PROGRESS row.)
3. **Stage, with the space check first.** Declared size: `DocumentsContract.Document.COLUMN_SIZE`
   from a one-column query on `uri` (null when the provider does not say). Required bytes =
   `(declared ?: limits.maxArchiveBytes) + MIN_TEMPORARY_HEADROOM` (the 16 MiB constant already in
   `ArchiveSpacePolicy`; expose it as `ArchiveSpacePolicy.stagingRequirement(declaredBytes: Long?,
   maxArchiveBytes: Long)`), checked against `availableCacheBytes()` through the existing
   `ArchiveSpacePolicy.evaluate(required, available, label = "temporary storage")`. Not enough →
   `ArchiveSourceException.InsufficientSpace(required, available)` and **nothing is copied**. Then
   copy into `cacheDir/archive-work/<uuid>/input` (today's directory, today's `copyBounded` shape)
   with a hard cap at `limits.maxArchiveBytes` → `ArchiveSourceException.ArchiveTooLarge`, workspace
   deleted. Success → `ParcelFileDescriptor.open(file, MODE_READ_ONLY)` → `Staged(pfd, workspace)`.
   `close()` closes the pfd and `deleteRecursively()`s the workspace.
   Also sweep `cacheDir/archive-work/` for entries older than 24 h on first `resolve()` (a process
   death between staging and `close()` leaves the directory behind; today's code has the same leak
   with no sweep — `UNVERIFIED`, the agent greps for one before adding it).

Everything the UI process can open by `Uri` reaches Rust only through `resolve()`, so the engine's
`NotSeekable` (§2.1) is unreachable from the app in practice; if it ever comes back it is reported as
`outcome = INTERNAL` with the message, not as a retry.

### 2.4 The Rust side: `inspect()` gets what a listing needs

Part 3 defines `inspect(fd) -> Inspection { archive_bytes, entries: Vec<EntryMetadata> }` and
`EntryMetadata { path, is_directory, uncompressed: Option<u64>, compressed: Option<u64> }`. Part 3
has not been implemented yet, so this design **amends part 3's types** rather than adding a second
listing call (the part-3 brief must carry these when it is dispatched):

```rust
pub enum EntryKind { File, Directory, Symlink, Hardlink, Other }   // from archive_entry_filetype / hardlink()
pub struct EntryMetadata {
    pub path: String,
    pub kind: EntryKind,                 // replaces `is_directory`; policy tests `kind == Directory`
    pub uncompressed: Option<u64>,       // archive_entry_size_is_set ? Some(size) : None
    pub compressed: Option<u64>,         // always None (2.2); kept so the policy signature is stable
    pub mtime: Option<i64>,              // archive_entry_mtime_is_set
    pub mode: u32,                       // archive_entry_perm (0 when the format carries none; libarchive has no is_set for it)
    pub encrypted_data: bool,            // archive_entry_is_data_encrypted
    pub encrypted_metadata: bool,        // archive_entry_is_metadata_encrypted
}
pub struct Inspection {
    pub archive_bytes: u64,              // fstat
    pub format: String,                  // archive_format_name after the first header
    pub filters: Vec<String>,            // filter_names(), reused
    pub has_encrypted_entries: Option<bool>,   // archive_read_has_encrypted_entries: 1/0 -> Some, ARCHIVE_READ_FORMAT_ENCRYPTION_UNSUPPORTED/DONT_KNOW -> None
    pub entries: Vec<EntryMetadata>,
}
pub struct Limits { /* part 3's seven fields */ pub max_listing_entries: usize /* default 1_000_000 */ }
```

`inspect` is one forward header pass (`archive_read_next_header`, never `archive_read_data`) and
stops with `ArchiveError::LimitExceeded { entry, rule: "listing" }` past `max_listing_entries` — a
memory bound for `:decoders`, distinct from the policy's `max_entries` (10,000), which stays an
*extraction* rule evaluated afterwards on the collected table. With a seekable descriptor this pass
is cheap for the three formats the plan names: ZIP's seekable bidder jumps to the central directory;
7z's header lives at the end and `seek_compat` becomes a real `lseek`; ISO reads its path tables at
the front. The policy `evaluate(archive_bytes, &entries, &limits)` runs inside `inspect_with_policy`
(the function the FFI calls), so the decision is computed once, in Rust, and the listing the UI shows
and the decision it shows come from the same pass.

`fylz-ffi-android` adds `fylz-archive` as a dependency and exports, **synchronously**:

```rust
#[derive(uniffi::Record)] pub struct ArchiveLimits { ... }          // mirrors, From<> both ways
#[derive(uniffi::Record)] pub struct ArchiveEntryRecord { path, kind: ArchiveEntryKind, uncompressed: Option<u64>, mtime: Option<i64>, mode: u32, encrypted_data: bool, encrypted_metadata: bool }
#[derive(uniffi::Record)] pub struct ArchiveInspectionRecord { archive_bytes, format, filters, has_encrypted_entries: Option<bool>, policy_allowed: bool, policy_reason: Option<String>, entries: Vec<ArchiveEntryRecord> }
#[derive(uniffi::Error, Debug, thiserror::Error)] pub enum ArchiveFfiError { NotSeekable{message}, Fatal{message}, NonUtf8Path, LimitExceeded{entry, rule} }
#[uniffi::export] pub fn archive_inspect(fd: i32, limits: ArchiveLimits) -> Result<ArchiveInspectionRecord, ArchiveFfiError>;
```

Synchronous because the call runs on a Binder thread that has nothing else to do; `sniff` is `async`
without an `.await` and `DecoderService` wraps it in `runBlocking` — that is harmless but a pattern
not to copy. Errors cross uniffi as `ArchiveFfiException` subclasses in Kotlin; `DecoderService`
catches them and maps to `ArchiveInspection.outcome` + `message` (AIDL propagates only a fixed set of
exception types; anything else kills the Binder thread and shows up client-side as a crash). The fd
is used as a plain `i32`, never wrapped in an owning `File` (the `ManuallyDrop` rule from `sniff`
applies to any wrapper the archive code creates). `thiserror` is not in the workspace today: use a
manual `Display` impl instead, as `fylz-archive` does — no new dependency for one enum.

Build consequences to record: the host `cargo build -p fylz-ffi-android` that Gradle runs for
bindgen now compiles libarchive plus all five companions on the host (it already does for `cargo
test`); the three shipped `.so`s grow by the static libarchive + companions. Record per-ABI
`libfylz_ffi_android.so` sizes before and after in the PROGRESS row against REPORT-M2's 12 MB/ABI
budget (M3.1 part 2's row already tracks the companion deltas).

### 2.5 `DecoderService` and `DecoderClient`

`DecoderService.inspectArchive`: `archive.use { a -> listingSink.use { sink -> ... } }`; call
`FylzCore.inspectArchive(a.fd, limits)`; on success run `ArchiveListingCodec.write(entries,
FileOutputStream(sink.fileDescriptor))` and return the summary with `listingEntries = entries.size`;
on `ArchiveFfiException` return a summary with the matching `outcome`, `listingEntries = 0`; catch
`Throwable` (an OOM on a pathological listing, a codec I/O error) → `outcome = INTERNAL` with the
exception's class name, so the client sees a *result* and not a dead process for engine-level
failures. `runBlocking` is not needed for a synchronous uniffi function. The service writes through a
received descriptor for the first time here: an isolated process may not *open* files but may
write to a descriptor it was handed (the same mechanism ashmem and pipes use) — `UNVERIFIED` on a
real device with SELinux enforcing; DEVICE_CHECKS §17 covers it.

`DecoderClient`: `call` takes a `timeoutMillis` parameter (the constructor default stays 5 s for
`ping`/`sniff`); new `suspend fun inspectArchive(archive, listingSink, limits, timeoutMillis =
STRUCTURE_TIMEOUT_MILLIS /* 30_000, section 4.4's structure budget */): ArchiveInspection?`. `null`
keeps its meaning (no connection, timeout, crash) and is what makes the UI say "could not be read
safely" rather than showing a possibly-corrupt result; a returned summary with a non-`OK` outcome
is an *engine* answer and is shown as such. The client still never closes the caller's descriptors.
Binding lifetime: the client is constructed once next to `ArchiveService` in `FylzV1App`'s
`remember { }` block (line ~382), so the binding lives for the composition, not per call — the
cold-start cost of spawning `:decoders` and registering JNA is paid once per browsing session
(survey risk 7).

### 2.6 `ArchiveInspector`: what the UI calls

```kotlin
class ArchiveInspector(context, source: ArchiveSource, client: DecoderClient, limits) {
    suspend fun inspect(uri: Uri): ArchiveInspectionResult
}
sealed class ArchiveInspectionResult {
    data class Ready(val summary: ArchiveInspection, val staged: Boolean, val listing: ArchiveListing) : ...   // listing lazily reads the .fzl
    data class Refused(val outcome, val message: String?) : ...      // engine error, e.g. CORRUPT
    data class Unavailable(val reason: String) : ...                 // decoder null: timeout/crash/bind failure
    data class SourceFailed(val cause: ArchiveSourceException) : ... // InsufficientSpace, ArchiveTooLarge, unreadable Uri
}
```

`inspect`: `source.resolve(uri).use { resolved -> create listing file; open write pfd; client.inspectArchive(...) }`;
the listing file is owned by the returned `ArchiveListing` (`Closeable`, deletes on close; the
overlay closes it when the dialog is dismissed). `staged` is surfaced so the overlay can show
"copied to temporary storage" for the non-seekable case — a user-visible statement of the plan's
rule. `ArchiveListing` exposes `sequence()`, `count`, and `firstEncrypted()`; the overlay's dialog
lists the first N rows as today and M3.3 builds paging on the same file.

Call-site changes: `ArchiveToolsOverlay.kt:146` (`service.inspectZip(uri)` → `inspector.inspect(uri)`;
the dialog reads `format`, `entryCount`, `policyAllowed`/`policyReason`, `hasEncryptedEntries`;
its "Encrypted ZIP"/"Standard ZIP" label becomes the format name plus an "encrypted" suffix);
`FylzV1App.kt:1011` (`archiveService.inspectZip(archiveUri).encrypted` → the inspector's
`hasEncryptedEntries`, `false` on anything but `Ready`, same one line — `FylzV1AppSizeTest`'s
ratchet at 2287 lines must not move up). `ArchiveService.inspectZip` and the `data.ArchiveInspection`
class are deleted; `stageArchive` stays for `extractZip`/`createZip` with a header comment naming
M3.4 as its removal. `fylz.extract`'s `enabledWhen` (ZIP family only) is **unchanged** — extraction is
still zip4j — and `fylz.archive.inspect` may now be offered for every format libarchive reads; widen
`ArchiveToolsOverlay`'s picker MIME list for Inspect only (`*/*` with the sniffer deciding is M3.3's
call; here: the ZIP list plus `application/x-7z-compressed`, `application/x-iso9660-image`,
`application/x-tar`, `application/gzip`, `application/x-xz`, `application/zstd`, `application/x-bzip2`).

### 2.7 Fixtures

`tools/fixtures/make_archive_fixtures.py` (stdlib `zipfile`, plus pip `py7zr` and `pycdlib`, both
available; document the pip line as `make_compression_fixtures.py` does), outputs in
`core/fixtures/archives/`, all small (< 64 KiB) and deterministic (fixed timestamps, sorted names):

| File | Made how | Proves |
|---|---|---|
| `sample-cd.zip` | `zipfile` normal write, 5 files in 2 dirs, deflate | baseline ZIP with central directory |
| `sample-streamed.zip` | `zipfile` writing to a file object **without `seek`/`tell`** → bit-3 data descriptors, local-header sizes zero | the seekable reader reads sizes from the central directory; a stream reader would report unknown |
| `sample-copy.7z` | `py7zr` with `FILTER_COPY` | 7z listing via end-of-file header and entry data via a backward pack seek, without LZMA |
| `sample-lzma2.7z` | `py7zr` default (LZMA2) | 7z with liblzma (part 2e); `UNVERIFIED`: py7zr may compress *headers* with LZMA2 even for the COPY archive — if so, both 7z fixtures need part 2e and the row says so |
| `sample.iso` | `pycdlib` ISO9660 level 3 + Joliet + Rock Ridge, nested dir, one file placed out of directory order if pycdlib allows (`UNVERIFIED`) | ISO listing with seeks; Rock Ridge names |
| `hostile/zip-slip.zip`, `hostile/absolute-path.zip`, `hostile/symlink-escape.tar` | `zipfile` with a hand-set filename; `tarfile` with a symlink to `/etc/passwd` | `inspect` collects them and the Rust policy refuses them (part 3's tests already cover the *rules* on synthetic metadata; this is the end-to-end file → refusal) |

The full M3 acceptance corpus (one of every format, bombs, oversized headers) is **not** M3.2's; it
lands with M3 acceptance. Fixtures used by Rust tests only; Kotlin unit tests never load native code
today and this design keeps that (they drive fakes through the `bind`/`unbind` and `isSeekable`
seams).

### 2.8 Tests

Rust (`fylz-archive`):
- `open_refuses_a_socket_with_not_seekable` (`std::os::unix::net::UnixStream::pair()`; no new
  dependency) and `open_refuses_a_pipe` if `libc` is already a dependency, else the socket test alone.
- `inspect_ignores_the_descriptors_initial_offset` (seek to the middle, inspect, compare with a
  fresh open).
- `streamed_zip_gets_sizes_from_the_central_directory` (every `uncompressed` is `Some`; the same
  archive fed through the crate's `ar` builder is not usable here — the point is the real file).
- `seven_zip_entry_data_needs_a_backward_seek` (`read_entry` on `sample-copy.7z` returns the bytes).
- `iso_lists_nested_directories_with_rock_ridge_names`.
- `hostile_fixtures_are_refused` (three files → `policy_allowed == false` with the Kotlin-identical
  reason strings).
- `filter_names` and `format` are asserted for each fixture (`"ZIP 2.0 (deflation)"` etc. — copy
  the strings libarchive actually returns; do not guess them).
- `max_listing_entries` → `LimitExceeded` on a synthetic tar built with the existing `ar` helper.

Rust (`fylz-ffi-android`): `archive_inspect` on `sample-cd.zip` round-trips the records; a negative
fd → `NotSeekable`, not a panic; `From` conversions are total (an exhaustive `match`, so a new
`EntryKind` variant fails to compile rather than silently mapping).

Kotlin (Robolectric, `app/src/test`):
- `ArchiveSourceTest`: a `FylzFilesDocumentsProvider` file `Uri` → `Direct` and **no** entry appears
  under `cacheDir/archive-work` (the "no whole-archive staging" assertion); a pipe-backed provider
  (adapt `FaultyDocumentsProvider`'s `createPipe()`-fed `openDocument` for read mode, with
  `isSeekable = { false }` injected because Robolectric's pipes are file-backed) → `Staged`, bytes
  identical, workspace gone after `close()`; `availableCacheBytes` too small → `InsufficientSpace`
  and no workspace created; a pipe longer than `maxArchiveBytes` (set to 4 KiB for the test) →
  `ArchiveTooLarge` and no workspace left.
- `ArchiveListingCodecTest`: round trip with non-ASCII and 300-byte paths, unknown size/mtime/mode,
  every flag; 100,000 entries write+read under a generous bound (assert correctness, log the time);
  a stream cut before the trailer → `ArchiveListingTruncated`.
- `ArchiveParcelablesTest`: `ArchiveInspection` and `ArchiveLimits` survive `Parcel` write/read.
- `DecoderClientTest` gains: `inspectArchive` hang → `null` after the injected timeout and the
  binding is dropped; a stub that throws `DeadObjectException` → `null` and the next call rebinds;
  a stub that returns `outcome = CORRUPT` → the summary is returned unchanged (not `null`).
- `ArchiveInspectorTest`: with a fake `IDecoderService.Stub` that writes a known listing through the
  sink and returns a summary → `Ready` with the entries readable and the listing file deleted on
  close; a fake returning `null` path (bind returns `false`) → `Unavailable`; the source throwing
  `InsufficientSpace` → `SourceFailed` and the decoder is never called.
- `NoHardCodedMenusTest`/`ActionResolverGoldenTest`/`FylzV1AppSizeTest` unchanged and green.

Fuzz: part 3's `archive_entries` target already exercises `inspect()`; add the new fixtures to its
seed corpus. No new target.

### 2.9 Device checks (`DEVICE_CHECKS.md` §17, "M3.2 — seekable descriptors into `:decoders`")

1. Inspect a ZIP, a 7z and an ISO from `Downloads` (local `FylzFilesDocumentsProvider`): the dialog
   shows the format, counts and sizes; `adb shell ls /data/data/<pkg>/cache/archive-work` stays
   empty during and after.
2. Inspect the same ZIP through a third-party provider that streams (Google Drive with a
   non-downloaded file, or a "Media" provider): the dialog says it was copied to temporary storage,
   the `archive-work` directory holds it during the dialog and is empty after dismissal.
3. A ZIP with 100,000 entries (script in the checklist) inspects without `TransactionTooLargeException`
   in logcat; the listing file appears under `cache/archive-listings/` and is removed on dismissal.
4. SELinux: `adb logcat | grep avc` shows no denial for `isolated_app` writing the listing sink or
   reading the archive descriptor.
5. Kill `:decoders` mid-inspect (`adb shell am kill`): the dialog reports "could not be read safely";
   the next inspect works (rebind).

## 3. Sequencing and gates

Three commits, each green on the full gate (`./gradlew --no-daemon :app:testDebugUnitTest
:app:lintDebug :app:assembleDebug`; `cd core && cargo test --workspace && cargo clippy --workspace
--all-targets -- -D warnings && cargo fmt --check && cargo deny check`; `cargo ndk --platform 31 -t
arm64-v8a -t armeabi-v7a -t x86_64 build --release -p fylz-ffi-android` — note the crate is now
the ffi one, since it links libarchive from this milestone on):

- **M3.2a (Rust):** `NotSeekable` + rewind in the open path; the amended `EntryMetadata`/`Inspection`
  (if part 3 landed with the unamended shapes, this commit changes them — part 3's own tests are
  updated in the same commit); `max_listing_entries`; `make_archive_fixtures.py` and the fixtures;
  the Rust tests of §2.8. PROGRESS row: fixture SHA-256s, libarchive format strings observed, the
  py7zr header question answered.
- **M3.2b (FFI + IPC):** `fylz-ffi-android` depends on `fylz-archive`; `archive_inspect`; parcelize
  plugin; `ArchiveLimits`/`ArchiveInspection` Parcelables; the AIDL method; `ArchiveListingCodec`;
  `DecoderService.inspectArchive`; `DecoderClient.inspectArchive` with the 30 s structure budget;
  the codec, parcel and client tests. PROGRESS row: per-ABI `.so` sizes before/after.
- **M3.2c (app):** `ArchiveSource`, `ArchiveSpacePolicy.stagingRequirement`, `ArchiveInspector`,
  `ArchiveListing`; the overlay and Extract-handler call sites; delete `ArchiveService.inspectZip`
  and `data.ArchiveInspection`; header comments on `stageArchive`/`extractZip` and on
  `ArchiveExtractionPolicy.kt` (M3.4 deletes it); `ARCHITECTURE.md` (the "Isolated decoder process"
  section describes `inspectArchive`, the listing sink and the seekable-descriptor rule; line 155's
  "stages data only in app-private cache" is amended for inspection); `DEVICE_CHECKS.md` §17;
  `REVIEW_QUEUE.md` entry (below); PROGRESS row. PR #19's description gets the M3.2 line.

`REVIEW_QUEUE.md` entry for M3.2 (log-and-continue): (1) the listing sink instead of "structure as
Parcelables" (§2.2) — the Binder limit makes the plan's wording unimplementable for real archives;
(2) the engine refuses non-seekable input outright (§2.1) instead of streaming what it can;
(3) the Kotlin policy copy's deletion moves from "M3.2/M3.3" to M3.4 (§0); (4) `compressed` stays
`None` for good (§2.2), amending part 3's expectation; (5) an isolated process writing through a
received descriptor is device-unverified until §17 runs.

## 4. Risks

- **SELinux on a real device** for the write descriptor into `isolated_app` (§2.5). If denied, the
  fallback is a `SharedMemory` sized by a first counting pass — a second `:decoders` call per
  inspect. Nothing in the UI-side API changes; `ArchiveListing` would read from ashmem instead.
- **`Os.fstat` under Robolectric** (§2.3) — mitigated by injection; only the default's own test may
  need `@Ignore` with a reason if Robolectric cannot run it, and then §17 item 1 is its coverage.
- **py7zr header compression** (§2.7) — worst case both 7z fixtures depend on part 2e, which lands
  before M3.2 starts.
- **Partial results in `:decoders`** — a corrupt archive after 50,000 valid headers returns
  `CORRUPT` with no listing. Acceptable for M3.2 (the policy would need the full table anyway);
  M3.3 may want "list what was readable" and can add a `partial` flag to the summary then.
- **Binding lifetime** — a `remember { }`-scoped `DecoderClient` is unbound when the composition
  leaves; that is the same lifetime `ArchiveService` has today, and M3.3 can lift it to the
  `Application` if browsing needs the process to outlive a configuration change.
