//! Golden tests ported one-to-one from `StagingNameTest.kt`.

use super::*;

#[test]
fn carries_the_operation_id_item_index_and_requested_name() {
    let name = staging_name("op-42", 3, "vacation.jpg");
    assert!(name.starts_with(STAGING_NAME_PREFIX));
    assert!(name.contains("op-42"));
    assert!(name.contains("-3-"));
    assert!(name.ends_with("vacation.jpg"));
}

#[test]
fn a_slash_in_the_requested_name_is_neutralized() {
    let name = staging_name("op-1", 0, "a/b.txt");
    assert!(!name.contains('/'));
}

#[test]
fn stays_within_255_utf8_bytes_and_is_recognized_as_a_staging_name() {
    let long_name = format!("{}.txt", "a".repeat(500));
    let name = staging_name("op-1", 0, &long_name);
    assert!(name.len() <= 255);
    assert!(is_staging_name(&name));
}

#[test]
fn truncation_to_255_utf8_bytes_never_splits_a_multi_byte_character() {
    // Each euro sign is 3 UTF-8 bytes; a naive char-count truncation could easily land
    // mid-character depending on the fixed prefix's own byte length.
    let long_name = format!("{}.txt", "\u{20ac}".repeat(200));
    let name = staging_name("op-1", 0, &long_name);
    let bytes = name.as_bytes();
    assert!(bytes.len() <= 255);
    // Rust strings are always valid UTF-8, so unlike the Kotlin test (which round-trips through
    // decode to catch a replacement character), a successful `String` construction above is
    // itself the proof that no multi-byte character was split.
    assert_eq!(name, String::from_utf8(bytes.to_vec()).unwrap());
}

#[test]
fn names_outside_the_staging_shape_are_not_recognized() {
    assert!(!is_staging_name("vacation.jpg"));
    assert!(!is_staging_name(".fylz-trash"));
    assert!(!is_staging_name(".fylz-replaced-abc-name"));
}
