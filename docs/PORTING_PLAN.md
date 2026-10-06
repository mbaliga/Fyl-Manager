# Fylz — multi-platform porting plan

> Part of the constellation-wide porting program (`Personal-Tracker/PORTING_PROGRAM.md`, 2026-10-06).
> Status: **PLAN — nothing in this document has been built.** Every claim about a target platform is
> labelled with its evidence class (§0). This file is owned by the lead planning session; a platform
> track updates only its own §4 row and appends to the per-task log in this repo's own state files
> (`docs/agent/PROGRESS.md` on PR #19's branch; `docs/ROADMAP.md` on `main` carries status only).

**Scope and reference convention.** Ubuntu Touch and Linux desktop are already planned in Fylz's own
documents: `docs/agent/MASTER_PLAN.md` (M13 Ubuntu Touch, M14 Desktop Linux, §4.1 Decision A-X) and
`docs/agent/ADR-LINUX-UT-STRATEGY.md`. Those files exist only on draft PR #19 (branch
`claude/fylz-fotoz-complete-y60pfw`, unmerged on 2026-10-06), so this plan cites them as `PR19:<path>`.
It does not restate or re-decide them, and where it disagrees with them they win. It adds the iOS/iPadOS
reframe ("Fylz for Files"), macOS, Windows, the cross-target sequencing, and the evidence, CI and
identifier hooks the program asks for. Fylz already uses M1–M14 for milestones and D1–D5/GATE-* for
decisions, so step ids here are prefixed `FZ-`. Where the program needs a repo decision changed, it is
written as a **PROPOSAL** (§8) and nothing here is ruled.

## 0. Evidence labels (never dropped)

`PLAN` (this document) · `CI (hosted VM)` · `SIMULATOR` · `CI-APPROX — NOT DEVICE EVIDENCE` ·
`NEEDS-DEVICE-VALIDATION` (NDV) · `NEEDS-OWNER-VALIDATION` (NOV) · `NOT-APPLICABLE` (with reason) ·
`CONTAINER-BUILD-ONLY` (the agent container: JVM x86_64 compile and tests, nothing else).

This plan runs nothing. Its only measurements are file counts and greps over checkouts (commands in
§1.3 and §2); facts about PR #19 come from reading its branch, not from building it.

## 1. What this repo is, in porting terms

### 1.1 Product and state

Fylz is an open-source (Apache-2.0), local-first Android file workspace: full-filesystem and SAF browsing
with rooms and tabs, durable journaled copy/move/recycle/restore, a manual-only recycle bin, archive
browse/create/extract, a broad preview registry, scheduled verified backups, scan-to-PDF/OCR, WebDAV/SFTP/
SMB/S3 remotes, and BYOK/local-model AI proposals that never receive raw filesystem authority
(`README.md`, `docs/PRODUCT-AND-ARCHITECTURE.md`). It is `1.0.0-alpha01` (`app/build.gradle.kts`,
`docs/ROADMAP.md`). Stable v1 is blocked on recorded device/provider, accessibility and signed-upgrade
acceptance (`README.md` "Required before stable v1", `docs/DEVICE_ACCEPTANCE.md`, `docs/RELEASE.md`);
**no port unblocks that gate and none may be presented as doing so.**

`main` (a04323f, last commit 2026-09-12) ships Android only. Ports exist only as work on draft PR #19. Open
PRs on 2026-10-06 (GitHub REST): #19 draft (Rust core, actions registry, M13 Ubuntu Touch), #18 ready (Fonebrew
workspace handoff), #14 draft (rooms plus pure-JVM `core-model`/`core-vfs`/`core-operations`/`core-format`
modules, `ItemRef` replacing `Uri`), #15 draft stacked on #14 (desktop-class keyboard/mouse), #16 draft (store
listings), #3 stale. #14 and #19 take different routes to portability, and Program OQ-9 records that they cannot all merge.

### 1.2 Stack

| Layer | On `main` | Added on PR #19 (unmerged) |
|---|---|---|
| Languages | Kotlin 2.1.20 (D3: do not touch) | Rust 1.94.1 (`core/rust-toolchain.toml`), QML (Qt 5.15), vendored C (libarchive 3.8.9, zlib, bzip2, xz, zstd, lz4), AIDL |
| UI | Jetpack Compose + Material 3, own `FylzTheme`; `dev.aarso:cell-shell` 0.1.0 (rooms, word-wheel rail, scrubber, shake) | cxx-qt 0.7.3 bridge (`fylz-ffi-qt`) and one QML proof screen |
| Build | Gradle 8.14.3, AGP 8.9.1 (lockstep with Hyle), `includeBuild` of two read-only submodules | `core/` Cargo workspace (13 crates), cargo-ndk, uniffi 0.32.2, Clickable (`ut/clickable.yaml`) |
| Native deps | none | libarchive and five codecs as submodules in `core/third_party`; Qt 5.15 from apt for the bridge |
| Hyle | `dev.aarso:hyle:0.2.0` declared, **zero imports**; it only forces minSdk 31 | M13.3 plans a "Hyle for QML" tokens export |

The one QML file on PR #19 (`core/crates/fylz-ffi-qt/qml/main.qml`, 91 lines) imports only `QtQuick 2.15` and
`com.fylz.demo 1.0`. **No Lomiri.Components control has been exercised yet:** the ADR proves the Lomiri
packages install from apt (§3.2), not that any Fylz QML uses them. This lowers the cost of a toolkit-neutral
shell (FZ-LX1).

### 1.3 Size (measured 2026-10-06)

`main`: `find app/src/main -name '*.kt' | wc -l` = 112 files; `... | xargs cat | wc -l` = 21,629 LOC.
Tests: 29 files, 2,248 LOC, 160 `@Test` (`grep -rh '@Test' app/src/test | wc -l`), no `androidTest`.
`grep -rl 'android.net.Uri' app/src/main --include=*.kt | wc -l` = 50 files.
PR #19 (via `git archive origin/claude/fylz-fotoz-complete-y60pfw core ut`): 58 Rust files, 18,243 LOC outside
`third_party`; 297 `#[test]` attributes, 9 of them in `fylz-ffi-qt`, which CI does not run (§2); four fuzz targets.
The ADR (§6.5) recorded 296 tests passing when written; they were not re-run here.

