//! M3.4a tests (`docs/agent/DESIGN-M34-SELECTIVE-EXTRACT.md` section 2.8, Rust): [extract_blocks]
//! over [Selection::Ranges] (exact match, normalisation, early exit), the per-entry failure kinds
//! libarchive's own damaged fixtures produce (`crc-bad.zip` -> `Crc` and continue, `crc-bad.7z` ->
//! `Crc`, `inflate-bad.zip` -> abort, `solid-bad.7z` -> abort and a resume that skips the folder),
//! the header-level `FAILED` rule on a tar built at test time, the structural re-check, the ordinal
//! cross-test (listing ordinals equal `begin` ordinals for every fixture), the cancel hook,
//! [policy::evaluate_selection] over real selections (a ratio bomb and an oversized-header archive
//! built at test time, `many-small-10000` allowed at the cap), and the adapters' error mapping.

use crate::extract;
use crate::extract_blocks;
use crate::extract_blocks_cancellable;
use crate::extract_entry_at;
use crate::inspect;
use crate::inspect_for_extraction;
use crate::policy;
use crate::tests::open_fixture;
use crate::tests::tempdir;
use crate::ArchiveError;
use crate::BlockSink;
use crate::EntryKind;
use crate::EntryMetadata;
use crate::ExtractLimits;
use crate::FailKind;
use crate::Limits;
use crate::Selection;
use crate::Warning;
use crate::CANCEL_POLL_HEADERS;
use std::collections::HashMap;
use std::fs;
use std::fs::File;
use std::io::Seek;
use std::io::Write;
use std::os::unix::io::AsRawFd;
use std::path::Path;
use std::path::PathBuf;
use std::process::Command;

fn archive_fixture(name: &str) -> File {
    open_fixture(&format!("archives/{name}"))
}

/// What a sink saw, in order.
#[derive(Debug, Clone, PartialEq)]
enum Event {
    Begin(u32, String, EntryKind),
    End(u32, u64, Option<Warning>),
    Failed(u32, FailKind, String),
}

/// Records every callback and the bytes of every entry; declines nothing unless told to.
#[derive(Default)]
struct Recording {
    events: Vec<Event>,
    data: HashMap<u32, Vec<u8>>,
    decline_kinds: Vec<EntryKind>,
}

impl Recording {
    fn begun(&self) -> Vec<u32> {
        self.events
            .iter()
            .filter_map(|e| match e {
                Event::Begin(o, _, _) => Some(*o),
                _ => None,
            })
            .collect()
    }

    fn failed(&self) -> Vec<(u32, FailKind)> {
        self.events
            .iter()
            .filter_map(|e| match e {
                Event::Failed(o, k, _) => Some((*o, *k)),
                _ => None,
            })
            .collect()
    }

    fn ended(&self) -> Vec<(u32, u64)> {
        self.events
            .iter()
            .filter_map(|e| match e {
                Event::End(o, b, _) => Some((*o, *b)),
                _ => None,
            })
            .collect()
    }

    fn paths(&self) -> Vec<String> {
        self.events
            .iter()
            .filter_map(|e| match e {
                Event::Begin(_, p, _) => Some(p.clone()),
                _ => None,
            })
            .collect()
    }
}

impl BlockSink for Recording {
    fn begin(&mut self, entry: &EntryMetadata) -> Result<bool, ArchiveError> {
        if self.decline_kinds.contains(&entry.kind) {
            return Ok(false);
        }
        self.events
            .push(Event::Begin(entry.ordinal, entry.path.clone(), entry.kind));
        Ok(true)
    }

    fn write(&mut self, ordinal: u32, block: &[u8]) -> Result<(), ArchiveError> {
        self.data
            .entry(ordinal)
            .or_default()
            .extend_from_slice(block);
        Ok(())
    }

    fn end(
        &mut self,
        ordinal: u32,
        bytes: u64,
        warning: Option<Warning>,
    ) -> Result<(), ArchiveError> {
        self.events.push(Event::End(ordinal, bytes, warning));
        Ok(())
    }

    fn failed(&mut self, ordinal: u32, kind: FailKind, message: &str) -> Result<(), ArchiveError> {
        self.events
            .push(Event::Failed(ordinal, kind, message.to_string()));
        Ok(())
    }
}

/// The fixture generator's `prng_bytes(seed, size)`: the classic 32-bit LCG, bits 16..23.
fn prng_bytes(seed: u32, size: usize) -> Vec<u8> {
    let mut x = seed;
    (0..size)
        .map(|_| {
            x = x.wrapping_mul(1_103_515_245).wrapping_add(12_345);
            (x >> 16) as u8
        })
        .collect()
}

/// `tree.zip`/`tree.tar.zst`'s 40 files in archive order, with the generator's size rule.
fn tree_files() -> Vec<String> {
    let mut files = vec!["readme.txt".to_string()];
    files.extend((1..=10).map(|i| format!("photos/2024/img-{i:02}.bin")));
    files.extend((1..=7).map(|i| format!("photos/2025/img-{i:02}.bin")));
    files.push("photos/index.txt".into());
    files.push("docs/guide.md".into());
    files.extend((1..=8).map(|i| format!("docs/notes/note-{i}.txt")));
    files.extend((1..=5).map(|i| format!("docs/notes/deep/deep-{i}.txt")));
    for name in [
        "bin/tool.sh",
        "bin/data/a.dat",
        "bin/data/b.dat",
        "bin/data/c.dat",
        "implicit/child.txt",
        "late/x.txt",
        "empty.txt",
    ] {
        files.push(name.into());
    }
    assert_eq!(files.len(), 40);
    files
}

fn tree_file_body(index: usize) -> Vec<u8> {
    let size = if tree_files()[index] == "empty.txt" {
        0
    } else {
        (index * 37) % 1500 + 100
    };
    prng_bytes(index as u32 + 1, size)
}

