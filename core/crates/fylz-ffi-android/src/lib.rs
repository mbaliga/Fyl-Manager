//! Uniffi bindings exposed to Kotlin.
//!
//! `fylz_version` and `sniff` are the whole M2.2 pipeline proof (Rust -> uniffi -> generated
//! Kotlin -> Gradle) end to end; `sniff` itself is `fylz-sniff`'s content-detection logic (M2.5)
//! wired to a real, caller-owned file descriptor. `archive_inspect` (M3.2b) is `fylz-archive`'s
//! one header pass plus the extraction policy, summarised into a record small enough to cross
//! Binder (`docs/agent/DESIGN-M32-SEEKABLE-PFD.md` section 2.4). `archive_list_into` and
//! `archive_extract_entry_at` (M3.3a) are the browsing pair: the same header pass writing the
//! full listing into a caller-owned pipe as it goes, and one entry by header ordinal streamed
//! into another (`docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md` section 2.2). `archive_extract_ranges`
//! (M3.4a) is the bulk path: every selected header ordinal of an archive streamed in one pass
//! through a single framed pipe ([frames]), after a header pass and the selection-scoped size
//! policy (`docs/agent/DESIGN-M34-SELECTIVE-EXTRACT.md` sections 2.3-2.5); it polls the sink for
//! a hang-up every 64 headers so a cancel during a long `tar.xz` header walk ends the call.
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
use fylz_archive::Selection;

pub mod frames;

uniffi::setup_scaffolding!();

mod poll_sys {
    //! The one `poll(2)` declaration behind [super::sink_hung_up], hand-written like `signal_sys`.
    //! `struct pollfd { int fd; short events; short revents; }` and `nfds_t` (`unsigned long`) are
    //! the same on every Linux ABI this crate builds for (bionic aarch64/armv7/x86_64 and the glibc
    //! host); `POLLERR`/`POLLHUP` are `0x008`/`0x010` in the kernel's `asm-generic/poll.h`.
    use std::os::raw::c_int;
    use std::os::raw::c_short;
    use std::os::raw::c_ulong;

    #[repr(C)]
    pub struct PollFd {
        pub fd: c_int,
        pub events: c_short,
        pub revents: c_short,
    }

    pub const POLLERR: c_short = 0x008;
    pub const POLLHUP: c_short = 0x010;

    unsafe extern "C" {
        pub fn poll(fds: *mut PollFd, nfds: c_ulong, timeout: c_int) -> c_int;
    }
}

/// Whether the reader of the pipe whose write end is `fd` has gone away: `poll` with a zero
/// timeout reports `POLLERR` on a pipe's write end once every read end is closed (and `POLLHUP`
/// for other descriptor kinds). The extraction pass asks this every 64 headers, so a cancel from
/// the UI process (which closes its read end) is noticed inside a header walk that writes nothing.
fn sink_hung_up(fd: RawFd) -> bool {
    let mut pollfd = poll_sys::PollFd {
        fd,
        events: 0,
        revents: 0,
    };
    // SAFETY: one initialised `pollfd` in a local array of one, a zero timeout; `poll` writes
    // only `revents`.
    let ready = unsafe { poll_sys::poll(&mut pollfd, 1, 0) };
    ready > 0 && (pollfd.revents & (poll_sys::POLLERR | poll_sys::POLLHUP)) != 0
}

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
    /// libarchive reported one entry's data or header wrong (a CRC or size mismatch, a header it
    /// could not parse) while the archive stays readable (M3.4): from `archive_extract_entry_at`,
    /// whose one entry is then lost. The bulk path reports these per entry in its frames instead.
    Failed { detail: String },
    /// The caller cancelled (the sink's reader went away); nothing is wrong with the archive.
    Cancelled,
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
            ArchiveEngineError::Failed { detail } => write!(f, "{detail}"),
            ArchiveEngineError::Cancelled => write!(f, "cancelled"),
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
            ArchiveError::Failed(detail) => ArchiveEngineError::Failed { detail },
            ArchiveError::Cancelled => ArchiveEngineError::Cancelled,
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Selective extraction (M3.4a).
// ---------------------------------------------------------------------------------------------

/// An inclusive range of header ordinals (`EntryMetadata::ordinal`), a plan's bitmap run.
#[derive(uniffi::Record, Debug, Clone, Copy, PartialEq, Eq)]
pub struct ArchiveOrdinalRangeRecord {
    pub first: u32,
    pub last: u32,
}

