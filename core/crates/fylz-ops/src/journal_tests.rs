//! Golden tests ported one-to-one from `OperationJournalStateFlowTest.kt` (the "P1.11" cache
//! semantics) and `OperationJournalConcurrencyTest.kt` (concurrent writers), plus new coverage
//! for the reconcile-once-per-process rule this module's own doc comment describes, which has
//! no dedicated Kotlin test file of its own.

use std::collections::HashSet;
use std::fs;
use std::sync::Arc;
use std::thread;

use tempfile::tempdir;

use crate::models::ConflictPolicy;
use crate::models::FileOperationType;

use super::*;

fn operation(id: &str, state: OperationState) -> FileOperation {
    FileOperation {
        id: id.to_string(),
        kind: FileOperationType::Copy,
        items: vec![OperationItem {
            id: format!("item-{id}"),
            source: format!("test/source-{id}"),
            destination: None,
            display_name: format!("file-{id}.txt"),
            expected_bytes: None,
            completed_bytes: 0,
            state,
            error_code: None,
            staging_name: None,
            final_location: None,
            sha256: None,
        }],
        conflict_policy: ConflictPolicy::Ask,
        state,
        created_at_millis: 0,
        updated_at_millis: 0,
        destination: None,
        cancel_requested: false,
    }
}

fn ids(operations: &[FileOperation]) -> HashSet<String> {
    operations.iter().map(|op| op.id.clone()).collect()
}

// --- ported one-to-one from OperationJournalStateFlowTest.kt -------------------------------

#[test]
fn put_republishes_operations_immediately() {
    let dir = tempdir().unwrap();
    let journal = Journal::open(dir.path().join("journal.json"), 0).unwrap();

    journal.put(operation("a", OperationState::Queued));

    assert_eq!(HashSet::from(["a".to_string()]), ids(&journal.operations()));
}

#[test]
fn remove_republishes_operations_immediately() {
    let dir = tempdir().unwrap();
    let journal = Journal::open(dir.path().join("journal.json"), 0).unwrap();
    journal.put(operation("a", OperationState::Queued));

    journal.remove("a");

    assert!(journal.operations().is_empty());
}

#[test]
fn clear_finished_republishes_operations_immediately() {
    let dir = tempdir().unwrap();
    let journal = Journal::open(dir.path().join("journal.json"), 0).unwrap();
    journal.put(operation("a", OperationState::Succeeded));
    journal.put(operation("b", OperationState::Running));

    journal.clear_finished();

    assert_eq!(HashSet::from(["b".to_string()]), ids(&journal.operations()));
}

#[test]
fn a_second_instance_over_the_same_store_does_not_see_the_firsts_mutation_until_it_mutates_too() {
    let dir = tempdir().unwrap();
    let path = dir.path().join("journal.json");
    let first = Journal::open(&path, 0).unwrap();
    let second = Journal::open(&path, 0).unwrap();

    first.put(operation("a", OperationState::Queued));

    assert!(
        second.operations().is_empty(),
        "a fresh instance's own cache must not change just because another instance wrote"
    );
    assert_eq!(
        HashSet::from(["a".to_string()]),
        ids(&second.list()),
        "the write is still durable -- a one-off list() read sees it"
    );

    second.put(operation("b", OperationState::Queued));

    assert_eq!(
        HashSet::from(["a".to_string(), "b".to_string()]),
        ids(&second.operations()),
        "the second instance's own mutation refreshes it to the store's full current state"
    );
}

// --- ported one-to-one from OperationJournalConcurrencyTest.kt -----------------------------

#[test]
fn concurrent_puts_from_multiple_threads_all_persist_without_corruption() {
    let dir = tempdir().unwrap();
    let journal = Arc::new(Journal::open(dir.path().join("journal.json"), 0).unwrap());
    const THREAD_COUNT: usize = 12;

    let handles: Vec<_> = (0..THREAD_COUNT)
        .map(|index| {
            let journal = Arc::clone(&journal);
            thread::spawn(move || {
                journal.put(operation(
                    &format!("operation-{index}"),
                    OperationState::Queued,
                ));
            })
        })
        .collect();
    for handle in handles {
        handle.join().expect("writer thread panicked");
    }

    let ids: HashSet<String> = ids(&journal.list());
    assert_eq!(THREAD_COUNT, ids.len());
    for index in 0..THREAD_COUNT {
        assert!(ids.contains(&format!("operation-{index}")));
    }
}

// --- new coverage: reconcile runs once per process, not once per Journal::open ------------

/// Raw on-disk shape a store written before this process ever opened it would have: no session
/// marker, one operation left `RUNNING` (as if a prior process died mid-copy).
fn seed_unreconciled_store(path: &std::path::Path) {
    let raw = serde_json::json!({
        "session": null,
        "operations": {
            "a": {
                "id": "a",
                "type": "COPY",
                "items": [{
                    "id": "item-a",
                    "source": "test/source-a",
                    "destination": null,
                    "display_name": "file-a.txt",
                    "expected_bytes": null,
                    "completed_bytes": 0,
                    "state": "RUNNING",
                    "error_code": null,
                    "staging_name": null,
                    "final_location": null,
                    "sha256": null,
                }],
                "conflict_policy": "ASK",
                "state": "RUNNING",
                "created_at_millis": 500,
                "updated_at_millis": 500,
                "destination": null,
                "cancel_requested": false,
            },
        },
    });
    fs::write(path, serde_json::to_string(&raw).unwrap()).unwrap();
}

#[test]
fn a_stale_running_operation_is_reconciled_once_and_a_second_open_never_re_flags_live_work() {
    let dir = tempdir().unwrap();
    let path = dir.path().join("journal.json");
    seed_unreconciled_store(&path);

    let first = Journal::open(&path, 1_000).unwrap();
    let recovered = first.find("a").unwrap();
    assert_eq!(
        OperationState::NeedsAttention,
        recovered.state,
        "a stale RUNNING record is reconciled on first open"
    );
    assert_eq!(
        Some("PROCESS_INTERRUPTED".to_string()),
        recovered.items[0].error_code
    );

    // Legitimately running work started by this same process, after the reconcile above.
    first.put(operation("b", OperationState::Running));

    // A second Journal over the SAME store, in the SAME process: its own open must not re-run
    // the sweep, or it would wrongly flag "b" -- work this very process is still doing -- as
    // interrupted.
    let second = Journal::open(&path, 2_000).unwrap();
    assert_eq!(
        OperationState::Running,
        second.find("b").unwrap().state,
        "live work is not re-flagged"
    );
    assert_eq!(
        OperationState::NeedsAttention,
        second.find("a").unwrap().state,
        "already-recovered record is untouched"
    );
}
