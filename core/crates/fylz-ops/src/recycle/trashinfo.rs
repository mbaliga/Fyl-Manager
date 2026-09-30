//! The `.trashinfo` file format from the freedesktop.org Trash specification (version 1.0,
//! section "2. The trash directories"): an INI-shaped document with one `[Trash Info]` section
//! and two keys, `Path` (the item's original location, percent-encoded per RFC 2396 section 2)
//! and `DeletionDate` (local time, `YYYY-MM-DDThh:mm:ss`, no fractional seconds, no time zone
//! offset). This module has no Kotlin counterpart to port from at all -- see
//! `super::trash`'s own doc comment for why.
//!
//! `Path`'s percent-encoding keeps `/` (and every unreserved RFC 2396 character: ASCII
//! alphanumerics, `-`, `_`, `.`, `~`) literal and escapes everything else, including space,
//! `%` itself, and every non-ASCII UTF-8 byte individually -- the same scheme GNOME's and
//! KDE's own trash implementations use, chosen for maximum interoperability with a real trash
//! can another desktop environment might also be reading from the same volume.
//!
//! `DeletionDate` round-trips through the OS's own time zone database (`libc::localtime_r` to
//! format, `libc::mktime` to parse) rather than a hand-rolled calendar calculation, so a
//! daylight-saving transition is handled exactly as correctly as every other program on the
//! same machine handles it.

use std::fmt;
use std::mem::MaybeUninit;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TrashInfoError {
    /// The `[Trash Info]` header line is missing.
    MissingHeader,
    /// The header is present, but no `Path=` line was found.
    MissingPath,
    /// A `Path=` value contains a `%` not followed by two valid hex digits, or the decoded
    /// bytes are not valid UTF-8.
    InvalidPercentEncoding,
}

impl fmt::Display for TrashInfoError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            TrashInfoError::MissingHeader => write!(f, "missing [Trash Info] header"),
            TrashInfoError::MissingPath => write!(f, "missing Path key"),
            TrashInfoError::InvalidPercentEncoding => write!(f, "invalid percent-encoding in Path"),
        }
    }
}

impl std::error::Error for TrashInfoError {}

/// The two keys a `.trashinfo` file's `[Trash Info]` section carries, decoded.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TrashInfoFields {
    /// The original location, percent-decoded back to a plain string (absolute or relative to
    /// the trash directory's own `topdir`, whichever the writer chose -- this module has no
    /// opinion on which; see [`super::trash`]).
    pub path: String,
    /// Seconds since the Unix epoch, or `None` if `DeletionDate` was absent or unparseable --
    /// a reader tolerates that rather than refusing the whole record, since `Path` is the only
    /// key restoring an item actually needs.
    pub deletion_date_unix: Option<i64>,
}

pub fn format_trashinfo(path: &str, deleted_at_unix_seconds: i64) -> String {
    format!(
        "[Trash Info]\nPath={}\nDeletionDate={}\n",
        percent_encode_path(path),
        format_deletion_date(deleted_at_unix_seconds),
    )
}

pub fn parse_trashinfo(text: &str) -> Result<TrashInfoFields, TrashInfoError> {
    let mut header_seen = false;
    let mut path = None;
    let mut deletion_date_unix = None;

    for raw_line in text.lines() {
        let line = raw_line.trim();
        if line.is_empty() {
            continue;
        }
        if line == "[Trash Info]" {
            header_seen = true;
            continue;
        }
        if let Some(value) = line.strip_prefix("Path=") {
            path = Some(percent_decode(value)?);
        } else if let Some(value) = line.strip_prefix("DeletionDate=") {
            deletion_date_unix = parse_deletion_date(value);
        }
    }

    if !header_seen {
        return Err(TrashInfoError::MissingHeader);
    }
    let path = path.ok_or(TrashInfoError::MissingPath)?;
    Ok(TrashInfoFields {
        path,
        deletion_date_unix,
    })
}

