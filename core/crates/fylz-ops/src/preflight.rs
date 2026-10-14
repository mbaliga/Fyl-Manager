//! Ported from `app/src/main/java/io/github/mbaliga/fylz/operations/PreflightPolicy.kt` and
//! `PreflightGathering.kt`. `PreflightPolicyTest.kt` is [`PreflightPolicy::evaluate`]'s parity
//! oracle (`preflight_tests.rs`); every rule below runs in the same order over the same fields,
//! and [`PreflightPolicy`]'s own doc comment below is carried over almost verbatim, since the
//! Linux target changes none of it.
//!
//! [`VolumeInfo`] is normally its own type in `io.github.mbaliga.fylz.storage.VolumeInfo`, not
//! the `operations` package this crate otherwise mirrors -- it is folded in here because this
//! crate has no storage module of its own, and [`PreflightPolicy::evaluate`] is the only thing
//! in this port that consumes it. [`gather_preflight_items`] is the impure counterpart
//! (`PreflightGathering.kt`), rewritten over `std::fs` instead of a SAF `ContentResolver`/
//! `DocNode`: a source that no longer resolves is dropped rather than failing the whole gather,
//! same reasoning as the Kotlin version -- the caller's own copy/move will report that failure
//! clearly when it actually tries to open the item.

use std::collections::HashSet;
use std::fs;
use std::path::Path;
use std::path::PathBuf;

/// What [`PreflightPolicy::evaluate`] needs to know about a transfer's destination volume.
/// `filesystem_type` is `None` when it could not be determined; every filesystem-specific rule
/// then simply does not apply, rather than guessing. `free_bytes` is `None` when it could not be
/// determined either, and the free-space check is then skipped, not treated as zero.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VolumeInfo {
    pub filesystem_type: Option<String>,
    pub free_bytes: Option<u64>,
    pub case_insensitive: bool,
}

/// One top-level item a transfer is about to attempt, as [`PreflightPolicy`] needs to know it.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct PreflightItem {
    /// The item's own source path (or, for a non-filesystem source such as a future Content Hub
    /// item, an opaque identifier) -- carried through so a caller can map a [`PreflightProblem`]
    /// back to the item the user selected.
    pub source: String,
    pub name: String,
    pub is_directory: bool,
    /// This item's own size, or -- for a directory -- every nested file's size summed by
    /// walking its real tree ([`gather_preflight_items`]): the free-space rule needs the total
    /// a copy will actually write, not just what one directory entry reports.
    pub total_bytes: Option<u64>,
}

/// `PreflightProblem` (`PreflightPolicy.kt`). A Rust `sealed interface` equivalent: each variant
/// carries the same fields the matching Kotlin subclass did, and [`PreflightProblem::item`]
/// stands in for the Kotlin interface's shared `item` property.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PreflightProblem {
    /// `item`'s name contains a character vfat/exfat cannot store. Fixable with
    /// [`PreflightPolicy::sanitized_name`].
    IllegalCharacters {
        item: PreflightItem,
        characters: HashSet<char>,
    },
    /// `item`'s name ends in a space or a dot, which vfat/exfat silently trim or reject. Fixable
    /// with [`PreflightPolicy::sanitized_name`].
    TrailingSpaceOrDot { item: PreflightItem },
    /// `item`'s name collides with `collides_with_name` only once case is ignored, on a
    /// destination that cannot tell them apart -- not fixable by
    /// [`PreflightPolicy::sanitized_name`], since replacing characters does nothing about two
    /// names differing only in case.
    NameCollision {
        item: PreflightItem,
        collides_with_name: String,
    },
    /// `item`'s own name is longer than `limit_bytes` once UTF-8 encoded.
    NameTooLong {
        item: PreflightItem,
        limit_bytes: usize,
    },
    /// `item` is a single file over vfat's own 4 GiB - 1 byte ceiling, which the filesystem
    /// cannot represent at all, regardless of free space.
    FileTooLargeForVfat { item: PreflightItem },
}

impl PreflightProblem {
    pub fn item(&self) -> &PreflightItem {
        match self {
            PreflightProblem::IllegalCharacters { item, .. }
            | PreflightProblem::TrailingSpaceOrDot { item }
            | PreflightProblem::NameCollision { item, .. }
            | PreflightProblem::NameTooLong { item, .. }
            | PreflightProblem::FileTooLargeForVfat { item } => item,
        }
    }
}

