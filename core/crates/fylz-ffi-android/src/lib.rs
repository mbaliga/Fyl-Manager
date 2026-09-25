//! Uniffi bindings exposed to Kotlin.
//!
//! `fylz_version` and `sniff` are the whole M2.2 pipeline proof (Rust -> uniffi -> generated
//! Kotlin -> Gradle) end to end; `sniff` itself is `fylz-sniff`'s content-detection logic (M2.5)
//! wired to a real, caller-owned file descriptor. `archive_inspect` (M3.2b) is `fylz-archive`'s
//! one header pass plus the extraction policy, summarised into a record small enough to cross
//! Binder (`docs/agent/DESIGN-M32-SEEKABLE-PFD.md` section 2.4).
//!
//! Naming: every uniffi record/enum here ends in `Record`, because uniffi generates Kotlin classes
//! with these exact names into `io.github.mbaliga.fylz.core`, and `DecoderService` imports both
//! those and the `decoder.*` Parcelables they are copied into (`ArchiveLimits`, `ArchiveInspection`,
//! `ArchiveEntryInfo`) -- the suffix keeps the two layers apart at every use site. Error variants
//! carry a field named `detail`, never `message`: uniffi's generated Kotlin error class would
//! otherwise declare `val message` against `Throwable.message` and fail to compile.

use std::io::{Read, Seek, SeekFrom};
use std::mem::ManuallyDrop;
use std::os::fd::{FromRawFd, RawFd};

use fylz_archive::ArchiveError;
use fylz_archive::Decision;
use fylz_archive::EntryKind;
use fylz_archive::EntryMetadata;
use fylz_archive::Inspection;
use fylz_archive::Limits;

uniffi::setup_scaffolding!();

/// The `fylz-core` workspace version, for the Kotlin side to log/display.
#[uniffi::export]
pub fn fylz_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

/// Identifies a file's format from its content. `path_fd` is an already-open, caller-owned file
/// descriptor (never a path string, so this never touches SAF permission checks itself, and
/// never a filename extension, which `DecoderService`'s whole isolated-process design exists to
/// stop this app from trusting for an untrusted file). Returns the format's registered MIME type
/// when it has one, else a short label, else `"unknown"` for anything unrecognised -- an
/// unreadable or empty file is `"unknown"` too, never an error the caller has no channel for yet.
#[uniffi::export]
pub async fn sniff(path_fd: i32) -> String {
    describe(detect_format(path_fd))
}

fn describe(format: Option<fylz_sniff::Format>) -> String {
    match format {
        Some(format) => format.mime().unwrap_or_else(|| format.label()).to_string(),
        None => "unknown".to_string(),
    }
}

fn detect_format(path_fd: RawFd) -> Option<fylz_sniff::Format> {
    if path_fd < 0 {
        return None;
    }
    // SAFETY: `path_fd` is caller-owned per this function's own documented contract. Wrapping it
    // in `ManuallyDrop` is load-bearing, not decoration: an ordinary `File` would close this
    // descriptor the moment it drops at the end of this function, which this function must not
    // do -- the caller may still need it (and owns closing it).
    let mut file = ManuallyDrop::new(unsafe { std::fs::File::from_raw_fd(path_fd) });
    format_from_reader(&mut *file).ok().flatten()
}

/// The actual read-and-sniff logic, generic over `Read + Seek` so it is testable against an
/// in-memory buffer -- no real file descriptor, temp file, or platform-specific fd plumbing
/// needed to exercise it.
fn format_from_reader<R: Read + Seek>(
    reader: &mut R,
) -> std::io::Result<Option<fylz_sniff::Format>> {
    let mut header = vec![0u8; fylz_sniff::HEADER_LEN];
    let read = read_up_to(reader, &mut header)?;
    header.truncate(read);

    let len = reader.seek(SeekFrom::End(0))?;
    let tail = if len == 0 {
        None
    } else if len <= fylz_sniff::TAIL_LEN as u64 {
        // A file shorter than the tail window is its own tail; re-reading it as the header
        // again is harmless (`sniff_with_tail` doesn't care that header and tail overlap) and
        // simpler than clamping the seek-back distance to `len`.
        reader.seek(SeekFrom::Start(0))?;
        let mut buf = Vec::new();
        reader.read_to_end(&mut buf)?;
        Some(buf)
    } else {
        reader.seek(SeekFrom::End(-(fylz_sniff::TAIL_LEN as i64)))?;
        let mut buf = vec![0u8; fylz_sniff::TAIL_LEN];
        let read = read_up_to(reader, &mut buf)?;
        buf.truncate(read);
        Some(buf)
    };

    Ok(fylz_sniff::sniff_with_tail(&header, tail.as_deref()))
}

