//! The real Fylz app for Ubuntu Touch and desktop Linux: a one-line wrapper over
//! `fylz_ffi_qt::run_app`, which loads `qml/Shell.qml`. The body lives in the library crate for
//! the same linking reason `src/bin/qml_demo.rs` documents. Pass `--smoke` (with
//! `QT_QPA_PLATFORM=offscreen`) to log what the shell shows and quit; that is what CI runs.

fn main() {
    std::process::exit(fylz_ffi_qt::run_app());
}
