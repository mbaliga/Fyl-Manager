//! Golden tests ported one-to-one from `ChecksumVerificationTest.kt`, over real temp files
//! instead of the Kotlin test's `FylzFilesDocumentsProvider` fixture.

use std::fs;

use sha2::Digest;
use sha2::Sha256;
use tempfile::tempdir;

use super::*;

/// Computed independently of [`sha256_hex`] -- via the same crate primitive, but without going
/// through this module's own streaming loop -- so these tests pin the reading and
/// hex-formatting behavior, not just restate the implementation.
fn expected_sha256_hex(bytes: &[u8]) -> String {
    hex_encode(&Sha256::digest(bytes))
}

#[test]
fn sha256_hex_matches_an_independently_computed_digest_of_the_same_bytes() {
    let dir = tempdir().unwrap();
    let path = dir.path().join("source.bin");
    let bytes = vec![7u8; 50_000];
    fs::write(&path, &bytes).unwrap();

    let hash = sha256_hex(&path).unwrap();

    assert_eq!(expected_sha256_hex(&bytes), hash);
}

#[test]
fn sha256_hex_handles_an_empty_file() {
    let dir = tempdir().unwrap();
    let path = dir.path().join("empty.bin");
    fs::write(&path, []).unwrap();

    let hash = sha256_hex(&path).unwrap();

    assert_eq!(expected_sha256_hex(&[]), hash);
}

#[test]
fn verify_checksum_returns_the_matching_hash_for_byte_identical_files() {
    let dir = tempdir().unwrap();
    let source = dir.path().join("source.bin");
    let copy = dir.path().join("copy.bin");
    let bytes = vec![9u8; 10_000];
    fs::write(&source, &bytes).unwrap();
    fs::copy(&source, &copy).unwrap();

    let hash = verify_checksum(&source, &copy).unwrap();

    assert_eq!(expected_sha256_hex(&bytes), hash);
}

#[test]
fn verify_checksum_errs_when_the_destination_differs_from_the_source_and_names_the_source_in_the_message(
) {
    let dir = tempdir().unwrap();
    let source = dir.path().join("source.bin");
    let corrupt = dir.path().join("corrupt.bin");
    let mut bytes = vec![9u8; 10_000];
    fs::write(&source, &bytes).unwrap();
    bytes[0] = bytes[0].wrapping_add(1);
    fs::write(&corrupt, &bytes).unwrap();

    let error = verify_checksum(&source, &corrupt).unwrap_err();

    assert!(matches!(error, ChecksumError::Mismatch { .. }));
    assert!(error.to_string().contains("source.bin"));
}
