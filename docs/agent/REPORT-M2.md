# REPORT-M2: `fylz-core` bootstrap

Per `docs/agent/MASTER_PLAN.md` §3.5. Covers M2's six tasks, M2.1–M2.6
(`docs/agent/MASTER_PLAN.md` §5, "M2. fylz-core bootstrap"), plus M3.1 part 1 (libarchive
vendored, read path), which landed on this branch immediately after M2.6 and is folded into this
report because `docs/agent/MASTER_PLAN_ADDENDUM_1.md` §A's housekeeping pass committed it as the
next thing after GATE-M2. Full per-task detail lives in `docs/agent/PROGRESS.md`'s task table; this
report summarises rather than repeats it. GATE-M2 (Madhav reviews APK size and cold-start) is a
review gate under Addendum 1 §A — this report, the device-check additions and
`docs/agent/REVIEW_QUEUE.md`'s GATE-M2 entry are what that policy calls for; the run continued past
it into M3.1 rather than stopping.

## 1. Status

**Verification level: the `core/` Cargo gate (`cargo build`/`clippy --all-features --all-targets -D
warnings`/`fmt --check`/`test --workspace`/`deny check`, all green for every M2.x and M3.1 commit)
plus Robolectric/plain-JVM `testDebugUnitTest`/`lintDebug` on the Kotlin side, plus
`assembleDebug`/`assembleRelease` and the new `buildCoreDebug`/`buildCoreRelease`/
`check16KbPageAlignment` Gradle tasks — no real Android device, emulator, or 16 KB-page-size
hardware was available in this run's container.** All six M2 tasks are done and verified at that
level: `testDebugUnitTest` grew from 385/385 at the end of M1 to 393/393 after M2.4 (the 8 new
`DecoderClientTest` cases) and stayed there through M2.6, with `lintDebug` clean throughout. The
Rust side grew from 0 tests (M2.1, pure scaffolding) to 24 tests across `fylz-ffi-android`/
`fylz-sniff` by M2.5, plus a `cargo fuzz` smoke target (M2.6) that ran 4,795,656 executions in 60 s
with zero crashes. M3.1 part 1 (libarchive vendoring) was verified on the host at commit time and,
as of this report, its Android cross-compile is verified too (§5). What this milestone cannot
itself confirm — a real 16 KB-page-size device install, genuine cross-process kill/crash behaviour
for `DecoderService`, and the actual cold-start delta — is exactly GATE-M2's own remaining scope,
tracked in §4 below and in `docs/agent/DEVICE_CHECKS.md`.

## 2. Tasks