/// `Read::read` may return short of `buf.len()` even before EOF (a pipe, a slow FUSE-backed SAF
/// document); looping until the buffer fills or EOF is reached is what makes the header/tail
/// reads above actually reliable rather than "usually enough bytes."
fn read_up_to<R: Read>(reader: &mut R, buf: &mut [u8]) -> std::io::Result<usize> {
    let mut total = 0;
    while total < buf.len() {
        match reader.read(&mut buf[total..]) {
            Ok(0) => break,
            Ok(n) => total += n,
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(e) => return Err(e),
        }
    }
    Ok(total)
}

// ---------------------------------------------------------------------------------------------
// Archive inspection (M3.2b).
// ---------------------------------------------------------------------------------------------

/// `fylz_archive::policy::Limits`, field for field, in the widths uniffi has (`u32` for the three
/// counts and lengths, which are `usize` in the engine; uniffi has no `usize`). The Kotlin
/// `decoder.ArchiveLimits` Parcelable is this record's twin, and the app's one limits type.
#[derive(uniffi::Record, Debug, Clone, PartialEq)]
pub struct ArchiveLimitsRecord {
    pub max_entries: u32,
    pub max_archive_bytes: u64,
    pub max_file_bytes: u64,
    pub max_total_uncompressed_bytes: u64,
    pub max_compression_ratio: f64,
    pub max_path_depth: u32,
    pub max_name_length: u32,
    pub max_listing_entries: u32,
}

impl From<ArchiveLimitsRecord> for Limits {
    fn from(record: ArchiveLimitsRecord) -> Self {
        Limits {
            max_entries: record.max_entries as usize,
            max_archive_bytes: record.max_archive_bytes,
            max_file_bytes: record.max_file_bytes,
            max_total_uncompressed_bytes: record.max_total_uncompressed_bytes,
            max_compression_ratio: record.max_compression_ratio,
            max_path_depth: record.max_path_depth as usize,
            max_name_length: record.max_name_length as usize,
            max_listing_entries: record.max_listing_entries as usize,
        }
    }
}

/// `fylz_archive::EntryKind` over uniffi. The two `From` impls are exhaustive matches, so a new
/// engine variant fails to compile here rather than silently mapping to something.
#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum ArchiveEntryKindRecord {
    File,
    Directory,
    Symlink,
    Hardlink,
    Other,
}

impl From<EntryKind> for ArchiveEntryKindRecord {
    fn from(kind: EntryKind) -> Self {
        match kind {
            EntryKind::File => ArchiveEntryKindRecord::File,
            EntryKind::Directory => ArchiveEntryKindRecord::Directory,
            EntryKind::Symlink => ArchiveEntryKindRecord::Symlink,
            EntryKind::Hardlink => ArchiveEntryKindRecord::Hardlink,
            EntryKind::Other => ArchiveEntryKindRecord::Other,
        }
    }
}

impl From<ArchiveEntryKindRecord> for EntryKind {
    fn from(kind: ArchiveEntryKindRecord) -> Self {
        match kind {
            ArchiveEntryKindRecord::File => EntryKind::File,
            ArchiveEntryKindRecord::Directory => EntryKind::Directory,
            ArchiveEntryKindRecord::Symlink => EntryKind::Symlink,
            ArchiveEntryKindRecord::Hardlink => EntryKind::Hardlink,
            ArchiveEntryKindRecord::Other => EntryKind::Other,
        }
    }
}

/// One listing row: `fylz_archive::EntryMetadata` minus `compressed` (always `None` from the
/// engine -- libarchive has no per-entry compressed size -- so it is not carried).
#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct ArchiveEntryRecord {
    pub path: String,
    pub name_lossy: bool,
    pub kind: ArchiveEntryKindRecord,
    pub link_target: Option<String>,
    pub uncompressed: Option<u64>,
    pub mtime: Option<i64>,
    pub mode: u32,
    pub encrypted_data: bool,
    pub encrypted_metadata: bool,
}

