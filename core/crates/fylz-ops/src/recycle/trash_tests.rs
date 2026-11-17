//! New coverage: no Kotlin test file is this module's parity oracle -- see `trash.rs`'s own
//! doc comment for why. These tests pin this module's behaviour directly against the
//! specification text instead.
//!
//! Tests that need to control `$HOME`/`$XDG_DATA_HOME` serialize on [`ENV_LOCK`] and restore
//! the previous values on drop, since environment variables are process-global state and
//! `cargo test` runs test functions on multiple threads of the same process by default.

use std::collections::HashSet;
use std::ffi::OsString;
use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::Path;
use std::sync::Mutex;

use tempfile::tempdir;

use super::*;

static ENV_LOCK: Mutex<()> = Mutex::new(());

struct HomeOverride {
    _lock: std::sync::MutexGuard<'static, ()>,
    previous_home: Option<OsString>,
    previous_xdg: Option<OsString>,
}

impl HomeOverride {
    fn new(home: &Path, xdg_data_home: Option<&Path>) -> Self {
        let lock = ENV_LOCK
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let previous_home = std::env::var_os("HOME");
        let previous_xdg = std::env::var_os("XDG_DATA_HOME");
        std::env::set_var("HOME", home);
        match xdg_data_home {
            Some(path) => std::env::set_var("XDG_DATA_HOME", path),
            None => std::env::remove_var("XDG_DATA_HOME"),
        }
        Self {
            _lock: lock,
            previous_home,
            previous_xdg,
        }
    }
}

impl Drop for HomeOverride {
    fn drop(&mut self) {
        match &self.previous_home {
            Some(value) => std::env::set_var("HOME", value),
            None => std::env::remove_var("HOME"),
        }
        match &self.previous_xdg {
            Some(value) => std::env::set_var("XDG_DATA_HOME", value),
            None => std::env::remove_var("XDG_DATA_HOME"),
        }
    }
}

// --- home trash resolution ------------------------------------------------------------------

#[test]
fn resolve_trash_dir_for_a_file_under_home_uses_xdg_data_home_trash() {
    let home = tempdir().unwrap();
    let _override = HomeOverride::new(home.path(), None);
    let file = home.path().join("document.txt");
    fs::write(&file, b"hello").unwrap();

    let trash_root = resolve_trash_dir_for(&file).unwrap();

    assert_eq!(home.path().join(".local/share/Trash"), trash_root);
    assert!(trash_root.join("files").is_dir());
    assert!(trash_root.join("info").is_dir());
}

#[test]
fn resolve_trash_dir_for_respects_an_explicit_xdg_data_home() {
    let home = tempdir().unwrap();
    let data_home = tempdir().unwrap();
    let _override = HomeOverride::new(home.path(), Some(data_home.path()));
    let file = home.path().join("document.txt");
    fs::write(&file, b"hello").unwrap();

    let trash_root = resolve_trash_dir_for(&file).unwrap();

    assert_eq!(data_home.path().join("Trash"), trash_root);
}

// --- topdir trash: $topdir/.Trash/$uid validity checks (called directly, not through device
// detection, since a real second device is not available in a sandboxed test run) -----------

#[test]
fn a_missing_dot_trash_falls_back_to_dot_trash_dash_uid() {
    let topdir = tempdir().unwrap();

    let trash_root = ensure_topdir_trash(topdir.path()).unwrap();

    assert_eq!(
        topdir.path().join(format!(".Trash-{}", current_uid())),
        trash_root
    );
}

#[test]
fn a_dot_trash_without_the_sticky_bit_is_rejected() {
    let topdir = tempdir().unwrap();
    let dot_trash = topdir.path().join(".Trash");
    fs::create_dir(&dot_trash).unwrap();
    fs::set_permissions(&dot_trash, fs::Permissions::from_mode(0o777)).unwrap(); // no sticky bit

    assert_eq!(
        None,
        valid_dot_trash_uid_dir(topdir.path(), current_uid()).unwrap()
    );
}

#[test]
fn a_dot_trash_that_is_a_symlink_is_rejected() {
    let topdir = tempdir().unwrap();
    let real_dir = topdir.path().join("real");
    fs::create_dir(&real_dir).unwrap();
    fs::set_permissions(&real_dir, fs::Permissions::from_mode(0o1777)).unwrap();
    std::os::unix::fs::symlink(&real_dir, topdir.path().join(".Trash")).unwrap();

    assert_eq!(
        None,
        valid_dot_trash_uid_dir(topdir.path(), current_uid()).unwrap()
    );
}

