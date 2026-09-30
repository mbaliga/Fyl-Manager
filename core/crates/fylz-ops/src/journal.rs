//! Ported from `app/src/main/java/io/github/mbaliga/fylz/operations/OperationJournal.kt`: not
//! its SQLite-backed `OperationsDao` (an Android/SAF-shaped persistence detail this crate does
//! not port, along with the M3-specific extract/create-plan bookkeeping layered on it), but its
//! *lifecycle* -- the plan-in-the-journal claim/tag/reconcile pattern this crate's brief singles
//! out as the foundational piece M3 itself was built on:
//!
//! - **claim**: [`Journal::claim`] atomically moves an operation from one of a set of expected
//!   states to a new one, succeeding only if it was still in one of them -- the mechanism a
//!   worker uses to claim the right to run an operation exactly once, even under concurrent
//!   callers, by making the state transition itself the point of mutual exclusion (Kotlin's
//!   `claimExtract`/`claimCreate`/`updateOperationStateIf`, generalised here to one function
//!   over any state set rather than three near-duplicates for extract, create and everything
//!   else).
//! - **tag**: [`Journal::update_item`] rewrites one item in place (Kotlin's `updateItem`), and
//!   [`Journal::set_cancel_requested`]/[`Journal::is_cancel_requested`] tag an operation a
//!   worker should poll to stop early.
//! - **reconcile**: [`Journal::open`] runs [`crate::models::RecoveryPolicy::recover_after_process_death`]
//!   over every operation still on disk, exactly once per real process (Kotlin's
//!   `recoverFromPriorProcessIfNeeded`, using the same "compare a process-wide session marker,
//!   stored alongside the data, against this process's own" mechanism so that constructing a
//!   *second* [`Journal`] over the same store in the same process never re-flags work the first
//!   instance is legitimately still running as if a process had died).
//!
//! **Storage is genuinely new, not a port.** Kotlin's `OperationsDao` is plain SQLite through
//! `FylzDatabase`; this crate has no Android `Context` to host that, and pulling in `rusqlite`
//! for a single small table this early would be a guess about M8's own eventual schema needs.
//! Instead each [`Journal`] is one JSON document, written with a temp-file-then-rename so a
//! reader never observes a half-written file -- the same staged-write shape [`crate::staging`]
//! already uses for a copy's own destination file, just applied to the journal's own storage.
//! `OperationJournalStateFlowTest.kt` and `OperationJournalConcurrencyTest.kt` are this module's
//! parity oracle for the lifecycle guarantees they exercise (`journal_tests.rs`): every mutator
//! makes the change visible to this same instance's own cached view immediately, a second
//! instance over the same store does not see the first's write until it also mutates or is told
//! to refresh, and concurrent callers on one instance never corrupt or drop a record.

use std::collections::HashMap;
use std::collections::HashSet;
use std::fs;
use std::io;
use std::path::Path;
use std::path::PathBuf;
use std::sync::Mutex;
use std::sync::OnceLock;

use serde::Deserialize;
use serde::Serialize;
use uuid::Uuid;

use crate::models::FileOperation;
use crate::models::OperationItem;
use crate::models::OperationState;
use crate::models::RecoveryPolicy;

#[derive(Debug, Default, Serialize, Deserialize)]
struct DiskState {
    /// Which process last ran [`Journal::open`]'s reconcile sweep over this store; compared
    /// against [`process_session_id`] so a second [`Journal`] opened in the *same* process does
    /// not run it again.
    session: Option<String>,
    operations: HashMap<String, FileOperation>,
}

/// One random id per real process, generated the first time it's asked for and shared by every
/// [`Journal`] in it -- the Rust equivalent of the Kotlin object's own `val PROCESS_SESSION_ID =
/// UUID.randomUUID()`, computed once at class-load time.
fn process_session_id() -> &'static str {
    static SESSION: OnceLock<String> = OnceLock::new();
    SESSION.get_or_init(|| Uuid::new_v4().to_string())
}

fn read_disk(path: &Path) -> DiskState {
    fs::read_to_string(path)
        .ok()
        .and_then(|text| serde_json::from_str(&text).ok())
        .unwrap_or_default()
}

fn write_disk(path: &Path, state: &DiskState) -> io::Result<()> {
    let json = serde_json::to_string_pretty(state).expect("a DiskState always serializes");
    // Written under a name nothing else looks for, then renamed into place: a POSIX rename is
    // atomic, so a concurrent reader of `path` always sees either the old, complete file or the
    // new, complete one, never a half-written one. The random suffix keeps two journals whose
    // callers happen to write at the same instant from ever colliding on the same temp name.
    let tmp_path = path.with_extension(format!("tmp-{}", Uuid::new_v4()));
    fs::write(&tmp_path, json)?;
    fs::rename(&tmp_path, path)
}

/// A small durable journal for user-visible file operations, backed by one JSON document on
/// disk. See this module's own doc comment for the claim/tag/reconcile lifecycle it implements
/// and why its storage is new rather than ported.
///
/// Two [`Journal`] instances over the same path each keep their own, independent
/// [`Journal::operations`] cache -- a write through one is invisible to the other's cache until
/// IT also mutates or calls [`Journal::refresh`]. A caller that wants a shared, live view must
/// construct exactly one instance and share it, rather than letting each collaborator
/// default-construct its own.
pub struct Journal {
    path: PathBuf,
    cache: Mutex<DiskState>,
}