fn ranges(list: &[(u32, u32)]) -> Selection {
    Selection::Ranges(list.to_vec())
}

// ---------------------------------------------------------------------------------------------
// Selection::Ranges: exact match, normalisation, early exit.
// ---------------------------------------------------------------------------------------------

#[test]
fn ranges_select_exactly_the_named_ordinals_and_the_bytes_are_the_generators() {
    for fixture in ["tree.zip", "tree.tar.zst"] {
        let inspection = inspect(archive_fixture(fixture).as_raw_fd(), &Limits::default()).unwrap();
        let files = tree_files();
        // The ordinals of readme.txt, docs/guide.md and empty.txt, wherever the format put them.
        let ordinal_of = |path: &str| {
            inspection
                .entries
                .iter()
                .find(|e| e.path == path)
                .unwrap_or_else(|| panic!("{fixture}: no {path}"))
                .ordinal
        };
        let picked = ["readme.txt", "docs/guide.md", "empty.txt"];
        let selection = ranges(&picked.map(|p| (ordinal_of(p), ordinal_of(p))));
        let mut sink = Recording::default();
        let report = extract_blocks(
            archive_fixture(fixture).as_raw_fd(),
            &selection,
            &ExtractLimits::default(),
            &mut sink,
        )
        .unwrap();
        assert_eq!(report.entries_written, 3, "{fixture}");
        assert_eq!(report.entries_failed, 0, "{fixture}");
        let mut expected_paths: Vec<String> = picked.iter().map(|p| p.to_string()).collect();
        expected_paths.sort_by_key(|p| ordinal_of(p));
        assert_eq!(sink.paths(), expected_paths, "{fixture}: archive order");
        for path in picked {
            let index = files.iter().position(|f| f == path).unwrap();
            let body = sink.data.remove(&ordinal_of(path)).unwrap_or_default();
            assert_eq!(body, tree_file_body(index), "{fixture}: {path}");
        }
        assert!(
            sink.data.is_empty(),
            "{fixture}: bytes for an unselected ordinal"
        );
    }
}

#[test]
fn overlapping_and_unordered_ranges_select_their_union_once() {
    let mut sink = Recording::default();
    extract_blocks(
        archive_fixture("tree.zip").as_raw_fd(),
        &ranges(&[(5, 7), (6, 9), (0, 0), (9, 8), (7, 7)]),
        &ExtractLimits::default(),
        &mut sink,
    )
    .unwrap();
    assert_eq!(sink.begun(), vec![0, 5, 6, 7, 8, 9]);
}

#[test]
fn an_empty_selection_reads_nothing_and_a_range_past_the_end_selects_nothing() {
    let mut sink = Recording::default();
    let report = extract_blocks(
        archive_fixture("tree.zip").as_raw_fd(),
        &ranges(&[]),
        &ExtractLimits::default(),
        &mut sink,
    )
    .unwrap();
    assert!(sink.events.is_empty());
    assert_eq!(report.entries_written, 0);
    let mut sink = Recording::default();
    let report = extract_blocks(
        archive_fixture("tree.zip").as_raw_fd(),
        &ranges(&[(500, 600)]),
        &ExtractLimits::default(),
        &mut sink,
    )
    .unwrap();
    assert!(sink.events.is_empty());
    assert_eq!(report.entries_written, 0);
}

/// `big-stream.tar.zst`: a 2 MiB member first, then ten 10 KiB incompressible ones. Selecting the
/// first alone yields exactly it, byte-exact; selecting the last three yields exactly those. (The
/// descriptor's offset is **not** the early-exit signal on a compressed stream: finishing member 0
/// consumes its 512-byte padding, and the zstd filter's 128 KiB read-ahead for that swallows the
/// remaining ~100 KiB of compressed input -- a recorded deviation from the design's wording; the
/// early exit itself is proven on the uncompressed tar of the next test, as M3.3 proved
/// `extract_entry_at`'s.)
#[test]
fn ranges_over_big_stream_select_exactly_the_named_members_byte_exact() {
    let mut sink = Recording::default();
    let report = extract_blocks(
        archive_fixture("big-stream.tar.zst").as_raw_fd(),
        &ranges(&[(0, 0)]),
        &ExtractLimits::default(),
        &mut sink,
    )
    .unwrap();
    assert_eq!(report.bytes_written, 2 * 1024 * 1024);
    assert_eq!(sink.paths(), vec!["zeros.bin"]);
    assert!(sink.data[&0].iter().all(|b| *b == 0));
    let mut sink = Recording::default();
    extract_blocks(
        archive_fixture("big-stream.tar.zst").as_raw_fd(),
        &ranges(&[(8, 10)]),
        &ExtractLimits::default(),
        &mut sink,
    )
    .unwrap();
    assert_eq!(
        sink.paths(),
        vec!["small-07.bin", "small-08.bin", "small-09.bin"]
    );
    for (ordinal, index) in [(8u32, 7u32), (9, 8), (10, 9)] {
        assert_eq!(sink.data[&ordinal], prng_bytes(1000 + index, 10 * 1024));
    }
}

