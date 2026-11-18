//! uniffi Swift bindings over the Fylz core, for the iOS "Fylz for Files" document browser.
//!
//! This is **not** the Android file manager's FFI. `fylz-ffi-android` is a descriptor-and-pipe
//! surface built for a separate decoder *process* behind Binder; iOS has no subprocess, so this
//! crate runs the same engine (`fylz-archive`, `fylz-sniff`) in-process and exposes a deliberately
//! small, path-based surface a SwiftUI app can call from a security-scoped URL:
//!
//! - [fylz_version] -- the workspace version, for the About screen.
//! - [sniff_path] -- content-based format identification (never the file extension).
//! - [archive_inspect] -- one header pass, the extraction policy's verdict and the first rows.
//! - [archive_extract_all] -- policy-gated extraction into a directory the app chose, never
//!   overwriting, never leaving a half-written tree behind (see [extract]).
//!
//! Isolation caveat (docs/PORTING_PLAN.md FZ-IOS5(b)): on Android the decoder runs in its own
//! process under a memory cap. Here it runs in the app process, so the limits in
//! [ArchiveLimits::defaults] are the only containment, and the app must call these off the main
//! thread. Whether that is acceptable for hostile archives is an owner decision recorded in the
//! design doc, not something this crate settles.

mod extract;

use fylz_archive::policy::EntryKind;
use fylz_archive::ArchiveError;
use fylz_archive::EntryMetadata;
use fylz_archive::ExtractLimits;
use fylz_archive::Limits;
use std::fs::File;
use std::io::Read;
use std::io::Seek;
use std::io::SeekFrom;
use std::os::fd::AsRawFd;
use std::path::Path;

uniffi::setup_scaffolding!();

/// The `fylz-core` workspace version.
#[uniffi::export]
pub fn fylz_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

/// Everything this crate can report as a failure. Swift sees it as a throwing `FylzError`.
#[derive(uniffi::Error, Debug, Clone, PartialEq, Eq)]
pub enum FylzError {
    /// Not an archive any compiled-in format recognises (or an encrypted 7-Zip header).
    Unsupported { detail: String },
    /// The archive is damaged.
    Corrupt { detail: String },
    /// The extraction policy refused the archive; `reason` is the policy's own text.
    Refused { reason: String },
    /// A runtime limit stopped the operation.
    LimitExceeded { entry: String, rule: String },
    /// An entry path could not be made safe, or would pass through a symlink.
    UnsafePath { path: String },
    /// The destination already holds something at that path. Nothing is overwritten.
    DestinationExists { path: String },
    /// A filesystem error from the app's own side (permissions, disk full).
    Io { detail: String },
    /// One entry's data failed its own check while the archive stayed readable.
    Failed { detail: String },
    /// A bug in this crate, not something to blame on the archive.
    Internal { detail: String },
}

impl std::fmt::Display for FylzError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            FylzError::Unsupported { detail } => write!(f, "unsupported archive: {detail}"),
            FylzError::Corrupt { detail }
            | FylzError::Failed { detail }
            | FylzError::Io { detail }
            | FylzError::Internal { detail } => write!(f, "{detail}"),
            FylzError::Refused { reason } => write!(f, "refused: {reason}"),
            FylzError::LimitExceeded { entry, rule } => {
                write!(f, "limit exceeded ({rule}) at entry {entry:?}")
            }
            FylzError::UnsafePath { path } => write!(f, "unsafe path {path:?}"),
            FylzError::DestinationExists { path } => write!(f, "already exists: {path}"),
        }
    }
}

impl std::error::Error for FylzError {}

impl From<std::io::Error> for FylzError {
    fn from(error: std::io::Error) -> Self {
        FylzError::Io {
            detail: error.to_string(),
        }
    }
}

impl From<ArchiveError> for FylzError {
    fn from(error: ArchiveError) -> Self {
        match error {
            ArchiveError::NotSeekable(detail) | ArchiveError::Fatal(detail) => {
                FylzError::Corrupt { detail }
            }
            ArchiveError::Unsupported(detail) => FylzError::Unsupported { detail },
            ArchiveError::LimitExceeded { entry, rule } => FylzError::LimitExceeded {
                entry,
                rule: rule.to_string(),
            },
            ArchiveError::NonUtf8Path => FylzError::Internal {
                detail: error.to_string(),
            },
            ArchiveError::NotFound { .. } | ArchiveError::Failed(_) => FylzError::Failed {
                detail: error.to_string(),
            },
            ArchiveError::Cancelled => FylzError::Internal {
                detail: "cancelled".to_string(),
            },
        }
    }
}

