# M3.2 design: a seekable descriptor into the decoder process, no whole-archive staging

**Rev 2** (2026-09-25), after the architecture review of rev 1 (commit `282f377`). The review's
findings and what changed are listed in §6. Design for MASTER_PLAN M3.2, written from
`SURVEY-M32-SEEKABLE-PFD.md` (the read-only fact sheet; every "today" claim below is verified there
with file:line evidence, with the corrections in §1) and from
`DESIGN-M31-PART3-EXTRACT-AND-POLICY.md`, whose §6 now carries the type amendments this design
needs (part 3 is implemented before M3.2). Paths are relative to the repository root.
`UNVERIFIED` marks a claim the implementing agent must confirm and record.

The plan's text, in full:

> **M3.2 Random access, no whole-archive staging.** Kotlin passes a seekable `ParcelFileDescriptor`
> into the decoder process. ZIP, 7z and ISO are read with seeks. Only non-seekable remote streams
> stage to cache, with a space check.

## 0. Scope

**In:** (a) the `fylz-archive` engine reads through a seekable descriptor and states that contract;
(b) `fylz-ffi-android` exposes archive inspection over uniffi; (c) `DecoderService` gains an archive
inspection call over AIDL that takes the archive descriptor and returns a Parcelable summary with
the first rows of the listing; (d) `DecoderClient`'s timeout is made to actually abandon a hung
call (a pre-existing M2.4 defect that M3.2, the first real caller, would otherwise ship on);
(e) a Kotlin `ArchiveSource` turns a `Uri` into a seekable descriptor, staging to cache only when
the provider hands back something that cannot seek, with a space check; (f) **every** app path that
inspects an archive today — `ArchiveToolsOverlay`'s Inspect dialog, the Extract action's encryption
check in `FylzV1App`, and `ZipArchivePreview` in `SpecializedDocumentPreview.kt` (the worst staging
site: a full copy of the archive every time a ZIP or APK gains preview focus) — moves off zip4j
onto this path, so inspecting an archive no longer copies it; (g) ZIP, 7z and ISO fixtures and tests
that prove seeks are used, with negative controls.

**Out (and where it goes):** extraction through the transfer queue, progress and cancellation
(M3.4 — `ArchiveService.extractZip` keeps zip4j and its own staging until then); browsing entries
as folders, previewing entry contents, and **transporting a full listing** out of `:decoders` (M3.3
— see §2.2 for why M3.2 carries only the first 500 rows); deleting the Kotlin
`ArchiveExtractionPolicy` copy (part 3 decision 2 placed it in "the commit that moves
`ArchiveService`'s read path onto the Rust engine"; that is now **M3.4**, because `extractZip`
still evaluates the Kotlin policy until extraction moves — this changes the schedule, not the rule);
AES/ZipCrypto/7z-encrypted reading (M5 and the 7-Zip pack); filename charset detection for legacy
ZIPs (M3.7; §2.4 says what M3.2 does with non-UTF-8 names meanwhile); remote (SFTP/SMB/WebDAV)
archives, which have no `Uri` and only a `download(path, File)` API today (survey §4) — they stay
unopenable until the network milestone gives them an entry point, and that entry point will hand
`ArchiveSource` a downloaded file (already seekable) or a stream (staged by §2.3). The plan's
"remote streams stage to cache" is therefore **structural** in M3.2: the staging path exists and is
tested with a pipe-backed provider, but no remote archive opens after M3.2.

## 1. What today's code does (short; the survey has the evidence, with two corrections)

- `IDecoderService` has `ping()` and `sniff(pfd)`; results are `Boolean`/`String`; no error
  channel; `DecoderClient` wraps each call in `withTimeoutOrNull(5 s) { withContext(IO) { … } }`.
  **Correction (review finding 1):** that timeout does not abandon a hung call. `withContext`
  waits for its blocking body under structured concurrency, so `withTimeoutOrNull` returns `null`
  only *after* the Binder transaction returns — measured: a 1,500 ms blocking body under a 20 ms
  timeout returns after 1,520 ms. `dropConnection()` (the unbind that lets the platform reap
  `:decoders`) therefore never runs while the process is hung; a libarchive spin would freeze the
  calling coroutine indefinitely. The existing hang tests pass because the fake sleeps 500 ms and
  nothing measures elapsed time. Nothing in `app/src/main` constructs a `DecoderClient` yet.
- `fylz-ffi-android` depends only on `fylz-sniff`; `sniff` wraps the caller's fd in
  `ManuallyDrop<File>` so Rust never closes it.
- `ArchiveService.inspectZip` and `extractZip` each copy the whole archive into
  `cacheDir/archive-work/<uuid>/` first (`stageArchive`), then read it with zip4j. Three callers
  inspect: `ArchiveToolsOverlay.kt:146`, `FylzV1App.kt:1012` (the Extract action's `.encrypted`
  check) and `SpecializedDocumentPreview.kt:186` (`ZipArchivePreview`, mounted from
  `PreviewPane.kt:156` for the ZIP family). The `data.ArchiveInspection` class carries
  `visibleEntries` capped at `DEFAULT_VISIBLE_ENTRY_LIMIT = 500` plus `entriesTruncated`.
- `fylz-archive` opens with `archive_read_open_fd`, which seeks when `fstat` says regular file and
  otherwise reads-and-discards; on a pipe libarchive still *attempts* seeks and gets `ARCHIVE_FAILED`.
  **Correction (finding 20):** survey §5's "7z can be listed on a pipe" is wrong — for fd input
  `can_seek` is always 1 (`archive_read.c:520`), so 7z's `seek_compat` calls the real seek and
  fails on a pipe (`7zip.c:4631`). 7z needs a seekable descriptor for listing *and* data.