/// An uncompressed tar whose second member is far larger than libarchive's 64 KiB read block:
/// after extracting only the first member the descriptor must not have reached the end of the
/// file -- the pass stopped reading at the highest selected ordinal.
#[test]
fn a_pass_stops_reading_after_the_highest_selected_ordinal() {
    let dir = tempdir();
    let path = dir.path().join("early-exit.tar");
    let mut out = File::create(&path).unwrap();
    out.write_all(&ustar_header("first.txt", 12, b'0')).unwrap();
    out.write_all(&padded(b"first member".to_vec())).unwrap();
    let big = prng_bytes(42, 4 * 1024 * 1024);
    out.write_all(&ustar_header("big.bin", big.len() as u64, b'0'))
        .unwrap();
    out.write_all(&padded(big)).unwrap();
    out.write_all(&ustar_header("last.txt", 4, b'0')).unwrap();
    out.write_all(&padded(b"last".to_vec())).unwrap();
    out.write_all(&[0u8; 1024]).unwrap();
    drop(out);
    let file = File::open(&path).unwrap();
    let length = file.metadata().unwrap().len();
    let mut sink = Recording::default();
    let report = extract_blocks(
        file.as_raw_fd(),
        &ranges(&[(0, 0)]),
        &ExtractLimits::default(),
        &mut sink,
    )
    .unwrap();
    assert_eq!(sink.paths(), vec!["first.txt"]);
    assert_eq!(report.bytes_written, 12);
    let offset = (&file).stream_position().unwrap();
    assert!(
        offset < length / 2,
        "the descriptor reached {offset} of {length}: the pass did not stop early"
    );
    // The header pass stops early the same way.
    let file = File::open(&path).unwrap();
    let selected =
        inspect_for_extraction(file.as_raw_fd(), &ranges(&[(0, 0)]), &mut || false).unwrap();
    assert_eq!(selected.entries.len(), 1);
    assert!((&file).stream_position().unwrap() < length / 2);
}

// ---------------------------------------------------------------------------------------------
// The ordinal contract: listing ordinals are begin ordinals, for every fixture.
// ---------------------------------------------------------------------------------------------

#[test]
fn listing_ordinals_equal_begin_ordinals_for_every_committed_fixture() {
    for fixture in [
        "sample-cd.zip",
        "sample-streamed.zip",
        "sample-copy.7z",
        "sample-lzma2.7z",
        "sample.iso",
        "tree.zip",
        "tree.tar.zst",
        "mixed-links.tar",
        "messy-paths.tar",
        "dot-rooted.tar",
        "implicit-dirs.zip",
        "backslash.zip",
        "big-stream.tar.zst",
        "hostile/many-small-10000.tar.zst",
        "hostile/zip-slip.zip",
    ] {
        let inspection = inspect(archive_fixture(fixture).as_raw_fd(), &Limits::default()).unwrap();
        let listed: Vec<(u32, String)> = inspection
            .entries
            .iter()
            .map(|e| (e.ordinal, e.path.clone()))
            .collect();
        let mut sink = Recording::default();
        let report = extract_blocks(
            archive_fixture(fixture).as_raw_fd(),
            &Selection::All,
            &ExtractLimits::default(),
            &mut sink,
        )
        .unwrap_or_else(|e| panic!("{fixture}: {e}"));
        let begun: Vec<(u32, String)> = sink
            .events
            .iter()
            .filter_map(|e| match e {
                Event::Begin(o, p, _) => Some((*o, p.clone())),
                _ => None,
            })
            .collect();
        assert_eq!(
            begun, listed,
            "{fixture}: begin ordinals differ from the listing's"
        );
        // Every begun entry ended or failed; zip-slip's `../evil` fails the structural re-check.
        assert_eq!(
            report.entries_written + report.entries_failed,
            listed.len(),
            "{fixture}"
        );
    }
}

#[test]
fn the_dot_rooted_root_is_never_offered_but_occupies_ordinal_zero() {
    let mut sink = Recording::default();
    extract_blocks(
        archive_fixture("dot-rooted.tar").as_raw_fd(),
        &ranges(&[(0, 3)]),
        &ExtractLimits::default(),
        &mut sink,
    )
    .unwrap();
    assert_eq!(sink.begun(), vec![1, 2, 3]);
    assert_eq!(
        sink.paths(),
        vec!["./first.txt", "./sub/", "./sub/second.txt"]
    );
}

#[test]
fn links_and_special_files_are_offered_with_their_kind_and_end_with_no_data() {
    let mut sink = Recording::default();
    let report = extract_blocks(
        archive_fixture("tree.tar.zst").as_raw_fd(),
        &Selection::All,
        &ExtractLimits::default(),
        &mut sink,
    )
    .unwrap();
    let symlink = sink
        .events
        .iter()
        .find(|e| matches!(e, Event::Begin(_, p, EntryKind::Symlink) if p == "docs/link-to-readme"))
        .expect("the symlink is offered");
    let hardlink = sink
        .events
        .iter()
        .find(|e| matches!(e, Event::Begin(_, p, EntryKind::Hardlink) if p == "bin/hard-to-guide"))
        .expect("the hardlink is offered");
    for begin in [symlink, hardlink] {
        let Event::Begin(ordinal, _, _) = begin else {
            unreachable!()
        };
        assert!(sink.ended().contains(&(*ordinal, 0)));
        assert!(!sink.data.contains_key(ordinal));
    }
    // 40 files, 9 directories (8 explicit rows plus `late/`), 2 links: everything ends.
    assert_eq!(report.entries_written, 51);
    assert_eq!(report.skipped_links, 0);
    // A sink that declines links sees them counted, not offered.
    let mut declining = Recording {
        decline_kinds: vec![EntryKind::Symlink, EntryKind::Hardlink],
        ..Recording::default()
    };
    let report = extract_blocks(
        archive_fixture("tree.tar.zst").as_raw_fd(),
        &Selection::All,
        &ExtractLimits::default(),
        &mut declining,
    )
    .unwrap();
    assert_eq!(report.skipped_links, 2);
    assert_eq!(report.entries_written, 49);
}

// ---------------------------------------------------------------------------------------------
// Per-entry failures: continue; fatal: abort.
// ---------------------------------------------------------------------------------------------

