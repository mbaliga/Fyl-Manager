//! The plain-Rust logic behind every real `QObject` in this crate ([`crate::folder_model`],
//! [`crate::archive_model`], [`crate::operation_queue`]), kept entirely free of Qt types so it
//! compiles and its tests run on ANY machine -- including one with no Qt installed at all, where
//! this crate's Qt-touching modules do not even exist (`crate`'s own doc comment). Each Qt
//! `QObject` is a thin wrapper: it reads its own `QString` properties into plain Rust values,
//! calls one function here, and copies the result into its row storage for `data()`/`rowCount()`
//! to serve. Real behaviour lives here, once, not duplicated per QObject.

use std::collections::HashMap;
use std::collections::HashSet;
use std::path::Path;
use std::path::PathBuf;

use fylz_archive::EntryKind;
use fylz_archive::Limits as ArchiveLimits;
use fylz_ops::preflight::gather_preflight_items;
use fylz_ops::preflight::PreflightItem;
use fylz_ops::preflight::PreflightPolicy;
use fylz_ops::preflight::PreflightProblem;
use fylz_ops::preflight::VolumeInfo;
use fylz_ops::staging::is_staging_name;
use fylz_ops::Journal;

// ---------------------------------------------------------------------------------------------
// FolderModel
// ---------------------------------------------------------------------------------------------

/// One row [`crate::folder_model`] serves to QML: a real directory entry plus the operational
/// metadata a file browser needs, both genuinely computed (never fabricated) from `fylz-ops`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FolderRow {
    pub name: String,
    pub is_directory: bool,
    pub size_bytes: u64,
    /// `fylz_ops::staging::is_staging_name`: a `.fylz-part-*` write in progress.
    pub is_staging: bool,
    /// A real `fylz_ops::preflight::PreflightPolicy::evaluate` verdict
    /// ([`PreflightProblem::NameCollision`]) under a case-insensitive destination, not a
    /// hand-rolled duplicate check -- the same policy a real copy/move would run before starting.
    pub has_name_collision: bool,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct FolderScan {
    pub rows: Vec<FolderRow>,
    /// The sum of every top-level entry's real size (a directory's summed recursively), from
    /// `fylz_ops::preflight::gather_preflight_items` -- the same number a real preflight
    /// free-space check would use as `required_bytes`.
    pub total_bytes: u64,
}

/// Lists `root`'s real top-level entries and runs them through `fylz-ops`'s real preflight and
/// staging-name policies. Never fabricates a conflict or a staging flag: an unreadable or
/// vanished `root` simply yields an empty scan, matching `gather_preflight_items`'s own
/// "drop what can't be read" contract.
pub fn scan_folder(root: &Path) -> FolderScan {
    let mut entries: Vec<(String, PathBuf)> = Vec::new();
    if let Ok(read_dir) = std::fs::read_dir(root) {
        for entry in read_dir.flatten() {
            let path = entry.path();
            if let Some(name) = path.file_name() {
                entries.push((name.to_string_lossy().into_owned(), path));
            }
        }
    }
    entries.sort_by(|a, b| a.0.cmp(&b.0));

    let paths: Vec<PathBuf> = entries.iter().map(|(_, path)| path.clone()).collect();
    let items = gather_preflight_items(&paths);

    // `case_insensitive: true` so two real, differently-cased sibling names are actually
    // exercised as a `NameCollision` -- the same rule a vfat/exfat destination, or a
    // case-insensitive filesystem, would trigger for real (`PreflightPolicy::evaluate`'s own
    // doc comment).
    let volume = VolumeInfo {
        filesystem_type: None,
        free_bytes: None,
        case_insensitive: true,
    };
    let result = PreflightPolicy::evaluate(&items, &volume);

    let mut collided: HashSet<&str> = HashSet::new();
    for problem in &result.problems {
        if let PreflightProblem::NameCollision { item, .. } = problem {
            collided.insert(item.name.as_str());
        }
    }

    let mut by_name: HashMap<&str, &PreflightItem> = items
        .iter()
        .map(|item| (item.name.as_str(), item))
        .collect();

    let mut rows = Vec::with_capacity(entries.len());
    let mut total_bytes: u64 = 0;
    for (name, _path) in &entries {
        let item = by_name.remove(name.as_str());
        let size_bytes = item.and_then(|item| item.total_bytes).unwrap_or(0);
        total_bytes = total_bytes.saturating_add(size_bytes);
        rows.push(FolderRow {
            name: name.clone(),
            is_directory: item.map(|item| item.is_directory).unwrap_or(false),
            size_bytes,
            is_staging: is_staging_name(name),
            has_name_collision: collided.contains(name.as_str()),
        });
    }

    FolderScan { rows, total_bytes }
}

