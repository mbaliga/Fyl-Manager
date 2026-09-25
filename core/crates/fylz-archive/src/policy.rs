//! The archive extraction policy: pure preflight rules over untrusted archive metadata, the source
//! of truth from M3.1 part 3 on. Ported rule-for-rule, in the same order and with the same reason
//! strings, from `app/src/main/java/io/github/mbaliga/fylz/data/ArchiveExtractionPolicy.kt`
//! (whose tests are this module's parity oracle -- `policy_tests.rs` carries every one of them),
//! with exactly the adaptations `docs/agent/DESIGN-M31-PART3-EXTRACT-AND-POLICY.md` decides:
//!
//! - **Per-entry compressed size is optional** (decision 1). zip4j reported one for every ZIP
//!   member; libarchive has no portable per-entry compressed size for any format, so
//!   [EntryMetadata::compressed] is `Option<u64>` and the two per-entry ratio rules ("implausibly
//!   compressed", "suspicious compression ratio") run only when it is `Some`. In its place an
//!   **archive-level** ratio rule runs for every format after the loop: the sum of declared
//!   uncompressed sizes over the archive's own byte length, refused with the existing "suspicious
//!   compression ratio" reason. Headers that lie are then caught at runtime by `extract()`'s own
//!   byte caps, which this policy only pre-screens. An unknown *uncompressed* size is still a
//!   refusal, exactly as a negative one was in Kotlin.
//! - **A link rule the Kotlin policy never had** (section 6.2): extraction writes through SAF,
//!   which cannot materialise a symlink or hardlink, so `extract()` never writes one -- but an
//!   archive whose link *would* have escaped the extraction folder is refused outright, the M3
//!   acceptance criterion "symlink escapes are refused". Evaluated per entry after its path rules.
//! - **A `.` segment is a no-op, not an escape** (M3.3a, the decision `REVIEW_QUEUE.md`'s M3.2
//!   entry item 13 asked for; a deliberate deviation from the Kotlin rule, which refused it):
//!   `tar -C dir -cf x.tar .` names every member `./…`, and libarchive lists an ISO's root as `.`,
//!   so the Kotlin rule refused every such archive whole. [validate_path] and [normalized_path_key]
//!   strip a leading `./` repeatedly and drop `.` segments before the remaining checks; `..` stays
//!   refused; an entry that is only `.` or `./` is the archive root (`is_archive_root`), neither
//!   counted nor refused. The browsing tree normalises the same way, so the two agree.
//!
//! - **A selection-scoped size pass** (M3.4, `docs/agent/DESIGN-M34-SELECTIVE-EXTRACT.md` section
//!   2.4): [evaluate_selection] runs only the size rules -- entry count, per-file size, running
//!   total, the archive-level ratio, plus the duplicate-key rule as defence in depth -- over the
//!   entries a caller selected, immediately before extracting them. The structural rules (paths,
//!   links, unknown sizes) are the whole archive's business and stay with [evaluate] and its
//!   `structural_only` twin; [structural_check] re-runs them for one entry at extraction time.
//!
//! Everything else is literal: `Long.MAX_VALUE` overflow guard -> `checked_add`, a negative
//! `archiveBytes` is unrepresentable in `u64`, `lowercase(Locale.ROOT)` -> `str::to_lowercase`,
//! the drive-letter regex -> a two-byte check, and Kotlin's `String.length`/`isBlank` semantics
//! reproduced where they differ from Rust's defaults (see [utf16_len] and [is_blank]).

use std::collections::HashSet;

