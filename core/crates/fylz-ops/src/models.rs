//! Ported from `app/src/main/java/io/github/mbaliga/fylz/operations/OperationModels.kt`,
//! field-for-field and rule-for-rule. `OperationRecoveryPolicyTest.kt` is [`RecoveryPolicy`]'s
//! parity oracle (`models_tests.rs`).
//!
//! One deliberate substitution: every Kotlin field typed `android.net.Uri` becomes a plain
//! `String` here rather than a URI or path type. On Linux and Ubuntu Touch a source or
//! destination is most naturally a filesystem path, but Content Hub items (M13.4) are opaque
//! `content://`-shaped identifiers the same way SAF ones are on Android -- a `PathBuf` would not
//! fit both, and this crate has no need yet to parse either shape, only to carry it. `String`
//! keeps that decision open for M13.2/M13.3 rather than baking in a guess.

use std::collections::HashSet;

use serde::Deserialize;
use serde::Serialize;
use uuid::Uuid;

/// `FileOperationType` (`OperationModels.kt`), unchanged.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum FileOperationType {
    Copy,
    Move,
    Recycle,
    Restore,
    PermanentDelete,
    Rename,
    CreateDirectory,
    CreateFile,
    Archive,
    Extract,
    Pdf,
}

/// `OperationState` (`OperationModels.kt`), unchanged, including the two states whose Kotlin
/// doc comments name a specific cause: [`OperationState::Interrupted`] (a dead process left this
/// item mid-copy; a resumable retry, not a failure) and [`OperationState::PausedBySystem`] (the
/// platform stopped the work itself, e.g. Android's WorkManager budget -- kept for parity even
/// though nothing on Linux triggers it yet).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum OperationState {
    Queued,
    Preflight,
    Running,
    Paused,
    Succeeded,
    Failed,
    /// Some items succeeded and some failed (P1.7) -- every item was attempted; this is not a
    /// failure that aborted the batch partway through. [`OperationState::Failed`] means none of
    /// the items succeeded; [`OperationState::Succeeded`] means all of them did.
    Partial,
    Cancelled,
    NeedsAttention,
    /// A dead process left this item mid-copy (P0.6); its staged partial write, if any, is
    /// deleted on recovery and it is safely retryable from scratch.
    Interrupted,
    /// The platform stopped the operation's worker rather than the user pausing it.
    PausedBySystem,
}

/// `ConflictPolicy` (`OperationModels.kt`), unchanged.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ConflictPolicy {
    Ask,
    KeepBoth,
    Replace,
    /// Replace only when the source is newer than the existing destination item (P1.6) --
    /// otherwise the same outcome as [`ConflictPolicy::Skip`]. Meaningless, and never selected,
    /// for a directory: there is no single "modified" instant for a whole tree to compare.
    ReplaceIfNewer,
    Skip,
}

/// `OperationItem` (`OperationModels.kt`).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct OperationItem {
    pub id: String,
    pub source: String,
    pub destination: Option<String>,
    pub display_name: String,
    pub expected_bytes: Option<u64>,
    pub completed_bytes: u64,
    pub state: OperationState,
    pub error_code: Option<String>,
    /// The `.fylz-part-*` name ([`crate::staging`]) this item is (or was) writing to before
    /// verification and the final rename (P0.6). Cleared once the item reaches a terminal state.
    pub staging_name: Option<String>,
    /// The item's actual final location, once it has one.
    pub final_location: Option<String>,
    /// The transferred content's SHA-256 ([`crate::checksum`]), populated only when verification
    /// ran for it -- null otherwise, including for every directory item, which has no single
    /// byte stream for one hash to describe.
    pub sha256: Option<String>,
}

impl OperationItem {
    /// A new item in [`OperationState::Queued`], mirroring the Kotlin data class's own
    /// defaults (`id` freshly random, every optional field unset).
    pub fn new(source: impl Into<String>, display_name: impl Into<String>) -> Self {
        Self {
            id: Uuid::new_v4().to_string(),
            source: source.into(),
            destination: None,
            display_name: display_name.into(),
            expected_bytes: None,
            completed_bytes: 0,
            state: OperationState::Queued,
            error_code: None,
            staging_name: None,
            final_location: None,
            sha256: None,
        }
    }
}