### 1.4 Not in scope

No change to Android behaviour, the package or `applicationId` (`io.github.mbaliga.fylz`), or product scope.
Nothing is released, signed or submitted. No edit to `PR19:docs/agent/MASTER_PLAN.md` or the ADR.

## 2. Portable core vs platform-bound layers

| Module / dir | Role | Portability | Approx LOC | Notes |
|---|---|---|---|---|
| `core/` Rust workspace (PR #19) | `fylz-archive` 9,707 (libarchive streaming inspect/extract/write), `fylz-ops` 3,266 (journal, staging, preflight, conflict, checksum, recycle incl. freedesktop Trash), `fylz-sniff` 732, `fylz-actions` 259, `fylz-ffi-android` 2,398, `fylz-ffi-qt` 1,510; `-rename -query -index -verify -usb -clean` are one-line stubs | portable core, with Unix-only seams (below) | 18,243 | cargo test/clippy/fmt/deny and a 60 s fuzz smoke run inside `android.yml` on ubuntu-latest; `fylz-ffi-qt` is its own workspace because cxx-qt's build script panics without Qt (ADR §6.3) |
| `ut/` (PR #19) | `clickable.yaml`, `manifest.json` (`fylz.mbaliga`), `fylz.apparmor` (`unconfined`), `fylz.desktop` | UT-bound | ~60 | source only; `specific_output_bin: fylz` names an M13.3 binary that does not exist |
| `ui`, `ui/components`, `ui/theme` | Compose shell on cell-shell; previews (PdfRenderer, Media3, Coil); ML Kit launcher in `FylzV1App.kt` | android-bound | 4,438 + 2,073 + 125 | the fonebrew pattern must be reproduced on every port, the code cannot be; `FylzV1App.kt` is 1,830 LOC and must shrink (`PR19:MASTER_PLAN.md` §2.3) |
| `storage`, `model`, `util` | SAF/`DocumentsContract`, `MANAGE_EXTERNAL_STORAGE`, exported `FylzFilesDocumentsProvider`; `FileEntry` keyed by `android.net.Uri` | android-bound | 1,071 + 78 + 51 | the largest coupling; replaced by native paths off Android; #14's `ItemRef` is the Kotlin-side answer (OQ-9) |
| `operations` | journal, recycle, retry, validation, checksum | android-bound (10/10 files import `android.*`) | 1,737 | already ported to Rust as `fylz-ops`, with the Kotlin tests as golden oracle |
| `browse`, `organize` | sort and scrubber bucketing; smart-collection evaluator | pure Kotlin | 171 + 312 | re-expressible in Rust/QML; `organize` duplicates `index/` and `library/` |
| `workspace`, `search`, `preview`, `network`, `ai` | desktop-mode policies; search model; format inspectors; remote clients; BYOK policy and vault | mixed: pure logic with `android.*` in 2/3, 1/2, 4/9, 3/10, 3/6 files | 347, 395, 1,915, 1,711, 732 | `network` (sshj, smbj, minio, okhttp) is JVM-only and unusable from Rust/QML |
| `pdf`, `backup`, `history`, `index`+`library`, `data` | PdfRenderer + ML Kit OCR; WorkManager backups; SHA-256 snapshots; two parallel indexes; zip4j and scan | android-bound | 647, 1,515, 430, 1,755, 1,315 | archive path moves to Rust `fylz-archive` on PR #19 |
| activities, `FylzApplication` | entry points, shortcuts, VIEW filters, `CrashRecovery` | android-bound | 811 | |

Platform-bound APIs that matter:

| API | Where | Porting impact |
|---|---|---|
| `android.net.Uri` as identity | 50 of 112 main files | every port needs a provider-neutral identity; Rust core uses paths, #14 uses `ItemRef` |
| SAF, `DocumentsContract`, own `DocumentsProvider` | `storage/`, `data/`, `operations/`, `backup/` | Android-only; native paths elsewhere; iOS has no equivalent (File Provider extension plus security-scoped bookmarks is the nearest shape) |
| `MANAGE_EXTERNAL_STORAGE` | `storage/FullAccessPermission.kt`, manifest | maps to UT `unconfined`, Flatpak `--filesystem=host`, macOS Full Disk Access or folder grants, nothing on iOS |
| WorkManager + `dataSync` foreground service | `backup/`, `index/` | per-OS scheduling: systemd user timer or in-app timer (Addendum C5), launchd, Task Scheduler; iOS background work is severely constrained |
| Android Keystore AES-GCM | `ai/ApiKeyVault.kt`, `network/RemoteConnectionStore.kt` | per-OS secret store; a UT click has no system keystore (OQ-22) |
| ML Kit scanner and OCR | `ui/FylzV1App.kt`, `pdf/` | proprietary, forbidden in new code (`PR19:MASTER_PLAN.md` §2.2); D1 must pick an open engine before any non-Android port can scan or OCR |
| `cell-shell`, `crash-recovery`, `hyle` | `FylzApplication.kt`, `ui/` | Android-only Gradle libraries; the interaction pattern and the recovery behaviour need Qt/Swift equivalents |
| **Core Unix seams (measured on PR #19)** | `fylz-archive/src/lib.rs`, `write.rs`; `fylz-ops/src/recycle/{trash,trashinfo}.rs` | public archive API takes `std::os::unix::io::RawFd` (libarchive `archive_read_open_fd`); `recycle::trash` uses `MetadataExt`, `PermissionsExt`, `libc::getuid/localtime_r/mktime`; `write.rs:151-154` hard-codes `CODESET = 14` for every non-Android target (the glibc value, per its own comment); `build.rs` has an Android shape and a host shape only. Windows is expected not to compile as written (unconditional `std::os::unix` imports; read, not built); Apple targets are expected to compile but are unverified, and need a `CODESET` check and a cross-build shape for iOS |

Measured with `grep -rn 'std::os::unix\|libc::\|std::os::fd' core/crates --include=*.rs`. The other `fylz-ops` modules
(journal, staging, preflight, conflict, checksum, models, `recycle::policy`) matched nothing.

## 3. Binding rules this port must not break

- **Local-first, no telemetry** (`README.md` principle 1, `docs/ARCHITECTURE.md` "Local AI and BYOK boundary"). No
  analytics or crash SaaS on any platform; network only on explicit user action; AI gets a bounded typed view and
  returns proposals. Program I-1: the TestFlight crash-report egress must be disclosed and accepted (OQ-2).
- **Recycle-bin contract** (`docs/product/preview-and-recycle-bin-contract.md`, binding on every backend): Delete
  means recycle; restore returns to the original location; no automatic expiry or purge; permanent delete is manual
  with confirmation; never silently fall back to permanent delete (§3: if no recycle location can be written, report
  it). Every OS backend must pass the same behavioural cases as the freedesktop one (`recycle::policy_tests`,
  `trash_tests`), and an OS bin with a user-set auto-empty is flagged to the owner, not assumed fine.
- **Preview contract** (same file): capability-driven, fail-closed, bounded memory, no script execution; native
  parsers of untrusted input run isolated (isolated process on Android; `seccomp`/`prlimit` subprocess on Linux and
  UT per `PR19:MASTER_PLAN.md` §4.4).
- **Decision A-X, with Program R8's exception clause** (`PR19:MASTER_PLAN.md` §4.1; Program §3 R8 and §4.6
  "Exception — Fylz"): new platform-neutral logic goes in a Rust core; Android glue stays Kotlin; **Kotlin
  Multiplatform/Native was evaluated and rejected** (Linux arm64 is not a supported host, its C API is clumsy for
  QML, its Linux targets are Tier 2) and may not be reopened without the owner. This plan therefore proposes no
  KMP, no Compose Multiplatform, and no consumption of F3/F5. Compose Desktop "stays a later option only if Madhav
  asks" (M14); it appears below only as the owner's explicit alternative (OQ-9).
- **M14 pre-made decision:** desktop Linux shares the UT QML shell (Qt 6 desktop, Qt 5.15 UT, thin compatibility
  layer). Extending that to macOS and Windows is a proposal, not a decision (§8).
- **Licence policy** (`PR19:MASTER_PLAN.md` §2.2): core Apache-2.0; LGPL only as a separately built, dynamically
  linked library users can replace, never statically linked (this governs how Qt is bundled on every desktop
  target); GPL/AGPL never in the core; unRAR never; no Play Services, Firebase, ML Kit or Crashlytics in new code.
  `THIRD_PARTY_NOTICES.md` records the Android release graph only; each new Rust, C and Qt dependency is added as it
  lands. Program I-11 agrees.
- **Engineering freeze** (`PR19:MASTER_PLAN.md` §2.3): AGP 8.9.1, Kotlin 2.1.20, Gradle 8.14.3, minSdk 31; the Rust
  toolchain is pinned and bumped only at a milestone boundary (so CI adds targets with `rustup target add`, not by
  editing `core/rust-toolchain.toml`); native libraries 16 KB page-aligned on Android; `hyle-design-system` and
  `shared-libraries` are read-only submodules consumed only by `includeBuild` (`docs/PRODUCT-AND-ARCHITECTURE.md` §4);
  `ui/FylzV1App.kt` must shrink.
- **Fonebrew navigation pattern, mandatory everywhere** (`docs/fonebrew-navigation.md`): rooms not screens, word-wheel
  rail, top-room pull-down reserved (no pull-to-refresh; refresh is a shake), no hamburger, no bottom tab bar, no
  full-screen settings dialogs, no instructional copy in gesture spaces. A SwiftUI `TabView` or a Qt desktop menubar as
  primary navigation would violate it.
- **Design and accessibility** (`docs/DESIGN.md`): 4.5:1 text and 3:1 non-text contrast, colour never carries
  provenance or state alone (Program I-3), 48 dp targets, visible keyboard/mouse focus, community themes as data-only JSON.
- **Full access, honestly asked for** (`README.md` principle 2, `docs/ARCHITECTURE.md` "Broad storage access"): the user
  can decline and still get a working degraded mode; restricted areas are not worked around. Off Android this maps to
  UT `unconfined` plus the confined "Fylz Lite", macOS folder grants when Full Disk Access is declined, iOS document scope.
- **Capability, not backend, decides the UI**; identifiers are provider-owned, never parsed as paths
  (`CONTRIBUTING.md`).
- **Open-core hygiene and honesty:** no Studio code, secrets or signing material in git; device-only claims go in
  `PR19:docs/agent/DEVICE_CHECKS.md` as unverified (law: never claim device behaviour not tested).
- **Owner-reserved decisions** (`PR19:MASTER_PLAN.md` §6, Addendum §A): D1 ML Kit, D2, D3, D5, GATE-A, GATE-M8, GATE-M10,
  GATE-D-cloud, GATE-UT. Hard gates never pass without the owner.
- **Program rules that bind every step:** R1 disjoint directories, R2 existing gate stays green, R3 new workflow files
  only with SHA-pinned actions and no edit to `android.yml` or `release-readiness.yml`, R4 pure-core-first, R5 scaffolds
  serve and sign nothing, R6 nothing released and no artefacts on PRs (Actions storage is exhausted;
  `cleanup-artifacts.yml` runs every 6 hours), R11 no identifier in a manifest before a NAMES.md row, R12 reframes are labelled.

## 4. Target matrix (owner's order)

| Target | Feasibility | Approach | Blockers | Effort (eng-weeks, estimate) | Evidence today |
|---|---|---|---|---|---|
| Ubuntu Touch | moderate | Defer to `PR19:MASTER_PLAN.md` M13 and the ADR: Rust core, cxx-qt 0.7.3, QML (Qt 5.15, Lomiri Components), Clickable Rust builder, unconfined click, later a confined "Fylz Lite". This plan adds only two CI hooks (FZ-UT1, FZ-UT2), one NDV behaviour note for M13.3 (FZ-UT3, proposed) and one secrets proposal (FZ-UT4, OQ-22). | trunk (OQ-9); PR #19 is an unmerged draft; no Docker daemon in the agent sandbox; no UT device (OQ-1); M13.3 binary `fylz` does not exist; six stub crates (rename, query, index, verify, usb, clean); no Rust remote clients; scan/OCR needs D1; no keystore in a click; C++ `QQuickImageProvider` for thumbnails; GATE-UT is the owner's | 12 (shippable browser, operations, archives, previews on PR #19; excludes M4–M12 parity and the Lite variant, +3–4) | PLAN |
| Linux desktop | straight | Defer to M14: Qt 6 build of the same QML shell, desktop layouts, freedesktop Trash/thumbnails/mimeapps/udisks2, `seccomp`/`prlimit` decoder subprocess, Secret Service; packaging deb, then AppImage, Flatpak, Snap | shares the M13.3 shell (if Linux goes first it absorbs about 6 more weeks); cxx-qt 0.7.3 was proven only on Qt 5.15.13, so a Qt 6 pin is unverified; same engine gaps; Flathub review of host-filesystem access; Compose Desktop is off the table unless the owner asks | 6 incremental after the UT shell | PLAN |
| iOS / iPadOS | reframe | "Fylz for Files": a SwiftUI document-browser app over the Rust core via uniffi Swift bindings (new `fylz-ffi-apple`), plus a File Provider extension exposing archives as folders; in-app journaled operations, PDFKit, OS-provided scan/OCR. Not a file manager and not a port of the Android app (R12). | no Apple plan or owner statement in the repo (OQ-6); Developer Program and delivery route (OQ-2); no Mac in the sandbox (hosted `macos-latest` only); core untested on Apple targets (`build.rs` has no iOS shape, `CODESET` constant); an isolation form for decoders on iOS is unknown (a spawned subprocess is not expected to be available); recycle contract has no clear iOS form; fonebrew pattern rewritten in SwiftUI; background work limited | 16 (the File Provider extension is about 4) | PLAN |
| macOS | moderate (proposed; OQ-9) | Qt 6 build of the shared shell plus a macOS platform layer (Trash via Foundation, FSEvents, Keychain, Open With, helper-process decoding); unsandboxed Developer ID `.dmg`, no Mac App Store. Alternative: native SwiftUI/AppKit shell, only if iOS is accepted. | OQ-9; no Mac on record (OQ-5), so device gates are NOV; signing and notarisation custody (OQ-3); Qt is LGPL, so bundling must stay dynamic and replaceable, and how that coexists with a notarised bundle is unknown; same engine gaps; non-native feel | 6 after the Linux build exists; 12+ for a native shell | PLAN |
| Windows | moderate (proposed; OQ-9) | Qt 6 (MSVC) build of the shared shell plus Windows backends (`IFileOperation` recycle, drive enumeration, `ReadDirectoryChangesW`, Credential Manager/DPAPI, registry Open With, Job objects); MSIX or MSI plus winget | OQ-9; core is expected not to compile on Windows as written (§2: `RawFd`, `recycle::trash`, locale pin; read, not built); MSVC build of the vendored C libraries untested; signing route (OQ-3; Azure Artifact Signing is unavailable to the owner); the Dell's fate (OQ-5) | 7 after the Linux build exists | PLAN |

Sum of the five rows: 47 engineer-weeks, an estimate (Program §5), overlapping rather than additive, excluding the Lite variant, M4–M12
feature parity and the Rust remote clients.

**What exists today (not produced by this plan).** On PR #19, `fylz-ops` (journal, preflight, freedesktop Trash) and `fylz-archive`
are covered by `cargo test --workspace` on a hosted ubuntu-latest runner (per the reader profile; run history not re-read for this plan): `CI (hosted VM)`, Linux x86_64 only. The cxx-qt bridge and
`main.qml` were run offscreen in an agent sandbox on Ubuntu 24.04 (ADR §6.4): `CI-APPROX — NOT DEVICE EVIDENCE`, and not in CI. Every
Lomiri, Content Hub, udisks2 and click behaviour is NDV.

**What each target does not get at first** (plan, from the blockers above):

| Capability | UT / Linux | iOS | macOS / Windows |
|---|---|---|---|
| Remote providers (SFTP, SMB, WebDAV, S3) | absent until Rust clients exist (M11); JVM clients are unusable | absent; File Provider route later | as Linux; a mounted SMB share should be reachable as a path (Windows UNC), so smbj is expected to be unneeded (unverified) |
| Scan-to-PDF and OCR | absent until D1 | OS frameworks (VisionKit/Vision) only if the owner accepts them under D1 | absent until D1, or OS OCR under the same ruling |
| Rename, search, index | honest stubs until M7/M8 | same | same |
| Scheduled verified backups | in-app or systemd user timer (Addendum C5) | on demand only | launchd or Task Scheduler (planned) |
| Whole-filesystem browsing | UT `unconfined`; "Lite" is Content Hub only | no | yes, by user grant or unsandboxed distribution |
| Recycle bin | freedesktop Trash (built, `fylz-ops`) | unresolved (FZ-IOS5) | per-OS backend to add (FZ-MAC2, FZ-WIN3) |
| Haptics | not applicable | not planned | dropped, not faked |

## 5. Tier and sequencing

**Tier B, hard-gated on OQ-9** (Program §5 row for Fyl-Manager). The trunk is undecided: Fylz's port work lives on an unmerged draft
that diverges from #14/#15, six core crates are stubs, and GATE-UT, D1 and every signing key are owner-only. Program §7 records Fylz
as one of three repos that run their own programs inside the waves ("Fylz:M13 → M14, trunk per OQ-9") and does not re-sequence it.
The reader profile proposed A-flagship for the UT and Linux rows alone because the owner pulled M13.1/M13.2 forward on 2026-09-30; this
plan keeps the Program's tier and leaves that to the owner.

**Gate before any wave:** OQ-9 ruled (trunk) and CI green on that trunk. Nothing in §6 starts before it except FZ-C0. The gate covers the steps this plan adds; M13 work the owner already authorised on PR #19 continues under the repo's own gates.

| Platform | Wave (Program §7) | Repo-local entry | Device entry (owner) | Master OQs |
|---|---|---|---|---|
| Ubuntu Touch | P-UT b ("native UT ports stay on their own gates: Fylz:M13") | trunk ruled; M13.3 shell exists before any click is built | a UT device (OQ-1); otherwise `CI (hosted VM)` and `CI-APPROX` only | OQ-1, OQ-8, OQ-21, OQ-22, OQ-24 |
| Linux desktop | P-LX ("Fylz:M14 (after OQ-9)") | UT shell done, or Linux carries it first | the Steam Deck and the RedMagic under Termux:X11 (the Dell only if OQ-5 says Linux) | OQ-4, OQ-5, OQ-9, OQ-25 |
| iOS / iPadOS | P-iOS lists Fylz only as "not on iOS: Fylz as a file manager (reframed)"; this plan joins it only if the reframe is accepted | OQ-6 accepted; decide after macOS | Developer Program and a delivery route (OQ-2); the iPad Pro M4 | OQ-2, OQ-3, OQ-6 |
| macOS | P-mac ("Fylz Qt 6 (after OQ-9)") | Linux build exists; OQ-9 ruled | no Mac on record (OQ-5): NOV | OQ-3, OQ-5, OQ-9 |
| Windows | P-win ("Fylz Qt 6 MSVC (after OQ-9)") | Linux build exists; OQ-9 ruled; path lint first | the Dell while it is still Windows (OQ-5); afterwards `CI (hosted VM)` only | OQ-3, OQ-5, OQ-9 |

Repo-local order: UT shell (M13.3), Linux (M14), macOS, Windows, iOS last. The Qt shell is shared, so the UT shell is the common
cost. Program §7's point that build order differs from validation order holds: the owner receives UT artefacts first, but a hosted
Linux x86_64 lane is the cheapest place to verify the shared shell. On OQ-21: a Waydroid run of the unmodified APK is owner-device
evidence only, would not be a port, and for a file manager is meaningful only if the APK can see the host filesystem, which this
plan has not established (unknown). M13.1/M13.2 are already built at the owner's request, so OQ-21 can at most reduce M13.3 onwards.

## 6. Work breakdown

Placement follows Program R1–R4 and Fylz's own layout (`PR19:MASTER_PLAN.md` §4.2: `core/`, `ut/`, `linux/`). `macos/`, `windows/` and `apple/` are proposed siblings of the repo's `ut/` and `linux/` (its layout names none for them). Placement assumes PR #19 as
trunk; if the owner picks another, the directories are re-derived (OQ-9). Every new workflow is a new file, with SHA-pinned actions, and
`android.yml` and `release-readiness.yml` are untouched. Package lanes run on `main` and tags only; PRs are compile-only; binaries go to
draft GitHub Releases, never Actions artifacts (R6). Anything needing a device, a Docker daemon, a Mac or a Windows host cannot be
verified in the agent container and says so.

### 6.1 Cross-target (FZ-C)

| Step | Work and placement | CI file | Done when |
|---|---|---|---|
| FZ-C0 | Rule the trunk (OQ-9). Land this file as `docs/PORTING_PLAN.md`; if PR #19 is the trunk, move it to `docs/agent/PORTING_PLAN.md` and cross-link from `MASTER_PLAN.md` §5 after M14. | none | owner decision recorded; plan on trunk (`PLAN`) |
| FZ-C1 | Core platform seam, as one PR to the shared core: `#[cfg(unix)]`-gate `recycle::trash`/`trashinfo`; give `fylz-archive` a platform-neutral input (`File`/handle) beside the existing `RawFd` API, whose uniffi surface must not change; replace the hard-coded `CODESET` and the `newlocale/uselocale` pin with a per-OS choice. Every backend must pass the same contract cases as the Linux one. | none (the core's own gate in `android.yml` must stay green) | Android CI and the Linux cargo gate unchanged and green; new targets compile (`CI (hosted VM)`) |
| FZ-C2 | Identifier hygiene: NAMES.md rows (OQ-25) for the click name `fylz.mbaliga` (already in `PR19:ut/manifest.json`, before R11), a Flatpak id, bundle ids, MSIX identity. No further identifier is written until its row exists. | none | rows exist |

### 6.2 Ubuntu Touch (defers to M13)

M13.1 (done), M13.2 (done for `FolderModel`, `ArchiveModel`, `OperationQueue`; `RenameController`/`SearchController` honest stubs;
`PreviewProvider` partial) and M13.3–M13.7 are in `PR19:MASTER_PLAN.md` and the ADR and are not repeated. S-UT1 (the JVM-in-click spike) does
not apply: Fylz's core is Rust.

| Step | Work and placement | CI file | Done when |
|---|---|---|---|
| FZ-UT1 | Run the bridge in CI, closing the gap ADR §6.3 records: Qt 5.15 and `qml-module-lomiri-components` from apt on ubuntu-24.04; `cd core/crates/fylz-ffi-qt && cargo test`; seed and offscreen demo. | `.github/workflows/qt-bridge.yml` | green hosted run, printed demo output; `CI (hosted VM)`, no device claim |
| FZ-UT2 | Clickable build of `ut/` in a digest-pinned image once M13.3 produces `fylz` (image per Program F7/F9; the ADR names `clickable/amd64-ut24.04-2.x-arm64`); non-gating 26.04 canary job (M13.7). Click to a draft Release on `main`/tags only. | `.github/workflows/ut-click.yml` | click builds on a hosted runner; installs nowhere (`CI-APPROX — NOT DEVICE EVIDENCE`) |
| FZ-UT3 | PROPOSED for M13.3: Treat suspension as normal: Lomiri freezes unfocused confined apps within seconds (Program §4.1; unconfined behaviour unverified), so M13.3 shows the journal's `NeedsAttention` state after refocus, as the ADR §6.4 harness exercised for process death (an agent-sandbox run, not a device). | none | NDV in `PR19:docs/agent/DEVICE_CHECKS.md` |
| FZ-UT4 | BYOK keys and remote passwords: proposed default is **not persisted** on UT until OQ-22 rules (§8). The UI reports the storage tier. | none | owner ruling |

### 6.3 Linux desktop (defers to M14)

| Step | Work and placement | CI file | Done when |
|---|---|---|---|
| FZ-LX1 | PROPOSED for M13.3 (owner, §8 Q8): Toolkit-neutral shell: keep any `Lomiri.Components` use inside one chrome directory so the same QML builds on Qt 6 (the thin layer M14.1 needs; Fotoz's ADR-013 reached "no Lomiri-specific chrome" independently, per the ADR). Where the shared QML lives with four consumers is a layout choice for the owner (§8, Q2). | none | the shell builds with and without the chrome directory |
| FZ-LX2 | Qt 6 lane: Qt 6 via aqtinstall or distro packages, a verified cxx-qt pin for Qt 6, x86_64 plus `ubuntu-24.04-arm`; build `linux/`, offscreen smoke. | `.github/workflows/linux-desktop.yml` | `CI (hosted VM)` build and smoke; Wayland, HiDPI and gamescope are NDV |
| FZ-LX3 | M14.2–M14.4 freedesktop integration, udisks2, inotify indexing (defer to M14); `seccomp`/`prlimit` decoder subprocess (`PR19:MASTER_PLAN.md` §4.4); Secret Service for keys (OQ-22). | `linux-desktop.yml` | per M14 |
| FZ-LX4 | M14.5 packaging, deb first, then AppImage, Flatpak, Snap (Program §4.2: repos keep their own formats). Build on the oldest supported runner for the glibc baseline; Flathub builds offline, so Cargo and the C submodules must be vendored; the Flatpak id needs its NAMES.md row and the Flathub app-id domain (OQ-4, OQ-25). | `.github/workflows/linux-package.yml` (`main`/tags only) | unsigned packages on a draft Release; Flathub submission is the owner's |
| FZ-LX5 | Start checks for the Deck and the RedMagic (Program §4.2): the app must start without Vulkan, Wayland or udisks2, so volume features degrade by capability. | none | NDV on both devices |

### 6.4 macOS (proposed; OQ-9)

| Step | Work and placement | CI file | Done when |
|---|---|---|---|
| FZ-MAC1 | Gate: Qt 6 shell (recommended) or native SwiftUI/AppKit (shares FZ-IOS4, only if iOS is accepted). | none | OQ-9 ruled |
| FZ-MAC2 | Core on Apple targets in hosted CI (`rustup target add` in the workflow, not in `rust-toolchain.toml`); macOS platform layer: Trash through Foundation (restore uses Fylz's journal record of the returned trash location; whether Finder "Put Back" metadata is set is unknown), FSEvents, data-protection Keychain (never the `security` CLI; Program §4.4), `NSWorkspace`/`UTType`, helper-process decoding. The shim's language (Rust Foundation bindings or a small Objective-C++ file beside the cxx-qt bridge) is chosen in the step and its licences recorded. Sits in `macos/` and cfg-gated backend modules. | `.github/workflows/apple-core.yml` | `cargo test` on a hosted macOS runner: `CI (hosted VM)` |
| FZ-MAC3 | Qt 6 shell build in `macos/`: Qt frameworks bundled dynamically (`macdeployqt`), unsigned `.app`/`.dmg` on `main`/tags only; signing and notarisation exist only as disabled templates until OQ-3. | `.github/workflows/macos-desktop.yml` | unsigned dmg on a draft Release, `CI (hosted VM)` |
| FZ-MAC4 | Distribution: unsandboxed Developer ID, no Mac App Store (Program §4.4 default). Declining Full Disk Access leaves a working degraded mode through folder grants, mirroring README principle 2. | none | NOV |

### 6.5 Windows (proposed; OQ-9)

| Step | Work and placement | CI file | Done when |
|---|---|---|---|
| FZ-WIN1 | R3 path lint as the first job: no Windows-hostile or case-colliding path was found in `git ls-files` on `main` (185 files) or by `find` in `core/` and `ut/` on PR #19 outside `third_party` (submodule contents not checked); there is no `.gitattributes`, so add one marking byte-exact fixtures `-text`. | `.github/workflows/windows-desktop.yml` | lint green before any other Windows job |
| FZ-WIN2 | Core compiles on `x86_64-pc-windows-msvc` after FZ-C1: `fylz-sniff`, `fylz-ops` (minus Unix Trash), then `fylz-archive` with MSVC builds of libarchive, zlib, bzip2, xz, zstd, lz4 through `build.rs` (a new target shape; untested). | `windows-desktop.yml` | `cargo test` on `windows-2025`: `CI (hosted VM)` |
| FZ-WIN3 | Windows backends in `windows/` and cfg-gated modules: recycle through `IFileOperation` with `FOF_ALLOWUNDO` (restore is journal-recorded; system-bin restore unknown), `GetLogicalDrives`, `ReadDirectoryChangesW`, Credential Manager/DPAPI, registry Open With, long paths and UNC, PDFium or `Windows.Data.Pdf`, Job objects or AppContainer for decoders. Case collisions are already flagged by `PreflightPolicy`. | `windows-desktop.yml` | contract cases pass on a hosted runner; shell behaviour NDV |
| FZ-WIN4 | Qt 6 MSVC shell and packaging (MSIX or MSI plus winget, a choice for OQ-4). Signing: SignPath Foundation (Fylz is public), the Store's free MSIX re-signing, or unsigned; Azure Artifact Signing is unavailable to the owner (Program §4.5). | `.github/workflows/windows-package.yml` (`main`/tags only) | unsigned package on a draft Release; `CI (hosted VM)` |

### 6.6 iOS / iPadOS reframe, "Fylz for Files" (OQ-6)

| Step | Work and placement | CI file | Done when |
|---|---|---|---|
| FZ-IOS1 | Gate: accept the reframe (OQ-6), Apple prerequisites (OQ-2), bundle id row (OQ-25). Its README must call it a document browser, not a port of the Android app (R12). | none | owner decision |
| FZ-IOS2 | Core on iOS in hosted CI: add a third `build.rs` shape (iOS), re-derive `CODESET`, gate Linux Trash. | `.github/workflows/apple-core.yml` | compile-only `CI (hosted VM)` for `aarch64-apple-ios-sim` |
| FZ-IOS3 | New crate `core/crates/fylz-ffi-apple` (uniffi Swift bindings, XCFramework), mirroring `fylz-ffi-android`. | `apple-core.yml` | XCFramework builds on a hosted macOS runner |
| FZ-IOS4 | `apple/` XcodeGen project and SwiftUI shell: `UIDocumentBrowserViewController` or `.fileImporter` with security-scoped bookmarks; rooms, word-wheel rail and scrubber rebuilt per `docs/fonebrew-navigation.md`, shake via CoreMotion; Program F10 XcodeGen/privacy-manifest templates reused, not the KMP recipe. | `.github/workflows/apple-ios.yml` | simulator build with `CODE_SIGNING_ALLOWED=NO`; `SIMULATOR` only |
| FZ-IOS5 | Contracts on iOS: (a) File Provider extension for archives as folders, about 4 weeks, memory budget unknown so spike first; (b) whether isolated decoding has any iOS form is unknown (a spawned decoder subprocess is not expected to be available; unverified here), so write an ADR before (a); (c) recycle: proposed that destructive delete is offered only where a recoverable path exists, otherwise withheld, never silently permanent (contract §3); the effect of Files' "Recently Deleted" auto-expiry on contract item 4 is unknown; (d) OS scan/OCR only if the owner accepts it under D1; (e) scheduled backups dropped. | none | ADR and owner ruling; extension NDV |
| FZ-IOS6 | Distribution: privacy manifest, no telemetry, TestFlight crash-report egress disclosed (OQ-2); signing and upload as disabled templates. | `apple-ios.yml` | NDV/NOV on the iPad Pro M4 |

## 7. Shared foundation this repo consumes or provides

| Item (Program §6) | Fylz role |
|---|---|
| F1 hyle-kmp, tokens | **Consumes** the new `qml` token platform (Hyle's single Style Dictionary pipeline) for M13.3's "Hyle for QML", never by editing the submodule. A KMP `:hyle` changes Fylz's `includeBuild` composite, which is frozen at AGP 8.9.1/Kotlin 2.1.20 (R2): OQ-17 must not break it. Hyle-consumer status is OQ-29 (zero imports today). |
| F2 crash-recovery as KMP | Android coordinate unchanged for the app. The Rust/Qt, Swift and Windows builds need a recovery view of their own; F2's report format is a **spec-only** reuse. OQ-27 decides the toolkit. |
| F3 cell-shell | **Spec-only** reuse (Program F3). Fylz may **provide** a QML fonebrew shell that Foto-Xplorr could share (OQ-8), home undecided. |
| F4, F5, F8, F12 | Not consumed: no asom client, no KMP (R8 exception), no engines pin before the M8 intelligence pack, no web. |
| F6 platform-ports | The Kotlin library cannot be used from a Rust/QML app; Fylz consumes the **tier vocabulary** (hardware, OS keystore, file) and reports it in the UI, implemented per OS in Rust (OQ-22). |
| F7 ubuntu-touch-shell | Fylz's `ut/` is repo-owned. It consumes the OpenStore account and policy, `DEVICE_CHECKLIST_UT.md`, and the click-review checker, which will flag `unconfined`; the Lite variant fits F7's common-groups template. |
| F9 CI matrix, F10 packaging | Consumes the Clickable lane, the Windows path lint, the Flathub offline rule, notarisation and XcodeGen/privacy templates as they appear; until then local workflows (§6). The KMP matrix is not used. |
| F11 evidence | Adopts the §0 labels and maps `PR19:docs/agent/DEVICE_CHECKS.md` to the per-OS checklists. |

Fylz may be asked for: the Rust-core-plus-cxx-qt pattern and the Hyle-to-QML script (Foto-Xplorr, OQ-8); a non-SAF form of the PR #18 workspace
handoff if Fonebrew ports to the same targets; and the D1 OCR engine, shared by every non-Android target.

## 8. Open questions for the owner

1. **Trunk (Program OQ-9).** `main`, PR #19, or PR #14/#15, which reshape identity and operations differently. *Blocks:* every row, the
   placement of this file (FZ-C0), and all step directories. **Proposed D6:** PR #19 carries the ports (it holds the decided Rust core); #14/#15 stay Android-side
   refactors. This plan has not diffed #14 against #19 and does not know how much they collide.
2. **One shell everywhere (OQ-9, part 2).** Does M14's "Qt 6, not Compose Desktop" extend to macOS and Windows (proposed D7), or native SwiftUI/WinUI shells? Kotlin Multiplatform and Compose Multiplatform stay rejected under A-X and Program R8's Fylz exception; this plan does not ask you to reopen them. The program's platform briefs, written before the Fylz exception was recorded, price a Compose Desktop JVM browser for
   macOS and Windows; that is the road M14 declines unless asked. Also where the shared QML lives (`ut/`, a new shared directory, or a shared library; OQ-8, OQ-24). *Blocks:* macOS, Windows, FZ-LX1.
3. **iOS (OQ-6, OQ-2).** Accept "Fylz for Files", or skip iOS. **Proposed D8:** accept it, decided after macOS, with the FZ-IOS5(c) delete rule. *Blocks:* FZ-IOS*, `fylz-ffi-apple`.
4. **Gate custody (OQ-3, OQ-4).** Who holds and when: GATE-UT and the `unconfined` justification, the Apple account and notarisation, the Windows signing route. *Blocks:* release templates (R6), M13.6, FZ-MAC3, FZ-WIN4.
5. **D1 (ML Kit).** Decide now or ship ports without scan/OCR; and whether an OS-provided OCR (Vision, Windows.Media.Ocr) counts as an open replacement. *Blocks:* scan and OCR on every non-Android target.
6. **"Fylz Lite" (content_exchange only) in the first UT release, or later.** *Blocks:* M13.5 scope and a second click identifier (OQ-25).
7. **Remote providers off the JVM.** Port SFTP/SMB/WebDAV/S3 to Rust (M11) before UT/Linux ship, or ship local-only first. *Blocks:* remotes on all non-Android targets.
8. **Sequencing.** The plan says M13 waits for M2–M9; the owner overrode that for M13.1/M13.2. Proceed with M13.3 using honest rename/search/thumbnail stubs, or wait for M7/M8? Also whether FZ-LX1's one-chrome-directory layout and FZ-UT3's refocus behaviour are accepted as M13.3 constraints. *Blocks:* M13.3 scope.
9. **CI budget and homes (OQ-20, OQ-24).** Fylz is public (Program §5), the case in which Program OQ-20 treats five-OS matrices as free; the constraint that remains is Actions storage. Approve Docker-capable, macOS and Windows lanes, and say where the Hyle-to-QML script and a shared QML shell live. *Blocks:* FZ-UT2, FZ-LX2, FZ-MAC2, FZ-WIN1.
10. **Secrets at rest on UT (OQ-22).** Store BYOK keys and remote passwords under an app-held key, or disable BYOK and remotes on UT. **Proposed D9:** not persisted until ruled. *Blocks:* FZ-UT4, F6's tier model.
11. **Toolchain and Hyle (OQ-17, OQ-29).** Leave D3 (Kotlin 2.1.20 vs 2.1.0) alone unless a shared Kotlin module appears, and rule whether Fylz is a Hyle consumer. *Blocks:* F1 consumer lists; Fylz's composite build.
12. **Fonebrew/Workbench alongside Fylz (OQ-7a).** The PR #18 handoff needs a path- or bookmark-based handle off Android. *Blocks:* the handoff on non-Android targets only.
13. **UT device and Waydroid (OQ-1, OQ-21).** Which device, and whether Waydroid is an answer in addition to native UT. *Blocks:* every UT device gate.
14. **Identifiers (OQ-25).** NAMES.md rows for `fylz.mbaliga`, a Flatpak id and domain, bundle ids and an MSIX identity. *Blocks:* the first publishable build on each platform.

Proposed ids D6–D9 continue the reserved-decision table in `PR19:MASTER_PLAN.md` §6 (D1–D5) as placeholders; if accepted they belong there and in
`docs/agent/REVIEW_QUEUE.md`. This plan edits neither.

## 9. Sources read

Read by the reader that produced the profile (2026-10-06), on `main`: `README.md`, `CHANGELOG.md`, `CONTRIBUTING.md`, `SECURITY.md`, `THIRD_PARTY_NOTICES.md`,
`settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `.gitmodules`, `app/build.gradle.kts`, `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`,
`app/src/main/res/xml/shortcuts.xml`, `model/FileEntry.kt`, `storage/StorageProvider.kt`, `ui/theme/`, `ai/ApiKeyVault.kt`, `docs/ARCHITECTURE.md`, `docs/ROADMAP.md`,
`docs/DESIGN.md`, `docs/PRODUCT_BRIEF.md`, `docs/PRODUCT-AND-ARCHITECTURE.md`, `docs/fonebrew-navigation.md`, `docs/product/preview-and-recycle-bin-contract.md`,
`docs/RELEASE.md`, `docs/DEVICE_ACCEPTANCE.md` (headings), `docs/PRODUCT_RESEARCH.md` (headings), `.github/workflows/{android,release-readiness}.yml`.

Re-read directly for this plan: `README.md`, `docs/ROADMAP.md`, `docs/ARCHITECTURE.md` ("Stable identity", "Broad storage access"), `docs/PRODUCT-AND-ARCHITECTURE.md` (§4, §5),
`docs/fonebrew-navigation.md`, `docs/DESIGN.md`, the recycle-bin contract, `app/build.gradle.kts`, `THIRD_PARTY_NOTICES.md`, `.gitmodules`, the workflow file list, and the
measurements of §1.3 and §2 over `main`.

On PR #19's branch (read-only, via `git show` and `git archive`): `docs/agent/MASTER_PLAN.md` (§2, §4, M13, M14, §6), `MASTER_PLAN_ADDENDUM_1.md` (§A, C5, C9, §F),
`ADR-LINUX-UT-STRATEGY.md` (full), `PROGRESS.md` and `REVIEW_QUEUE.md` (heads only), `core/Cargo.toml`, `core/rust-toolchain.toml`, `core/crates/*` (sizes, Unix-seam greps,
`fylz-archive/build.rs` header, `fylz-ops/src/recycle/mod.rs`, `fylz-ffi-qt/Cargo.toml` and `qml/main.qml`), `ut/*`, `.github/workflows/android.yml`.

Program inputs: `Personal-Tracker/PORTING_PROGRAM.md` (§0–§3, §4.1–§4.6, the §5 row, §6, §7, §8), the reader profile, and the Fylz entries and platform facts in the program
briefs `Personal-Tracker/porting/platforms/{ios,macos,windows,linux,ubuntu-touch,framework-strategy}.md`. GitHub REST `pulls` listing for PR states. The submodules
`hyle-design-system` and `shared-libraries` are not checked out locally and were not read.
