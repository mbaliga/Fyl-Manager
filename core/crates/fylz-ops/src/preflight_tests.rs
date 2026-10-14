//! Golden tests ported one-to-one from `PreflightPolicyTest.kt`: same names (translated to
//! `snake_case`), same fixtures, same assertions.

use super::*;

fn item(name: &str) -> PreflightItem {
    item_full(name, false, Some(1_000))
}

fn item_full(name: &str, is_directory: bool, total_bytes: Option<u64>) -> PreflightItem {
    PreflightItem {
        source: format!("preflight-test/{name}"),
        name: name.to_string(),
        is_directory,
        total_bytes,
    }
}

fn volume(
    filesystem_type: Option<&str>,
    free_bytes: Option<u64>,
    case_insensitive: bool,
) -> VolumeInfo {
    VolumeInfo {
        filesystem_type: filesystem_type.map(String::from),
        free_bytes,
        case_insensitive,
    }
}

fn default_volume() -> VolumeInfo {
    volume(Some("ext4"), Some(u64::MAX / 2), false)
}

// --- illegal characters / trailing space or dot -----------------------------------------

#[test]
fn illegal_characters_are_flagged_on_vfat_and_exfat_but_not_on_ext4() {
    let with_colon = item("bad:name.txt");

    let on_vfat = PreflightPolicy::evaluate(
        std::slice::from_ref(&with_colon),
        &volume(Some("vfat"), Some(u64::MAX / 2), false),
    );
    let on_exfat = PreflightPolicy::evaluate(
        std::slice::from_ref(&with_colon),
        &volume(Some("exfat"), Some(u64::MAX / 2), false),
    );
    let on_ext4 = PreflightPolicy::evaluate(
        &[with_colon],
        &volume(Some("ext4"), Some(u64::MAX / 2), false),
    );

    assert!(matches!(
        on_vfat.problems.as_slice(),
        [PreflightProblem::IllegalCharacters { .. }]
    ));
    assert!(matches!(
        on_exfat.problems.as_slice(),
        [PreflightProblem::IllegalCharacters { .. }]
    ));
    assert!(
        on_ext4.problems.is_empty(),
        "ext4 has no illegal-character rule"
    );
}

#[test]
fn every_fat_illegal_character_is_reported_not_just_the_first() {
    let name = "a\\b/c:d*e?f\"g<h>i|j.txt";

    let result = PreflightPolicy::evaluate(
        &[item(name)],
        &volume(Some("vfat"), Some(u64::MAX / 2), false),
    );

    let PreflightProblem::IllegalCharacters { characters, .. } = &result.problems[0] else {
        panic!("expected IllegalCharacters, got {:?}", result.problems);
    };
    assert_eq!(
        &HashSet::from(['\\', '/', ':', '*', '?', '"', '<', '>', '|']),
        characters
    );
}

#[test]
fn a_trailing_space_or_dot_is_flagged_on_vfat_and_exfat_only() {
    let trailing_space = item("name ");
    let trailing_dot = item("name.");
    let clean = item("name");

    let result = PreflightPolicy::evaluate(
        &[trailing_space.clone(), trailing_dot.clone(), clean.clone()],
        &volume(Some("exfat"), Some(u64::MAX / 2), false),
    );

    assert_eq!(
        2,
        result
            .problems
            .iter()
            .filter(|p| matches!(p, PreflightProblem::TrailingSpaceOrDot { .. }))
            .count()
    );
    assert!(result.problems.iter().all(|p| p.item() != &clean));
}

#[test]
fn sanitized_name_replaces_illegal_characters_and_a_trailing_space_or_dot_with_underscore() {
    assert_eq!("a_b_c.txt", PreflightPolicy::sanitized_name("a:b/c.txt"));
    assert_eq!("name_", PreflightPolicy::sanitized_name("name "));
    assert_eq!("name_", PreflightPolicy::sanitized_name("name."));
    assert_eq!("clean.txt", PreflightPolicy::sanitized_name("clean.txt"));
}

// --- name length --------------------------------------------------------------------------

