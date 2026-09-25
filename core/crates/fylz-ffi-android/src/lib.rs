//! Uniffi bindings exposed to Kotlin.
//!
//! `fylz_version` and `sniff` are the whole M2.2 pipeline proof (Rust -> uniffi -> generated
//! Kotlin -> Gradle) end to end; `sniff` itself is `fylz-sniff`'s content-detection logic (M2.5)
//! wired to a real, caller-owned file descriptor. `archive_inspect` (M3.2b) is `fylz-archive`'s
//! one header pass plus the extraction policy, summarised into a record small enough to cross
//! Binder (`docs/agent/DESIGN-M32-SEEKABLE-PFD.md` section 2.4). `archive_list_into` and
//! `archive_extract_entry_at` (M3.3a) are the browsing pair: the same header pass writing the
//! full listing into a caller-owned pipe as it goes, and one entry by header ordinal streamed
//! into another (`docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md` section 2.2).
//!
//! SIGPIPE: the sinks those two write into are pipes the UI process owns, and a cancelled call
//! closes the read end first, so the engine's `write_all` gets `EPIPE`. Rust installs `SIG_IGN`
//! for SIGPIPE only in binaries (`lang_start`), never in a cdylib such as this one, so with a
//! default disposition that write would kill `:decoders` outright and the failure would look like
//! a crash rather than an `Err`. Android's runtime does ignore SIGPIPE in every app process
//! (`app_process`'s `main` does so before the Zygote forks, and `:decoders` is a Zygote fork), but
//! this crate does not rely on it: [ignore_sigpipe] runs once at the start of every exported
//! archive function, through a hand-declared `signal(2)` as `fylz-archive`'s `sys` module declares
//! its own externs.
//!
//! Naming: every uniffi record/enum here ends in `Record`, because uniffi generates Kotlin classes
//! with these exact names into `io.github.mbaliga.fylz.core`, and `DecoderService` imports both
//! those and the `decoder.*` Parcelables they are copied into (`ArchiveLimits`, `ArchiveInspection`,
//! `ArchiveEntryInfo`) -- the suffix keeps the two layers apart at every use site. Error variants
//! carry a field named `detail`, never `message`: uniffi's generated Kotlin error class would
//! otherwise declare `val message` against `Throwable.message` and fail to compile.

use std::io::{Read, Seek, SeekFrom, Write};
use std::mem::ManuallyDrop;
use std::os::fd::{FromRawFd, RawFd};
use std::sync::Once;

use fylz_archive::ArchiveError;
use fylz_archive::Decision;
use fylz_archive::EntryKind;
use fylz_archive::EntryMetadata;
use fylz_archive::ExtractLimits;
use fylz_archive::Inspection;
use fylz_archive::Limits;

uniffi::setup_scaffolding!();

mod signal_sys {
    //! The one `signal(2)` declaration behind [super::ignore_sigpipe], hand-written for the same
    //! reason `fylz-archive`'s `sys` module is: a couple of decades-old, stable libc symbols do
    //! not justify a `libc` crate dependency in the shipped library. `SIGPIPE` is 13 and `SIG_IGN`
    //! is `(sighandler_t) 1` on every Linux ABI this crate builds for (aarch64, armv7, x86_64
    //! bionic, and the glibc host), per the kernel's `asm-generic/signal.h`.
    use std::os::raw::c_int;

    pub type SigHandler = usize;
    pub const SIGPIPE: c_int = 13;
    pub const SIG_IGN: SigHandler = 1;
    #[cfg(test)]
    pub const SIG_DFL: SigHandler = 0;

    unsafe extern "C" {
        pub fn signal(signum: c_int, handler: SigHandler) -> SigHandler;
    }
}

static SIGPIPE_IGNORED: Once = Once::new();

