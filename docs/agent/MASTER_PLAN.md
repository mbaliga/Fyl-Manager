# Fylz master plan: the ultimate file manager

Android first, then Ubuntu Touch (phones), then desktop Linux.

For Claude Code, Sonnet with extended thinking ("ultracode"). Prepared 24 Sep 2026. Repo `mbaliga/Fyl-Manager`, working branch `claude/fylz-fotoz-complete-y60pfw` at `5fc9b21` (Phase 0 done, Phase 1 through P1.7 done).

This file supersedes `FYLZ_CLAUDE_CODE_INSTRUCTIONS.md` for everything after P1.7. The non-negotiables and the per-task workflow from that file still apply, and are restated in sections 2 and 3 so this file stands alone.

---

## 0. How to run this plan

1. **Commit this file** to `docs/agent/MASTER_PLAN.md` and read it in full before doing anything.
2. **Work milestone by milestone, in order** (section 5). Inside a milestone, work task by task. Each task has an ID such as `M3.4`.
3. **Stop at every `GATE`.** A gate needs Madhav: a decision, a device test, or approval of a licence. When you reach one:
   - write the milestone report (section 3.5);
   - push the branch;
   - stop.

   He restarts you with "continue from <ID>". Don't start work that depends on an unresolved gate. Independent tasks after a gate are fine only if the gate text says so.
4. **Architecture is pre-decided** (section 4). Don't redesign it. If reality contradicts this file (an API is missing, a licence differs, a library won't build):
   - stop that task;
   - write the finding in `docs/agent/PROGRESS.md` under "Deviations";
   - pick the listed fallback if there is one, otherwise mark the task blocked;
   - move on.
5. **Verify every licence yourself before adding a dependency.** This plan's research marks some licences as unconfirmed. Read the dependency's actual LICENSE file at the version you pin, and record it in `THIRD_PARTY_NOTICES.md`. If it differs from what this plan says in a way that breaks the licence policy (section 2.2), treat it as a gate.
6. **Size key:** S = days, M = 1–3 weeks, L = 1–2 months, XL = a quarter. Sizes assume one agent working with Madhav reviewing.

---

## 1. What "ultimate" means

Fylz aims to be the most capable file manager on any phone. Its promise is **"any drive, any file, nothing lost."** That promise rests on five pillars. Every milestone serves one or more of them.

| Pillar | Promise | Measured by |
|---|---|---|
| **Reach** | Every place files live: internal, SD, USB (any filesystem), cameras and phones, disk images, archives, network shares, cloud providers | Filesystems and protocols supported; drives that open without root |
| **Understanding** | Every file is identified, inspected and searchable; common formats render beautifully | Capability ladder level per format (below) |
| **Control** | Power operations at desktop grade: bulk actions, rule-based rename, tiered search, queues, dual pane, keyboard and mouse | Parity checklist vs Finder, Explorer, Dolphin, Directory Opus, Total Commander, MiXplorer |
| **Trust** | Nothing is silently lost: journals, verification, recycle bin, recovery, version history | Zero data-loss defects; every destructive action reversible or explicitly confirmed |
| **Sovereignty** | Local-first, no accounts, no telemetry, open source, runs without Google | Works fully on de-Googled Android, F-Droid, IzzyOnDroid, Ubuntu Touch |

### Capability ladder (per file format)

| Level | Meaning |
|---|---|
| L0 Identify | Type detected by content (magic bytes), not by name |
| L1 Inspect | Size, dates, full hashes, hex, strings, entropy, embedded metadata |
| L2 Structure | What's inside: archive entries, partitions, SQLite tables, APK manifest, EXIF, ID3, font tables, PDF outline |
| L3 Render | A faithful preview |
| L4 Thumbnail and index | Grid thumbnail; extracted text and metadata in search |
| L5 Edit or convert | Change it in place, or convert it to an open format |

**Targets:**
- Every file reaches L1.
- The top 100 formats reach L3 or L4.
- Archives, images, text and PDF reach L5.

---

## 2. Non-negotiables

### 2.1 Product and safety

1. **Recycle-bin contract** (`docs/product/preview-and-recycle-bin-contract.md`, binding):
   - Delete means recycle.
   - Never fall back silently to a permanent delete.
   - A permanent delete needs confirmation that states the item count and the total size.
   - No automatic purge.
   - **Cleanup features (M9) move items to the recycle bin; they never delete directly.**
2. **Never destroy user data to tidy up.**
   - Delete only what the current operation created and recorded in the journal.
   - No glob deletes.
   - Nothing automatic on legacy folders.
3. **Local-first.**
   - No telemetry, no analytics, no Fylz backend.
   - Network calls happen only when the user acts: remotes, and optional model or pack downloads the user starts.
   - Model packs are verified against the signed catalogue that already exists in `ai/`.
4. **Raw-device writes** (ISO to USB, format drive, partition edits) always require:
   - an explicit typed confirmation naming the device and its capacity;
   - a final summary before the first byte is written.
5. **Honesty.**
   - Never claim device behaviour you didn't test.
   - Every device-dependent claim goes into `docs/agent/DEVICE_CHECKS.md` with steps and an expected result.

### 2.2 Licence policy

The core is Apache-2.0.

| Licence | Allowed where |
|---|---|
| Apache-2.0, MIT, BSD, ISC, zlib, public domain, MPL-2.0 (file-level) | Anywhere |
| LGPL-2.1+ / LGPL-3.0+ | Only as a **separately built shared library** (`.so`), dynamically linked, source and build instructions published, users able to replace it. Never statically linked into the core. Recorded in `THIRD_PARTY_NOTICES.md` |
| GPL-2.0+, GPL-3.0, AGPL | **Never in the core app.** Only in a separate **add-on APK / package** with its own repo, its own licence and source. It talks to Fylz over a DocumentsProvider or an AIDL/D-Bus interface, never by linking |
| unRAR licence | Never. Use libarchive's clean-room RAR/RAR5 reader |
| Proprietary SDKs (Google Play Services, Firebase, ML Kit, Crashlytics) | Never in new code. The existing ML Kit use stays behind the `DocumentScanner`/`OcrEngine` seams until decision D1 |
| Model weights | Only under Apache-2.0 / MIT / BSD (or a licence Madhav approves at a gate). Downloaded on demand, never in the APK |

### 2.3 Engineering

- **Android toolchain frozen:** AGP 8.9.1, Kotlin 2.1.20, Gradle 8.14.3, compileSdk 36, targetSdk 35, minSdk 31. No KSP or kapt; SQLite through `SQLiteOpenHelper`.
- **Rust toolchain:** pin via `rust-toolchain.toml`, stable channel, and update only at a milestone boundary.
- **Android NDK:** pin r28+ (16 KB page alignment by default). Every native library must be 16 KB page-aligned; add a CI check with `zipalign -c -P 16` / `llvm-readelf`.
- **Submodules are read-only:** `hyle-design-system`, `shared-libraries`.
- **Theme:** everything renders inside `FylzTheme`.
- **Rooms:** cell-shell rooms push no back-stack entry.
- **Size:** `ui/FylzV1App.kt` must shrink, not grow. New UI goes in new files.
- **Isolation:** native decoders of untrusted input run in the isolated decoder process (M6.2), never in the UI process.

---

## 3. Workflow

### 3.1 Setup and preflight (every run)

```bash
git fetch && git checkout claude/fylz-fotoz-complete-y60pfw && git pull
git submodule update --init --recursive
```

- JDK 17 (21 worked before; keep using it if 17 is missing and note it).
- Android SDK 36 and build-tools 36.0.0.
- From M2 on:
  - Android NDK r28+ (`sdkmanager "ndk;28.x"`);
  - Rust stable with `rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android`;
  - `cargo install cargo-ndk`.
- From M13 on: Docker plus `clickable` for Ubuntu Touch.
- If a toolchain can't be installed, continue with code and tests, mark tasks `unverified: no build`, and say so at the top of the report.
- Maven Central rate limiting happened before. Retry with backoff; never vendor jars to get round it.

### 3.2 Per task

1. Read the named files in full.
2. Confirm the gap still exists.
3. Write failing tests first.
4. Implement.
5. Run `./gradlew --no-daemon :app:testDebugUnitTest`, plus `cargo test` for core tasks.
6. Commit with the task ID prefix, one commit per task.
7. Update `docs/agent/PROGRESS.md`: ID, status, commit, files, tests, verification level, notes.

### 3.3 Per milestone

- Run the full gate:

  ```bash
  ./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
  ./gradlew --no-daemon :app:lintRelease :app:assembleRelease
  ```

  From M2 on, also `cargo test --workspace` and `cargo clippy -- -D warnings` in `core/`.
- Update `DEVICE_CHECKS.md`.
- Push. Keep the draft PR updated. Never merge to `main`.
- Don't wait on GitHub Actions; local results are the gate.

### 3.4 Tests you must write

