//! Parity tests for [crate::policy]: every case of the app's `ArchiveExtractionPolicyTest.kt`
//! and `ArchiveExtractionPolicyFuzzTest.kt`, ported one-to-one (same inputs, same expected
//! verdict, plus the exact reason string where Kotlin's test only asserted `allowed`), then the
//! cases the design adds -- the `None` twins of every compressed-size-dependent case (decision
//! 1), the archive-level ratio rule, the link rule (section 6.2), lossy names, and the two
//! places Kotlin's string semantics differ from Rust's defaults.

use crate::policy::evaluate;
use crate::policy::Decision;
use crate::policy::EntryKind;
use crate::policy::EntryMetadata;
use crate::policy::Limits;

/// Kotlin's `ArchiveEntryMetadata(name, directory, compressedBytes, uncompressedBytes)` in the
/// same argument order, so each ported case reads against its original line for line. A
/// negative size is Kotlin's "unknown" and becomes `None`, the shape libarchive reports.
fn meta(name: &str, directory: bool, compressed: i64, uncompressed: i64) -> EntryMetadata {
    EntryMetadata {
        ordinal: 0,
        path: name.to_string(),
        name_lossy: false,
        raw_path: None,
        kind: if directory {
            EntryKind::Directory
        } else {
            EntryKind::File
        },
        link_target: None,
        uncompressed: u64::try_from(uncompressed).ok(),
        compressed: u64::try_from(compressed).ok(),
        mtime: None,
        mode: 0,
        encrypted_data: false,
        encrypted_metadata: false,
    }
}

fn link(kind: EntryKind, path: &str, target: Option<&str>) -> EntryMetadata {
    EntryMetadata {
        ordinal: 0,
        path: path.to_string(),
        name_lossy: false,
        raw_path: None,
        kind,
        link_target: target.map(str::to_string),
        uncompressed: Some(0),
        compressed: None,
        mtime: None,
        mode: 0,
        encrypted_data: false,
        encrypted_metadata: false,
    }
}

fn refused(reason: &'static str) -> Decision {
    Decision {
        allowed: false,
        reason: Some(reason),
    }
}

const ALLOWED: Decision = Decision {
    allowed: true,
    reason: None,
};

// ---------------------------------------------------------------------------------------------
// ArchiveExtractionPolicyTest.kt, the eight cases.
// ---------------------------------------------------------------------------------------------

#[test]
fn allows_a_normal_bounded_archive() {
    let decision = evaluate(
        1_024,
        &[
            meta("docs/readme.md", false, 100, 500),
            meta("images/", true, 0, 0),
        ],
        &Limits::default(),
    );
    assert_eq!(decision, ALLOWED);
}

#[test]
fn rejects_traversal_absolute_and_drive_qualified_paths() {
    let limits = Limits::default();
    assert_eq!(
        evaluate(100, &[meta("../escape.txt", false, 10, 20)], &limits),
        refused("Archive contains an unsafe path segment.")
    );
    assert_eq!(
        evaluate(100, &[meta("/absolute.txt", false, 10, 20)], &limits),
        refused("Archive contains an absolute or invalid path.")
    );
    assert_eq!(
        evaluate(100, &[meta("C:/windows.txt", false, 10, 20)], &limits),
        refused("Archive contains a drive-qualified path.")
    );
}