| ID | Status | Commit | Tests | Notes |
|---|---|---|---|---|
| M2.1 | done, verified | `6ae3650` | `cargo test --workspace`: 0/0, trivially (pure scaffolding) | Bootstrapped the `fylz-core` Rust workspace: eleven crates under `core/crates/` per §4.2's target layout (all eleven scaffolded now, not just M2's two), `core/deny.toml` with an eight-licence allow-list (Apache-2.0, MIT, BSD-2/3-Clause, ISC, Zlib, CC0-1.0, MPL-2.0 — MPL-2.0 added ahead of need for M2.2's uniffi), `[bans] wildcards = "deny"` and unknown-registry/git denied as supply-chain hardening. `cargo-deny`/`cargo-ndk` installed (neither pre-installed in the sandbox). |
| M2.2 | done, verified | `dad5d6d` | New `fylz-ffi-android` tests (2): version matches manifest, sniff stub ignores its fd; `testDebugUnitTest` unchanged, 385/385 | uniffi 0.32.2 skeleton: `fylz_version()`/`sniff()` (stub) exported, generated `fylz_ffi_android.kt` plus hand-written `FylzCore.kt` wrapper (both committed, since nothing regenerates them yet). `net.java.dev.jna:jna:5.19.1` and `kotlinx-coroutines-android:1.11.0` added to `app/build.gradle.kts`. `Unicode-3.0` added to `deny.toml`'s allow-list (a transitive of `uniffi_bindgen`'s `askama`/`unicode-ident`, found by `cargo deny check` failing on first run). |
| M2.3 | done, verified | `851b2f8` (+ CI fixup `0fc9473`) | No new Rust/Kotlin tests; `testDebugUnitTest` re-run with `jniLibs/` deleted first to prove `preDebugBuild`'s new dependency actually triggers `buildCoreDebug`, confirmed, then 385/385 unchanged | Wired `fylz-core` into Gradle: `buildCoreDebug`/`buildCoreRelease` cross-compile all three ABIs via `cargo ndk` into `app/src/main/jniLibs`, then regenerate the Kotlin bindings from a fresh host build; `check16KbPageAlignment` reads every shipped `.so`'s ELF `LOAD` alignment via `llvm-readelf -l` and fails the build under `0x4000`. Two real bugs found and fixed: the `uniffi-cli` feature pulled ~40 extra crates into the *library* build (fixed by gating `cli` behind its own feature + `required-features` on the bin target); `[profile.release] strip = true` silently broke uniffi-bindgen's own symbol-table-based metadata lookup (fixed by always generating bindings from a separate, unstripped host build). A third finding, not a bug in code: the NDK r28+ toolchain links `--profile dev-android` at the classic 4 KB page size and `--release` (LTO+strip) of the *same* crate at 16 KB, with no other variable changed — pinned explicitly via `-Wl,-z,max-page-size=16384` in `core/.cargo/config.toml` rather than relying on the linker's optimization-dependent default. Post-push, both CI workflows broke (`no such command: 'ndk'`) since neither runner had `cargo-ndk`/the NDK installed; fixed by adding the install steps to both. |
| M2.4 | done, verified | `6de3490` | New `DecoderClientTest` (8 cases); `testDebugUnitTest` 393/393 (up from 385), `lintDebug` clean (87 warnings, unchanged baseline) | The isolated `:decoders` process (§4.4): `IDecoderService` AIDL, `DecoderService` (`isolatedProcess="true"`/`process=":decoders"`/`exported="false"`, the first real caller of M2.2's FFI stub), `DecoderClient` (lazy bind, per-call timeout, drops and rebinds on any `RemoteException`/disconnect/crash — never throws, returns `false`/`null`). A real bug was found in the *test* strategy, not the production code: `kotlinx.coroutines.test.runTest`'s virtual clock raced ahead of `withContext(Dispatchers.IO)` work that genuinely crosses onto a real dispatcher, firing the timeout branch regardless of how fast the real work finished; fixed by switching to plain `runBlocking`. Genuine cross-process kill/crash behaviour is real-device-only (`DEVICE_CHECKS.md` §14). |
| M2.5 | done, verified | `9226754` | New `fylz-sniff` tests (17) and `fylz-ffi-android` tests (7, up from 2); `testDebugUnitTest` unchanged, 393/393 | `fylz-sniff`: ~39 formats detected by hand-written magic-byte/structure rules (ISO9660, UDF, MBR/GPT, DMG/VHD/VHDX/QCOW/VMDK/VDI/WIM, 7z/RAR4/RAR5/ZIP + zstd/xz/bz2/gz/lz4, PDF/OOXML/ODF/EPUB/APK, plus common raster/executable/database formats) — `shared-mime-info` is GPL-2.0-or-later (confirmed against its own `COPYING` before writing a single rule), so neither used as a dependency nor copied for its rules. `sniff()`'s stub is replaced with real detection, wired through `DecoderService` end to end. `cargo deny check` caught a real wildcard-dependency gap (the first internal path dependency in this workspace, `fylz-sniff = { path = ... }` with no `version`) — fixed by pinning the version alongside the path. |
| M2.6 | done, verified | `27637e8` | No new unit tests — the fuzz smoke run and CI steps are this task's own test; `cargo +nightly fuzz run sniff -- -max_total_time=60`: 4,795,656 executions, 0 crashes | CI additions to `android.yml`: `cargo test`/`clippy --all-features --all-targets -D warnings`/`fmt --check`/`deny check` from `core/`, nightly toolchain + `cargo-fuzz` install, a 60 s fuzz smoke step against a new `sniff` fuzz target (`core/fuzz/`) covering both `fylz_sniff::sniff` and `sniff_with_tail`. **GATE-M2 reached at this commit** per the master plan's own gate text; M3 waited on Madhav's review until Addendum 1 §A.1 changed the gate policy. |
| M3.1 (part 1) | done, verified (host + Android cross-compile) | `5490b0d` | 4 tests in `fylz-archive` against a real `ar` archive (host); Android cross-compile of `fylz-archive` now verified for all three ABIs (§5) | `libarchive` 3.8.9 vendored as a git submodule (`core/third_party/libarchive`), statically linked via `build.rs` (drives libarchive's own CMake build, routed through `cargo ndk`'s toolchain on Android targets, plain host CMake otherwise; every optional codec/ACL/xattr/iconv/crypto backend explicitly `OFF` for this first cut). A hand-written FFI to 10 libarchive C functions backs a read-only `Reader`: `entries(fd)` (list, no data read) and `read_entry(fd, path)`. Found already written but uncommitted by a prior session (§3); verified and committed under Addendum 1 §A.1's housekeeping directive, not authored fresh in this pass. Part 2 (compression backends, `extract()`) and part 3 (moving `ArchiveExtractionPolicy`'s limits into this crate) are explicitly still open. |