/// `fylz_archive::policy::Limits` over uniffi (`u32` counts; uniffi has no `usize`).
#[derive(uniffi::Record, Debug, Clone, PartialEq)]
pub struct ArchiveLimits {
    pub max_entries: u32,
    pub max_archive_bytes: u64,
    pub max_file_bytes: u64,
    pub max_total_uncompressed_bytes: u64,
    pub max_compression_ratio: f64,
    pub max_path_depth: u32,
    pub max_name_length: u32,
    pub max_listing_entries: u32,
}

impl ArchiveLimits {
    /// The engine's own defaults, so Swift never re-types them.
    fn from_engine(limits: &Limits) -> Self {
        ArchiveLimits {
            max_entries: u32::try_from(limits.max_entries).unwrap_or(u32::MAX),
            max_archive_bytes: limits.max_archive_bytes,
            max_file_bytes: limits.max_file_bytes,
            max_total_uncompressed_bytes: limits.max_total_uncompressed_bytes,
            max_compression_ratio: limits.max_compression_ratio,
            max_path_depth: u32::try_from(limits.max_path_depth).unwrap_or(u32::MAX),
            max_name_length: u32::try_from(limits.max_name_length).unwrap_or(u32::MAX),
            max_listing_entries: u32::try_from(limits.max_listing_entries).unwrap_or(u32::MAX),
        }
    }
}

impl From<ArchiveLimits> for Limits {
    fn from(l: ArchiveLimits) -> Self {
        Limits {
            max_entries: l.max_entries as usize,
            max_archive_bytes: l.max_archive_bytes,
            max_file_bytes: l.max_file_bytes,
            max_total_uncompressed_bytes: l.max_total_uncompressed_bytes,
            max_compression_ratio: l.max_compression_ratio,
            max_path_depth: l.max_path_depth as usize,
            max_name_length: l.max_name_length as usize,
            max_listing_entries: l.max_listing_entries as usize,
        }
    }
}

/// The limits an iOS caller should start from: the engine's defaults.
#[uniffi::export]
pub fn archive_default_limits() -> ArchiveLimits {
    ArchiveLimits::from_engine(&Limits::default())
}

#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum ArchiveEntryKind {
    File,
    Directory,
    Symlink,
    Hardlink,
    Other,
}

impl From<EntryKind> for ArchiveEntryKind {
    fn from(kind: EntryKind) -> Self {
        match kind {
            EntryKind::File => ArchiveEntryKind::File,
            EntryKind::Directory => ArchiveEntryKind::Directory,
            EntryKind::Symlink => ArchiveEntryKind::Symlink,
            EntryKind::Hardlink => ArchiveEntryKind::Hardlink,
            EntryKind::Other => ArchiveEntryKind::Other,
        }
    }
}

/// One listing row.
#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct ArchiveRow {
    pub ordinal: u32,
    pub path: String,
    /// The raw name was not UTF-8; `path` is a lossy rendering and the UI should say so.
    pub name_lossy: bool,
    pub kind: ArchiveEntryKind,
    pub link_target: Option<String>,
    pub uncompressed: Option<u64>,
    pub mtime: Option<i64>,
    pub encrypted: bool,
}

impl From<&EntryMetadata> for ArchiveRow {
    fn from(e: &EntryMetadata) -> Self {
        ArchiveRow {
            ordinal: e.ordinal,
            path: e.path.clone(),
            name_lossy: e.name_lossy,
            kind: e.kind.into(),
            link_target: e.link_target.clone(),
            uncompressed: e.uncompressed,
            mtime: e.mtime,
            encrypted: e.encrypted_data || e.encrypted_metadata,
        }
    }
}

/// What [archive_inspect] returns.
#[derive(uniffi::Record, Debug, Clone, PartialEq)]
pub struct ArchiveSummary {
    pub archive_bytes: u64,
    pub format_name: Option<String>,
    pub filters: Vec<String>,
    pub entry_count: u32,
    pub file_count: u32,
    pub directory_count: u32,
    pub link_count: u32,
    pub total_uncompressed: Option<u64>,
    pub has_encrypted_entries: bool,
    pub has_lossy_names: bool,
    /// The extraction policy's verdict. When `false`, [archive_extract_all] will refuse.
    pub extraction_allowed: bool,
    pub refusal_reason: Option<String>,
    pub rows: Vec<ArchiveRow>,
    pub rows_truncated: bool,
}