/// M3.3a's deliberate deviation from the Kotlin rule (the module doc's last bullet): a `.`
/// segment is a no-op. Kotlin's `validatePath` refused `./a` and `a/./b` as "unsafe path
/// segment" -- the twin of [rejects_traversal_absolute_and_drive_qualified_paths] above, which
/// still refuses `..` -- but every `tar -C dir -cf x.tar .` archive names its members `./…`, so
/// the parity rule refused them whole. Logged for the owner's review pass in `REVIEW_QUEUE.md`.
#[test]
fn a_dot_segment_is_a_no_op_not_an_escape_unlike_kotlin() {
    let limits = Limits::default();
    assert_eq!(
        evaluate(100, &[meta("./a.txt", false, 10, 20)], &limits),
        ALLOWED
    );
    assert_eq!(
        evaluate(100, &[meta("././a.txt", false, 10, 20)], &limits),
        ALLOWED
    );
    assert_eq!(
        evaluate(100, &[meta("a/./b.txt", false, 10, 20)], &limits),
        ALLOWED
    );
    assert_eq!(
        evaluate(100, &[meta("./dir/", true, 0, 0)], &limits),
        ALLOWED
    );
    // `..` stays refused, however it is dressed.
    assert_eq!(
        evaluate(100, &[meta("./../escape.txt", false, 10, 20)], &limits),
        refused("Archive contains an unsafe path segment.")
    );
    assert_eq!(
        evaluate(100, &[meta("a/./../b.txt", false, 10, 20)], &limits),
        refused("Archive contains an unsafe path segment.")
    );
    // Stripping the prefix exposes what it hid: `.//abs` is the absolute path `/abs`.
    assert_eq!(
        evaluate(100, &[meta(".//abs.txt", false, 10, 20)], &limits),
        refused("Archive contains an absolute or invalid path.")
    );
    // A name that is nothing but dots and slashes is not a member (a *directory* `.` is the
    // archive root and never reaches the policy; a file so named is invalid).
    assert_eq!(
        evaluate(100, &[meta("./", false, 10, 20)], &limits),
        refused("Archive contains an invalid path.")
    );
    assert_eq!(
        evaluate(100, &[meta(".", false, 10, 20)], &limits),
        refused("Archive contains an invalid path.")
    );
    // The duplicate key sees through the prefix too: `./a` and `a` are one path.
    assert_eq!(
        evaluate(
            100,
            &[meta("./a.txt", false, 10, 20), meta("a.txt", false, 10, 20)],
            &limits
        ),
        refused("Archive contains duplicate or colliding paths.")
    );
    assert_eq!(
        evaluate(
            100,
            &[meta("dir/./x", false, 10, 20), meta("DIR/X", false, 10, 20)],
            &limits
        ),
        refused("Archive contains duplicate or colliding paths.")
    );
}

#[test]
fn structural_only_limits_switch_every_size_rule_off_and_nothing_else() {
    let structural = Limits::default().structural_only();
    assert_eq!(structural.max_entries, usize::MAX);
    assert_eq!(structural.max_archive_bytes, u64::MAX);
    assert_eq!(structural.max_file_bytes, u64::MAX);
    assert_eq!(structural.max_total_uncompressed_bytes, u64::MAX);
    assert!(structural.max_compression_ratio.is_infinite());
    assert_eq!(structural.max_path_depth, Limits::default().max_path_depth);
    assert_eq!(
        structural.max_name_length,
        Limits::default().max_name_length
    );
    // A bomb by size passes the structural rules ...
    let bomb = [meta(
        "big.bin",
        false,
        1,
        1_u64 as i64 * 1024 * 1024 * 1024 * 8,
    )];
    assert_ne!(evaluate(100, &bomb, &Limits::default()), ALLOWED);
    assert_eq!(evaluate(100, &bomb, &structural), ALLOWED);
    // ... and an unsafe path, a duplicate, an escaping link and an unknown size do not.
    assert_eq!(
        evaluate(100, &[meta("../x", false, 1, 1)], &structural),
        refused("Archive contains an unsafe path segment.")
    );
    assert_eq!(
        evaluate(
            100,
            &[meta("a", false, 1, 1), meta("A", false, 1, 1)],
            &structural
        ),
        refused("Archive contains duplicate or colliding paths.")
    );
    assert_eq!(
        evaluate(
            100,
            &[link(EntryKind::Symlink, "l", Some("/etc/passwd"))],
            &structural
        ),
        refused(LINK_ESCAPES)
    );
    assert_eq!(
        evaluate(100, &[meta("unknown", false, 1, -1)], &structural),
        refused("Archive contains an entry with unknown size.")
    );
}

#[test]
fn rejects_duplicate_and_provider_colliding_paths() {
    let limits = Limits::default();
    let duplicate = "Archive contains duplicate or colliding paths.";
    let exact_duplicate = evaluate(
        100,
        &[
            meta("docs/readme.md", false, 10, 20),
            meta("docs/readme.md", false, 10, 20),
        ],
        &limits,
    );
    assert_eq!(exact_duplicate, refused(duplicate));

    let slash_and_case_collision = evaluate(
        100,
        &[
            meta("Docs\\Readme.md", false, 10, 20),
            meta("docs/readme.md", false, 10, 20),
        ],
        &limits,
    );
    assert_eq!(slash_and_case_collision, refused(duplicate));

    let directory_file_collision = evaluate(
        100,
        &[meta("assets/", true, 0, 0), meta("assets", false, 10, 20)],
        &limits,
    );
    assert_eq!(directory_file_collision, refused(duplicate));
}