- **Format fixtures:** under `core/fixtures/` and `app/src/test/resources/fixtures/`, one valid and one hostile file per format you add. Generate them with scripts in `tools/fixtures/`, and commit the scripts plus small outputs; no file over 2 MB in git. For large cases, generate at test time.
- **Fuzzing:** every native parser entry point gets a `cargo fuzz` target. Run each for at least 60 s per milestone and record the result.
- **Golden tests:** rename-engine outputs, query parser ASTs and NL parser outputs are table-driven with expected results.
- **Performance:** add benchmarks where a task names a budget, and record the numbers.

### 3.5 Milestone report

Write `docs/agent/REPORT-<milestone>.md`:
1. **Status:** one paragraph, verification level first.
2. **Tasks:** a table of ID, status, commit, tests, notes.
3. **Deviations**, and why.
4. **Unverified items**, with their device-check numbers.
5. **Numbers:** benchmarks, and APK size delta per ABI.
6. **Licences added**, each with its verified licence.
7. **Questions for Madhav.**

---

## 4. Target architecture (decided)

### 4.1 Decision A-X: a shared Rust core ("fylz-core") for all new platform-neutral logic

**Why:**
- Ubuntu Touch apps must be QML on Qt 5.15 today (Qt 6 is experimental in UT 24.04-2.0 and meant to stabilise in 26.04). Compose and the JVM are not viable there.
- A Rust core serves all three platforms:
  - Android through **uniffi** (MPL-2.0, production-used by Mozilla);
  - Qt/QML through **cxx-qt** (MIT/Apache, supports Qt 5.15 and 6);
  - Clickable has a first-class Rust builder.
- The heavy native libraries (libarchive, 7-Zip, FatFs, libyal, llama.cpp, sqlite-vec) are C/C++ anyway, and Rust wraps them safely.
- Kotlin Multiplatform/Native was the alternative. It was rejected because:
  - Linux arm64 isn't a supported build host;
  - its exported C API is clumsy for QML;
  - its Linux targets are Tier 2.

**Rules:**
- **Existing Kotlin code stays.** Don't rewrite working Android code into Rust for its own sake.
- **New platform-neutral engines go into `core/`:** archive and disk-image engine, rename engine, query language and NL parser, index schema and ranking, USB mass-storage and filesystem stack, format sniffing, checksum and signature verification, cleanup detectors (dupes, similar images).
- **Android-only glue stays in Kotlin:** SAF, DocumentsProvider, WorkManager, MediaStore, the Compose UI.
- The Kotlin operation engine (journal, recycle, transfers) gets ported to `core/` only in M13, when Ubuntu Touch needs it.
  - Until then its **policies** (pure logic) must stay free of Android imports, so porting is mechanical.
  - Add a lint/test that fails if `operations/*Policy.kt` imports `android.*`.

### 4.2 Repository layout (target)

```
Fyl-Manager/
  app/                     Android app (Kotlin/Compose) — exists
  core/                    Rust workspace
    Cargo.toml
    crates/
      fylz-types/          shared data types (FileMeta, QueryAst, RenamePlan, …)
      fylz-archive/        archives + disk images (libarchive, 7-Zip .so, libyal .so)
      fylz-sniff/          content-type detection
      fylz-rename/         rename rule engine
      fylz-query/          query AST, typed syntax parser, NL rule parser, SQL builder
      fylz-index/          index schema, FTS + vector ranking (rusqlite, sqlite-vec)
      fylz-verify/         hashing, sums files, OpenPGP signature verification (rpgp; see M4.6)
      fylz-usb/            BOT/SCSI, block devices, partitions, FS drivers (M10)
      fylz-clean/          dupes, similar/blurry images, large/old detectors
      fylz-ffi-android/    uniffi bindings → Kotlin (generated into app/src/main/java/…/core)
      fylz-ffi-qt/         cxx-qt bridge → QML (M13+)
    third_party/           vendored C sources as git submodules, each with LICENSE
    fixtures/
    fuzz/
  addons/                  separate packages with their own licences (GPL/AGPL/LGPL-heavy)
  ut/                      Ubuntu Touch app (QML + cxx-qt), Clickable project (M13)
  linux/                   Desktop Linux app (M14)
  tools/                   fixture generators, scripts
  docs/agent/              plan, progress, reports, device checks
```

- **Android build:** a Gradle task `:app:buildCore` runs `cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o app/src/main/jniLibs build --release -p fylz-ffi-android`, then runs uniffi-bindgen for Kotlin. `preBuild` depends on it. Keep it as a plain Gradle `Exec` task; no new Gradle plugins that fight the frozen AGP.
- **APK size:** ship per-ABI splits or an AAB. Report the size delta per ABI in every milestone report. Budget for the core: +12 MB per ABI before add-on packs.

### 4.3 Add-on packs

Heavy or differently licensed capabilities ship as **packs**:

| Pack | Contents | Licence | Delivery |
|---|---|---|---|
| Codec pack | Media3 FFmpeg audio extension, optional libVLC | LGPL | Separate APK exposing a bound service, or Play dynamic feature |
| Image pack | libheif, LibRaw, jxl, libtiff, OpenJPEG | Mixed permissive/LGPL | Separate `.so` set, same APK split or separate APK |
| Disk-image pack | 7-Zip `.so`, libyal `.so` libs, libudfread | LGPL | In core APK as separate `.so` if the size budget allows; otherwise a pack |
| USB write pack | ntfs-3g, lwext4 | GPL | **Separate repo and APK**, exposes a DocumentsProvider |
| Intelligence pack | llama.cpp runtime + a downloadable model; ONNX Runtime + embedding models; Tesseract | MIT/Apache + model licence | Runtime in a pack; models downloaded on request |
| Office pack | OpenDocument.core | MPL-2.0 | Separate `.so` |

- The core app must work fully without any pack, showing honest L1/L2 for formats a missing pack would render.
- A pack is discovered by a signed manifest (reuse `ai/SignedModelCatalog` verification). Never load code from an unverified pack.
- **GATE-A (licence and distribution):** before the first pack ships, Madhav approves the pack list and where each pack is distributed (F-Droid/IzzyOnDroid/Play/GitHub releases). You may build packs before the gate; don't publish them.

### 4.4 Isolated decoder process

Parsing untrusted files with native code runs in `DecoderService`:
- `android:isolatedProcess="true"`, `android:process=":decoders"`.
- It receives `ParcelFileDescriptor`s (read-only) and returns results over AIDL: bitmaps via `SharedMemory`, structure as Parcelables, text as strings.
- Limits: a 256 MB address-space target, a per-call timeout (5 s for thumbnails, 30 s for structure), and kill-and-restart on a timeout or crash.
- A crash marks that file "unsafe to preview"; it never crashes the app.
- On Linux and Ubuntu Touch the equivalent is a subprocess with `seccomp`/`prlimit` (M13/M14).

---

## 5. Milestones

Order: M1 → M2 → then M3–M9 (features, Android) → M10–M12 (hardware, network, workspace) → M13 (Ubuntu Touch) → M14 (Linux).

M3–M9 may interleave once M2 is done, but finish each task before starting another.

---

### M1. Finish the engine (Phase 1 remainder) · size M

These are the remaining tasks from the previous instruction file. They are restated here with the same IDs.

**P1.8 Clipboard and in-app destinations.**
- **Cut** and **Copy** put items on a Fylz clipboard, shown as a chip with the item count.
- **Paste** targets the active tab's current folder.
- **Copy to…** and **Move to…** open an in-app folder chooser over open tabs and storage roots.
- The system picker stays available as **Other location…**.
- This replaces the `OpenDocumentTree` destination flow for everyday use.

**P1.9 Provider hardening.** In `FylzFilesDocumentsProvider.queryRoots`, when `Binder.getCallingUid() != Process.myUid()`, omit `FLAG_SUPPORTS_IS_CHILD`. Other apps can still open single documents, but can't get tree grants over whole volumes. Document it in `docs/ARCHITECTURE.md`.

**P1.10 State and session.**
- Add `ui/BrowserViewModel.kt` with a `SavedStateHandle`, owning tabs, location stacks, selection, sort, view, preview mode, search and clipboard.
- Persist the session (tabs including File-backend roots, stacks, scroll positions) and restore it on launch.
- Remove the interim `configChanges` from P0.5.
- **Device check:** with "Don't keep activities" on, rotate and background the app mid-task.

**P1.11 Listing performance.**
- Paged listing: show the first 500 rows, then stream the rest. Sort once, off the main thread.
- In `FylzFilesDocumentsProvider`, call `setNotificationUri` on child cursors and `notifyChange` after writes.
- Add a `FileObserver` on the visible folder for File-backed tabs.
- Move the remaining main-thread I/O off the main thread:
  - `recycleSelection`;
  - the history and backup overlays;
  - the 1-second `OperationJournal` polling in `FylzAppShell` (replace it with a flow);
  - `LibraryStore.commit()`;
  - `BackupScheduler.reconcile()` in `onCreate`.
- **Budget (device check):** a 100,000-entry folder shows first rows within 300 ms and finishes within 2 s.

