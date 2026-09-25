//! M3.3a tests (`docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md` section 2.10, Rust): the listing
//! codec's golden bytes (the cross-language contract with `archive/ArchiveListingCodec.kt`), the
//! ordinal contract (raw header indices, a `./` root counted but not listed), `extract_entry_at`
//! (exact by ordinal and path, stops after the match, caps enforced, `NotFound` with fields), and
//! the partial listing of a tar damaged after its third header.
//!
//! The golden files live under `app/src/test/resources/fixtures/archives/<stem>.fzl`. Running with
//! `FYLZ_WRITE_GOLDEN=1` (re)writes them from this writer; without it the test asserts the writer
//! reproduces the committed bytes exactly, so a codec change cannot land on one side only.

use crate::extract_entry_at;
use crate::inspect;
use crate::inspect_into;
use crate::listing;
use crate::tests::open_fixture;
use crate::tests::tempdir;
use crate::ArchiveError;
use crate::EntryKind;
use crate::EntryMetadata;
use crate::ExtractLimits;
use crate::Limits;
use std::fs;
use std::fs::File;
use std::io::Read;
use std::io::Seek;
use std::io::Write;
use std::os::unix::io::AsRawFd;
use std::path::Path;
use std::path::PathBuf;
use std::process::Command;

/// The fixtures whose listings are committed as golden `.fzl` files for the Kotlin reader.
const GOLDEN: [&str; 8] = [
    "sample-cd.zip",
    "messy-paths.tar",
    "backslash.zip",
    "mixed-links.tar",
    "implicit-dirs.zip",
    "damaged-after-3.tar",
    "dot-rooted.tar",
    "sample-entries.zip",
];

fn archive_fixture(name: &str) -> File {
    open_fixture(&format!("archives/{name}"))
}

fn golden_dir() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../app/src/test/resources/fixtures/archives")
}

fn golden_path(fixture: &str) -> PathBuf {
    let stem = fixture.rsplit_once('.').map_or(fixture, |(stem, _)| stem);
    golden_dir().join(format!("{stem}.fzl"))
}

/// Lists `fixture` through `inspect_into` into memory.
fn listing_of(
    fixture: &str,
    limits: &Limits,
) -> (Result<crate::ListingPass, ArchiveError>, Vec<u8>) {
    let mut sink = Vec::new();
    let result = inspect_into(archive_fixture(fixture).as_raw_fd(), limits, &mut sink);
    (result, sink)
}

/// A record as this test's own reader decodes it -- deliberately independent of the writer so
/// the two halves are checked against each other.
#[derive(Debug, Clone, PartialEq, Eq)]
struct Decoded {
    ordinal: u32,
    path: String,
    kind: u8,
    flags: u8,
    uncompressed: u64,
    mtime: i64,
    mode: u32,
    link_target: Option<String>,
}

struct DecodedListing {
    records: Vec<Decoded>,
    count: u32,
    partial: bool,
}

fn decode(bytes: &[u8]) -> DecodedListing {
    assert_eq!(&bytes[..4], listing::MAGIC, "magic");
    let mut at = 4;
    let mut records = Vec::new();
    let u32_at = |at: usize| u32::from_le_bytes(bytes[at..at + 4].try_into().unwrap());
    loop {
        let tag = bytes[at];
        at += 1;
        if tag == listing::TAG_TRAILER {
            let count = u32_at(at);
            let partial = bytes[at + 4];
            assert_eq!(at + 5, bytes.len(), "the trailer ends the file");
            return DecodedListing {
                records,
                count,
                partial: partial == 1,
            };
        }
        assert_eq!(tag, listing::TAG_RECORD, "tag at {at}");
        let ordinal = u32_at(at);
        at += 4;
        let len = u32_at(at) as usize;
        at += 4;
        let path = String::from_utf8(bytes[at..at + len].to_vec()).unwrap();
        at += len;
        let kind = bytes[at];
        let flags = bytes[at + 1];
        at += 2;
        let uncompressed = u64::from_le_bytes(bytes[at..at + 8].try_into().unwrap());
        at += 8;
        let mtime = i64::from_le_bytes(bytes[at..at + 8].try_into().unwrap());
        at += 8;
        let mode = u32_at(at);
        at += 4;
        let link_target = if flags & listing::FLAG_HAS_LINK_TARGET != 0 {
            let len = u32_at(at) as usize;
            at += 4;
            let target = String::from_utf8(bytes[at..at + len].to_vec()).unwrap();
            at += len;
            Some(target)
        } else {
            None
        };
        records.push(Decoded {
            ordinal,
            path,
            kind,
            flags,
            uncompressed,
            mtime,
            mode,
            link_target,
        });
    }
}