fn percent_encode_path(path: &str) -> String {
    let mut out = String::with_capacity(path.len());
    for byte in path.as_bytes() {
        if byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.' | b'~' | b'/') {
            out.push(*byte as char);
        } else {
            out.push_str(&format!("%{byte:02X}"));
        }
    }
    out
}

fn percent_decode(encoded: &str) -> Result<String, TrashInfoError> {
    let bytes = encoded.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' {
            if i + 3 > bytes.len() {
                return Err(TrashInfoError::InvalidPercentEncoding);
            }
            let high = hex_value(bytes[i + 1]).ok_or(TrashInfoError::InvalidPercentEncoding)?;
            let low = hex_value(bytes[i + 2]).ok_or(TrashInfoError::InvalidPercentEncoding)?;
            out.push((high << 4) | low);
            i += 3;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    String::from_utf8(out).map_err(|_| TrashInfoError::InvalidPercentEncoding)
}

fn hex_value(byte: u8) -> Option<u8> {
    match byte {
        b'0'..=b'9' => Some(byte - b'0'),
        b'a'..=b'f' => Some(byte - b'a' + 10),
        b'A'..=b'F' => Some(byte - b'A' + 10),
        _ => None,
    }
}

/// `unix_seconds` formatted as local time, `YYYY-MM-DDThh:mm:ss`, via `libc::localtime_r`.
fn format_deletion_date(unix_seconds: i64) -> String {
    let time = unix_seconds as libc::time_t;
    // SAFETY: `localtime_r` only reads `time` and writes into `tm`, a plain-old-data struct we
    // own for the length of the call; zero-initializing it before the call is always valid.
    let tm = unsafe {
        let mut tm = MaybeUninit::<libc::tm>::zeroed();
        libc::localtime_r(&time, tm.as_mut_ptr());
        tm.assume_init()
    };
    format!(
        "{:04}-{:02}-{:02}T{:02}:{:02}:{:02}",
        tm.tm_year + 1900,
        tm.tm_mon + 1,
        tm.tm_mday,
        tm.tm_hour,
        tm.tm_min,
        tm.tm_sec,
    )
}

/// The inverse of [`format_deletion_date`]: parses a strict `YYYY-MM-DDThh:mm:ss` value
/// (rejecting anything a shorter or malformed string, including one with a fractional-second
/// or time-zone suffix a non-conforming writer might have added) and resolves it back to a
/// Unix timestamp as local time via `libc::mktime`. Returns `None` rather than an error: an
/// unparseable `DeletionDate` should not stop a caller from restoring or purging the item.
fn parse_deletion_date(value: &str) -> Option<i64> {
    let bytes = value.as_bytes();
    if bytes.len() != 19
        || bytes[4] != b'-'
        || bytes[7] != b'-'
        || bytes[10] != b'T'
        || bytes[13] != b':'
        || bytes[16] != b':'
    {
        return None;
    }
    let year: i32 = value.get(0..4)?.parse().ok()?;
    let month: i32 = value.get(5..7)?.parse().ok()?;
    let day: i32 = value.get(8..10)?.parse().ok()?;
    let hour: i32 = value.get(11..13)?.parse().ok()?;
    let minute: i32 = value.get(14..16)?.parse().ok()?;
    let second: i32 = value.get(17..19)?.parse().ok()?;

    let mut tm: libc::tm = unsafe { MaybeUninit::zeroed().assume_init() };
    tm.tm_year = year - 1900;
    tm.tm_mon = month - 1;
    tm.tm_mday = day;
    tm.tm_hour = hour;
    tm.tm_min = minute;
    tm.tm_sec = second;
    tm.tm_isdst = -1; // let the C library work out whether daylight saving applies.

    // SAFETY: `tm` above is fully initialized (every field `mktime` reads is set explicitly).
    let converted = unsafe { libc::mktime(&mut tm) };
    if converted == -1 {
        None
    } else {
        Some(converted as i64)
    }
}

#[cfg(test)]
#[path = "trashinfo_tests.rs"]
mod trashinfo_tests;