// ---------------------------------------------------------------------------------------------
// ArchiveModel
// ---------------------------------------------------------------------------------------------

/// One row [`crate::archive_model`] serves to QML: one archive entry as `fylz-archive`'s own
/// `inspect` reports it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ArchiveRow {
    pub path: String,
    pub is_directory: bool,
    pub size_bytes: u64,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ArchiveScan {
    pub rows: Vec<ArchiveRow>,
    /// `Inspection::format_name` (e.g. `"zip"`), when `fylz-archive` could determine one.
    pub format_name: Option<String>,
    /// Set instead of `rows` on any failure -- opening the file, or `fylz_archive::inspect`
    /// itself -- carrying `fylz_archive::ArchiveError`'s own message, never silently empty rows.
    pub error: Option<String>,
}

/// Opens `path` as a real file descriptor and runs `fylz-archive`'s own header-pass listing over
/// it (`fylz_archive::inspect`), the exact M3.3 browsing entry point -- never a re-implementation
/// of archive parsing.
pub fn scan_archive(path: &Path) -> ArchiveScan {
    use std::os::fd::AsRawFd;

    let file = match std::fs::File::open(path) {
        Ok(file) => file,
        Err(error) => {
            return ArchiveScan {
                rows: Vec::new(),
                format_name: None,
                error: Some(format!("opening {path:?}: {error}")),
            }
        }
    };

    match fylz_archive::inspect(file.as_raw_fd(), &ArchiveLimits::default()) {
        Ok(inspection) => ArchiveScan {
            rows: inspection
                .entries
                .iter()
                .map(|entry| ArchiveRow {
                    path: entry.path.clone(),
                    is_directory: entry.kind == EntryKind::Directory,
                    size_bytes: entry.uncompressed.unwrap_or(0),
                })
                .collect(),
            format_name: inspection.format_name,
            error: None,
        },
        Err(error) => ArchiveScan {
            rows: Vec::new(),
            format_name: None,
            error: Some(error.to_string()),
        },
    }
}

// ---------------------------------------------------------------------------------------------
// OperationQueue
// ---------------------------------------------------------------------------------------------

/// One row [`crate::operation_queue`] serves to QML: one `fylz_ops::FileOperation` from a real
/// `fylz_ops::journal::Journal`, summarised for display.
#[derive(Debug, Clone, PartialEq)]
pub struct OperationRow {
    pub id: String,
    pub kind: String,
    pub state: String,
    pub item_count: usize,
    /// `FileOperation::progress` as a whole percentage, or `-1` when the operation's total size
    /// is not fully known (`FileOperation::progress`'s own `None` case) -- never a fabricated
    /// number in place of "unknown".
    pub progress_percent: i32,
}