#[test]
fn a_name_over_255_utf8_bytes_is_flagged_regardless_of_filesystem() {
    let long_name = format!("{}.txt", "a".repeat(300));

    let result = PreflightPolicy::evaluate(
        &[item(&long_name)],
        &volume(Some("ext4"), Some(u64::MAX / 2), false),
    );

    let PreflightProblem::NameTooLong { limit_bytes, .. } = &result.problems[0] else {
        panic!("expected NameTooLong, got {:?}", result.problems);
    };
    assert_eq!(255, *limit_bytes);
}

#[test]
fn a_255_byte_name_is_not_flagged_a_256_byte_name_is() {
    let exactly_255 = item(&"a".repeat(255));
    let exactly_256 = item(&"a".repeat(256));

    let result = PreflightPolicy::evaluate(&[exactly_255, exactly_256.clone()], &default_volume());

    assert_eq!(1, result.problems.len());
    assert_eq!(&exactly_256, result.problems[0].item());
}

#[test]
fn name_length_is_measured_in_utf8_bytes_not_characters() {
    // Each of these is one Unicode scalar value but 3 UTF-8 bytes -- 100 of them is 300 bytes,
    // over the limit, even though the string itself is only 100 characters long.
    let name = "\u{3042}".repeat(100);

    let result = PreflightPolicy::evaluate(&[item(&name)], &default_volume());

    assert!(matches!(
        result.problems.as_slice(),
        [PreflightProblem::NameTooLong { .. }]
    ));
}

// --- vfat's 4 GiB file limit --------------------------------------------------------------

#[test]
fn a_file_at_exactly_4_gib_minus_1_fits_on_vfat_one_byte_more_does_not() {
    let fits = item_full("big.bin", false, Some(PreflightPolicy::VFAT_MAX_FILE_BYTES));
    let too_big = item_full(
        "toobig.bin",
        false,
        Some(PreflightPolicy::VFAT_MAX_FILE_BYTES + 1),
    );

    let result = PreflightPolicy::evaluate(
        &[fits, too_big.clone()],
        &volume(Some("vfat"), Some(u64::MAX / 2), false),
    );

    assert_eq!(1, result.problems.len());
    assert_eq!(&too_big, result.problems[0].item());
}

#[test]
fn the_vfat_file_size_limit_does_not_apply_to_exfat_or_a_directory() {
    let big_file_on_exfat = item_full(
        "big.bin",
        false,
        Some(PreflightPolicy::VFAT_MAX_FILE_BYTES + 1),
    );
    let big_folder_on_vfat = item_full(
        "folder",
        true,
        Some(PreflightPolicy::VFAT_MAX_FILE_BYTES + 1),
    );

    let on_exfat = PreflightPolicy::evaluate(
        &[big_file_on_exfat],
        &volume(Some("exfat"), Some(u64::MAX / 2), false),
    );
    let on_vfat_directory = PreflightPolicy::evaluate(
        &[big_folder_on_vfat],
        &volume(Some("vfat"), Some(u64::MAX / 2), false),
    );

    assert!(
        on_exfat
            .problems
            .iter()
            .all(|p| !matches!(p, PreflightProblem::FileTooLargeForVfat { .. })),
        "exfat has no 4 GiB single-file limit"
    );
    assert!(
        on_vfat_directory
            .problems
            .iter()
            .all(|p| !matches!(p, PreflightProblem::FileTooLargeForVfat { .. })),
        "a directory has no single file size to exceed"
    );
}

// --- case-insensitive collisions -----------------------------------------------------------

#[test]
fn case_insensitive_collisions_are_flagged_on_a_case_insensitive_volume() {
    let first = item("Photo.jpg");
    let second = item("photo.JPG");
    let third = item("PHOTO.jpg");

    let result = PreflightPolicy::evaluate(
        &[first, second.clone(), third.clone()],
        &volume(None, Some(u64::MAX / 2), true),
    );

    let collisions: Vec<&PreflightProblem> = result
        .problems
        .iter()
        .filter(|p| matches!(p, PreflightProblem::NameCollision { .. }))
        .collect();
    assert_eq!(2, collisions.len());
    let items: HashSet<&PreflightItem> = collisions.iter().map(|p| p.item()).collect();
    assert_eq!(HashSet::from([&second, &third]), items);
    assert!(collisions.iter().all(|p| matches!(p, PreflightProblem::NameCollision { collides_with_name, .. } if collides_with_name == "Photo.jpg")));
}