/// The limits [evaluate] enforces. [Limits::default] is the Kotlin `ArchiveExtractionLimits`
/// defaults field for field, plus [Limits::max_listing_entries], which is not a policy rule at
/// all but a memory bound for the decoder process (`inspect` stops collecting metadata past it).
#[derive(Debug, Clone, PartialEq)]
pub struct Limits {
    pub max_entries: usize,
    pub max_archive_bytes: u64,
    pub max_file_bytes: u64,
    pub max_total_uncompressed_bytes: u64,
    pub max_compression_ratio: f64,
    pub max_path_depth: usize,
    pub max_name_length: usize,
    /// How many entries `inspect` will collect before giving up with
    /// `ArchiveError::LimitExceeded { rule: "listing" }`. Deliberately far above
    /// [Limits::max_entries]: browsing (M3.3) must still list an archive the policy would refuse
    /// to extract. 200,000 entries is roughly 30 MB of [EntryMetadata] -- inside the decoder
    /// process's 256 MB budget (`docs/agent/MASTER_PLAN.md` section 4.4) with room to spare.
    pub max_listing_entries: usize,
}

impl Limits {
    /// These limits with every size rule switched off: `max_archive_bytes`, `max_file_bytes` and
    /// `max_total_uncompressed_bytes` at `u64::MAX`, `max_entries` at `usize::MAX`,
    /// `max_compression_ratio` at infinity. [evaluate] under them can only fire the **structural**
    /// rules -- paths, duplicate keys, links, unknown sizes, a sum that overflows -- which is what
    /// `Inspection::structural_refusal` (M3.3a) records so a caller with a larger destination than
    /// the defaults assume can still tell "too big for these limits" from "unsafe whatever the
    /// limits" (M3.4 refuses extraction from an archive whose structural verdict is a refusal).
    pub fn structural_only(&self) -> Limits {
        Limits {
            max_entries: usize::MAX,
            max_archive_bytes: u64::MAX,
            max_file_bytes: u64::MAX,
            max_total_uncompressed_bytes: u64::MAX,
            max_compression_ratio: f64::INFINITY,
            max_path_depth: self.max_path_depth,
            max_name_length: self.max_name_length,
            max_listing_entries: self.max_listing_entries,
        }
    }
}

impl Default for Limits {
    fn default() -> Self {
        Limits {
            max_entries: 10_000,
            max_archive_bytes: 2 * 1024 * 1024 * 1024,
            max_file_bytes: 1024 * 1024 * 1024,
            max_total_uncompressed_bytes: 4 * 1024 * 1024 * 1024,
            max_compression_ratio: 200.0,
            max_path_depth: 64,
            max_name_length: 255,
            max_listing_entries: 200_000,
        }
    }
}

/// What kind of object an archive entry describes. Replaces Kotlin's `directory: Boolean`; the
/// policy's size rules treat everything but [EntryKind::Directory] as a file (a link's declared
/// size counts toward the totals, whatever it is), and the link rule applies to
/// [EntryKind::Symlink]/[EntryKind::Hardlink] only.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum EntryKind {
    File,
    Directory,
    Symlink,
    Hardlink,
    /// Anything else libarchive can describe (device nodes, fifos, sockets). Never extracted.
    Other,
}