/// How one `archive_extract_ranges` call ended -- the authority for the outcome; the frames in the
/// sink are data. `decoder.ArchiveExtractResult`'s `OUTCOME_*` codes are this enum's twin.
#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum ArchiveExtractOutcomeRecord {
    /// The pass ran to the end: every selected entry ended or failed and `DONE` was written.
    Ok,
    /// `policy::evaluate_selection` refused the selection before any frame (the message is the
    /// rule's reason).
    Refused,
    NotSeekable,
    Unsupported,
    /// libarchive went fatal mid-way; the stream ends with `ABORT` and `stop_ordinal` says where.
    Corrupt,
    /// A runtime cap stopped the pass (`ABORT` written; not worth re-issuing).
    LimitExceeded,
    /// The sink's reader went away (a cancel from the UI process).
    Cancelled,
    /// A bug: an invalid descriptor, or an engine error this call is documented never to see.
    Internal,
}

/// What `archive_extract_ranges` hands back: the outcome, libarchive's message when there is one,
/// the counts `DONE` carried (or what was counted before an abort), and the ordinal the engine
/// blames for an abort (`fylz_archive::BlocksOutcome::stop_ordinal`), which a re-issue resumes
/// **after**.
#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct ArchiveExtractRecord {
    pub outcome: ArchiveExtractOutcomeRecord,
    pub message: Option<String>,
    pub entries_written: u32,
    pub bytes_written: u64,
    pub entries_failed: u32,
    pub stop_ordinal: Option<u32>,
}

impl ArchiveExtractRecord {
    fn before_frames(outcome: ArchiveExtractOutcomeRecord, message: String) -> Self {
        ArchiveExtractRecord {
            outcome,
            message: Some(message),
            entries_written: 0,
            bytes_written: 0,
            entries_failed: 0,
            stop_ordinal: None,
        }
    }
}

/// The outcome and message for an engine error of the extraction pass. `Failed` cannot reach here
/// (the frame writer reports per-entry failures as frames and never returns it), `NonUtf8Path`
/// and `NotFound` are other entry points' errors: all three are `Internal`, as a bug would be.
fn extract_outcome(error: &ArchiveError) -> (ArchiveExtractOutcomeRecord, String) {
    let outcome = match error {
        ArchiveError::NotSeekable(_) => ArchiveExtractOutcomeRecord::NotSeekable,
        ArchiveError::Unsupported(_) => ArchiveExtractOutcomeRecord::Unsupported,
        ArchiveError::Fatal(_) => ArchiveExtractOutcomeRecord::Corrupt,
        ArchiveError::LimitExceeded { .. } => ArchiveExtractOutcomeRecord::LimitExceeded,
        ArchiveError::Cancelled => ArchiveExtractOutcomeRecord::Cancelled,
        ArchiveError::Failed(_) | ArchiveError::NonUtf8Path | ArchiveError::NotFound { .. } => {
            ArchiveExtractOutcomeRecord::Internal
        }
    };
    (outcome, error.to_string())
}