#[test]
fn rejects_archive_input_beyond_staging_limit() {
    let limits = Limits {
        max_archive_bytes: 100,
        ..Limits::default()
    };
    assert_eq!(
        evaluate(101, &[], &limits),
        refused("Archive exceeds the allowed input size.")
    );
    assert_eq!(evaluate(100, &[], &limits), ALLOWED);
}

#[test]
fn rejects_too_many_entries() {
    let limits = Limits {
        max_entries: 2,
        ..Limits::default()
    };
    let entries: Vec<EntryMetadata> = (0..3)
        .map(|index| meta(&format!("file-{index}.txt"), false, 10, 20))
        .collect();
    assert_eq!(
        evaluate(100, &entries, &limits),
        refused("Archive contains too many entries.")
    );
}

#[test]
fn rejects_oversized_files_and_total_expansion() {
    let oversized = evaluate(
        100,
        &[meta("huge.bin", false, 100, 1_001)],
        &Limits {
            max_file_bytes: 1_000,
            ..Limits::default()
        },
    );
    assert_eq!(
        oversized,
        refused("Archive contains a file larger than the extraction limit.")
    );

    let total = evaluate(
        100,
        &[
            meta("a.bin", false, 100, 700),
            meta("b.bin", false, 100, 700),
        ],
        &Limits {
            max_file_bytes: 1_000,
            max_total_uncompressed_bytes: 1_000,
            ..Limits::default()
        },
    );
    assert_eq!(
        total,
        refused("Archive expands beyond the total extraction limit.")
    );
}

#[test]
fn rejects_suspicious_compression_ratio() {
    let limits = Limits {
        max_compression_ratio: 100.0,
        ..Limits::default()
    };
    assert_eq!(
        evaluate(100, &[meta("bomb.txt", false, 1, 1_000)], &limits),
        refused("Archive contains a suspicious compression ratio.")
    );

    // The `None` twin (decision 1): with no compressed size the per-entry rule is skipped and the
    // archive-level rule decides -- 1,000 declared bytes out of a 100-byte archive is a ratio of
    // 10, allowed; out of a 5-byte archive it is 200, refused with the same reason.
    assert_eq!(
        evaluate(100, &[meta("bomb.txt", false, -1, 1_000)], &limits),
        ALLOWED
    );
    assert_eq!(
        evaluate(5, &[meta("bomb.txt", false, -1, 1_000)], &limits),
        refused("Archive contains a suspicious compression ratio.")
    );
}

#[test]
fn rejects_unknown_sizes_and_excessive_nesting() {
    // Kotlin: `ArchiveEntryMetadata("unknown.bin", false, -1, 20)` -- an unknown *compressed*
    // size, refused there. Decision 1 makes that the normal case for every libarchive entry, so
    // here it is accepted; an unknown *uncompressed* size is refused exactly as before.
    assert_eq!(
        evaluate(
            100,
            &[meta("unknown.bin", false, -1, 20)],
            &Limits::default()
        ),
        ALLOWED
    );
    assert_eq!(
        evaluate(
            100,
            &[meta("unknown.bin", false, 10, -1)],
            &Limits::default()
        ),
        refused("Archive contains an entry with unknown size.")
    );
    assert_eq!(
        evaluate(
            100,
            &[meta("a/b/c/d.txt", false, 10, 20)],
            &Limits {
                max_path_depth: 3,
                ..Limits::default()
            }
        ),
        refused("Archive path nesting is too deep.")
    );
}

// ---------------------------------------------------------------------------------------------
// ArchiveExtractionPolicyFuzzTest.kt, the four cases.
// ---------------------------------------------------------------------------------------------

/// Kotlin's corpus minus `folder/./file`: M3.3a made a `.` segment a no-op (module doc, and
/// [a_dot_segment_is_a_no_op_not_an_escape_unlike_kotlin]), so that one entry moved there as an
/// *allowed* case; every other member of the Kotlin corpus is still refused.
#[test]
fn hostile_path_corpus_is_always_rejected() {
    let hostile = [
        "../escape",
        "..\\escape",
        "/absolute",
        "\\absolute",
        "C:/windows/system32",
        "C:\\windows\\system32",
        "folder/../file",
        "folder//../file",
        "\u{0}payload",
        "",
        "   ",
    ];
    for path in hostile {
        let result = evaluate(100, &[meta(path, false, 10, 20)], &Limits::default());
        assert!(!result.allowed, "Expected rejection for {path:?}");
        assert!(result.reason.is_some(), "Expected a reason for {path:?}");
    }
}

