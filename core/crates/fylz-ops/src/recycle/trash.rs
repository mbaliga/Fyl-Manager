//! A from-scratch implementation of the freedesktop.org Trash specification (version 1.0),
//! which `docs/agent/MASTER_PLAN.md`'s own M13.1 entry calls for in place of Android's
//! SAF/MediaStore-backed `RecycleBinStore.kt`/`RecycleBinService.kt`: "On Linux the recycle bin
//! follows the freedesktop Trash spec: `$XDG_DATA_HOME/Trash` for home, and
//! `$topdir/.Trash-$uid` for removable volumes... Keep Fylz's own journal on top. The Android
//! recycle bin stays as is." No search of this codebase (`trash`/`XDG_DATA_HOME`/`xdg`) turned
//! up an existing freedesktop-spec implementation or reference to port from -- the closest
//! things found were `RecycleBinService.RECYCLE_DIRECTORY = ".fylz-trash"` (Android's own,
//! differently-shaped, SAF-based bin) and `VolumeInfo.kt`'s `/proc/self/mounts` parser (a
//! different problem: filesystem *type*, not a device's mount-point *boundary*, which is what
//! this module's own [`topdir_for`] needs and computes independently, by walking `st_dev`
//! boundaries rather than parsing `/proc/mounts` text). This module, and `super::trashinfo`, are
//! written directly from the specification text, not ported from anything.
//!
//! ## Why no Kotlin test file is this module's parity oracle
//!
//! Every other module in this crate cites a `*Test.kt` file it ports test cases from.
//! `RecycleBinStore.kt`/`RecycleBinService.kt` have no equivalent here to port tests *for*:
//! their tests (`RecycleBinServiceTest.kt`, `RecycleBinLegacyFolderTest.kt`) exercise a SAF
//! `DocumentFile` tree, MediaStore-adjacent quirks, and an Android-only `.fylz-trash` naming
//! convention with no meaning on Linux. What *is* ported one-to-one is the pure decision logic
//! in `RecycleBinPolicy.kt` (see [`super::policy`]) and the conflict-resolution shape
//! `RecycleBinService.resolveRestorePlan`/`uniqueName` used for restoring into an occupied
//! destination (see [`resolve_restore_plan`]/[`unique_name`] below, which follow that method's
//! logic exactly, still with no Kotlin test file behind them since it had none of its own
//! either -- `RecycleBinServiceTest.kt` only exercises it indirectly through a full SAF-backed
//! `restore()` call). This module's own tests (`trash_tests.rs`) are therefore new coverage
//! written directly against the specification, not a golden port.
//!
//! ## No separate `RecycleBinStore` equivalent
//!
//! On Android, `RecycleBinStore` exists because a SAF trash folder cannot tell you an item's
//! original location or deletion time on its own -- Fylz has to keep that metadata itself, in
//! a JSON blob in `SharedPreferences`. The freedesktop Trash spec's own `.trashinfo` file
//! *already is* that metadata, one file per trashed item, sitting right next to the design this
//! module implements -- [`list_trash`] reads it directly. A second, separate manifest here
//! would only duplicate what `Trash/info/*.trashinfo` already durably records, so this module
//! has none.
//!
//! ## Scope: same-device only
//!
//! [`resolve_trash_dir_for`] always names a trash directory on the SAME device as the item
//! being trashed (that is the entire point of the spec's home/topdir split), so [`trash_at`]'s
//! move into it, and [`restore_with_plan`]'s move back to the item's own original location, are
//! always a same-filesystem `rename` -- one atomic syscall, no copy, no verification needed,
//! the same fast path Kotlin's own `RecycleBinService.recycleNode` comment calls out as its
//! "P1.3/A5" special case for same-volume moves, except here it is the ONLY path, never a
//! fallback. Restoring to an arbitrary destination on a DIFFERENT device than the trash
//! directory is deliberately out of this module's scope: that needs a real copy-and-verify
//! transfer (this crate's [`crate::checksum`] and [`crate::staging`] modules already provide
//! the pieces for one), which belongs to the eventual multi-device copy/move engine, not to the
//! recycle bin -- `restore_with_plan` below reports a plain `io::Error` (`ErrorKind::CrossesDevices`
//! on a platform new enough to have it) rather than silently reimplementing that engine poorly.