#[test]
fn a_valid_sticky_dot_trash_gets_its_uid_subdirectory_created() {
    let topdir = tempdir().unwrap();
    let dot_trash = topdir.path().join(".Trash");
    fs::create_dir(&dot_trash).unwrap();
    fs::set_permissions(&dot_trash, fs::Permissions::from_mode(0o1777)).unwrap();

    let uid_dir = valid_dot_trash_uid_dir(topdir.path(), current_uid())
        .unwrap()
        .unwrap();

    assert_eq!(dot_trash.join(current_uid().to_string()), uid_dir);
    assert_eq!(
        0o700,
        fs::metadata(&uid_dir).unwrap().permissions().mode() & 0o777
    );
}

// --- topdir_for: property-based rather than an exact value, which depends on the sandbox's
// own real mount layout ----------------------------------------------------------------------

#[test]
fn topdir_for_returns_an_ancestor_on_the_same_device_as_the_input() {
    let dir = tempdir().unwrap();
    let nested = dir.path().join("a/b/c");
    fs::create_dir_all(&nested).unwrap();
    let file = nested.join("f.txt");
    fs::write(&file, b"x").unwrap();

    let topdir = topdir_for(&file).unwrap();

    assert!(
        file.starts_with(&topdir),
        "{topdir:?} must be an ancestor of {file:?}"
    );
    assert_eq!(
        fs::metadata(&topdir).unwrap().dev(),
        fs::symlink_metadata(&file).unwrap().dev()
    );
}

// --- trashing, listing, purging ---------------------------------------------------------------

#[test]
fn trashing_a_file_moves_it_and_writes_a_matching_trashinfo() {
    let home = tempdir().unwrap();
    let _override = HomeOverride::new(home.path(), None);
    let file = home.path().join("note.txt");
    fs::write(&file, b"contents").unwrap();

    let record = trash_at(&file, 1_700_000_000).unwrap();

    assert!(
        !file.exists(),
        "the original location is empty after trashing"
    );
    assert_eq!(b"contents".to_vec(), fs::read(&record.files_path).unwrap());
    assert!(record.info_path.exists());
    let fields =
        trashinfo::parse_trashinfo(&fs::read_to_string(&record.info_path).unwrap()).unwrap();
    assert_eq!(file.to_string_lossy(), fields.path);
}

#[test]
fn trashing_a_directory_moves_the_whole_tree() {
    let home = tempdir().unwrap();
    let _override = HomeOverride::new(home.path(), None);
    let dir = home.path().join("folder");
    fs::create_dir(&dir).unwrap();
    fs::write(dir.join("inside.txt"), b"y").unwrap();

    let record = trash_at(&dir, 0).unwrap();

    assert!(!dir.exists());
    assert!(record.files_path.join("inside.txt").is_file());
}

#[test]
fn a_second_item_with_the_same_name_gets_a_disambiguated_id() {
    let home = tempdir().unwrap();
    let _override = HomeOverride::new(home.path(), None);
    let first_dir = home.path().join("a");
    let second_dir = home.path().join("b");
    fs::create_dir_all(&first_dir).unwrap();
    fs::create_dir_all(&second_dir).unwrap();
    fs::write(first_dir.join("dup.txt"), b"1").unwrap();
    fs::write(second_dir.join("dup.txt"), b"2").unwrap();

    let first = trash_at(&first_dir.join("dup.txt"), 0).unwrap();
    let second = trash_at(&second_dir.join("dup.txt"), 0).unwrap();

    assert_ne!(first.id, second.id);
    assert_eq!(b"1".to_vec(), fs::read(&first.files_path).unwrap());
    assert_eq!(b"2".to_vec(), fs::read(&second.files_path).unwrap());
}

#[test]
fn list_trash_reads_back_every_record_written() {
    let home = tempdir().unwrap();
    let _override = HomeOverride::new(home.path(), None);
    fs::write(home.path().join("a.txt"), b"a").unwrap();
    fs::write(home.path().join("b.txt"), b"b").unwrap();
    trash_at(&home.path().join("a.txt"), 10).unwrap();
    trash_at(&home.path().join("b.txt"), 20).unwrap();

    let records = list_trash(&home_trash_dir()).unwrap();

    let mut names: Vec<String> = records.iter().map(|r| r.id.clone()).collect();
    names.sort();
    assert_eq!(vec!["a.txt".to_string(), "b.txt".to_string()], names);
}

#[test]
fn list_trash_on_a_directory_that_does_not_exist_yet_is_an_empty_list_not_an_error() {
    let dir = tempdir().unwrap();
    assert!(list_trash(&dir.path().join("nonexistent/Trash"))
        .unwrap()
        .is_empty());
}

#[test]
fn purge_removes_both_the_content_and_the_trashinfo() {
    let home = tempdir().unwrap();
    let _override = HomeOverride::new(home.path(), None);
    fs::write(home.path().join("gone.txt"), b"z").unwrap();
    let record = trash_at(&home.path().join("gone.txt"), 0).unwrap();

    purge(&record).unwrap();

    assert!(!record.files_path.exists());
    assert!(!record.info_path.exists());
}

// --- restoring ---------------------------------------------------------------------------

