//! Whole-archive extraction into a caller-chosen directory, for the iOS document browser.
//!
//! The engine ([fylz_archive::extract]) never creates, names or closes a destination; this module
//! is the destination provider the iOS app needs. Three rules, none of which the archive controls:
//!
//! 1. **The archive never picks a location outside `dest_dir`.** Every entry path is re-joined
//!    component by component ([safe_relative]); `..`, an absolute path, a NUL and an empty path are
//!    refused here even though the engine's policy already refuses them (defence in depth: this is
//!    the last line before a filesystem write).
//! 2. **Nothing is overwritten and nothing is followed through a symlink.** Files are created with
//!    `create_new` (`O_EXCL`), and an existing path component that is a symlink is refused.
//! 3. **A failed extraction leaves nothing behind.** Every file and directory this call created is
//!    removed again (files first, then directories deepest-first, only if empty) when the pass
//!    returns an error. The engine itself never deletes, so the cleanup is this module's job.

use crate::FylzError;
use fylz_archive::ArchiveEntry;
use fylz_archive::ArchiveError;
use fylz_archive::DestinationProvider;
use fylz_archive::ExtractLimits;
use fylz_archive::Selection;
use std::fs::File;
use std::fs::OpenOptions;
use std::os::fd::AsFd;
use std::os::fd::BorrowedFd;
use std::path::Component;
use std::path::Path;
use std::path::PathBuf;

/// Turns an archive entry path into a relative path made only of normal components, or `None`
/// when it cannot be made safe. Backslashes count as separators (the policy's own normalisation),
/// `.` and empty components are dropped, anything else odd is refused.
pub(crate) fn safe_relative(entry_path: &str) -> Option<PathBuf> {
    if entry_path.contains('\0') {
        return None;
    }
    let unified = entry_path.replace('\\', "/");
    if unified.starts_with('/') {
        return None;
    }
    let mut out = PathBuf::new();
    for part in unified.split('/') {
        match part {
            "" | "." => {}
            ".." => return None,
            other => {
                // A drive-letter or other prefix has no meaning on Apple targets, but refusing a
                // non-`Normal` component keeps this function correct if it is ever built elsewhere.
                let mut components = Path::new(other).components();
                match (components.next(), components.next()) {
                    (Some(Component::Normal(_)), None) => out.push(other),
                    _ => return None,
                }
            }
        }
    }
    if out.as_os_str().is_empty() {
        None
    } else {
        Some(out)
    }
}

struct DirDestination<'a> {
    root: &'a Path,
    current: Option<File>,
    created_files: Vec<PathBuf>,
    created_dirs: Vec<PathBuf>,
    refusal: Option<FylzError>,
}

impl DirDestination<'_> {
    /// Creates `dir` and any missing parents under `root`, refusing a symlink at any existing step.
    fn ensure_dir(&mut self, relative: &Path) -> Result<PathBuf, FylzError> {
        let mut path = self.root.to_path_buf();
        for component in relative.components() {
            path.push(component);
            match std::fs::symlink_metadata(&path) {
                Ok(meta) if meta.file_type().is_symlink() => {
                    return Err(FylzError::UnsafePath {
                        path: relative.display().to_string(),
                    })
                }
                Ok(meta) if meta.is_dir() => {}
                Ok(_) => {
                    return Err(FylzError::DestinationExists {
                        path: path.display().to_string(),
                    })
                }
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                    std::fs::create_dir(&path).map_err(FylzError::from)?;
                    self.created_dirs.push(path.clone());
                }
                Err(e) => return Err(e.into()),
            }
        }
        Ok(path)
    }

    /// `Ok(true)` when a file was opened into `self.current`, `Ok(false)` for a directory.
    fn open_entry(&mut self, entry: &ArchiveEntry) -> Result<bool, FylzError> {
        let relative = safe_relative(&entry.path).ok_or_else(|| FylzError::UnsafePath {
            path: entry.path.clone(),
        })?;
        if entry.is_directory {
            self.ensure_dir(&relative)?;
            return Ok(false);
        }
        let parent = relative.parent().filter(|p| !p.as_os_str().is_empty());
        let parent_path = match parent {
            Some(parent) => self.ensure_dir(parent)?,
            None => self.root.to_path_buf(),
        };
        let file_name = relative
            .file_name()
            .expect("safe_relative yields a final component");
        let target = parent_path.join(file_name);
        let file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&target)
            .map_err(|e| {
                if e.kind() == std::io::ErrorKind::AlreadyExists {
                    FylzError::DestinationExists {
                        path: target.display().to_string(),
                    }
                } else {
                    FylzError::from(e)
                }
            })?;
        self.created_files.push(target);
        self.current = Some(file);
        Ok(true)
    }

    fn cleanup(&mut self) {
        self.current = None;
        for file in self.created_files.drain(..).rev() {
            let _ = std::fs::remove_file(file);
        }
        // Deepest first; `remove_dir` only removes an empty directory, so a directory that gained
        // content from somewhere else is left alone rather than deleted recursively.
        let mut dirs = std::mem::take(&mut self.created_dirs);
        dirs.sort_by_key(|d| std::cmp::Reverse(d.components().count()));
        for dir in dirs {
            let _ = std::fs::remove_dir(dir);
        }
    }
}

impl DestinationProvider for DirDestination<'_> {
    fn open(&mut self, entry: &ArchiveEntry) -> Result<Option<BorrowedFd<'_>>, ArchiveError> {
        // The engine only knows `ArchiveError`; our own refusal is stashed and surfaced by
        // `extract_all` so the caller sees `UnsafePath`/`DestinationExists`, not a generic failure.
        match self.open_entry(entry) {
            Ok(true) => Ok(self.current.as_ref().map(|f| f.as_fd())),
            Ok(false) => Ok(None),
            Err(error) => {
                let message = error.to_string();
                self.refusal = Some(error);
                Err(ArchiveError::Failed(message))
            }
        }
    }

    fn done(&mut self, _entry: &ArchiveEntry, _bytes_written: u64) -> Result<(), ArchiveError> {
        self.current = None;
        Ok(())
    }
}

/// What a successful [extract_all] wrote.
pub(crate) struct Extracted {
    pub entries_written: u64,
    pub bytes_written: u64,
    pub skipped_links: u64,
}

pub(crate) fn extract_all(
    archive: &File,
    dest_dir: &Path,
    limits: &ExtractLimits,
) -> Result<Extracted, FylzError> {
    use std::os::fd::AsRawFd;
    match std::fs::metadata(dest_dir) {
        Ok(meta) if meta.is_dir() => {}
        Ok(_) => {
            return Err(FylzError::DestinationExists {
                path: dest_dir.display().to_string(),
            })
        }
        Err(e) => return Err(e.into()),
    }
    let mut destination = DirDestination {
        root: dest_dir,
        current: None,
        created_files: Vec::new(),
        created_dirs: Vec::new(),
        refusal: None,
    };
    let result = fylz_archive::extract(
        archive.as_raw_fd(),
        &Selection::All,
        limits,
        &mut destination,
    );
    match result {
        Ok(report) => Ok(Extracted {
            entries_written: report.entries_written as u64,
            bytes_written: report.bytes_written,
            skipped_links: report.skipped_links as u64,
        }),
        Err(error) => {
            let refusal = destination.refusal.take();
            destination.cleanup();
            Err(refusal.unwrap_or_else(|| error.into()))
        }
    }
}