use std::collections::HashSet;
use std::fs;
use std::io;
use std::os::unix::fs::MetadataExt;
use std::os::unix::fs::PermissionsExt;
use std::path::Path;
use std::path::PathBuf;

use crate::models::ConflictPolicy;

use super::trashinfo;

/// One item currently in a freedesktop trash directory, as read back from its `.trashinfo`
/// file (or about to be written to one).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TrashRecord {
    /// The shared basename `Trash/files/<id>` and `Trash/info/<id>.trashinfo` both use.
    pub id: String,
    pub original_path: PathBuf,
    pub trashed_at_unix_seconds: i64,
    pub files_path: PathBuf,
    pub info_path: PathBuf,
}

/// `$XDG_DATA_HOME`, or its spec-defined default, `$HOME/.local/share`.
fn xdg_data_home() -> PathBuf {
    std::env::var_os("XDG_DATA_HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| home_dir().join(".local/share"))
}

fn home_dir() -> PathBuf {
    std::env::var_os("HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("/"))
}

/// The home trash directory's own root (before `files`/`info` are appended):
/// `$XDG_DATA_HOME/Trash`.
pub fn home_trash_dir() -> PathBuf {
    xdg_data_home().join("Trash")
}

/// The mount point containing `path`: the highest ancestor directory that still reports the
/// same device number as `path` itself. Walking `st_dev` this way finds a filesystem boundary
/// without parsing `/proc/mounts` at all -- it works for a bind mount, an overlay, or any other
/// mount the kernel does not bother naming in the same way `/proc/self/mounts` would, and it is
/// the specification's own "topdir" concept: "the mount point of the partition containing the
/// trashed file".
fn topdir_for(path: &Path) -> io::Result<PathBuf> {
    let start = if path.is_absolute() {
        path.to_path_buf()
    } else {
        std::env::current_dir()?.join(path)
    };
    let target_dev = fs::symlink_metadata(&start)?.dev();

    let mut boundary = start.clone();
    let mut current = start;
    while let Some(parent) = current.parent() {
        let parent_dev = match fs::metadata(parent) {
            Ok(metadata) => metadata.dev(),
            Err(_) => break,
        };
        if parent_dev != target_dev {
            break;
        }
        boundary = parent.to_path_buf();
        current = parent.to_path_buf();
    }
    Ok(boundary)
}

/// Decides, then creates if needed, the trash directory `path` should be trashed into: the
/// home trash if `path` is on the same device as `$HOME`, otherwise a trash directory rooted
/// under `path`'s own `topdir` (see [`topdir_for`]) -- `$topdir/.Trash/$uid` if that passes the
/// specification's own safety checks, else `$topdir/.Trash-$uid`, created fresh if neither
/// exists yet. Returns the trash directory's root (its `files`/`info` subdirectories are
/// created too, but not returned separately -- join `"files"`/`"info"` onto the result).
pub fn resolve_trash_dir_for(path: &Path) -> io::Result<PathBuf> {
    let absolute = if path.is_absolute() {
        path.to_path_buf()
    } else {
        std::env::current_dir()?.join(path)
    };
    let file_dev = fs::symlink_metadata(&absolute)?.dev();
    let home_dev = fs::metadata(home_dir())?.dev();

    if file_dev == home_dev {
        ensure_trash_layout(&home_trash_dir())
    } else {
        let topdir = topdir_for(&absolute)?;
        ensure_topdir_trash(&topdir)
    }
}

/// SAFETY note for callers, not a memory-safety one: `getuid()` never fails and takes no
/// pointer, so this has nothing unsafe about its preconditions -- it is only `unsafe` because
/// every raw libc FFI call is.
fn current_uid() -> u32 {
    // SAFETY: `getuid(2)` reads only process state, never memory this call passes it.
    unsafe { libc::getuid() }
}

fn ensure_topdir_trash(topdir: &Path) -> io::Result<PathBuf> {
    let uid = current_uid();
    if let Some(candidate) = valid_dot_trash_uid_dir(topdir, uid)? {
        return ensure_trash_layout(&candidate);
    }
    let fallback = topdir.join(format!(".Trash-{uid}"));
    fs::create_dir_all(&fallback)?;
    fs::set_permissions(&fallback, fs::Permissions::from_mode(0o700))?;
    ensure_trash_layout(&fallback)
}

/// The specification's own checks for `$topdir/.Trash`, in order: it must exist, must not be a
/// symlink (a symlink here could point an attacker's `.Trash` at another user's files), and
/// must have the sticky bit set (so only each file's own owner can remove it from a directory
/// every user can write into). Only once all three hold does `$topdir/.Trash/$uid` become a
/// candidate; that subdirectory is then created (mode `0700`) if absent, or, if present,
/// accepted only when it is itself a real directory owned by `uid` and not a symlink. Any
/// failed check returns `Ok(None)` -- "fall back to `.Trash-$uid`" -- never an error: a `.Trash`
/// some other tool created but got wrong is not this crate's problem to report, only to avoid
/// trusting.
fn valid_dot_trash_uid_dir(topdir: &Path, uid: u32) -> io::Result<Option<PathBuf>> {
    let dot_trash = topdir.join(".Trash");
    let metadata = match fs::symlink_metadata(&dot_trash) {
        Ok(metadata) => metadata,
        Err(_) => return Ok(None),
    };
    if metadata.file_type().is_symlink() || !metadata.is_dir() {
        return Ok(None);
    }
    const STICKY_BIT: u32 = 0o1000;
    if metadata.permissions().mode() & STICKY_BIT == 0 {
        return Ok(None);
    }

    let uid_dir = dot_trash.join(uid.to_string());
    match fs::symlink_metadata(&uid_dir) {
        Ok(uid_metadata) => {
            if uid_metadata.file_type().is_symlink()
                || !uid_metadata.is_dir()
                || uid_metadata.uid() != uid
            {
                Ok(None)
            } else {
                Ok(Some(uid_dir))
            }
        }
        Err(_) => {
            fs::create_dir(&uid_dir)?;
            fs::set_permissions(&uid_dir, fs::Permissions::from_mode(0o700))?;
            Ok(Some(uid_dir))
        }
    }
}

fn ensure_trash_layout(trash_root: &Path) -> io::Result<PathBuf> {
    fs::create_dir_all(trash_root)?;
    let _ = fs::set_permissions(trash_root, fs::Permissions::from_mode(0o700));
    fs::create_dir_all(trash_root.join("files"))?;
    fs::create_dir_all(trash_root.join("info"))?;
    Ok(trash_root.to_path_buf())
}

/// A basename under `trash_root` that collides with neither an existing `files/` entry nor an
/// existing `info/*.trashinfo` record -- the specification requires implementations to rename
/// on collision (for example by appending a number) rather than overwrite.
fn unique_trash_id(trash_root: &Path, requested_name: &str) -> String {
    if !trash_entry_exists(trash_root, requested_name) {
        return requested_name.to_string();
    }
    let (stem, extension) = split_extension(requested_name);
    let mut n = 2u64;
    loop {
        let candidate = if extension.is_empty() {
            format!("{stem}.{n}")
        } else {
            format!("{stem}.{n}{extension}")
        };
        if !trash_entry_exists(trash_root, &candidate) {
            return candidate;
        }
        n += 1;
    }
}

fn trash_entry_exists(trash_root: &Path, id: &str) -> bool {
    trash_root.join("files").join(id).exists()
        || trash_root
            .join("info")
            .join(format!("{id}.trashinfo"))
            .exists()
}

/// Moves `path` into its own resolved trash directory ([`resolve_trash_dir_for`]) and records
/// it there, returning the [`TrashRecord`] to keep (typically in Fylz's own journal -- "Keep
/// Fylz's own journal on top" per the master plan).
///
/// The `.trashinfo` file is written BEFORE the move: if the move then fails, the orphaned info
/// file is removed and the error propagated, so a failure never leaves a moved-but-unrecorded
/// item behind. The reverse order would risk exactly that if this process died between the two
/// steps.
pub fn trash_at(path: &Path, now_unix_seconds: i64) -> io::Result<TrashRecord> {
    let absolute = if path.is_absolute() {
        path.to_path_buf()
    } else {
        std::env::current_dir()?.join(path)
    };
    let requested_name = absolute
        .file_name()
        .ok_or_else(|| {
            io::Error::new(
                io::ErrorKind::InvalidInput,
                "path has no file name to trash",
            )
        })?
        .to_string_lossy()
        .into_owned();

    let trash_root = resolve_trash_dir_for(&absolute)?;
    let id = unique_trash_id(&trash_root, &requested_name);
    let files_path = trash_root.join("files").join(&id);
    let info_path = trash_root.join("info").join(format!("{id}.trashinfo"));

    let info_contents = trashinfo::format_trashinfo(&absolute.to_string_lossy(), now_unix_seconds);
    fs::write(&info_path, info_contents)?;

    match fs::rename(&absolute, &files_path) {
        Ok(()) => Ok(TrashRecord {
            id,
            original_path: absolute,
            trashed_at_unix_seconds: now_unix_seconds,
            files_path,
            info_path,
        }),
        Err(err) => {
            let _ = fs::remove_file(&info_path);
            Err(err)
        }
    }
}

/// Every record currently in `trash_root` (as returned by [`resolve_trash_dir_for`], or any
/// other freedesktop trash directory -- this reads `Trash/info/*.trashinfo` directly, so it
/// works on a trash directory another tool wrote into too). A `.trashinfo` file this crate
/// cannot read or parse is skipped rather than failing the whole listing, and a missing
/// `DeletionDate` is recorded as `0` rather than dropping the record.
pub fn list_trash(trash_root: &Path) -> io::Result<Vec<TrashRecord>> {
    let info_dir = trash_root.join("info");
    let entries = match fs::read_dir(&info_dir) {
        Ok(entries) => entries,
        Err(err) if err.kind() == io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(err) => return Err(err),
    };

    let mut records = Vec::new();
    for entry in entries {
        let entry = entry?;
        let name = entry.file_name();
        let name = name.to_string_lossy();
        let Some(id) = name.strip_suffix(".trashinfo") else {
            continue;
        };
        let Ok(text) = fs::read_to_string(entry.path()) else {
            continue;
        };
        let Ok(fields) = trashinfo::parse_trashinfo(&text) else {
            continue;
        };
        records.push(TrashRecord {
            id: id.to_string(),
            original_path: PathBuf::from(fields.path),
            trashed_at_unix_seconds: fields.deletion_date_unix.unwrap_or(0),
            files_path: trash_root.join("files").join(id),
            info_path: entry.path(),
        });
    }
    Ok(records)
}

/// Permanently removes `record`'s content and its `.trashinfo` file. A `record` whose `files/`
/// entry is already gone (perhaps removed by something else) is not itself an error; only its
/// info file, if still present, is cleaned up.
pub fn purge(record: &TrashRecord) -> io::Result<()> {
    remove_path(&record.files_path)?;
    let _ = fs::remove_file(&record.info_path);
    Ok(())
}

fn remove_path(path: &Path) -> io::Result<()> {
    match fs::symlink_metadata(path) {
        Ok(metadata) if metadata.is_dir() => fs::remove_dir_all(path),
        Ok(_) => fs::remove_file(path),
        Err(err) if err.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(err) => Err(err),
    }
}

// --- restoring: the pure conflict policy, then the actual move -----------------------------

/// What [`restore_with_plan`] should do with a restore, once a conflict (if any) at the
/// destination has been resolved: land under `requested_name`, replacing whatever is there if
/// `replaces_existing`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RestorePlan {
    pub requested_name: String,
    pub replaces_existing: bool,
}

/// [`resolve_restore_plan`]'s only error: [`ConflictPolicy::Ask`] was passed for a name that
/// does collide, so the decision belongs to a user this function has no way to ask -- the
/// caller must ask them and call this again with whichever policy they actually chose.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AskRequired;

impl std::fmt::Display for AskRequired {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "a name collision at the restore destination requires asking the user"
        )
    }
}