#[test]
fn case_insensitive_collisions_are_also_flagged_on_the_fat_family_even_if_case_insensitive_is_false(
) {
    let result = PreflightPolicy::evaluate(
        &[item("a.txt"), item("A.txt")],
        &volume(Some("vfat"), Some(u64::MAX / 2), false),
    );

    assert_eq!(
        1,
        result
            .problems
            .iter()
            .filter(|p| matches!(p, PreflightProblem::NameCollision { .. }))
            .count()
    );
}

#[test]
fn distinct_names_never_collide_even_on_a_case_insensitive_volume() {
    let result = PreflightPolicy::evaluate(
        &[item("a.txt"), item("b.txt")],
        &volume(None, Some(u64::MAX / 2), true),
    );

    assert!(result
        .problems
        .iter()
        .all(|p| !matches!(p, PreflightProblem::NameCollision { .. })));
}

#[test]
fn case_differences_never_collide_on_a_case_sensitive_volume() {
    let result = PreflightPolicy::evaluate(
        &[item("a.txt"), item("A.txt")],
        &volume(Some("ext4"), Some(u64::MAX / 2), false),
    );

    assert!(result.problems.is_empty());
}

// --- free space ----------------------------------------------------------------------------

#[test]
fn insufficient_space_is_flagged_when_free_bytes_fall_under_required_plus_a_5_percent_margin() {
    let items = [
        item_full("a.bin", false, Some(1_000)),
        item_full("b.bin", false, Some(1_000)),
    ];
    // required = 2000, margin = 100, requiredWithMargin = 2100
    let result = PreflightPolicy::evaluate(&items, &volume(None, Some(2_050), false));

    let insufficient = result
        .insufficient_space
        .expect("expected insufficient space");
    assert_eq!(2_100, insufficient.required_bytes);
    assert_eq!(2_050, insufficient.available_bytes);
}

#[test]
fn exactly_enough_space_including_the_margin_is_not_flagged() {
    let items = [item_full("a.bin", false, Some(1_000))];
    // required = 1000, margin = 50, requiredWithMargin = 1050
    let result = PreflightPolicy::evaluate(&items, &volume(None, Some(1_050), false));

    assert_eq!(None, result.insufficient_space);
}

#[test]
fn unknown_free_space_is_never_flagged_as_insufficient() {
    let items = [item_full("a.bin", false, Some(u64::MAX))];

    let result = PreflightPolicy::evaluate(&items, &volume(None, None, false));

    assert_eq!(None, result.insufficient_space);
}

#[test]
fn an_item_with_an_unknown_size_is_excluded_from_the_required_total_rather_than_failing_the_check()
{
    let items = [
        item_full("known.bin", false, Some(1_000)),
        item_full("unknown.bin", false, None),
    ];
    // required counts only the known item: 1000, margin 50, requiredWithMargin 1050
    let result = PreflightPolicy::evaluate(&items, &volume(None, Some(1_040), false));

    let insufficient = result
        .insufficient_space
        .expect("expected insufficient space");
    assert_eq!(1_050, insufficient.required_bytes);
}

// --- overall result shape -------------------------------------------------------------------

#[test]
fn is_clean_is_true_only_when_there_are_no_problems_and_no_insufficient_space_flag() {
    let clean =
        PreflightPolicy::evaluate(&[item("ok.txt")], &volume(None, Some(u64::MAX / 2), false));
    assert!(clean.is_clean());

    let dirty = PreflightPolicy::evaluate(
        &[item("bad:name")],
        &volume(Some("vfat"), Some(u64::MAX / 2), false),
    );
    assert!(!dirty.is_clean());
}

#[test]
fn an_empty_selection_has_no_problems_and_no_space_requirement() {
    let result = PreflightPolicy::evaluate(&[], &volume(Some("vfat"), Some(0), false));
    assert!(result.is_clean());
}