/// One entry's metadata as the policy sees it: what `inspect` collects from a header pass, or
/// what a test constructs directly. Only [EntryMetadata::path], [EntryMetadata::kind],
/// [EntryMetadata::link_target], [EntryMetadata::uncompressed] and [EntryMetadata::compressed]
/// take part in a decision; the rest is carried for the listing (`docs/agent/DESIGN-M32-SEEKABLE-PFD.md`
/// section 2.4 is its consumer).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EntryMetadata {
    /// The 0-based index of the raw `archive_read_next_header` call that returned this entry,
    /// counting **every** header: a format's own root directory (which `inspect` then drops as
    /// an entry), links, `Other` kinds, and headers whose data later fails. Never an index into
    /// `Inspection::entries`. It is what a browsing document id names and what
    /// `extract_entry_at` walks to (M3.3), and what M3.4's `Selection::Ordinals` selects by;
    /// one shared counter in the `Reader` produces it for every entry point. A test-constructed
    /// entry may use 0: the policy never reads it.
    pub ordinal: u32,
    pub path: String,
    /// `true` when the raw name was not UTF-8 and `path` is `String::from_utf8_lossy` of it
    /// (legacy CP437/GBK ZIPs; M3.7 adds charset detection). The policy validates the lossy
    /// string: every structural rule (depth, length, `..`, absolute) reads the same through
    /// replacement characters.
    pub name_lossy: bool,
    pub kind: EntryKind,
    /// `archive_entry_symlink` for [EntryKind::Symlink], `archive_entry_hardlink` for
    /// [EntryKind::Hardlink], else `None`. `None` on a link entry is malformed and refused.
    pub link_target: Option<String>,
    /// The declared uncompressed size; `None` when the header does not say
    /// (`archive_entry_size_is_set` == 0). Unknown is a refusal, as a negative size was in Kotlin.
    pub uncompressed: Option<u64>,
    /// The declared compressed size, when the reader can report one. **Always `None` from
    /// `inspect`**: libarchive has no per-entry compressed size. The field exists so the two
    /// per-entry ratio rules keep their exact Kotlin semantics for any caller that does know it.
    pub compressed: Option<u64>,
    /// Modification time as seconds since the epoch, `None` when unset. Widened from `time_t`
    /// (which is 32-bit on armv7 bionic) by the reader, never narrowed.
    pub mtime: Option<i64>,
    /// `archive_entry_perm`: the permission bits, which libarchive synthesises for formats that
    /// carry none (ZIP, 7z, ISO).
    pub mode: u32,
    pub encrypted_data: bool,
    pub encrypted_metadata: bool,
}

/// The policy's verdict. `reason` is `Some` exactly when `allowed` is `false`; every reason string
/// is one of the Kotlin `ArchiveExtractionPolicy` literals, plus the one new link-rule string.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Decision {
    pub allowed: bool,
    pub reason: Option<&'static str>,
}

impl Decision {
    fn allowed() -> Self {
        Decision {
            allowed: true,
            reason: None,
        }
    }

    fn refused(reason: &'static str) -> Self {
        Decision {
            allowed: false,
            reason: Some(reason),
        }
    }
}

/// The reason string of the link rule (section 6.2) -- the one rule without a Kotlin counterpart.
const LINK_ESCAPES: &str = "Archive contains a link that escapes the extraction folder.";

