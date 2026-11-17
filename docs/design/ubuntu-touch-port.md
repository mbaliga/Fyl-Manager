# Fylz on Ubuntu Touch (Qt 5.15 + Rust core)

> **Correction 2026-11-17 (Linux stage):** this record says the shell was proven on Qt 5.15.13. A later
> check could not reproduce that: with Qt 5.15.13 explicitly pinned the `fylz` binary aborts at
> start (`stack smashing detected` in cxx-qt-lib's `QGuiApplication::new`), while the same sources
> pass the smoke on Qt 6.4.2; and `cxx-qt-build` silently prefers `qmake6` when installed, so
> earlier runs may have been Qt 6. Treat the Qt 5 claims below as UNCONFIRMED until a Qt 5 run is
> green. Also fixed there: the shell root is now a `Window` (it was an `Item`, which is never
> shown). See `docs/linux/README.md`.

Status date: 2026-11-17. This is a native port: a Qt Quick shell over the same Rust core the
Android app uses (`fylz-archive`, `fylz-ops`, `fylz-sniff`). It is not Waydroid and not a wrapper
of the Android app. Owner request 2026-11-17 puts Ubuntu Touch in scope; the plan's earlier
"follows" verdict is overridden. Evidence classes: BUILT-HERE, CI-LANE-WRITTEN, CODE-ONLY,
NEEDS-DEVICE-VALIDATION.

## 1. What exists

| Piece | Where | Evidence |
|---|---|---|
| cxx-qt 0.7.3 bridge: `FolderModel`, `ArchiveModel`, `OperationQueue` (real); `PreviewProvider` (classifies via `fylz-sniff`, no pixels); `RenameController`, `SearchController` (honest "not implemented" stubs) | `core/crates/fylz-ffi-qt/src/` | BUILT-HERE (Qt 5.15.13, g++ 13.3) |
| Qt-free logic (`scan_folder`, `scan_archive`, `list_operations`, plus navigation helpers `parent_dir`, `child_path`, `human_size`, `is_archive_name`, `start_folder_from`) | `src/logic.rs` | BUILT-HERE: 14 unit tests pass |
| **The app binary `fylz`** and its QML shell (folder browser, tap-to-descend, Up, archive viewer, journal count) | `src/bin/fylz.rs`, `qml/Shell.qml` | BUILT-HERE builds and runs offscreen; looks/feel NEEDS-DEVICE-VALIDATION |
| Offscreen smoke asserting real values (7 rows, name clash, staging flag, ZIP inspect 48 entries, descend/ascend) | `ut/smoke.sh` | BUILT-HERE: `SMOKE OK` |
| Click packaging: `clickable.yaml` (repo root), `ut/manifest.json`, `ut/fylz.apparmor` (unconfined), `ut/fylz.desktop` | repo | CODE-ONLY: Clickable 8.10.0 parses the config and proceeds to the Docker step (BUILT-HERE, stops there: no Docker daemon); the click itself was never built |
| CI lanes: `qt-shell` (fmt, clippy, test, qmllint, build, smoke) and `click` (arm64 + amd64, review, tag-only upload) | `.github/workflows/ubuntu-touch.yml` | CI-LANE-WRITTEN (yaml parses; action SHAs resolved with `git ls-remote`) |

## 2. Why this approach (record)

- Rust core + cxx-qt + QML was already the recorded decision (`docs/agent/ADR-LINUX-UT-STRATEGY.md`,
  plan 6.2) and was proven end to end in a sandbox on Qt 5.15; this stage re-proved it and added the
  missing app binary rather than switching stacks.
- The shell imports only `QtQuick 2.15`, not Lomiri Components. Reason: it then loads on any
  Qt 5.15 install (CI, desktop Linux) with no extra QML module, so the smoke test is meaningful.
  Cost: it does not yet look native on UT. Moving to Lomiri Components / a Hyle-for-QML theme is
  a follow-up, not a rewrite (the models and logic do not change).
- The Qt crate stays **outside** the core cargo workspace (cxx-qt's build script panics without Qt;
  see `core/Cargo.toml`). Its gates are therefore separate commands (section 3), run by the
  `qt-shell` job.

## 3. Gate (run from `core/crates/fylz-ffi-qt`)

    cargo fmt --check && cargo clippy --all-targets -- -D warnings && cargo test
    qmllint qml/Shell.qml qml/main.qml
    cargo build && ../../../ut/smoke.sh

Plus the unchanged core gate in `core/`: `cargo test --workspace` (BUILT-HERE, all green).

## 4. What the Clickable check did and did not prove

Read from clickable-ut 8.10.0's own source (a wheel downloaded from PyPI and read, not run as
code beyond `clickable build`):

- The Rust builder runs `cargo install --locked --root <install>/lib/<triplet> --path <src_dir>`
  and then appends `build_args`. The old `specific_output_bin:` key does not exist in Clickable;
  it was removed and replaced with `build_args: [--bin, fylz]`, otherwise the demo binaries would
  also be installed.
- The Rust builder copies no packaging files, so `install_root_data` now lists
  `manifest.json`, `fylz.apparmor` and `fylz.desktop`.
- The binary lands in `lib/<triplet>/bin/`, which Clickable's own gdbserver `PATH` shows is on the
  app's PATH on device; that is why `Exec=fylz` (bare name) is expected to work. This is an
  inference from Clickable's source; NEEDS-DEVICE-VALIDATION.
- `clickable.yaml` moved from `ut/` to the repo root: Clickable mounts `root_dir` (the directory
  holding the file) into its container, and the crate's path dependencies and libarchive
  submodules live outside `ut/`.
- Not proven: that `cxx-qt-build` finds `qmake` inside the arm64 cross container, that CMake builds
  libarchive for aarch64 there, or that `cargo` in the image accepts `core/rust-toolchain.toml`.
  Those are the first things to look at if the `click` job fails.

## 5. Not done (honest list)

- No click was built; no device run. Nothing here has touched a phone.
- Shell is minimal: no rooms IA, edge scrubber, Quick Look, selection, copy/move/delete UI, rename,
  search (`fylz-rename`, `fylz-query`, `fylz-index` are still stub crates), thumbnails (needs a C++
  `QQuickImageProvider`), remote clients, or Hyle theme.
- The operation journal path for the real app is not wired: `OperationQueue` still reads
  `FYLZ_DEMO_JOURNAL`, so the footer shows 0 operations outside the proof harness.
- Unconfined click (needs filesystem-wide access); a confined "Fylz Lite" is later work.
- No key storage: the owner ruled an app-private file with the weaker guarantee shown in the UI.
- Linux desktop: the same shell runs on desktop Qt 5.15 (that is what the smoke job proves), but
  there is no deb/AppImage/Flatpak packaging in this stage.
- Manifest maintainer is a `users.noreply` address and the app id `fylz.mbaliga` is unchecked:
  owner follow-ups (`ut/OWNER_VERIFY.md`).