## 3. Deviations

- **Branch name substitution** and **JDK 17 → 21**, carried unchanged from `docs/agent/REPORT-M1.md`
  §3 — neither is specific to M2's own scope.
- **M3.1's libarchive work was found already written but uncommitted in the working tree by a prior
  run/session, not the run that verified and committed it.** At the time it was found, both
  `docs/agent/PROGRESS.md`'s M1 and M2.6 rows recorded M3 as waiting on Madhav past GATE-M1/GATE-M2,
  with no record of that approval — so the code was verified as-is (`cargo build`/`test`/
  `clippy -D warnings`/`deny check` green for the host target; `cargo fmt --check` failures were
  fixed, formatting-only) and committed to local history only, not pushed, pending a decision.
  Not independently verified at that point: the Android cross-compile path — only the host build had
  been exercised. **Resolved by Addendum 1 §A.1:** the gate policy changed (GATE-M1/GATE-M2 are now
  review gates, logged in `docs/agent/REVIEW_QUEUE.md` and continued past, never a stop), and §A's
  own housekeeping list directed this commit to be made and logged as M3.1 — done. This report's own
  §5 closes the one verification gap that commit left open (the Android cross-compile), which is new
  work done for this report, not carried over from the prior session's own claim.
- **M2.3's 16 KB linker-flag pin** (§2 above) is recorded here as a deviation-worth-flagging rather
  than a plain implementation detail, because it overrides an NDK toolchain default rather than just
  configuring an explicit option the toolchain already exposed as a first-class knob;
  `docs/agent/REVIEW_QUEUE.md`'s GATE-M2 entry already asks for Madhav's own sign-off that this is
  the right long-term fix rather than a workaround to revisit.
- No other new deviation was introduced by M2 itself. Every "found and fixed" item named in §2 above
  (the `uniffi-cli` feature leak, the stripped-binary bindgen breakage, the `runTest` virtual-clock
  race, the wildcard-dependency gap) was a genuine bug caught and fixed within its own task's commit,
  described in full in its own `docs/agent/PROGRESS.md` row rather than repeated here as a deviation.

## 4. Unverified items

Every device-only claim below has a corresponding entry in `docs/agent/DEVICE_CHECKS.md`, cited by
its section number and heading:

- **§13 — "fylz-core native library: 16 KB page-size device install (M2.3)."** `check16KbPageAlignment`
  confirms every shipped `.so`'s ELF `LOAD` segment is aligned to 16 KB via `llvm-readelf -l`; a real
  install and `dlopen` on genuine 16 KB-page-size hardware, rather than a static ELF-header
  measurement, is unverified.
