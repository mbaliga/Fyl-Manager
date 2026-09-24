//! Uniffi bindings exposed to Kotlin.
//!
//! `fylz_version` and `sniff` are the whole M2.2 pipeline proof (Rust -> uniffi -> generated
//! Kotlin -> Gradle) end to end; `sniff` itself is `fylz-sniff`'s content-detection logic (M2.5)
//! wired to a real, caller-owned file descriptor.

use std::io::{Read, Seek, SeekFrom};
use std::mem::ManuallyDrop;
use std::os::fd::{FromRawFd, RawFd};

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

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

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