impl From<&EntryMetadata> for ArchiveEntryRecord {
    fn from(entry: &EntryMetadata) -> Self {
        ArchiveEntryRecord {
            path: entry.path.clone(),
            name_lossy: entry.name_lossy,
            kind: entry.kind.into(),
            link_target: entry.link_target.clone(),
            uncompressed: entry.uncompressed,
            mtime: entry.mtime,
            mode: entry.mode,
            encrypted_data: entry.encrypted_data,
            encrypted_metadata: entry.encrypted_metadata,
        }
    }
}

/// What one `archive_inspect` call hands back: the archive's structure, the policy's verdict, and
/// the first `max_rows` entries. Everything a caller decides on is a count or a code; `format_name`
/// is informational (for ZIP it names the compression method of whichever entry the reader last
/// positioned on).
#[derive(uniffi::Record, Debug, Clone, PartialEq)]
pub struct ArchiveInspectionRecord {
    pub archive_bytes: u64,
    /// `archive_format() & ARCHIVE_FORMAT_BASE_MASK`: the format family (`0x50000` ZIP, `0xE0000`
    /// 7-Zip, `0x40000` ISO 9660, `0x30000` tar, ...), which is what UI decisions key on.
    pub format_code: u32,
    pub format_name: Option<String>,
    pub filters: Vec<String>,
    /// Every entry the header pass listed (links and `Other` kinds included); the three counts
    /// below partition it apart from `Other`.
    pub entry_count: u32,
    pub file_count: u32,
    pub directory_count: u32,
    pub link_count: u32,
    /// The declared sizes of every non-directory entry summed; `None` when any of them is unknown
    /// or the sum overflows. Directories are excluded because their "size" is format noise (an
    /// ISO directory reports its extent length), not bytes an extraction would write.
    pub total_uncompressed: Option<u64>,
    /// The engine's own verdict (`archive_read_has_encrypted_entries` said yes) or any entry
    /// flagged `encrypted_data`.
    pub has_encrypted_entries: bool,
    pub has_encrypted_metadata: bool,
    pub has_lossy_names: bool,
    pub policy_allowed: bool,
    pub policy_reason: Option<String>,
    /// The first `max_rows` entries, in archive order, cut **here** so a large listing never
    /// crosses uniffi (or Binder) whole.
    pub rows: Vec<ArchiveEntryRecord>,
    pub rows_truncated: bool,
}

/// `fylz_archive::ArchiveError` over uniffi, plus the one case the engine never produces from
/// `inspect` but a total conversion must still name.
#[derive(uniffi::Error, Debug, Clone, PartialEq, Eq)]
pub enum ArchiveEngineError {
    /// `fstat` says the descriptor is not a regular file (or the caller passed a negative one).
    /// Unreachable through the app's `ArchiveSource`, which stages non-seekable input first.
    NotSeekable { detail: String },
    /// No format recognised the input, or a 7-Zip archive whose header is itself encrypted.
    Unsupported { detail: String },
    /// libarchive reported a fatal error mid-way: the archive is damaged (or lies).
    Corrupt { detail: String },
    /// `Limits::max_listing_entries` stopped the header pass; `rule` is `"listing"` here.
    LimitExceeded { entry: String, rule: String },
    /// An engine error `inspect` is documented never to return (`NonUtf8Path`): a bug to report
    /// as such, not something to blame the archive for.
    Internal { detail: String },
}

impl std::fmt::Display for ArchiveEngineError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ArchiveEngineError::NotSeekable { detail } => write!(f, "{detail}"),
            ArchiveEngineError::Unsupported { detail } => {
                write!(f, "unsupported archive: {detail}")
            }
            ArchiveEngineError::Corrupt { detail } => write!(f, "{detail}"),
            ArchiveEngineError::LimitExceeded { entry, rule } => {
                write!(f, "limit exceeded ({rule}) at entry {entry:?}")
            }
            ArchiveEngineError::Internal { detail } => write!(f, "internal error: {detail}"),
        }
    }
}

impl std::error::Error for ArchiveEngineError {}

