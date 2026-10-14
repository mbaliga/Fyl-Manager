//! cxx-qt bridge exposing `fylz-ops`, `fylz-archive` and `fylz-sniff` to QML for the Ubuntu
//! Touch and desktop Linux app (`docs/agent/MASTER_PLAN.md` M13.2). See
//! `docs/agent/ADR-LINUX-UT-STRATEGY.md` for the sandbox-verification record this crate builds
//! on and its own table of which `QObject` below is real and which is a stub; this doc comment
//! restates the same verdicts in this crate's own words, kept in sync with that table rather than
//! duplicating its reasoning.
//!
//! ## Real vs. stubbed
//!
//! - [`folder_model`], [`archive_model`] and [`operation_queue`] are **real**: each is a thin
//!   `QAbstractListModel` wrapper around a plain-Rust function in [`logic`] (unit-tested in
//!   `logic.rs`'s own `#[cfg(test)]` module -- Qt-free, so those tests run in `cargo test` on any
//!   machine) that calls straight into `fylz-ops` (`preflight`, `staging`, `journal::Journal`) and
//!   `fylz-archive` (`inspect`), the same real, already-tested engines M3 and M13.1 built.
//! - [`preview_provider`] is a **documented partial stub**: it classifies a file's real content
//!   via `fylz-sniff` (M2.5, done -- genuine magic-byte sniffing, not a guess from the file
//!   extension), but it does not decode or render actual thumbnail pixels. A real
//!   `QQuickImageProvider` is a plain (non-`QObject`) abstract C++ class outside cxx-qt's
//!   supported subclassing surface (which targets `QObject`-derived bases such as
//!   `QAbstractListModel`, not `QQuickImageProvider`); wiring a hand-written C++ image provider on
//!   top of this bridge is real, in-scope future work, not attempted here. Said plainly, per this
//!   task's own brief, rather than silently skipped.
//! - [`rename_controller`] and [`search_controller`] are **honest stubs**: `fylz-rename` (M7) and
//!   `fylz-query`/`fylz-index` (M8) are still one-line stub crates with no real logic behind them
//!   (`docs/agent/ADR-LINUX-UT-STRATEGY.md` §1), so every operation these two `QObject`s expose
//!   returns a clearly labelled "not implemented yet" string. Their `QObject` shape (property and
//!   invokable names QML code can already compile against) is real; the behaviour behind it is
//!   not, and says so in the string it returns rather than fabricating rename or search results.
//!
//! ## Why this crate compiles with no Qt installed at all
//!
//! `build.rs` only calls into `cxx-qt-build` -- which needs `qmake`/Qt 5.15 to succeed -- when it
//! can actually find Qt on `PATH`; otherwise it leaves the `fylz_qt_available` cfg unset. Every
//! module below that touches Qt is gated on that cfg, with nothing at all standing in for it
//! otherwise (not a stub module -- literally not compiled, so no `#[cxx_qt::bridge]` macro ever
//! runs and no C++ is ever referenced). That means this crate joining `core/Cargo.toml`'s
//! workspace members cannot break `cargo build`/`test --workspace` on a machine without Qt, such
//! as `.github/workflows/android.yml`'s existing CI runner -- out of this task's stated scope, and
//! left exactly as it was.
//!
//! [`logic`] itself has no such gate: it is plain, Qt-free Rust, so its own tests always run.

pub mod logic;

#[cfg(fylz_qt_available)]
mod archive_model;
#[cfg(fylz_qt_available)]
mod folder_model;
#[cfg(fylz_qt_available)]
mod operation_queue;
#[cfg(fylz_qt_available)]
mod preview_provider;
#[cfg(fylz_qt_available)]
mod rename_controller;
#[cfg(fylz_qt_available)]
mod search_controller;

#[cfg(fylz_qt_available)]
pub use archive_model::qobject::ArchiveModel;
#[cfg(fylz_qt_available)]
pub use folder_model::qobject::FolderModel;
#[cfg(fylz_qt_available)]
pub use operation_queue::qobject::OperationQueue;
#[cfg(fylz_qt_available)]
pub use preview_provider::qobject::PreviewProvider;
#[cfg(fylz_qt_available)]
pub use rename_controller::qobject::RenameController;
#[cfg(fylz_qt_available)]
pub use search_controller::qobject::SearchController;

/// `true` only when `build.rs` could not find Qt at all (no `qmake` on `PATH`) and therefore
/// compiled every `*_model`/`*_controller`/`preview_provider` module out of this crate entirely.
/// The one place calling code can check for that condition without depending on `cfg` itself.
#[cfg(not(fylz_qt_available))]
pub const QT_UNAVAILABLE_AT_BUILD_TIME: bool = true;

/// `false` when Qt was found and every `QObject` module above is really compiled in.
#[cfg(fylz_qt_available)]
pub const QT_UNAVAILABLE_AT_BUILD_TIME: bool = false;

/// Runs the QML end-to-end proof screen (`qml/main.qml`) to completion and returns the process
/// exit code `src/bin/qml_demo.rs` should use.
///
/// Deliberately lives here rather than in `src/bin/qml_demo.rs` itself: `cxx-qt-build`'s Qt/C++
/// link flags (`build.rs`) apply to every target in this package, including both binaries, but
/// the compiled Rust definitions of each `QObject`'s `extern "Rust"` glue (`create_rs_*`,
/// property getters/setters, invokable trampolines -- the symbols the generated C++ actually
/// calls) live only in whichever crate's own object code contains `mod folder_model;` and its
/// siblings, which is this library, not a `[[bin]]`'s own separate crate root. A binary that
/// never references this library at all is never linked against its `.rlib`, so those symbols
/// would stay undefined at link time even though the C++ side needs them -- calling a real
/// function here from `src/bin/qml_demo.rs` is what makes cargo actually link this crate into
/// that binary.
#[cfg(fylz_qt_available)]
pub fn run_qml_demo() -> i32 {
    use cxx_qt_lib::QGuiApplication;
    use cxx_qt_lib::QQmlApplicationEngine;
    use cxx_qt_lib::QUrl;

    let mut app = QGuiApplication::new();
    let mut engine = QQmlApplicationEngine::new();

    if let Some(engine) = engine.as_mut() {
        // The resource path `cxx-qt-build`'s `qml_module` registers `qml/main.qml` under, for
        // the `com.fylz.demo` 1.0 URI `build.rs` declares.
        engine.load(&QUrl::from("qrc:/qt/qml/com/fylz/demo/qml/main.qml"));
    }

    match app.as_mut() {
        Some(app) => app.exec(),
        None => {
            eprintln!("fylz-qml-demo: QGuiApplication::new() returned null");
            1
        }
    }
}

/// The `fylz_qt_available` cfg is unset (see this module's own doc comment): nothing Qt-shaped
/// to run.
#[cfg(not(fylz_qt_available))]
pub fn run_qml_demo() -> i32 {
    eprintln!(
        "fylz-qml-demo: Qt was not found when fylz-ffi-qt was built (see \
         docs/agent/ADR-LINUX-UT-STRATEGY.md for what that means); nothing to run."
    );
    0
}
