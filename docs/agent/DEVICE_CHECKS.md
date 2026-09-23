# Device checks

Everything below was implemented and verified in this session's sandboxed container — Robolectric
(hosted, real `DocumentsProvider` instances) plus plain-JVM unit tests, `lintDebug`/`lintRelease`
and `assembleDebug`/`assembleRelease` — but the container has no real Android device, emulator,
removable storage, USB host, or reachable SFTP server. Per `FYLZ_CLAUDE_CODE_INSTRUCTIONS.md`
("never claim device behaviour you couldn't test"), every claim below is a manual check still
owed on a real device before Phase 0 can be called done in practice, not just in CI. This is the
Phase 0 exit minimum list; Phase 1 device checks (P1.10's rotate/background-survival budget,
P1.11's large-folder listing budget) will be appended here when those tasks land.

Each entry: what to do, what should happen, and which task it verifies.

## 1. Rotating mid-copy (P0.5)

**Steps:**
1. Start a copy or move of a large-enough folder (or file) that it takes several seconds.
2. While it's running, rotate the device to landscape, then back to portrait; separately, fold
   and unfold a foldable device (or resize the window in desktop mode).

**Expected:** the operation keeps running throughout — it's driven by `OperationRunner` on an
app-scoped `CoroutineScope`, independent of the observing UI's lifecycle, not by the screen that
started it. The progress bar (bytes and items) reattaches showing the operation's current true
progress after each configuration change, not reset to zero and not stuck. Cancel still works
after a rotation. As of P1.10 the Activity genuinely recreates on rotation (the interim
`android:configChanges` line on `MainActivity` is gone) — `OperationRunner`'s own progress
reattaching was never dependent on that line, so this check now also exercises the real
recreation path, not just a suppressed one.

## 1a. Rotating and backgrounding mid-task, with "Don't keep activities" (P1.10)

**Steps:**
1. In Developer Options, enable "Don't keep activities" (forces the Activity's process to be
   destroyed the moment it leaves the foreground — a stronger test than a plain rotation, which by
   itself might not destroy the process at all on some devices/API levels).
2. Open a few tabs across different storage roots, navigate a couple of folders deep in one of
   them, select several files (don't start a transfer), switch the sort order and view mode away
   from their defaults, and Cut or Copy a file.
3. Rotate the device. Separately (a fresh run of steps 1-2), background the app (Home button) and
   return to it.

**Expected:** every tab reopens at the exact folder it was on, not its root — `BrowserViewModel`
persisted each tab's full `locations` stack, not just its `treeUri`. The same tab that was active
before is active again. The selected files are still selected (multi-select survives; a single
open preview does not — see `BrowserViewModel`'s own KDoc on why `focusedEntry` is out of this
task's scope). Sort order and view mode are unchanged. The clipboard chip is gone after this kind
of recreation — see the same KDoc for why clipboard content specifically isn't part of the
persisted session yet. Cold-launching the app fresh afterward (force-stop it, then reopen from the
launcher) restores the same session too, via `SessionStore`'s SharedPreferences fallback, not just
`SavedStateHandle`'s own platform-level restore.

## 2. Folder copy, move and recycle on internal storage, SD and USB (P0.1, P0.2)

**Steps:**
1. Grant Fylz access to three separate roots: internal storage, a real SD card, and a real USB
   drive (OTG or USB-C).
2. On each root, copy a multi-level folder (with a few nested subfolders and files) to another
   location on the *same* root, then to a location on a *different* root (cross-volume).
3. Repeat as a move instead of a copy.
4. Recycle a folder tree on each root, then restore it.

**Expected:** every copy and move is byte-identical to the source (same file count, same bytes,
same relative structure) — this is what `DocNode`-based recursion and per-file `verifyFile`
already guarantee under the Robolectric-hosted provider; the device check is specifically whether
a *real* SD card's and a *real* USB drive's DocumentsProvider (frequently a different
implementation than Fylz's own, sometimes OEM-specific) behaves the same way through the same
`DocNode` calls. A move only deletes the source after every nested item verifies clean. A
recycled folder restores with its structure intact.

## 3. Restore conflicts (P0.2)

**Steps:**
1. Recycle a file or folder.
2. Create a new file or folder with the same name in the original location.
3. Restore the recycled item, and for each of the three conflict policies (Skip, Keep both,
   Replace) confirm the outcome.
4. Specifically for Replace: if possible, interrupt or deny a permission mid-restore to exercise
   the rollback path.

**Expected:** Skip leaves the existing item untouched and the recycled item stays recycled; Keep
both restores under a disambiguated name without touching the existing item; Replace swaps the
existing item for the restored one. A refused rename on the first (destructive) step of a Replace
leaves the record untouched and changes nothing on disk; a refused rename on the second step (the
actual replace) rolls the first step back, so the original item is never lost.

## 4. Dotfiles (P0.3)

**Steps:**
1. Create a new file or folder whose name starts with a dot, e.g. `.hidden-notes.txt` or
   `.config`.
2. Rename an existing (non-dotfile) item to a leading-dot name, and vice versa.

**Expected:** the leading dot is preserved exactly as typed — Fylz grants full filesystem access,
so a user-legitimate hidden dotfile is a name to keep, not to silently strip. (The pre-existing
production bug this fixed — `sanitizeDisplayName` stripping every leading dot — also silently
broke the recycle bin's own `.fylz-trash` folder lookup; confirm the recycle bin still works
normally as part of this check.)

## 5. SFTP connect (P0.11)

**Steps:**
1. Open the SFTP connection form and point it at a real SFTP server.
2. Use "Detect fingerprint" to connect once (no credentials) and capture the server's host key
   fingerprint.
3. Independently obtain that same server's fingerprint via `ssh-keygen -lf` (or equivalent) and
   compare.
4. Confirm the detected fingerprint in the dialog, then connect with real credentials.
5. Reconnect later and confirm the pinned fingerprint is honored (a changed host key should be
   rejected).

**Expected:** the fingerprint Fylz detects matches the independently-obtained one exactly. The
pinned connection succeeds. If key exchange fails with an algorithm-negotiation error, register
the bundled BouncyCastle provider at startup
(`Security.removeProvider("BC"); Security.insertProviderAt(BouncyCastleProvider(), 1)`) per the
brief, and note here whether that was needed for the tested server.

## 6. "Open with Fylz" (P0.12)

**Steps:**
1. From another app (a file browser, an email client, a share sheet), choose "Open with Fylz" (or
   share/send to Fylz) for a document, while Fylz is **not** already running.
2. With Fylz still running from step 1, repeat with a **different** document from another app.
3. Repeat step 1 for a few different file types across the format families `PreviewPane` renders
   (e.g. an image, a PDF, a text file).

**Expected:** step 1 launches Fylz directly into a full-size `ExternalDocumentDialog` previewing
that exact document (via `onCreate` reading the `ACTION_VIEW` intent's data) — not the normal home
screen. Step 2 updates the same dialog to the new document without restarting Fylz (via
`onNewIntent`, since `MainActivity` is `singleTask`). Share and Save-a-copy-to both work from the
dialog for every format tried in step 3.

## 7. Launcher shortcuts (P0.12)

**Steps:**
1. Long-press the Fylz launcher icon.
2. Tap each of the three shortcuts in turn.

**Expected:** all three still open their target activity/screen, despite `PostV1ToolsActivity`,
`IndexManagerActivity` and `OperationHistoryActivity` now being `exported="false"` — a shortcut
this app itself publishes with an explicit `targetClass` is allowed to start a non-exported
activity of the same app; this check exists because that's a real-device launcher behavior, not
something Robolectric models.

## 8. Permanent delete (P0.8)

**Steps:**
1. Recycle a couple of items, open the Recycle Bin, and permanently delete a single item.
2. Recycle a few more items, then use "Empty Recycle Bin".

**Expected:** both actions require typing `DELETE PERMANENTLY` in the confirmation dialog, which
states the correct item count and known total size (and handles an item with unknown size
gracefully). The Recycle Bin list updates immediately after either action with no manual
refresh needed (this was the P0.8 `StateFlow` fix — confirm it's not just a Robolectric artifact).
Emptying reports how many items succeeded and leaves the recycle bin folder itself clean.

## 9. Transfer throughput (P1.3)

**Steps:**
1. Call `TransferBenchmark.run(primaryVolumeDir = <a real writable dir on internal storage>,
   secondaryVolumeDir = <a real writable dir on an SD card or USB drive, or null to skip that
   scenario>)` from a debug build (there is no UI trigger for this yet — see the P1.3 progress
   note) and read `Report.summary()`.
2. Separately, on the same device, time `cp` on the same 4 GB file and the same 10,000×4 KB tree
   (e.g. `time cp bigfile.bin copy.bin`, `time cp -r manyfiles/ copy/`).

**Expected:** the same-volume 4 GB scenario's `megabytesPerSecond` is at least 90% of `cp`'s own
throughput for the same file on the same device — the brief's own target, and the reason this is a
device check rather than an automated one: the Robolectric sandbox this task was built in has no
real disk to measure, only `TransferBenchmarkTest`'s proof that the scenarios themselves run
correctly at a small scale. Also confirm the cross-volume and many-small-files numbers are
directionally reasonable (cross-volume slower than same-volume; many small files bottlenecked by
per-file overhead rather than raw throughput), and note all three numbers here once measured.
