//! The operation engine for the Linux and Ubuntu Touch targets (`docs/agent/MASTER_PLAN.md`
//! M13.1), ported from the Kotlin `app/src/main/java/io/github/mbaliga/fylz/operations` package.
//!
//! **This crate started ahead of its own stated prerequisite.** M13's plan entry says
//! "Prerequisite: M2-M9 core crates are stable"; as of this port, only M2 and M3 are done --
//! M4 (`fylz-verify`), M7 (`fylz-rename`), M8 (`fylz-query`/`fylz-index`) and M9 (`fylz-clean`)
//! are still one-line stub crates. The owner asked to start this anyway, in full knowledge of
//! that gap; see `docs/agent/ADR-LINUX-UT-STRATEGY.md` for the record. Nothing here depends on
//! any of those four crates, and nothing in this crate should be read as a signal that they are
//! now implemented.
//!
//! ## What is ported, and from where
//!
//! - [`models`] -- `OperationModels.kt`: [`FileOperation`], [`OperationItem`], their enums, and
//!   [`models::RecoveryPolicy`] (`OperationRecoveryPolicy`), ported field-for-field and
//!   rule-for-rule; `OperationRecoveryPolicyTest.kt` is this module's parity oracle.
//! - [`journal`] -- `OperationJournal.kt`'s *lifecycle* (claim a state transition, tag an item,
//!   reconcile interrupted work left by a dead process), re-hosted over a small JSON-file store
//!   instead of the Android SQLite `OperationsDao`, which this crate does not port: that DAO, and
//!   the M3-specific extract/create-plan bookkeeping layered on it, are Android/SAF-shaped
//!   persistence details, not part of the engine's spec. `OperationJournalStateFlowTest.kt` and
//!   `OperationJournalConcurrencyTest.kt` are this module's parity oracle for the lifecycle rules
//!   they exercise (a live view that updates on every write; two independent instances over the
//!   same store not seeing each other's in-memory state).
//! - [`staging`] -- the `stagingName`/`isStagingName` pair from `FileOperationService.kt`
//!   (P0.6): every write lands under a `.fylz-part-*` name until it is verified, so a dead
//!   process never leaves a half-written file under the name the user would see.
//!   `StagingNameTest.kt` is this module's parity oracle.
//! - [`preflight`] -- `PreflightPolicy.kt`, ported one-to-one: it is already a pure function over
//!   plain data ([`preflight::VolumeInfo`], [`preflight::PreflightItem`]), so nothing about the
//!   Linux target changes its rules. `PreflightPolicyTest.kt` is this module's parity oracle.
//!   [`preflight::gather_preflight_items`] is the impure counterpart (`PreflightGathering.kt`),
//!   rewritten over `std::fs` instead of a SAF `ContentResolver`/`DocNode`.
//! - [`conflict`] -- `ConflictDetection.kt`'s policy (a same-named destination entry, checked
//!   against the *effective* post-rename name), rewritten over `std::fs::read_dir` instead of a
//!   SAF tree lookup. `ConflictDetectionTest.kt` is this module's parity oracle.
//! - [`checksum`] -- `ChecksumVerification.kt`: streamed SHA-256 of a transfer's destination
//!   against its source, distinct from `fylz-verify`'s planned scope (hashing a *downloaded*
//!   file against a published checksum file or an OpenPGP signature, M4.6). See that module's
//!   doc comment for the boundary call in full. `ChecksumVerificationTest.kt` is this module's
//!   parity oracle.
//! - [`recycle`] -- `RecycleBinPolicy.kt`'s pure policy, ported one-to-one
//!   ([`recycle::policy`], `RecycleBinPolicyTest.kt` is its parity oracle), plus a from-scratch
//!   [`recycle::trash`] backend implementing the freedesktop.org Trash specification that the
//!   master plan's own M13.1 entry calls for, in place of `RecycleBinStore.kt`/
//!   `RecycleBinService.kt`'s SAF/MediaStore-backed Android implementation, which is out of this
//!   crate's scope entirely -- the Android app keeps using it unchanged. See [`recycle::trash`]'s
//!   doc comment for why no Kotlin test file is this module's parity oracle.

pub mod checksum;
pub mod conflict;
pub mod journal;
pub mod models;
pub mod preflight;
pub mod recycle;
pub mod staging;

pub use checksum::verify_checksum;
pub use checksum::ChecksumError;
pub use conflict::find_conflicts;
pub use conflict::ConflictedItem;
pub use journal::Journal;
pub use models::ConflictPolicy;
pub use models::FileOperation;
pub use models::FileOperationType;
pub use models::OperationItem;
pub use models::OperationState;
pub use preflight::PreflightItem;
pub use preflight::PreflightPolicy;
pub use preflight::PreflightProblem;
pub use preflight::PreflightResult;
pub use preflight::VolumeInfo;
