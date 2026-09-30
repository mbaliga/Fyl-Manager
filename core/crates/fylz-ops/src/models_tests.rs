//! Golden tests ported one-to-one from `OperationRecoveryPolicyTest.kt`, plus a few new cases
//! for [`FileOperation`]'s computed properties, which have no dedicated Kotlin test file.

use super::*;

fn operation(
    kind: FileOperationType,
    state: OperationState,
    updated_at_millis: i64,
) -> FileOperation {
    FileOperation {
        id: "operation-1".to_string(),
        kind,
        items: Vec::new(),
        conflict_policy: ConflictPolicy::Ask,
        state,
        created_at_millis: 100,
        updated_at_millis,
        destination: None,
        cancel_requested: false,
    }
}

// --- ported one-to-one from OperationRecoveryPolicyTest.kt --------------------------------

#[test]
fn running_operation_becomes_needs_attention_after_process_death() {
    let op = operation(FileOperationType::Copy, OperationState::Running, 110);

    let recovered = RecoveryPolicy::recover_after_process_death(&op, 200);

    assert_eq!(OperationState::NeedsAttention, recovered.state);
    assert_eq!(200, recovered.updated_at_millis);
}

#[test]
fn terminal_operation_remains_unchanged() {
    let op = operation(FileOperationType::Move, OperationState::Succeeded, 150);

    assert_eq!(op, RecoveryPolicy::recover_after_process_death(&op, 200));
}

#[test]
fn partial_is_terminal_since_every_item_was_already_attempted() {
    assert!(RecoveryPolicy::is_terminal(OperationState::Partial));
}

// --- new coverage: recovery also tags interrupted items, not just the operation -----------

#[test]
fn an_item_still_running_is_tagged_process_interrupted() {
    let mut op = operation(FileOperationType::Copy, OperationState::Running, 110);
    op.items.push(OperationItem::new("src", "a.txt"));
    op.items[0].state = OperationState::Running;
    op.items.push(OperationItem::new("src", "b.txt"));
    op.items[1].state = OperationState::Succeeded;

    let recovered = RecoveryPolicy::recover_after_process_death(&op, 200);

    assert_eq!(OperationState::NeedsAttention, recovered.items[0].state);
    assert_eq!(
        Some("PROCESS_INTERRUPTED".to_string()),
        recovered.items[0].error_code
    );
    // An already-succeeded item is left exactly as it was.
    assert_eq!(OperationState::Succeeded, recovered.items[1].state);
    assert_eq!(None, recovered.items[1].error_code);
}

// --- new coverage: FileOperation's computed byte/progress properties ----------------------

#[test]
fn total_bytes_is_none_when_any_item_size_is_unknown() {
    let mut op = FileOperation::new(FileOperationType::Copy, Vec::new(), 0);
    op.items.push(OperationItem::new("a", "a.txt"));
    op.items[0].expected_bytes = Some(10);
    op.items.push(OperationItem::new("b", "b.txt"));
    op.items[1].expected_bytes = None;

    assert_eq!(None, op.total_bytes());
}

#[test]
fn progress_is_the_completed_over_total_ratio_clamped() {
    let mut op = FileOperation::new(FileOperationType::Copy, Vec::new(), 0);
    op.items.push(OperationItem::new("a", "a.txt"));
    op.items[0].expected_bytes = Some(100);
    op.items[0].completed_bytes = 150; // over-reported completion still clamps to 1.0

    assert_eq!(Some(1.0), op.progress());
}

#[test]
fn progress_is_none_when_total_bytes_is_zero() {
    let mut op = FileOperation::new(FileOperationType::Copy, Vec::new(), 0);
    op.items.push(OperationItem::new("a", "a.txt"));
    op.items[0].expected_bytes = Some(0);

    assert_eq!(None, op.progress());
}
