//! Tests for part 3b: the shared seekable open path (`fstat` -> `NotSeekable`, rewind to 0,
//! `open_unchecked`), [inspect]/[inspect_with_policy], and the streaming [extract] with its
//! runtime limits -- per `docs/agent/DESIGN-M31-PART3-EXTRACT-AND-POLICY.md` section 4.2 and the
//! section-6 amendments. Fixtures: part 1's `ar` builder and part 2's committed `core/fixtures/*`
//! (all the same `hello.txt`/`second.txt` pair), plus tars built at test time with the system GNU
//! `tar` for what those cannot hold: directories, symlinks, hardlinks, a fifo, a sparse member,
//! a non-UTF-8 name.

use crate::entries;
use crate::extract;
use crate::filter_names;
use crate::inspect;
use crate::inspect_with_policy;
use crate::read_entry;
use crate::tests::build_ar_fixture;
use crate::tests::open_fixture;
use crate::tests::tempdir;
use crate::ArchiveEntry;
use crate::ArchiveError;
use crate::DestinationProvider;
use crate::EntryKind;
use crate::ExtractLimits;
use crate::ExtractReport;
use crate::Limits;
use crate::Reader;
use crate::Selection;
use std::ffi::OsStr;
use std::fs;
use std::fs::File;
use std::io::Read;
use std::io::Seek;
use std::io::SeekFrom;
use std::io::Write;
use std::os::fd::AsFd;
use std::os::fd::BorrowedFd;
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::PermissionsExt;
use std::os::unix::io::AsRawFd;
use std::path::Path;
use std::path::PathBuf;
use std::process::Command;

const ARCHIVE_FORMAT_TAR: u32 = 0x30000;
const ARCHIVE_FORMAT_ZIP: u32 = 0x50000;
const ARCHIVE_FORMAT_EMPTY: u32 = 0x60000;
const ARCHIVE_FORMAT_AR: u32 = 0x70000;
/// `--mtime='2020-01-01 00:00:00 UTC'`, as [build_tar_fixture] stamps every member.
const FIXTURE_MTIME: i64 = 1_577_836_800;
const LINK_ESCAPES: &str = "Archive contains a link that escapes the extraction folder.";

/// A [DestinationProvider] writing each offered entry to a file under `dir` (slashes flattened to
/// `__`), recording what it was offered and what it was told was finished. Declines directories,
/// as the app's SAF-backed provider will.
pub(crate) struct TempProvider {
    dir: PathBuf,
    files: Vec<File>,
    offered: Vec<ArchiveEntry>,
    done: Vec<(String, u64)>,
}

impl TempProvider {
    pub(crate) fn new(dir: &Path) -> Self {
        let out = dir.join("out");
        fs::create_dir_all(&out).unwrap();
        TempProvider {
            dir: out,
            files: Vec::new(),
            offered: Vec::new(),
            done: Vec::new(),
        }
    }

    fn output_path(&self, entry_path: &str) -> PathBuf {
        self.dir.join(entry_path.replace('/', "__"))
    }

    pub(crate) fn read(&self, entry_path: &str) -> Vec<u8> {
        fs::read(self.output_path(entry_path))
            .unwrap_or_else(|e| panic!("reading the output for {entry_path}: {e}"))
    }

    pub(crate) fn offered_paths(&self) -> Vec<&str> {
        self.offered.iter().map(|e| e.path.as_str()).collect()
    }
}

impl DestinationProvider for TempProvider {
    fn open(&mut self, entry: &ArchiveEntry) -> Result<Option<BorrowedFd<'_>>, ArchiveError> {
        self.offered.push(entry.clone());
        if entry.is_directory {
            return Ok(None);
        }
        let file = File::create(self.output_path(&entry.path))
            .map_err(|e| ArchiveError::Fatal(format!("test provider: {e}")))?;
        self.files.push(file);
        Ok(Some(self.files.last().unwrap().as_fd()))
    }

    fn done(&mut self, entry: &ArchiveEntry, bytes_written: u64) -> Result<(), ArchiveError> {
        self.done.push((entry.path.clone(), bytes_written));
        Ok(())
    }
}