**P1.12 One index.** Keep `index/` as the only live implementation, backed by `FylzDatabase`:
- a `files` table (uri, root, parent, name, ext, mime, size, mtime, tags);
- an **FTS4** table over name, path and text (FTS5 only behind a runtime probe).

Migrate the JSON store. Incremental updates:
- `MediaStore.getGeneration` plus `GENERATION_MODIFIED` for MediaStore-indexed volumes;
- a rescan on connect and on demand for others.

Search:
- The main search uses the index when the scope is covered, and the live walk otherwise.
- **Do not** adopt `search-core` yet. M8 replaces the query layer with `fylz-query`; keep the existing `SearchQuery` syntax working until then.

Delete the duplicates once grep confirms nothing references them: `library/LocalIndexService.kt`, `library/LocalFileIndex.kt`, `library/OrganizationEngine.kt`, `organize/*`.

**P1.13 Remove dead duplicates** (grep-verify first):
- delete `network/SmbService.kt`, `data/ArchiveBrowserService.kt`, `data/ScanPdfService.kt`;
- consolidate `PdfToolService` into the DPI-aware `PdfPageTools`.

**P1.14 Docs.** Update `README.md`, `docs/ARCHITECTURE.md` and `CHANGELOG.md` to match reality.

**GATE-M1:** Madhav runs the device checks.
- You may start M2.1–M2.3 before he's done; they don't touch app behaviour.
- **Don't start M3 until GATE-M1 passes.**

---

### M2. fylz-core bootstrap · size M

- **M2.1 Workspace.** Create `core/` with the layout in 4.2, plus:
  - `rust-toolchain.toml`;
  - `deny.toml` for cargo-deny, with a licence allow-list matching section 2.2 (LGPL and GPL crates denied by default; LGPL C libraries are handled as separate `.so` outside cargo);
  - `cargo clippy` clean.
- **M2.2 FFI skeleton.**
  - `fylz-ffi-android` with uniffi (pin the version and record it), exposing `fylz_version()` and an async `sniff(path_fd)` stub.
  - Generate Kotlin bindings into `app/src/main/java/io/github/mbaliga/fylz/core/`.
  - Kotlin wrapper classes in the same package hide the generated names.
- **M2.3 Gradle integration.**
  - An `Exec` task `buildCore` runs cargo-ndk for arm64-v8a, armeabi-v7a and x86_64, into `app/src/main/jniLibs`.
  - Uniffi-bindgen runs in the same task.
  - The debug build uses `--profile dev-android` (opt-level 1) for speed; release uses LTO and `strip = true`.
  - Add a 16 KB alignment check task.
- **M2.4 Decoder process.** Build `DecoderService` (4.4) with a trivial `ping` and `sniff`. Tests cover timeout-kill and crash recovery (a test-only command that aborts).
- **M2.5 Content sniffing (`fylz-sniff`).**
  - Magic-byte detection for the top 300 formats. Seed it from the freedesktop `shared-mime-info` magic database (MIT/GPL dual? **verify**; if not permissive, hand-write rules from format specs).
  - Include ISO9660 (`CD001` at 0x8001/0x8801/0x9001), UDF (`BEA01`/`NSR02`/`NSR03`), MBR (`55AA` at 510), GPT (`EFI PART` at 512), DMG (`koly` trailer), VHD (`conectix`), VHDX (`vhdxfile`), QCOW (`QFI\xfb`), VMDK (`KDMV`), VDI, WIM (`MSWIM`), 7z, RAR4/5, ZIP, zstd, xz, bz2, gz, lz4, MPEG-TS (`0x47` sync), PDF, OOXML/ODF (ZIP plus a mimetype entry), EPUB, APK.
  - Kotlin calls it through the decoder service, and `FileFormatRegistry` uses it as the first signal.
- **M2.6 CI.** Add `cargo test`, `clippy`, `cargo deny` and fuzz smoke runs to the Android workflow. Keep workflows cheap: GitHub Actions is unreliable on this account.

**GATE-M2:** APK size delta and cold-start delta reported. Madhav approves continuing.

---

### M3. Archives and compression · size L

**Goal:** read every mainstream archive format, write the open ones, and work inside archives like folders.

| Format | Read | Write | Engine |
|---|---|---|---|
| ZIP / ZIPX / ZIP64 | yes, including AES and ZipCrypto decryption | yes, including AES-256 (AE-2) and ZipCrypto | libarchive; keep zip4j only until parity, then remove |
| 7z | yes, including AES and encrypted headers | yes, including AES-256 with encrypted headers | 7-Zip `.so` (LGPL); libarchive for unencrypted read |
| RAR4 / RAR5 | yes, including encrypted where libarchive supports it | **never** | libarchive clean-room reader |
| tar, tar.gz/.bz2/.xz/.zst/.lz4/.lzma, tgz, tbz2, txz | yes | yes | libarchive |
| gz, bz2, xz, zst, lz4, lzma, Z (single-file streams) | yes | yes (not Z) | libarchive filters |
| cpio, ar, deb, rpm, xar/pkg, cab, lha/lzh, arj, warc | yes | cpio, xar | libarchive |
| Split and multi-volume (`.zip.001`, `.7z.001`, `.part1.rar`, `.z01`) | yes | zip and 7z | libarchive / 7-Zip |
| APK, JAR, AAR, IPA, XAPK, APKM, CBZ, CBR, CB7, EPUB, OOXML, ODF | yes, as containers | — | same engines, flagged "container" |

**Tasks:**

- **M3.1 `fylz-archive` over libarchive.**
  - Vendor libarchive (BSD) as a git submodule under `core/third_party/libarchive`, built with CMake through the `cc`/`cmake` crate.
  - Include zlib, bzip2, xz/liblzma, zstd and lz4 (all permissive; verify).
  - Expose streaming `open(fd)`, `entries()`, `read_entry(path)` and `extract(selection, dest_fd_provider)`.
  - Enforce the existing `ArchiveExtractionPolicy` limits: move the policy logic into Rust and delete the Kotlin copy once parity tests pass.
- **M3.2 Random access, no whole-archive staging.**
  - Kotlin passes a seekable `ParcelFileDescriptor` into the decoder process.
  - ZIP, 7z and ISO are read with seeks.
  - Only non-seekable remote streams stage to cache, with a space check.
- **M3.3 Archive browsing as folders.**
  - Opening an archive pushes a virtual location in the tab: breadcrumb `Downloads › photos.zip › 2024`.
  - Preview, copy-out, share and drag-out work on entries.
  - **Nested archives** (an archive inside an archive) open the same way, with bounded recursion depth 4 and extraction to cache.
- **M3.4 Selective extract.**
  - Actions: Extract here; Extract to `<name>/`; Extract to…; Extract selected entries.
  - Every extract runs through the transfer queue (`TransferWorker`), so it gets progress, cancel, staging, verification and conflict handling.
- **M3.5 Create.** A **Compress…** sheet with:
  - format: zip, 7z, tar.gz, tar.xz, tar.zst;
  - level;
  - password (M5);
  - split size (off, 100 MB, 700 MB, 4 GB for FAT32, custom);
  - "store paths relative to selection";
  - folders included recursively (today folders are rejected).

  It runs in the queue.
- **M3.6 Edit in place.**
  - For ZIP and 7z: add, delete and rename entries.
  - Implemented as rewrite-to-staging then atomic replace (never in-place mutation).
  - The old archive goes to the recycle bin.
- **M3.7 Encodings.**
  - Detect and choose the filename charset for legacy ZIPs (CP437, CP866, GBK, Shift-JIS, EUC-KR): auto-detect, with a manual override in the archive header bar.
- **M3.8 Integrity.** A **Test archive** action verifies CRCs without extracting.
- **M3.9 Password prompt.**
  - Shared for all formats: remember for the session only (opt-in).
  - Passwords are held in `CharArray` and wiped after use; never logged or persisted.
- **M3.10 Remove zip4j and `ExtendedArchiveBrowserService`** once the parity tests for create, extract and AES pass.

**Acceptance:**
- A fixture corpus of at least one of each format opens.
- A 5 GB 7z extracts through the queue with verification.
- Hostile fixtures (zip-slip, bombs, symlink escapes, oversized headers) are refused.
- Fuzz targets for zip, 7z, rar, tar and iso run clean.

---

### M4. ISO and disk images · size L

**Goal:** open any disk image like a drive, create ISOs, verify downloads, and (after M10) write images to USB.

**Tasks:**

- **M4.1 Block-device abstraction** in `fylz-usb` (shared with M10):

  ```rust
  trait BlockDevice {
      fn sector_size(&self) -> u32;
      fn len(&self) -> u64;
      fn read_at(&self, off: u64, buf: &mut [u8]) -> Result<()>;
      fn write_at(&self, …); // may be unsupported
  }
  ```

  Implementations:
  - `FileBlockDevice` over an fd;
  - `SubRange` for partitions;
  - later `UsbBlockDevice`.
