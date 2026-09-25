#![no_main]

use fylz_archive::policy::evaluate;
use fylz_archive::policy::EntryKind;
use fylz_archive::policy::EntryMetadata;
use fylz_archive::policy::Limits;
// The derive expands to `arbitrary::...` paths, so the crate name must resolve; libfuzzer-sys
// re-exports it (this workspace has no direct `arbitrary` dependency line of its own).
use libfuzzer_sys::arbitrary;
use libfuzzer_sys::arbitrary::Arbitrary;
use libfuzzer_sys::fuzz_target;

// fylz_archive::policy::evaluate is pure Rust over metadata an attacker fully controls (every
// entry name and declared size comes straight out of the archive's own headers, via
// DecoderService -- docs/agent/MASTER_PLAN.md section 4.4). It must never panic, whatever the
// names (empty, NUL, non-BMP, thousands of segments), sizes (u64::MAX, sums that overflow) or
// limits (zero, MAX, NaN ratios) are: an overflow in the running total, a slice index into an
// empty segment list, a multiplication of two limits, or a division by a zero archive length would
// each show up here as a crash.
//
// These mirror the section-6 types of docs/agent/DESIGN-M31-PART3-EXTRACT-AND-POLICY.md field for
// field so `arbitrary` can derive over them without the engine crate itself depending on the
// `arbitrary` crate (which only this fuzz workspace needs). A new field or variant on the engine
// side fails to compile here, which `cargo +nightly fuzz build` in the gate is there to catch.
#[derive(Arbitrary, Debug)]
enum Kind {
    File,
    Directory,
    Symlink,
    Hardlink,
    Other,
}

#[derive(Arbitrary, Debug)]
struct Entry {
    ordinal: u32,
    path: String,
    name_lossy: bool,
    kind: Kind,
    link_target: Option<String>,
    uncompressed: Option<u64>,
    compressed: Option<u64>,
    mtime: Option<i64>,
    mode: u32,
    encrypted_data: bool,
    encrypted_metadata: bool,
}

#[derive(Arbitrary, Debug)]
struct FuzzLimits {
    max_entries: usize,
    max_archive_bytes: u64,
    max_file_bytes: u64,
    max_total_uncompressed_bytes: u64,
    max_compression_ratio: f64,
    max_path_depth: usize,
    max_name_length: usize,
    max_listing_entries: usize,
}

#[derive(Arbitrary, Debug)]
struct Input {
    archive_bytes: u64,
    limits: FuzzLimits,
    entries: Vec<Entry>,
}

impl From<Kind> for EntryKind {
    fn from(kind: Kind) -> Self {
        match kind {
            Kind::File => EntryKind::File,
            Kind::Directory => EntryKind::Directory,
            Kind::Symlink => EntryKind::Symlink,
            Kind::Hardlink => EntryKind::Hardlink,
            Kind::Other => EntryKind::Other,
        }
    }
}

impl From<Entry> for EntryMetadata {
    fn from(entry: Entry) -> Self {
        EntryMetadata {
            ordinal: entry.ordinal,
            path: entry.path,
            name_lossy: entry.name_lossy,
            kind: entry.kind.into(),
            link_target: entry.link_target,
            uncompressed: entry.uncompressed,
            compressed: entry.compressed,
            mtime: entry.mtime,
            mode: entry.mode,
            encrypted_data: entry.encrypted_data,
            encrypted_metadata: entry.encrypted_metadata,
        }
    }
}

impl From<FuzzLimits> for Limits {
    fn from(limits: FuzzLimits) -> Self {
        Limits {
            max_entries: limits.max_entries,
            max_archive_bytes: limits.max_archive_bytes,
            max_file_bytes: limits.max_file_bytes,
            max_total_uncompressed_bytes: limits.max_total_uncompressed_bytes,
            max_compression_ratio: limits.max_compression_ratio,
            max_path_depth: limits.max_path_depth,
            max_name_length: limits.max_name_length,
            max_listing_entries: limits.max_listing_entries,
        }
    }
}

fuzz_target!(|input: Input| {
    let limits: Limits = input.limits.into();
    let entries: Vec<EntryMetadata> = input.entries.into_iter().map(Into::into).collect();
    let decision = evaluate(input.archive_bytes, &entries, &limits);
    // The one invariant every caller relies on: a refusal always carries its reason, an
    // acceptance never does.
    assert_eq!(decision.allowed, decision.reason.is_none());
    // The same inputs under the shipped defaults, so the default rule set (the one the app runs)
    // is what most of the executions exercise rather than a random limit tuple.
    let default_decision = evaluate(input.archive_bytes, &entries, &Limits::default());
    assert_eq!(default_decision.allowed, default_decision.reason.is_none());
});