- libarchive treats the first byte it reads as offset 0 (`archive_read.c:522`) but `file_seek`
  issues an absolute `lseek(fd, request, whence)` (`open_fd.c:210`): with a non-zero starting
  offset, the seekable ZIP reader and 7z's `seek_pack` read the wrong bytes. The fd's offset is
  shared with every Binder dup.
- ISO9660 is read as a single forward walk of directory extents in sector order (`iso9660.c:70-80`);
  the only backward seek is for out-of-order file extents (`iso9660.c:1406`). libarchive does not
  read ISO path tables. For ISO, "read with seeks" means lseek-backed forward skips
  (`open_fd.c:161-167`) instead of read-and-discard.

## 2. Decisions

### 2.1 The engine accepts only seekable input, and says so

`fylz-archive`'s open path (the `Reader` behind `entries`, `read_entry`, `filter_names`, and part
3's `inspect`/`extract`) gains two steps before `archive_read_open_fd`:

1. `fstat(fd)`; unless `S_ISREG`, return `ArchiveError::NotSeekable(String)` whose text names the
   file type (`"not seekable (fifo)"`, `"(socket)"`, `"(character device)"`).
2. `lseek(fd, 0, SEEK_SET)`. **This is a correctness requirement, not hygiene** (§1): libarchive
   seeks absolutely, so the engine must start at byte 0 whatever offset the caller left the shared
   open file description at. The code comment says so. A Binder call is synchronous and the
   service closes its dup on return, so nothing else reads the same open file description while the
   engine runs; the moving shared offset is not a race, and the test
   `inspect_ignores_the_descriptors_initial_offset` (on `sample-cd.zip`, whose central directory is
   found by an absolute seek from the end) pins the property.

**Why refuse pipes instead of streaming them:** libarchive can stream tar/gz/xz/zst/lz4 and the
streaming ZIP reader, but with sizes unknown until data descriptors, no 7z at all (§1), no ISO
out-of-order extents, and no way to tell the caller which of those it got. The plan's own rule is
"only non-seekable streams stage to cache", so the streaming case never reaches the engine; making
that a hard contract (a typed error, a test) is cheaper than a second code path whose behaviour
differs by format. **Alternative not taken:** custom `archive_read_set_*_callback`s over `pread` so
the shared offset never moves — it replaces libarchive's tested `open_fd.c` with our own
read/skip/seek trio for no behaviour the caller can observe. Revisit only if a later milestone
keeps one descriptor open across several concurrent engine calls.

For tests only, `#[cfg(test)] Reader::open_unchecked(fd)` skips step 1 (and step 2's `lseek`
failure is ignored) so the same fixtures can be fed through `std::io::pipe()` (stable since Rust
1.87; the toolchain is 1.94.1) to show what the seekable path buys — §2.8.

### 2.2 Inspection over AIDL: a Parcelable summary carrying the first rows

`IDecoderService` gains:

```aidl
// `archive` is read-only, caller-owned, and seekable (the client guarantees that -- section 2.3);
// it is not valid after this call returns. The result carries the archive's structure and at most
// `maxRows` entries (MASTER_PLAN section 4.4: "structure as Parcelables").
ArchiveInspection inspectArchive(in ParcelFileDescriptor archive, in ArchiveLimits limits, int maxRows);
```

with two Parcelables (`kotlin-parcelize` plugin: `id("org.jetbrains.kotlin.plugin.parcelize")
version "2.1.20" apply false` in the root `build.gradle.kts`, applied in `app/`; `@Parcelize` data
classes in `decoder/`, declared to AIDL with `parcelable ArchiveLimits;` / `parcelable
ArchiveInspection;` files):

- `ArchiveLimits` mirrors `fylz-archive`'s `policy::Limits` field for field: the seven numbers
  `ArchiveExtractionLimits` has today plus `maxListingEntries` (part 3 §6). It is the app's one
  limits type from M3.2 on; `ArchiveExtractionLimits` stays only for `extractZip` until M3.4.
- `ArchiveInspection`: `outcome: Int` (`OK`, `NOT_SEEKABLE`, `UNSUPPORTED`, `CORRUPT`,
  `LIMIT_EXCEEDED`, `INTERNAL` — full mapping in §2.4), `message: String?` (the engine's error text,
  never a stack trace), `formatCode: Int` (`archive_format() & ARCHIVE_FORMAT_BASE_MASK`; the
  family — ZIP, 7ZIP, ISO9660, TAR, … — which is what UI decisions key on), `formatName: String?`
  (libarchive's `archive_format_name`, informational; for ZIP it names the *current entry's*
  compression method, so it is never used for decisions), `filters: List<String>`
  (`archive_filter_name` per filter, `"none"` dropped), `archiveBytes: Long`, `entryCount: Int`,
  `fileCount: Int`, `directoryCount: Int`, `linkCount: Int`, `totalUncompressedBytes: Long` (-1 when
  any entry's size is unknown), `hasEncryptedEntries: Boolean`, `hasEncryptedMetadata: Boolean`,
  `hasLossyNames: Boolean`, `policyAllowed: Boolean`, `policyReason: String?`,
  `rows: List<ArchiveEntryInfo>` (the first `maxRows` entries in archive order), `rowsTruncated:
  Boolean`.
- `ArchiveEntryInfo` (`@Parcelize`): `path`, `kind: Int` (FILE, DIRECTORY, SYMLINK, HARDLINK,
  OTHER), `linkTarget: String?`, `uncompressedBytes: Long` (-1 unknown), `mtimeEpochSeconds: Long`
  (`Long.MIN_VALUE` unknown), `mode: Int`, `encryptedData: Boolean`, `encryptedMetadata: Boolean`,
  `nameLossy: Boolean`.

**Why only the first rows, and why 500.** The Binder transaction buffer is 1 MB per process,
shared by every in-flight transaction; at roughly 100 bytes per entry a full listing overflows it
around ten thousand entries, and M3.3 has to browse eighty-thousand-entry tarballs. But no M3.2
consumer needs more than today's `DEFAULT_VISIBLE_ENTRY_LIMIT = 500` (`ZipArchivePreview` lists
`visibleEntries`; the overlay and the Extract check use only the summary). So M3.2 carries up to
500 rows (about 50 KB) inside the Parcelable — §4.4's "structure as Parcelables", literally — and
**M3.3 designs the full-listing transport** (a UI-owned listing file the service writes through a
passed descriptor, `SharedMemory`, or paged calls) with the browsing requirements in hand. Rev 1's
listing-sink file and `ArchiveListingCodec` are withdrawn from M3.2; the `maxRows` parameter is
what lets M3.3 add a second transport without changing this call.