impl Journal {
    /// Opens (creating if absent) the journal stored at `path`, then reconciles it: any
    /// operation left in [`OperationState::Preflight`], [`OperationState::Running`] or
    /// [`OperationState::Paused`] is moved to [`OperationState::NeedsAttention`] -- but only the
    /// first time this real process opens this particular path; a second [`Journal::open`] on
    /// the same path in the same process is a no-op reconcile, since whatever is `Running` now
    /// may well be this same process legitimately still running it.
    pub fn open(path: impl Into<PathBuf>, now_millis: i64) -> io::Result<Self> {
        let path = path.into();
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
        }
        let mut disk = read_disk(&path);
        if disk.session.as_deref() != Some(process_session_id()) {
            for operation in disk.operations.values_mut() {
                *operation = RecoveryPolicy::recover_after_process_death(operation, now_millis);
            }
            disk.session = Some(process_session_id().to_string());
            write_disk(&path, &disk)?;
        }
        Ok(Self {
            path,
            cache: Mutex::new(disk),
        })
    }

    /// A one-off, always-fresh read straight from disk -- the equivalent of Kotlin's `list()`,
    /// which reads `OperationsDao` directly rather than through the cached `StateFlow`.
    pub fn list(&self) -> Vec<FileOperation> {
        read_disk(&self.path).operations.into_values().collect()
    }

    /// This instance's own cached view, refreshed by every mutator it has made (or after an
    /// explicit [`Journal::refresh`]) -- the equivalent of Kotlin's `operations.value`.
    pub fn operations(&self) -> Vec<FileOperation> {
        self.cache
            .lock()
            .unwrap()
            .operations
            .values()
            .cloned()
            .collect()
    }

    pub fn find(&self, id: &str) -> Option<FileOperation> {
        read_disk(&self.path).operations.get(id).cloned()
    }

    pub fn put(&self, operation: FileOperation) {
        self.mutate(|operations| {
            operations.insert(operation.id.clone(), operation);
        });
    }

    pub fn remove(&self, id: &str) {
        self.mutate(|operations| {
            operations.remove(id);
        });
    }

    /// Removes every operation in a terminal state ([`RecoveryPolicy::is_terminal`]), keeping
    /// in-flight records visible until the user explicitly resolves or dismisses them.
    pub fn clear_finished(&self) {
        self.mutate(|operations| {
            operations.retain(|_, operation| !RecoveryPolicy::is_terminal(operation.state));
        });
    }

    /// The claim half of the lifecycle: moves `id` from one of `from` to `to` and returns
    /// whether that happened. The read-check-write happens under this instance's own lock, so
    /// concurrent callers on the SAME instance never both observe an eligible state and both
    /// "win" the claim; only a genuine winner's write reaches disk.
    pub fn claim(
        &self,
        id: &str,
        from: &HashSet<OperationState>,
        to: OperationState,
        now_millis: i64,
    ) -> bool {
        let mut cache = self.cache.lock().unwrap();
        let mut disk = read_disk(&self.path);
        let claimed = match disk.operations.get_mut(id) {
            Some(operation) if from.contains(&operation.state) => {
                operation.state = to;
                operation.updated_at_millis = now_millis;
                true
            }
            _ => false,
        };
        if claimed {
            write_disk(&self.path, &disk).expect("persist the operation journal");
            *cache = disk;
        }
        claimed
    }

    pub fn update_operation_state(&self, id: &str, state: OperationState, now_millis: i64) {
        self.mutate(|operations| {
            if let Some(operation) = operations.get_mut(id) {
                operation.state = state;
                operation.updated_at_millis = now_millis;
            }
        });
    }

    /// The tag half: rewrites one item in place. `refresh = false` leaves
    /// [`Journal::operations`] stale until the next refreshing write or an explicit
    /// [`Journal::refresh`] -- a high-frequency progress writer's per-item journal write would
    /// otherwise reload every operation on each one; it can throttle its own refresh instead.
    pub fn update_item(
        &self,
        operation_id: &str,
        item: OperationItem,
        now_millis: i64,
        refresh: bool,
    ) {
        let mut cache = self.cache.lock().unwrap();
        let mut disk = read_disk(&self.path);
        if let Some(operation) = disk.operations.get_mut(operation_id) {
            match operation
                .items
                .iter_mut()
                .find(|existing| existing.id == item.id)
            {
                Some(existing) => *existing = item,
                None => operation.items.push(item),
            }
            operation.updated_at_millis = now_millis;
        }
        write_disk(&self.path, &disk).expect("persist the operation journal");
        if refresh {
            *cache = disk;
        }
    }

    /// Republishes [`Journal::operations`] from disk -- for a caller that wrote with
    /// `refresh = false` and now wants to catch up.
    pub fn refresh(&self) {
        *self.cache.lock().unwrap() = read_disk(&self.path);
    }

    pub fn set_cancel_requested(&self, id: &str) {
        self.mutate(|operations| {
            if let Some(operation) = operations.get_mut(id) {
                operation.cancel_requested = true;
            }
        });
    }

    pub fn is_cancel_requested(&self, id: &str) -> bool {
        read_disk(&self.path)
            .operations
            .get(id)
            .map(|operation| operation.cancel_requested)
            .unwrap_or(false)
    }

    /// Reads the freshest disk state, applies `f`, persists it, and republishes the cache --
    /// the shared shape behind every unconditional mutator above.
    fn mutate(&self, f: impl FnOnce(&mut HashMap<String, FileOperation>)) {
        let mut cache = self.cache.lock().unwrap();
        let mut disk = read_disk(&self.path);
        f(&mut disk.operations);
        write_disk(&self.path, &disk).expect("persist the operation journal");
        *cache = disk;
    }
}

#[cfg(test)]
#[path = "journal_tests.rs"]
mod journal_tests;