fn expected_record(entry: &EntryMetadata) -> Decoded {
    let mut flags = 0u8;
    if entry.encrypted_data {
        flags |= listing::FLAG_ENCRYPTED_DATA;
    }
    if entry.encrypted_metadata {
        flags |= listing::FLAG_ENCRYPTED_METADATA;
    }
    if entry.uncompressed.is_none() {
        flags |= listing::FLAG_SIZE_UNKNOWN;
    }
    if entry.mtime.is_none() {
        flags |= listing::FLAG_MTIME_UNKNOWN;
    }
    if entry.name_lossy {
        flags |= listing::FLAG_NAME_LOSSY;
    }
    if entry.link_target.is_some() {
        flags |= listing::FLAG_HAS_LINK_TARGET;
    }
    Decoded {
        ordinal: entry.ordinal,
        path: entry.path.clone(),
        kind: listing::kind_code(entry.kind),
        flags,
        uncompressed: entry.uncompressed.unwrap_or(0),
        mtime: entry.mtime.unwrap_or(0),
        mode: entry.mode,
        link_target: entry.link_target.clone(),
    }
}

// ---------------------------------------------------------------------------------------------
// The golden files.
// ---------------------------------------------------------------------------------------------

#[test]
fn golden_listings_match_the_committed_fzl_files() {
    let write = std::env::var_os("FYLZ_WRITE_GOLDEN").is_some_and(|v| v == "1");
    if write {
        fs::create_dir_all(golden_dir()).unwrap();
    }
    for fixture in GOLDEN {
        let (result, bytes) = listing_of(fixture, &Limits::default());
        result.unwrap_or_else(|e| panic!("{fixture}: {e}"));
        let path = golden_path(fixture);
        if write {
            fs::write(&path, &bytes).unwrap();
            eprintln!("wrote {} ({} bytes)", path.display(), bytes.len());
            continue;
        }
        let committed = fs::read(&path).unwrap_or_else(|e| {
            panic!(
                "{}: {e} (run `FYLZ_WRITE_GOLDEN=1 cargo test -p fylz-archive golden` to write it)",
                path.display()
            )
        });
        assert!(
            committed == bytes,
            "{fixture}: the writer no longer reproduces the committed {} ({} vs {} bytes); \
             regenerate it with FYLZ_WRITE_GOLDEN=1 and update the Kotlin reader test in lockstep",
            path.display(),
            committed.len(),
            bytes.len()
        );
    }
}

// ---------------------------------------------------------------------------------------------
// inspect_into: one record per entry, agreeing with inspect.
// ---------------------------------------------------------------------------------------------

#[test]
fn inspect_into_writes_one_record_per_entry_and_the_same_inspection() {
    for fixture in [
        "sample-cd.zip",
        "mixed-links.tar",
        "sample.iso",
        "sample-lzma2.7z",
    ] {
        let plain = inspect(archive_fixture(fixture).as_raw_fd(), &Limits::default()).unwrap();
        let (pass, bytes) = listing_of(fixture, &Limits::default());
        let pass = pass.unwrap();
        assert_eq!(pass.inspection, plain, "{fixture}: inspection differs");
        assert_eq!(pass.partial, None, "{fixture}");
        let decoded = decode(&bytes);
        assert!(!decoded.partial, "{fixture}");
        assert_eq!(
            decoded.count as usize,
            plain.entries.len(),
            "{fixture}: trailer count"
        );
        let expected: Vec<Decoded> = plain.entries.iter().map(expected_record).collect();
        assert_eq!(decoded.records, expected, "{fixture}: records");
    }
}