- **§14 — "DecoderService kill/crash behaviour (M2.4)."** `DecoderClientTest`'s 8 cases drive
  `IDecoderService.Stub` fakes through `DecoderClient`'s `bind`/`unbind` seam, not a real bound
  service in a real isolated process; genuine cross-process kill-on-timeout, a real crash not
  reaching the host app, and confirming `:decoders` truly has no inherited permissions are all
  real-device-only and unverified here.
- **§15 — "M2 — cold-start delta."** No `adb`/real device in this container, so no real
  `am start -W` timing exists for either a pre-M2 or a current build. The architectural expectation
  (read from the code, not assumed): the only `Native.register`-equivalent load happens inside the
  *generated* `UniffiLib`/`IntegrityCheckingUniffiLib` Kotlin `object`s, which run their `init` block
  only on first reference; the only call site anywhere that references `FylzCore` is
  `DecoderService.sniff()`, which runs in the separate `:decoders` process — never the main process
  `am start` times — and, per M2.5's own progress note, nothing in the app calls `DecoderService`
  yet either. On paper this means a zero cold-start delta for the main process; confirming that on a
  real device, rather than trusting the code-reading argument alone, is exactly this check's own job.

No new `DEVICE_CHECKS.md` entry was needed for M3.1 part 1: it adds a read-only Rust library with no
call site anywhere in the app yet (nothing wires `fylz-archive` into `fylz-ffi-android` or any UI
path), so it has no device-observable behaviour of its own to check, the same reasoning M2.1's own
progress row gave for skipping a device-check entry at that stage.

## 5. Numbers

APK measurements are from `assembleRelease` at two commits: `f15a314` (P1.14, the last commit before
`core/` existed — M1's own baseline) and `5490b0d` (M3.1, the current head at measurement time). Both
are `app-release-unsigned.apk`, the universal APK (no ABI splits), and every `.so` in the APK is
STORED (uncompressed), so compressed size equals uncompressed size for the numbers below.

**Total size:**

| | Bytes |
|---|---|
| Baseline (`f15a314`) | 53,474,676 |
| HEAD (`5490b0d`) | 55,687,755 |
| Delta | +2,213,079 (+4.14%) |

**New native libraries in HEAD, absent from the baseline:**

| ABI | `libfylz_ffi_android.so` | `libjnidispatch.so` (JNA) | Subtotal |
|---|---|---|---|
| arm64-v8a | 402,472 B | 165,992 B | 568,464 B |
| armeabi-v7a | 277,764 B | 116,344 B | 394,108 B |
| x86_64 | 429,800 B | 116,904 B | 546,704 B |

Sum of new `.so` bytes across all three ABIs: **2,033,328 B**. The remaining ≈180 KB of the total
delta (2,213,079 − 2,033,328 = 179,751 B) is dex/resources: the generated uniffi Kotlin bindings,
JNA's own Java classes, `DecoderService`, and the sniff wiring.

**Finding — JNA dead-ABI weight.** JNA also ships `libjnidispatch.so` for ABIs the app never builds
`fylz-core` for and that no Android device Fylz targets needs: `armeabi` 126,980 B, `mips` 130,556 B,
`mips64` 150,256 B, `x86` 116,260 B — **524,052 B of dead weight** carried in the universal APK
purely because the app declares no `abiFilters`/ABI splits, so Gradle packages every JNA-published
ABI rather than just the three the core actually targets. Recorded as a finding and a candidate
follow-up (§7); no build change was made to fix it in this pass.

**Pre-existing, unchanged, dominating the APK:** `libmlkit_google_ocr_pipeline.so` — ≈11.06 MB
(arm64-v8a), 6.78 MB (armeabi-v7a), 11.56 MB (x86), 11.63 MB (x86_64). M2 did not touch this file;
it is already the largest native library in the APK by an order of magnitude over anything M2 added.

**Budget check:** `docs/agent/MASTER_PLAN.md` §4.2 sets a budget of **+12 MB per ABI** for the core
before add-on packs. M2's actual per-ABI addition is **0.39–0.57 MB** (568,464 B / 394,108 B /
546,704 B, i.e. ~0.37–0.54 MiB) — roughly 3–5% of the budget, comfortably inside it.