impl std::error::Error for AskRequired {}

/// Ported from `RecycleBinService.resolveRestorePlan`'s logic (that method has no dedicated
/// Kotlin test file of its own -- see this module's own doc comment). `Ok(None)` means "skip
/// this restore silently" ([`ConflictPolicy::Skip`], and [`ConflictPolicy::ReplaceIfNewer`],
/// which nothing in this crate yet offers a caller a way to actually select, exactly as in the
/// Kotlin source).
pub fn resolve_restore_plan(
    existing_names: &HashSet<String>,
    requested_name: &str,
    policy: ConflictPolicy,
) -> Result<Option<RestorePlan>, AskRequired> {
    if !existing_names.contains(requested_name) {
        return Ok(Some(RestorePlan {
            requested_name: requested_name.to_string(),
            replaces_existing: false,
        }));
    }
    match policy {
        ConflictPolicy::Ask => Err(AskRequired),
        ConflictPolicy::Skip | ConflictPolicy::ReplaceIfNewer => Ok(None),
        ConflictPolicy::KeepBoth => Ok(Some(RestorePlan {
            requested_name: unique_name(existing_names, requested_name),
            replaces_existing: false,
        })),
        ConflictPolicy::Replace => Ok(Some(RestorePlan {
            requested_name: requested_name.to_string(),
            replaces_existing: true,
        })),
    }
}