/// What [archive_extract_all] wrote.
#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct ExtractSummary {
    pub entries_written: u64,
    pub bytes_written: u64,
    /// Symlinks and hardlinks, which are never materialised.
    pub skipped_links: u64,
}

/// Identifies a file's format from its content. `"unknown"` for anything unrecognised or
/// unreadable.
#[uniffi::export]
pub fn sniff_path(path: String) -> String {
    match File::open(&path) {
        Ok(mut file) => describe(sniff_reader(&mut file).ok().flatten()),
        Err(_) => "unknown".to_string(),
    }
}

fn describe(format: Option<fylz_sniff::Format>) -> String {
    match format {
        Some(format) => format.mime().unwrap_or_else(|| format.label()).to_string(),
        None => "unknown".to_string(),
    }
}

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

/// Header and tail window, exactly as `fylz-ffi-android::format_from_reader` reads them.
fn sniff_reader<R: Read + Seek>(reader: &mut R) -> std::io::Result<Option<fylz_sniff::Format>> {
    let mut header = vec![0u8; fylz_sniff::HEADER_LEN];
    let read = read_up_to(reader, &mut header)?;
    header.truncate(read);
    let len = reader.seek(SeekFrom::End(0))?;
    let tail = if len == 0 {
        None
    } else if len <= fylz_sniff::TAIL_LEN as u64 {
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

/// One header pass over the archive at `path`, the extraction policy over what it found, and the
/// first `max_rows` entries.
#[uniffi::export]
pub fn archive_inspect(
    path: String,
    limits: ArchiveLimits,
    max_rows: u32,
) -> Result<ArchiveSummary, FylzError> {
    let file = File::open(&path)?;
    let limits = Limits::from(limits);
    let (inspection, decision) = fylz_archive::inspect_with_policy(file.as_raw_fd(), &limits)?;
    let entries = &inspection.entries;
    let count = |p: fn(&EntryMetadata) -> bool| entries.iter().filter(|e| p(e)).count() as u32;
    let total_uncompressed = entries
        .iter()
        .filter(|e| e.kind != EntryKind::Directory)
        .try_fold(0u64, |sum, e| {
            e.uncompressed.and_then(|n| sum.checked_add(n))
        });
    let max_rows = max_rows as usize;
    Ok(ArchiveSummary {
        archive_bytes: inspection.archive_bytes,
        format_name: inspection.format_name.clone(),
        filters: inspection.filters.clone(),
        entry_count: entries.len() as u32,
        file_count: count(|e| e.kind == EntryKind::File),
        directory_count: count(|e| e.kind == EntryKind::Directory),
        link_count: count(|e| matches!(e.kind, EntryKind::Symlink | EntryKind::Hardlink)),
        total_uncompressed,
        has_encrypted_entries: inspection.has_encrypted_entries.unwrap_or(false)
            || entries.iter().any(|e| e.encrypted_data),
        has_lossy_names: entries.iter().any(|e| e.name_lossy),
        extraction_allowed: decision.allowed,
        refusal_reason: decision.reason.map(str::to_string),
        rows: entries
            .iter()
            .take(max_rows)
            .map(ArchiveRow::from)
            .collect(),
        rows_truncated: entries.len() > max_rows,
    })
}

/// Extracts the whole archive at `archive_path` into the existing directory `dest_dir`.
///
/// The policy runs first and a refusal is [FylzError::Refused] before any byte is written. No
/// existing file is overwritten ([FylzError::DestinationExists]); on any error every file and
/// directory this call created is removed again.
#[uniffi::export]
pub fn archive_extract_all(
    archive_path: String,
    dest_dir: String,
    limits: ArchiveLimits,
) -> Result<ExtractSummary, FylzError> {
    let file = File::open(&archive_path)?;
    let limits = Limits::from(limits);
    let (inspection, decision) = fylz_archive::inspect_with_policy(file.as_raw_fd(), &limits)?;
    if !decision.allowed {
        return Err(FylzError::Refused {
            reason: decision.reason.unwrap_or("refused by policy").to_string(),
        });
    }
    if let Some(reason) = &inspection.structural_refusal {
        return Err(FylzError::Refused {
            reason: reason.clone(),
        });
    }
    let extract_limits = ExtractLimits::from(&limits);
    let done = extract::extract_all(&file, Path::new(&dest_dir), &extract_limits)?;
    Ok(ExtractSummary {
        entries_written: done.entries_written,
        bytes_written: done.bytes_written,
        skipped_links: done.skipped_links,
    })
}

#[cfg(test)]
mod tests;