**16 KB page alignment:** `:app:check16KbPageAlignment` ran as part of `buildCoreRelease` (via
`finalizedBy`) and the build succeeded — every shipped `.so`'s ELF `LOAD` segment alignment is at
or above `0x4000` (16 KB). A real device install is still device-needed (§4, `DEVICE_CHECKS.md`
§13).

**Cold-start delta:** device-needed (`DEVICE_CHECKS.md` §15); see §4 above for the architectural
argument this run could make without a device.

**M3.1 Android cross-compile result (new since M2.6):** `cargo ndk -t arm64-v8a -t armeabi-v7a
-t x86_64 build --release -p fylz-archive` compiled `fylz-archive` — and the vendored libarchive,
built via CMake through the NDK toolchain — successfully for all three ABIs. cargo-ndk's own final
artifact-copy step then reported "No usable artifacts produced by cargo … set the crate-type … to
include 'cdylib'". This is expected, not a failure: `fylz-archive` is an rlib library crate (it has
no `[lib] crate-type = ["cdylib"]` of its own — nothing links it into a standalone `.so` yet; it's
consumed as an ordinary Rust dependency, presumably by `fylz-ffi-android` once M3.1 part 2/3 or a
later milestone wires it in), so cargo-ndk's copy step — which only ever looks for a `cdylib`
artifact to place under `jniLibs/` — has nothing to find. It is a post-build copy complaint, not a
compile failure. So M3.1 part 1 is now verified for Android as well as the host, closing the one gap
`docs/agent/PROGRESS.md`'s own M3.1 row left open ("Android cross-compile … verified separately").

## 6. Licences added

- **uniffi 0.32.2** (`core/crates/fylz-ffi-android`, M2.2) — **MPL-2.0**, file-level copyleft,
  permitted anywhere per §2.2. Generates the Kotlin bindings in
  `app/src/main/java/io/github/mbaliga/fylz/core/`. `cargo deny check` passes with it on the
  allow-list and zero `license-not-encountered` warning for this entry.
- **JNA** (`net.java.dev.jna:jna:5.19.1@aar`, M2.2) — dual-licensed **Apache-2.0/LGPL-2.1**; the
  **Apache-2.0 option** applies here, stated explicitly in `THIRD_PARTY_NOTICES.md` so a future
  reviewer doesn't have to re-derive the choice from §2.2 themselves.