### 2.3 `ArchiveSource`: one `Uri` in, one seekable descriptor out

New package `io.github.mbaliga.fylz.archive` (the engine-facing Kotlin; `decoder/` keeps the IPC
plumbing, `data/ArchiveService` keeps the zip4j code that M3.4–M3.10 retire).

```kotlin
class ArchiveSource(
    private val context: Context,
    private val limits: ArchiveLimits,
    private val isSeekable: (ParcelFileDescriptor) -> Boolean = { it.statSize >= 0L },
    private val availableCacheBytes: () -> Long? = { StatFs(context.cacheDir.path).availableBytes },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed class Resolved : Closeable {
        abstract val pfd: ParcelFileDescriptor
        class Direct(override val pfd: ParcelFileDescriptor) : Resolved()
        class Staged(override val pfd: ParcelFileDescriptor, val workspace: File) : Resolved()
    }
    suspend fun resolve(uri: Uri): Resolved   // throws ArchiveSourceException
}
sealed class ArchiveSourceException(message: String) : IOException(message) {
    class Unreadable(cause: Throwable) ; class InsufficientSpace(required: Long, available: Long?)
    class ArchiveTooLarge(limit: Long) ; class SizeMismatch(declared: Long, actual: Long)
}
```

Resolution, in order:

1. `contentResolver.openFileDescriptor(uri, "r")`. If it throws (`FileNotFoundException` — which
   is also what a provider raises for a sub-range asset, "Not a whole file";
   `UnsupportedOperationException`; `SecurityException`) → step 3 through `openInputStream`; if
   that throws too → `Unreadable`.
2. `isSeekable(pfd)`: default `pfd.statSize >= 0L` — AOSP's `getStatSize()` is exactly
   `S_ISREG(st_mode) ? st_size : -1`, so it is the `fstat` test without `android.system.Os`.
   Seekable → `Direct(pfd)`. Not seekable → close that pfd, step 3. (The lambda is injectable
   because Robolectric's `createPipe()` is file-backed and would report a size; tests inject
   `{ false }` for the pipe provider.)