- **M4.2 Partition tables.** Parse MBR (including extended/logical), GPT (validate header CRC and backup) and the Apple Partition Map in Rust. Hybrid ISOs (isohybrid MBR/GPT plus ISO9660) show both views.
- **M4.3 Filesystem readers inside images:**
  - **ISO9660** with Joliet, Rock Ridge and El Torito (show boot images as entries) via libarchive.
  - **UDF** 1.02–2.60 via libudfread (LGPL `.so`). Fallback: the 7-Zip `.so`.
  - **FAT12/16/32 and exFAT** via FatFs (M10.5; do it here first, read-only).
  - **NTFS, HFS+, APFS, ext2/3/4, SquashFS** read via the 7-Zip `.so` (LGPL) for now. M10 adds the dedicated libyal readers where 7-Zip falls short.
- **M4.4 Container formats:**
  - **DMG** (UDIF: zlib, bzip2, LZFSE, ADC) via libmodi (LGPL) or 7-Zip;
  - **VHD/VHDX** via libvhdi or 7-Zip;
  - **VMDK** via libvmdk or 7-Zip;
  - **QCOW2** via libqcow or 7-Zip;
  - **VDI** and **WIM** via 7-Zip;
  - **CUE/BIN, NRG, MDF/MDS** parsed in Rust: map 2352-byte raw sectors to 2048-byte user data, then read as ISO; show audio tracks as `.wav` entries;
  - **CHD** via libchdr (BSD; verify).

  Encrypted DMG, VHDX with BitLocker and encrypted APFS show L1 with "encrypted" and nothing more.
- **M4.5 UX.**
  - Opening an image shows a **Disk image** header (format, size, partitions, filesystems, bootable flag, volume label) and its partitions as child folders.
  - Everything is read-only: copy-out, preview, search inside.
  - Add a "Mount as location" action that pins the image as a root in the left room until closed.
- **M4.6 Checksums and signatures (`fylz-verify`).**
  - **Hashes:** MD5, SHA-1, SHA-256, SHA-512, BLAKE3 and CRC32, streamed, running in the queue for big files.
  - **Sums files:** parse GNU style (`hash␠␠name`) and BSD style (`SHA256 (name) = hash`), single-hash files and pasted hashes. When a folder has an image plus a `SHA256SUMS` / `*.sha256` / `CHECKSUM` file, offer **Verify** automatically.
  - **Signatures:** verify detached and clear-signed OpenPGP signatures on the sums file.
    - Library choice: **PGPainless** (Apache-2.0, on Bouncy Castle) on the Kotlin side for Android; on the Rust side for UT and Linux, rpgp (`pgp` crate, MIT/Apache; verify).
    - Show "Signed by <fingerprint> (<user ID>)". Ship a small pinned set of distro signing-key fingerprints (Ubuntu, Debian, Fedora, Arch, Raspberry Pi OS) in a signed JSON inside the app.
    - Never auto-fetch keys without asking.
- **M4.7 Create ISO.**
  - **Make ISO from folder** via the libarchive ISO9660 writer: Joliet and Rock Ridge on, ISO level 3, volume ID field, and optional El Torito boot image (no-emulation) chosen from the folder.
  - Hybrid (USB-bootable) ISOs are out of scope in the core; they are xorriso territory (GPL add-on, GATE-A).
- **M4.8 Write image to USB.** This depends on **M10.2** (the USB mass-storage driver). The task lives here but is scheduled after M10.2.
  - Raw write of ISO/IMG (optionally `.img.xz`/`.img.gz`/`.zst`, decompressed on the fly) to a whole USB drive over the USB Host API.
  - Sequence:
    1. Pre-checks: size fits, the image is hybrid or raw (warn if it's a pure ISO9660 that won't boot), Windows-ISO detection with an explanation that Windows ISOs need a different tool.
    2. Typed confirmation (2.1.4).
    3. Write through the foreground queue with the screen kept on.
    4. Read back and hash-verify.
    5. Report.
  - EtchDroid (GPL-3) is reference only. **Do not copy its code.** Write the implementation from the USB Mass Storage BOT and SCSI specifications.

**Acceptance:**
- The fixture corpus opens: an Ubuntu-style hybrid ISO (generate a small one), a UDF image, FAT/exFAT/NTFS/ext4 IMGs, DMG (zlib), VHD, VMDK, QCOW2, CUE/BIN.
- A created ISO mounts on Linux (`mount -o loop`) with Joliet names intact (automated in a Linux CI step).

**GATE-M4:** the licences of libudfread, libmodi, libvhdi, libvmdk, libqcow and the 7-Zip build are verified; APK size reported.

---

### M5. Protection and encryption · size M

**Goal:** a single **Protect…** action that makes password-protected output other systems can open, with honest compatibility labels.

| Option in the Protect sheet | Output | Opens on | Label to show |
|---|---|---|---|
| **Secure archive** (default) | `.7z`, AES-256, encrypted file names | 7-Zip (Windows), Keka / The Unarchiver (Mac), p7zip/7zz/file-roller (Linux), Fylz | "Strong. Needs a free app like 7-Zip on Windows and Mac." |
| **Secure ZIP** | `.zip`, AES-256 (AE-2) | 7-Zip, WinRAR, WinZip, Keka, The Unarchiver, most Linux tools; **not** the built-in Windows/macOS extractors | "Strong. File names stay visible. Built-in Windows and Mac unzip can't open it." |
| **Compatible ZIP** | `.zip`, ZipCrypto | Everything, including built-in Windows and macOS | "Weak: fine against casual snooping, not against a determined attacker." Show this warning every time |
| **Protected PDF** (PDF inputs only) | PDF with AES-256 (R6), open password, optional permissions | Every PDF reader | "Strong." Use PdfBox-Android (Apache) |
| **age file** (advanced) | `.age`, passphrase or recipient key | age, rage, Fylz | "For people who use age." Use kage (Apache/MIT; verify) |
| **OpenPGP** (advanced) | `.gpg` / `.asc` | GnuPG, Kleopatra, GPG Suite | Use PGPainless |

**Tasks:**

- **M5.1** The Protect sheet (UI) and a shared password field:
  - strength meter (zxcvbn-style, a permissive port; verify);
  - confirm field;
  - show/hide;
  - optional "generate passphrase" (diceware, bundled word list; verify its licence).
- **M5.2** 7z AES-256 with header encryption via the 7-Zip `.so`, from M3.
- **M5.3** ZIP AES-256 (AE-2) and ZipCrypto via libarchive (`zip:encryption=aes256|zipcrypt`).
- **M5.4** PDF protect and unlock via PdfBox-Android. **Unlock** means removing the password when the user knows it.
- **M5.5** age encrypt and decrypt, with passphrase and X25519 recipients; keys held in the Android Keystore-wrapped vault that already exists (`ai/ApiKeyVault` pattern).
- **M5.6** OpenPGP encrypt and decrypt, symmetric and public-key.
- **M5.7 Unlock anything.**
  - Opening an encrypted file of any supported kind prompts for the password and then behaves like the unencrypted version: browse, preview, extract.
  - Wrong passwords fail cleanly, with no partial output.
- **M5.8 Encrypted vault folders.** **GATE-D5** first: Madhav chooses between a clean-room Cryptomator vault-format-8 implementation in the core, and the AGPL Cryptomator library as an add-on. Don't start before the gate.

**Acceptance:** round-trip tests for each format. Interop tests in a Linux CI step (7z, unzip, qpdf, age and gpg command-line tools decrypt what Fylz produced, and Fylz decrypts what they produced).

---

### M6. Format platform, previews and Quick Look · size XL

**Goal:** every file L1, the top 100 formats L3/L4, and previews that feel like macOS Quick Look plus Windows Explorer's preview pane.

- **M6.1 `FormatHandler`** interface in Kotlin (Android). The Rust side mirrors it for UT and Linux later.

  ```kotlin
  interface FormatHandler {
      val id: String
      val levels: Set<Level>                   // INSPECT, STRUCTURE, RENDER, THUMBNAIL, TEXT, EDIT
      fun sniff(name: String, mime: String, header: ByteArray): Float
      suspend fun inspect(ctx: HandlerContext, file: FileRef): Inspection
      suspend fun structure(ctx: HandlerContext, file: FileRef): StructureTree?
      @Composable fun Render(file: FileRef, state: PreviewState, modifier: Modifier)
      suspend fun thumbnail(ctx: HandlerContext, file: FileRef, px: Int): Bitmap?
      suspend fun extractText(ctx: HandlerContext, file: FileRef, maxChars: Int): String?
  }
  ```

  - `FileFormatRegistry` becomes an ordered handler list.
  - The preview UI shows the level reached and why ("Needs the image pack").
