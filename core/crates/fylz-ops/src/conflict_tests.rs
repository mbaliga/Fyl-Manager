//! Golden tests ported one-to-one from `ConflictDetectionTest.kt`, over real temp-directory
//! trees instead of the Kotlin test's `FylzFilesDocumentsProvider` fixture -- see this module's
//! doc comment for the one Kotlin case with no Linux analogue.

use std::collections::HashMap;
use std::fs;

use tempfile::tempdir;

use super::*;

#[test]
fn no_conflicts_when_nothing_at_the_destination_shares_a_name() {
    let root = tempdir().unwrap();
    let source = root.path().join("source.bin");
    fs::write(&source, b"0123456789").unwrap();
    let destination = root.path().join("destination");
    fs::create_dir(&destination).unwrap();

    let conflicts = find_conflicts(&[source], &destination, &HashMap::new());

    assert!(conflicts.is_empty());
}

#[test]
fn a_same_named_destination_item_is_reported_as_a_conflict() {
    let root = tempdir().unwrap();
    let source = root.path().join("source.bin");
    fs::write(&source, b"0123456789").unwrap();
    let destination = root.path().join("destination");
    fs::create_dir(&destination).unwrap();
    fs::write(destination.join("source.bin"), [0u8; 20]).unwrap();

    let conflicts = find_conflicts(std::slice::from_ref(&source), &destination, &HashMap::new());

    let conflict = &conflicts[0];
    assert_eq!(1, conflicts.len());
    assert_eq!(source, conflict.source_path);
    assert_eq!("source.bin", conflict.existing_name);
    assert_eq!(20, fs::metadata(&conflict.existing_path).unwrap().len());
}

#[test]
fn name_overrides_is_what_gets_checked_not_the_sources_own_original_name() {
    let root = tempdir().unwrap();
    let source = root.path().join("bad_name.txt");
    fs::write(&source, b"0123456789").unwrap();
    let destination = root.path().join("destination");
    fs::create_dir(&destination).unwrap();
    fs::write(destination.join("renamed.txt"), [0u8; 5]).unwrap();

    let mut overrides = HashMap::new();
    overrides.insert(source.clone(), "renamed.txt".to_string());

    let conflicts = find_conflicts(&[source], &destination, &overrides);

    assert_eq!(1, conflicts.len());
    assert_eq!("renamed.txt", conflicts[0].existing_name);
}

#[test]
fn a_name_overrides_rename_can_also_make_an_original_conflict_disappear() {
    let root = tempdir().unwrap();
    let source = root.path().join("source.bin");
    fs::write(&source, b"0123456789").unwrap();
    let destination = root.path().join("destination");
    fs::create_dir(&destination).unwrap();
    fs::write(destination.join("source.bin"), [0u8; 20]).unwrap();

    let mut overrides = HashMap::new();
    overrides.insert(source.clone(), "source (2).bin".to_string());

    let conflicts = find_conflicts(&[source], &destination, &overrides);

    assert!(conflicts.is_empty());
}

#[test]
fn a_source_that_no_longer_resolves_is_dropped_rather_than_failing_the_whole_check() {
    let root = tempdir().unwrap();
    let real_source = root.path().join("real.bin");
    fs::write(&real_source, b"0123456789").unwrap();
    let destination = root.path().join("destination");
    fs::create_dir(&destination).unwrap();
    fs::write(destination.join("real.bin"), [0u8; 5]).unwrap();
    fs::write(destination.join("ghost.bin"), [0u8; 5]).unwrap();
    // Never created at the top level, unlike "real.bin" -- this source path does not resolve.
    let ghost_source = root.path().join("ghost.bin");

    let conflicts = find_conflicts(&[real_source, ghost_source], &destination, &HashMap::new());

    assert_eq!(
        vec!["real.bin".to_string()],
        conflicts
            .iter()
            .map(|c| c.existing_name.clone())
            .collect::<Vec<_>>()
    );
}
