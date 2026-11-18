//! Host tests: they run the real libarchive build on whatever machine runs `cargo test` (Linux
//! here, macOS in `.github/workflows/apple-ios.yml`). They prove the Rust layer's behaviour; they
//! do **not** prove anything about iOS itself (no iOS target is built in the Linux container).

use super::*;
use std::path::PathBuf;

fn fixture(rel: &str) -> String {
    let p: PathBuf = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../fixtures")
        .join(rel);
    assert!(p.exists(), "missing fixture {}", p.display());
    p.to_string_lossy().into_owned()
}

#[test]
fn version_is_the_workspace_version() {
    assert_eq!(fylz_version(), "0.1.0");
}

#[test]
fn sniff_identifies_by_content_not_extension() {
    let dir = tempfile::tempdir().unwrap();
    let disguised = dir.path().join("photo.jpg");
    std::fs::copy(fixture("sample-deflate.zip"), &disguised).unwrap();
    assert_eq!(
        sniff_path(disguised.to_string_lossy().into_owned()),
        sniff_path(fixture("sample-deflate.zip"))
    );
    assert_ne!(sniff_path(fixture("sample-deflate.zip")), "unknown");
    assert_eq!(sniff_path("/definitely/not/here".into()), "unknown");
}

#[test]
fn inspect_lists_a_zip_and_allows_extraction() {
    let s = archive_inspect(fixture("sample-deflate.zip"), archive_default_limits(), 10).unwrap();
    assert_eq!(s.entry_count, 2);
    assert_eq!(s.file_count, 2);
    assert!(s.extraction_allowed, "{:?}", s.refusal_reason);
    assert_eq!(s.total_uncompressed, Some(34));
    let names: Vec<_> = s.rows.iter().map(|r| r.path.as_str()).collect();
    assert_eq!(names, ["hello.txt", "second.txt"]);
    assert!(!s.rows_truncated);
}

#[test]
fn inspect_truncates_rows_but_not_counts() {
    let s = archive_inspect(fixture("sample-deflate.zip"), archive_default_limits(), 1).unwrap();
    assert_eq!(s.rows.len(), 1);
    assert!(s.rows_truncated);
    assert_eq!(s.entry_count, 2);
}

#[test]
fn inspect_reads_tar_gz_filters() {
    let s = archive_inspect(fixture("sample.tar.gz"), archive_default_limits(), 10).unwrap();
    assert_eq!(s.filters, ["gzip"]);
    assert_eq!(s.file_count, 2);
}

#[test]
fn inspect_a_non_archive_is_unsupported() {
    let dir = tempfile::tempdir().unwrap();
    let junk = dir.path().join("junk.bin");
    std::fs::write(&junk, b"this is not an archive at all, just words").unwrap();
    let err = archive_inspect(
        junk.to_string_lossy().into_owned(),
        archive_default_limits(),
        5,
    )
    .unwrap_err();
    assert!(matches!(err, FylzError::Unsupported { .. }), "{err:?}");
}

#[test]
fn inspect_a_missing_file_is_io() {
    let err = archive_inspect(
        "/definitely/not/here.zip".into(),
        archive_default_limits(),
        5,
    )
    .unwrap_err();
    assert!(matches!(err, FylzError::Io { .. }), "{err:?}");
}

#[test]
fn extract_all_writes_the_tree() {
    let dir = tempfile::tempdir().unwrap();
    let out = archive_extract_all(
        fixture("archives/tree.zip"),
        dir.path().to_string_lossy().into_owned(),
        archive_default_limits(),
    )
    .unwrap();
    assert!(out.entries_written >= 1);
    assert!(out.bytes_written > 0);
    // Whatever the tree holds, every extracted path stays inside the destination.
    fn walk(d: &Path, n: &mut usize) {
        for e in std::fs::read_dir(d).unwrap() {
            let e = e.unwrap();
            *n += 1;
            if e.file_type().unwrap().is_dir() {
                walk(&e.path(), n);
            }
        }
    }
    let mut n = 0;
    walk(dir.path(), &mut n);
    assert!(n >= 1);
}

#[test]
fn extract_all_content_matches_hello() {
    let dir = tempfile::tempdir().unwrap();
    archive_extract_all(
        fixture("sample-deflate.zip"),
        dir.path().to_string_lossy().into_owned(),
        archive_default_limits(),
    )
    .unwrap();
    assert_eq!(
        std::fs::read(dir.path().join("hello.txt")).unwrap().len(),
        11
    );
    assert_eq!(
        std::fs::read(dir.path().join("second.txt")).unwrap().len(),
        23
    );
}

