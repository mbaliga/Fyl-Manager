# Fylz for Files (iOS / iPadOS)

Status: **implemented as far as the Linux container allows; nothing has run on an Apple platform.**
Evidence labels used below: BUILT-HERE (run in the Linux container and seen to pass), CI-LANE-WRITTEN
(a workflow exists, no run seen), CODE-ONLY (written, never compiled), NEEDS-DEVICE-VALIDATION
(only the owner can verify).

## 1. What this is, and what it is not

Owner request 2026-11-17 overrides the plan's earlier "no-port" proposal for iOS. The plan's
recorded ruling still binds: **native ports only, and a reframe is labelled as one.** iOS does not
let an app manage the device's files (no `MANAGE_EXTERNAL_STORAGE`, no SAF, no arbitrary file
tree), so the Android file manager cannot exist there. What exists is **Fylz for Files**: a SwiftUI
document browser for archives, running the same Rust engine (`fylz-archive`, `fylz-sniff`) as the
Android app. It is **not** the Fylz file manager and **not a port of the Android app** (plan R12);
its README and About screen say so.

Scope of v0.1: open an archive the user picks (or "Open in Fylz" from Files/Mail/Safari), identify it
by content, list it as a browsable tree with the extraction policy's verdict shown up front, and
extract it into `On My iPhone / Fylz / Extracted /<name>`. Out of scope on purpose: creating
archives, remote locations, search/index, rename/organise/clean/verify (the Rust crates for those
are still stubs), scan/OCR (plan D1), scheduled backups (FZ-IOS5(e): dropped), the Android rooms /
word-wheel navigation (FZ-IOS4 asks for it; it is not built, see section 9).

## 2. Layout

| Path | What | Evidence |
|---|---|---|
| `core/crates/fylz-archive/build.rs` | third `build.rs` shape for `*-apple-ios*` ([`ios_cmake_settings`]) | pure function BUILT-HERE (scratch harness); the CMake build itself CI-LANE-WRITTEN |
| `core/crates/fylz-archive/src/write.rs` | Darwin `CODESET`=0, `LC_CTYPE_MASK`=1<<1, locale-name fallbacks | type-checked BUILT-HERE for `aarch64-apple-ios`, `-ios-sim`, `-darwin`; behaviour NOT run |
| `core/crates/fylz-ops/src/recycle/mod.rs` | freedesktop Trash gated off Apple targets | `cargo check` BUILT-HERE for `aarch64-apple-ios`; Linux tests still 287 pass |
| `core/crates/fylz-ffi-apple/` | uniffi crate: `fylz_version`, `sniff_path`, `archive_inspect`, `archive_extract_all`, `archive_default_limits` | 15 host tests BUILT-HERE (Linux); not built for iOS |
| `apple/FylzKit/` | pure-Swift logic: tree builder, byte formatting, folder naming, failure text, `ArchiveEngine` seam | CODE-ONLY (no Swift toolchain ran, see section 7) |
| `apple/FylzApp/` | SwiftUI shell, `CoreEngine` adapter, privacy manifest | CODE-ONLY |
| `apple/project.yml`, `apple/Config/Bundle.xcconfig` | XcodeGen spec; the single place the bundle id lives | CODE-ONLY (YAML parses) |
| `apple/scripts/build-xcframework.sh` | cargo for 2 iOS targets, uniffi Swift bindings, `xcodebuild -create-xcframework` | bash syntax BUILT-HERE; the binding-generation half ran on Linux; the rest CI-LANE-WRITTEN |
| `.github/workflows/apple-ios.yml` | linux-core, macos-core, swift-kit, ios-app, testflight (disabled) | YAML parses; CI-LANE-WRITTEN |

## 3. Core seam (FZ-C1 as far as iOS needs it)

* **`CODESET` / locale.** `write.rs` hard-coded glibc's `14`. Darwin's `<langinfo.h>` defines
  `CODESET` as `0`, and its `LC_CTYPE_MASK` is `1 << 1` (Linux/bionic: `1`). Darwin also has no
  `C.UTF-8` locale, so the pin now tries `["UTF-8", "en_US.UTF-8", "C.UTF-8"]` on Apple and still
  *verifies* `nl_langinfo(CODESET) == "UTF-8"` before trusting any of them. The constants come
  from Apple's headers as I know them; the macOS lane is what proves them, and the iOS values
  are NEEDS-DEVICE-VALIDATION (whether iOS ships a locale the pin can use is unknown; if it
  does not, creating archives fails with a clear `Unsupported`, while reading/extracting is
  unaffected, because only the *writer* uses the pin).
* **Trash.** The freedesktop backend is `#[cfg(all(unix, not(target_vendor = "apple")))]`. iOS has
  no Trash (section 5); macOS will get its own backend later.
* **`RawFd` API kept.** The plan proposed a `File`-based input beside `RawFd`. iOS is Unix, and
  Swift can hand the engine a path that `fylz-ffi-apple` opens itself, so the `RawFd` surface (and
  therefore the Android uniffi surface) is untouched. A handle-based input is still needed for
  Windows, not here.