impl From<ArchiveError> for ArchiveEngineError {
    fn from(error: ArchiveError) -> Self {
        match error {
            ArchiveError::NotSeekable(detail) => ArchiveEngineError::NotSeekable { detail },
            ArchiveError::Unsupported(detail) => ArchiveEngineError::Unsupported { detail },
            ArchiveError::Fatal(detail) => ArchiveEngineError::Corrupt { detail },
            ArchiveError::LimitExceeded { entry, rule } => ArchiveEngineError::LimitExceeded {
                entry,
                rule: rule.to_string(),
            },
            ArchiveError::NonUtf8Path => ArchiveEngineError::Internal {
                detail: ArchiveError::NonUtf8Path.to_string(),
            },
        }
    }
}

/// One header pass over the archive open at `fd` (a caller-owned, read-only descriptor to a
/// regular file; used as a plain integer, never wrapped in anything that would close it), the
/// extraction policy over what it found, and the first `max_rows` entries. Synchronous on
/// purpose: the caller is a Binder thread in the decoder process with nothing else to do, and a
/// `suspend` function that never awaits (`sniff`) is a pattern not to copy.
#[uniffi::export]
pub fn archive_inspect(
    fd: i32,
    limits: ArchiveLimitsRecord,
    max_rows: u32,
) -> Result<ArchiveInspectionRecord, ArchiveEngineError> {
    if fd < 0 {
        return Err(ArchiveEngineError::NotSeekable {
            detail: format!("not seekable (invalid descriptor {fd})"),
        });
    }
    let limits = Limits::from(limits);
    let (inspection, decision) = fylz_archive::inspect_with_policy(fd, &limits)?;
    Ok(summarize(inspection, decision, max_rows))
}