#[test]
fn crc_bad_zip_fails_that_entry_with_kind_crc_and_the_pass_continues() {
    let mut sink = Recording::default();
    let report = extract_blocks(
        archive_fixture("crc-bad.zip").as_raw_fd(),
        &Selection::All,
        &ExtractLimits::default(),
        &mut sink,
    )
    .unwrap();
    assert_eq!(
        sink.paths(),
        vec!["good-before.txt", "bad.txt", "good-after.txt"]
    );
    assert_eq!(sink.ended(), vec![(0, 7), (2, 6)]);
    let failed = sink.failed();
    assert_eq!(failed, vec![(1, FailKind::Crc)]);
    assert!(matches!(
        &sink.events[3],
        Event::Failed(1, FailKind::Crc, m) if m.contains("ZIP bad CRC")
    ));
    // The data arrived before the verdict (libarchive checks the CRC after the last block).
    assert_eq!(sink.data[&1].len(), 53);
    assert_eq!(sink.data[&0], b"before\n");
    assert_eq!(sink.data[&2], b"after\n");
    assert_eq!((report.entries_written, report.entries_failed), (2, 1));
}

#[test]
fn crc_bad_7z_fails_that_entry_with_kind_crc_withholding_the_final_block() {
    let mut sink = Recording::default();
    let report = extract_blocks(
        archive_fixture("crc-bad.7z").as_raw_fd(),
        &Selection::All,
        &ExtractLimits::default(),
        &mut sink,
    )
    .unwrap();
    assert_eq!(
        sink.paths(),
        vec!["good-before.txt", "bad.txt", "good-after.txt"]
    );
    assert_eq!(sink.failed(), vec![(1, FailKind::Crc)]);
    assert!(matches!(
        &sink.events[3],
        Event::Failed(1, FailKind::Crc, m) if m.contains("7-Zip bad CRC")
    ));
    // The whole member came in the block libarchive flagged, which was withheld.
    assert!(!sink.data.contains_key(&1));
    assert_eq!(sink.data[&2], b"after\n");
    assert_eq!((report.entries_written, report.entries_failed), (2, 1));
}

#[test]
fn inflate_bad_zip_aborts_the_pass_naming_the_entry() {
    let mut sink = Recording::default();
    let outcome = extract_blocks_cancellable(
        archive_fixture("inflate-bad.zip").as_raw_fd(),
        &Selection::All,
        &ExtractLimits::default(),
        &mut sink,
        &mut || false,
    );
    assert!(
        matches!(&outcome.result, Err(ArchiveError::Fatal(m)) if m.contains("decompression failed")),
        "{:?}",
        outcome.result
    );
    assert_eq!(outcome.stop_ordinal, Some(1));
    assert_eq!(outcome.report.entries_written, 1);
    assert_eq!(sink.paths(), vec!["good-before.txt", "bad.bin"]);
    assert_eq!(sink.ended(), vec![(0, 70)]);
    // A resume after the blamed ordinal extracts the rest.
    let mut sink = Recording::default();
    let report = extract_blocks(
        archive_fixture("inflate-bad.zip").as_raw_fd(),
        &ranges(&[(2, u32::MAX)]),
        &ExtractLimits::default(),
        &mut sink,
    )
    .unwrap();
    assert_eq!(sink.paths(), vec!["good-after.txt"]);
    assert_eq!(report.entries_written, 1);
}

/// `solid-bad.7z`: `folder1/first.bin` and `folder1/second.bin` share a corrupted LZMA2 folder,
/// `folder2/third.txt` has its own. libarchive is **fatal** on the first decode (not `FAILED`, as
/// the survey expected -- recorded), so the pass aborts at ordinal 0; a resume that still names
/// `second.bin` dies the same way (reading it decodes the folder from its start), and a resume
/// that leaves the whole failed folder out extracts `third.txt` (an unread folder is skipped
/// without decoding). That is the scope: the folder is lost, the next folder is not.
#[test]
fn solid_bad_7z_aborts_at_the_folder_and_a_resume_that_skips_it_extracts_the_next_folder() {
    let mut sink = Recording::default();
    let outcome = extract_blocks_cancellable(
        archive_fixture("solid-bad.7z").as_raw_fd(),
        &Selection::All,
        &ExtractLimits::default(),
        &mut sink,
        &mut || false,
    );
    assert!(
        matches!(&outcome.result, Err(ArchiveError::Fatal(m)) if m.contains("Decompression failed")),
        "{:?}",
        outcome.result
    );
    assert_eq!(outcome.stop_ordinal, Some(0));
    assert_eq!(sink.paths(), vec!["folder1/first.bin"]);
    assert_eq!(outcome.report.entries_written, 0);
    // Resuming with the folder's other member still selected fails again, at that member.
    let mut sink = Recording::default();
    let outcome = extract_blocks_cancellable(
        archive_fixture("solid-bad.7z").as_raw_fd(),
        &ranges(&[(1, 2)]),
        &ExtractLimits::default(),
        &mut sink,
        &mut || false,
    );
    assert!(matches!(outcome.result, Err(ArchiveError::Fatal(_))));
    assert_eq!(outcome.stop_ordinal, Some(1));
    // Resuming past the whole folder succeeds without touching it.
    let mut sink = Recording::default();
    let report = extract_blocks(
        archive_fixture("solid-bad.7z").as_raw_fd(),
        &ranges(&[(2, 2)]),
        &ExtractLimits::default(),
        &mut sink,
    )
    .unwrap();
    assert_eq!(sink.paths(), vec!["folder2/third.txt"]);
    assert_eq!(report.bytes_written, 31 * 20);
    assert!(sink.data[&2].starts_with(b"third member in its own folder\n"));
}

#[test]
fn a_bad_tar_header_checksum_is_fatal_and_blames_the_header_it_could_not_read() {
    let mut sink = Recording::default();
    let outcome = extract_blocks_cancellable(
        archive_fixture("damaged-after-3.tar").as_raw_fd(),
        &Selection::All,
        &ExtractLimits::default(),
        &mut sink,
        &mut || false,
    );
    assert!(
        matches!(&outcome.result, Err(ArchiveError::Fatal(m)) if m.contains("bad header checksum")),
        "{:?}",
        outcome.result
    );
    assert_eq!(outcome.report.entries_written, 3);
    assert_eq!(
        outcome.stop_ordinal,
        Some(3),
        "the header that would have been ordinal 3"
    );
}

