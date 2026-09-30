//! Ported from `app/src/main/java/io/github/mbaliga/fylz/operations/ChecksumVerification.kt`:
//! streamed SHA-256 verification that a transfer's destination is byte-for-byte identical to
//! its source, not just the same length -- the existing size check every copy already gets.
//! A size match does not rule out silent corruption on a flaky SD card, a USB drive, or a lossy
//! network path. `ChecksumVerificationTest.kt` is this module's parity oracle
//! (`checksum_tests.rs`).
//!
//! **Distinct from `fylz-verify`'s planned scope.** `fylz-verify`'s own doc comment describes
//! "hashing, checksum files, and OpenPGP signature verification" landing in M4.6: that crate
//! verifies a file a user already has -- typically just downloaded -- against a published
//! checksum file or signature the *publisher* supplied, to confirm the download was not
//! tampered with or corrupted in transit from a third party. This module verifies something
//! narrower and purely internal: that a copy *this app itself just wrote* matches the source
//! *this app itself just read*, during one copy or move operation, with no publisher, checksum
//! file, or signature involved on either side. The two never call into each other and share no
//! code; `fylz-verify` staying a stub does not block this module, and this module implementing
//! its narrow case first is not a claim that M4 is now done.

use std::fmt;
use std::fs::File;
use std::io;
use std::io::Read;
use std::path::Path;

use sha2::Digest;
use sha2::Sha256;

const CHECKSUM_BUFFER_BYTES: usize = 256 * 1024;

/// `sha256Hex`: `path`'s SHA-256, streamed so the whole file is never held in memory at once.
pub fn sha256_hex(path: &Path) -> io::Result<String> {
    let mut file = File::open(path)?;
    let mut hasher = Sha256::new();
    let mut buffer = [0u8; CHECKSUM_BUFFER_BYTES];
    loop {
        let read = file.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }
    Ok(hex_encode(&hasher.finalize()))
}

fn hex_encode(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

/// Thrown -- as a distinct, matchable error, the same reason `ChecksumMismatchException` in
/// Kotlin is its own type rather than a bare `IllegalStateException` -- when [`verify_checksum`]
/// cannot complete, or completes but finds the destination does not match the source.
#[derive(Debug)]
pub enum ChecksumError {
    /// Either file could not be opened or read.
    Io(io::Error),
    /// Both files were read fully, but their hashes differ.
    Mismatch { message: String },
}

impl fmt::Display for ChecksumError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ChecksumError::Io(err) => write!(f, "{err}"),
            ChecksumError::Mismatch { message } => write!(f, "{message}"),
        }
    }
}

impl std::error::Error for ChecksumError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            ChecksumError::Io(err) => Some(err),
            ChecksumError::Mismatch { .. } => None,
        }
    }
}

/// `verifyChecksum`: confirms `written` is byte-for-byte identical to `source` by SHA-256.
///
/// Returns the matching hash on success, to store as the item's own recorded checksum.
pub fn verify_checksum(source: &Path, written: &Path) -> Result<String, ChecksumError> {
    let source_hash = sha256_hex(source).map_err(ChecksumError::Io)?;
    let written_hash = sha256_hex(written).map_err(ChecksumError::Io)?;
    if source_hash != written_hash {
        let name = source
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_else(|| source.display().to_string());
        return Err(ChecksumError::Mismatch {
            message: format!(
                "Verification failed for {name}: the copy does not match the original."
            ),
        });
    }
    Ok(written_hash)
}

#[cfg(test)]
#[path = "checksum_tests.rs"]
mod checksum_tests;