/// A 64-bit linear congruential generator (Knuth's MMIX constants) standing in for Kotlin's
/// `java.util.Random`: the design asks for the same construction rule and count, not the same
/// bit stream, and this keeps `rand` out of the dependency graph.
struct Lcg(u64);

impl Lcg {
    fn new(seed: u64) -> Self {
        Lcg(seed)
    }

    fn next_u32(&mut self) -> u32 {
        self.0 = self
            .0
            .wrapping_mul(6364136223846793005)
            .wrapping_add(1442695040888963407);
        (self.0 >> 33) as u32
    }

    /// `Random.nextInt(bound)`: uniform in `0..bound`.
    fn next_int(&mut self, bound: u32) -> u32 {
        self.next_u32() % bound
    }

    fn next_bool(&mut self) -> bool {
        self.next_u32() & 1 == 1
    }
}

#[test]
fn random_traversal_variants_never_pass() {
    let mut random = Lcg::new(0xF71A);
    let limits = Limits::default();
    for iteration in 0..2_000 {
        let prefix: String = (0..random.next_int(8))
            .map(|_| char::from(b'a' + random.next_int(26) as u8))
            .collect();
        let separator = if random.next_bool() { '/' } else { '\\' };
        let path = format!("{prefix}{separator}..{separator}{iteration}.bin");
        let result = evaluate(128, &[meta(&path, false, 16, 32)], &limits);
        assert!(!result.allowed, "Traversal variant passed: {path}");
    }
}

#[test]
fn random_bounded_normal_paths_do_not_crash_policy_evaluation() {
    let mut random = Lcg::new(44);
    let limits = Limits::default();
    for iteration in 0..5_000 {
        let depth = 1 + random.next_int(8);
        let path = (0..depth)
            .map(|level| format!("segment-{iteration}-{level}-{}", random.next_int(10_000)))
            .collect::<Vec<_>>()
            .join("/")
            + ".txt";
        // Kotlin only asserted "does not throw"; every one of these is in fact a well-formed,
        // bounded entry, so the stronger claim holds too.
        assert_eq!(
            evaluate(1_024, &[meta(&path, false, 100, 500)], &limits),
            ALLOWED,
            "{path}"
        );
    }
}

#[test]
fn overflow_and_implausible_compression_corpus_is_rejected() {
    let limits = Limits {
        max_file_bytes: 10_000,
        max_total_uncompressed_bytes: 20_000,
        max_compression_ratio: 100.0,
        ..Limits::default()
    };
    let rejected = [
        meta("negative-expanded", false, 1, -1),
        meta("zero-compressed", false, 0, 10),
        meta("ratio-bomb", false, 1, 1_000_000),
        meta("oversized", false, 1_000, i64::MAX),
    ];
    for entry in &rejected {
        let result = evaluate(1_024, std::slice::from_ref(entry), &limits);
        assert!(!result.allowed, "{}", entry.path);
    }
    // Kotlin's fifth case, `("negative-compressed", false, -1L, 10L)`: an unknown compressed
    // size. Under decision 1 the per-entry rules skip it, and 10 declared bytes out of 1,024 is
    // no ratio to refuse -- but the same unknown-compressed entry declaring 10,000 bytes out of a
    // 1-byte archive is caught by the archive-level rule.
    assert_eq!(
        evaluate(
            1_024,
            &[meta("negative-compressed", false, -1, 10)],
            &limits
        ),
        ALLOWED
    );
    assert_eq!(
        evaluate(
            1,
            &[meta("negative-compressed", false, -1, 10_000)],
            &limits
        ),
        refused("Archive contains a suspicious compression ratio.")
    );
    // The `None` twins of the two compressed-size cases above: accepted by the per-entry rule,
    // and `ratio-bomb` still refused -- by the file-size rule, which runs first, as in Kotlin.
    assert_eq!(
        evaluate(1_024, &[meta("zero-compressed", false, -1, 10)], &limits),
        ALLOWED
    );
    assert_eq!(
        evaluate(1_024, &[meta("ratio-bomb", false, -1, 1_000_000)], &limits),
        refused("Archive contains a file larger than the extraction limit.")
    );
}