/// Sets SIGPIPE to `SIG_IGN` once per process (see the crate doc for why a cdylib must do this
/// itself), so a write into a pipe whose reader has gone reports `EPIPE` as an `Err` instead of
/// terminating the decoder process.
fn ignore_sigpipe() {
    SIGPIPE_IGNORED.call_once(|| {
        // SAFETY: `signal` with `SIG_IGN` installs no handler code of ours and touches no memory;
        // its only effect is the process-wide disposition of SIGPIPE, which nothing in this
        // process relies on being the default.
        unsafe {
            signal_sys::signal(signal_sys::SIGPIPE, signal_sys::SIG_IGN);
        }
    });
}

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
    /// `EntryMetadata::ordinal`: the raw header index this entry came from (M3.3).
    pub ordinal: u32,
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
            ordinal: entry.ordinal,
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
    /// crosses uniffi (or Binder) whole. Empty from `archive_list_into`, whose listing went into
    /// the sink instead.
    pub rows: Vec<ArchiveEntryRecord>,
    pub rows_truncated: bool,
    /// `archive_list_into` only (M3.3): the header pass stopped on a damaged header after at
    /// least one entry; the sink holds what was read, with its trailer flagged partial, and
    /// `partial_message` is libarchive's own text for the damage. Always `false` from
    /// `archive_inspect`, which reports that damage as `Corrupt` instead.
    #[uniffi(default = false)]
    pub partial: bool,
    #[uniffi(default = None)]
    pub partial_message: Option<String>,
    /// `Inspection::structural_refusal` (M3.3a): the policy's reason under limits with every size
    /// rule off, so only the structural rules (paths, duplicates, links, unknown sizes, overflow)
    /// can fire. `None` when the archive is structurally sound whatever its size.
    #[uniffi(default = None)]
    pub structural_refusal: Option<String>,
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
    /// `archive_extract_entry_at` found no header at `ordinal`, or a different path there than
    /// the caller expected: the archive changed under its listing, or the id was forged. Carries
    /// its fields (so it is not a `flat_error`, which would drop them).
    NotFound { ordinal: u32, path: String },
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
            ArchiveEngineError::NotFound { ordinal, path } => {
                write!(f, "no entry {path:?} at header {ordinal}")
            }
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
            ArchiveError::NotFound { ordinal, path } => ArchiveEngineError::NotFound {
                ordinal: u32::try_from(ordinal).unwrap_or(u32::MAX),
                path,
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
    ignore_sigpipe();
    check_fd(fd)?;
    let limits = Limits::from(limits);
    let (inspection, decision) = fylz_archive::inspect_with_policy(fd, &limits)?;
    Ok(summarize(inspection, decision, Some(max_rows), None))
}

/// The same header pass as [archive_inspect], writing the full listing into `sink_fd` in the
/// `fylz_archive::listing` codec **as each header is read** (M3.3a; design section 2.2): the
/// browser's transport for a table Binder could never carry whole. `sink_fd` is the write end of
/// a pipe the caller created and owns (the decoder process closes its own dup after this returns;
/// this function never closes it), `fd` the archive as for [archive_inspect]. The record comes
/// back with `rows` empty, and `partial = true` (with libarchive's message) when the pass stopped
/// on damage after at least one entry -- the sink then holds what was readable. The policy is
/// evaluated over the same entries, so a browsing caller can refuse to *open* entries of an
/// archive that failed the extraction rules while still listing it.
#[uniffi::export]
pub fn archive_list_into(
    fd: i32,
    limits: ArchiveLimitsRecord,
    sink_fd: i32,
) -> Result<ArchiveInspectionRecord, ArchiveEngineError> {
    ignore_sigpipe();
    check_fd(fd)?;
    if sink_fd < 0 {
        return Err(ArchiveEngineError::Internal {
            detail: format!("invalid listing sink descriptor {sink_fd}"),
        });
    }
    let limits = Limits::from(limits);
    // SAFETY: `sink_fd` is caller-owned for the whole call, per this function's contract; the
    // `ManuallyDrop` keeps the `File` from closing it.
    let mut sink = ManuallyDrop::new(unsafe { std::fs::File::from_raw_fd(sink_fd) });
    let pass = fylz_archive::inspect_into(fd, &limits, &mut *sink)?;
    sink.flush().map_err(|e| ArchiveEngineError::Corrupt {
        detail: format!("writing the listing: {e}"),
    })?;
    let decision = fylz_archive::policy::evaluate(
        pass.inspection.archive_bytes,
        &pass.inspection.entries,
        &limits,
    );
    Ok(summarize(pass.inspection, decision, None, pass.partial))
}