// ---------------------------------------------------------------------------------------------
// The structural re-check at extraction time.
// ---------------------------------------------------------------------------------------------

#[test]
fn a_selected_entry_with_an_unsafe_path_fails_after_begin_and_its_data_is_never_read() {
    let mut sink = Recording::default();
    let report = extract_blocks(
        archive_fixture("hostile/zip-slip.zip").as_raw_fd(),
        &Selection::All,
        &ExtractLimits::default(),
        &mut sink,
    )
    .unwrap();
    assert_eq!(sink.paths(), vec!["innocent.txt", "../evil"]);
    assert_eq!(
        sink.failed(),
        vec![(1, FailKind::Other)],
        "{:?}",
        sink.events
    );
    assert!(matches!(
        &sink.events[3],
        Event::Failed(1, FailKind::Other, m) if m == "Archive contains an unsafe path segment."
    ));
    assert!(!sink.data.contains_key(&1));
    assert_eq!((report.entries_written, report.entries_failed), (1, 1));
    // The same rule through an escaping link the sink accepts.
    let mut sink = Recording::default();
    extract_blocks(
        archive_fixture("hostile/symlink-escape.tar").as_raw_fd(),
        &Selection::All,
        &ExtractLimits::default(),
        &mut sink,
    )
    .unwrap();
    assert_eq!(sink.failed(), vec![(1, FailKind::Other)]);
}

// ---------------------------------------------------------------------------------------------
// The cancel hook.
// ---------------------------------------------------------------------------------------------

#[test]
fn the_cancel_hook_is_polled_every_64_headers_and_stops_the_pass_as_cancelled() {
    let mut polls = 0;
    let mut sink = Recording::default();
    let outcome = extract_blocks_cancellable(
        archive_fixture("hostile/many-small-10000.tar.zst").as_raw_fd(),
        &Selection::All,
        &ExtractLimits {
            max_entries: 200_000,
            ..ExtractLimits::default()
        },
        &mut sink,
        &mut || {
            polls += 1;
            polls >= 3
        },
    );
    assert!(matches!(outcome.result, Err(ArchiveError::Cancelled)));
    assert_eq!(outcome.stop_ordinal, Some(3 * CANCEL_POLL_HEADERS));
    assert_eq!(
        outcome.report.entries_written as u32,
        3 * CANCEL_POLL_HEADERS
    );
    // The header pass polls the same way.
    let mut polls = 0;
    let result = inspect_for_extraction(
        archive_fixture("hostile/many-small-10000.tar.zst").as_raw_fd(),
        &Selection::All,
        &mut || {
            polls += 1;
            true
        },
    );
    assert!(matches!(result, Err(ArchiveError::Cancelled)));
    assert_eq!(polls, 1);
}

// ---------------------------------------------------------------------------------------------
// inspect_for_extraction + evaluate_selection.
// ---------------------------------------------------------------------------------------------

#[test]
fn inspect_for_extraction_gathers_the_selected_metadata_in_archive_order_and_stops_early() {
    let selected = inspect_for_extraction(
        archive_fixture("tree.zip").as_raw_fd(),
        &ranges(&[(8, 10), (3, 3)]),
        &mut || false,
    )
    .unwrap();
    assert_eq!(
        selected
            .entries
            .iter()
            .map(|e| e.ordinal)
            .collect::<Vec<_>>(),
        vec![3, 8, 9, 10]
    );
    assert_eq!(
        selected.archive_bytes,
        archive_fixture("tree.zip").metadata().unwrap().len()
    );
    let file = archive_fixture("big-stream.tar.zst");
    let selected =
        inspect_for_extraction(file.as_raw_fd(), &ranges(&[(0, 0)]), &mut || false).unwrap();
    assert_eq!(selected.entries.len(), 1);
    assert_eq!(selected.entries[0].uncompressed, Some(2 * 1024 * 1024));
}

#[test]
fn the_entry_cap_allows_exactly_ten_thousand_and_refuses_one_more_unless_the_selection_is_smaller()
{
    let all = |fixture: &str| {
        inspect_for_extraction(
            archive_fixture(fixture).as_raw_fd(),
            &Selection::All,
            &mut || false,
        )
        .unwrap()
    };
    let allowed = all("hostile/many-small-10000.tar.zst");
    assert_eq!(allowed.entries.len(), 10_000);
    assert!(
        policy::evaluate_selection(allowed.archive_bytes, &allowed.entries, &Limits::default())
            .allowed
    );
    let refused = all("hostile/many-entries.tar.zst");
    assert_eq!(refused.entries.len(), 10_001);
    assert_eq!(
        policy::evaluate_selection(refused.archive_bytes, &refused.entries, &Limits::default())
            .reason,
        Some("Archive contains too many entries.")
    );
    // A sub-selection of the refused archive is what a selection means: allowed.
    let half = inspect_for_extraction(
        archive_fixture("hostile/many-entries.tar.zst").as_raw_fd(),
        &ranges(&[(0, 4_999)]),
        &mut || false,
    )
    .unwrap();
    assert_eq!(half.entries.len(), 5_000);
    assert!(
        policy::evaluate_selection(half.archive_bytes, &half.entries, &Limits::default()).allowed
    );
    // With consent's cap the whole archive is allowed.
    let consented = Limits {
        max_entries: 200_000,
        ..Limits::default()
    };
    assert!(
        policy::evaluate_selection(refused.archive_bytes, &refused.entries, &consented).allowed
    );
}