#[test]
fn resolve_restore_plan_with_no_collision_keeps_the_requested_name() {
    let plan = resolve_restore_plan(&HashSet::new(), "photo.jpg", ConflictPolicy::Ask)
        .unwrap()
        .unwrap();
    assert_eq!("photo.jpg", plan.requested_name);
    assert!(!plan.replaces_existing);
}

#[test]
fn resolve_restore_plan_ask_on_a_collision_is_an_error_the_caller_must_resolve() {
    let existing = HashSet::from(["photo.jpg".to_string()]);
    assert!(resolve_restore_plan(&existing, "photo.jpg", ConflictPolicy::Ask).is_err());
}

#[test]
fn resolve_restore_plan_skip_on_a_collision_returns_none() {
    let existing = HashSet::from(["photo.jpg".to_string()]);
    assert_eq!(
        Ok(None),
        resolve_restore_plan(&existing, "photo.jpg", ConflictPolicy::Skip)
    );
}

#[test]
fn resolve_restore_plan_replace_if_newer_is_treated_as_skip() {
    let existing = HashSet::from(["photo.jpg".to_string()]);
    assert_eq!(
        Ok(None),
        resolve_restore_plan(&existing, "photo.jpg", ConflictPolicy::ReplaceIfNewer)
    );
}

#[test]
fn resolve_restore_plan_keep_both_finds_a_free_numbered_name() {
    let existing = HashSet::from(["photo.jpg".to_string(), "photo (2).jpg".to_string()]);
    let plan = resolve_restore_plan(&existing, "photo.jpg", ConflictPolicy::KeepBoth)
        .unwrap()
        .unwrap();
    assert_eq!("photo (3).jpg", plan.requested_name);
    assert!(!plan.replaces_existing);
}

#[test]
fn resolve_restore_plan_replace_keeps_the_name_and_flags_the_replacement() {
    let existing = HashSet::from(["photo.jpg".to_string()]);
    let plan = resolve_restore_plan(&existing, "photo.jpg", ConflictPolicy::Replace)
        .unwrap()
        .unwrap();
    assert_eq!("photo.jpg", plan.requested_name);
    assert!(plan.replaces_existing);
}

#[test]
fn restore_with_plan_moves_the_item_back_and_removes_the_trashinfo() {
    let home = tempdir().unwrap();
    let _override = HomeOverride::new(home.path(), None);
    let original = home.path().join("restore-me.txt");
    fs::write(&original, b"back again").unwrap();
    let record = trash_at(&original, 0).unwrap();

    let plan = RestorePlan {
        requested_name: "restore-me.txt".to_string(),
        replaces_existing: false,
    };
    let restored = restore_with_plan(&record, home.path(), &plan, 0).unwrap();

    assert_eq!(original, restored);
    assert_eq!(b"back again".to_vec(), fs::read(&restored).unwrap());
    assert!(!record.info_path.exists());
}

#[test]
fn restore_with_plan_replace_trashes_the_displaced_item_first() {
    let home = tempdir().unwrap();
    let _override = HomeOverride::new(home.path(), None);
    let original = home.path().join("shared-name.txt");
    fs::write(&original, b"version 1").unwrap();
    let record = trash_at(&original, 0).unwrap();
    // Something now occupies the original name again.
    fs::write(&original, b"version 2").unwrap();

    let plan = RestorePlan {
        requested_name: "shared-name.txt".to_string(),
        replaces_existing: true,
    };
    let restored = restore_with_plan(&record, home.path(), &plan, 100).unwrap();

    assert_eq!(b"version 1".to_vec(), fs::read(&restored).unwrap());
    // "version 2" was not deleted outright -- it was trashed (under a disambiguated id, since
    // "shared-name.txt" was already taken by `record` at that point), so it is still
    // recoverable.
    let displaced: Vec<_> = list_trash(&home_trash_dir())
        .unwrap()
        .into_iter()
        .filter(|r| fs::read(&r.files_path).ok() == Some(b"version 2".to_vec()))
        .collect();
    assert_eq!(1, displaced.len());
}

// --- unique_name / split_extension ----------------------------------------------------------

#[test]
fn unique_name_appends_a_counter_starting_at_2() {
    let existing = HashSet::from(["a.txt".to_string()]);
    assert_eq!("a (2).txt", unique_name(&existing, "a.txt"));
}

#[test]
fn unique_name_skips_a_counter_already_taken() {
    let existing = HashSet::from(["a.txt".to_string(), "a (2).txt".to_string()]);
    assert_eq!("a (3).txt", unique_name(&existing, "a.txt"));
}

#[test]
fn split_extension_treats_a_leading_dot_as_no_extension() {
    assert_eq!(
        (".bashrc".to_string(), String::new()),
        split_extension(".bashrc")
    );
    assert_eq!(
        ("name".to_string(), ".txt".to_string()),
        split_extension("name.txt")
    );
}