// ---------------------------------------------------------------------------------------------
// Decision 1: the archive-level ratio rule.
// ---------------------------------------------------------------------------------------------

#[test]
fn archive_level_ratio_sums_declared_sizes_across_entries() {
    let limits = Limits {
        max_compression_ratio: 200.0,
        ..Limits::default()
    };
    // Each entry alone is 100x; together 300x, over a 1-byte archive.
    let entries = [
        meta("a", false, -1, 100),
        meta("b", false, -1, 100),
        meta("c", false, -1, 100),
    ];
    assert_eq!(evaluate(1, &entries[..2], &limits), ALLOWED);
    assert_eq!(
        evaluate(1, &entries, &limits),
        refused("Archive contains a suspicious compression ratio.")
    );
    // Exactly at the limit is allowed (`>`), as the per-entry rule is.
    assert_eq!(evaluate(2, &entries[..1], &limits), ALLOWED);
}

#[test]
fn archive_level_ratio_treats_a_zero_length_archive_as_infinite() {
    let limits = Limits::default();
    assert_eq!(
        evaluate(0, &[meta("a", false, -1, 1)], &limits),
        refused("Archive contains a suspicious compression ratio.")
    );
    // Nothing declared: nothing to judge (a directory-only archive, say).
    assert_eq!(evaluate(0, &[meta("dir/", true, -1, 0)], &limits), ALLOWED);
    assert_eq!(evaluate(0, &[], &limits), ALLOWED);
}

#[test]
fn directories_never_take_part_in_ratio_rules() {
    // A directory with a declared size and no compressed size, as libarchive may report one:
    // the per-entry rules skip directories in Kotlin, and the archive-level sum still includes
    // its declared bytes (Kotlin's running total did too).
    let limits = Limits {
        max_compression_ratio: 2.0,
        ..Limits::default()
    };
    assert_eq!(evaluate(100, &[meta("d/", true, 0, 150)], &limits), ALLOWED);
    assert_eq!(
        evaluate(100, &[meta("d/", true, 0, 250)], &limits),
        refused("Archive contains a suspicious compression ratio.")
    );
}

// ---------------------------------------------------------------------------------------------
// Section 6.2: the link rule.
// ---------------------------------------------------------------------------------------------

const LINK_ESCAPES: &str = "Archive contains a link that escapes the extraction folder.";

#[test]
fn symlink_with_an_absolute_target_is_refused() {
    let limits = Limits::default();
    for target in ["/etc/passwd", "\\windows\\system32", "C:/x", "c:\\x"] {
        assert_eq!(
            evaluate(
                100,
                &[link(EntryKind::Symlink, "a/link", Some(target))],
                &limits
            ),
            refused(LINK_ESCAPES),
            "{target}"
        );
    }
}

#[test]
fn symlink_that_climbs_out_of_the_root_is_refused() {
    let limits = Limits::default();
    let escaping = [
        ("link", "../x"),
        ("a/link", "../../x"),
        ("a/b/link", "../../../x"),
        ("a/link", "..\\..\\x"),
        ("a/link", "./../../x"),
        ("a/link", "b/../../../x"),
    ];
    for (path, target) in escaping {
        assert_eq!(
            evaluate(
                100,
                &[link(EntryKind::Symlink, path, Some(target))],
                &limits
            ),
            refused(LINK_ESCAPES),
            "{path} -> {target}"
        );
    }
}

#[test]
fn symlink_that_stays_in_the_tree_is_allowed() {
    let limits = Limits::default();
    let in_tree = [
        ("a/link", "../b/file"),
        ("a/link", "file"),
        ("a/b/link", "../../c"),
        ("link", "dir/file"),
        ("a/link", "./file"),
        ("a/link", "b/../c"),
        ("a/link", ""),
    ];
    for (path, target) in in_tree {
        assert_eq!(
            evaluate(
                100,
                &[link(EntryKind::Symlink, path, Some(target))],
                &limits
            ),
            ALLOWED,
            "{path} -> {target}"
        );
    }
}