#[test]
fn every_flag_kind_and_link_target_round_trips_on_mixed_links() {
    let (pass, bytes) = listing_of("mixed-links.tar", &Limits::default());
    let entries = pass.unwrap().inspection.entries;
    let decoded = decode(&bytes).records;
    let by_path = |p: &str| decoded.iter().find(|r| r.path == p).unwrap().clone();
    assert_eq!(by_path("target.txt").kind, listing::KIND_FILE);
    let symlink = by_path("link-to-target");
    assert_eq!(symlink.kind, listing::KIND_SYMLINK);
    assert_eq!(symlink.link_target.as_deref(), Some("target.txt"));
    assert_ne!(symlink.flags & listing::FLAG_HAS_LINK_TARGET, 0);
    let hardlink = by_path("hard-to-target");
    assert_eq!(hardlink.kind, listing::KIND_HARDLINK);
    assert_eq!(hardlink.link_target.as_deref(), Some("target.txt"));
    assert_eq!(by_path("fifo").kind, listing::KIND_OTHER);
    // libarchive's tar reader reports a directory with a trailing slash whatever the header said.
    assert_eq!(by_path("dir/").kind, listing::KIND_DIRECTORY);
    // Every mtime is known and stamped; mode bits carry through.
    assert!(decoded
        .iter()
        .all(|r| r.flags & listing::FLAG_MTIME_UNKNOWN == 0 && r.mtime == 1_577_836_800));
    assert_eq!(by_path("dir/").mode, 0o755);
    assert_eq!(entries.len(), 6);
}

#[test]
fn a_streamed_zip_through_a_pipe_would_flag_unknown_sizes_but_the_file_does_not() {
    // On the seekable path every size is known (central directory), so no SIZE_UNKNOWN flag.
    let (_, bytes) = listing_of("sample-streamed.zip", &Limits::default());
    assert!(decode(&bytes)
        .records
        .iter()
        .all(|r| r.flags & listing::FLAG_SIZE_UNKNOWN == 0));
}

// ---------------------------------------------------------------------------------------------
// Ordinals are raw header indices.
// ---------------------------------------------------------------------------------------------

#[test]
fn ordinals_count_every_raw_header_including_a_dot_root() {
    // `tar -C dir -cf x.tar .`: header 0 is the `.` root the engine drops as an entry.
    let inspection = inspect(
        archive_fixture("dot-rooted.tar").as_raw_fd(),
        &Limits::default(),
    )
    .unwrap();
    let ordinals: Vec<(u32, &str)> = inspection
        .entries
        .iter()
        .map(|e| (e.ordinal, e.path.as_str()))
        .collect();
    assert_eq!(
        ordinals,
        vec![(1, "./first.txt"), (2, "./sub/"), (3, "./sub/second.txt")],
        "the root occupies header 0 and is not an entry (libarchive's tar reader gives directories a trailing slash)"
    );
    // The listing carries the same ordinals.
    let (_, bytes) = listing_of("dot-rooted.tar", &Limits::default());
    let decoded = decode(&bytes);
    assert_eq!(
        decoded
            .records
            .iter()
            .map(|r| r.ordinal)
            .collect::<Vec<_>>(),
        vec![1, 2, 3]
    );
    assert_eq!(decoded.count, 3);
    // And extract_entry_at fetches the first member under ordinal 1, not 0.
    let out = tempdir();
    let dest_path = out.path().join("first");
    let dest = File::create(&dest_path).unwrap();
    let written = extract_entry_at(
        archive_fixture("dot-rooted.tar").as_raw_fd(),
        1,
        "./first.txt",
        &ExtractLimits::default(),
        dest.as_raw_fd(),
    )
    .unwrap();
    assert_eq!(written, 6);
    assert_eq!(fs::read(&dest_path).unwrap(), b"first\n");
    // Ordinal 0 is the root, whose path is `.`: asking for the file there is NotFound.
    let dest = File::create(out.path().join("wrong")).unwrap();
    assert_eq!(
        extract_entry_at(
            archive_fixture("dot-rooted.tar").as_raw_fd(),
            0,
            "./first.txt",
            &ExtractLimits::default(),
            dest.as_raw_fd(),
        )
        .unwrap_err()
        .to_string(),
        "no entry \"./first.txt\" at header 0"
    );
}