3. **Stage, with the space check first.** Declared size: `DocumentsContract.Document.COLUMN_SIZE`
   from a one-column query on `uri` (`null` when the provider does not say). If `declared >
   limits.maxArchiveBytes` → `ArchiveTooLarge` before copying anything. Required bytes =
   `(declared ?: limits.maxArchiveBytes) + MIN_TEMPORARY_HEADROOM` (the 16 MiB constant already in
   `ArchiveSpacePolicy`; expose it as `ArchiveSpacePolicy.stagingRequirement(declaredBytes: Long?,
   maxArchiveBytes: Long): Long`), checked against `availableCacheBytes()` through the existing
   `ArchiveSpacePolicy.evaluate(required, available, label = "temporary storage")`. Not enough →
   `InsufficientSpace` and **nothing is copied**. Then copy into `cacheDir/archive-work/<uuid>/input`
   (today's directory) with: a hard cap at `min(declared ?: MAX, limits.maxArchiveBytes)` — a
   provider that under-declares and keeps sending past `declared` is a `SizeMismatch`, past the
   limit an `ArchiveTooLarge`; and, when the size was unknown, a re-check of
   `availableCacheBytes() >= MIN_TEMPORARY_HEADROOM` after every 64 MiB copied → `InsufficientSpace`.
   Any failure deletes the workspace. Success → `ParcelFileDescriptor.open(file, MODE_READ_ONLY)` →
   `Staged(pfd, workspace)`. `close()` closes the pfd and `deleteRecursively()`s the workspace.
4. Sweep: on the first `resolve()` per process, delete entries under `cacheDir/archive-work/`
   older than 24 h (a process death between staging and `close()` leaves them; today's code has the
   same leak and — `UNVERIFIED`, grep before adding — no sweep).

Everything the UI process can open by `Uri` reaches Rust only through `resolve()`, so the engine's
`NotSeekable` (§2.1) is unreachable from the app in practice; if it ever comes back, the outcome is
reported as `NOT_SEEKABLE` (the engine's answer, shown as "could not be opened") and logged at
`Log.w` as a bug, not retried.

### 2.4 The Rust side

Part 3 §6 defines the amended types; the ones this design relies on:

```rust
pub enum EntryKind { File, Directory, Symlink, Hardlink, Other }
pub struct EntryMetadata { path: String, name_lossy: bool, kind: EntryKind, link_target: Option<String>,
                           uncompressed: Option<u64>, compressed: Option<u64> /* always None */,
                           mtime: Option<i64>, mode: u32, encrypted_data: bool, encrypted_metadata: bool }
pub struct Inspection { archive_bytes: u64, format_code: u32, format_name: Option<String>, filters: Vec<String>,
                        has_encrypted_entries: Option<bool>, entries: Vec<EntryMetadata> }
pub struct Limits { /* the seven policy fields */ max_listing_entries: usize /* default 200_000 */ }
pub enum ArchiveError { NotSeekable(String), Unsupported(String), Fatal(String), NonUtf8Path, LimitExceeded { entry: String, rule: &'static str } }
pub fn inspect(fd: RawFd, limits: &Limits) -> Result<Inspection, ArchiveError>;
pub fn inspect_with_policy(fd: RawFd, limits: &Limits) -> Result<(Inspection, Decision), ArchiveError>;
```

Notes that are M3.2's rather than part 3's:

- **Non-UTF-8 entry names do not fail inspection.** zip4j decoded legacy (CP437 etc.) names and
  never failed; libarchive passes them through as raw bytes off Windows (`zip.c:1127-1190`), and
  rev 1 would have failed the whole inspection with `NonUtf8Path` — a regression for Inspect, the
  Extract check and Preview on every legacy ZIP until M3.7. So `inspect` decodes with
  `String::from_utf8_lossy` and sets `name_lossy = true` on that entry; the policy validates the
  lossy string (its structural rules — depth, length, `..`, absolute — are unaffected by
  replacement characters). `read_entry(fd, path)` keeps `NonUtf8Path` (an exact-match lookup on a
  lossy name is ambiguous; M3.7 gives both a real charset). Logged as a deviation.
- **`mtime` is `time_t`**, which is `long` — 32 bits on armv7 bionic. The `sys` declaration is
  `-> c_long`, widened to `i64` (the same hazard `lib.rs` already documents for `mode_t`). The
  M3.2a gate therefore includes a three-ABI `cargo ndk … -p fylz-archive` build, because
  `fylz-ffi-android` does not depend on `fylz-archive` until M3.2b.
- `format_name` is `Option<String>` (`archive_format_name` is NULL for an empty archive).
- **Memory bound:** `max_listing_entries` defaults to 200,000 — about 30 MB of `EntryMetadata` in
  Rust — a bound for `:decoders` distinct from the policy's `max_entries` (10,000), which stays an
  *extraction* rule evaluated afterwards on the collected table (browsing in M3.3 must still list
  archives the policy would refuse to extract). Rev 1's 1,000,000 implied ~150 MB and breached
  §4.4's 256 MB target. The implementing agent measures peak RSS of `inspect` on a synthetic
  200,000-entry tar (built with `tarfile` in the fixture script, not committed) and records it in
  the PROGRESS row; if it exceeds 64 MB the default drops until it fits.
- **Hardlinks** are detected with `archive_entry_hardlink() != NULL` *before* `archive_entry_filetype`
  (libarchive reports them as `AE_IFREG`).

`fylz-ffi-android` adds `fylz-archive` as a dependency and exports, **synchronously**:

```rust
#[derive(uniffi::Record)] pub struct ArchiveLimitsRecord { max_entries: u32, max_archive_bytes: u64, max_file_bytes: u64,
    max_total_uncompressed_bytes: u64, max_compression_ratio: f64, max_path_depth: u32, max_name_length: u32, max_listing_entries: u32 }
#[derive(uniffi::Enum)]   pub enum ArchiveEntryKindRecord { File, Directory, Symlink, Hardlink, Other }
#[derive(uniffi::Record)] pub struct ArchiveEntryRecord { path: String, name_lossy: bool, kind: ArchiveEntryKindRecord, link_target: Option<String>,
    uncompressed: Option<u64>, mtime: Option<i64>, mode: u32, encrypted_data: bool, encrypted_metadata: bool }
#[derive(uniffi::Record)] pub struct ArchiveInspectionRecord { archive_bytes: u64, format_code: u32, format_name: Option<String>, filters: Vec<String>,
    entry_count: u32, file_count: u32, directory_count: u32, link_count: u32, total_uncompressed: Option<u64>,
    has_encrypted_entries: bool, has_encrypted_metadata: bool, has_lossy_names: bool,
    policy_allowed: bool, policy_reason: Option<String>, rows: Vec<ArchiveEntryRecord>, rows_truncated: bool }
#[derive(uniffi::Error, Debug)] pub enum ArchiveEngineError { NotSeekable { detail: String }, Unsupported { detail: String },
    Corrupt { detail: String }, LimitExceeded { entry: String, rule: String } }   // manual Display impl; no thiserror
#[uniffi::export] pub fn archive_inspect(fd: i32, limits: ArchiveLimitsRecord, max_rows: u32) -> Result<ArchiveInspectionRecord, ArchiveEngineError>;
```

- Names end in `Record` because uniffi generates Kotlin classes with the Rust names in package
  `io.github.mbaliga.fylz.core`, and `DecoderService` imports both those and the `decoder.*`
  Parcelables; the field is `detail`, never `message`, because uniffi's generated error class
  `class X(val message: String) : ArchiveEngineException()` conflicts with `Throwable.message`
  and does not compile. uniffi has no `usize`, hence the `u32`/`u64` fields; `From` conversions
  both ways are total (exhaustive `match`, so a new `EntryKind` variant fails to compile rather
  than silently mapping). `has_encrypted_entries = (engine Some(true)) || any entry
  encrypted_data`; `has_encrypted_metadata = any entry encrypted_metadata`. `rows` are the first
  `max_rows` entries, taken **in Rust**, so a large listing is never copied across uniffi.
- Synchronous because the call runs on a Binder thread that has nothing else to do; `sniff` is
  `async` without an `.await` and `DecoderService` wraps it in `runBlocking` — harmless, but a
  pattern not to copy. The fd is used as a plain `i32`, never wrapped in an owning `File`.

**Outcome table** (engine → `ArchiveInspection.outcome`):

| Engine result | Outcome | Notes |
|---|---|---|
| `Ok` | `OK` | |
| `NotSeekable` | `NOT_SEEKABLE` | unreachable via `ArchiveSource`; logged as a bug |
| `Unsupported` | `UNSUPPORTED` | first `archive_read_next_header` fails with `archive_format(a) == 0` ("Unrecognized archive format", `archive_read.c:781`), or a 7z with an encrypted header (`7zip.c:1644-1655`, message contains "encrypted") |
| `Fatal` | `CORRUPT` | any other libarchive `ARCHIVE_FATAL` |
| `LimitExceeded` | `LIMIT_EXCEEDED` | `max_listing_entries`; `message` = rule + entry |
| `NonUtf8Path` | never from `inspect` (lossy names) | if it appears, `INTERNAL` |
| Kotlin `Throwable` in the service | `INTERNAL` | exception class name as `message` |

Build consequences to record: the host `cargo build -p fylz-ffi-android` that Gradle runs for
bindgen now compiles libarchive plus all five companions on the host (it already does for `cargo
test`); the three shipped `.so`s grow by the static libarchive + companions. Record per-ABI
`libfylz_ffi_android.so` sizes before and after in the PROGRESS row against REPORT-M2's 12 MB/ABI
budget.

### 2.5 `DecoderService` and `DecoderClient`

`DecoderService.inspectArchive(archive, limits, maxRows)`: `archive.use { a -> engine(a.fd,
limits.toRecord(), maxRows) }` where `engine` is a constructor-injected lambda defaulting to
`FylzCore::inspectArchive` (so the **mapping** — each `ArchiveEngineException` subclass to its
outcome, `Throwable` to `INTERNAL`, the record-to-Parcelable conversion — is unit-tested on the JVM
without a native library; the generated exception classes are plain Kotlin). `runBlocking` is not
needed for a synchronous uniffi function.

`DecoderClient` — two changes:

1. **The timeout abandons the call** (finding 1, a fix to M2.4's contract). `call` runs the Binder
   transaction in a job that is *not* a child of the timeout scope — `CoroutineScope(Dispatchers.IO
   + SupervisorJob()).async { block(service) }` held by the client — and the timeout wraps only
   `deferred.await()`. On timeout: `dropConnection()` (unbind → the platform reaps `:decoders` →
   the orphaned transaction returns with `DeadObjectException`, which the job swallows). The thread
   is occupied until then, which is what `Dispatchers.IO`'s elastic pool is for. Tests assert
   **elapsed time**: a stub that sleeps 1,500 ms under a 100 ms timeout returns `null` in under
   1,000 ms and `unbind` has been called.
2. **Per-call timeout and a distinguishable result.** `call` takes `timeoutMillis`; the class
   default stays 5 s for `ping`/`sniff`. New `suspend fun inspectArchive(archive, limits, maxRows,
   timeoutMillis = STRUCTURE_TIMEOUT_MILLIS /* 30_000, section 4.4's structure budget */):
   DecoderCall<ArchiveInspection>` with `sealed class DecoderCall<T> { Ok(value), TimedOut, Failed }`
   — a compressed tarball's header pass decompresses the whole stream, so a multi-GB `.tar.xz`
   *will* hit 30 s, and the UI must say "took too long to read" rather than "could not be read
   safely" (a crash). `ping`/`sniff` keep their nullable API by mapping `Ok(v) → v`, else `null`.
   The client never closes the caller's descriptor.

**Lifetime.** `DecoderClient` has no close method and `remember { }` disposes nothing, so a
composition-scoped client bound with an Activity context leaks the `ServiceConnection`; and
`FylzV1App.kt` sits exactly on its 2,287-line ratchet, so nothing can be added there. The client
and the `ArchiveInspector` (§2.6) are therefore **application-scoped**: `FylzApplication` gains
`val archiveInspector: ArchiveInspector by lazy { … }` bound with the application context, the same
pattern as `operationRunner`. Call sites reach it with `(context.applicationContext as
FylzApplication).archiveInspector`. Idle policy for M3.2: **none** — once used, the binding (and so
`:decoders`) lives as long as the app process, which is what survey risk 7 asks for during a
browsing session; the cost is one idle isolated process, and M3.3 decides an idle-unbind after
measuring it (recorded in REVIEW_QUEUE).

### 2.6 `ArchiveInspector`: what the UI calls

```kotlin
class ArchiveInspector(private val source: ArchiveSource, private val client: DecoderClient, private val limits: ArchiveLimits) {
    suspend fun inspect(uri: Uri, maxRows: Int = 500): ArchiveInspectionResult
}
sealed class ArchiveInspectionResult {
    data class Ready(val summary: ArchiveInspection, val staged: Boolean,
                     val temporarySpace: ArchiveSpaceRequirements?, val temporarySpaceAvailable: Long?) : ...
    data class Refused(val outcome: Int, val message: String?) : ...        // engine answer: UNSUPPORTED, CORRUPT, LIMIT_EXCEEDED, NOT_SEEKABLE, INTERNAL
    data object TimedOut : ...                                              // "took too long to read"
    data object Unavailable : ...                                           // decoder crashed / bind failed: "could not be read safely"
    data class SourceFailed(val cause: ArchiveSourceException) : ...        // InsufficientSpace, ArchiveTooLarge, SizeMismatch, Unreadable
}
```

`inspect`: `source.resolve(uri).use { resolved -> client.inspectArchive(resolved.pfd, limits,
maxRows) }`, then map. Nothing outlives the call: the staged copy (if any) is deleted when
`resolve(...).use` ends, i.e. as soon as the summary is in hand — the dialog shows the summary, not
the archive, so there is nothing to hold (rev 1's device check said otherwise; corrected in §2.9).
`temporarySpace` is `ArchiveSpacePolicy.requirements(archiveBytes, totalUncompressedBytes)` computed
in Kotlin, because **extraction still stages until M3.4** and the dialog's "Temporary space
required/available" rows stay truthful.

Call-site changes (M3.2c), all three:

- `ArchiveToolsOverlay.kt:55,146,306-352`: `service.inspectZip(uri)` → `inspector.inspect(uri)`;
  the dialog shows the format **family** (from `formatCode`: "ZIP archive", "7-Zip archive",
  "ISO 9660 image", "tar archive" + filters, …) with an "encrypted" suffix, the counts, sizes,
  space rows and verdict as today, plus "copied to temporary storage first" when `staged`.
  **The confirm button is shown only when `formatCode` is ZIP** (extraction is still zip4j) and
  its enabled state is `policyAllowed`. Because Inspect's verdict now comes from the Rust rules and
  `extractZip` re-checks with the Kotlin rules, the two can disagree until M3.4 — a logged
  deviation, not something to paper over.
- `FylzV1App.kt:1012`: `archiveService.inspectZip(archiveUri).encrypted` → the app-scoped
  inspector's `Ready.summary.hasEncryptedEntries`, `false` on any other result — inline, on the same
  line count; `FylzV1AppSizeTest` stays at 2,287.
- `SpecializedDocumentPreview.kt:183-258` (`ZipArchivePreview`): `produceState` calls the inspector;
  `ArchiveInspectionContent` reads `rows`/`rowsTruncated`, `fileCount`/`directoryCount`/
  `linkCount`, `totalUncompressedBytes`, the policy fields and the family label; `TimedOut`,
  `Unavailable`, `Refused`, `SourceFailed` each render through the existing
  `UniversalInspectorPreview` fallback with their own message. `PreviewPane.kt:156`'s format gate is
  unchanged in M3.2 (M3.3 widens preview to every libarchive format).
- Inspect's picker MIME list in the overlay widens from the ZIP family to add
  `application/x-7z-compressed`, `application/x-iso9660-image`, `application/x-tar`,
  `application/gzip`, `application/x-xz`, `application/zstd`, `application/x-bzip2` — this is what
  makes "7z and ISO are read with seeks" reachable from the UI; logged.
- Deleted: `ArchiveService.inspectZip`, `data.ArchiveInspection`, `readMetadata`'s inspection-only
  callers; `stageArchive` stays for `extractZip`/`createZip` with a header comment naming M3.4.
  `fylz.extract`'s `enabledWhen` (ZIP family only) is unchanged.

### 2.7 Fixtures

`tools/fixtures/make_archive_fixtures.py` (stdlib `zipfile`/`tarfile`, plus pip `py7zr` and
`pycdlib` — both pip-installable, neither preinstalled; document the pip line as
`make_compression_fixtures.py` does). Outputs in `core/fixtures/archives/`, each under 128 KiB,
**deterministic**: fixed source-file mtimes via `os.utime` (py7zr `writestr` stamps the current
time otherwise), `time.time` patched for pycdlib, sorted names. The script asserts its own
determinism by building twice and comparing bytes.

| File | Made how | Proves |
|---|---|---|
| `sample-cd.zip` | `zipfile` normal write, 5 files in 2 dirs, deflate | baseline ZIP with central directory |
| `sample-streamed.zip` | `zipfile` writing to a file object **without `seek`/`tell`** → bit-3 data descriptors, local-header sizes zero (verified) | the seekable reader reads sizes from the central directory; the stream reader reports none |
| `sample-copy.7z` | `py7zr` with `FILTER_COPY` **and `set_encoded_header_mode(False)`** (verified: py7zr otherwise writes an LZMA2-encoded header even for COPY) | 7z listing via the end-of-file header and entry data via a backward pack seek, without liblzma |
| `sample-lzma2.7z` | `py7zr` default (LZMA2, encoded header) | 7z with liblzma (part 2e) |
| `sample.iso` | `pycdlib` ISO9660 level 3 + Joliet + Rock Ridge, nested dir (about 69,632 B) | ISO listing with lseek-backed skips; Rock Ridge names. pycdlib has no API for extent placement, so **no out-of-order fixture**: the row records that ISO listing needs no backward seek for in-order images |
| `hostile/zip-slip.zip`, `hostile/absolute-path.zip` | `zipfile` with hand-set names `../evil`, `/etc/passwd` (kept verbatim; verified) | end-to-end file → policy refusal with the Kotlin-identical reason string |
| `hostile/symlink-escape.tar` | `tarfile` symlink member → `/etc/passwd` | the **new link rule** (part 3 §6) refuses it |
| `hostile/many-entries.tar` | `tarfile`, 10,001 zero-length members (about 5 MB uncompressed → shipped as `.tar.zst`, a few KB) | `max_entries` refusal on a real file |

The full M3 acceptance corpus lands with M3 acceptance. Fixtures are used by Rust tests only;
Kotlin unit tests never load native code (they drive fakes through the `bind`/`unbind`,
`isSeekable` and `engine` seams).

### 2.8 Tests

Rust (`fylz-archive`) — the point is to **discriminate** seekable from not, so each positive has a
pipe-fed negative control through `open_unchecked` + `std::io::pipe()`:
- `open_refuses_a_pipe_and_a_socket_with_not_seekable` (`std::io::pipe()`,
  `UnixStream::pair()`; the message names the type).
- `inspect_ignores_the_descriptors_initial_offset` (`sample-cd.zip`; seek the fd to the middle,
  inspect, compare with a fresh open).
- `streamed_zip_sizes_come_from_the_central_directory_only_when_seekable`: file → every
  `uncompressed` is `Some`; the same bytes through a pipe → the streaming reader yields `None`s.
- `seven_zip_entry_data_needs_a_backward_seek`: file → `read_entry` returns the bytes; pipe →
  an `Err` (seek failure), never a panic.
- `iso_lists_nested_directories_with_rock_ridge_names` (file only; the row records why there is no
  negative control).
- `hostile_fixtures_are_refused` (four files → `policy_allowed == false`, each with its reason).
- `lossy_names_are_flagged_not_fatal` (a ZIP built in-test with a CP437 byte in a name).
- `format_code_and_filters_per_fixture` (copy the strings/codes libarchive returns; do not guess).
- `max_listing_entries_stops_the_pass` (a synthetic tar built with `tarfile` at test time is not
  possible in Rust; use the existing in-test `ar` archive builder — it builds `ar`, not tar — with
  the limit set below its member count).

Rust (`fylz-ffi-android`): `archive_inspect` on `sample-cd.zip` round-trips the records with
`max_rows = 2` → `rows.len() == 2`, `rows_truncated`; a negative fd → `NotSeekable`, not a panic;
conversions total.

Fuzz (`core/fuzz`, its own workspace — `cargo test --workspace` never builds it): part 3's
`policy_evaluate` and `archive_entries` targets are updated for the amended types in the same
commit as the types, and `cargo +nightly fuzz build` joins the M3.2a gate so a type change cannot
silently break them; the new fixtures seed `archive_entries`' corpus. No new target.

Kotlin (Robolectric, `app/src/test`):
- `ArchiveSourceTest`: a `FylzFilesDocumentsProvider` file `Uri` → `Direct` and **no** entry under
  `cacheDir/archive-work` (the "no whole-archive staging" assertion); a pipe-backed provider
  (adapt `FaultyDocumentsProvider`'s `createPipe()`-fed `openDocument` for read mode, `isSeekable =
  { false }`) → `Staged`, bytes identical, workspace gone after `close()`; `availableCacheBytes`
  too small → `InsufficientSpace`, no workspace created; declared size over the limit →
  `ArchiveTooLarge` before any copy; a provider sending more than it declared → `SizeMismatch`, no
  workspace left; unknown size with the space vanishing mid-copy (inject a counter-driven
  `availableCacheBytes`) → `InsufficientSpace`; a stale workspace older than 24 h is swept.
- `ArchiveParcelablesTest`: `ArchiveInspection` (with 500 rows), `ArchiveEntryInfo`,
  `ArchiveLimits` survive `Parcel` write/read.
- `DecoderServiceMappingTest`: through the `engine` lambda — each `ArchiveEngineException`
  subclass → its outcome; `Throwable` → `INTERNAL`; record → Parcelable field for field.
- `DecoderClientTest`: existing anonymous `Stub`s implement the new AIDL method; **elapsed-time**
  hang test (§2.5); `inspectArchive` timeout → `TimedOut` and unbind called; `DeadObjectException`
  → `Failed` and the next call rebinds; a stub returning `outcome = CORRUPT` → `Ok(summary)`.
- `ArchiveInspectorTest`: fake client → `Ready` with space rows computed; `TimedOut`/`Failed`
  mapped; `InsufficientSpace` → `SourceFailed` and the client never called; `staged = true` for the
  pipe provider and the workspace deleted after `inspect` returns.
- `NoHardCodedMenusTest`/`ActionResolverGoldenTest`/`FylzV1AppSizeTest` unchanged and green.

### 2.9 Device checks (`DEVICE_CHECKS.md` §17, "M3.2 — seekable descriptors into `:decoders`")

1. Inspect a ZIP, a 7z and an ISO from `Downloads` (local provider), and preview-focus an APK:
   format family, counts and sizes appear; `adb shell ls /data/data/<pkg>/cache/archive-work`
   stays empty throughout.
2. Inspect the same ZIP through a third-party provider that streams (Google Drive with a
   non-downloaded file): the dialog says it was copied to temporary storage first;
   `archive-work` is empty again once the dialog is up (the copy is released when the summary is
   in hand).
3. A ZIP with 100,000 entries (script in the checklist) inspects without
   `TransactionTooLargeException` in logcat; the dialog shows 500 rows and "only the first 500".
4. SELinux: `adb logcat | grep avc` shows no denial for `isolated_app` reading the archive descriptor.
5. Hang: a debug-only `inspectArchive` variant is not added; instead use §14's existing
   test-only hang command against the new client — the coroutine returns `TimedOut` within
   about 30 s, `:decoders` disappears from `adb shell ps`, and the next inspect works.
6. Kill `:decoders` mid-inspect (`adb shell am kill`): "could not be read safely"; the next
   inspect works (rebind).
7. A legacy ZIP with CP437 names inspects (names shown with replacement characters), not "unsupported".

## 3. Sequencing and gates

Four commits, each green on the full gate (`./gradlew --no-daemon :app:testDebugUnitTest
:app:lintDebug :app:assembleDebug`; `cd core && cargo test --workspace && cargo clippy --workspace
--all-targets -- -D warnings && cargo fmt --check && cargo deny check && (cd fuzz && cargo +nightly
fuzz build)`; `cargo ndk --platform 31 -t arm64-v8a -t armeabi-v7a -t x86_64 build --release -p
fylz-archive -p fylz-ffi-android`):

- **M3.2a (Rust):** `NotSeekable` + rewind + `open_unchecked`; `Unsupported`; lossy names;
  `inspect(fd, &limits)`/`inspect_with_policy`; `max_listing_entries` + the RSS measurement; the
  fuzz targets updated; `make_archive_fixtures.py` and the fixtures; the Rust tests of §2.8.
  (Part 3 §6's type amendments land with part 3 itself; if part 3 landed without them, this commit
  adds them and updates part 3's tests.) PROGRESS row: fixture SHA-256s, libarchive format
  codes/strings observed, RSS figure, the ISO negative-control note.
- **M3.2b (client fix + FFI + IPC):** the `DecoderClient` timeout fix with its elapsed-time test
  (its own paragraph in the row and a REVIEW_QUEUE correction under GATE-M2, since M2.4's
  "kill-and-restart on timeout" was not true); `fylz-ffi-android` depends on `fylz-archive`;
  `archive_inspect`; parcelize plugin; the Parcelables; the AIDL method; `DecoderService.inspectArchive`
  with the `engine` seam; `DecoderClient.inspectArchive` + `DecoderCall`; the mapping, parcel and
  client tests. PROGRESS row: per-ABI `.so` sizes before/after.
- **M3.2c (app):** `ArchiveSource`, `ArchiveSpacePolicy.stagingRequirement`, `ArchiveInspector`,
  `FylzApplication.archiveInspector`; the three call sites; the overlay's ZIP-only Extract button
  and widened Inspect MIME list; delete `inspectZip`/`data.ArchiveInspection`; header comments on
  `stageArchive`/`extractZip` and on `ArchiveExtractionPolicy.kt` (M3.4 deletes it);
  `ARCHITECTURE.md` (the decoder section describes `inspectArchive`, the seekable-descriptor rule
  and the corrected timeout; line 155's "stages data only in app-private cache" is amended for
  inspection); `DEVICE_CHECKS.md` §17; the `REVIEW_QUEUE.md` entry; PROGRESS row; PR #19's
  description gets the M3.2 line.

`REVIEW_QUEUE.md` entry for M3.2 (log-and-continue):
1. The engine refuses non-seekable input outright (§2.1) instead of streaming what it can; the
   pipe-fed negative controls are the evidence for what streaming would lose.
2. The Kotlin policy copy's deletion moves from "M3.2/M3.3" to M3.4 (§0). Until then Inspect
   (Rust rules) and Extract (Kotlin rules) can disagree on the same ZIP (§2.6).
3. `compressed` stays `None` for good: libarchive has no per-entry compressed size, so once M3.4
   deletes the Kotlin copy, **ZIP loses the per-entry ratio rule** that part 3 decision 1 assumed it
   would keep; the archive-level ratio check and the runtime caps are the defence.
4. Non-UTF-8 names are decoded lossily and flagged rather than failing (§2.4), until M3.7.
5. The new link rule and link skipping in `extract()` (part 3 §6): a rule the Kotlin policy never had.
6. MASTER_PLAN §4.4's 256 MB address-space target is not enforced; `max_listing_entries` (default
   200,000, measured) is the only memory bound in `:decoders`.
7. §4.4's kill-and-restart on timeout did not work before M3.2b (`withContext` inside
   `withTimeoutOrNull`); corrected under GATE-M2 as well.
8. "Remote streams stage to cache" is structural only: no remote archive opens after M3.2 (§0).
9. Inspect is widened beyond the ZIP family (§2.6); Extract is not.
10. Extraction still stages whole archives until M3.4.
11. Full-listing transport deferred to M3.3 (§2.2); M3.2 carries the first 500 rows in the
    Parcelable, as §4.4 says.
12. No idle-unbind policy for the application-scoped `DecoderClient` (§2.5); M3.3 measures and decides.

## 4. Risks

- **`isolated_app` and the passed descriptor** — reading through a Binder-passed fd is how `sniff`
  already works and §14 exercised it; §17 item 4 re-checks with the larger reads.
- **Compressed tarballs and the 30 s budget** — a multi-GB `.tar.xz` header pass decompresses
  everything and times out with "took too long"; honest, and M3.3 (which needs incremental
  listing anyway) revisits the budget.
- **Partial results in `:decoders`** — a corrupt archive after 50,000 valid headers returns
  `CORRUPT` with no rows. Acceptable for M3.2; M3.3 may add a `partial` flag.
- **py7zr/pycdlib versions** — the generator pins `py7zr==1.1.3`, `pycdlib==1.20.0` in its header
  comment; a different version may produce different bytes, which the determinism assertion is
  there to catch.

## 5. Amendments this design makes to part 3

All in `DESIGN-M31-PART3-EXTRACT-AND-POLICY.md` §6 (added with this rev): `EntryKind` replacing
`is_directory`; `name_lossy`, `link_target`, `mtime` (`c_long`), `mode`, `encrypted_data`,
`encrypted_metadata` on `EntryMetadata`; `Inspection.format_code`/`format_name`/`filters`/
`has_encrypted_entries`; `Limits.max_listing_entries`; `ArchiveError::NotSeekable`/`Unsupported`;
`inspect(fd, &limits)` and `inspect_with_policy`; the link rule; links skipped by `extract()`
with `ExtractReport.skipped_links`; hardlink detection order; `policy_evaluate`/`archive_entries`
fuzz targets take the amended types.

## 6. Review findings and disposition (rev 1 → rev 2)

Blockers, all adopted: (1) the timeout that never abandoned a hung call → §2.5 item 1, fixed in
M3.2b with an elapsed-time test; (2) uniffi error field named `message` does not compile → `detail`;
(9) `ZipArchivePreview` was an unlisted caller of `inspectZip` → in scope, §2.6; (10) the 2,287-line
ratchet, the overlay's own `ArchiveService`, and `remember` not disposing → application-scoped
inspector in `FylzApplication`, §2.5; (11) no rule could refuse `symlink-escape.tar` → link rule
and link skipping added to part 3 §6.

Should-fix, adopted: (3) `time_t` is 32-bit on armv7 → `c_long`, three-ABI build of `fylz-archive`
in the 3.2a gate; (12) Extract button only for ZIP, `formatCode` for decisions, space rows
recomputed in Kotlin, the rule-set disagreement logged; (15) signatures and the outcome table
written out, `Unsupported` added; (16) `max_listing_entries` 1,000,000 → 200,000 with a measured
RSS; (17) fuzz targets in the gate, hardlink detection order; (21) listing sink deferred to M3.3
and the first 500 rows carried in the Parcelable — which made (13) codec framing and (14) listing
file lifetimes moot; (22) pipe-fed negative controls via `open_unchecked`, ISO recorded honestly;
(23) service mapping behind an `engine` seam, elapsed-time asserts, cleanup tests; (24) the
REVIEW_QUEUE list expanded, including the lossy-name deviation.

Nits, adopted: (4) ISO wording, (5) rewind is correctness, (6) `std::io::pipe()` and the `ar`
helper, (7) `Record` suffix / `u32` fields / no `thiserror`, (8) `set_encoded_header_mode(False)`,
determinism, 128 KiB bound, (18) declared-size cap, incremental space check, `statSize` as the
default probe, `ArchiveLimits` as the one type, (19) wording and `format_name: Option`, (20) survey
correction on 7z, (25) root plugin line and the `:1012` citation.

Not adopted: none.