/// Decides whether an archive of `archive_bytes` bytes whose headers describe `entries` may be
/// extracted under `limits`. Pure: no I/O, no allocation beyond the duplicate-path set. Rules run
/// in the Kotlin order -- archive size, entry count, then per entry: path, duplicate key, link,
/// unknown size, file size, running total (with overflow guard), per-entry ratio -- and finally
/// the archive-level ratio. The first failing rule's reason is the decision's.
pub fn evaluate(archive_bytes: u64, entries: &[EntryMetadata], limits: &Limits) -> Decision {
    // Kotlin: `archiveBytes < 0L || archiveBytes > limits.maxArchiveBytes`. A negative archive
    // size is not representable in `u64`, so only the upper bound remains.
    if archive_bytes > limits.max_archive_bytes {
        return Decision::refused("Archive exceeds the allowed input size.");
    }
    if entries.len() > limits.max_entries {
        return Decision::refused("Archive contains too many entries.");
    }

    let mut total_uncompressed: u64 = 0;
    let mut normalized_paths: HashSet<String> = HashSet::new();
    for entry in entries {
        if let Some(reason) = validate_path(&entry.path, limits) {
            return Decision::refused(reason);
        }
        if !normalized_paths.insert(normalized_path_key(&entry.path)) {
            return Decision::refused("Archive contains duplicate or colliding paths.");
        }
        if let Some(reason) = validate_link(entry, limits) {
            return Decision::refused(reason);
        }
        // Kotlin: `entry.compressedBytes < 0L || entry.uncompressedBytes < 0L`. An unknown
        // compressed size is no longer a refusal (decision 1: libarchive never reports one); an
        // unknown uncompressed size still is.
        let Some(uncompressed) = entry.uncompressed else {
            return Decision::refused("Archive contains an entry with unknown size.");
        };
        let is_directory = entry.kind == EntryKind::Directory;
        if !is_directory && uncompressed > limits.max_file_bytes {
            return Decision::refused("Archive contains a file larger than the extraction limit.");
        }
        // Kotlin: `Long.MAX_VALUE - totalUncompressed < entry.uncompressedBytes`.
        total_uncompressed = match total_uncompressed.checked_add(uncompressed) {
            Some(total) => total,
            None => return Decision::refused("Archive size metadata overflowed."),
        };
        if total_uncompressed > limits.max_total_uncompressed_bytes {
            return Decision::refused("Archive expands beyond the total extraction limit.");
        }

        if !is_directory && uncompressed > 0 {
            match entry.compressed {
                Some(0) => {
                    return Decision::refused("Archive contains an implausibly compressed entry.");
                }
                Some(compressed) => {
                    let ratio = uncompressed as f64 / compressed as f64;
                    if ratio > limits.max_compression_ratio {
                        return Decision::refused(
                            "Archive contains a suspicious compression ratio.",
                        );
                    }
                }
                // Decision 1: the per-entry ratio rules need a compressed size; without one they
                // are skipped for this entry and the archive-level rule below stands in.
                None => {}
            }
        }
    }

    // Decision 1's archive-level ratio: the declared expansion of the whole archive against its
    // own length, for every format. Declared bytes out of a zero-length archive is an infinite
    // ratio, refused like any other ratio over the limit; an archive declaring nothing at all
    // (every entry empty) has no ratio to judge.
    if total_uncompressed > 0
        && (archive_bytes == 0
            || total_uncompressed as f64 / archive_bytes as f64 > limits.max_compression_ratio)
    {
        return Decision::refused("Archive contains a suspicious compression ratio.");
    }
    Decision::allowed()
}

/// The size rules of [evaluate] over a **selection** (M3.4): the entries about to be extracted,
/// not the whole archive. Same order and reason strings: archive size, entry count (`>`, so a
/// selection of exactly `max_entries` passes), then per entry the duplicate-key rule (defence in
/// depth -- a structurally sound archive has none, but a stale plan might), unknown size, the
/// per-file cap, the running total with its overflow guard, the per-entry ratio when a compressed
/// size is known; finally the archive-level ratio of the **selected** bytes over the archive's
/// own length. No path or link rule: those are structural, decided over the whole archive from
/// the persisted listing summary (the Kotlin planner is their gate), and re-checked per entry by
/// [structural_check] as the extraction reaches it.
pub fn evaluate_selection(
    archive_bytes: u64,
    selected: &[EntryMetadata],
    limits: &Limits,
) -> Decision {
    if archive_bytes > limits.max_archive_bytes {
        return Decision::refused("Archive exceeds the allowed input size.");
    }
    if selected.len() > limits.max_entries {
        return Decision::refused("Archive contains too many entries.");
    }
    let mut total_uncompressed: u64 = 0;
    let mut normalized_paths: HashSet<String> = HashSet::new();
    for entry in selected {
        if !normalized_paths.insert(normalized_path_key(&entry.path)) {
            return Decision::refused("Archive contains duplicate or colliding paths.");
        }
        let Some(uncompressed) = entry.uncompressed else {
            return Decision::refused("Archive contains an entry with unknown size.");
        };
        let is_directory = entry.kind == EntryKind::Directory;
        if !is_directory && uncompressed > limits.max_file_bytes {
            return Decision::refused("Archive contains a file larger than the extraction limit.");
        }
        total_uncompressed = match total_uncompressed.checked_add(uncompressed) {
            Some(total) => total,
            None => return Decision::refused("Archive size metadata overflowed."),
        };
        if total_uncompressed > limits.max_total_uncompressed_bytes {
            return Decision::refused("Archive expands beyond the total extraction limit.");
        }
        if !is_directory && uncompressed > 0 {
            match entry.compressed {
                Some(0) => {
                    return Decision::refused("Archive contains an implausibly compressed entry.");
                }
                Some(compressed) => {
                    if uncompressed as f64 / compressed as f64 > limits.max_compression_ratio {
                        return Decision::refused(
                            "Archive contains a suspicious compression ratio.",
                        );
                    }
                }
                None => {}
            }
        }
    }
    if total_uncompressed > 0
        && (archive_bytes == 0
            || total_uncompressed as f64 / archive_bytes as f64 > limits.max_compression_ratio)
    {
        return Decision::refused("Archive contains a suspicious compression ratio.");
    }
    Decision::allowed()
}

