//! Golden tests ported one-to-one from `RecycleBinPolicyTest.kt`.

use super::*;

#[test]
fn default_delete_refuses_when_recycle_is_unavailable() {
    assert_eq!(
        DefaultDeletePolicyDecision::RefuseNoRecycleRoot,
        decide_default_delete_policy(false, false),
    );
}

#[test]
fn default_delete_refuses_when_recycle_root_is_not_writable() {
    assert_eq!(
        DefaultDeletePolicyDecision::RefuseRecycleRootNotWritable,
        decide_default_delete_policy(true, false),
    );
}

#[test]
fn default_delete_moves_to_recycle_when_writable() {
    assert_eq!(
        DefaultDeletePolicyDecision::MoveToRecycleBin,
        decide_default_delete_policy(true, true)
    );
}

#[test]
fn permanent_delete_requires_location_or_advanced_action_and_confirmation() {
    assert!(!allow_permanent_delete(false, false, true));
    assert!(!allow_permanent_delete(true, false, false));
    assert!(allow_permanent_delete(true, false, true));
    assert!(allow_permanent_delete(false, true, true));
}