- **M6.2** Native handlers run through `DecoderService` (4.4).
- **M6.3 Handlers, in order:**
  1. Text and code: a tree-sitter-based highlighter (MIT) for the top 40 languages, large files via a memory-mapped virtual view, JSON/YAML/XML tree view, CSV/TSV grid, diff view, Markdown (existing), Jupyter notebooks.
  2. Images: platform codecs, plus the image pack (JPEG XL, AVIF/HEIC extras, TIFF, JPEG 2000, PSD composite, TGA, HDR/EXR, camera RAW via LibRaw including embedded-JPEG fast path, ICO/CUR). Add an EXIF/XMP/IPTC/ICC panel and a histogram.
  3. PDF: `androidx.pdf` viewer (search, selection, forms, annotations), thumbnails, outline, password handling; PdfBox-Android for lossless page tools (replaces rasterising).
  4. Audio and video: Media3 plus the codec pack; stream info (codecs, bitrate, tracks), chapters, subtitles, audio tags (read and edit ID3/Vorbis/MP4 via a permissive tagging lib; verify), waveform thumbnail.
  5. Archives and disk images: from M3 and M4.
  6. E-books: Readium kotlin-toolkit (EPUB, PDF, CBZ; CBR/CB7 via the archive engine), MOBI/AZW3 via libmobi (LGPL `.so`), FB2 parser.
  7. Office: OpenDocument.core pack; embedded preview images from OOXML (`docProps/thumbnail.*`), ODF (`Thumbnails/thumbnail.png`) and iWork (`preview.jpg` / `QuickLook/Thumbnail.png`) as an immediate L4 without the pack.
  8. APK/AAB/XAPK: icon, label, package, versions, permissions, signatures (apksig), install via `PackageInstaller` sessions (split APKs).
  9. Data: SQLite browser (read-only copy), Parquet/Avro/Arrow schema and first rows, JSON Lines.
  10. 3D and printing: SceneView/Filament for glTF, GLB, OBJ, STL, 3MF and PLY; G-code toolpath preview; plate thumbnails from sliced 3MF files.
  11. Fonts: WOFF/WOFF2 decompression, glyph grid, name table, variable-axis sliders.
  12. Mail and contacts: EML, MBOX, MSG, vCard, iCal.
  13. Certificates and keys: PEM, DER, PKCS#12, SSH keys, with fingerprints.
  14. Geo: GeoJSON, GPX and KML on an offline map (MapLibre Native, BSD) using user-supplied or blank tiles; EXIF GPS on the map.
- **M6.4 Thumbnails everywhere.**
  - A disk cache keyed by `(documentId or path, size, mtime, px)`, LRU, 256 MB default (setting).
  - Memory cache is Coil's default.
  - Decode at cell size, cancel on scroll-off, prefetch 2 rows ahead.
  - Use `ContentResolver.loadThumbnail` first, then a provider thumbnail (`FLAG_SUPPORTS_THUMBNAIL`), then the handler.
  - The failure cache stops retry storms.
- **M6.5 Quick Look.**
  - **Space** (hardware keyboard), or long-press on the thumbnail → full-screen preview overlay.
  - Arrow keys and swipes move between files in the current order.
  - Space or Esc closes it.
  - Actions: open with, share, protect, rename, recycle.
  - Works from search results and inside archives.
- **M6.6 Hover previews** (mouse, trackpad, DeX, ChromeOS, desktop mode):
  - Use Compose `pointerInput` with `PointerEventType.Enter/Move/Exit`.
  - After a **500 ms** hover, show a floating preview card near the pointer.
  - **Video scrubbing:** pre-extract a sprite strip of 10 frames (Coil `VideoFrameDecoder` / `MediaMetadataRetriever` in the decoder process), cached. Pointer x maps to a frame.
  - **Audio:** hover shows the waveform; clicking the play glyph plays it.
  - Documents and PDFs: first page. Folders: the first 4 thumbnails and counts.
  - Tooltip for truncated names via Material 3 `TooltipBox`.
- **M6.7 Autoplay.** Setting **Autoplay in grid**: Off / GIF and animated images only (default) / Also video (muted).
  - Only for visible cells.
  - At most 4 concurrent animations and 1 video.
  - Paused under battery saver and when the app isn't focused.
- **M6.8 Preview pane modes.** Setting **Preview pane**: Auto (current behaviour) / **Always open** / Off.
  - Always open, wide screens: a docked right pane.
  - Always open, phones in portrait: a bottom pane taking 40% of the height, resizable by dragging its handle (30–70%).
  - Always open, phones in landscape: a right pane.
  - The pane follows the focused item. Preview state persists per tab.
- **M6.9 Evidence room** (bottom), per the August 2026 decision, with tabs:
  - **Details:** path, provider, sizes, dates, owner, permissions, capabilities.
  - **Attributes:** EXIF, ID3, PDF info, archive info, hashes on demand.
  - **Versions:** file history.
  - **Activity:** operations touching this file from the journal.
  - **Recovery:** recycle and restore.

**Acceptance:**
- The fixture corpus: every file shows at least L1, and the top 100 list (`docs/agent/TOP_FORMATS.md`, which you create from the registry) is at L3 or better where its pack is installed.
- Scrolling a 5,000-image grid holds 60 fps on a mid-range device (device check).
- Quick Look opens in under 150 ms for images already thumbnailed.

---

### M7. Selection, bulk actions and rule-based rename · size L

- **M7.1 Selection model:**
  - tap and long-press (existing);
  - **range select** (shift-click; on touch, long-press one item then long-press another with "select range");
  - **rubber-band** drag with a mouse;
  - **Select all**, **Invert**, **Select none**;
  - **Select by…** (pattern, type, date, size, tag);
  - selection survives scrolling, sorting and filtering;
  - a count and total size in the action bar.
- **M7.2 Bulk actions** on any selection, all through the queue:
  - copy, move, cut/paste, recycle, rename, compress, protect, extract (multiple archives), share, tag, favourite, set dates (touch), convert (images: format and resize; M9);
  - "copy path(s)";
  - create a folder from the selection ("New folder with selection");
  - checksum;
  - compare (two files: side by side; two folders: diff).
- **M7.3 Rename engine (`fylz-rename`)**: a pure Rust, deterministic, golden-tested pipeline of rules applied in order.

  | Rule | Parameters |
  |---|---|
  | Replace | find, replace-with, regex on/off, case-sensitive, all/first, scope (name / extension / both) |
  | Insert | text, position (start / end / before extension / index N / before or after regex match) |
  | Remove | characters N–M, first/last N, regex, digits/symbols/whitespace |
  | Template | full new name from tokens (below) |
  | Case | lower, UPPER, Title, Sentence, camelCase, snake_case, kebab-case |
  | Number | add counter at a position with start, step, padding, reverse, per-folder reset |
  | Extension | set, lowercase, uppercase, remove |
  | Clean | trim, collapse spaces, Unicode NFC, strip diacritics, make safe for target FS (FAT/exFAT/NTFS rules from P1.5) |

  **Template tokens** (these also accept Figma syntax):
  - Current name, compatible with Figma `$&`: `{name}`, `{ext}`, `{fullname}`.
  - Folder: `{parent}`, `{path}`.
  - Counters:
    - `{n}` ascending, compatible with Figma `$n`;
    - `{nnn}` zero-padded to 3, compatible with Figma `$nnn`;
    - `{N}` / `{NNN}` descending, compatible with Figma `$N` / `$NNN`;
    - `{n:start=10,step=5,pad=4}`;
    - `{letter}` gives a, b, … z, aa.
  - Regex capture groups: `{1}`…`{9}`, compatible with `$1`…`$9`.
  - Dates, each with any format string:
    - `{created:yyyy-MM-dd}`
    - `{modified:HHmmss}`
    - `{taken:yyyyMMdd_HHmmss}` (EXIF)
    - `{now:…}`
  - Metadata:
    - images: `{exif.make}`, `{exif.model}`, `{exif.lens}`, `{exif.iso}`, `{w}`, `{h}`;
    - audio: `{id3.artist}`, `{id3.album}`, `{id3.track:00}`, `{id3.title}`;
    - video: `{video.duration:mm-ss}`;
    - documents: `{pdf.title}`, `{doc.author}`.
  - Content and random: `{hash:sha1:8}`, `{random:alnum:6}`, `{uuid}`.
  - **Figma compatibility:** in the Template field, a leading `$&`, `$n`, `$nn…`, `$N…` or `$1` is accepted and shown converted to the brace form.

  **UI:**
  - The Rename sheet has stacked rule cards (add, reorder, disable).
  - A live preview table (old → new) highlights conflicts, invalid names and unchanged items.
  - Numbering follows the current sort; the sort can be overridden in the sheet.
  - Presets can be saved.
  - Folders and files have toggles.
  - "Apply to subfolders" is off by default.
- **M7.4 Safety.** Execution is atomic per batch:
  - the two-phase temp-name approach from P0.4;
  - full rollback on failure;
  - one **Undo** entry in the journal.

  Conflicts follow the P1.6 per-item policy.
- **M7.5 Rename with AI** (optional; needs the intelligence pack, M8.6). The user describes a rule ("date taken then camera model"). The model returns **a rule pipeline, not names**, via grammar-constrained JSON. The user sees and edits the rules before applying them. Nothing runs without the preview.

**Acceptance:**
- Golden tests: at least 80 cases covering every rule and token, and Figma-syntax parity cases.
- Renaming 10,000 files completes, and undoes, through the queue (benchmark).

---

### M8. Search: three tiers plus semantic · size XL