#[test]
fn the_iso_root_occupies_header_zero_too() {
    let inspection = inspect(
        archive_fixture("sample.iso").as_raw_fd(),
        &Limits::default(),
    )
    .unwrap();
    assert_eq!(
        inspection.entries[0].ordinal, 1,
        "{:?}",
        inspection.entries[0].path
    );
    assert!(inspection.entries.iter().all(|e| e.ordinal >= 1));
    let ordinals: Vec<u32> = inspection.entries.iter().map(|e| e.ordinal).collect();
    let mut sorted = ordinals.clone();
    sorted.dedup();
    assert_eq!(ordinals, sorted, "strictly increasing");
}

#[test]
fn a_zip_without_a_root_starts_at_ordinal_zero_and_extract_agrees() {
    let inspection = inspect(
        archive_fixture("sample-cd.zip").as_raw_fd(),
        &Limits::default(),
    )
    .unwrap();
    assert_eq!(
        inspection
            .entries
            .iter()
            .map(|e| e.ordinal)
            .collect::<Vec<_>>(),
        (0..8).collect::<Vec<u32>>()
    );
    let hello = inspection
        .entries
        .iter()
        .find(|e| e.path == "hello.txt")
        .unwrap();
    let out = tempdir();
    let dest_path = out.path().join("hello");
    let dest = File::create(&dest_path).unwrap();
    let written = extract_entry_at(
        archive_fixture("sample-cd.zip").as_raw_fd(),
        hello.ordinal as usize,
        "hello.txt",
        &ExtractLimits::default(),
        dest.as_raw_fd(),
    )
    .unwrap();
    assert_eq!(written, 11);
    assert_eq!(fs::read(&dest_path).unwrap(), b"hello world");
}

// ---------------------------------------------------------------------------------------------
// extract_entry_at: exact, early exit, caps, NotFound.
// ---------------------------------------------------------------------------------------------

#[test]
fn extract_entry_at_is_exact_by_ordinal_for_case_and_duplicate_members() {
    let inspection = inspect(
        archive_fixture("messy-paths.tar").as_raw_fd(),
        &Limits::default(),
    )
    .unwrap();
    let ordinal_of = |path: &str, nth: usize| -> usize {
        inspection
            .entries
            .iter()
            .filter(|e| e.path == path)
            .nth(nth)
            .unwrap_or_else(|| panic!("no {path} #{nth}"))
            .ordinal as usize
    };
    let out = tempdir();
    let fetch = |ordinal: usize, path: &str| -> Result<Vec<u8>, ArchiveError> {
        let dest_path = out.path().join(format!("o{ordinal}"));
        let dest = File::create(&dest_path).unwrap();
        extract_entry_at(
            archive_fixture("messy-paths.tar").as_raw_fd(),
            ordinal,
            path,
            &ExtractLimits::default(),
            dest.as_raw_fd(),
        )?;
        Ok(fs::read(&dest_path).unwrap())
    };
    // Case is significant: two entries, two ordinals, two bodies.
    assert_eq!(
        fetch(ordinal_of("README", 0), "README").unwrap(),
        b"upper\n"
    );
    assert_eq!(
        fetch(ordinal_of("readme", 0), "readme").unwrap(),
        b"lower\n"
    );
    assert_ne!(ordinal_of("README", 0), ordinal_of("readme", 0));
    // Duplicate members (`tar -r`): each ordinal yields its own body.
    assert_eq!(
        fetch(ordinal_of("dup.txt", 0), "dup.txt").unwrap(),
        b"first\n"
    );
    assert_eq!(
        fetch(ordinal_of("dup.txt", 1), "dup.txt").unwrap(),
        b"second\n"
    );
    // The raw path is what is compared: `./a` is not `a`, `/abs` is not `abs`.
    assert_eq!(fetch(ordinal_of("./a", 0), "./a").unwrap(), b"a\n");
    assert!(matches!(
        fetch(ordinal_of("./a", 0), "a"),
        Err(ArchiveError::NotFound { ordinal: 0, ref path }) if path == "a"
    ));
    // A mismatched path at a valid ordinal, and an ordinal past the end.
    let mismatch = fetch(ordinal_of("README", 0), "readme").unwrap_err();
    assert!(
        matches!(mismatch, ArchiveError::NotFound { ordinal, ref path } if ordinal == ordinal_of("README", 0) && path == "readme"),
        "{mismatch:?}"
    );
    assert!(matches!(
        fetch(500, "README"),
        Err(ArchiveError::NotFound { ordinal: 500, .. })
    ));
}