#[test]
fn hardlink_to_a_valid_path_is_allowed_and_to_an_escaping_one_refused() {
    let limits = Limits::default();
    assert_eq!(
        evaluate(
            100,
            &[
                meta("dir/target.txt", false, -1, 20),
                link(EntryKind::Hardlink, "dir/alias.txt", Some("dir/target.txt")),
            ],
            &limits
        ),
        ALLOWED
    );
    for target in ["../x", "/x", "C:/x", "dir/../../x", "", "a\u{0}b"] {
        assert_eq!(
            evaluate(
                100,
                &[link(EntryKind::Hardlink, "dir/alias.txt", Some(target))],
                &limits
            ),
            refused(LINK_ESCAPES),
            "{target:?}"
        );
    }
}

#[test]
fn a_link_without_a_target_is_refused() {
    let limits = Limits::default();
    for kind in [EntryKind::Symlink, EntryKind::Hardlink] {
        assert_eq!(
            evaluate(100, &[link(kind, "a/link", None)], &limits),
            refused(LINK_ESCAPES),
            "{kind:?}"
        );
    }
    // A plain file or directory carrying no target is of course fine; `Other` too.
    for kind in [EntryKind::File, EntryKind::Directory, EntryKind::Other] {
        assert_eq!(
            evaluate(100, &[link(kind, "a/thing", None)], &limits),
            ALLOWED,
            "{kind:?}"
        );
    }
}

#[test]
fn link_rule_runs_after_the_path_rules_and_before_the_size_rules() {
    let limits = Limits::default();
    // The link's own path fails first: the path reason wins over the link reason.
    assert_eq!(
        evaluate(
            100,
            &[link(EntryKind::Symlink, "../link", Some("/etc/passwd"))],
            &limits
        ),
        refused("Archive contains an unsafe path segment.")
    );
    // A duplicate key is a path rule too.
    assert_eq!(
        evaluate(
            100,
            &[
                meta("a/link", false, -1, 1),
                link(EntryKind::Symlink, "a/LINK", Some("/etc/passwd")),
            ],
            &limits
        ),
        refused("Archive contains duplicate or colliding paths.")
    );
    // An escaping link with an unknown size: the link reason wins over "unknown size".
    let mut no_size = link(EntryKind::Symlink, "a/link", Some("/etc/passwd"));
    no_size.uncompressed = None;
    assert_eq!(evaluate(100, &[no_size], &limits), refused(LINK_ESCAPES));
}

#[test]
fn links_and_other_entries_count_as_files_for_size_rules() {
    let limits = Limits {
        max_file_bytes: 10,
        max_entries: 1,
        ..Limits::default()
    };
    for kind in [EntryKind::Symlink, EntryKind::Hardlink, EntryKind::Other] {
        let mut entry = link(kind, "a/thing", Some("b"));
        entry.uncompressed = Some(11);
        assert_eq!(
            evaluate(100, &[entry], &limits),
            refused("Archive contains a file larger than the extraction limit."),
            "{kind:?}"
        );
    }
    // A directory declaring the same size is not a file for this rule.
    assert_eq!(evaluate(100, &[meta("d/", true, 0, 11)], &limits), ALLOWED);
    // Two links are two entries.
    assert_eq!(
        evaluate(
            100,
            &[
                link(EntryKind::Symlink, "a", Some("b")),
                link(EntryKind::Symlink, "b", Some("a")),
            ],
            &limits
        ),
        refused("Archive contains too many entries.")
    );
}

// ---------------------------------------------------------------------------------------------
// Construction details: lossy names, defaults, and the two Kotlin string semantics.
// ---------------------------------------------------------------------------------------------

#[test]
fn a_lossy_name_is_validated_structurally_like_any_other() {
    let limits = Limits::default();
    let mut entry = meta("caf\u{FFFD}/readme.txt", false, -1, 20);
    entry.name_lossy = true;
    assert_eq!(evaluate(100, &[entry.clone()], &limits), ALLOWED);
    entry.path = "../caf\u{FFFD}.txt".to_string();
    assert_eq!(
        evaluate(100, &[entry], &limits),
        refused("Archive contains an unsafe path segment.")
    );
}