/// Opens the real journal at `path` (creating it if absent, exactly like any other caller of
/// `fylz_ops::journal::Journal::open`) and lists every operation on it. This is what makes the
/// end-to-end QML demo (`docs/agent/ADR-LINUX-UT-STRATEGY.md`) a real exercise of
/// `Journal::open`'s reconcile-after-process-death sweep: the demo binary calling this function
/// is a different OS process than whatever last wrote the journal, so an operation left
/// `Running`/`Preflight`/`Paused` genuinely gets moved to `NeedsAttention` here, not simulated.
pub fn list_operations(path: &Path) -> Vec<OperationRow> {
    let journal = match Journal::open(path, now_millis()) {
        Ok(journal) => journal,
        Err(_) => return Vec::new(),
    };
    let mut operations = journal.list();
    operations.sort_by(|a, b| a.created_at_millis.cmp(&b.created_at_millis));
    operations
        .iter()
        .map(|operation| OperationRow {
            id: operation.id.clone(),
            kind: format!("{:?}", operation.kind),
            state: format!("{:?}", operation.state),
            item_count: operation.items.len(),
            progress_percent: operation
                .progress()
                .map(|ratio| (ratio * 100.0).round() as i32)
                .unwrap_or(-1),
        })
        .collect()
}

fn now_millis() -> i64 {
    use std::time::SystemTime;
    use std::time::UNIX_EPOCH;
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis() as i64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use fylz_ops::FileOperation;
    use fylz_ops::FileOperationType;
    use fylz_ops::OperationItem;
    use fylz_ops::OperationState;
    use std::fs;

    #[test]
    fn scan_folder_lists_real_entries_sorted_by_name() {
        let dir = tempfile::tempdir().unwrap();
        fs::write(dir.path().join("b.txt"), b"bb").unwrap();
        fs::write(dir.path().join("a.txt"), b"a").unwrap();
        fs::create_dir(dir.path().join("sub")).unwrap();

        let scan = scan_folder(dir.path());

        let names: Vec<&str> = scan.rows.iter().map(|row| row.name.as_str()).collect();
        assert_eq!(names, vec!["a.txt", "b.txt", "sub"]);
        let a = &scan.rows[0];
        assert_eq!(a.size_bytes, 1);
        assert!(!a.is_directory);
        let sub = &scan.rows[2];
        assert!(sub.is_directory);
        assert_eq!(scan.total_bytes, 3); // 1 ("a") + 2 ("bb") + 0 (empty "sub")
    }

    #[test]
    fn scan_folder_flags_staging_names_via_fylz_ops() {
        let dir = tempfile::tempdir().unwrap();
        let staging = fylz_ops::staging::staging_name("op-1", 0, "partial.bin");
        fs::write(dir.path().join(&staging), b"partial").unwrap();
        fs::write(dir.path().join("done.bin"), b"done").unwrap();

        let scan = scan_folder(dir.path());

        let staging_row = scan.rows.iter().find(|row| row.name == staging).unwrap();
        assert!(staging_row.is_staging);
        let done_row = scan.rows.iter().find(|row| row.name == "done.bin").unwrap();
        assert!(!done_row.is_staging);
    }

    #[test]
    fn scan_folder_flags_case_insensitive_collisions_via_preflight_policy() {
        let dir = tempfile::tempdir().unwrap();
        fs::write(dir.path().join("Report.txt"), b"one").unwrap();
        fs::write(dir.path().join("report.TXT"), b"two").unwrap();
        fs::write(dir.path().join("unique.txt"), b"three").unwrap();

        let scan = scan_folder(dir.path());

        let collided: Vec<&str> = scan
            .rows
            .iter()
            .filter(|row| row.has_name_collision)
            .map(|row| row.name.as_str())
            .collect();
        assert_eq!(
            collided.len(),
            1,
            "only the second-seen name is flagged: {collided:?}"
        );
        let unique_row = scan
            .rows
            .iter()
            .find(|row| row.name == "unique.txt")
            .unwrap();
        assert!(!unique_row.has_name_collision);
    }

    #[test]
    fn scan_folder_on_a_missing_directory_is_empty_not_an_error() {
        let scan = scan_folder(Path::new("/does/not/exist/at/all"));
        assert!(scan.rows.is_empty());
        assert_eq!(scan.total_bytes, 0);
    }

    #[test]
    fn scan_archive_lists_a_real_fixture_via_fylz_archive_inspect() {
        // `core/fixtures/archives/tree.zip`, a real, non-hostile M3 fixture (real files and
        // nested folders, no fuzzing/attack content) -- not a fabricated archive for this test.
        let fixture =
            Path::new(env!("CARGO_MANIFEST_DIR")).join("../../fixtures/archives/tree.zip");
        assert!(fixture.is_file(), "fixture missing at {fixture:?}");

        let scan = scan_archive(&fixture);

        assert!(scan.error.is_none(), "unexpected error: {:?}", scan.error);
        assert!(
            scan.format_name
                .as_deref()
                .is_some_and(|name| name.starts_with("ZIP")),
            "expected a ZIP format name, got {:?}",
            scan.format_name
        );
        assert!(
            scan.rows
                .iter()
                .any(|row| row.path == "readme.txt" && !row.is_directory),
            "expected a readme.txt file entry, got {:?}",
            scan.rows
        );
        assert!(
            scan.rows
                .iter()
                .any(|row| row.path == "photos/" && row.is_directory),
            "expected a photos/ directory entry, got {:?}",
            scan.rows
        );
        assert!(
            scan.rows.len() >= 20,
            "expected the fixture's full tree, got {:?}",
            scan.rows
        );
    }

    #[test]
    fn scan_archive_on_a_missing_file_reports_an_error_not_empty_rows() {
        let scan = scan_archive(Path::new("/does/not/exist.zip"));
        assert!(scan.rows.is_empty());
        assert!(scan.error.is_some());
    }

    #[test]
    fn list_operations_reads_a_real_journal_and_summarises_progress() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("journal.json");
        let journal = Journal::open(&path, 1_000).unwrap();

        let mut half_done = OperationItem::new("/src/a.bin", "a.bin");
        half_done.expected_bytes = Some(100);
        half_done.completed_bytes = 50;
        let operation = FileOperation::new(FileOperationType::Copy, vec![half_done], 1_000);
        let operation_id = operation.id.clone();
        journal.put(operation);
        journal.update_operation_state(&operation_id, OperationState::Running, 1_500);

        let rows = list_operations(&path);

        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].kind, "Copy");
        assert_eq!(rows[0].state, "Running");
        assert_eq!(rows[0].item_count, 1);
        assert_eq!(rows[0].progress_percent, 50);
    }

    #[test]
    fn list_operations_on_a_fresh_path_is_empty() {
        let dir = tempfile::tempdir().unwrap();
        let rows = list_operations(&dir.path().join("does-not-exist-yet.json"));
        assert!(rows.is_empty());
    }

    #[test]
    fn a_journal_left_by_a_different_process_is_reconciled_on_open() {
        // `fylz_ops::journal::Journal::open`'s reconcile sweep keys off a session marker stored
        // ON DISK versus this real process's own `process_session_id` -- a per-process `OnceLock`
        // (`journal.rs`), so it is deliberately NOT reconciled again by a second `Journal::open`
        // call in the SAME process (confirmed the hard way: an earlier version of this test tried
        // exactly that, in-process, and correctly saw no reconcile happen at all). Simulating a
        // genuinely different process's leftover session marker -- writing the journal file
        // directly, reusing `FileOperation`'s own real `Serialize` impl for the operation itself,
        // faking only the `session` string -- exercises the real reconcile code path without
        // that same-process limitation. The full, no-faking version of this proof is the QML
        // demo itself: `fylz-seed-demo` and `fylz-qml-demo` are two REAL, separate OS processes
        // (see both binaries' own doc comments).
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("journal.json");

        let item = OperationItem::new("/src/big.bin", "big.bin");
        let mut operation = FileOperation::new(FileOperationType::Move, vec![item], 1_000);
        operation.state = OperationState::Running;
        let disk = serde_json::json!({
            "session": "a-different-process-entirely",
            "operations": { operation.id.clone(): operation },
        });
        fs::write(&path, serde_json::to_string_pretty(&disk).unwrap()).unwrap();

        let rows = list_operations(&path);
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].state, "NeedsAttention");
    }
}
