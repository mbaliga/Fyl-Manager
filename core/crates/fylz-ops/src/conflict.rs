//! Ported from `app/src/main/java/io/github/mbaliga/fylz/operations/ConflictDetection.kt`:
//! every source checked against the *effective* name it will land under at the destination
//! (after a Preflight [`crate::preflight::PreflightPolicy::sanitized_name`] rename, matching
//! `name_overrides`), not each source's own original name -- checking the original name would
//! find, or miss, the wrong conflicts for anything already renamed.
//!
//! Rewritten over `std::fs` instead of a SAF `ContentResolver`/`DocNode` tree lookup: on Linux
//! and Ubuntu Touch a destination is an ordinary directory, so "does a same-named entry already
//! exist there" is a single `Path::exists` check rather than a tree-root resolution. That also
//! drops the one Kotlin test case with no Linux analogue -- `ConflictDetectionTest.kt`'s
//! "checks conflicts against an already resolved nested destination, not the tree's root" --
//! which exists only because a SAF tree URI can itself point above the actual destination
//! document; a filesystem `Path` names its destination directly, so there is no root-versus-
//! nested distinction to get wrong. `ConflictDetectionTest.kt`'s other five cases are this
//! module's parity oracle (`conflict_tests.rs`).

use std::collections::HashMap;
use std::path::Path;
use std::path::PathBuf;

/// One top-level source item that already has a same-named sibling at the destination.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ConflictedItem {
    pub source_path: PathBuf,
    /// The effective name this source will land under (after `name_overrides`, if any).
    pub existing_name: String,
    /// The already-existing destination entry sharing that name.
    pub existing_path: PathBuf,
}

/// Checks every one of `sources` against `destination_dir`, before a copy or move ever starts.
/// `name_overrides` mirrors what the transfer itself will actually use as each item's target
/// name (a Preflight sheet's own auto-rename choice) -- checking a source's own original name
/// here would find, or miss, the wrong conflicts for anything already renamed.
///
/// A source that no longer resolves (its path no longer exists) is silently skipped, same
/// reasoning as [`crate::preflight::gather_preflight_items`]: the transfer itself will report
/// that failure clearly when it actually tries to open the item.
pub fn find_conflicts(
    sources: &[PathBuf],
    destination_dir: &Path,
    name_overrides: &HashMap<PathBuf, String>,
) -> Vec<ConflictedItem> {
    sources
        .iter()
        .filter_map(|source| {
            if std::fs::symlink_metadata(source).is_err() {
                return None;
            }
            let own_name = source.file_name()?.to_string_lossy().into_owned();
            let target_name = name_overrides.get(source).cloned().unwrap_or(own_name);
            let candidate = destination_dir.join(&target_name);
            if std::fs::symlink_metadata(&candidate).is_ok() {
                Some(ConflictedItem {
                    source_path: source.clone(),
                    existing_name: target_name,
                    existing_path: candidate,
                })
            } else {
                None
            }
        })
        .collect()
}

#[cfg(test)]
#[path = "conflict_tests.rs"]
mod conflict_tests;