/// Streams the entry at raw header `ordinal` (`ArchiveEntryRecord::ordinal`, a listing record's
/// ordinal), whose path must be byte for byte `expected_path`, into `sink_fd` (caller-owned, the
/// write end of a pipe; never closed here) under the extraction caps in `limits`, and returns the
/// bytes written; the engine stops reading the archive as soon as the entry is written (M3.3a;
/// design section 2.2). A mismatch is [ArchiveEngineError::NotFound] with both fields.
#[uniffi::export]
pub fn archive_extract_entry_at(
    fd: i32,
    ordinal: u32,
    expected_path: String,
    limits: ArchiveLimitsRecord,
    sink_fd: i32,
) -> Result<u64, ArchiveEngineError> {
    ignore_sigpipe();
    check_fd(fd)?;
    if sink_fd < 0 {
        return Err(ArchiveEngineError::Internal {
            detail: format!("invalid entry sink descriptor {sink_fd}"),
        });
    }
    let limits = ExtractLimits::from(&Limits::from(limits));
    Ok(fylz_archive::extract_entry_at(
        fd,
        ordinal as usize,
        &expected_path,
        &limits,
        sink_fd,
    )?)
}

fn check_fd(fd: i32) -> Result<(), ArchiveEngineError> {
    if fd < 0 {
        return Err(ArchiveEngineError::NotSeekable {
            detail: format!("not seekable (invalid descriptor {fd})"),
        });
    }
    Ok(())
}

