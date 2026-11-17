# M3.2 survey: random access, no whole-archive staging (read-only fact sheet for DESIGN-M32-SEEKABLE-PFD.md)

Read-only fact sheet, 2026-09-25. Paths are relative to `/home/user/Fyl-Manager`. Working tree is
mid-M3.1-part-2c (zlib): `build.rs`, `lib.rs`, `.gitmodules` modified and uncommitted; HEAD `5c8c0e3`.

## 1. Decoder-process plumbing as built (M2.4/M2.5)

| Piece | Where | Fact |
|---|---|---|
| AIDL | `app/src/main/aidl/io/github/mbaliga/fylz/decoder/IDecoderService.aidl:6-12` | `boolean ping()`, `String sniff(in ParcelFileDescriptor pfd)`. Comment: pfd is read-only, caller-owned, not valid after return. |
| Service | `app/src/main/java/io/github/mbaliga/fylz/decoder/DecoderService.kt:25-33` | `pfd.use { open -> runBlocking { FylzCore.sniffFile(open.fd) } }` — `getFd()` (not `detachFd()`), raw `Int` into Rust; `use` closes the service-side Binder dup after the call. `runBlocking` on the Binder thread is deliberate (lines 19-21). |
| Wrapper | `app/src/main/java/io/github/mbaliga/fylz/core/FylzCore.kt:8-13` | `suspend fun sniffFile(pathFd: Int): String = sniff(pathFd)`. |
| Rust side | `core/crates/fylz-ffi-android/src/lib.rs:26-47` | `pub async fn sniff(path_fd: i32) -> String`; fd wrapped in `ManuallyDrop<File>` so Rust never closes it (41-46). It seeks `End(0)`/`Start(0)` (59-71) — moving the shared file offset. |
| Client | `app/src/main/java/io/github/mbaliga/fylz/decoder/DecoderClient.kt` | Lazy bind (62-72), `withTimeoutOrNull(timeoutMillis)` + `Dispatchers.IO` (80-90), default **5 000 ms** (93). Timeout or `RemoteException` → `dropConnection()` = `unbind` (56-60); `onServiceDisconnected`/`onBindingDied` → same (47-53). Returns `false`/`null`, never throws (74-78). Never closes the caller's pfd. |
| Manifest | `app/src/main/AndroidManifest.xml:93-97` | `isolatedProcess="true"`, `process=":decoders"`, `exported="false"`. Comment 84-91: no permissions, cannot open by path. |

- **Kill/restart:** there is no explicit kill. "Kill" is `unbindService`; the platform reaps the
  unbound isolated process (`docs/ARCHITECTURE.md:291-296`). A hung call is abandoned client-side
  while the Binder thread in `:decoders` keeps running until the process dies.
- **Limits enforced vs. documented:** only the 5 s timeout exists. No 256 MB address-space cap, no
  `setrlimit`, no 30 s "structure" budget (grep of `decoder/`, manifest, ffi `src/` for
  `256|rlimit|largeHeap` is empty). `ARCHITECTURE.md:291-294` says a real caller should pass
  per-purpose budgets explicitly.
- **Result types:** `Boolean`, `String` only. No Parcelables, no `SharedMemory`, no error channel
  (generated `fylz_ffi_android.kt:1084` uses `UniffiNullRustCallStatusErrorHandler`).
- **Callers:** none. `DecoderClient` is constructed nowhere in `app/src/main` (grep); `DEVICE_CHECKS.md:299`
  and `PROGRESS.md:82` (M2.5 tail) confirm `FileFormatRegistry` wiring was deferred to M4/M6.2.