/// Folds an [Inspection] and its [Decision] into the record, cutting the rows to `max_rows`.
fn summarize(inspection: Inspection, decision: Decision, max_rows: u32) -> ArchiveInspectionRecord {
    let entries = &inspection.entries;
    let count = |predicate: fn(&EntryMetadata) -> bool| -> u32 {
        u32::try_from(entries.iter().filter(|e| predicate(e)).count()).unwrap_or(u32::MAX)
    };
    let total_uncompressed = entries
        .iter()
        .filter(|e| e.kind != EntryKind::Directory)
        .try_fold(0u64, |total, e| {
            e.uncompressed.and_then(|size| total.checked_add(size))
        });
    let max_rows = max_rows as usize;
    ArchiveInspectionRecord {
        archive_bytes: inspection.archive_bytes,
        format_code: inspection.format_code,
        format_name: inspection.format_name.clone(),
        filters: inspection.filters.clone(),
        entry_count: u32::try_from(entries.len()).unwrap_or(u32::MAX),
        file_count: count(|e| e.kind == EntryKind::File),
        directory_count: count(|e| e.kind == EntryKind::Directory),
        link_count: count(|e| matches!(e.kind, EntryKind::Symlink | EntryKind::Hardlink)),
        total_uncompressed,
        has_encrypted_entries: inspection.has_encrypted_entries == Some(true)
            || entries.iter().any(|e| e.encrypted_data),
        has_encrypted_metadata: entries.iter().any(|e| e.encrypted_metadata),
        has_lossy_names: entries.iter().any(|e| e.name_lossy),
        policy_allowed: decision.allowed,
        policy_reason: decision.reason.map(str::to_string),
        rows: entries
            .iter()
            .take(max_rows)
            .map(ArchiveEntryRecord::from)
            .collect(),
        rows_truncated: entries.len() > max_rows,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs::File;
    use std::io::Cursor;
    use std::os::fd::AsRawFd;
    use std::path::Path;

    fn limits() -> ArchiveLimitsRecord {
        let defaults = Limits::default();
        ArchiveLimitsRecord {
            max_entries: defaults.max_entries as u32,
            max_archive_bytes: defaults.max_archive_bytes,
            max_file_bytes: defaults.max_file_bytes,
            max_total_uncompressed_bytes: defaults.max_total_uncompressed_bytes,
            max_compression_ratio: defaults.max_compression_ratio,
            max_path_depth: defaults.max_path_depth as u32,
            max_name_length: defaults.max_name_length as u32,
            max_listing_entries: defaults.max_listing_entries as u32,
        }
    }

    fn fixture(name: &str) -> File {
        let path = Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../fixtures")
            .join(name);
        File::open(&path).unwrap_or_else(|e| panic!("opening fixture {}: {e}", path.display()))
    }

    #[test]
    fn limits_record_round_trips_the_engine_defaults() {
        assert_eq!(Limits::from(limits()), Limits::default());
    }

    #[test]
    fn archive_inspect_summarises_a_zip_and_cuts_the_rows_in_rust() {
        let file = fixture("archives/sample-cd.zip");
        let record = archive_inspect(file.as_raw_fd(), limits(), 2).unwrap();
        assert_eq!(record.archive_bytes, 1_514);
        assert_eq!(record.format_code, 0x50000);
        assert_eq!(record.filters, Vec::<String>::new());
        assert_eq!(
            (
                record.entry_count,
                record.file_count,
                record.directory_count,
                record.link_count
            ),
            (8, 5, 3, 0)
        );
        assert_eq!(record.total_uncompressed, Some(268 + 67 + 11 + 1024 + 256));
        assert!(!record.has_encrypted_entries && !record.has_encrypted_metadata);
        assert!(!record.has_lossy_names);
        assert!(record.policy_allowed);
        assert_eq!(record.policy_reason, None);
        assert_eq!(record.rows.len(), 2);
        assert!(record.rows_truncated);
        assert_eq!(record.rows[0].path, "docs/");
        assert_eq!(record.rows[0].kind, ArchiveEntryKindRecord::Directory);
        assert_eq!(record.rows[0].mode, 0o755);
        assert_eq!(record.rows[0].mtime, Some(1_577_836_800));
        // Every row fits when asked for.
        let file = fixture("archives/sample-cd.zip");
        let all = archive_inspect(file.as_raw_fd(), limits(), 500).unwrap();
        assert_eq!(all.rows.len(), 8);
        assert!(!all.rows_truncated);
        // Exactly the count is not truncated; one fewer is.
        let file = fixture("archives/sample-cd.zip");
        assert!(
            !archive_inspect(file.as_raw_fd(), limits(), 8)
                .unwrap()
                .rows_truncated
        );
        let file = fixture("archives/sample-cd.zip");
        let none = archive_inspect(file.as_raw_fd(), limits(), 0).unwrap();
        assert!(none.rows.is_empty() && none.rows_truncated);
    }

    #[test]
    fn archive_inspect_carries_the_policy_refusal_and_the_link_row() {
        let file = fixture("archives/hostile/symlink-escape.tar");
        let record = archive_inspect(file.as_raw_fd(), limits(), 500).unwrap();
        assert!(!record.policy_allowed);
        assert_eq!(
            record.policy_reason.as_deref(),
            Some("Archive contains a link that escapes the extraction folder.")
        );
        assert_eq!((record.file_count, record.link_count), (1, 1));
        assert_eq!(record.rows[1].kind, ArchiveEntryKindRecord::Symlink);
        assert_eq!(record.rows[1].link_target.as_deref(), Some("/etc/passwd"));
        // Links' declared sizes count toward the total, directories' do not.
        assert_eq!(record.total_uncompressed, Some(5));
    }

    #[test]
    fn archive_inspect_reports_the_engines_errors_by_kind() {
        assert_eq!(
            archive_inspect(-1, limits(), 500),
            Err(ArchiveEngineError::NotSeekable {
                detail: "not seekable (invalid descriptor -1)".into()
            })
        );
        let (reader, _writer) = std::io::pipe().unwrap();
        assert_eq!(
            archive_inspect(reader.as_raw_fd(), limits(), 500),
            Err(ArchiveEngineError::NotSeekable {
                detail: "not seekable (fifo)".into()
            })
        );
        let file = fixture("archives/sample-encrypted-header.7z");
        assert!(matches!(
            archive_inspect(file.as_raw_fd(), limits(), 500),
            Err(ArchiveEngineError::Unsupported { .. })
        ));
        let file = fixture("truncated.tar.gz");
        assert!(matches!(
            archive_inspect(file.as_raw_fd(), limits(), 500),
            Err(ArchiveEngineError::Corrupt { .. })
        ));
        let file = fixture("archives/hostile/many-entries.tar.zst");
        let bounded = ArchiveLimitsRecord {
            max_listing_entries: 10_000,
            ..limits()
        };
        assert_eq!(
            archive_inspect(file.as_raw_fd(), bounded, 500),
            Err(ArchiveEngineError::LimitExceeded {
                entry: "e10000".into(),
                rule: "listing".into()
            })
        );
    }

    #[test]
    fn error_and_kind_conversions_are_total() {
        let cases = [
            (
                ArchiveError::NotSeekable("not seekable (socket)".into()),
                ArchiveEngineError::NotSeekable {
                    detail: "not seekable (socket)".into(),
                },
            ),
            (
                ArchiveError::Unsupported("Unrecognized archive format".into()),
                ArchiveEngineError::Unsupported {
                    detail: "Unrecognized archive format".into(),
                },
            ),
            (
                ArchiveError::Fatal("Truncated input".into()),
                ArchiveEngineError::Corrupt {
                    detail: "Truncated input".into(),
                },
            ),
            (
                ArchiveError::LimitExceeded {
                    entry: "x".into(),
                    rule: "listing",
                },
                ArchiveEngineError::LimitExceeded {
                    entry: "x".into(),
                    rule: "listing".into(),
                },
            ),
            (
                ArchiveError::NonUtf8Path,
                ArchiveEngineError::Internal {
                    detail: "entry pathname is not valid UTF-8".into(),
                },
            ),
        ];
        for (engine, expected) in cases {
            assert_eq!(ArchiveEngineError::from(engine), expected);
        }
        for kind in [
            EntryKind::File,
            EntryKind::Directory,
            EntryKind::Symlink,
            EntryKind::Hardlink,
            EntryKind::Other,
        ] {
            assert_eq!(EntryKind::from(ArchiveEntryKindRecord::from(kind)), kind);
        }
    }

    #[test]
    fn engine_errors_display_without_debug_noise() {
        assert_eq!(
            ArchiveEngineError::LimitExceeded {
                entry: "e10000".into(),
                rule: "listing".into()
            }
            .to_string(),
            "limit exceeded (listing) at entry \"e10000\""
        );
        assert_eq!(
            ArchiveEngineError::Unsupported { detail: "x".into() }.to_string(),
            "unsupported archive: x"
        );
        assert_eq!(
            ArchiveEngineError::Internal { detail: "x".into() }.to_string(),
            "internal error: x"
        );
    }

    #[test]
    fn version_matches_the_crate_manifest() {
        assert_eq!(fylz_version(), env!("CARGO_PKG_VERSION"));
    }

    #[test]
    fn sniff_never_blocks_on_a_negative_fd() {
        assert_eq!(pollster::block_on(sniff(-1)), "unknown");
    }

    #[test]
    fn an_empty_reader_is_unknown_not_an_error() {
        let mut empty = Cursor::new(Vec::<u8>::new());
        assert_eq!(format_from_reader(&mut empty).unwrap(), None);
    }

    #[test]
    fn a_png_header_is_identified_by_content_not_a_filename_extension() {
        let mut data = b"\x89PNG\r\n\x1A\n".to_vec();
        data.extend_from_slice(&[0u8; 32]);
        let mut reader = Cursor::new(data);
        assert_eq!(
            format_from_reader(&mut reader).unwrap(),
            Some(fylz_sniff::Format::Png)
        );
    }

    #[test]
    fn a_short_file_still_gets_its_own_bytes_as_the_tail() {
        // Shorter than fylz_sniff::TAIL_LEN entirely -- the tail-seek-back path must not panic
        // or truncate the seek offset into the negative in a way that misses the file's own data.
        let mut reader = Cursor::new(b"%PDF-1.4\n".to_vec());
        assert_eq!(
            format_from_reader(&mut reader).unwrap(),
            Some(fylz_sniff::Format::Pdf)
        );
    }

    #[test]
    fn a_dmg_is_only_identified_once_the_trailer_is_actually_read() {
        let mut data = vec![0u8; 4096];
        data[4096 - 512..4096 - 508].copy_from_slice(b"koly");
        let mut reader = Cursor::new(data);
        assert_eq!(
            format_from_reader(&mut reader).unwrap(),
            Some(fylz_sniff::Format::Dmg)
        );
    }

    #[test]
    fn detect_format_rejects_a_negative_fd_without_touching_the_os() {
        assert_eq!(detect_format(-1), None);
    }
}