* **iOS CMake shape.** `cmake` 0.1.58 already sets `CMAKE_SYSTEM_NAME=iOS` and the cc flags for
  these targets. `build.rs` additionally pins SDK (`iphoneos`/`iphonesimulator`), architecture and
  deployment target (default 16.0, `IPHONEOS_DEPLOYMENT_TARGET`), and forces libarchive's
  `HAVE_FORK/VFORK/POSIX_SPAWN/POSIX_SPAWNP` probes to absent, so `filter_fork_posix.c` compiles
  to its stub: the external-program filters are unavailable *by construction* (iOS has no
  subprocess). `x86_64-apple-ios` (Intel simulator) is handled by `ios_cmake_settings` but is not
  built by the lane (Apple-silicon runners only).

## 4. Isolation (FZ-IOS5(b)) -- decision recorded, owner may overrule

On Android the decoder runs in a separate process with a memory cap (`:decoders`). iOS has no
`fork`/`exec` for third-party apps and an app extension is a poor substitute (tight memory budget,
lifecycle owned by the system). Decision for v0.1: **decode in-process**, on a detached task, under
the policy and runtime limits the engine already enforces (`Limits::default()`: 10,000 entries,
2 GiB archive, 1 GiB per file, 4 GiB total, 200:1 ratio, depth 64, name 255), which stop an archive
bomb at the byte level, not just at its headers. What this does **not** give: containment of a
libarchive memory-safety bug (it would be inside the app process). That residual risk is stated in
the About screen and here. If the owner wants more, the options are an XPC-less File Provider/Share
extension per archive (separate process, ~tens of MB budget, unproven for libarchive) or refusing
the riskiest formats; both need a spike on a device first.

## 5. Delete / recycle (FZ-IOS5(c))

The proposed rule is adopted: **v0.1 never deletes anything**, so there is nothing to recycle and
nothing can be silently permanent. Extracted folders live in the app's Documents folder, shared via
`UIFileSharingEnabled` and `LSSupportsOpeningDocumentsInPlace`, so the user removes them in the
Files app, where "Recently Deleted" applies. How Files' auto-expiry interacts with the plan's
contract item 4 is unknown and not tested.

## 6. File access

`.fileImporter` (also `.onOpenURL` for "Open in") yields a security-scoped URL. `BrowserModel`
calls `startAccessingSecurityScopedResource()` when an archive opens and stops it on close: the
engine reads the *original* file (it never copies the archive into the app). Bookmarks for "recent
archives" are not built; the picker is the only way back to a file. `UIDocumentBrowserViewController`
was not chosen: `.fileImporter` is the smaller surface and gives the same scoped URL; revisit if the
owner wants a document-browser-style launcher.

## 7. Tooling reality

* Swift for Linux (6.0.3) was downloadable through the proxy (783 MB); running the downloaded
  toolchain was blocked by this session's permission classifier, and no workaround was attempted.
  Therefore **no Swift code has been compiled**: `FylzKit` and `FylzApp` are CODE-ONLY. The
  uniffi-generated `FylzCore.swift` (1,450 lines) was generated on Linux from the static library,
  and `CoreEngine.swift` was written against its real case names (`FylzError.Io`, ...), which is the
  part most likely to drift.
* `FylzKit` deliberately imports neither SwiftUI nor FylzCore so the `swift-kit` job (and a future
  Linux Swift job) can test it. It carries 15 XCTest cases, never run.

## 8. Distribution and signing (FZ-IOS6)

Bundle id **`com.asoc.fylz`** is set in exactly one place: `apple/Config/Bundle.xcconfig`
(`FYLZ_BUNDLE_ID`). Plan rule R11 wants a `NAMES.md` row first; that file lives outside this repo,
so it is an owner follow-up. No signing identity, profile, team id or key is in the repo; CI builds
with `CODE_SIGNING_ALLOWED=NO`. The `testflight` job is `if: false` and documents the secrets
(App Store Connect API key, distribution certificate, profile) the owner would put in an
`apple-release` GitHub environment with required reviewers. The privacy manifest declares no
tracking, no collected data, and the file-timestamp API reasons (3B52.1, C617.1); a disk-space
declaration may be demanded by App Store Connect on first upload (libarchive/Rust `statfs`), which
cannot be known here.

**Telemetry:** none. The app has no network entitlement use and no analytics. The only
crash-report path is Apple's own TestFlight/App Store collection, which the user controls in iOS
Settings; it is named in `apple/README.md` and the About screen. This is the single approved
exception to the no-telemetry rule.

## 9. Not built, on purpose

* File Provider extension exposing archives as folders (FZ-IOS5(a)): needs the isolation spike and
  a device; ADR first. Not started.
* The Android rooms / word-wheel / scrubber navigation (FZ-IOS4): a standard `NavigationStack` list
  is used. Rebuilding the fonebrew pattern in SwiftUI is a later, owner-visible design task.
* Archive creation (the writer exists in the core and the locale pin is ported, but no UI).
* iPad-specific layout beyond what SwiftUI gives for free (`TARGETED_DEVICE_FAMILY` is `1,2`).
* A unit-test target in the Xcode project (FylzKit's tests run through `swift test`).