/// The structural rules of [evaluate] for **one** entry -- its path ([validate_path]) and, for a
/// link, its target ([validate_link]) -- under `max_path_depth`/`max_name_length`. What
/// `extract_blocks` runs on every selected entry as it reaches it (M3.4, defence in depth against
/// a plan built from a listing the archive no longer matches), failing that one entry rather than
/// the pass. Duplicate keys need the whole archive and are not checked here.
pub(crate) fn structural_check(
    entry: &EntryMetadata,
    max_path_depth: usize,
    max_name_length: usize,
) -> Option<&'static str> {
    let limits = Limits {
        max_path_depth,
        max_name_length,
        ..Limits::default()
    };
    validate_path(&entry.path, &limits).or_else(|| validate_link(entry, &limits))
}

/// Kotlin's `normalizedPathKey`: backslashes to slashes, trailing slashes trimmed, lower-cased --
/// plus (M3.3a) a leading `./` stripped repeatedly and `.` segments dropped, so `./a` and `a` are
/// one key, as they are one path. `lowercase(Locale.ROOT)` and `str::to_lowercase` are both the
/// locale-independent Unicode default case mapping, so two names collide here exactly when they
/// did in Kotlin. Crate-visible (not public, as in Kotlin) because `extract()`'s
/// `Selection::Paths` matches by the same key.
pub(crate) fn normalized_path_key(name: &str) -> String {
    let slashes = name.replace('\\', "/");
    let rest = strip_dot_prefix(&slashes).trim_end_matches('/');
    rest.split('/')
        .filter(|segment| *segment != ".")
        .collect::<Vec<_>>()
        .join("/")
        .to_lowercase()
}

/// `./` (and, for ZIP names, `.\`) stripped from the front as many times as it appears.
fn strip_dot_prefix(name: &str) -> &str {
    let mut rest = name;
    loop {
        if let Some(stripped) = rest.strip_prefix("./") {
            rest = stripped;
        } else if let Some(stripped) = rest.strip_prefix(".\\") {
            rest = stripped;
        } else {
            return rest;
        }
    }
}

/// Kotlin's `validatePath`, rule for rule, with the one M3.3a amendment (module doc): a leading
/// `./` is stripped repeatedly and `.` segments are dropped before the remaining checks, so
/// `./a` is judged as `a` and `.//abs` as the absolute path it is. Returns the reason string of
/// the first failing rule. `pub(crate)` since M3.5: `write.rs` runs this over every outgoing
/// entry path too (design section 2.5), defence in depth against a manifest built from a stale
/// plan -- the same rule, not a copy of it.
pub(crate) fn validate_path(name: &str, limits: &Limits) -> Option<&'static str> {
    if is_blank(name)
        || utf16_len(name) > limits.max_name_length.saturating_mul(limits.max_path_depth)
    {
        return Some("Archive contains an invalid path.");
    }
    let rest = strip_dot_prefix(name);
    if rest.contains('\0') || rest.starts_with('/') || rest.starts_with('\\') {
        return Some("Archive contains an absolute or invalid path.");
    }
    let normalized = rest.replace('\\', "/");
    let segments: Vec<&str> = normalized
        .split('/')
        .filter(|s| !s.is_empty() && *s != ".")
        .collect();
    if segments.is_empty() {
        return Some("Archive contains an invalid path.");
    }
    if segments.len() > limits.max_path_depth {
        return Some("Archive path nesting is too deep.");
    }
    if segments
        .iter()
        .any(|s| *s == ".." || utf16_len(s) > limits.max_name_length)
    {
        return Some("Archive contains an unsafe path segment.");
    }
    if is_drive_letter(segments[0]) {
        return Some("Archive contains a drive-qualified path.");
    }
    None
}

