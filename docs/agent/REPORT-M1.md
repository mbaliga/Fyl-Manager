# REPORT-M1: Finish the engine (Phase 1 remainder)

Per `docs/agent/MASTER_PLAN.md` §3.5. Covers M1's seven tasks, P1.8–P1.14
(`docs/agent/MASTER_PLAN.md` §5). Full per-task detail lives in `docs/agent/PROGRESS.md`'s task
table; this report summarises rather than repeats it. GATE-M1 (Madhav runs the device checks) is a
review gate under `docs/agent/MASTER_PLAN_ADDENDUM_1.md` §A.1 — this report, the device-check
additions and `docs/agent/REVIEW_QUEUE.md`'s GATE-M1 entry are what that policy calls for; the run
continued past it into M2 and M3.1 rather than stopping.

## 1. Status

**Verification level: Robolectric (hosted, real `DocumentsProvider` instances) plus plain-JVM unit
tests, `lintDebug`/`lintRelease` and `assembleDebug`/`assembleRelease` — no real Android device,
emulator, removable storage, USB host, or reachable SFTP server was available in this run's
container.** All seven tasks (P1.8–P1.14) are done and verified at that level: `testDebugUnitTest`
grew from 341/341 at the end of P1.7 to 385/385 after P1.14, with `lintDebug`/`lintRelease` clean
and `assembleDebug`/`assembleRelease` succeeding after every task. Every real, previously-latent
bug this milestone's own new tests found (a destination-URI resolution bug in P1.8, two separate
`DocumentsProvider` classic-query-overload traps in P1.11 and P1.12, a degrees-vs-quarter-turns
unit mismatch in P1.13) was fixed before its task's commit, not deferred. What this milestone
cannot itself confirm — real removable-storage/USB behaviour, a real 100,000-file listing's actual
timing, a real SQLite build's FTS5-vs-FTS4 choice, a real PDF render's rotation correctness — is
exactly GATE-M1's own remaining scope, tracked in §4 below and in `docs/agent/DEVICE_CHECKS.md`.

## 2. Tasks