- **Async mechanics:** generated `uniffiRustCallAsync` (`fylz_ffi_android.kt:859-884`) polls the Rust
  future from the Kotlin coroutine; `sniff` has no `.await`, so its body runs to completion inside the
  first poll on the calling thread (UNVERIFIED: inferred from uniffi's poll model, not traced).

## 2. The uniffi surface

- `core/crates/fylz-ffi-android/Cargo.toml:24-26`: `uniffi = "0.32.2"`, `fylz-sniff`. **No `fylz-archive`
  dependency** (expected). `uniffi-cli` feature gated to the bindgen bin (12-22). Proc-macros only
  (`uniffi::setup_scaffolding!()` lib.rs:11, `#[uniffi::export]` 14/25); no `.udl`. `uniffi.toml`:
  `package_name = io.github.mbaliga.fylz.core`, `cdylib_name = fylz_ffi_android`.
- Errors do not cross today: `sniff` returns `"unknown"` for any failure (lib.rs:22-24).
- Gradle: `app/build.gradle.kts:160-215` — `buildCoreDebug/Release` run cargo-ndk for three ABIs and
  then bindgen against a **host dev build** `core/target/debug/libfylz_ffi_android.so` (205).
  `fylz-archive/build.rs:20-21` warns: once the ffi crate depends on `fylz-archive`, that host build
  must compile libarchive + companions too (it does already for `cargo test`).
- uniffi 0.32.2 capabilities (from `~/.cargo/registry/.../uniffi_macros-0.32.2`): `#[derive(uniffi::Record/Enum/Error)]`
  (`src/lib.rs:117-141`); trait export with `callback_interface` (legacy Box-based foreign-only),
  `with_foreign` (alias of `rust, foreign`) and `async_runtime` (`src/export/attributes.rs:27-35`).
  So a `DestinationProvider` **can** be a foreign-implemented trait — but the "foreign" side is
  Kotlin **inside `:decoders`**, which has no storage. The real provider (UI process) can only be
  reached over AIDL.
- What `inspect/entries/read_entry/extract` need:
  - Records: `ArchiveEntry{path,size,is_directory}` (`fylz-archive/src/lib.rs:78-82`), part-3
    `EntryMetadata`, `Inspection`, `Limits`, `Decision`, `ExtractLimits`, `ExtractReport`; enums
    `Selection`; `ArchiveError{Fatal(String),NonUtf8Path}` (85-92) + `LimitExceeded{entry,rule}` as
    `#[derive(uniffi::Error)]`. `BorrowedFd`/`RawFd` cannot cross uniffi — use `i32`.
  - `dest_fd_provider`: two workable shapes. (a) Pre-opened fd list: UI process creates SAF documents
    and passes `ParcelFileDescriptor[]` over AIDL, `:decoders` hands the ints to Rust (thousands of
    fds → fd-limit risk, §7). (b) AIDL callback interface (`IDestinationSink.open(entryPath) →
    ParcelFileDescriptor`, `done(path, bytes)`) implemented in the UI process; the Kotlin
    `DestinationProvider` impl in `:decoders` forwards each Rust callback over Binder (one round-trip
    per entry). Either way the AIDL boundary, not uniffi, is the design point.
  - Large bodies: uniffi returns `Vec<u8>` as a copied `RustBuffer` — acceptable for `read_entry` of
    small files (previews), wrong for extraction. Write into a caller fd (part-3 design already does).
    `SharedMemory` (§4.4) fits bitmaps, not multi-GB streams; a `ParcelFileDescriptor` per output file
    or a pipe is the natural channel.

## 3. Today's archive path in the app

`app/src/main/java/io/github/mbaliga/fylz/data/ArchiveService.kt` (489 lines, zip4j):

| Step | Lines | Fact |
|---|---|---|
| Uri → file | `stageArchive` 345-353 | `contentResolver.openInputStream(uri)` copied whole into `cacheDir/archive-work/<uuid>/input.zip` (465-466), bounded by `maxArchiveBytes` (349). Done for **both** `inspectZip` (153) and `extractZip` (207) — the archive is staged twice per extract flow. |
| Inspect | 144-180 | zip4j `ZipFile`, `readMetadata` (355-363, has compressed+uncompressed sizes), `ArchiveExtractionPolicy.evaluate`, `ArchiveSpacePolicy.requirements` (163), `availableCacheBytes()` = `StatFs(cacheDir)` (417-419). |
| Extract | 182-275 | Policy (221-226); space checks temp (231-236) and destination via `queryRootAvailableBytes` (237-242, `storage/VolumeInfo.kt:114-134`); `extractBounded` to `cache/extracted/` (244-245, 277-311); then `copyIntoProvider` (251-253, 374-400) via `DocumentFile.createFile` + `openOutputStream(target.uri,"w")`. Output is written twice. Journal 74/200; cancel via `ensureActive` (252, 282); rollback deletes provider root (324-327). |

- Never uses `openFileDescriptor`; `getStatSize()` is used **nowhere** in the app (grep). Seekable-fd
  precedents: `SpecializedDocumentPreview.kt:74`, `PdfPagerPreview.kt:57`, `FontFilePreview.kt:38`
  (`openFileDescriptor(uri,"r")`); size via `openAssetFileDescriptor(...).length` at
  `MeshWireframePreview.kt:127`, `PdfPageTools.kt:225`.
- Local files reach the app through `storage/FylzFilesDocumentsProvider.kt:132-139`:
  `ParcelFileDescriptor.open(file, mode)` → regular, seekable fd. External SAF trees
  (`SafStorageProvider.kt:89-101`, persisted permissions) return whatever the foreign provider gives —
  possibly a pipe.
- `ExtendedArchiveBrowserService.kt` (228 lines, commons-compress `1.28.0`, `build.gradle.kts:105`):
  7z staged to cache (67-70), tar/cpio/ar streamed (101-142), compressed single streams fully
  inflated to count bytes (144-175). **No caller anywhere in `app/`** (grep) — already dead.
- Callers of `ArchiveService`: `ui/FylzV1App.kt:382` (instance), `extract()` handler 1007-1018
  (`inspectZip(...).encrypted` then destination picker), 605-609 runs `extractZip` through
  `operationRunner.run(FileOperationType.EXTRACT, ...)` — **not** `TransferWorker`
  (`TransferWorker.kt:25-27, 44-45` handles COPY/MOVE only); `compress()` 1006 → `createZip` 704.
  `ui/ArchiveToolsOverlay.kt:55, 87, 121, 146` (picker Uris; `ZIP_MIME_TYPES` 361).
  `ui/components/SpecializedDocumentPreview.kt:186` (`ZipArchivePreview`).
- Registry: `actions/BuiltInActions.kt:102-109` `fylz.extract` → `ctx.extract()`, enabled only for
  `isZipFamilyArchive` (`FylzV1App.kt:237`: zip, zipx, jar, apk, cbz); `fylz.archive.inspect` (458-462)
  and `fylz.archive.tools` (439-444) have no-op `run`; the overlay dispatches by id string
  (`ArchiveToolsOverlay.kt:169-171`); `RoomActionsRenderer.kt:279` mounts the overlay.
- Progress hook available: `OperationRunner.run` gives `report: (OperationProgress) -> Unit`
  (`operations/OperationRunner.kt:85-89`, fields 38-44); comment 76-77: "archive extract, which has
  none today".

## 4. Remote / non-seekable sources

- `network/RemoteProvider.kt:33-43`: `list`, `download(path, destination: File, onProgress)`, `upload`,
  `createDirectory`, `delete`. **No stream or fd API.** `RANGE_READ` (14) is declared only by S3
  (`S3RemoteProvider.kt:20`), unused for reads. No `DocumentsProvider` exists for any remote; the only
  provider is `FylzFilesDocumentsProvider`. Downloads write `.<name>.<nanoTime>.part` then rename
  (`SftpProvider.kt:93-110`, `SmbProvider.kt:97`, `WebDavService.kt:68`). `RemoteConnectionsDialog.kt:70-72`:
  download is "not yet wired to a destination picker"; `RemoteBrowser.kt` lists only.
- Consequence: today a remote archive cannot be opened at all. "Stage to cache" for remotes =
  `download()` into a cache workspace, then `ParcelFileDescriptor.open` on the result — which is
  seekable. Genuine pipe-backed PFDs come only from third-party SAF providers.
- Reusable: `ArchiveSpacePolicy` (`data/ArchiveSpacePolicy.kt:22-47`; headroom 16 MiB / 8 MiB; note
  `requirements` assumes temp = archive + uncompressed, i.e. the staging model — the seekable path needs
  a variant where temp ≈ 0 or archive-only); `availableCacheBytes` (`ArchiveService.kt:417`);
  `queryRootAvailableBytes` (`VolumeInfo.kt:114`); staging name convention `.fylz-part-<opId>-<idx>-<name>`
  (`operations/FileOperationService.kt:564-579`); `PreflightPolicy.InsufficientSpace`
  (`operations/PreflightPolicy.kt:47`); `SftpProviderConfig.maximumDownloadBytes` (`SftpProvider.kt:98`).

## 5. Formats and seek requirements (libarchive 3.8.9, `core/third_party/libarchive`)

- `fylz-archive/src/lib.rs:128-129` calls `support_filter_all` + `support_format_all`.
  Formats registered (`archive_read_support_format_all.c:53-72`): ar, cpio, empty, lha, mtree, tar,
  xar, warc, 7zip, cab, rar, rar5, iso9660, zip. Filters (`..._filter_all.c:41-83`): bzip2, compress,
  gzip, lzip, lzma, xz, uu, rpm, lrzip, lzop, grzip, lz4, zstd — a filter whose library is absent
  shells out to an external program (`lib.rs:295-297`), which does not exist on Android → failure.
- Backends compiled in (`build.rs`, live working tree): lz4 ON (227), zstd ON (232), **zlib ON (239,
  uncommitted part 2c**; `core/third_party/zlib` added, untracked fixtures `sample-deflate.zip`,
  `sample.tar.gz`, `truncated.tar.gz`), bzip2 OFF (219), LZMA OFF (220). All crypto OFF
  (OPENSSL/MBEDTLS/NETTLE). Effects: 7z LZMA/LZMA2 need liblzma (`archive_read_support_format_7zip.c:1368-1369,
  1754-1755`) — **most real 7z files are unreadable until xz lands**; 7z BZ2/Deflate need bzlib/zlib;
  PPMd/COPY/zstd are built in. ZIP deflate needs zlib (`..._zip.c:3432-3440`). No AES for ZIP or 7z.
  ISO zisofs needs zlib (`..._iso9660.c:218-219`). RAR5 uses no seek at all (grep empty).

| Format | Bid needs seek? | Listing on a pipe | Data on a pipe | Evidence |
|---|---|---|---|---|
| ZIP | Seekable bidder tries `__archive_read_seek(a,0,SEEK_END)` and returns 0 on failure (`zip.c:4038-4047`) → streamable bidder wins (`zip.c:3628-3636` registers both). | Yes, but sizes may be unset until the data descriptor (`zip.c:1785-1797`); central-directory sizes only via seekable reader (`zip.c:4590-4600`, then `__archive_read_seek` to each local header 4634-4636). | Yes (streaming) | header history `zip.c:39-42` |
| 7z | No — `read_ahead` only (`7zip.c:599-611`, `get_data_offset` 532-560). | Yes: header at end reached by `consume` forward (`7zip.c:3330-3345`, `seek_compat(..., compat=1)`). | **No**: `seek_pack` seeks backwards to pack streams (`3677-3698`); `seek_compat` with `can_seek==0` returns FAILED for a backward `SEEK_SET` (`4627-4650`) → "Seek error", FATAL. | |
| ISO9660 | No — 48 KiB `read_ahead` (`iso9660.c:521-523`). | Yes: single forward pass by design (`73-80`); forward moves via `consume` (`1745-1749`). | Mostly: backward seek only for out-of-order files, else warn+skip (`1403-1420`); repeated extents become hardlinks when unseekable (`1369-1372`). | |
| RAR4 | UNVERIFIED | UNVERIFIED | `__archive_read_seek` at `rar.c:1248, 1275, 1305` (multivolume data-block seeking; enclosing function not read). | |

- `archive_read_open_fd.c`: `fstat` (75); `S_ISREG` → `use_lseek=1`, size recorded (98-102); read/skip/
  seek/close callbacks are registered **unconditionally** (107-110). `file_skip` returns 0 when
  `!use_lseek` so libarchive reads and discards (144-145, 173-175). `file_seek` calls `lseek` regardless;
  on a pipe `ESPIPE` → `ARCHIVE_FAILED` "not seekable(PIPE)" (210-219). `archive_read.c:520` sets
  `can_seek=1` for the client filter, so libarchive *attempts* seeks on pipes and gets FAILED back
  (`archive_read.c:1696`).
- The fd's offset is shared with every dup (POSIX; Binder dups the fd). `archive_read_open_fd` starts
  at the current offset, never rewinds, and moves it; `entries()` + `read_entry()` on one fd need a
  fresh position (`lib.rs:105-109, 264-267`). Alternative: custom `archive_read_set_*_callback`s
  (`archive_read.c:343`) over `pread`, leaving the Kotlin PFD's offset untouched.

## 6. Tests and fixtures

- `app/src/test/java/io/github/mbaliga/fylz/decoder/DecoderClientTest.kt`: 8 Robolectric cases through the
  `bind`/`unbind` seam with fake `Stub`s; pfds are `createPipe()[0]` (48); hang = `Thread.sleep` (36-46);
  crash = `DeadObjectException` (134-136) or `onServiceDisconnected` (127). No real IPC; device steps
  in `docs/agent/DEVICE_CHECKS.md:288-314`.
- Archive: `data/ArchiveExtractionPolicyTest.kt`, `ArchiveExtractionPolicyFuzzTest.kt`,
  `ArchiveSpacePolicyTest.kt`. **No tests for `ArchiveService` or `ExtendedArchiveBrowserService`** (grep).
- Pipe-backed provider template: `app/src/test/java/io/github/mbaliga/fylz/storage/FaultyDocumentsProvider.kt`
  (wraps the real provider, `openDocument` 127-133; `throwAfterBytes` returns a `createPipe()` end fed by a
  thread, 184-198) — directly adaptable to a read-mode pipe for M3.2's "non-seekable" tests.
- Rust: `fylz-archive/src/lib.rs:304-547` (13 tests: `ar` builder 320-341; lz4/zstd sample + truncated;
  corrupt → `Fatal`). Fixtures `core/fixtures/`: `sample.tar.lz4/.zst`, `truncated.tar.lz4/.zst`
  (+ untracked gz/deflate); generator `tools/fixtures/make_compression_fixtures.py`. **No ZIP-with-central-
  directory, 7z, ISO or RAR fixtures.** `app/src/test/resources/fixtures/` does not exist (only `sftp/`).
- Fuzz: `core/fuzz/fuzz_targets/sniff.rs` only; CI smoke 60 s (`.github/workflows/android.yml:89-91`).

## 7. Risks for the design

1. **fd ownership across AIDL.** Binder gives `:decoders` a dup sharing the file offset; `DecoderService`
   closes its dup via `use` (`DecoderService.kt:29`), the client never closes the original (`DecoderClient.kt:78`).
   Rust must keep `ManuallyDrop` discipline (`ffi lib.rs:41-46`); libarchive's `archive_read_free` calls
   the close callback, but `archive_read_open_fd`'s `file_close` only frees buffers (`open_fd.c:227-236`) —
   it does **not** close the fd. Good, but a `File::from_raw_fd` anywhere else would.
2. **No storage in `:decoders`.** Every output fd must originate in the UI process. `extract()` of N
   entries means N SAF `createFile` + `openFileDescriptor(...,"w")` calls there, then either N fds up
   front (fd-table and Binder fd-count caps — UNVERIFIED exact values) or N callback round-trips.
   Batching (open K ahead) or a UI-owned staging directory fd used with `openat` are the options
   (whether SELinux `isolated_app` permits `openat` on a passed dir fd is UNVERIFIED).
3. **Timeouts.** 5 s default (`DecoderClient.kt:93`) is wrong for extraction; §4.4's 30 s is wrong for a
   5 GB 7z (M3 acceptance). Extraction needs a progress-based liveness contract (AIDL callback or
   `oneway` progress), not a wall-clock cap; `OperationRunner.run`'s `report` (`OperationRunner.kt:85-89`)
   is the consumer. `TransferWorker` only runs COPY/MOVE (`TransferWorker.kt:44-45`) — M3.4 must widen it.
4. **Cancellation.** A `withTimeoutOrNull` cancel abandons the Binder call; the native loop continues
   until unbind kills the process. Rust `extract()` needs a cancel flag checked per block, or the
   design accepts process kill as the cancel primitive (loses partial-output bookkeeping unless the UI
   process tracks the fds it handed out — it can, since it created them).
5. **Compression backends.** Until bzip2/xz land, 7z LZMA fails with "Unsupported"; ZIP deflate depends
   on the uncommitted part 2c. Encrypted 7z headers and AES entries are out of libarchive's reach
   (crypto OFF; header encryption unsupported regardless, `7zip.c:1653`) — needs the 7-Zip `.so` pack.
6. **Seekability detection in Kotlin.** Nothing calls `getStatSize()` today; a pipe returns −1 there
   (Android API; UNVERIFIED in repo). `fstat` in Rust (`open_fd.c:98`) is the authoritative check and
   already gates `use_lseek`; exposing "is regular file" from `inspect()` avoids duplicating it.
7. **Cold start.** `libfylz_ffi_android.so` loads only in `:decoders` (`REPORT-M2.md:88-90`); every
   archive open pays a process spawn + JNA `Native.register` on first use. Keep the binding alive for
   the browsing session (M3.3) rather than per call.
8. **Dead code to remove with M3.10:** `ExtendedArchiveBrowserService` (no callers) and commons-compress.