#[test]
fn evaluate_selection_applies_the_size_rules_over_the_selection_only() {
    let selected = inspect_for_extraction(
        archive_fixture("big-stream.tar.zst").as_raw_fd(),
        &ranges(&[(1, 10)]),
        &mut || false,
    )
    .unwrap();
    // Ten 10 KiB members: 100 KiB, well under any default cap, and a ratio near 1.
    assert!(
        policy::evaluate_selection(
            selected.archive_bytes,
            &selected.entries,
            &Limits::default()
        )
        .allowed
    );
    let tight = Limits {
        max_total_uncompressed_bytes: 50 * 1024,
        ..Limits::default()
    };
    assert_eq!(
        policy::evaluate_selection(selected.archive_bytes, &selected.entries, &tight).reason,
        Some("Archive expands beyond the total extraction limit.")
    );
    let per_file = Limits {
        max_file_bytes: 1024,
        ..Limits::default()
    };
    assert_eq!(
        policy::evaluate_selection(selected.archive_bytes, &selected.entries, &per_file).reason,
        Some("Archive contains a file larger than the extraction limit.")
    );
    // The 2 MiB zeros member alone: 20:1 against the whole file is fine, and 2 MiB is under the
    // default file cap; a selection that includes it is refused only by a ratio limit below 20.
    let zeros = inspect_for_extraction(
        archive_fixture("big-stream.tar.zst").as_raw_fd(),
        &ranges(&[(0, 0)]),
        &mut || false,
    )
    .unwrap();
    assert!(
        policy::evaluate_selection(zeros.archive_bytes, &zeros.entries, &Limits::default()).allowed
    );
    let strict_ratio = Limits {
        max_compression_ratio: 10.0,
        ..Limits::default()
    };
    assert_eq!(
        policy::evaluate_selection(zeros.archive_bytes, &zeros.entries, &strict_ratio).reason,
        Some("Archive contains a suspicious compression ratio.")
    );
    // Duplicate keys within a selection are refused (defence in depth).
    let dup = inspect_for_extraction(
        archive_fixture("messy-paths.tar").as_raw_fd(),
        &Selection::Paths(vec!["dup.txt".into()]),
        &mut || false,
    )
    .unwrap();
    assert_eq!(dup.entries.len(), 2);
    assert_eq!(
        policy::evaluate_selection(dup.archive_bytes, &dup.entries, &Limits::default()).reason,
        Some("Archive contains duplicate or colliding paths.")
    );
}

/// Builds a tar with the system GNU `tar` from a tree `populate` creates; `--format=gnu` so a
/// 300-byte name and a 70-deep path are representable.
fn gnu_tar(dir: &Path, populate: impl FnOnce(&Path), members: &[&str], extra: &[&str]) -> PathBuf {
    let root = dir.join("root");
    fs::create_dir_all(&root).unwrap();
    populate(&root);
    let archive_path = dir.join("fixture.tar");
    let status = Command::new("tar")
        .arg("--format=gnu")
        .arg("--owner=0")
        .arg("--group=0")
        .arg("--numeric-owner")
        .arg("--mtime=2020-01-01 00:00:00 UTC")
        .args(extra)
        .arg("-cf")
        .arg(&archive_path)
        .arg("-C")
        .arg(&root)
        .args(members)
        .status()
        .expect("the `tar` tool must be available to build this test's fixture");
    assert!(status.success(), "tar -cf failed building the test fixture");
    archive_path
}

/// The ratio bomb of design section 2.8, built at test time: 300 MiB of zeros gzipped to a few
/// hundred KiB. Refused by the ratio rule in every mode -- the rule no consent relaxes.
#[test]
fn a_ratio_bomb_built_at_test_time_is_refused_by_evaluate_selection_in_every_mode() {
    let dir = tempdir();
    let archive_path = gnu_tar(
        dir.path(),
        |root| {
            let file = File::create(root.join("zeros.bin")).unwrap();
            file.set_len(300 * 1024 * 1024).unwrap();
        },
        &["zeros.bin"],
        &["-z"],
    );
    let file = File::open(&archive_path).unwrap();
    assert!(
        file.metadata().unwrap().len() < 2 * 1024 * 1024,
        "the bomb is small"
    );
    let selected =
        inspect_for_extraction(file.as_raw_fd(), &Selection::All, &mut || false).unwrap();
    assert_eq!(selected.entries[0].uncompressed, Some(300 * 1024 * 1024));
    for limits in [
        Limits::default(),
        // Consent: the sizes a 20 GB destination allows, entries at the ceiling.
        Limits {
            max_archive_bytes: 256 * 1024 * 1024 * 1024,
            max_file_bytes: 256 * 1024 * 1024 * 1024,
            max_total_uncompressed_bytes: 20 * 1024 * 1024 * 1024,
            max_entries: 200_000,
            ..Limits::default()
        },
    ] {
        assert_eq!(
            policy::evaluate_selection(selected.archive_bytes, &selected.entries, &limits).reason,
            Some("Archive contains a suspicious compression ratio.")
        );
    }
}

/// A GNU long-name member: the `././@LongLink` `L` header carrying the full name, then the
/// member's own header (whose name field holds the first 99 characters) and body.
fn gnu_long_name_member(out: &mut File, name: &str, body: &[u8]) {
    let mut long = name.as_bytes().to_vec();
    long.push(0);
    out.write_all(&ustar_header("././@LongLink", long.len() as u64, b'L'))
        .unwrap();
    out.write_all(&padded(long)).unwrap();
    let short: String = name.chars().take(99).collect();
    out.write_all(&ustar_header(&short, body.len() as u64, b'0'))
        .unwrap();
    out.write_all(&padded(body.to_vec())).unwrap();
}