| ID | Status | Commit | Tests | Notes |
|---|---|---|---|---|
| P1.8 | done, verified | `8aa1496` | New cases in `FileOperationServiceTest`, `ConflictDetectionTest`, `FylzV1AppLogicTest`; suite 350/350 | Clipboard (Cut/Copy/Paste, a top-bar chip) and an in-app `DestinationChooserSheet` (tabs, storage roots, "Other location…" falling back to the system picker) replace jumping straight to `OpenDocumentTree` for everyday copy/move. Found and fixed a real correctness bug while wiring it: `transfer()`/`findConflicts()`/`runPreflight()` all unconditionally rebuilt their destination as a tree's root, silently redirecting a paste/chooser pick at an already-resolved nested folder back to the tree's root. |
| P1.9 | done, verified | `a8cf712` | New cases in `FylzFilesDocumentsProviderSmokeTest`; suite 352/352 | `queryRoots()` now omits `FLAG_SUPPORTS_IS_CHILD` for any caller that isn't Fylz's own process; single-document access (open/read/write) is unaffected. Documented in `docs/ARCHITECTURE.md`. |
| P1.10 | done, verified | `dbf4f71` | New `SessionCodecTest` (7), `SessionStoreTest` (3); suite 359/359 | New `BrowserViewModel` (`AndroidViewModel` + `SavedStateHandle`) owns tabs, selection, sort/view/preview/search and clipboard, replacing `remember{}` state torn down on rotation/fold/process death; a `SessionStore` SharedPreferences fallback covers a genuinely cold launch. The interim `android:configChanges` line is removed. Found and fixed a real bug before shipping: an unconditional selection-reset effect would have wiped a just-restored selection on its first post-restore run. |
| P1.11 | done, verified | `aef894f` | New `DocumentRepositoryListChildrenTest` (3), `OperationJournalStateFlowTest` (4); suite 366/366 | `DocumentRepository.listChildren` streams batches (500, then 5,000) instead of blocking for the whole cursor; sorting/filtering moved off the main thread. Provider `setNotificationUri`/`notifyChange` plus a `FileObserver` on the visible File-backed folder replace pull-to-refresh for local changes. Six more main-thread I/O sites (journal polling, `LibraryStore` writes, File history/Backup overlays, `BackupScheduler.reconcile()`) moved to `Dispatchers.IO`/`.apply()`. Found and fixed a real pre-existing bug: `listChildren` used the classic `ContentResolver.query` overload, which a real `DocumentsProvider` throws on. |
| P1.12 | done, verified | `329bcfd` | New `FylzDatabaseIndexMigrationTest`, `LocalIndexStoreTest`, `IndexDaoTest`, `ShouldRescanTest`, `RecursiveSearchEngineIndexTest`; suite 378/378 | One SQLite-backed index (`IndexDao`, an FTS4-or-FTS5-probed virtual table) replaces four independent JSON files and five now-redundant indexer implementations (deleted, grep-reconfirmed first). Closes two real gaps (`IndexedFile.path`/`textSnippet` were previously unreadable). Search answers from the index when a scope is covered, reusing the live walk's own match predicates for name/metadata parity. Found and fixed two real bugs: a document-vs-tree URI shape mismatch in indexed search, and a second instance of P1.11's classic-query-overload trap in the live-walk fallback. |
| P1.13 | done, verified | `d951f1c` | New `PageDrawMatrixTest` (7); suite 385/385 | Three dead files deleted (grep-reconfirmed zero callers first): `SmbService`, `ArchiveBrowserService`, `ScanPdfService`. `PdfToolService` consolidated into the DPI-aware `PdfPageTools`, gaining `PdfToolService`'s one capability it lacked (searchable-OCR export) via a new `pageDrawMatrix()` that makes OCR-and-rotation correct by construction rather than rotation-specific text math. Caught before shipping: the rewired call site would have crashed on any non-zero rotation (degrees passed where quarter-turns were required). |
| P1.14 | done | `f15a314` | None (docs-only); markdown code-fence balance checked | `README.md`, `docs/ARCHITECTURE.md` and `CHANGELOG.md` updated to match Phase 1 reality, including correcting a real documentation-vs-code gap (README's "a picker is planned" line, when the picker had already shipped in P1.6) and adding sections for P1.12's index and P1.13's PDF tools that had no prior mention despite being live. |

## 3. Deviations

- **Branch name substitution.** The master plan's own §2 (via `FYLZ_CLAUDE_CODE_INSTRUCTIONS.md`)
  names `agent/fylz-p0-p1`; this run's harness assigns the fixed shared branch
  `claude/fylz-fotoz-complete-y60pfw` across several repos, so every commit here lands there
  instead. Naming only — every other workflow rule (one commit per task, draft PR, never merge to
  `main`) was followed as written. Recorded in `docs/agent/PROGRESS.md`'s own Deviations section
  before this milestone began.
- **JDK 17 → 21.** JDK 17 is not installed in this session's container; JDK 21 is used instead,
  confirmed compatible for the full Gradle configuration phase and every task's own build/test
  run throughout M1. No task-specific deviation beyond this standing one.
- **No new deviation was introduced by M1 itself.** Every "found and fixed" item named in §2 above
  was a genuine pre-existing bug or a bug introduced earlier in the same task's own change, caught
  and fixed within that task's commit — not a deviation from the master plan's stated scope, and
  each is described in full in its own `docs/agent/PROGRESS.md` row rather than summarised again
  here as a deviation.

## 4. Unverified items

Every device-only claim below has a corresponding entry in `docs/agent/DEVICE_CHECKS.md`, cited by
its section number and heading:

- **§1a — "Rotating and backgrounding mid-task, with 'Don't keep activities' (P1.10)."** Tab/
  selection/sort/view state surviving a real Activity recreation and a real process kill (not just
  Robolectric's single-JVM approximation of either) is unverified.
- **§5 — "SFTP connect (P0.11)."** Carried over from Phase 0, still open; not itself an M1 task,
  but P1.11's `FileObserver`/change-notification work does not extend to network mounts, so this
  gap is unchanged by M1.
- **§6 — "'Open with Fylz' (P0.12)."** and **§7 — "Launcher shortcuts (P0.12)."** Both carried over
  from Phase 0, still open; unaffected by M1's own tasks.
- **§10 — "Large-folder listing budget, external-change notifications and off-main-thread I/O
  (P1.11)."** The 300 ms first-rows / 2 s full-listing budget for a 100,000-entry folder, a real
  SD-card/USB external change being picked up by `FileObserver`, and the four Storage & recovery
  room dialogs staying responsive mid-load are all unverified on a real device.
- **§11 — "Local index: FTS5 probe, MediaStore-generation skip, and search fast path (P1.12)."**
  Which SQLite FTS module a real device's build actually picks, whether the internal-storage
  generation-skip and the SD/USB always-rescan behave as designed on real media, and the indexed
  search's real speed advantage over a live walk at ~50,000 files are all unverified.
- **§12 — "PDF tools: rotation and searchable OCR (P1.13)."** `pageDrawMatrix`'s correctness was
  proven only against matrix math in a unit test; whether a real rendered page is actually
  right-side-up at every rotation, and whether OCR-recognised text actually selects/searches at the
  correct on-screen position, is unverified.
- **§13 — "fylz-core native library: 16 KB page-size device install (M2.3)."** Not an M1 task, but
  listed here because M1's own P1.9/P1.10 changes ship in the same APK this check installs; no new
  M1-specific behaviour is added to this check.

No M1 task introduced a device-only claim outside the list above; P1.8's clipboard/destination
chooser and P1.14's documentation changes are both fully covered by the Robolectric/plain-JVM gate
with no device-only component.

## 5. Numbers

**M1 is Kotlin-only — no `core/` Rust code was touched by P1.8–P1.14 — so no APK size delta was
measured for this milestone.** (The APK size question belongs to GATE-M2, whose native `.so`
additions are the first thing in this branch's history to actually change per-ABI weight; see
`docs/agent/REPORT-M2.md` §5.) No performance benchmark was run or budget set for M1 beyond the one
the master plan itself names: **P1.11's 100,000-entry folder listing budget** (first rows within
~300 ms, full listing within ~2 s) — this is a device check (`docs/agent/DEVICE_CHECKS.md` §10),
not a number this run's sandboxed container could produce, since it has no real disk or SD/USB
media to measure against. `testDebugUnitTest`'s own count is tracked per task in §2 above and in
`docs/agent/PROGRESS.md`, but a passing unit-test count is not a performance number and is not
reported as one here.

## 6. Licences added

`git diff 5fc9b21 f15a314 -- app/build.gradle.kts gradle/ settings.gradle.kts` (`5fc9b21` = P1.7,
the master plan's own stated M1 baseline; `f15a314` = P1.14, M1's last commit) shows exactly one
new dependency added across all of P1.8–P1.14, and no changes to `gradle/` or
`settings.gradle.kts`:

- **`androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7`** (P1.10, for `BrowserViewModel` and the
  `viewModel()` composable factory) — **Apache License, Version 2.0**, verified directly from this
  version's own POM in the local Gradle cache
  (`~/.gradle/caches/modules-2/files-2.1/androidx.lifecycle/lifecycle-viewmodel-compose/2.8.7/.../lifecycle-viewmodel-compose-2.8.7.pom`,
  `<licenses><license><name>The Apache Software License, Version 2.0</name>`). Already covered by
  `THIRD_PARTY_NOTICES.md`'s existing "AndroidX ... Lifecycle" line under the Apache License 2.0
  families section — no new `THIRD_PARTY_NOTICES.md` entry was needed, since it names the AndroidX
  Lifecycle family generically rather than per-artifact.

No other M1 task changed `app/build.gradle.kts`'s dependency block, added a new Gradle plugin, or
touched `gradle/`/`settings.gradle.kts`.

## 7. Questions for Madhav

- None specific to M1's own scope. The one open question this milestone's work originally raised —
  whether continuing past GATE-M1/GATE-M2 into M3 was authorised — was resolved by
  `docs/agent/MASTER_PLAN_ADDENDUM_1.md` §A.1's gate-policy change rather than a direct answer; see
  `docs/agent/PROGRESS.md`'s Deviations section and `docs/agent/REVIEW_QUEUE.md`'s GATE-M1 entry
  for what still needs your own review pass (the device checks in §4 above), which this report
  does not attempt to substitute for.