/// One extraction pass (M3.4a; design section 2.3 steps 3-4 and section 2.4): the header pass
/// over the archive at `fd` collecting the metadata of the ordinals in `ranges`
/// (`fylz_archive::inspect_for_extraction`), the selection-scoped size policy
/// (`policy::evaluate_selection` -- a refusal returns `Refused` before a single frame is written),
/// then `fylz_archive::extract_blocks_cancellable` writing the [frames] codec into `sink_fd` (the
/// write end of a pipe the caller owns; never closed here, as `fd` is not). Both passes poll the
/// sink for a hang-up every 64 headers ([sink_hung_up]); a write that meets `EPIPE` ends the pass
/// the same way. The stream is empty for `Refused`, `NotSeekable`, `Unsupported` and a cancel before
/// the first frame; `MAGIC ABORT` for a fatal error at any point (the reader requires the magic
/// with a `Corrupt` result); `MAGIC ... DONE` for `Ok`. Synchronous, like every archive export.
#[uniffi::export]
pub fn archive_extract_ranges(
    fd: i32,
    ranges: Vec<ArchiveOrdinalRangeRecord>,
    limits: ArchiveLimitsRecord,
    sink_fd: i32,
) -> ArchiveExtractRecord {
    ignore_sigpipe();
    if fd < 0 {
        return ArchiveExtractRecord::before_frames(
            ArchiveExtractOutcomeRecord::NotSeekable,
            format!("not seekable (invalid descriptor {fd})"),
        );
    }
    if sink_fd < 0 {
        return ArchiveExtractRecord::before_frames(
            ArchiveExtractOutcomeRecord::Internal,
            format!("invalid extraction sink descriptor {sink_fd}"),
        );
    }
    let limits = Limits::from(limits);
    let selection = Selection::Ranges(ranges.iter().map(|r| (r.first, r.last)).collect());
    let mut cancel = || sink_hung_up(sink_fd);
    // SAFETY: `sink_fd` is caller-owned for the whole call, per this function's contract; the
    // `ManuallyDrop` keeps the `File` from closing it.
    let mut sink = ManuallyDrop::new(unsafe { std::fs::File::from_raw_fd(sink_fd) });

    let selected = match fylz_archive::inspect_for_extraction(fd, &selection, &mut cancel) {
        Ok(selected) => selected,
        Err(error) => {
            let (outcome, message) = extract_outcome(&error);
            if outcome == ArchiveExtractOutcomeRecord::Corrupt {
                // The reader requires the magic with a Corrupt result: `MAGIC ABORT`.
                let _ = frames::FrameWriter::new(&mut *sink).abort(&message);
            }
            return ArchiveExtractRecord::before_frames(outcome, message);
        }
    };
    let decision = fylz_archive::policy::evaluate_selection(
        selected.archive_bytes,
        &selected.entries,
        &limits,
    );
    if !decision.allowed {
        return ArchiveExtractRecord::before_frames(
            ArchiveExtractOutcomeRecord::Refused,
            decision
                .reason
                .unwrap_or("Archive extraction was refused.")
                .to_string(),
        );
    }

    let extract_limits = ExtractLimits::from(&limits);
    let mut writer = frames::FrameWriter::new(&mut *sink);
    let outcome = fylz_archive::extract_blocks_cancellable(
        fd,
        &selection,
        &extract_limits,
        &mut writer,
        &mut cancel,
    );
    let (entries_written, bytes_written, entries_failed) = writer.counts();
    let (result_outcome, message, stop_ordinal) = match outcome.result {
        Ok(()) => match writer.finish() {
            Ok(_) => (ArchiveExtractOutcomeRecord::Ok, None, None),
            Err(error) => {
                let (code, message) = extract_outcome(&error);
                (code, Some(message), None)
            }
        },
        Err(error) => {
            let (code, message) = extract_outcome(&error);
            // Best effort: the reader has a terminal frame when the pipe still has a reader.
            let _ = writer.abort(&message);
            (code, Some(message), outcome.stop_ordinal)
        }
    };
    ArchiveExtractRecord {
        outcome: result_outcome,
        message,
        entries_written,
        bytes_written,
        entries_failed,
        stop_ordinal,
    }
}

// ---------------------------------------------------------------------------------------------
// Create (M3.5a, docs/agent/DESIGN-M35-CREATE.md section 2.5).
// ---------------------------------------------------------------------------------------------

/// `fylz_archive::write::WriteFormat` over uniffi: the format/filter pair `write_frames`
/// configures, resolved by the caller's own Fast/Normal/Best mapping into the numeric
/// `WriteOptionsRecord::level` -- this record only names *which* writer, never the level's own
/// meaning per format (that table lives in `CompressPlanner.kt`/design section 2.4, not here).
#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum WriteFormatRecord {
    Zip,
    TarGz,
    TarXz,
    TarZstd,
    TarBzip2,
    TarLz4,
}

impl From<WriteFormatRecord> for fylz_archive::write::WriteFormat {
    fn from(format: WriteFormatRecord) -> Self {
        match format {
            WriteFormatRecord::Zip => fylz_archive::write::WriteFormat::Zip,
            WriteFormatRecord::TarGz => fylz_archive::write::WriteFormat::TarGz,
            WriteFormatRecord::TarXz => fylz_archive::write::WriteFormat::TarXz,
            WriteFormatRecord::TarZstd => fylz_archive::write::WriteFormat::TarZstd,
            WriteFormatRecord::TarBzip2 => fylz_archive::write::WriteFormat::TarBzip2,
            WriteFormatRecord::TarLz4 => fylz_archive::write::WriteFormat::TarLz4,
        }
    }
}

/// `fylz_archive::write::FormatOptions` over uniffi (design section 2.5's own naming).
#[derive(uniffi::Record, Debug, Clone, Copy, PartialEq, Eq)]
pub struct WriteOptionsRecord {
    pub format: WriteFormatRecord,
    pub level: u32,
}