**One query model:** `fylz-query` defines the AST.

```
Query := And(Query…) | Or(Query…) | Not(Query)
       | Name(match: Contains|Starts|Glob|Regex, text, case)
       | Ext(set) | Kind(set: image|video|audio|document|archive|apk|code|font|model3d|diskimage|folder…)
       | Size(op, bytes) | Date(field: modified|created|taken|opened|added, range)
       | Path(under|not_under) | Tag(set) | Favourite | Content(text, mode: words|phrase|regex)
       | Meta(key, op, value)  // exif.model = "X-T4", id3.artist ~ "…", pdf.pages > 10, w >= 3000
       | Dupe | Location(root set) | Semantic(text)  // M8.7
```

Every tier produces this AST. The planner runs it against the index (FTS4/FTS5 + columns), then live-walks roots the index doesn't cover.

- **M8.1 Index schema v2.** Add `opened_at` (Fylz's own open log), tags, a metadata key/value table (EXIF, ID3, PDF info), extracted text (from M6 handlers, bounded to 1 MB per file), and duplicate hashes (M9). Background indexing runs while charging by default, with visible scope, pause, rebuild and delete controls in Settings.
- **M8.2 Typed syntax** (power users), an Everything-style superset:
  - space = AND; `|` or `OR` = OR; `-` or `!` or `NOT` = NOT; `( )` grouping; `"phrase"`;
  - `name:`, `ext:pdf;docx`, `kind:image`, `size:>100mb`, `size:10mb..1gb`;
  - `modified:today|yesterday|thisweek|lastweek|2026-09|2026-01-01..2026-03-31|<7d`, and likewise `created:` and `taken:`;
  - `in:"Downloads"`, `path:`, `tag:work`, `is:fav`, `content:"invoice"`, `regex:`, `dupe:`, `exif.model:"X-T4"`, `w:>=3000`.

  Keep backwards compatibility with today's `ext:`, `type:` (alias of `kind:`), `size:` and `content:`.
- **M8.3 Tier A: instant search with suggestions**, like macOS Finder:
  - Results update as you type (debounced 60 ms, index only; live walk after 400 ms idle).
  - Under the field, **suggestion rows** turn the typed text into tokens:
    - "Name contains 'inv'";
    - "Content contains 'inv'";
    - "Kind: Documents" (when the text matches a kind word);
    - "Tag: invoices";
    - "Modified: last week" (when the text parses as a date);
    - recent searches;
    - matching folders ("Go to Downloads/Invoices").
  - Choosing a suggestion creates a **chip** in the field. Each chip can be tapped to switch the operator (contains / starts / is), negate it (**exclude**) or remove it.
  - Scope chips: This folder / This location / Everywhere.
  - Ranking: exact name > prefix > word-start > fuzzy (Smith-Waterman or fzf-style) > content, then boosted by recent opens.
  - Keyboard: ↑↓ to move, Enter to open, Ctrl+Enter to reveal in folder, Space for Quick Look.
- **M8.4 Tier B: Advanced search** (a sheet or panel):
  - All conditions up front as rows: [field] [operator] [value] (+ add row, − remove, a group toggle for "All / Any of these").
  - Fields: name, kind, extension, size, dates, location, tags, content, metadata (with key picker), duplicates.
  - Live result count.
  - **Save as smart folder**: saved queries appear in the left room and as tabs, re-run live.
  - A two-way sync between Tier B rows and the Tier A chip field (same AST).
- **M8.5 Tier C: natural language, deterministic first.** A rule-based NL parser in `fylz-query`:
  - Lexicons: kinds and synonyms ("photos", "pics", "screenshots", "songs", "PDFs", "zips", "videos from my camera").
  - Size phrases: "bigger than 1 gig", "over 500MB", "tiny".
  - Date phrases, ported rules in the style of chrono (not a library dependency): "last week", "in March", "since Monday", "between Jan and March 2025", "2 days ago", "this year".
  - Places: "in Downloads", "on the USB drive", "on SD".
  - Negation: "not", "except", "without", "excluding".
  - Metadata: "taken with the Fuji", "by Taylor Swift", "with more than 10 pages".
  - Content: "that mention invoice", "containing 'total due'".
  - Output: AST plus a list of unparsed spans. Always show the parsed result as chips ("I understood: Kind Photos · Taken March 2026 · Location Downloads") so the user can fix it.
  - Golden tests: at least 150 phrases, English first. The structure allows more languages (lexicon files per locale); add Hindi as the second locale when Madhav approves at GATE-M8.
- **M8.6 Tier C+: optional on-device model** (intelligence pack). This covers phrases the rule parser leaves unresolved.
  - Runtime: **llama.cpp** (MIT) in the pack, with a **GBNF grammar generated from the AST JSON schema**, so the output is always a valid AST.
  - Model: a small Apache-licensed instruct model. Candidates (verify each licence and the Android memory footprint at GATE-M8):
    - Qwen2.5 / Qwen3 0.5–1.7B (Apache-2.0);
    - Gemma 4 E2B (reported Apache-2.0 since April 2026).
  - Downloaded on request, checksum and signature verified, deletable. No network otherwise.
  - Merge: rule-parser output wins on conflicts; the model fills only unparsed spans. Show the chips before searching.
  - **Alternative runtime:** LiteRT-LM (Kotlin API, reported Apache-2.0, no Play Services required; verify) is allowed on Android if it beats llama.cpp in a benchmark. llama.cpp stays the cross-platform default.
- **M8.7 Semantic search** (intelligence pack, opt-in per location):
  - Text embeddings: all-MiniLM-L6-v2 (Apache-2.0) or bge-small (MIT), via ONNX Runtime Android (MIT).
  - Image-text embeddings: SigLIP-family (Apache-2.0; verify), so "sunset at the beach" finds photos.
  - Vectors stored with **sqlite-vec** (Apache/MIT; pin, it's pre-1.0).
  - Hybrid ranking with FTS via reciprocal rank fusion.
  - A `Semantic(text)` AST node, triggered by a "Similar meaning" chip or when NL detects a descriptive query.
  - Indexing only while charging and idle, with visible progress and a per-location toggle.
- **M8.8 OCR for search** (intelligence pack): Tesseract (Apache-2.0) through Tesseract4Android (verify), or PaddleOCR via ONNX, over images and scanned PDFs. The text goes into the index. It also replaces ML Kit for the D1 open build.
- **M8.9 Search results UX:**
  - The same list, grid and details views.
  - Group by folder, kind or date (toggle).
  - Every result shows its location breadcrumb.
  - Bulk actions work on results.
  - "Search inside archives and disk images" (opt-in, uses M3/M4).

**Acceptance:**
- Tier A shows the first results within 100 ms on a 200k-file index (device check).
- The NL golden set passes at least 95% rule-only.
- Tier B and Tier A round-trip an AST without loss.

**GATE-M8:** model choice and licences; Hindi locale; index default scope.

---

### M9. Organise, recents and cleanup · size L

- **M9.1 Home: category hub.** The storage home surface gains, above the locations:
  - **Recents**: Fylz-opened plus MediaStore `DATE_ADDED`/`DATE_MODIFIED`, last 30 days, grouped Today / Yesterday / This week / Earlier;
  - **Categories:** Images, Videos, Audio, Documents, Archives, APKs, Downloads, Screenshots, Large files, Disk images.

  Each opens as a virtual location powered by the index (with MediaStore as a fallback), with the full toolbar (sort, view, bulk actions).
- **M9.2 Recents details.**
  - Fylz keeps its own `opened_at` log in the database: local only, clearable, with a setting to turn it off.
  - Android has no system-wide recent-files API, so recents are Fylz's log merged with MediaStore date queries.
  - Pin and unpin.
  - "Hide from recents" per item and per folder.
- **M9.3 Organise by type.** In any folder:
  - Group by: none / kind / extension / date (day, month, year) / size band / first letter.
  - Headers are sticky and collapsible.
  - The edge scrubber follows the grouping.
- **M9.4 Storage analyser.**
  - Per volume and per folder: a **sunburst** (default) and a **treemap** (toggle), built from a background scan with cached results and incremental refresh.
  - Tap to drill down; long-press to select.
  - A list view with sizes and percentages.
  - A "Largest 100 files" view.
  - A **staging tray** in the DaisyDisk pattern: drag items into the tray, review the total, then "Move to Recycle Bin".
- **M9.5 Cleanup cards** (a Clean room or a section of the Tools room), each a list with previews, "select all", and the staging tray:

  | Card | Detector |
  |---|---|
  | Duplicates | size → first and last 4 KB → full BLAKE3; keeps the oldest or the one in the "preferred folder"; across locations |
  | Similar photos | dHash/pHash with Hamming distance ≤ 8, grouped, best picked by sharpness and resolution |
  | Blurry photos | Laplacian-variance threshold (tunable) |
  | Large files | ≥ 100 MB (configurable) |
  | Old downloads | Downloads items not opened in 90 days |
  | Installed APKs | APK files whose package is already installed at the same or a newer version |
  | Screenshots | older than 30 days |
  | Messaging media | WhatsApp, Telegram and Signal media folders, by age and size |
  | Empty folders | recursive, excluding the protected/system list |
  | Thumbnails caches | `.thumbnails` folders |
  | Leftovers | folders named after packages no longer installed (heuristic; always shows a reason) |
  | App caches | **Shizuku only**: `pm clear --cache-only <pkg>` per app with sizes from `StorageStatsManager` (needs usage access). Without Shizuku, a single "Ask Android to clear all app caches" button using `StorageManager.ACTION_CLEAR_APP_CACHE`, with the system warning explained |

  Every card moves items to the **recycle bin**, never deletes. It shows "Reclaim X GB (after emptying the bin)".
- **M9.6 Media tools** (bulk):
  - image convert and resize (JPEG/WebP/AVIF/PNG, quality, max edge; keep or strip EXIF; strip GPS);
  - video frame export;
  - audio tag edit.

  All run in the queue and write new files.
- **M9.7 Tags and colours.**
  - Finder-style colour tags plus free tags.
  - Shown in rows; searchable; bulk-applied.
  - Stored in the database, keyed by identity (provider document ID plus relink heuristics).
  - Optional export to a portable sidecar.
- **M9.8 Favourites and quick access**: pinned folders in the left room; home-screen shortcuts (`requestPinShortcut`); a Quick Settings tile ("Paste / Recent"); a widget (Recents).

**Acceptance:**
- Duplicate detection on 100k files finishes within 3 minutes on a device (device check).
- No cleanup path can permanently delete (tests assert every cleanup path goes through `RecycleBinService`).

---

### M10. USB and external hardware · size XL

- **M10.1 Tier 1: mounted drives first-class.**
  - `StorageManager.registerStorageVolumeCallback` for live connect/disconnect.
  - An on-connect sheet (Open / Import photos / Back up / Eject).
  - Per-drive memory by volume UUID (name, last folder, backup plan: "when this drive connects, back up DCIM" reusing the backup engine).
  - Yank safety: jobs pause with "reconnect to resume" and resume from byte offsets.
  - Filesystem type shown (from P1.5's `VolumeInfo`).
  - **Safe removal:** fsync, check no jobs are open, then deep-link to the system eject. With Shizuku, `sm unmount` gives one-tap eject (device check).
  - Verify-by-default to removable media.
  - A fake-capacity test.
  - An Android 16 Advanced Protection USB-lock explanation.
- **M10.2 Tier 2: USB mass-storage driver** (`fylz-usb`, Rust, plus a Kotlin transport):
  - The Kotlin side does the USB Host API: permission, claiming the interface, and handing `UsbDeviceConnection.getFileDescriptor()` to Rust.
  - Rust does Bulk-Only Transport and SCSI: INQUIRY, TEST UNIT READY, REQUEST SENSE, READ CAPACITY(10/16), READ(10/16), WRITE(10/16), SYNCHRONIZE CACHE, and multi-LUN (GET MAX LUN).
  - Transfer path: bulk transfers via `UsbDeviceConnection.bulkTransfer` called from Kotlin through a callback, or `usbdevfs` ioctls on the fd from Rust (**prefer the ioctls; fall back to the Kotlin callback if SELinux blocks them**).
  - A block cache with write-back and flush-on-detach.
  - Manifest:
    - `uses-feature android.hardware.usb.host required=false`;
    - a `USB_DEVICE_ATTACHED` filter with `device_filter.xml` (class 8, class 6);
    - the Android 14+ mutable PendingIntent with an explicit package.
  - An explicit "Fylz manages this drive" exclusive mode.
  - Written from the specifications. libaums (Apache-2.0) may be read as a reference; EtchDroid (GPL) must not be copied.
- **M10.3 Filesystems on the driver:**
  - FAT12/16/32 and exFAT read/write via **FatFs** (BSD-style; verify) in the core;
  - NTFS, HFS+ and APFS read via libyal `libfsntfs`/`libfshfs`/`libfsapfs` (LGPL `.so`);
  - ext2/3/4 read via `lwext4` read-only paths **only if** its licence is confirmed permissive for the files used, otherwise via the USB write pack;
  - NTFS and ext4 **write** via the **USB write pack** (ntfs-3g, lwext4; GPL, separate repo and APK, exposing a DocumentsProvider). **GATE-A.**
- **M10.4 `UsbDocumentsProvider`** exposes driver-mounted filesystems as roots, so Fylz and other apps see them.
- **M10.5 Format drive:**
  - FAT32 (with a larger-than-32 GB option) or exFAT via FatFs `f_mkfs`;
  - MBR or GPT;
  - typed confirmation.
- **M10.6 Cameras and phones (MTP/PTP)**, via `android.mtp.MtpDevice`:
  - browse storages;
  - thumbnails via `getThumbnail`;
  - an **Import** flow: only new shots (tracked per device serial), RAW+JPEG pairing, rename via the M7 engine (default template `{taken:yyyyMMdd_HHmmss}_{exif.model}`), dedupe against the destination, verify, and an optional "delete from camera after verified import" (typed confirmation).
- **M10.7 Write image to USB.** This is M4.8, now possible.
- **M10.8 Optical drives:** SCSI-MMC READ(10) and ISO9660/UDF through M4. Low priority; do it last.
- **M10.9 Test bench list** in `DEVICE_CHECKS.md`:
  - FAT32, exFAT, NTFS, ext4, HFS+ and APFS sticks;
  - a multi-slot card reader;
  - a camera over PTP;
  - a UASP SSD;
  - a fake-capacity stick;
  - a hub with Ethernet.

**GATE-M10:** exFAT patent position (Madhav takes legal advice before shipping the exFAT writer), and the USB write pack licence and distribution.

---

### M11. Network and sharing · size L

- **M11.1** Remote transfers through the queue; a `RemoteDocumentsProvider` so remotes are roots.
- **M11.2 SFTP:** trust on first use (done in P0.11), ed25519 key auth, agent-less keys stored in the vault.
- **M11.3 SMB2/3:** encryption on, share enumeration, guest, discovery (mDNS, NetBIOS, WS-Discovery).
- **M11.4 WebDAV:** Digest auth, user-pinned self-signed certificates, streaming PROPFIND, Nextcloud chunked upload.
- **M11.5 FTP/FTPS** client (Apache Commons Net).
- **M11.6 S3:** multipart upload, range reads.
- **M11.7** Streaming preview and playback from remotes via range reads; a Media3 `DataSource` for SMB/SFTP/WebDAV.
- **M11.8 LAN server:**
  - HTTP with a browser UI for upload and download (from a folder the user picks), plus a WebDAV server.
  - Off by default; runs only while the screen shows it or as a foreground service with a notification.
  - Per-session random password shown as a QR code.
  - LAN only; binds to the Wi-Fi interface.
- **M11.9 Cloud:** SAF providers now. Native Drive/Dropbox/OneDrive with the user's own OAuth app only at **GATE-D-cloud**; no Fylz relay ever.

---

### M12. Workspace and accessibility · size L

- **M12.1 Dual pane** (the `workspace/` models exist): side by side on wide screens, with F5/F6 copy and move between panes.
- **M12.2 Drag and drop:**
  - within Fylz (between tabs and panes, into folders, into archives);
  - across apps with `DRAG_FLAG_GLOBAL | DRAG_FLAG_GLOBAL_URI_READ`;
  - accepting drops with `requestDragAndDropPermissions`.
- **M12.3 Keyboard map** (one policy file; resolve the existing conflicts):
  - Ctrl+C / X / V / A / Z / Shift+Z;
  - F2 rename, Delete recycle, Shift+Delete permanent (with confirmation);
  - Space Quick Look, Enter open, Backspace or Alt+↑ up, Alt+← / → back and forward;
  - Ctrl+T / W tabs, Ctrl+L path jump, Ctrl+F search, Ctrl+Shift+F advanced search, Ctrl+K command palette;
  - F5 refresh; Ctrl+1 / 2 / 3 views.
- **M12.4 Mouse:**
  - a right-click context menu (every bulk action);
  - hover (M6.6);
  - middle-click opens in a new tab;
  - back/forward mouse buttons;
  - column resize in details view.
- **M12.5 Details view** with columns (name, size, kind, modified, created, tags, dimensions, duration), sortable, configurable.
- **M12.6 Intent room** (top): command palette (every action, fuzzy), path jump with autocomplete, and search entry.
- **M12.7 Protected folders:** `Android/data` and `obb` via Shizuku; an optional root mode.
- **M12.8 App manager:** list installed apps with sizes; back up APKs, including splits as `.apks`; share; uninstall (system dialog); install split packages.
- **M12.9 Accessibility:**
  - every room reachable by a visible button and a TalkBack action;
  - scrubber semantics;
  - system back-gesture exclusion on the edge zones;
  - 200% font scale;
  - keyboard-only operation.
- **M12.10 Desktop mode and external display:**
  - multi-window;
  - `FLAG_ACTIVITY_LAUNCH_ADJACENT` for "open in new window";
  - window-size-class layouts (the Large and XL classes).

---

### M13. Ubuntu Touch app · size XL

**Prerequisite:** M2–M9 core crates are stable.

**Research facts** (verified Sept 2026; re-check at the start of M13):
- UT 24.04-2.0 runs on the Noble base and the Lomiri shell, arm64.
- Apps are clicks built with Clickable 8.9+.
- **Qt 5.15 is the supported UI stack**; Qt 6 is experimental until UT 26.04.
- A file manager needs the `unconfined` AppArmor template. It gets manual OpenStore review, is open-source only, and carries a "Full System Access" badge, which the stock `lomiri-filemanager-app` already has.
- SD and USB mount under `/media/phablet/<label>`, usually via udisks2 (device-dependent).
- Content Hub handles file exchange with other apps.

**Tasks:**

- **M13.1 Port the engine.** Port the operation engine to `core/`: journal, staging, verification, recycle bin, conflict and preflight policies. Use the Kotlin policies as the spec, and port the Kotlin tests as golden tests.
  - On Linux the recycle bin follows the **freedesktop Trash spec**: `$XDG_DATA_HOME/Trash` for home, and `$topdir/.Trash-$uid` for removable volumes.
  - Keep Fylz's own journal on top.
  - The Android recycle bin stays as is.
- **M13.2 `fylz-ffi-qt`:**
  - cxx-qt bridge exposing `QObject`s: `FolderModel` (`QAbstractListModel`), `OperationQueue`, `SearchController`, `ArchiveModel`, `PreviewProvider` (`QQuickImageProvider` for thumbnails), `RenameController`.
  - Pin a cxx-qt version with Qt 5.15 support.
- **M13.3 UI:** QML with Lomiri Components on Qt 5.15.
  - Mirror the Android information architecture: browser as home, left/right/bottom rooms, edge scrubber, Quick Look.
  - A **Hyle for QML** theme file: tokens only; colours and type from `hyle-design-system` exported as JSON by a script, not by editing the submodule.
  - Convergence: tablet and desktop layout when docked.
- **M13.4 Platform integration:**
  - Content Hub as a source and destination for `all`;
  - udisks2 on the system bus for mount, unmount and eject (unconfined);
  - `inotify` for live updates;
  - freedesktop thumbnails cache (`~/.cache/thumbnails`) read and write;
  - `mimeapps.list` for Open With;
  - the `url-dispatcher` for opening files.
- **M13.5 Packaging:**
  - `ut/` Clickable project with the Rust builder, framework `ubuntu-touch-24.04-1.x`, arm64 (plus amd64 for desktop testing);
  - an AppArmor manifest with the unconfined template.
  - A **confined "Fylz Lite"** variant using `content_exchange` only, as the OpenStore suggests.
- **M13.6 OpenStore submission kit:** README, build instructions, a justification for unconfined access, screenshots. **GATE-UT:** Madhav submits.
- **M13.7** A Qt 6 readiness branch compiled against UT 26.04 dev images when available.

---

### M14. Desktop Linux app · size L

**Decision (pre-made):** share the QML UI with Ubuntu Touch (Qt 6 on the desktop, Qt 5.15 on UT, with a thin compatibility layer), not Compose Desktop. Reasons: one UI for both Linux targets, native Wayland, and no JVM. Compose Desktop stays a later option only if Madhav asks.

- **M14.1 Qt 6 build** of the `ut/` QML sources, with desktop-specific layouts: menubar optional, dual pane default, details view default.
- **M14.2 freedesktop integration:**
  - Trash spec;
  - Thumbnail Managing Standard (normal/large/x-large/xx-large, with `.thumbnailer` interop to *use* system thumbnailers when present);
  - shared-mime-info for MIME;
  - desktop entries and `mimeapps.list` for Open With;
  - `recently-used.xbel` read and write;
  - GTK bookmarks and KDE places import;
  - xdg-desktop-portal OpenURI.
- **M14.3 Drives via udisks2:**
  - mount, unmount, eject, power off;
  - LUKS unlock;
  - format;
  - **loop-mount ISO/IMG** via `Manager.LoopSetup`, with the userspace reader (M4) as a fallback;
  - **write image to USB** via `Block.OpenForRestore` with polkit.
- **M14.4 Indexing:** Fylz's own index (fanotify needs CAP_SYS_ADMIN for whole-filesystem marks, so use inotify for watched folders plus periodic rescans). Optionally query LocalSearch (GNOME) or Baloo (KDE) as extra sources.
- **M14.5 Packaging:**
  - `.deb` first (unconfined, full function);
  - AppImage;
  - Flatpak with `--filesystem=host`, `--filesystem=/run/media`, `--filesystem=/media` and `--system-talk-name=org.freedesktop.UDisks2`, noting Flathub review scrutiny;
  - Snap last (classic confinement needs approval).
- **M14.6 Network on Linux:** reuse the Rust remote clients from M11 (or port them), plus optional GVfs/KIO URIs for opening in other apps.

---

## 6. Decisions reserved for Madhav

| ID | Decision | Default until decided |
|---|---|---|
| D1 | Remove Google Play Services (ML Kit scanner and OCR): a Play flavour vs open replacements (Tesseract / PaddleOCR from M8.8) | Keep behind seams |
| D2 | Allow other apps tree access to Fylz locations (setting) | Off |
| D3 | Kotlin version alignment with the constellation | Don't touch |
| D4 | Legacy `fylz-trash` tidy | Manual action only |
| D5 | Encrypted vaults: clean-room Cryptomator format 8 in the core, vs AGPL add-on | Not started |
| GATE-A | Pack list and distribution channels; any GPL/AGPL add-on | Build, don't publish |
| GATE-M8 | Model choice and licence; Hindi locale; default index scope | Rule-based NL only |
| GATE-M10 | exFAT writer legal position; USB write pack | FAT32 write only, exFAT read |
| GATE-D-cloud | Native cloud providers | SAF only |
| GATE-UT | OpenStore submission | Prepare kit only |

---

## 7. Reference: libraries (verify every licence before use)

| Purpose | Library | Licence (as researched; verify) | Platform |
|---|---|---|---|
| Rust ↔ Kotlin | uniffi | MPL-2.0 | Android |
| Rust ↔ Qt/QML | cxx-qt (KDAB) | MIT / Apache-2.0 | UT, Linux |
| Archives | libarchive | BSD-2 | all |
| 7z write + AES, disk images | 7-Zip (official source, not p7zip), unRAR removed | LGPL-2.1 (+ BSD parts) | all, as `.so` |
| UDF | libudfread | LGPL-2.1+ | all, as `.so` |
| DMG / VHD / VMDK / QCOW2 | libmodi / libvhdi / libvmdk / libqcow | LGPL-3.0+ | all, as `.so` |
| NTFS / HFS+ / APFS read | libfsntfs / libfshfs / libfsapfs | LGPL-3.0+ | all, as `.so` |
| FAT / exFAT | FatFs | BSD-style | all |
| NTFS write | ntfs-3g | GPL-2.0+ | add-on only |
| ext4 write | lwext4 | effectively GPL-2 | add-on only |
| CHD | libchdr | BSD-3 | all |
| OpenPGP (Kotlin) | PGPainless + Bouncy Castle | Apache-2.0 + MIT | Android |
| OpenPGP (Rust) | rpgp (`pgp` crate) | MIT / Apache-2.0 | UT, Linux |
| age (Kotlin) | kage | Apache-2.0 / MIT | Android |
| age (Rust) | rage (`age` crate) | MIT / Apache-2.0 | UT, Linux |
| PDF write / encrypt | PdfBox-Android | Apache-2.0 | Android |
| PDF view | androidx.pdf | Apache-2.0 | Android |
| E-books | Readium kotlin-toolkit | BSD-3 | Android |
| MOBI | libmobi | LGPL-3+ | `.so` |
| Office | OpenDocument.core | MPL-2.0 | `.so` |
| Syntax highlighting | tree-sitter | MIT | all |
| 3D | SceneView / Filament | Apache-2.0 | Android |
| Maps | MapLibre Native | BSD-2 | Android |
| LLM runtime | llama.cpp | MIT | all (pack) |
| LLM runtime alternative | LiteRT-LM | Apache-2.0 (verify) | Android (pack) |
| Embeddings runtime | ONNX Runtime | MIT | all (pack) |
| Vector store | sqlite-vec | Apache-2.0 / MIT | all |
| OCR | Tesseract (+ Tesseract4Android) | Apache-2.0 | all (pack) |
| FTP | Apache Commons Net | Apache-2.0 | Android |
| Reference only, do not copy | EtchDroid (GPL-3), SD Maid SE (GPL-3), Cryptomator cryptolib (AGPL), xorriso/libisofs (GPL), libcdio (GPL) | — | — |

---

## 8. First instructions for this run

1. Commit this file as `docs/agent/MASTER_PLAN.md`.
2. Read `docs/agent/PROGRESS.md`, add a "Master plan" section, and continue with **M1 / P1.8**.
3. Stop at **GATE-M1**.