/// A tar whose second member is far larger than libarchive's 64 KiB read block: after
/// extracting the first member the descriptor must not have reached the end of the file.
#[test]
fn extract_entry_at_stops_reading_after_the_match() {
    let dir = tempdir();
    let root = dir.path().join("root");
    fs::create_dir_all(&root).unwrap();
    fs::write(root.join("first.txt"), b"first member").unwrap();
    fs::write(root.join("second.bin"), vec![0x5Au8; 400 * 1024]).unwrap();
    let archive_path = dir.path().join("two.tar");
    let status = Command::new("tar")
        .arg("--format=ustar")
        .arg("--owner=0")
        .arg("--group=0")
        .arg("--numeric-owner")
        .arg("--mtime=2020-01-01 00:00:00 UTC")
        .arg("-cf")
        .arg(&archive_path)
        .arg("-C")
        .arg(&root)
        .arg("first.txt")
        .arg("second.bin")
        .status()
        .expect("the `tar` tool must be available to build this test's fixture");
    assert!(status.success());
    let archive = File::open(&archive_path).unwrap();
    let total = archive.metadata().unwrap().len();
    assert!(total > 400 * 1024);
    let dest_path = dir.path().join("first");
    let dest = File::create(&dest_path).unwrap();
    let written = extract_entry_at(
        archive.as_raw_fd(),
        0,
        "first.txt",
        &ExtractLimits::default(),
        dest.as_raw_fd(),
    )
    .unwrap();
    assert_eq!(written, 12);
    assert_eq!(fs::read(&dest_path).unwrap(), b"first member");
    let mut archive = archive;
    let offset = archive.stream_position().unwrap();
    assert!(
        offset < total,
        "the pass ran to the end of the archive ({offset} of {total} bytes) instead of stopping after the entry"
    );
    // For contrast, the second member is fetched under its own ordinal, byte-exact.
    let dest_path = dir.path().join("second");
    let dest = File::create(&dest_path).unwrap();
    let written = extract_entry_at(
        File::open(&archive_path).unwrap().as_raw_fd(),
        1,
        "second.bin",
        &ExtractLimits::default(),
        dest.as_raw_fd(),
    )
    .unwrap();
    assert_eq!(written, 400 * 1024);
    assert_eq!(fs::read(&dest_path).unwrap(), vec![0x5Au8; 400 * 1024]);
}