/// `available_bytes` falls short of `required_bytes` (already including the safety margin) at
/// the destination.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct InsufficientSpace {
    pub required_bytes: u64,
    pub available_bytes: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PreflightResult {
    pub problems: Vec<PreflightProblem>,
    pub insufficient_space: Option<InsufficientSpace>,
}

impl PreflightResult {
    pub fn is_clean(&self) -> bool {
        self.problems.is_empty() && self.insufficient_space.is_none()
    }
}

/// A pure policy over what a copy or move is about to attempt, run once against the
/// destination's [`VolumeInfo`] before a transfer starts -- catching a class of failure that
/// otherwise only ever surfaces mid-copy, one item at a time.
///
/// Scope, stated up front: every rule here runs against the TOP-LEVEL items the caller
/// selected ([`gather_preflight_items`]'s own `sources`), not a full recursive listing of a
/// folder's own contents -- a bad name several levels inside a large copied tree is not
/// individually flagged. Free space is the one rule that does look inside a directory, via
/// [`gather_preflight_items`]'s own recursive sum, because a free-space check that ignored a
/// folder's real contents would not be a check at all.
pub struct PreflightPolicy;

impl PreflightPolicy {
    /// vfat's own hard ceiling: a 32-bit unsigned byte-count field, so no single file can be
    /// this size or larger there, regardless of how much free space exists.
    pub const VFAT_MAX_FILE_BYTES: u64 = 4 * 1024 * 1024 * 1024 - 1;

    /// 255 UTF-8 bytes is the per-component name ceiling on every filesystem this crate expects
    /// to meet in practice -- ext4, f2fs, vfat and exfat all cap one path component at this,
    /// independent of overall path length.
    pub const NAME_LENGTH_LIMIT_BYTES: usize = 255;

    /// Refuse when the destination's free space would fall under required-plus-this-fraction.
    const FREE_SPACE_MARGIN: f64 = 0.05;

    pub fn evaluate(items: &[PreflightItem], volume: &VolumeInfo) -> PreflightResult {
        let mut problems = Vec::new();
        let is_vfat = volume.filesystem_type.as_deref() == Some("vfat");
        let is_fat_family = matches!(
            volume.filesystem_type.as_deref(),
            Some("vfat") | Some("exfat")
        );

        for item in items {
            if is_vfat
                && !item.is_directory
                && item.total_bytes.unwrap_or(0) > Self::VFAT_MAX_FILE_BYTES
            {
                problems.push(PreflightProblem::FileTooLargeForVfat { item: item.clone() });
            }
            if is_fat_family {
                let illegal: HashSet<char> = item
                    .name
                    .chars()
                    .filter(|c| Self::fat_illegal_characters().contains(c))
                    .collect();
                if !illegal.is_empty() {
                    problems.push(PreflightProblem::IllegalCharacters {
                        item: item.clone(),
                        characters: illegal,
                    });
                }
                if matches!(item.name.chars().last(), Some(' ') | Some('.')) {
                    problems.push(PreflightProblem::TrailingSpaceOrDot { item: item.clone() });
                }
            }
            if item.name.len() > Self::NAME_LENGTH_LIMIT_BYTES {
                problems.push(PreflightProblem::NameTooLong {
                    item: item.clone(),
                    limit_bytes: Self::NAME_LENGTH_LIMIT_BYTES,
                });
            }
        }

        if is_fat_family || volume.case_insensitive {
            problems.extend(Self::case_insensitive_collisions(items));
        }

        PreflightResult {
            problems,
            insufficient_space: Self::check_free_space(items, volume.free_bytes),
        }
    }

    /// `display_name` with every character [`PreflightProblem::IllegalCharacters`] would flag
    /// replaced by `_`, and a trailing space or dot also replaced -- the sheet's own
    /// "auto-rename" choice. Meaningless (and never called) for a
    /// [`PreflightProblem::NameCollision`], [`PreflightProblem::NameTooLong`] or
    /// [`PreflightProblem::FileTooLargeForVfat`] problem, none of which a character
    /// substitution fixes.
    pub fn sanitized_name(display_name: &str) -> String {
        let illegal = Self::fat_illegal_characters();
        let replaced: String = display_name
            .chars()
            .map(|c| if illegal.contains(&c) { '_' } else { c })
            .collect();
        match replaced.chars().last() {
            Some(' ') | Some('.') => {
                let mut chars: Vec<char> = replaced.chars().collect();
                chars.pop();
                chars.push('_');
                chars.into_iter().collect()
            }
            _ => replaced,
        }
    }

