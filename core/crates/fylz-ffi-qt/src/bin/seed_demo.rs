//! Seeds a real, self-contained fixture that `fylz-qml-demo` (`src/bin/qml_demo.rs`) reads back
//! as a genuinely SEPARATE OS process -- the whole point being that
//! `fylz_ops::journal::Journal::open`'s reconcile-after-process-death sweep only fires for a
//! second, different process, never a second `Journal::open` call inside the same one
//! (`journal.rs`'s own doc comment). Prints `export FYLZ_DEMO_*=...` lines to stdout for a shell
//! to `eval` before launching `fylz-qml-demo`.
//!
//! Deliberately does NOT clean up what it creates: `fylz-qml-demo`, started afterward as a
//! separate process, needs it still on disk. Whoever runs this is responsible for removing the
//! printed `FYLZ_DEMO_BASE` directory once done.

use std::fs;
use std::path::PathBuf;

use fylz_ops::FileOperation;
use fylz_ops::FileOperationType;
use fylz_ops::Journal;
use fylz_ops::OperationItem;
use fylz_ops::OperationState;

fn main() {
    let base = std::env::temp_dir().join(format!(
        "fylz-qml-demo-{}-{}",
        std::process::id(),
        now_millis()
    ));
    let folder = base.join("folder");
    fs::create_dir_all(&folder).expect("create the demo folder");

    // Real files and subfolders (this task's own brief: "a real temp directory with real
    // files/subfolders you create for the test").
    fs::write(
        folder.join("readme.txt"),
        b"hello from the fylz-ffi-qt demo\n",
    )
    .unwrap();
    fs::write(folder.join("Report.txt"), b"one\n").unwrap();
    // Same name, different case: a real `fylz_ops::preflight` case-insensitive NameCollision.
    fs::write(folder.join("report.TXT"), b"two\n").unwrap();
    fs::create_dir_all(folder.join("photos")).unwrap();
    fs::write(folder.join("photos").join("holiday.bin"), vec![0u8; 4096]).unwrap();
    // A real `.fylz-part-*` staged write (`fylz_ops::staging`), left behind exactly as a dead
    // process's own in-flight copy would leave one.
    let staged_name = fylz_ops::staging::staging_name("demo-op", 0, "incoming.bin");
    fs::write(folder.join(&staged_name), vec![1u8; 512]).unwrap();

    // Just the real 8-byte PNG signature `fylz_sniff::sniff` matches on -- not a fully decodable
    // PNG (this crate's `PreviewProvider` only classifies content, it never decodes pixels; see
    // its own doc comment for why), padded so it is comfortably longer than the magic pattern.
    let preview_path = folder.join("thumbnail-source.png");
    let mut png_like = b"\x89PNG\r\n\x1a\n".to_vec();
    png_like.extend_from_slice(b"not a real IHDR chunk, only the signature matters here");
    fs::write(&preview_path, png_like).unwrap();

    let journal_path = base.join("journal.json");
    seed_journal(&journal_path);

    let archive_path = archive_fixture_path();

    println!("export FYLZ_DEMO_BASE={}", base.display());
    println!("export FYLZ_DEMO_FOLDER={}", folder.display());
    println!("export FYLZ_DEMO_ARCHIVE={}", archive_path.display());
    println!("export FYLZ_DEMO_JOURNAL={}", journal_path.display());
    println!("export FYLZ_DEMO_PREVIEW_PATH={}", preview_path.display());

    // A genuine, useful check, not a no-op: `cxx-qt-build`'s Qt/C++ link flags (`build.rs`)
    // apply to every target in this package, this binary included, even though it never touches
    // the bridge itself -- so it is worth telling whoever runs this pipeline, right here, whether
    // the `fylz-qml-demo` step that follows will actually have Qt to run against.
    if fylz_ffi_qt::QT_UNAVAILABLE_AT_BUILD_TIME {
        eprintln!(
            "warning: fylz-ffi-qt was built with no Qt found (see \
             docs/agent/ADR-LINUX-UT-STRATEGY.md); fylz-qml-demo will not do anything with this \
             fixture."
        );
    }
}

/// A real journal, written by this process and then abandoned. `fylz-qml-demo` -- a genuinely
/// different process -- is the one that calls `Journal::open` on it next, so its reconcile
/// sweep runs for real rather than being simulated.
fn seed_journal(path: &PathBuf) {
    let journal = Journal::open(path, now_millis()).expect("open the demo journal");

    let mut done_item = OperationItem::new("/demo/src/archive-old.zip", "archive-old.zip");
    done_item.expected_bytes = Some(2048);
    done_item.completed_bytes = 2048;
    let mut done = FileOperation::new(FileOperationType::Copy, vec![done_item], now_millis());
    done.state = OperationState::Succeeded;
    journal.put(done);

    let mut queued_item = OperationItem::new("/demo/src/notes.txt", "notes.txt");
    queued_item.expected_bytes = Some(512);
    let queued = FileOperation::new(FileOperationType::Move, vec![queued_item], now_millis());
    journal.put(queued);

    // Left `Running`: `fylz-qml-demo`'s own `Journal::open`, in ITS process, will find this
    // still `Running` and move it to `NeedsAttention` -- a real crash-recovery sweep exercised
    // end to end, not merely asserted about (that assertion already lives in `logic.rs`'s own
    // unit test; this is the same behaviour, proven live through the QML bridge).
    let mut running_item = OperationItem::new("/demo/src/big-video.mp4", "big-video.mp4");
    running_item.expected_bytes = Some(1_000_000);
    running_item.completed_bytes = 250_000;
    let mut running = FileOperation::new(FileOperationType::Copy, vec![running_item], now_millis());
    running.state = OperationState::Running;
    journal.put(running);
}

/// `core/fixtures/archives/tree.zip`: a real, non-hostile M3 fixture (real files and nested
/// folders, no fuzzing/attack content), relative to this crate's own manifest directory
/// (`core/crates/fylz-ffi-qt`).
fn archive_fixture_path() -> PathBuf {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../fixtures/archives/tree.zip");
    path.canonicalize().unwrap_or(path)
}

fn now_millis() -> i64 {
    use std::time::SystemTime;
    use std::time::UNIX_EPOCH;
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis() as i64)
        .unwrap_or(0)
}