- **kotlinx-coroutines-android 1.11.0** (M2.2) — **Apache-2.0**.
- **libarchive 3.8.9** (`core/third_party/libarchive`, git submodule pinned to release tag `v3.8.9`,
  M3.1) — read in full from its own `COPYING`, not from memory:
  - **The library proper** (all C sources and documentation files, except the exceptions below) is
    under a **2-clause BSD-style licence**, copyright Tim Kientzle (the file's own boilerplate reads
    "Copyright (c) 2003-2018 <author(s)>"): redistribution in source and binary form is permitted
    provided (1) source redistributions retain the copyright notice, conditions and disclaimer
    unchanged, and (2) binary redistributions reproduce the same notice, conditions and disclaimer
    in the documentation or other materials provided with the distribution; the licence disclaims
    all warranties and liability.
  - **Exceptions carrying different terms**, per `COPYING`'s own accounting: `archive_read_support_
    filter_compress.c`, `archive_write_add_filter_compress.c` and `mtree.5` are also subject in
    whole or part to a 3-clause UC Regents copyright (the file text itself is controlling, per
    `COPYING`'s own instruction, not this summary); `archive_parse_date.c` is public domain;
    `archive_blake2.h`/`archive_blake2_impl.h`/`archive_blake2s_ref.c`/`archive_blake2sp_ref.c` are
    triple-licensed, redistributable under the recipient's choice of CC0 1.0 Universal, OpenSSL, or
    Apache 2.0; and the build files (Makefiles, configure scripts, auxiliary build scripts) carry
    "widely varying licensing terms" per `COPYING`'s own text, to be checked file-by-file before
    distributing them specifically. `fylz-archive`'s `build.rs` compiles the library through CMake
    and statically links only the resulting compiled object code (`cargo:rustc-link-lib=static=
    archive`) — it never redistributes libarchive's own build scripts as such. Whether the UC
    Regents-covered `compress`-filter sources or the triple-licensed BLAKE2 sources actually end up
    among the compiled objects in this specific static link was not independently confirmed in this
    pass (carried to §7 as an open item); libarchive itself is BSD-licensed C code linked statically,
    never a Cargo dependency, so it sits outside `deny.toml`'s own graph entirely, matching M2.1's
    own note that LGPL/GPL-avoidance policy is enforced there for Cargo dependencies specifically.
- **`fylz-archive`'s build-time-only dependencies** (M3.1, never linked into the APK — they run only
  while compiling `core/` on the build machine): `cmake 0.1.58`, `cc 1.4.7`, `shlex 2.0.1`,
  `find-msvc-tools 0.1.13`. Each crate's own `license` field was read directly from
  `~/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/<crate>-<version>/Cargo.toml`:

  | Crate | Version | `license` field |
  |---|---|---|
  | `cmake` | 0.1.58 | `MIT OR Apache-2.0` |
  | `cc` | 1.4.7 | `MIT OR Apache-2.0` |
  | `shlex` | 2.0.1 | `MIT OR Apache-2.0` |
  | `find-msvc-tools` | 0.1.13 | `MIT OR Apache-2.0` |

  All four are already covered by `deny.toml`'s eight-licence allow-list (both MIT and Apache-2.0
  are on it from M2.1) and are build-dependencies only — `cargo metadata`'s own dependency-kind
  field distinguishes them from anything in the shipped library's runtime graph, and none of the
  four appears in `fylz-ffi-android`'s or `fylz-archive`'s `[dependencies]` (only `[build-
  dependencies]`), so no object code from any of them is linked into `libfylz_ffi_android.so`.

## 7. Questions for Madhav

- **JNA's dead-ABI weight (§5): exclude via packaging, or adopt ABI splits?** 524,052 B of
  `libjnidispatch.so` ships for `armeabi`/`mips`/`mips64`/`x86` — ABIs the app builds no core for and
  that no Android device Fylz targets needs. The candidate fixes are a
  `packaging { jniLibs { excludes += ... } }` block naming those four paths, or moving to full ABI
  splits (which would also shrink every other per-ABI APK by excluding the other two architectures'
  copies of the ≈11 MB ML Kit OCR library — a much larger win than JNA's own dead weight, but a
  bigger build-graph change). This run made no build change either way, per the instruction not to.
- **M2.3's 16 KB linker-flag pin** (§3): is pinning `-Wl,-z,max-page-size=16384` explicitly in
  `core/.cargo/config.toml` the right long-term fix, or should this be revisited once (or if) a
  future NDK release makes 16 KB the unconditional default regardless of build profile? Carried from
  `docs/agent/REVIEW_QUEUE.md`'s GATE-M2 entry.
- **libarchive's exception files** (§6): whether the UC Regents-covered `compress`-filter sources or
  the triple-licensed BLAKE2 sources are actually among the compiled objects in this build's static
  link wasn't independently confirmed here; worth a build-log/object-list check before this ships
  publicly, since `COPYING` is explicit that those files carry terms beyond the top-level BSD notice.
- **`shared-mime-info`'s GPL-2.0-or-later status** (M2.5): now that `fylz-sniff` has a working
  ~39-format hand-written table as a permissively-licensed alternative, is there any future value in
  also offering `shared-mime-info` itself as a separately-packaged, GPL-segregated add-on (per the
  master plan's own add-on policy) for broader format coverage, or does `fylz-sniff`'s own
  additive-by-design table make that unnecessary? M2.5's own progress note leaves "top 300 formats"
  as a stated non-goal rather than a decided one.