/// The oversized-header archive of design section 2.8, built at test time (no filesystem can hold
/// a 300-byte name, so the tar is written by hand with GNU long-name headers): a 300-character
/// name and a 70-deep path. Structural, so refused by `structural_check`/`evaluate` whatever the
/// limits, and failed per entry (kind `Other`) if a stale plan ever selects them.
#[test]
fn an_oversized_header_archive_built_at_test_time_is_refused_structurally_and_failed_per_entry() {
    let dir = tempdir();
    let long_name = "n".repeat(300);
    let deep = format!(
        "{}/leaf",
        (0..70).map(|_| "d").collect::<Vec<_>>().join("/")
    );
    let archive_path = dir.path().join("oversized.tar");
    let mut out = File::create(&archive_path).unwrap();
    gnu_long_name_member(&mut out, &long_name, b"long");
    gnu_long_name_member(&mut out, &deep, b"deep");
    out.write_all(&ustar_header("fine.txt", 4, b'0')).unwrap();
    out.write_all(&padded(b"fine".to_vec())).unwrap();
    out.write_all(&[0u8; 1024]).unwrap();
    drop(out);
    let file = File::open(&archive_path).unwrap();
    let inspection = inspect(file.as_raw_fd(), &Limits::default()).unwrap();
    assert_eq!(
        inspection
            .entries
            .iter()
            .map(|e| e.path.as_str())
            .collect::<Vec<_>>(),
        vec![long_name.as_str(), deep.as_str(), "fine.txt"]
    );
    assert_eq!(
        inspection.structural_refusal.as_deref(),
        Some("Archive contains an unsafe path segment."),
        "the 300-byte name fires first"
    );
    assert_eq!(
        policy::structural_check(&inspection.entries[1], 64, 255),
        Some("Archive path nesting is too deep.")
    );
    assert_eq!(
        policy::structural_check(&inspection.entries[2], 64, 255),
        None
    );
    let mut sink = Recording::default();
    let report = extract_blocks(
        File::open(&archive_path).unwrap().as_raw_fd(),
        &Selection::All,
        &ExtractLimits::default(),
        &mut sink,
    )
    .unwrap();
    assert_eq!(
        sink.failed(),
        vec![(0, FailKind::Other), (1, FailKind::Other)]
    );
    assert!(!sink.data.contains_key(&0) && !sink.data.contains_key(&1));
    assert_eq!(sink.ended(), vec![(2, 4)]);
    assert_eq!((report.entries_written, report.entries_failed), (1, 2));
}

// ---------------------------------------------------------------------------------------------
// The adapters.
// ---------------------------------------------------------------------------------------------

#[test]
fn extract_and_extract_entry_at_turn_a_per_entry_failure_into_failed() {
    struct Nowhere;
    impl crate::DestinationProvider for Nowhere {
        fn open(
            &mut self,
            _: &crate::ArchiveEntry,
        ) -> Result<Option<std::os::fd::BorrowedFd<'_>>, ArchiveError> {
            Ok(None)
        }
        fn done(&mut self, _: &crate::ArchiveEntry, _: u64) -> Result<(), ArchiveError> {
            Ok(())
        }
    }
    // Declining every entry never reads data, so the CRC verdict is never reached: no error.
    let report = extract(
        archive_fixture("crc-bad.zip").as_raw_fd(),
        &Selection::All,
        &ExtractLimits::default(),
        &mut Nowhere,
    )
    .unwrap();
    assert_eq!(report.skipped, 3);
    // Writing it reaches the verdict: `Failed`, with what was written before it in place.
    let dir = tempdir();
    let mut provider = crate::extract_tests::TempProvider::new(dir.path());
    let result = extract(
        archive_fixture("crc-bad.zip").as_raw_fd(),
        &Selection::All,
        &ExtractLimits::default(),
        &mut provider,
    );
    assert!(
        matches!(&result, Err(ArchiveError::Failed(m)) if m.contains("ZIP bad CRC")),
        "{result:?}"
    );
    assert_eq!(provider.read("good-before.txt"), b"before\n");
    // The single-entry adapter: the one entry asked for is the failed one.
    let out = tempdir();
    let dest = File::create(out.path().join("bad")).unwrap();
    let result = extract_entry_at(
        archive_fixture("crc-bad.zip").as_raw_fd(),
        1,
        "bad.txt",
        &ExtractLimits::default(),
        dest.as_raw_fd(),
    );
    assert!(matches!(result, Err(ArchiveError::Failed(_))), "{result:?}");
    let dest = File::create(out.path().join("after")).unwrap();
    assert_eq!(
        extract_entry_at(
            archive_fixture("crc-bad.zip").as_raw_fd(),
            2,
            "good-after.txt",
            &ExtractLimits::default(),
            dest.as_raw_fd(),
        )
        .unwrap(),
        6
    );
}

#[test]
fn extract_limits_carry_the_structural_bounds_from_the_policy_limits() {
    let limits = ExtractLimits::from(&Limits {
        max_path_depth: 7,
        max_name_length: 9,
        ..Limits::default()
    });
    assert_eq!((limits.max_path_depth, limits.max_name_length), (7, 9));
    assert_eq!(ExtractLimits::default().max_path_depth, 64);
    assert_eq!(ExtractLimits::default().max_name_length, 255);
}

#[test]
fn errors_display_their_message() {
    assert_eq!(ArchiveError::Failed("x".into()).to_string(), "x");
    assert_eq!(ArchiveError::Cancelled.to_string(), "cancelled");
    let mut buf = Vec::new();
    write!(buf, "{}", ArchiveError::Cancelled).unwrap();
    assert_eq!(buf, b"cancelled");
}

// ---------------------------------------------------------------------------------------------
// The header-level FAILED rule, on a pax tar built by hand.
// ---------------------------------------------------------------------------------------------

/// One 512-byte ustar header with a correct checksum.
fn ustar_header(name: &str, size: u64, typeflag: u8) -> [u8; 512] {
    let mut block = [0u8; 512];
    block[..name.len()].copy_from_slice(name.as_bytes());
    block[100..108].copy_from_slice(b"0000644\0");
    block[108..116].copy_from_slice(b"0000000\0");
    block[116..124].copy_from_slice(b"0000000\0");
    block[124..136].copy_from_slice(format!("{size:011o}\0").as_bytes());
    block[136..148].copy_from_slice(b"13575444000\0");
    block[148..156].copy_from_slice(b"        ");
    block[156] = typeflag;
    block[257..263].copy_from_slice(b"ustar\0");
    block[263..265].copy_from_slice(b"00");
    let sum: u32 = block.iter().map(|b| *b as u32).sum();
    block[148..156].copy_from_slice(format!("{sum:06o}\0 ").as_bytes());
    block
}

