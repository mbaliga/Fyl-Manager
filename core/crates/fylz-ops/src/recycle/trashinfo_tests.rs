//! New coverage: this format has no Kotlin counterpart (see this module's own doc comment), so
//! these tests are not a port of anything -- they pin the encoding scheme and error handling
//! against the freedesktop.org Trash specification's own text directly.

use super::*;

#[test]
fn format_trashinfo_matches_the_specs_own_shape() {
    let text = format_trashinfo("/home/user/Documents/report.txt", 1_700_000_000);

    assert!(text.starts_with("[Trash Info]\n"));
    assert!(text.contains("Path=/home/user/Documents/report.txt\n"));
    assert!(text.contains("DeletionDate="));
}

#[test]
fn parse_trashinfo_reads_back_what_format_trashinfo_wrote() {
    let text = format_trashinfo("/home/user/Pictures/vacation photo.jpg", 1_700_000_000);

    let fields = parse_trashinfo(&text).unwrap();

    assert_eq!("/home/user/Pictures/vacation photo.jpg", fields.path);
    assert_eq!(Some(1_700_000_000), fields.deletion_date_unix);
}

#[test]
fn a_space_is_percent_encoded_but_a_slash_is_not() {
    let text = format_trashinfo("/mnt/usb/my music/track 01.mp3", 0);

    assert!(text.contains("Path=/mnt/usb/my%20music/track%2001.mp3\n"));
}

#[test]
fn a_literal_percent_sign_in_the_original_name_is_itself_escaped() {
    let text = format_trashinfo("/tmp/100% done.txt", 0);

    assert!(text.contains("Path=/tmp/100%25%20done.txt\n"));
    assert_eq!("/tmp/100% done.txt", parse_trashinfo(&text).unwrap().path);
}

#[test]
fn non_ascii_bytes_round_trip_through_percent_encoding() {
    let original = "/home/user/Documents/\u{00e9}t\u{00e9} 2026.txt";
    let text = format_trashinfo(original, 0);

    assert_eq!(original, parse_trashinfo(&text).unwrap().path);
}

#[test]
fn missing_header_is_refused() {
    let error = parse_trashinfo("Path=/tmp/a.txt\nDeletionDate=2026-01-01T00:00:00\n").unwrap_err();
    assert_eq!(TrashInfoError::MissingHeader, error);
}

#[test]
fn missing_path_is_refused() {
    let error = parse_trashinfo("[Trash Info]\nDeletionDate=2026-01-01T00:00:00\n").unwrap_err();
    assert_eq!(TrashInfoError::MissingPath, error);
}

#[test]
fn a_truncated_percent_escape_is_refused() {
    let error = parse_trashinfo("[Trash Info]\nPath=/tmp/bad%2\n").unwrap_err();
    assert_eq!(TrashInfoError::InvalidPercentEncoding, error);
}

#[test]
fn an_unparseable_deletion_date_is_tolerated_as_unknown_rather_than_refusing_the_record() {
    let fields =
        parse_trashinfo("[Trash Info]\nPath=/tmp/a.txt\nDeletionDate=not-a-date\n").unwrap();
    assert_eq!("/tmp/a.txt", fields.path);
    assert_eq!(None, fields.deletion_date_unix);
}

#[test]
fn deletion_date_round_trips_through_local_time() {
    for unix_seconds in [0i64, 1_000, 1_700_000_000, 1_800_000_000] {
        let text = format_trashinfo("/tmp/a.txt", unix_seconds);
        let fields = parse_trashinfo(&text).unwrap();
        assert_eq!(
            Some(unix_seconds),
            fields.deletion_date_unix,
            "round trip failed for {unix_seconds}"
        );
    }
}
