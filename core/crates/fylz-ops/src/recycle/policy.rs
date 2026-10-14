//! Ported one-to-one from
//! `app/src/main/java/io/github/mbaliga/fylz/operations/RecycleBinPolicy.kt`.
//! `RecycleBinPolicyTest.kt` is this module's parity oracle (`policy_tests.rs`).
//!
//! The only adaptation: `DeleteDecision::MoveToRecycleBin` carries a [`std::path::PathBuf`]
//! (the freedesktop `files/` directory a trash operation will write into, from
//! [`super::trash`]) where the Kotlin `sealed interface` carried a SAF tree `Uri` -- the
//! decision the two rules below make is unchanged, only what "the recycle location" is shaped
//! like.

use std::path::PathBuf;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DeleteDecision {
    MoveToRecycleBin { recycle_root: PathBuf },
    Refuse { reason: String },
    PermanentDeleteRequiresExplicitAdvancedAction,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) enum DefaultDeletePolicyDecision {
    MoveToRecycleBin,
    RefuseNoRecycleRoot,
    RefuseRecycleRootNotWritable,
}

pub(super) fn decide_default_delete_policy(
    has_recycle_root: bool,
    can_write_recycle_root: bool,
) -> DefaultDeletePolicyDecision {
    if !has_recycle_root {
        DefaultDeletePolicyDecision::RefuseNoRecycleRoot
    } else if !can_write_recycle_root {
        DefaultDeletePolicyDecision::RefuseRecycleRootNotWritable
    } else {
        DefaultDeletePolicyDecision::MoveToRecycleBin
    }
}

pub fn decide_default_delete(
    recycle_root: Option<PathBuf>,
    can_write_recycle_root: bool,
) -> DeleteDecision {
    match decide_default_delete_policy(recycle_root.is_some(), can_write_recycle_root) {
        DefaultDeletePolicyDecision::RefuseNoRecycleRoot => DeleteDecision::Refuse {
            reason: "No writable Fylz recycle location is available for this destination."
                .to_string(),
        },
        DefaultDeletePolicyDecision::RefuseRecycleRootNotWritable => DeleteDecision::Refuse {
            reason: "The selected destination cannot write to its Fylz recycle location."
                .to_string(),
        },
        DefaultDeletePolicyDecision::MoveToRecycleBin => DeleteDecision::MoveToRecycleBin {
            recycle_root: recycle_root.expect("checked above"),
        },
    }
}

pub fn allow_permanent_delete(
    invoked_from_recycle_bin: bool,
    explicit_advanced_action: bool,
    confirmed: bool,
) -> bool {
    confirmed && (invoked_from_recycle_bin || explicit_advanced_action)
}

#[cfg(test)]
#[path = "policy_tests.rs"]
mod policy_tests;