#[test]
fn default_limits_are_the_kotlin_defaults_plus_the_listing_bound() {
    let limits = Limits::default();
    assert_eq!(limits.max_entries, 10_000);
    assert_eq!(limits.max_archive_bytes, 2 * 1024 * 1024 * 1024);
    assert_eq!(limits.max_file_bytes, 1024 * 1024 * 1024);
    assert_eq!(limits.max_total_uncompressed_bytes, 4 * 1024 * 1024 * 1024);
    assert_eq!(limits.max_compression_ratio, 200.0);
    assert_eq!(limits.max_path_depth, 64);
    assert_eq!(limits.max_name_length, 255);
    assert_eq!(limits.max_listing_entries, 200_000);
}

#[test]
fn blank_paths_use_kotlin_whitespace_semantics() {
    let limits = Limits::default();
    let invalid = "Archive contains an invalid path.";
    // Kotlin's `isBlank`: Unicode space separators including the non-breaking ones, the ASCII
    // controls `\t`..`\r`, and U+001C..U+001F.
    for path in ["\t", " \u{a0} ", "\u{2007}", "\u{3000}", "\u{1c}", "\r\n"] {
        assert_eq!(
            evaluate(100, &[meta(path, false, 10, 20)], &limits),
            refused(invalid),
            "{path:?}"
        );
    }
    // U+0085 (NEL) is White_Space to Rust but a control to Java: not blank, so it is a one-
    // segment path like any other odd character.
    assert_eq!(
        evaluate(100, &[meta("\u{85}", false, 10, 20)], &limits),
        ALLOWED
    );
    // Slashes only: not blank, but no segments -- "invalid path" by the second rule.
    assert_eq!(
        evaluate(100, &[meta("a//b", false, 10, 20)], &limits),
        ALLOWED
    );
    assert_eq!(
        evaluate(100, &[meta("\\\\", false, 10, 20)], &limits),
        refused("Archive contains an absolute or invalid path.")
    );
}

#[test]
fn name_lengths_are_measured_in_utf16_code_units_as_kotlin_did() {
    let limits = Limits {
        max_name_length: 4,
        max_path_depth: 2,
        ..Limits::default()
    };
    let unsafe_segment = "Archive contains an unsafe path segment.";
    assert_eq!(
        evaluate(100, &[meta("abcd", false, 10, 20)], &limits),
        ALLOWED
    );
    assert_eq!(
        evaluate(100, &[meta("abcde", false, 10, 20)], &limits),
        refused(unsafe_segment)
    );
    // Four BMP characters are four units (twelve bytes): allowed.
    assert_eq!(
        evaluate(
            100,
            &[meta("\u{e9}\u{e9}\u{e9}\u{e9}", false, 10, 20)],
            &limits
        ),
        ALLOWED
    );
    // Three astral characters are six units (twelve bytes, three scalar values): refused.
    assert_eq!(
        evaluate(
            100,
            &[meta("\u{1F600}\u{1F600}\u{1F600}", false, 10, 20)],
            &limits
        ),
        refused(unsafe_segment)
    );
    // The whole-name bound, `maxNameLength * maxPathDepth` = 8, is measured the same way and
    // checked first: nine units in one segment is "invalid path", not "unsafe segment".
    assert_eq!(
        evaluate(100, &[meta("abcdefghi", false, 10, 20)], &limits),
        refused("Archive contains an invalid path.")
    );
}

#[test]
fn a_drive_letter_is_exactly_one_ascii_letter_and_a_colon() {
    let limits = Limits::default();
    assert_eq!(
        evaluate(100, &[meta("z:\\x", false, 10, 20)], &limits),
        refused("Archive contains a drive-qualified path.")
    );
    for path in ["CC:/x", "1:/x", ":/x", "\u{e9}:/x", "a:b/x", "x/C:/y"] {
        assert_eq!(
            evaluate(100, &[meta(path, false, 10, 20)], &limits),
            ALLOWED,
            "{path}"
        );
    }
}

#[test]
fn running_total_overflow_is_its_own_reason() {
    let limits = Limits {
        max_file_bytes: u64::MAX,
        max_total_uncompressed_bytes: u64::MAX,
        max_archive_bytes: u64::MAX,
        max_compression_ratio: f64::INFINITY,
        ..Limits::default()
    };
    let huge = |name: &str| {
        let mut entry = meta(name, false, -1, 0);
        entry.uncompressed = Some(u64::MAX);
        entry
    };
    assert_eq!(
        evaluate(u64::MAX, &[huge("a"), huge("b")], &limits),
        refused("Archive size metadata overflowed.")
    );
}