/// Moves `record`'s content back to `destination_dir.join(plan.requested_name)`, and removes
/// its `.trashinfo` file once that succeeds. If `plan.replaces_existing`, the item currently
/// occupying that name is trashed first (via [`trash_at`], so it stays recoverable) -- if the
/// subsequent restore then fails, nothing is lost: the displaced item is simply sitting safely
/// in the trash, and `record`'s own info file is left in place so the restore can be retried.
///
/// Both moves are same-device renames by construction (see this module's own doc comment's
/// "Scope: same-device only" section) EXCEPT restoring to `destination_dir` itself, which this
/// function assumes is on the same device `record.files_path` already lives on -- true for
/// `record.original_path`'s own parent (the common "restore to where it came from" case), not
/// guaranteed for an arbitrary caller-chosen `destination_dir`. A cross-device destination
/// surfaces as a plain `io::Error` from the underlying `rename` (`EXDEV`), which this function
/// does not catch or paper over.
pub fn restore_with_plan(
    record: &TrashRecord,
    destination_dir: &Path,
    plan: &RestorePlan,
    now_unix_seconds: i64,
) -> io::Result<PathBuf> {
    let final_path = destination_dir.join(&plan.requested_name);
    if plan.replaces_existing {
        trash_at(&final_path, now_unix_seconds)?;
    }
    fs::rename(&record.files_path, &final_path)?;
    fs::remove_file(&record.info_path)?;
    Ok(final_path)
}

/// `RecycleBinService.uniqueName`, ported one-to-one: `"name (2).ext"`, `"name (3).ext"`, ...,
/// the first not already in `existing_names`.
fn unique_name(existing_names: &HashSet<String>, requested_name: &str) -> String {
    let (stem, extension) = split_extension(requested_name);
    let mut n = 2u64;
    loop {
        let candidate = format!("{stem} ({n}){extension}");
        if !existing_names.contains(&candidate) {
            return candidate;
        }
        n += 1;
    }
}

/// `requestedName.lastIndexOf('.')` split, ported field-for-field: a name whose only dot is its
/// first character (`".bashrc"`) is treated as having no extension at all, matching Kotlin's
/// own `dot > 0` (not `>= 0`) check.
fn split_extension(name: &str) -> (String, String) {
    match name.rfind('.') {
        Some(dot) if dot > 0 => (name[..dot].to_string(), name[dot..].to_string()),
        _ => (name.to_string(), String::new()),
    }
}

#[cfg(test)]
#[path = "trash_tests.rs"]
mod trash_tests;