/// `FileOperation` (`OperationModels.kt`). [`FileOperation::total_bytes`],
/// [`FileOperation::completed_bytes`] and [`FileOperation::progress`] are computed the same way
/// the Kotlin data class computes them as constructor-time `val`s -- here as methods, since Rust
/// has no equivalent of a field initialised from `this` at construction time, and a plain field
/// would silently go stale as items change underneath it.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct FileOperation {
    pub id: String,
    #[serde(rename = "type")]
    pub kind: FileOperationType,
    pub items: Vec<OperationItem>,
    pub conflict_policy: ConflictPolicy,
    pub state: OperationState,
    pub created_at_millis: i64,
    pub updated_at_millis: i64,
    /// The operation's overall target folder, distinct from each item's own
    /// [`OperationItem::destination`].
    pub destination: Option<String>,
    /// Set once by [`crate::journal::Journal::set_cancel_requested`]; a running worker polls
    /// this rather than the journal being able to reach into it directly.
    pub cancel_requested: bool,
}

impl FileOperation {
    pub fn new(kind: FileOperationType, items: Vec<OperationItem>, now_millis: i64) -> Self {
        Self {
            id: Uuid::new_v4().to_string(),
            kind,
            items,
            conflict_policy: ConflictPolicy::Ask,
            state: OperationState::Queued,
            created_at_millis: now_millis,
            updated_at_millis: now_millis,
            destination: None,
            cancel_requested: false,
        }
    }

    /// `None` unless every item has a known [`OperationItem::expected_bytes`] -- an unknown size
    /// anywhere makes the whole total unknown, exactly like the Kotlin `takeIf { it.size ==
    /// items.size }`.
    pub fn total_bytes(&self) -> Option<u64> {
        let known: Vec<u64> = self
            .items
            .iter()
            .filter_map(|item| item.expected_bytes)
            .collect();
        (known.len() == self.items.len()).then(|| known.iter().sum())
    }

    pub fn completed_bytes(&self) -> u64 {
        self.items.iter().map(|item| item.completed_bytes).sum()
    }

    /// `None` when [`FileOperation::total_bytes`] is `None` or zero; otherwise clamped to
    /// `[0.0, 1.0]`, matching Kotlin's `coerceIn`.
    pub fn progress(&self) -> Option<f32> {
        let total = self.total_bytes()?;
        if total == 0 {
            return None;
        }
        let ratio = self.completed_bytes() as f32 / total as f32;
        Some(ratio.clamp(0.0, 1.0))
    }
}

/// `OperationRecoveryPolicy` (`OperationModels.kt`), ported one-to-one.
/// `OperationRecoveryPolicyTest.kt` is this struct's parity oracle -- see `models_tests.rs`.
pub struct RecoveryPolicy;

impl RecoveryPolicy {
    fn interrupted_states() -> HashSet<OperationState> {
        HashSet::from([
            OperationState::Preflight,
            OperationState::Running,
            OperationState::Paused,
        ])
    }

    /// If `operation.state` is one a dead process could have left mid-flight
    /// ([`OperationState::Preflight`], [`OperationState::Running`] or [`OperationState::Paused`]),
    /// moves the whole operation and every item still in one of those states to
    /// [`OperationState::NeedsAttention`], tagging each such item with `"PROCESS_INTERRUPTED"`.
    /// Any other operation is returned unchanged (including its `updated_at_millis`).
    pub fn recover_after_process_death(
        operation: &FileOperation,
        recovered_at_millis: i64,
    ) -> FileOperation {
        let interrupted = Self::interrupted_states();
        if !interrupted.contains(&operation.state) {
            return operation.clone();
        }
        let mut recovered = operation.clone();
        recovered.state = OperationState::NeedsAttention;
        recovered.updated_at_millis = recovered_at_millis;
        for item in &mut recovered.items {
            if interrupted.contains(&item.state) {
                item.state = OperationState::NeedsAttention;
                item.error_code = Some("PROCESS_INTERRUPTED".to_string());
            }
        }
        recovered
    }

    pub fn is_terminal(state: OperationState) -> bool {
        matches!(
            state,
            OperationState::Succeeded
                | OperationState::Failed
                | OperationState::Partial
                | OperationState::Cancelled
                | OperationState::NeedsAttention
                | OperationState::Interrupted
        )
    }
}

#[cfg(test)]
#[path = "models_tests.rs"]
mod models_tests;
