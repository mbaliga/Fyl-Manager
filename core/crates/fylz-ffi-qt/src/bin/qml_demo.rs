//! The QML end-to-end proof harness (M13.2/M13.3, `docs/agent/ADR-LINUX-UT-STRATEGY.md`): runs
//! `fylz_ffi_qt::run_qml_demo`, which loads `qml/main.qml` against the real folder/archive/
//! journal `fylz-seed-demo` created. Run it under Qt's own offscreen QPA platform plugin to need
//! no real display (`QT_QPA_PLATFORM=offscreen`, set by whoever runs this, not by this binary).
//!
//! This binary is deliberately a one-line wrapper: see `run_qml_demo`'s own doc comment in
//! `src/lib.rs` for why the real body has to live in the library crate rather than here.

fn main() {
    std::process::exit(fylz_ffi_qt::run_qml_demo());
}