#[test]
fn extract_entry_at_enforces_the_runtime_caps() {
    let inspection = inspect(
        archive_fixture("sample-entries.zip").as_raw_fd(),
        &Limits::default(),
    )
    .unwrap();
    let big = inspection
        .entries
        .iter()
        .find(|e| e.path == "big.bin")
        .unwrap();
    assert_eq!(big.uncompressed, Some(3 * 1024 * 1024));
    let out = tempdir();
    let dest_path = out.path().join("big");
    let dest = File::create(&dest_path).unwrap();
    let capped = ExtractLimits {
        max_file_bytes: 1024 * 1024,
        ..ExtractLimits::default()
    };
    assert!(matches!(
        extract_entry_at(
            archive_fixture("sample-entries.zip").as_raw_fd(),
            big.ordinal as usize,
            "big.bin",
            &capped,
            dest.as_raw_fd(),
        ),
        Err(ArchiveError::LimitExceeded { ref entry, rule: "file" }) if entry == "big.bin"
    ));
    // Nothing past the cap reached the destination.
    assert!(fs::metadata(&dest_path).unwrap().len() <= 1024 * 1024);
    let total_capped = ExtractLimits {
        max_total_uncompressed_bytes: 2 * 1024 * 1024,
        ..ExtractLimits::default()
    };
    let dest = File::create(out.path().join("big2")).unwrap();
    assert!(matches!(
        extract_entry_at(
            archive_fixture("sample-entries.zip").as_raw_fd(),
            big.ordinal as usize,
            "big.bin",
            &total_capped,
            dest.as_raw_fd(),
        ),
        Err(ArchiveError::LimitExceeded { rule: "total", .. })
    ));
    // Under the defaults the 3 MB of zeros come out whole.
    let dest_path = out.path().join("big3");
    let dest = File::create(&dest_path).unwrap();
    assert_eq!(
        extract_entry_at(
            archive_fixture("sample-entries.zip").as_raw_fd(),
            big.ordinal as usize,
            "big.bin",
            &ExtractLimits::default(),
            dest.as_raw_fd(),
        )
        .unwrap(),
        3 * 1024 * 1024
    );
    assert_eq!(fs::metadata(&dest_path).unwrap().len(), 3 * 1024 * 1024);
}

#[test]
fn a_directory_or_link_at_the_ordinal_streams_no_bytes() {
    let inspection = inspect(
        archive_fixture("mixed-links.tar").as_raw_fd(),
        &Limits::default(),
    )
    .unwrap();
    let out = tempdir();
    for path in ["link-to-target", "hard-to-target", "fifo", "dir/"] {
        let entry = inspection.entries.iter().find(|e| e.path == path).unwrap();
        let dest = File::create(out.path().join(path.trim_end_matches('/'))).unwrap();
        assert_eq!(
            extract_entry_at(
                archive_fixture("mixed-links.tar").as_raw_fd(),
                entry.ordinal as usize,
                path,
                &ExtractLimits::default(),
                dest.as_raw_fd(),
            )
            .unwrap(),
            0,
            "{path}"
        );
    }
}

#[test]
fn extract_entry_at_refuses_a_pipe_like_every_other_entry_point() {
    let (reader, _writer) = std::io::pipe().unwrap();
    let out = tempdir();
    let dest = File::create(out.path().join("x")).unwrap();
    assert!(matches!(
        extract_entry_at(
            reader.as_raw_fd(),
            0,
            "x",
            &ExtractLimits::default(),
            dest.as_raw_fd()
        ),
        Err(ArchiveError::NotSeekable(_))
    ));
}

// ---------------------------------------------------------------------------------------------
// Partial listings and the listing bound.
// ---------------------------------------------------------------------------------------------

#[test]
fn a_tar_damaged_after_three_headers_lists_partially_where_inspect_fails() {
    // The plain header pass keeps M3.2's contract: damage is Fatal, no rows.
    assert!(matches!(
        inspect(
            archive_fixture("damaged-after-3.tar").as_raw_fd(),
            &Limits::default()
        ),
        Err(ArchiveError::Fatal(_))
    ));
    // The listing pass keeps what was readable and says so.
    let (pass, bytes) = listing_of("damaged-after-3.tar", &Limits::default());
    let pass = pass.unwrap();
    let message = pass.partial.expect("partial");
    assert!(!message.is_empty());
    assert_eq!(
        pass.inspection
            .entries
            .iter()
            .map(|e| e.path.as_str())
            .collect::<Vec<_>>(),
        vec!["good-0.txt", "good-1.txt", "good-2.txt"]
    );
    let decoded = decode(&bytes);
    assert!(decoded.partial);
    assert_eq!(decoded.count, 3);
    assert_eq!(decoded.records.len(), 3);
    // The three good members are still fetchable by ordinal.
    let out = tempdir();
    let dest_path = out.path().join("g2");
    let dest = File::create(&dest_path).unwrap();
    extract_entry_at(
        archive_fixture("damaged-after-3.tar").as_raw_fd(),
        2,
        "good-2.txt",
        &ExtractLimits::default(),
        dest.as_raw_fd(),
    )
    .unwrap();
    assert_eq!(fs::read(&dest_path).unwrap(), b"member 2\n");
}