fn padded(mut body: Vec<u8>) -> Vec<u8> {
    let pad = (512 - body.len() % 512) % 512;
    body.extend(std::iter::repeat_n(0u8, pad));
    body
}

/// A tar whose middle member carries a pax extended header with a `GNU.sparse.map` attribute
/// longer than libarchive's 8 MiB sparse-map limit ("Unreasonably large sparse map"). The tar
/// reader escalates the failed attribute to `ARCHIVE_FATAL` at the header, so this is the
/// header-fatal case: the pass ends blaming the header it could not read, and no constructible
/// input was found that makes `archive_read_next_header` return `ARCHIVE_FAILED` for a tar or a
/// ZIP (the header-level FAILED rule stays exercised by no fixture -- recorded). Built here because
/// no committed fixture can be this large.
fn tar_with_a_fatal_pax_header(dir: &Path) -> PathBuf {
    let path = dir.join("fatal-header.tar");
    let mut out = File::create(&path).unwrap();
    out.write_all(&ustar_header("good-0.txt", 6, b'0')).unwrap();
    out.write_all(&padded(b"first\n".to_vec())).unwrap();
    // The pax body: one record `<len> GNU.sparse.map=<value>\n` with an 8 MiB + 1 value.
    let value_len = 8 * 1024 * 1024 + 1;
    let key = "GNU.sparse.map=";
    // The record length counts its own decimal digits: find the digit count that agrees.
    let mut record_len = 0usize;
    for digits in 1..=12 {
        let candidate = digits + 1 + key.len() + value_len + 1;
        if candidate.to_string().len() == digits {
            record_len = candidate;
            break;
        }
    }
    let mut body = format!("{record_len} {key}").into_bytes();
    body.extend(std::iter::repeat_n(b'1', value_len));
    body.push(b'\n');
    assert_eq!(body.len(), record_len);
    out.write_all(&ustar_header(
        "./PaxHeaders/bad.bin",
        body.len() as u64,
        b'x',
    ))
    .unwrap();
    out.write_all(&padded(body)).unwrap();
    out.write_all(&ustar_header("bad.bin", 9, b'0')).unwrap();
    out.write_all(&padded(b"bad body\n".to_vec())).unwrap();
    out.write_all(&ustar_header("good-2.txt", 6, b'0')).unwrap();
    out.write_all(&padded(b"third\n".to_vec())).unwrap();
    out.write_all(&[0u8; 1024]).unwrap();
    path
}

#[test]
fn a_header_libarchive_cannot_parse_is_fatal_after_the_entries_before_it_and_lists_partially() {
    let dir = tempdir();
    let path = tar_with_a_fatal_pax_header(dir.path());
    // The listing keeps M3.2's mapping: damage after the first entry is a partial listing.
    let mut listing = Vec::new();
    let pass = crate::inspect_into(
        File::open(&path).unwrap().as_raw_fd(),
        &Limits::default(),
        &mut listing,
    )
    .unwrap();
    assert_eq!(pass.inspection.entries.len(), 1);
    assert!(
        pass.partial
            .as_deref()
            .is_some_and(|m| m.contains("sparse map")),
        "{:?}",
        pass.partial
    );
    // The extraction pass: ordinal 0 is complete, the header at ordinal 1 is fatal and blamed.
    let mut sink = Recording::default();
    let outcome = extract_blocks_cancellable(
        File::open(&path).unwrap().as_raw_fd(),
        &Selection::All,
        &ExtractLimits::default(),
        &mut sink,
        &mut || false,
    );
    assert!(
        matches!(&outcome.result, Err(ArchiveError::Fatal(m)) if m.contains("sparse map")),
        "{:?}",
        outcome.result
    );
    assert_eq!(outcome.stop_ordinal, Some(1));
    assert_eq!(
        sink.events,
        vec![
            Event::Begin(0, "good-0.txt".into(), EntryKind::File),
            Event::End(0, 6, None),
        ]
    );
    assert_eq!(outcome.report.entries_written, 1);
    // The header pass before an extraction fails the same way (nothing gathered is usable).
    assert!(matches!(
        inspect_for_extraction(
            File::open(&path).unwrap().as_raw_fd(),
            &Selection::All,
            &mut || false
        ),
        Err(ArchiveError::Fatal(_))
    ));
}

#[test]
fn a_per_entry_decode_failure_is_kind_decode_and_the_pass_continues() {
    let mut sink = Recording::default();
    let report = extract_blocks(
        archive_fixture("ppmd-bad.zip").as_raw_fd(),
        &Selection::All,
        &ExtractLimits::default(),
        &mut sink,
    )
    .unwrap();
    assert_eq!(
        sink.paths(),
        vec!["good-before.txt", "ppmd.bin", "good-after.txt"]
    );
    assert_eq!(sink.failed(), vec![(1, FailKind::Decode)]);
    assert!(
        sink_message(&sink, 1).contains("PPMd8"),
        "{}",
        sink_message(&sink, 1)
    );
    assert!(!sink.data.contains_key(&1));
    assert_eq!(sink.data[&2], b"after\n");
    assert_eq!((report.entries_written, report.entries_failed), (2, 1));
}

fn sink_message(sink: &Recording, ordinal: u32) -> String {
    sink.events
        .iter()
        .find_map(|e| match e {
            Event::Failed(o, _, m) if *o == ordinal => Some(m.clone()),
            _ => None,
        })
        .unwrap_or_default()
}