#[test]
fn extract_never_overwrites_and_cleans_up_what_it_made() {
    let dir = tempfile::tempdir().unwrap();
    // second.txt already exists: the extraction must fail on it, keep its bytes, and remove
    // hello.txt (written earlier in the same pass).
    std::fs::write(dir.path().join("second.txt"), b"mine").unwrap();
    let err = archive_extract_all(
        fixture("sample-deflate.zip"),
        dir.path().to_string_lossy().into_owned(),
        archive_default_limits(),
    )
    .unwrap_err();
    assert!(
        matches!(err, FylzError::DestinationExists { .. }),
        "{err:?}"
    );
    assert_eq!(
        std::fs::read(dir.path().join("second.txt")).unwrap(),
        b"mine"
    );
    assert!(
        !dir.path().join("hello.txt").exists(),
        "partial output left behind"
    );
}

#[test]
fn extract_refuses_zip_slip_before_writing_anything() {
    let dir = tempfile::tempdir().unwrap();
    let err = archive_extract_all(
        fixture("archives/hostile/zip-slip.zip"),
        dir.path().to_string_lossy().into_owned(),
        archive_default_limits(),
    )
    .unwrap_err();
    assert!(matches!(err, FylzError::Refused { .. }), "{err:?}");
    assert_eq!(std::fs::read_dir(dir.path()).unwrap().count(), 0);
}

#[test]
fn extract_enforces_limits() {
    let dir = tempfile::tempdir().unwrap();
    let mut limits = archive_default_limits();
    limits.max_file_bytes = 5;
    let err = archive_extract_all(
        fixture("sample-deflate.zip"),
        dir.path().to_string_lossy().into_owned(),
        limits,
    )
    .unwrap_err();
    assert!(
        matches!(
            err,
            FylzError::Refused { .. } | FylzError::LimitExceeded { .. }
        ),
        "{err:?}"
    );
    assert_eq!(std::fs::read_dir(dir.path()).unwrap().count(), 0);
}

#[test]
fn extract_into_a_missing_directory_is_io() {
    let err = archive_extract_all(
        fixture("sample-deflate.zip"),
        "/definitely/not/here".into(),
        archive_default_limits(),
    )
    .unwrap_err();
    assert!(matches!(err, FylzError::Io { .. }), "{err:?}");
}

#[test]
fn safe_relative_accepts_and_refuses() {
    use crate::extract::safe_relative;
    assert_eq!(safe_relative("a/b.txt"), Some(PathBuf::from("a/b.txt")));
    assert_eq!(safe_relative("./a//b/"), Some(PathBuf::from("a/b")));
    assert_eq!(safe_relative("a\\b.txt"), Some(PathBuf::from("a/b.txt")));
    for bad in [
        "",
        ".",
        "/",
        "/etc/passwd",
        "../x",
        "a/../../x",
        "a/..",
        "a\0b",
    ] {
        assert_eq!(safe_relative(bad), None, "{bad:?}");
    }
}

#[cfg(unix)]
#[test]
fn extract_refuses_to_pass_through_a_symlink() {
    let dir = tempfile::tempdir().unwrap();
    let outside = tempfile::tempdir().unwrap();
    // tree.zip has a subdirectory; plant a symlink where that directory would be created.
    let s = archive_inspect(fixture("archives/tree.zip"), archive_default_limits(), 50).unwrap();
    let first_dir = s
        .rows
        .iter()
        .filter_map(|r| r.path.split('/').next().filter(|_| r.path.contains('/')))
        .next()
        .or_else(|| {
            s.rows
                .iter()
                .find(|r| r.kind == ArchiveEntryKind::Directory)
                .map(|r| r.path.trim_end_matches('/'))
        })
        .expect("tree.zip has a directory")
        .to_string();
    std::os::unix::fs::symlink(outside.path(), dir.path().join(&first_dir)).unwrap();
    let err = archive_extract_all(
        fixture("archives/tree.zip"),
        dir.path().to_string_lossy().into_owned(),
        archive_default_limits(),
    )
    .unwrap_err();
    assert!(matches!(err, FylzError::UnsafePath { .. }), "{err:?}");
    assert_eq!(
        std::fs::read_dir(outside.path()).unwrap().count(),
        0,
        "wrote through the symlink"
    );
}