#[test]
fn damage_before_the_first_entry_is_still_an_error_on_the_listing_path() {
    let mut sink = Vec::new();
    assert!(matches!(
        inspect_into(
            open_fixture("truncated.tar.gz").as_raw_fd(),
            &Limits::default(),
            &mut sink
        ),
        Err(ArchiveError::Fatal(_))
    ));
    let dir = tempdir();
    let path = dir.path().join("not-an-archive");
    File::create(&path)
        .unwrap()
        .write_all(b"nothing like an archive here")
        .unwrap();
    let mut sink = Vec::new();
    assert!(matches!(
        inspect_into(
            File::open(&path).unwrap().as_raw_fd(),
            &Limits::default(),
            &mut sink
        ),
        Err(ArchiveError::Unsupported(_))
    ));
    // No trailer was written on either failure: the Kotlin reader would call this corrupt.
    assert!(sink.len() <= listing::MAGIC.len());
}

#[test]
fn the_listing_bound_stops_the_pass_without_a_trailer() {
    let bounded = Limits {
        max_listing_entries: 10,
        ..Limits::default()
    };
    let (result, bytes) = listing_of("hostile/many-entries.tar.zst", &bounded);
    assert!(matches!(
        result,
        Err(ArchiveError::LimitExceeded { ref entry, rule: "listing" }) if entry == "e00010"
    ));
    // Ten records went out before the bound hit; no trailer follows them.
    assert_eq!(&bytes[..4], listing::MAGIC);
    assert_ne!(bytes[bytes.len() - 6], listing::TAG_TRAILER);
    let mut count = 0;
    let mut at = 4;
    while at < bytes.len() {
        assert_eq!(bytes[at], listing::TAG_RECORD);
        let len = u32::from_le_bytes(bytes[at + 5..at + 9].try_into().unwrap()) as usize;
        at += 1 + 4 + 4 + len + 1 + 1 + 8 + 8 + 4;
        count += 1;
    }
    assert_eq!(count, 10);
}

#[test]
fn a_lossy_name_is_flagged_in_the_listing_and_matched_by_its_lossy_string() {
    // The legacy CP437 ZIP from seek_tests, rebuilt here: byte 0x82 in a name.
    let dir = tempdir();
    let archive_path = crate::seek_tests::build_stored_zip(
        dir.path(),
        &[(b"caf\x82.txt".as_slice(), b"legacy".as_slice())],
    );
    let file = File::open(&archive_path).unwrap();
    let mut sink = Vec::new();
    let pass = inspect_into(file.as_raw_fd(), &Limits::default(), &mut sink).unwrap();
    let entry = &pass.inspection.entries[0];
    assert!(entry.name_lossy);
    assert_eq!(entry.path, "caf\u{FFFD}.txt");
    let decoded = decode(&sink);
    assert_ne!(decoded.records[0].flags & listing::FLAG_NAME_LOSSY, 0);
    assert_eq!(decoded.records[0].path, entry.path);
    // The listing's string is exactly what extract_entry_at matches.
    let dest_path = dir.path().join("out");
    let dest = File::create(&dest_path).unwrap();
    extract_entry_at(
        File::open(&archive_path).unwrap().as_raw_fd(),
        0,
        &entry.path,
        &ExtractLimits::default(),
        dest.as_raw_fd(),
    )
    .unwrap();
    assert_eq!(fs::read(&dest_path).unwrap(), b"legacy");
    let mut check = Vec::new();
    File::open(&dest_path)
        .unwrap()
        .read_to_end(&mut check)
        .unwrap();
    assert_eq!(check, b"legacy");
}

#[test]
fn every_entry_kind_has_a_kind_code_the_listing_writes() {
    for kind in [
        EntryKind::File,
        EntryKind::Directory,
        EntryKind::Symlink,
        EntryKind::Hardlink,
        EntryKind::Other,
    ] {
        let _ = listing::kind_code(kind);
    }
}