    fn fat_illegal_characters() -> HashSet<char> {
        HashSet::from(['\\', '/', ':', '*', '?', '"', '<', '>', '|'])
    }

    /// Groups `items` by lower-cased name, preserving each key's first-seen order (Kotlin's
    /// `groupBy` does the same), and reports every item after the first in a group of two or
    /// more as colliding with that first item's own (original-case) name.
    fn case_insensitive_collisions(items: &[PreflightItem]) -> Vec<PreflightProblem> {
        let mut order: Vec<String> = Vec::new();
        let mut groups: std::collections::HashMap<String, Vec<&PreflightItem>> =
            std::collections::HashMap::new();
        for item in items {
            let key = item.name.to_lowercase();
            if !groups.contains_key(&key) {
                order.push(key.clone());
            }
            groups.entry(key).or_default().push(item);
        }

        let mut problems = Vec::new();
        for key in order {
            let group = &groups[&key];
            if group.len() > 1 {
                let first_name = group[0].name.clone();
                for item in &group[1..] {
                    problems.push(PreflightProblem::NameCollision {
                        item: (*item).clone(),
                        collides_with_name: first_name.clone(),
                    });
                }
            }
        }
        problems
    }

    fn check_free_space(
        items: &[PreflightItem],
        free_bytes: Option<u64>,
    ) -> Option<InsufficientSpace> {
        let free_bytes = free_bytes?;
        // Known sizes only -- an item whose recursive walk could not read a nested size
        // contributes nothing here rather than failing the whole check; a possible undercount
        // on an unreadable item is an acceptable trade for an otherwise-useful early warning.
        let required = Self::safe_sum(items.iter().filter_map(|item| item.total_bytes))?;
        let required_with_margin =
            Self::safe_sum([required, Self::margin_for(required)].into_iter())?;
        if free_bytes < required_with_margin {
            Some(InsufficientSpace {
                required_bytes: required_with_margin,
                available_bytes: free_bytes,
            })
        } else {
            None
        }
    }

    fn margin_for(bytes: u64) -> u64 {
        (bytes as f64 * Self::FREE_SPACE_MARGIN) as u64
    }

    fn safe_sum(values: impl Iterator<Item = u64>) -> Option<u64> {
        let mut total = 0u64;
        for value in values {
            total = total.checked_add(value)?;
        }
        Some(total)
    }
}

/// One [`PreflightItem`] per path in `sources` -- the impure counterpart to [`PreflightPolicy`],
/// which stays pure over the result. A source that no longer exists, or whose metadata cannot
/// be read, is dropped rather than failing the whole gather.
pub fn gather_preflight_items(sources: &[PathBuf]) -> Vec<PreflightItem> {
    sources
        .iter()
        .filter_map(|path| {
            let metadata = fs::symlink_metadata(path).ok()?;
            let name = path.file_name()?.to_string_lossy().into_owned();
            let is_directory = metadata.is_dir();
            let total_bytes = if is_directory {
                Some(recursive_size_bytes(path))
            } else {
                Some(metadata.len())
            };
            Some(PreflightItem {
                source: path.to_string_lossy().into_owned(),
                name,
                is_directory,
                total_bytes,
            })
        })
        .collect()
}

/// Every nested file's size under `path`, summed -- a child whose own size is unknown, or that
/// cannot be listed at all, contributes 0 rather than making the whole sum unknown; see
/// [`PreflightPolicy`]'s own "known sizes only" note on why an undercount here is an acceptable
/// trade.
fn recursive_size_bytes(path: &Path) -> u64 {
    let entries = match fs::read_dir(path) {
        Ok(entries) => entries,
        Err(_) => return 0,
    };
    entries
        .filter_map(|entry| entry.ok())
        .map(|entry| {
            let child = entry.path();
            match fs::symlink_metadata(&child) {
                Ok(metadata) if metadata.is_dir() => recursive_size_bytes(&child),
                Ok(metadata) => metadata.len(),
                Err(_) => 0,
            }
        })
        .sum()
}

#[cfg(test)]
#[path = "preflight_tests.rs"]
mod preflight_tests;
