//! Builds the cxx-qt bridge for real when Qt is actually available on this machine (checked via
//! `qmake` on `PATH` -- the same signal `docs/agent/ADR-LINUX-UT-STRATEGY.md` used to confirm
//! this sandbox has real Qt 5.15, re-verified independently for this M13.2 dispatch), compiling
//! every `#[qml_element]` QObject under `src/` into the `com.fylz.demo` QML module described by
//! `qml/main.qml`.
//!
//! **On a machine with no Qt at all** -- notably `.github/workflows/android.yml`'s existing CI
//! runner, a plain `ubuntu-latest` GitHub Actions image with no Qt packages installed, which this
//! task's own brief says stays untouched -- this script does nothing beyond leaving the
//! `fylz_qt_available` cfg unset. `src/lib.rs` gates every Qt-touching module on that cfg, so the
//! whole crate compiles as an inert placeholder there instead of panicking the workspace build:
//! `cxx-qt-build`'s own Qt discovery (only ever called from inside [build_bridge]) would
//! otherwise call `qmake`/CMake and panic outright on a machine that has neither.

fn main() {
    // Rust 1.80+ lints an unrecognised `#[cfg(...)]` unless the build script first declares every
    // name it might emit -- declared unconditionally so both branches below stay warning-free.
    println!("cargo::rustc-check-cfg=cfg(fylz_qt_available)");

    if qt_is_available() {
        println!("cargo::rustc-cfg=fylz_qt_available");
        build_bridge();
    } else {
        println!(
            "cargo:warning=fylz-ffi-qt: no usable `qmake` (the QMAKE variable, else `qmake` on PATH), so Qt could not be located; \
             compiling this crate as an inert placeholder instead (see \
             docs/agent/ADR-LINUX-UT-STRATEGY.md for what that means and why)."
        );
    }
}

/// Cheap and deliberately dependency-free: this is checked BEFORE `cxx_qt_build` is touched at
/// all, so a Qt-less machine never runs any of its Qt-discovery code (which panics when it finds
/// nothing) in the first place.
fn qt_is_available() -> bool {
    // `QMAKE` is tried first: cxx-qt-build reads the same variable, and distributions that ship only Qt 6 name the
    // binary `qmake6`, so a bare `qmake` on PATH is not a reliable signal there (it made the arm64 CI leg build
    // this crate as an inert placeholder even though Qt 6 was installed). A set-but-unusable QMAKE falls through
    // to `qmake` on PATH, so this can only find Qt in more places than before, never fewer.
    println!("cargo:rerun-if-env-changed=QMAKE");
    let from_env = std::env::var("QMAKE").ok().filter(|q| !q.is_empty());
    from_env
        .iter()
        .map(String::as_str)
        .chain(std::iter::once("qmake"))
        .any(|qmake| {
            std::process::Command::new(qmake)
                .arg("-v")
                .output()
                .map(|output| output.status.success())
                .unwrap_or(false)
        })
}

fn build_bridge() {
    use cxx_qt_build::CxxQtBuilder;
    use cxx_qt_build::QmlModule;

    CxxQtBuilder::new()
        .qml_module(QmlModule {
            uri: "com.fylz.demo",
            rust_files: &[
                "src/folder_model.rs",
                "src/archive_model.rs",
                "src/operation_queue.rs",
                "src/preview_provider.rs",
                "src/rename_controller.rs",
                "src/search_controller.rs",
            ],
            qml_files: &["qml/main.qml", "qml/Shell.qml"],
            ..Default::default()
        })
        .build();
}