/// `fylz_archive::write::WriteReport` over uniffi: what one `archive_write_frames` call actually
/// wrote, for the drain's own `Σ bytesOut` integrity check (design section 2.3 step 6) and the
/// journal's progress arithmetic.
#[derive(uniffi::Record, Debug, Clone, Copy, PartialEq, Eq)]
pub struct ArchiveWriteReportRecord {
    pub entries: u32,
    pub bytes_in: u64,
    pub bytes_out: u64,
}

/// One create pass (design section 2.3 steps 4-5): parses `FZW1` frames from `in_fd` (the
/// feeder's own pipe, caller-owned and never closed here) and streams the resulting archive into
/// `out_fd` (the drain's pipe, likewise caller-owned), pinning the calling thread's locale to
/// `C.UTF-8` for the call's whole duration so a non-ASCII pathname does not fail its header
/// outright (`write.rs`'s own module doc). Every [ArchiveError] variant [write_frames] can return
/// already has a total `From` mapping onto [ArchiveEngineError] (added for M3.4a's extraction
/// pair): `Unsupported` for a locale that will not pin, `Failed`/`Corrupt` for a protocol
/// violation or a fatal libarchive error, `Cancelled` for `ABORT` or a broken sink pipe -- no new
/// error variant is needed here. Synchronous, like every other archive export.
#[uniffi::export]
pub fn archive_write_frames(
    in_fd: i32,
    out_fd: i32,
    options: WriteOptionsRecord,
) -> Result<ArchiveWriteReportRecord, ArchiveEngineError> {
    ignore_sigpipe();
    if in_fd < 0 {
        return Err(ArchiveEngineError::Internal {
            detail: format!("invalid input descriptor {in_fd}"),
        });
    }
    if out_fd < 0 {
        return Err(ArchiveEngineError::Internal {
            detail: format!("invalid output descriptor {out_fd}"),
        });
    }
    let format_options = fylz_archive::write::FormatOptions {
        format: options.format.into(),
        level: options.level,
    };
    let report = fylz_archive::write::write_frames(in_fd, out_fd, &format_options)?;
    Ok(ArchiveWriteReportRecord {
        entries: report.entries,
        bytes_in: report.bytes_in,
        bytes_out: report.bytes_out,
    })
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
            (
                ArchiveError::Failed("ZIP bad CRC: 0x1 should be 0x2".into()),
                ArchiveEngineError::Failed {
                    detail: "ZIP bad CRC: 0x1 should be 0x2".into(),
                },
            ),
            (ArchiveError::Cancelled, ArchiveEngineError::Cancelled),
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
            // M3.4a: a closed reader is a *cancel* for the bulk path -- noticed by the poll during a
            // long header walk (10,001 headers, nothing written yet), and by the EPIPE of the first
            // frame flush when the walk is too short to be polled.
            let (reader, writer) = std::io::pipe().unwrap();
            drop(reader);
            let record = archive_extract_ranges(
                fixture("archives/hostile/many-entries.tar.zst").as_raw_fd(),
                vec![ArchiveOrdinalRangeRecord {
                    first: 10_000,
                    last: 10_000,
                }],
                limits(),
                writer.as_raw_fd(),
            );
            assert_eq!(
                record.outcome,
                ArchiveExtractOutcomeRecord::Cancelled,
                "{record:?}"
            );
            assert_eq!(record.entries_written, 0);
            let (reader, writer) = std::io::pipe().unwrap();
            drop(reader);
            let record = archive_extract_ranges(
                fixture("archives/sample-cd.zip").as_raw_fd(),
                vec![ArchiveOrdinalRangeRecord { first: 0, last: 7 }],
                limits(),
                writer.as_raw_fd(),
            );
            assert_eq!(
                record.outcome,
                ArchiveExtractOutcomeRecord::Cancelled,
                "{record:?}"
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

    // -----------------------------------------------------------------------------------------
    // M3.4a: archive_extract_ranges.
    // -----------------------------------------------------------------------------------------

    /// The frames of a stream, as `(tag, ordinal-or-0)` with the DONE/ABORT payloads kept.
    #[derive(Debug, PartialEq)]
    enum Frame {
        Begin {
            ordinal: u32,
            declared: i64,
            kind: u8,
            path: String,
        },
        Data {
            ordinal: u32,
            len: u32,
        },
        End {
            ordinal: u32,
            bytes: u64,
            warn: u8,
        },
        Fail {
            ordinal: u32,
            kind: u8,
            message: String,
        },
        Done {
            entries: u32,
            bytes: u64,
            failed: u32,
        },
        Abort {
            message: String,
        },
    }

    fn walk(bytes: &[u8]) -> Vec<Frame> {
        assert_eq!(&bytes[..4], b"FZX1", "magic");
        let mut at = 4;
        let mut frames = Vec::new();
        let u32_at = |at: usize| u32::from_le_bytes(bytes[at..at + 4].try_into().unwrap());
        let u64_at = |at: usize| u64::from_le_bytes(bytes[at..at + 8].try_into().unwrap());
        let u16_at = |at: usize| u16::from_le_bytes(bytes[at..at + 2].try_into().unwrap()) as usize;
        while at < bytes.len() {
            let tag = bytes[at];
            at += 1;
            match tag {
                frames::TAG_BEGIN => {
                    let ordinal = u32_at(at);
                    let declared = i64::from_le_bytes(bytes[at + 4..at + 12].try_into().unwrap());
                    let kind = bytes[at + 12];
                    let len = u32_at(at + 13) as usize;
                    let path = String::from_utf8(bytes[at + 17..at + 17 + len].to_vec()).unwrap();
                    at += 17 + len;
                    frames.push(Frame::Begin {
                        ordinal,
                        declared,
                        kind,
                        path,
                    });
                }
                frames::TAG_DATA => {
                    let ordinal = u32_at(at);
                    let len = u32_at(at + 4);
                    at += 8 + len as usize;
                    frames.push(Frame::Data { ordinal, len });
                }
                frames::TAG_END => {
                    let ordinal = u32_at(at);
                    let bytes_ = u64_at(at + 4);
                    let warn = bytes[at + 12];
                    let len = u16_at(at + 13);
                    at += 15 + len;
                    frames.push(Frame::End {
                        ordinal,
                        bytes: bytes_,
                        warn,
                    });
                }
                frames::TAG_FAIL => {
                    let ordinal = u32_at(at);
                    let kind = bytes[at + 4];
                    let len = u16_at(at + 5);
                    let message = String::from_utf8(bytes[at + 7..at + 7 + len].to_vec()).unwrap();
                    at += 7 + len;
                    frames.push(Frame::Fail {
                        ordinal,
                        kind,
                        message,
                    });
                }
                frames::TAG_DONE => {
                    frames.push(Frame::Done {
                        entries: u32_at(at),
                        bytes: u64_at(at + 4),
                        failed: u32_at(at + 12),
                    });
                    at += 16;
                }
                frames::TAG_ABORT => {
                    let len = u16_at(at);
                    let message = String::from_utf8(bytes[at + 2..at + 2 + len].to_vec()).unwrap();
                    at += 2 + len;
                    frames.push(Frame::Abort { message });
                }
                other => panic!("unknown tag {other} at {at}"),
            }
        }
        frames
    }

    fn all_ranges(name: &str) -> Vec<ArchiveOrdinalRangeRecord> {
        let listing = archive_inspect(fixture(name).as_raw_fd(), limits(), 200_000).unwrap();
        let last = listing.rows.iter().map(|r| r.ordinal).max().unwrap_or(0);
        vec![ArchiveOrdinalRangeRecord { first: 0, last }]
    }

    fn extract_to_file(
        name: &str,
        ranges: Vec<ArchiveOrdinalRangeRecord>,
        limits: ArchiveLimitsRecord,
    ) -> (ArchiveExtractRecord, Vec<u8>) {
        let sink_path = scratch(&format!("{}.fzx", name.replace('/', "_")));
        let sink = File::create(&sink_path).unwrap();
        let record =
            archive_extract_ranges(fixture(name).as_raw_fd(), ranges, limits, sink.as_raw_fd());
        assert!(sink.metadata().is_ok(), "the sink is the caller's to close");
        (record, std::fs::read(&sink_path).unwrap())
    }

    #[test]
    fn archive_extract_ranges_streams_every_selected_entry_and_ends_with_done() {
        let (record, bytes) = extract_to_file(
            "archives/tree.zip",
            all_ranges("archives/tree.zip"),
            limits(),
        );
        assert_eq!(
            record.outcome,
            ArchiveExtractOutcomeRecord::Ok,
            "{record:?}"
        );
        assert_eq!(record.message, None);
        assert_eq!(record.stop_ordinal, None);
        // 40 files and 8 directory rows.
        assert_eq!(record.entries_written, 48);
        assert_eq!(record.entries_failed, 0);
        let frames = walk(&bytes);
        let begins = frames
            .iter()
            .filter(|f| matches!(f, Frame::Begin { .. }))
            .count();
        assert_eq!(begins, 48);
        let data_bytes: u64 = frames
            .iter()
            .map(|f| match f {
                Frame::Data { len, .. } => *len as u64,
                _ => 0,
            })
            .sum();
        assert_eq!(data_bytes, record.bytes_written);
        assert_eq!(
            frames.last(),
            Some(&Frame::Done {
                entries: 48,
                bytes: record.bytes_written,
                failed: 0
            })
        );
        // A BEGIN carries the declared size and the kind; `readme.txt` is ordinal 8 (after the
        // eight directory rows) with the generator's size for index 0.
        assert!(frames.contains(&Frame::Begin {
            ordinal: 8,
            declared: 100,
            kind: frames::KIND_FILE,
            path: "readme.txt".into()
        }));
        assert!(frames.contains(&Frame::Begin {
            ordinal: 0,
            declared: 0,
            kind: frames::KIND_DIRECTORY,
            path: "photos/".into()
        }));
    }

    #[test]
    fn archive_extract_ranges_refuses_before_any_frame_when_the_selection_fails_the_size_policy() {
        // 10,001 entries under the default cap of 10,000: refused, nothing written.
        let (record, bytes) = extract_to_file(
            "archives/hostile/many-entries.tar.zst",
            vec![ArchiveOrdinalRangeRecord {
                first: 0,
                last: 20_000,
            }],
            limits(),
        );
        assert_eq!(
            record.outcome,
            ArchiveExtractOutcomeRecord::Refused,
            "{record:?}"
        );
        assert_eq!(
            record.message.as_deref(),
            Some("Archive contains too many entries.")
        );
        assert!(bytes.is_empty(), "a refusal writes no frame");
        // Exactly 10,000 is allowed.
        let (record, _) = extract_to_file(
            "archives/hostile/many-small-10000.tar.zst",
            vec![ArchiveOrdinalRangeRecord {
                first: 0,
                last: 20_000,
            }],
            limits(),
        );
        assert_eq!(
            record.outcome,
            ArchiveExtractOutcomeRecord::Ok,
            "{record:?}"
        );
        assert_eq!(record.entries_written, 10_000);
        // A 3 MiB member in a 3,850-byte archive: the ratio rule, in every mode.
        let (record, bytes) = extract_to_file(
            "archives/sample-entries.zip",
            all_ranges("archives/sample-entries.zip"),
            limits(),
        );
        assert_eq!(
            record.outcome,
            ArchiveExtractOutcomeRecord::Refused,
            "{record:?}"
        );
        assert_eq!(
            record.message.as_deref(),
            Some("Archive contains a suspicious compression ratio.")
        );
        assert!(bytes.is_empty());
    }

    #[test]
    fn archive_extract_ranges_reports_a_crc_failure_as_a_fail_frame_and_finishes_ok() {
        let (record, bytes) = extract_to_file(
            "archives/crc-bad.zip",
            all_ranges("archives/crc-bad.zip"),
            limits(),
        );
        assert_eq!(
            record.outcome,
            ArchiveExtractOutcomeRecord::Ok,
            "{record:?}"
        );
        assert_eq!((record.entries_written, record.entries_failed), (2, 1));
        assert_eq!(record.bytes_written, 13);
        let frames = walk(&bytes);
        assert!(frames.iter().any(|f| matches!(f, Frame::Fail { ordinal: 1, kind, message } if *kind == frames::FAIL_CRC && message.contains("ZIP bad CRC"))), "{frames:?}");
        assert_eq!(
            frames.last(),
            Some(&Frame::Done {
                entries: 2,
                bytes: 13,
                failed: 1
            })
        );
        // 7-Zip's CRC warning becomes the same kind.
        let (record, bytes) = extract_to_file(
            "archives/crc-bad.7z",
            all_ranges("archives/crc-bad.7z"),
            limits(),
        );
        assert_eq!(
            record.outcome,
            ArchiveExtractOutcomeRecord::Ok,
            "{record:?}"
        );
        assert_eq!((record.entries_written, record.entries_failed), (2, 1));
        assert!(walk(&bytes).iter().any(
            |f| matches!(f, Frame::Fail { ordinal: 1, kind, .. } if *kind == frames::FAIL_CRC)
        ));
    }

    #[test]
    fn archive_extract_ranges_aborts_on_a_fatal_error_with_the_blamed_ordinal() {
        let (record, bytes) = extract_to_file(
            "archives/inflate-bad.zip",
            all_ranges("archives/inflate-bad.zip"),
            limits(),
        );
        assert_eq!(
            record.outcome,
            ArchiveExtractOutcomeRecord::Corrupt,
            "{record:?}"
        );
        assert!(record
            .message
            .as_deref()
            .is_some_and(|m| m.contains("decompression failed")));
        assert_eq!(record.stop_ordinal, Some(1));
        assert_eq!(record.entries_written, 1);
        let frames = walk(&bytes);
        assert!(
            matches!(frames.last(), Some(Frame::Abort { message }) if message.contains("decompression failed"))
        );
        assert!(frames
            .iter()
            .any(|f| matches!(f, Frame::Begin { ordinal: 1, .. })));
        // Corrupt before the first entry (a truncated compressed stream): MAGIC ABORT, nothing else.
        let (record, bytes) = extract_to_file(
            "truncated.tar.gz",
            vec![ArchiveOrdinalRangeRecord { first: 0, last: 5 }],
            limits(),
        );
        assert_eq!(
            record.outcome,
            ArchiveExtractOutcomeRecord::Corrupt,
            "{record:?}"
        );
        assert!(
            matches!(walk(&bytes).as_slice(), [Frame::Abort { .. }]),
            "{:?}",
            walk(&bytes)
        );
    }

    #[test]
    fn archive_extract_ranges_answers_bad_descriptors_and_non_archives_without_frames() {
        let sink_path = scratch("bad.fzx");
        let sink = File::create(&sink_path).unwrap();
        let record = archive_extract_ranges(-1, vec![], limits(), sink.as_raw_fd());
        assert_eq!(record.outcome, ArchiveExtractOutcomeRecord::NotSeekable);
        let record = archive_extract_ranges(
            fixture("archives/tree.zip").as_raw_fd(),
            vec![],
            limits(),
            -1,
        );
        assert_eq!(record.outcome, ArchiveExtractOutcomeRecord::Internal);
        let (reader, _writer) = std::io::pipe().unwrap();
        let record = archive_extract_ranges(reader.as_raw_fd(), vec![], limits(), sink.as_raw_fd());
        assert_eq!(
            record.outcome,
            ArchiveExtractOutcomeRecord::NotSeekable,
            "{record:?}"
        );
        let (record, bytes) = extract_to_file(
            "archives/sample-encrypted-header.7z",
            vec![ArchiveOrdinalRangeRecord { first: 0, last: 4 }],
            limits(),
        );
        assert_eq!(
            record.outcome,
            ArchiveExtractOutcomeRecord::Unsupported,
            "{record:?}"
        );
        assert!(bytes.is_empty());
        assert_eq!(
            std::fs::read(&sink_path).unwrap().len(),
            0,
            "nothing reached the sink"
        );
    }

    #[test]
    fn extract_outcomes_map_every_engine_error() {
        let cases = [
            (
                ArchiveError::NotSeekable("x".into()),
                ArchiveExtractOutcomeRecord::NotSeekable,
            ),
            (
                ArchiveError::Unsupported("x".into()),
                ArchiveExtractOutcomeRecord::Unsupported,
            ),
            (
                ArchiveError::Fatal("x".into()),
                ArchiveExtractOutcomeRecord::Corrupt,
            ),
            (
                ArchiveError::LimitExceeded {
                    entry: "e".into(),
                    rule: "total",
                },
                ArchiveExtractOutcomeRecord::LimitExceeded,
            ),
            (
                ArchiveError::Cancelled,
                ArchiveExtractOutcomeRecord::Cancelled,
            ),
            (
                ArchiveError::Failed("x".into()),
                ArchiveExtractOutcomeRecord::Internal,
            ),
            (
                ArchiveError::NonUtf8Path,
                ArchiveExtractOutcomeRecord::Internal,
            ),
            (
                ArchiveError::NotFound {
                    ordinal: 1,
                    path: "p".into(),
                },
                ArchiveExtractOutcomeRecord::Internal,
            ),
        ];
        for (error, expected) in cases {
            let (outcome, message) = extract_outcome(&error);
            assert_eq!(outcome, expected, "{error:?}");
            assert_eq!(message, error.to_string());
        }
        assert_eq!(ArchiveEngineError::Cancelled.to_string(), "cancelled");
        assert_eq!(
            ArchiveEngineError::Failed { detail: "d".into() }.to_string(),
            "d"
        );
    }

    // -----------------------------------------------------------------------------------------
    // M3.5a: archive_write_frames.
    // -----------------------------------------------------------------------------------------

    /// A minimal, valid `FZW1` stream: one ten-byte file named `hello.txt`, built by hand from
    /// `fylz_archive::write`'s own public tag/kind constants -- the same ones the real Kotlin
    /// feeder's frame writer would use, so this test exercises the exact wire format
    /// `archive_write_frames` parses, not a paraphrase of it.
    fn hello_frames() -> Vec<u8> {
        let mut bytes = fylz_archive::write::MAGIC.to_vec();
        bytes.push(fylz_archive::write::TAG_ENTRY);
        bytes.extend_from_slice(&0u32.to_le_bytes());
        bytes.push(fylz_archive::write::KIND_FILE);
        bytes.extend_from_slice(&11i64.to_le_bytes());
        bytes.extend_from_slice(&0i64.to_le_bytes());
        bytes.extend_from_slice(&0o644u32.to_le_bytes());
        let name = b"hello.txt";
        bytes.extend_from_slice(&(name.len() as u32).to_le_bytes());
        bytes.extend_from_slice(name);
        bytes.push(fylz_archive::write::TAG_DATA);
        bytes.extend_from_slice(&0u32.to_le_bytes());
        bytes.extend_from_slice(&11u32.to_le_bytes());
        bytes.extend_from_slice(b"hello world");
        bytes.push(fylz_archive::write::TAG_END);
        bytes.extend_from_slice(&0u32.to_le_bytes());
        bytes.extend_from_slice(&11u64.to_le_bytes());
        bytes.push(fylz_archive::write::TAG_FINISH);
        bytes
    }

    #[test]
    fn archive_write_frames_writes_a_real_archive_and_reports_its_own_counts() {
        let in_path = scratch("write-in.fzw");
        std::fs::write(&in_path, hello_frames()).unwrap();
        let out_path = scratch("write-out.zip");
        let in_file = File::open(&in_path).unwrap();
        let out_file = File::create(&out_path).unwrap();
        let report = archive_write_frames(
            in_file.as_raw_fd(),
            out_file.as_raw_fd(),
            WriteOptionsRecord {
                format: WriteFormatRecord::Zip,
                level: 6,
            },
        )
        .unwrap();
        assert_eq!(report.entries, 1);
        assert_eq!(report.bytes_in, 11);
        assert!(report.bytes_out > 0);
        drop(out_file);
        let readback = File::open(&out_path).unwrap();
        let inspection = archive_inspect(readback.as_raw_fd(), limits(), 10).unwrap();
        assert_eq!(inspection.entry_count, 1);
        assert_eq!(inspection.rows[0].path, "hello.txt");
        assert_eq!(inspection.rows[0].uncompressed, Some(11));
    }

    #[test]
    fn archive_write_frames_rejects_bad_descriptors_before_touching_the_engine() {
        let options = WriteOptionsRecord {
            format: WriteFormatRecord::Zip,
            level: 6,
        };
        assert!(matches!(
            archive_write_frames(-1, 0, options),
            Err(ArchiveEngineError::Internal { .. })
        ));
        assert!(matches!(
            archive_write_frames(0, -1, options),
            Err(ArchiveEngineError::Internal { .. })
        ));
    }

    #[test]
    fn archive_write_frames_maps_a_protocol_violation_to_a_failure_never_a_panic() {
        let in_path = scratch("write-bad.fzw");
        // No magic at all -- the simplest protocol violation the engine names.
        std::fs::write(&in_path, b"not a frame stream").unwrap();
        let in_file = File::open(&in_path).unwrap();
        let out_file = File::create(scratch("write-bad-out.zip")).unwrap();
        let result = archive_write_frames(
            in_file.as_raw_fd(),
            out_file.as_raw_fd(),
            WriteOptionsRecord {
                format: WriteFormatRecord::Zip,
                level: 6,
            },
        );
        assert!(
            matches!(result, Err(ArchiveEngineError::Failed { .. })),
            "{result:?}"
        );
    }

    #[test]
    fn write_format_record_conversions_are_total() {
        for format in [
            WriteFormatRecord::Zip,
            WriteFormatRecord::TarGz,
            WriteFormatRecord::TarXz,
            WriteFormatRecord::TarZstd,
            WriteFormatRecord::TarBzip2,
            WriteFormatRecord::TarLz4,
        ] {
            let _: fylz_archive::write::WriteFormat = format.into();
        }
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