/// Folds an [Inspection] and its [Decision] into the record, cutting the rows to `max_rows`;
/// `None` carries no rows at all and is not "truncated" (the listing went into a sink instead).
fn summarize(
    inspection: Inspection,
    decision: Decision,
    max_rows: Option<u32>,
    partial: Option<String>,
) -> ArchiveInspectionRecord {
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
    let (rows, rows_truncated) = match max_rows {
        Some(max_rows) => {
            let max_rows = max_rows as usize;
            (
                entries
                    .iter()
                    .take(max_rows)
                    .map(ArchiveEntryRecord::from)
                    .collect(),
                entries.len() > max_rows,
            )
        }
        None => (Vec::new(), false),
    };
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
        rows,
        rows_truncated,
        partial: partial.is_some(),
        partial_message: partial,
        structural_refusal: inspection.structural_refusal.clone(),
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
        assert_eq!(record.rows[0].ordinal, 0);
        assert_eq!(record.rows[1].ordinal, 1);
        assert!(!record.partial && record.partial_message.is_none());
        assert_eq!(record.structural_refusal, None);
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
        // An escaping link is structural: no destination size would make it safe.
        assert_eq!(
            record.structural_refusal.as_deref(),
            Some("Archive contains a link that escapes the extraction folder.")
        );
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
            (
                ArchiveError::NotFound {
                    ordinal: 7,
                    path: "a/b".into(),
                },
                ArchiveEngineError::NotFound {
                    ordinal: 7,
                    path: "a/b".into(),
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
        assert_eq!(
            ArchiveEngineError::NotFound {
                ordinal: 3,
                path: "dir/x".into()
            }
            .to_string(),
            "no entry \"dir/x\" at header 3"
        );
    }

    // -----------------------------------------------------------------------------------------
    // M3.3a: the browsing pair and SIGPIPE.
    // -----------------------------------------------------------------------------------------

    fn scratch(name: &str) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!("fylz-ffi-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        dir.join(name)
    }

    #[test]
    fn archive_list_into_writes_the_listing_to_the_sink_and_returns_rows_empty() {
        let sink_path = scratch("cd.fzl");
        let sink = File::create(&sink_path).unwrap();
        let file = fixture("archives/sample-cd.zip");
        let record = archive_list_into(file.as_raw_fd(), limits(), sink.as_raw_fd()).unwrap();
        assert!(record.rows.is_empty());
        assert!(!record.rows_truncated);
        assert!(!record.partial && record.partial_message.is_none());
        assert_eq!(record.entry_count, 8);
        assert!(record.policy_allowed);
        // The sink holds the same bytes the engine's own writer produces, i.e. the golden file.
        let golden = std::fs::read(
            Path::new(env!("CARGO_MANIFEST_DIR"))
                .join("../../../app/src/test/resources/fixtures/archives/sample-cd.fzl"),
        )
        .unwrap();
        assert_eq!(std::fs::read(&sink_path).unwrap(), golden);
        // The sink descriptor is still open: the caller owns it.
        assert!(sink.metadata().is_ok());
        // A negative sink is refused before anything is read.
        assert!(matches!(
            archive_list_into(fixture("archives/sample-cd.zip").as_raw_fd(), limits(), -1),
            Err(ArchiveEngineError::Internal { .. })
        ));
    }

    #[test]
    fn archive_list_into_reports_partial_for_a_damaged_tar_and_the_policy_over_what_was_read() {
        let sink_path = scratch("damaged.fzl");
        let sink = File::create(&sink_path).unwrap();
        let file = fixture("archives/damaged-after-3.tar");
        let record = archive_list_into(file.as_raw_fd(), limits(), sink.as_raw_fd()).unwrap();
        assert!(record.partial);
        assert!(record
            .partial_message
            .as_deref()
            .is_some_and(|m| !m.is_empty()));
        assert_eq!(record.entry_count, 3);
        assert!(record.policy_allowed);
        let bytes = std::fs::read(&sink_path).unwrap();
        assert_eq!(bytes[bytes.len() - 1], 1, "trailer flagged partial");
        // The same file through archive_inspect is Corrupt: M3.2's contract is unchanged.
        assert!(matches!(
            archive_inspect(
                fixture("archives/damaged-after-3.tar").as_raw_fd(),
                limits(),
                5
            ),
            Err(ArchiveEngineError::Corrupt { .. })
        ));
    }

    #[test]
    fn archive_extract_entry_at_streams_one_entry_and_reports_not_found_with_fields() {
        let dest_path = scratch("hello.out");
        let dest = File::create(&dest_path).unwrap();
        let file = fixture("archives/sample-cd.zip");
        // `hello.txt` is header 5 of sample-cd.zip (three directories, two docs files first).
        let listing =
            archive_inspect(fixture("archives/sample-cd.zip").as_raw_fd(), limits(), 500).unwrap();
        let hello = listing.rows.iter().find(|r| r.path == "hello.txt").unwrap();
        let written = archive_extract_entry_at(
            file.as_raw_fd(),
            hello.ordinal,
            "hello.txt".into(),
            limits(),
            dest.as_raw_fd(),
        )
        .unwrap();
        assert_eq!(written, 11);
        assert_eq!(std::fs::read(&dest_path).unwrap(), b"hello world");
        assert!(dest.metadata().is_ok(), "the sink is the caller's to close");
        assert_eq!(
            archive_extract_entry_at(
                fixture("archives/sample-cd.zip").as_raw_fd(),
                hello.ordinal,
                "HELLO.TXT".into(),
                limits(),
                dest.as_raw_fd(),
            ),
            Err(ArchiveEngineError::NotFound {
                ordinal: hello.ordinal,
                path: "HELLO.TXT".into()
            })
        );
        assert_eq!(
            archive_extract_entry_at(
                fixture("archives/sample-cd.zip").as_raw_fd(),
                99,
                "hello.txt".into(),
                limits(),
                dest.as_raw_fd(),
            ),
            Err(ArchiveEngineError::NotFound {
                ordinal: 99,
                path: "hello.txt".into()
            })
        );
        assert!(matches!(
            archive_extract_entry_at(-1, 0, "x".into(), limits(), dest.as_raw_fd()),
            Err(ArchiveEngineError::NotSeekable { .. })
        ));
        assert!(matches!(
            archive_extract_entry_at(
                fixture("archives/sample-cd.zip").as_raw_fd(),
                0,
                "x".into(),
                limits(),
                -1
            ),
            Err(ArchiveEngineError::Internal { .. })
        ));
    }

    /// The SIGPIPE contract (crate doc): a write into a pipe whose read end is closed must come
    /// back as an `Err`, not kill the process. The Rust test harness is a binary, so it has
    /// already set SIGPIPE to `SIG_IGN` for this process -- which is exactly the disposition a
    /// cdylib cannot count on -- so the real check runs in a **child process** that first resets
    /// SIGPIPE to `SIG_DFL` and then calls the exported function; the parent asserts the child
    /// exited normally (an `Err` was returned) rather than being terminated by the signal. A
    /// fresh process also means `ignore_sigpipe`'s `Once` genuinely runs inside the call under
    /// test, whatever other tests did in this process.
    #[test]
    fn a_write_into_a_closed_pipe_is_an_error_not_a_dead_decoder_process() {
        const CHILD_MARKER: &str = "FYLZ_SIGPIPE_CHILD";
        if std::env::var_os(CHILD_MARKER).is_some() {
            // SAFETY: restoring the default disposition of SIGPIPE in a throwaway child process.
            unsafe {
                signal_sys::signal(signal_sys::SIGPIPE, signal_sys::SIG_DFL);
            }
            let (reader, writer) = std::io::pipe().unwrap();
            drop(reader);
            let file = fixture("archives/sample-entries.zip");
            let listing = archive_inspect(
                fixture("archives/sample-entries.zip").as_raw_fd(),
                limits(),
                500,
            )
            .unwrap();
            let big = listing.rows.iter().find(|r| r.path == "big.bin").unwrap();
            // 3 MB into a pipe nobody reads: the first write past the pipe buffer is EPIPE.
            let result = archive_extract_entry_at(
                file.as_raw_fd(),
                big.ordinal,
                "big.bin".into(),
                limits(),
                writer.as_raw_fd(),
            );
            assert!(
                matches!(result, Err(ArchiveEngineError::Corrupt { ref detail }) if detail.contains("writing the destination")),
                "{result:?}"
            );
            // And the listing path the same way.
            let (reader, writer) = std::io::pipe().unwrap();
            drop(reader);
            let result = archive_list_into(
                fixture("archives/hostile/many-entries.tar.zst").as_raw_fd(),
                limits(),
                writer.as_raw_fd(),
            );
            assert!(
                matches!(result, Err(ArchiveEngineError::Corrupt { ref detail }) if detail.contains("writing the listing")),
                "{result:?}"
            );
            return;
        }
        let exe = std::env::current_exe().unwrap();
        let output = std::process::Command::new(exe)
            .arg("--exact")
            .arg("tests::a_write_into_a_closed_pipe_is_an_error_not_a_dead_decoder_process")
            .arg("--nocapture")
            .env(CHILD_MARKER, "1")
            .output()
            .expect("re-running this test binary as a child process");
        assert!(
            output.status.success(),
            "the child did not exit normally (status {:?}: SIGPIPE killed it, or the assertion failed):\n{}\n{}",
            output.status,
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
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