/// Section 6.2's link rule, evaluated after the entry's own path rules. Not a link: no opinion.
fn validate_link(entry: &EntryMetadata, limits: &Limits) -> Option<&'static str> {
    match entry.kind {
        EntryKind::Symlink => match entry.link_target.as_deref() {
            None => Some(LINK_ESCAPES),
            Some(target) if symlink_escapes(&entry.path, target) => Some(LINK_ESCAPES),
            Some(_) => None,
        },
        // A hardlink's target is another entry's path: it gets every check a path gets, and the
        // link reason rather than the path reason, because the entry's own path already passed.
        EntryKind::Hardlink => match entry.link_target.as_deref() {
            None => Some(LINK_ESCAPES),
            Some(target) if validate_path(target, limits).is_some() => Some(LINK_ESCAPES),
            Some(_) => None,
        },
        EntryKind::File | EntryKind::Directory | EntryKind::Other => None,
    }
}

/// Whether a symlink at `path` (already validated: no `.`/`..` segments) pointing at `target`
/// would resolve outside the extraction root: an absolute target (leading slash after backslash
/// normalisation, or a drive-letter first segment), or a relative one whose `..` segments climb
/// above the root when joined onto `dirname(path)`. Only depth is tracked -- the names of the
/// segments never matter, and a target that merely re-enters the tree (`../sibling`) is fine.
fn symlink_escapes(path: &str, target: &str) -> bool {
    let target = target.replace('\\', "/");
    if target.starts_with('/') {
        return true;
    }
    let mut target_segments = target.split('/').filter(|s| !s.is_empty()).peekable();
    if target_segments
        .peek()
        .is_some_and(|first| is_drive_letter(first))
    {
        return true;
    }
    let normalized_path = path.replace('\\', "/");
    // dirname(path): every segment but the link's own name.
    let mut depth = normalized_path
        .split('/')
        .filter(|s| !s.is_empty())
        .count()
        .saturating_sub(1);
    for segment in target_segments {
        match segment {
            "." => {}
            ".." => {
                if depth == 0 {
                    return true;
                }
                depth -= 1;
            }
            _ => depth += 1,
        }
    }
    false
}

/// Kotlin's `Regex("^[A-Za-z]:$")` on a segment, as the explicit two-byte check the design asks
/// for: exactly one ASCII letter followed by a colon.
fn is_drive_letter(segment: &str) -> bool {
    let bytes = segment.as_bytes();
    bytes.len() == 2 && bytes[0].is_ascii_alphabetic() && bytes[1] == b':'
}

/// Kotlin's `String.length` counts UTF-16 code units, so the Kotlin rules measured a name of N
/// non-BMP characters as 2N. The two length rules use this rather than `str::len` (bytes) or
/// `chars().count()` (scalar values) so a name is exactly as long here as it was there.
fn utf16_len(s: &str) -> usize {
    s.encode_utf16().count()
}

/// Kotlin's `CharSequence.isBlank()`: every char satisfies `Char.isWhitespace()`, which on the
/// JVM is `Character.isWhitespace(c) || Character.isSpaceChar(c)` -- Unicode Zs/Zl/Zp plus
/// `\t \n \u000B \f \r` and `\u001C`..`\u001F`. That is Rust's `char::is_whitespace` (Unicode
/// `White_Space`) minus U+0085 (NEL, a control in Java's view) plus the four ASCII separators.
/// The empty string is blank in both.
fn is_blank(s: &str) -> bool {
    s.chars()
        .all(|c| ('\u{1c}'..='\u{1f}').contains(&c) || (c.is_whitespace() && c != '\u{85}'))
}