/// Builds a tar with the system `tar` (GNU tar, on every Ubuntu runner and in this workspace)
/// from the tree `populate` creates under a fresh root, archiving exactly `members` in that
/// order and nothing else (`--no-recursion`: a listed directory is just its own entry, and a
/// file listed after its directory is never archived twice -- GNU tar would store the second
/// occurrence as a hardlink), names relative to the root so GNU tar's `./` prefix never appears,
/// with fixed owner and mtime so the metadata assertions are deterministic. `extra_args` come
/// after the defaults and may override them (`--format=gnu` for a sparse member).
fn build_tar_fixture(
    dir: &Path,
    populate: impl FnOnce(&Path),
    members: &[&OsStr],
    extra_args: &[&str],
) -> PathBuf {
    let root = dir.join("root");
    fs::create_dir_all(&root).unwrap();
    populate(&root);
    let archive_path = dir.join("fixture.tar");
    let status = Command::new("tar")
        .arg("--format=ustar")
        .arg("--no-recursion")
        .arg("--owner=0")
        .arg("--group=0")
        .arg("--numeric-owner")
        .arg("--mtime=2020-01-01 00:00:00 UTC")
        .args(extra_args)
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

fn write_file(path: &Path, contents: &[u8]) {
    File::create(path).unwrap().write_all(contents).unwrap();
    fs::set_permissions(path, fs::Permissions::from_mode(0o644)).unwrap();
}

fn os(s: &str) -> &OsStr {
    OsStr::new(s)
}

fn hello_pair_fixture(dir: &Path) -> PathBuf {
    build_ar_fixture(
        dir,
        &[
            ("hello.txt", b"hello world"),
            ("second.txt", b"more data, a bit longer"),
        ],
    )
}

// ---------------------------------------------------------------------------------------------
// extract()
// ---------------------------------------------------------------------------------------------

#[test]
fn extract_all_writes_every_member_byte_exact() {
    let dir = tempdir();
    let archive_path = hello_pair_fixture(dir.path());
    let file = File::open(&archive_path).unwrap();
    let mut provider = TempProvider::new(dir.path());
    let report = extract(
        file.as_raw_fd(),
        &Selection::All,
        &ExtractLimits::default(),
        &mut provider,
    )
    .unwrap();
    assert_eq!(
        report,
        ExtractReport {
            entries_written: 2,
            bytes_written: 34,
            skipped: 0,
            skipped_links: 0,
            missing: vec![],
            entries_failed: 0,
        }
    );
    assert_eq!(provider.read("hello.txt"), b"hello world");
    assert_eq!(provider.read("second.txt"), b"more data, a bit longer");
    assert_eq!(
        provider.done,
        vec![
            ("hello.txt".to_string(), 11),
            ("second.txt".to_string(), 23)
        ]
    );
    assert_eq!(provider.offered_paths(), vec!["hello.txt", "second.txt"]);
}

#[test]
fn extract_all_is_byte_exact_for_every_committed_fixture() {
    for name in [
        "sample.tar.lz4",
        "sample.tar.zst",
        "sample.tar.gz",
        "sample.tar.bz2",
        "sample.tar.xz",
        "sample-deflate.zip",
    ] {
        let dir = tempdir();
        let file = open_fixture(name);
        let mut provider = TempProvider::new(dir.path());
        let report = extract(
            file.as_raw_fd(),
            &Selection::All,
            &ExtractLimits::default(),
            &mut provider,
        )
        .unwrap_or_else(|e| panic!("{name}: {e}"));
        assert_eq!(
            (report.entries_written, report.bytes_written),
            (2, 34),
            "{name}"
        );
        assert_eq!(provider.read("hello.txt"), b"hello world", "{name}");
        assert_eq!(
            provider.read("second.txt"),
            b"more data, a bit longer",
            "{name}"
        );
    }
}

#[test]
fn selection_paths_writes_only_the_named_entries_and_reports_the_missing() {
    let dir = tempdir();
    let archive_path = hello_pair_fixture(dir.path());
    let file = File::open(&archive_path).unwrap();
    let mut provider = TempProvider::new(dir.path());
    // `HELLO.TXT` matches `hello.txt` through the policy's normalised key (case-folded), exactly
    // as a duplicate would; `missing.txt` matches nothing and is reported, not an error.
    let selection = Selection::Paths(vec![
        "second.txt".to_string(),
        "missing.txt".to_string(),
        "HELLO.TXT".to_string(),
    ]);
    let report = extract(
        file.as_raw_fd(),
        &selection,
        &ExtractLimits::default(),
        &mut provider,
    )
    .unwrap();
    assert_eq!(
        report,
        ExtractReport {
            entries_written: 2,
            bytes_written: 34,
            skipped: 0,
            skipped_links: 0,
            missing: vec!["missing.txt".to_string()],
            entries_failed: 0,
        }
    );
    // Archive order, not selection order.
    assert_eq!(provider.offered_paths(), vec!["hello.txt", "second.txt"]);

    let file = File::open(&archive_path).unwrap();
    let second_dir = tempdir();
    let mut provider = TempProvider::new(second_dir.path());
    let report = extract(
        file.as_raw_fd(),
        &Selection::Paths(vec!["second.txt".to_string()]),
        &ExtractLimits::default(),
        &mut provider,
    )
    .unwrap();
    assert_eq!((report.entries_written, report.bytes_written), (1, 23));
    assert_eq!(provider.offered_paths(), vec!["second.txt"]);
    assert!(!provider.output_path("hello.txt").exists());
}

#[test]
fn directories_are_offered_and_the_provider_skips_them() {
    let dir = tempdir();
    let archive_path = build_tar_fixture(
        dir.path(),
        |root| {
            fs::create_dir(root.join("dir")).unwrap();
            write_file(&root.join("dir/file.txt"), b"in a directory");
        },
        &[os("dir"), os("dir/file.txt")],
        &[],
    );
    let file = File::open(&archive_path).unwrap();
    let mut provider = TempProvider::new(dir.path());
    let report = extract(
        file.as_raw_fd(),
        &Selection::All,
        &ExtractLimits::default(),
        &mut provider,
    )
    .unwrap();
    assert_eq!(
        report,
        ExtractReport {
            entries_written: 1,
            bytes_written: 14,
            skipped: 1,
            skipped_links: 0,
            missing: vec![],
            entries_failed: 0,
        }
    );
    assert_eq!(
        provider.offered,
        vec![
            ArchiveEntry {
                path: "dir/".to_string(),
                size: 0,
                is_directory: true,
            },
            ArchiveEntry {
                path: "dir/file.txt".to_string(),
                size: 14,
                is_directory: false,
            },
        ]
    );
    assert_eq!(provider.read("dir/file.txt"), b"in a directory");
}

#[test]
fn max_file_bytes_stops_the_extraction_naming_the_entry_and_keeps_earlier_output() {
    let dir = tempdir();
    let archive_path = hello_pair_fixture(dir.path());
    let file = File::open(&archive_path).unwrap();
    let mut provider = TempProvider::new(dir.path());
    let limits = ExtractLimits {
        max_file_bytes: 15,
        ..ExtractLimits::default()
    };
    let result = extract(file.as_raw_fd(), &Selection::All, &limits, &mut provider);
    assert!(
        matches!(
            result,
            Err(ArchiveError::LimitExceeded { ref entry, rule: "file" }) if entry == "second.txt"
        ),
        "{result:?}"
    );
    // The 11-byte member before it is complete and reported; the offending one was never
    // reported done and holds nothing past the cap.
    assert_eq!(provider.read("hello.txt"), b"hello world");
    assert_eq!(provider.done, vec![("hello.txt".to_string(), 11)]);
    assert!(provider.read("second.txt").len() <= 15);
}

#[test]
fn max_total_uncompressed_bytes_stops_the_extraction_likewise() {
    let dir = tempdir();
    let archive_path = hello_pair_fixture(dir.path());
    let file = File::open(&archive_path).unwrap();
    let mut provider = TempProvider::new(dir.path());
    let limits = ExtractLimits {
        max_total_uncompressed_bytes: 30,
        ..ExtractLimits::default()
    };
    let result = extract(file.as_raw_fd(), &Selection::All, &limits, &mut provider);
    assert!(
        matches!(
            result,
            Err(ArchiveError::LimitExceeded { ref entry, rule: "total" }) if entry == "second.txt"
        ),
        "{result:?}"
    );
    assert_eq!(provider.read("hello.txt"), b"hello world");
    assert_eq!(provider.done, vec![("hello.txt".to_string(), 11)]);
    // Exactly at the total is fine.
    let file = File::open(&archive_path).unwrap();
    let mut provider = TempProvider::new(dir.path());
    let limits = ExtractLimits {
        max_total_uncompressed_bytes: 34,
        ..ExtractLimits::default()
    };
    assert_eq!(
        extract(file.as_raw_fd(), &Selection::All, &limits, &mut provider)
            .unwrap()
            .bytes_written,
        34
    );
}

#[test]
fn max_entries_stops_the_extraction_likewise() {
    let dir = tempdir();
    let archive_path = hello_pair_fixture(dir.path());
    let file = File::open(&archive_path).unwrap();
    let mut provider = TempProvider::new(dir.path());
    let limits = ExtractLimits {
        max_entries: 1,
        ..ExtractLimits::default()
    };
    let result = extract(file.as_raw_fd(), &Selection::All, &limits, &mut provider);
    assert!(
        matches!(
            result,
            Err(ArchiveError::LimitExceeded { ref entry, rule: "entries" }) if entry == "second.txt"
        ),
        "{result:?}"
    );
    assert_eq!(provider.offered_paths(), vec!["hello.txt"]);
    assert_eq!(provider.read("hello.txt"), b"hello world");
    // Only *selected* entries count: selecting one of two under the same limit succeeds.
    let file = File::open(&archive_path).unwrap();
    let mut provider = TempProvider::new(dir.path());
    let report = extract(
        file.as_raw_fd(),
        &Selection::Paths(vec!["second.txt".to_string()]),
        &limits,
        &mut provider,
    )
    .unwrap();
    assert_eq!(report.entries_written, 1);
}

#[test]
fn a_truncated_stream_stops_extraction_with_fatal_never_a_panic() {
    for name in [
        "truncated.tar.lz4",
        "truncated.tar.zst",
        "truncated.tar.gz",
        "truncated.tar.bz2",
        "truncated.tar.xz",
    ] {
        let dir = tempdir();
        let file = open_fixture(name);
        let mut provider = TempProvider::new(dir.path());
        let result = extract(
            file.as_raw_fd(),
            &Selection::All,
            &ExtractLimits::default(),
            &mut provider,
        );
        assert!(
            matches!(result, Err(ArchiveError::Fatal(_))),
            "{name}: {result:?}"
        );
    }
}

#[test]
fn links_are_never_materialised_and_other_kinds_are_never_offered() {
    let dir = tempdir();
    let archive_path = build_tar_fixture(
        dir.path(),
        |root| {
            write_file(&root.join("a.txt"), b"target");
            fs::hard_link(root.join("a.txt"), root.join("a-hard.txt")).unwrap();
            std::os::unix::fs::symlink("a.txt", root.join("a-sym")).unwrap();
            let status = Command::new("mkfifo")
                .arg(root.join("pipe"))
                .status()
                .expect("mkfifo");
            assert!(status.success());
        },
        &[os("a.txt"), os("a-hard.txt"), os("a-sym"), os("pipe")],
        &[],
    );
    let file = File::open(&archive_path).unwrap();
    let mut provider = TempProvider::new(dir.path());
    let report = extract(
        file.as_raw_fd(),
        &Selection::All,
        &ExtractLimits::default(),
        &mut provider,
    )
    .unwrap();
    assert_eq!(
        report,
        ExtractReport {
            entries_written: 1,
            bytes_written: 6,
            skipped: 1,
            skipped_links: 2,
            missing: vec![],
            entries_failed: 0,
        }
    );
    assert_eq!(provider.offered_paths(), vec!["a.txt"]);
    assert_eq!(provider.read("a.txt"), b"target");
    // Links still count as selected entries for `max_entries`.
    let file = File::open(&archive_path).unwrap();
    let mut provider = TempProvider::new(dir.path());
    let limits = ExtractLimits {
        max_entries: 2,
        ..ExtractLimits::default()
    };
    let result = extract(file.as_raw_fd(), &Selection::All, &limits, &mut provider);
    assert!(
        matches!(
            result,
            Err(ArchiveError::LimitExceeded { ref entry, rule: "entries" }) if entry == "a-sym"
        ),
        "{result:?}"
    );
}

#[test]
fn sparse_members_are_written_byte_exact_holes_included() {
    let dir = tempdir();
    let original = {
        // 2 MiB with data only in the middle: a leading hole, six bytes, a trailing hole.
        let mut bytes = vec![0u8; 2 * 1024 * 1024];
        bytes[1024 * 1024..1024 * 1024 + 6].copy_from_slice(b"middle");
        bytes
    };
    let archive_path = build_tar_fixture(
        dir.path(),
        |root| {
            let mut file = File::create(root.join("sparse.bin")).unwrap();
            file.set_len(2 * 1024 * 1024).unwrap();
            file.seek(SeekFrom::Start(1024 * 1024)).unwrap();
            file.write_all(b"middle").unwrap();
        },
        &[os("sparse.bin")],
        // GNU sparse entries (ustar has none); `raw` hole detection scans for zero blocks, so the
        // member is sparse whatever the filesystem reports, and libarchive hands back data blocks
        // with gaps between them plus a final EOF offset at the full size.
        &["--format=gnu", "--sparse", "--hole-detection=raw"],
    );
    let file = File::open(&archive_path).unwrap();
    let inspection = inspect(file.as_raw_fd(), &Limits::default()).unwrap();
    assert_eq!(inspection.entries[0].uncompressed, Some(2 * 1024 * 1024));
    let file = File::open(&archive_path).unwrap();
    let mut provider = TempProvider::new(dir.path());
    let report = extract(
        file.as_raw_fd(),
        &Selection::All,
        &ExtractLimits::default(),
        &mut provider,
    )
    .unwrap();
    assert_eq!(report.bytes_written, 2 * 1024 * 1024);
    assert!(provider.read("sparse.bin") == original);
    // The holes count against the caps like any other byte: they are what the file will occupy.
    let file = File::open(&archive_path).unwrap();
    let mut provider = TempProvider::new(dir.path());
    let limits = ExtractLimits {
        max_file_bytes: 1024 * 1024 + 5,
        ..ExtractLimits::default()
    };
    let result = extract(file.as_raw_fd(), &Selection::All, &limits, &mut provider);
    assert!(
        matches!(
            result,
            Err(ArchiveError::LimitExceeded { rule: "file", .. })
        ),
        "{result:?}"
    );
}

#[test]
fn extract_limits_derive_from_the_policy_limits() {
    let limits = Limits {
        max_file_bytes: 1,
        max_total_uncompressed_bytes: 2,
        max_entries: 3,
        ..Limits::default()
    };
    assert_eq!(
        ExtractLimits::from(&limits),
        ExtractLimits {
            max_file_bytes: 1,
            max_total_uncompressed_bytes: 2,
            max_entries: 3,
            max_path_depth: 64,
            max_name_length: 255,
        }
    );
    assert_eq!(
        ExtractLimits::default(),
        ExtractLimits::from(&Limits::default())
    );
}

// ---------------------------------------------------------------------------------------------
// inspect() and inspect_with_policy()
// ---------------------------------------------------------------------------------------------

#[test]
fn inspect_reports_sizes_format_filters_and_the_archive_length() {
    let file = open_fixture("sample.tar.gz");
    let inspection = inspect(file.as_raw_fd(), &Limits::default()).unwrap();
    assert_eq!(inspection.archive_bytes, file.metadata().unwrap().len());
    assert_eq!(inspection.format_code, ARCHIVE_FORMAT_TAR);
    assert!(inspection.format_name.is_some(), "{inspection:?}");
    assert_eq!(inspection.filters, vec!["gzip"]);
    // tar has no notion of encryption: UNSUPPORTED -> None.
    assert_eq!(inspection.has_encrypted_entries, None);
    let names: Vec<&str> = inspection.entries.iter().map(|e| e.path.as_str()).collect();
    assert_eq!(names, vec!["hello.txt", "second.txt"]);
    let hello = &inspection.entries[0];
    assert_eq!(hello.kind, EntryKind::File);
    assert!(!hello.name_lossy);
    assert_eq!(hello.link_target, None);
    assert_eq!(hello.uncompressed, Some(11));
    // Always None: libarchive has no per-entry compressed size (decision 1).
    assert_eq!(hello.compressed, None);
    // The fixture generator's `TarInfo` leaves mtime at 0 and mode at 0644.
    assert_eq!(hello.mtime, Some(0));
    assert_eq!(hello.mode, 0o644);
    assert!(!hello.encrypted_data && !hello.encrypted_metadata);
    assert_eq!(inspection.entries[1].uncompressed, Some(23));

    for (name, filter) in [
        ("sample.tar.lz4", "lz4"),
        ("sample.tar.zst", "zstd"),
        ("sample.tar.bz2", "bzip2"),
        ("sample.tar.xz", "xz"),
    ] {
        let file = open_fixture(name);
        let inspection = inspect(file.as_raw_fd(), &Limits::default()).unwrap();
        assert_eq!(inspection.filters, vec![filter], "{name}");
        assert_eq!(inspection.format_code, ARCHIVE_FORMAT_TAR, "{name}");
        assert_eq!(inspection.entries.len(), 2, "{name}");
    }
}

#[test]
fn inspect_on_a_zip_reports_the_encryption_verdict_and_no_filters() {
    let file = open_fixture("sample-deflate.zip");
    let inspection = inspect(file.as_raw_fd(), &Limits::default()).unwrap();
    assert_eq!(inspection.format_code, ARCHIVE_FORMAT_ZIP);
    assert_eq!(inspection.filters, Vec::<String>::new());
    assert_eq!(inspection.has_encrypted_entries, Some(false));
    assert_eq!(inspection.entries.len(), 2);
    let hello = &inspection.entries[0];
    assert_eq!(hello.uncompressed, Some(11));
    assert_eq!(hello.compressed, None);
    assert_eq!(hello.mode, 0o644);
    assert!(hello.mtime.is_some());
    assert!(!hello.encrypted_data && !hello.encrypted_metadata);
}

#[test]
fn inspect_on_the_ar_fixture_reports_the_ar_family() {
    let dir = tempdir();
    let archive_path = hello_pair_fixture(dir.path());
    let file = File::open(&archive_path).unwrap();
    let inspection = inspect(file.as_raw_fd(), &Limits::default()).unwrap();
    assert_eq!(inspection.format_code, ARCHIVE_FORMAT_AR);
    assert_eq!(
        inspection.archive_bytes,
        fs::metadata(&archive_path).unwrap().len()
    );
    assert_eq!(inspection.entries[0].uncompressed, Some(11));
    assert_eq!(inspection.entries[1].uncompressed, Some(23));
}

#[test]
fn inspect_of_an_empty_file_is_the_empty_format_with_no_entries() {
    let dir = tempdir();
    let path = dir.path().join("empty");
    File::create(&path).unwrap();
    let file = File::open(&path).unwrap();
    let inspection = inspect(file.as_raw_fd(), &Limits::default()).unwrap();
    assert_eq!(inspection.format_code, ARCHIVE_FORMAT_EMPTY);
    assert_eq!(inspection.archive_bytes, 0);
    assert!(inspection.entries.is_empty());
    assert_eq!(inspection.filters, Vec::<String>::new());
}

#[test]
fn inspect_lists_links_and_directories_with_kind_target_mtime_and_mode() {
    let dir = tempdir();
    let archive_path = build_tar_fixture(
        dir.path(),
        |root| {
            fs::create_dir(root.join("dir")).unwrap();
            fs::set_permissions(root.join("dir"), fs::Permissions::from_mode(0o755)).unwrap();
            write_file(&root.join("dir/a.txt"), b"target");
            fs::hard_link(root.join("dir/a.txt"), root.join("dir/a-hard.txt")).unwrap();
            std::os::unix::fs::symlink("a.txt", root.join("dir/a-sym")).unwrap();
        },
        &[
            os("dir"),
            os("dir/a.txt"),
            os("dir/a-hard.txt"),
            os("dir/a-sym"),
        ],
        &[],
    );
    let file = File::open(&archive_path).unwrap();
    let inspection = inspect(file.as_raw_fd(), &Limits::default()).unwrap();
    let by_path = |path: &str| {
        inspection
            .entries
            .iter()
            .find(|e| e.path == path)
            .unwrap_or_else(|| panic!("{path} not listed in {inspection:?}"))
    };
    let directory = by_path("dir/");
    assert_eq!(directory.kind, EntryKind::Directory);
    assert_eq!(directory.mode, 0o755);
    assert_eq!(directory.mtime, Some(FIXTURE_MTIME));
    let regular = by_path("dir/a.txt");
    assert_eq!(regular.kind, EntryKind::File);
    assert_eq!(regular.uncompressed, Some(6));
    assert_eq!(regular.mode, 0o644);
    assert_eq!(regular.mtime, Some(FIXTURE_MTIME));
    // Detected from `archive_entry_hardlink` before the file type, which says regular file.
    let hardlink = by_path("dir/a-hard.txt");
    assert_eq!(hardlink.kind, EntryKind::Hardlink);
    assert_eq!(hardlink.link_target.as_deref(), Some("dir/a.txt"));
    assert_eq!(hardlink.uncompressed, Some(0));
    let symlink = by_path("dir/a-sym");
    assert_eq!(symlink.kind, EntryKind::Symlink);
    assert_eq!(symlink.link_target.as_deref(), Some("a.txt"));
    assert_eq!(symlink.uncompressed, Some(0));
    // A well-formed tree with in-tree links passes the policy.
    let file = File::open(&archive_path).unwrap();
    let (_, decision) = inspect_with_policy(file.as_raw_fd(), &Limits::default()).unwrap();
    assert!(decision.allowed, "{decision:?}");
}

#[test]
fn inspect_with_policy_refuses_a_symlink_escape() {
    let dir = tempdir();
    let archive_path = build_tar_fixture(
        dir.path(),
        |root| {
            write_file(&root.join("innocent.txt"), b"fine");
            std::os::unix::fs::symlink("../../etc/passwd", root.join("escape")).unwrap();
        },
        &[os("innocent.txt"), os("escape")],
        &[],
    );
    let file = File::open(&archive_path).unwrap();
    let (inspection, decision) = inspect_with_policy(file.as_raw_fd(), &Limits::default()).unwrap();
    assert_eq!(inspection.entries.len(), 2);
    assert_eq!(
        inspection.entries[1].link_target.as_deref(),
        Some("../../etc/passwd")
    );
    assert!(!decision.allowed);
    assert_eq!(decision.reason, Some(LINK_ESCAPES));
    // And extract, if a caller ignored the verdict, would still never write the link.
    let file = File::open(&archive_path).unwrap();
    let mut provider = TempProvider::new(dir.path());
    let report = extract(
        file.as_raw_fd(),
        &Selection::All,
        &ExtractLimits::default(),
        &mut provider,
    )
    .unwrap();
    assert_eq!((report.entries_written, report.skipped_links), (1, 1));
    assert_eq!(provider.offered_paths(), vec!["innocent.txt"]);
}

#[test]
fn inspect_with_policy_refuses_what_the_policy_refuses() {
    let file = open_fixture("sample.tar.gz");
    let limits = Limits {
        max_entries: 1,
        ..Limits::default()
    };
    let (inspection, decision) = inspect_with_policy(file.as_raw_fd(), &limits).unwrap();
    assert_eq!(inspection.entries.len(), 2);
    assert_eq!(decision.reason, Some("Archive contains too many entries."));
}

#[test]
fn inspect_stops_past_max_listing_entries_with_the_listing_rule() {
    let dir = tempdir();
    let archive_path = build_tar_fixture(
        dir.path(),
        |root| {
            for name in ["a", "b", "c"] {
                write_file(&root.join(name), name.as_bytes());
            }
        },
        &[os("a"), os("b"), os("c")],
        &[],
    );
    let file = File::open(&archive_path).unwrap();
    let limits = Limits {
        max_listing_entries: 2,
        ..Limits::default()
    };
    let result = inspect(file.as_raw_fd(), &limits);
    assert!(
        matches!(
            result,
            Err(ArchiveError::LimitExceeded { ref entry, rule: "listing" }) if entry == "c"
        ),
        "{result:?}"
    );
    // Exactly at the bound lists everything.
    let file = File::open(&archive_path).unwrap();
    let limits = Limits {
        max_listing_entries: 3,
        ..Limits::default()
    };
    assert_eq!(inspect(file.as_raw_fd(), &limits).unwrap().entries.len(), 3);
}

#[test]
fn inspect_decodes_a_non_utf8_name_lossily_where_the_lookups_refuse_it() {
    let dir = tempdir();
    let raw_name = OsStr::from_bytes(b"caf\xe9.txt");
    let archive_path = build_tar_fixture(
        dir.path(),
        |root| write_file(&root.join(raw_name), b"latin-1 name"),
        &[raw_name],
        &[],
    );
    let file = File::open(&archive_path).unwrap();
    let inspection = inspect(file.as_raw_fd(), &Limits::default()).unwrap();
    assert_eq!(inspection.entries.len(), 1);
    assert_eq!(inspection.entries[0].path, "caf\u{FFFD}.txt");
    assert!(inspection.entries[0].name_lossy);
    // The exact-match listing keeps failing loudly, as before.
    let file = File::open(&archive_path).unwrap();
    assert!(matches!(
        entries(file.as_raw_fd()),
        Err(ArchiveError::NonUtf8Path)
    ));
    // extract() names it the same way inspect() did, so the lossy path selects its own entry.
    let file = File::open(&archive_path).unwrap();
    let mut provider = TempProvider::new(dir.path());
    let report = extract(
        file.as_raw_fd(),
        &Selection::Paths(vec!["caf\u{FFFD}.txt".to_string()]),
        &ExtractLimits::default(),
        &mut provider,
    )
    .unwrap();
    assert_eq!(report.entries_written, 1);
    assert!(report.missing.is_empty());
    assert_eq!(provider.read("caf\u{FFFD}.txt"), b"latin-1 name");
}

#[test]
fn a_non_archive_is_unsupported_from_inspect_and_extract() {
    let dir = tempdir();
    let path = dir.path().join("not-an-archive");
    write_file(&path, b"this is not an archive of any kind");
    let file = File::open(&path).unwrap();
    assert!(matches!(
        inspect(file.as_raw_fd(), &Limits::default()),
        Err(ArchiveError::Unsupported(_))
    ));
    let file = File::open(&path).unwrap();
    let mut provider = TempProvider::new(dir.path());
    assert!(matches!(
        extract(
            file.as_raw_fd(),
            &Selection::All,
            &ExtractLimits::default(),
            &mut provider
        ),
        Err(ArchiveError::Unsupported(_))
    ));
    assert!(provider.offered.is_empty());
}

// ---------------------------------------------------------------------------------------------
// The shared open path: seekability and the rewind.
// ---------------------------------------------------------------------------------------------

/// A pipe holding the whole of `sample.tar.gz`, its write end already closed.
fn pipe_with_fixture() -> std::io::PipeReader {
    let (reader, mut writer) = std::io::pipe().unwrap();
    let mut bytes = Vec::new();
    open_fixture("sample.tar.gz")
        .read_to_end(&mut bytes)
        .unwrap();
    writer.write_all(&bytes).unwrap();
    drop(writer);
    reader
}

#[test]
fn a_pipe_is_refused_as_not_seekable_by_every_entry_point() {
    let reader = pipe_with_fixture();
    let fd = reader.as_raw_fd();
    let not_seekable = |result: Result<(), ArchiveError>| match result {
        Err(ArchiveError::NotSeekable(message)) => message,
        other => panic!("expected NotSeekable, got {other:?}"),
    };
    // Every call fails at `fstat`, before reading a byte, so the pipe's contents survive for the
    // next one.
    assert_eq!(not_seekable(entries(fd).map(drop)), "not seekable (fifo)");
    assert_eq!(
        not_seekable(read_entry(fd, "hello.txt").map(drop)),
        "not seekable (fifo)"
    );
    assert_eq!(
        not_seekable(filter_names(fd).map(drop)),
        "not seekable (fifo)"
    );
    assert_eq!(
        not_seekable(inspect(fd, &Limits::default()).map(drop)),
        "not seekable (fifo)"
    );
    let dir = tempdir();
    let mut provider = TempProvider::new(dir.path());
    assert_eq!(
        not_seekable(
            extract(
                fd,
                &Selection::All,
                &ExtractLimits::default(),
                &mut provider
            )
            .map(drop)
        ),
        "not seekable (fifo)"
    );
    // The contents are still there: the test-only unchecked open streams them.
    let mut streamed = Reader::open_unchecked(fd).unwrap();
    assert_eq!(streamed.next_entry().unwrap().unwrap().path, "hello.txt");
}

#[test]
fn open_unchecked_streams_a_pipe_for_the_negative_controls() {
    let reader = pipe_with_fixture();
    let mut streamed = Reader::open_unchecked(reader.as_raw_fd()).unwrap();
    assert_eq!(streamed.archive_bytes, 0);
    let mut names = Vec::new();
    while let Some(entry) = streamed.next_entry().unwrap() {
        names.push(entry.path);
    }
    assert_eq!(names, vec!["hello.txt", "second.txt"]);
}

#[test]
fn the_reader_rewinds_a_descriptor_left_at_a_nonzero_offset() {
    // The seekable ZIP reader finds the central directory by an absolute seek from the end, so
    // a descriptor that starts mid-file would read the wrong bytes without the rewind.
    let mut file = open_fixture("sample-deflate.zip");
    file.seek(SeekFrom::Start(100)).unwrap();
    let names: Vec<String> = entries(file.as_raw_fd())
        .unwrap()
        .into_iter()
        .map(|e| e.path)
        .collect();
    assert_eq!(names, vec!["hello.txt", "second.txt"]);

    let mut file = open_fixture("sample-deflate.zip");
    file.seek(SeekFrom::End(-7)).unwrap();
    assert_eq!(
        read_entry(file.as_raw_fd(), "second.txt").unwrap(),
        Some(b"more data, a bit longer".to_vec())
    );

    // The same for a streamed format, and `archive_bytes` is the file's whole length regardless.
    let mut file = open_fixture("sample.tar.xz");
    file.seek(SeekFrom::Start(50)).unwrap();
    let inspection = inspect(file.as_raw_fd(), &Limits::default()).unwrap();
    assert_eq!(inspection.entries.len(), 2);
    assert_eq!(inspection.archive_bytes, file.metadata().unwrap().len());
}

#[test]
fn a_directory_descriptor_is_not_seekable_either() {
    let dir = tempdir();
    let file = File::open(dir.path()).unwrap();
    let result = inspect(file.as_raw_fd(), &Limits::default());
    assert!(
        matches!(result, Err(ArchiveError::NotSeekable(ref m)) if m == "not seekable (directory)"),
        "{result:?}"
    );
}
