# Fylz on desktop Linux (Qt shell, .deb + AppImage)

Owner request 2026-11-17 puts desktop Linux in scope. This is a **native** build of the same Qt
shell as Ubuntu Touch (`core/crates/fylz-ffi-qt`, `qml/Shell.qml`) -- not the Android app, and not
a compatibility layer. It is **early and read-only**: folder browse, archive viewer (zip/tar/7z...
via libarchive), operation-journal count. No copy/move/delete/rename UI, no rooms IA, no Quick Look,
no thumbnails, no Trash UI. The desktop-class features in the plan (udisks2, Secret Service,
seccomp decoder subprocess, remote providers) are NOT built.

## What exists

| Piece | Where | Evidence |
|---|---|---|
| Shell is now a real top-level `Window` (an `Item` root is created but never shown by `QQmlApplicationEngine`; the old offscreen smoke could not see that) | `qml/Shell.qml`; smoke asserts `FYLZ_SHELL_WINDOW visible=true` | BUILT-HERE (Qt 6.4.2); shows-on-screen NEEDS-DEVICE-VALIDATION |
| Release build `fylz` against **Qt 6.4.2** (Ubuntu 24.04), cxx-qt 0.7.3 | `cargo build --release --bins` with `QT_VERSION_MAJOR=6 QMAKE=/usr/lib/qt6/bin/qmake` | BUILT-HERE |
| Offscreen smoke of the release binary (real folder, real zip, real journal) | `ut/smoke.sh` | BUILT-HERE: `SMOKE OK` |
| `cargo test` for the Qt crate (14 tests), `cargo fmt --check` | `core/crates/fylz-ffi-qt` | BUILT-HERE (run under the default Qt auto-pick) |
| `.deb` (unsigned, `dpkg-deb`; `Depends:` generated from `ldd` + `dpkg -S`) | `linux/packaging/build-deb.sh` | BUILT-HERE (amd64): built, inspected, extracted, extracted binary passes the smoke. NOT installed with apt/dpkg here |
| Desktop entry, AppStream metainfo, SVG icon | `linux/io.github.mbaliga.Fylz.*` | BUILT-HERE: `desktop-file-validate` clean, `appstreamcli validate --no-net` passes (one pedantic note) |
| AppImage (linuxdeploy + Qt plugin, pinned + sha256-verified; sums taken from real downloads) | `linux/packaging/build-appimage.sh` | CODE-ONLY (script syntax checked; never run: needs the Qt dev packages + AppImage tooling) |
| CI lane: metadata, build, smoke, deb, install-the-deb-and-smoke, AppImage + smoke, tag-only upload; arm64 leg; informational Qt 5 probe | `.github/workflows/linux-desktop.yml` | CI-LANE-WRITTEN (YAML parses; no run seen) |

## Why Qt 6 for the desktop build, and the Qt 5 finding

The plan (M14) expected Qt 5.15 first (proven for cxx-qt 0.7.3, and what Ubuntu Touch needs) with Qt 6
a later, unverified migration. While building this lane that ordering turned out to be **inverted
in this container**:

* `cxx-qt-build` prefers `qmake6` whenever it is installed, so an unpinned build here silently
  used Qt 6.4.2. The earlier "runs on Qt 5.15.13" claims for the shell may therefore have been
  produced by a Qt 6 build, or by a Qt 5 build before Qt 6 dev packages were installed; the
  record does not say, so treat them as unconfirmed. Builds are now pinned explicitly in every
  workflow (`QT_VERSION_MAJOR` + `QMAKE`).
* With Qt **5.15.13 pinned** (cfg `cxxqt_qt_version_major="5"`, links `libQt5*`, 0 `libQt6` links)
  the `fylz` and `fylz-qml-demo` binaries compile and link but **abort at start-up** with
  `*** stack smashing detected ***` inside `QVector<QByteArray>::append`, called from
  `cxx_qt_lib::QGuiApplication::new` (gdb backtrace, debug and release). Same sources against Qt 6.4.2
  run and pass the smoke. Root cause is **not** found (not checked: toolchain flags such as the
  distro's default hardening, or a cxx-qt-lib 0.7.3 Qt 5 `QVector` layout issue). The Ubuntu
  Touch click build uses Qt 5, so **this is a risk to the UT port** until a Qt 5 run is green:
  the `qt5-probe` job (and `ubuntu-touch.yml`'s `qt-shell`) will show it on the runner image.
* Consequence taken: the Linux desktop package is Qt 6 (the version in Ubuntu 24.04's archive, no
  PPA). It declares `qml6-module-qtquick` / `qml6-module-qtquick-window`. QML imports use the
  `2.15` versions, which Qt 6 accepts.

The deb `Depends:` lists only what the binary links; it also pulls `libqt6network6` and, through
it, curl/ssh libraries, because cxx-qt-lib links QtNetwork. The app opens **no** network connections
(binding rule: no telemetry, no phoning home); there is simply no network code to remove.

## Build it yourself (Ubuntu 24.04)

```sh
git submodule update --init --depth 1 core/third_party/libarchive core/third_party/zlib \
  core/third_party/bzip2 core/third_party/xz core/third_party/zstd core/third_party/lz4
sudo apt-get install build-essential cmake pkg-config qt6-base-dev qt6-declarative-dev \
  qt6-declarative-dev-tools qml6-module-qtquick qml6-module-qtquick-window libgl1-mesa-dev libxkbcommon-dev
export QT_VERSION_MAJOR=6 QMAKE=/usr/lib/qt6/bin/qmake
(cd core/crates/fylz-ffi-qt && cargo build --release --bins)
FYLZ_BIN_DIR=$PWD/core/crates/fylz-ffi-qt/target/release ut/smoke.sh      # headless
linux/packaging/build-deb.sh core/crates/fylz-ffi-qt/target/release/fylz dist
sudo apt-get install ./dist/fylz_0.1.0_amd64.deb && fylz
```

Disk note: a release build needs about 1 GB under `core/crates/fylz-ffi-qt/target/` (git-ignored).

## Not done (and why)

* Flatpak: needs vendored crates for an offline build plus `--filesystem=host` justification and
  Flathub review; skipped, not attempted. Snap: not attempted.
* Qt 6 for Ubuntu Touch / macOS / Windows: out of scope here.
* Desktop layouts (this window is a phone-shaped list that stretches), keyboard navigation, drag and drop.
* The app-id `io.github.mbaliga.Fylz` is a proposal, **owner follow-up**: confirm it (and a
  `NAMES.md` row in the constellation repo) before any store listing. The Debian maintainer line
  uses a `users.noreply` address as a placeholder.
* Signing: none. Debs/AppImages are unsigned; any signing key must live in a GitHub environment
  secret behind approval, never in the repo.

Owner checklist: `docs/linux/OWNER_VERIFY.md`.
