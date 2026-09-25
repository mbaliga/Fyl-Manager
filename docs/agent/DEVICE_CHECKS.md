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

## 10. Large-folder listing budget, external-change notifications and off-main-thread I/O (P1.11)

**Steps:**
1. On a real device, create (or copy in) a single folder containing 100,000 files on internal
   storage, and open it as a Fylz tab.
2. While that folder is open, use another app (or `adb shell` / a second device writing to the same
   SD card) to create, delete, and rename a file inside it, without touching Fylz.
3. Force a slow first paint honestly: cold-launch the app straight into that folder (a fresh tab,
   not a warm one already cached in `entries`), and watch the wall-clock time to the first rows
   appearing versus the whole folder finishing.
4. With "Don't keep activities" still enabled (see check 1a), open the Storage & recovery room's
   File history and Backup plans cards mid-way through some real work (a big copy running, a folder
   still loading) and exercise their dialogs: change file-history settings, restore an old version,
   delete a snapshot, add/edit/delete a backup plan, run a backup, restore a backup snapshot.

**Expected:** the 100,000-entry folder shows its first rows within roughly 300 ms and finishes
listing the rest within roughly 2 seconds — the master plan's own budget for `P1.11`'s paged
listing (`DocumentRepository.listChildren`'s `INITIAL_BATCH_SIZE`/`SUBSEQUENT_BATCH_SIZE` streaming,
`FylzV1App`'s `visibleEntries` sort moved off the main thread via `Dispatchers.Default`). The app
stays responsive (scrolling, tapping other UI) while that folder is still streaming in, not frozen
until the whole cursor walk finishes. The external create/delete/rename from step 2 shows up in the
open tab's listing on its own, with no manual pull-to-refresh — `FylzFilesDocumentsProvider`'s new
`notifyChange`/`setNotificationUri` calls plus `FylzV1App`'s new `FileObserver` on the visible
File-backed folder. (A tab browsing a foreign SAF provider, or a `content://` root this app's own
provider doesn't serve, has no real filesystem path to watch and won't pick up an external change
this way — pull-to-refresh is still how those notice one, unchanged from before this task.) Step 4's
dialogs never show a frozen/janky first frame and never block whatever else is running concurrently
— File history's and Backup's own store reads/writes (`FileHistoryStore`/`BackupStore`, both plain
SharedPreferences-plus-JSON-file I/O, never `suspend`-marked themselves) now only ever run from
inside a launched coroutine dispatched to `Dispatchers.IO`, never directly on a composition or a
click handler; `LibraryStore`'s seven SharedPreferences writes moved from blocking `.commit()` to
async `.apply()` (all but `importJson`, which still needs the synchronous durability guarantee) —
confirm a rapid sequence of favorite/tag toggles doesn't visibly stutter the UI thread the way it
could before. `BackupScheduler.reconcile()` moving off `MainActivity.onCreate`'s pre-`setContent`
path onto `lifecycleScope.launch(Dispatchers.IO)` should be invisible — scheduled backups still get
(re)armed correctly after a cold launch; confirm by checking a plan's next scheduled run is still
correct after a fresh install-and-launch.

## 11. Local index: FTS5 probe, MediaStore-generation skip, and search fast path (P1.12)

**Steps:**
1. In the Tools screen, add a folder on internal storage as an indexed scope and run "Rebuild".
   Once it completes, browse into that same folder in the file browser and search for a term that
   matches several file names, then a `content:`/quoted-phrase term that only appears inside one
   text file's contents.
2. While the index is still built from step 1, edit a file inside the indexed folder from another
   app (or `adb shell`), then trigger another rebuild (Rebuild button, or wait for the periodic
   one) without touching Fylz's own UI first.
3. Add a second scope on a real SD card or USB drive and rebuild.
4. Build a folder with roughly 50,000+ files on internal storage, index it, then search it and time
   how long results take to appear compared to searching an equivalent un-indexed folder.

**Expected:** step 1's name search returns the same files an un-indexed folder's live walk would
(same substring semantics); the content search returns the file whose text was actually captured
at index time, with a highlighted snippet, and does NOT need the file's live bytes read again.
Check `adb shell run-as <package> sqlite3 files/databases/fylz.db ".schema index_files_fts"` (or
equivalent) once to confirm which module (`fts5` vs `fts4`) this device's SQLite build actually
picked — `FylzDatabase.supportsFts5`'s probe is exercised for real here, not just in Robolectric,
where FTS5 availability may differ. Step 2's edited file is picked up by the next rebuild (the
internal-storage scope's `MediaStore.getGeneration` check must see the volume's generation change
and do a real rescan, not skip it) — confirm by searching for content unique to the edit. Step 3's
SD-card/USB scope always rescans on every rebuild regardless of whether anything changed (no
generation tracking applies to a non-primary volume) — confirm by checking `lastMediaStoreGeneration`
stays null for that scope's row across rebuilds (or simply that Rebuild always takes roughly the
same time for it, never a near-instant skip). Step 4's indexed search should return results
near-instantly (a plain indexed SQL query, no SAF walk), versus the live walk's own visibly slower,
progressively-emitted results for the equivalent un-indexed folder.

## 12. PDF tools: rotation and searchable OCR (P1.13)

Neither `PdfPageTools` nor the `PdfToolService` it replaced this task had ever had a device check
before now, despite the PDF tools dialog being live UI since before this session -- there is no
prior baseline to regress against, only a first-ever confirmation that the feature actually works.

**Steps:**
1. Open a multi-page PDF, extract a range of pages with no rotation, and open the result.
2. Repeat, rotating the extracted pages 90°, then separately 180°, then 270°, checking each
   output.
3. Select two or more PDFs and Merge them; check page order matches selection order.
4. Repeat steps 1-3 with "Make searchable (OCR)" on, then use the output PDF viewer's own
   text-selection or search feature to select/search for text visible on a page.

**Expected:** every rotation in step 2 shows the page right-side-up, not sideways, upside down, or
mirrored -- this specifically exercises `pageDrawMatrix` (new this task; unit-tested for its matrix
math in `PageDrawMatrixTest`, but never rendered to a real page before). Step 4's recognized text
selects/searches at roughly the same on-screen position as the visible (rasterized) text beneath
it, for every rotation, not just 0° -- this is the one thing `PageDrawMatrixTest` cannot itself
prove, since it never draws a real bitmap or invisible text layer, only the matrix each of those
draws through.

## 13. fylz-core native library: 16 KB page-size device install (M2.3)

Nothing in the app calls into `fylz-core` yet (M2.2's `FylzCore.version()`/`sniffFile()` have no
call site until M2.4+), so there is no *behaviour* to check here today -- only whether the APK
itself installs and runs at all on a device whose page size the emulator/JVM gate cannot represent.
`check16KbPageAlignment` (this task) confirms the `.so` files' own ELF layout is 16 KB-aligned via
`llvm-readelf`, a static check; it cannot confirm the *loader* actually accepts them, since that is
kernel behaviour no unit test or `assembleDebug` run touches.

**Steps:**
1. Install a debug build on a real 16 KB page-size device or the equivalent AVD image (Android
   15+, e.g. a Pixel 8/9 with the 16 KB developer option enabled, or a "believed" 16 KB emulator
   target).
2. Launch the app and confirm it starts normally -- a page-size mismatch fails at
   `System.loadLibrary`/process start with `dlopen failed`, not at any later, harder-to-attribute
   point.
3. Repeat on an ordinary 4 KB-page-size device, to confirm nothing about the 16 KB alignment
   itself broke the (much more common, today) 4 KB case.

**Expected:** the app launches cleanly on both. This check becomes meaningful earnest device
behaviour, not just an install smoke test, from M2.4 on, once `DecoderService` and later crates
give the native library actual work to do -- add the specific behaviour to check for at that point
rather than expanding this section speculatively now.

## 14. Isolated decoder process: real crash and timeout behaviour (M2.4)

`DecoderClientTest` covers `DecoderClient`'s own retry/timeout/crash-recovery state machine
against a fake `IDecoderService.Stub`, entirely within one JVM. It cannot exercise what section
4.4 is actually FOR: a real separate OS process, a real kill, a real crash that must not reach the
UI process. Robolectric runs every "process" as one JVM, so none of that is provable there.

**Steps:**
1. With the app running, find `DecoderService`'s PID (`adb shell ps -A | grep decoders`, looking
   for `io.github.mbaliga.fylz:decoders`) and confirm it is a distinct process from the main app.
2. Kill it directly (`adb shell kill -9 <pid>`) while the app is idle, then trigger any action that
   calls `DecoderClient` (nothing does yet -- this becomes exercisable once a real caller lands,
   M2.5+). Confirm the app does not crash or ANR, and that the next `DecoderClient` call reconnects
   (a fresh PID appears under `:decoders`).
3. Confirm `DecoderService`'s process shows no permissions in `adb shell dumpsys package
   io.github.mbaliga.fylz` beyond what an isolated process always has (none), and that
   `MANAGE_EXTERNAL_STORAGE`/broad filesystem access, granted to the main process, is NOT usable
   from `:decoders` -- an isolated process cannot inherit the app's own storage permission.
4. Once a real slow/hostile-input path exists to exercise it, confirm a call that genuinely hangs
   past its timeout is abandoned client-side without the UI freezing, and that the abandoned
   `:decoders` process is eventually killed by the platform once nothing is bound to it.

**Expected:** the app is never the process that shows a crash dialog or ANR for a `:decoders`
failure; `:decoders` is free to die and come back, invisibly to the user beyond that one request
failing softly (a file marked "can't preview" rather than a spinner that never resolves).
Steps 2 and 4 are the "kill-and-restart" and "crash marks the file unsafe, never crashes the app"
guarantees section 4.4 states in words; nothing before this milestone could observe either one.

## 15. M2 — cold-start delta

GATE-M2 (`docs/agent/MASTER_PLAN.md`'s own text: "APK size delta and cold-start delta reported")
needs a real number this sandboxed container cannot produce — no real device, so no real launch to
time. `docs/agent/REPORT-M2.md` §5 carries this as **device-needed** rather than a measured number.

**Steps:**
1. `adb shell am start -W -n <pkg>/<launcher activity>` five times on a build from before M2
   landed (e.g. `f15a314`, P1.14, the last commit before `core/` existed) and five times on the
   current build, force-stopping the app between runs so each start is a genuine cold start.
2. Report the median `TotalTime` (the line `am start -W` itself prints) for each build.

**Expected:** no measurable regression, since the Rust core is loaded lazily. What this run
actually found by reading the code, rather than assumed: `FylzCore` (the hand-written Kotlin
wrapper in `app/src/main/java/io/github/mbaliga/fylz/core/FylzCore.kt`) is two one-line delegating
functions with no `init`/companion-object loading logic of its own. The actual
`System.loadLibrary`-equivalent call is `Native.register(...)` (JNA) inside the *generated*
`fylz_ffi_android.kt`'s `UniffiLib`/`IntegrityCheckingUniffiLib` — both plain Kotlin `object`s, so
that `init` block runs only the first time either object is referenced, not at class-load or app
startup. The only call site anywhere in the app that references `FylzCore` (and so would trigger
that first reference) is `DecoderService.sniff()` — and `DecoderService` runs in the separate,
`android:isolatedProcess="true"`/`android:process=":decoders"` process (section 4.4), never the
main app process `am start` times. Nothing in `MainActivity`/`FylzApplication`/any other
main-process startup path calls `FylzCore` at all (confirmed by grep: `DecoderService.kt` is the
only importer), and nothing yet calls `DecoderService` itself either (M2.5's own progress note:
"nothing calls sniff from app code yet") — so as of M2.6, the `:decoders` process is not even
spawned in ordinary use, let alone the native library loaded in the process `am start` measures.
On paper this means M2 should show a zero cold-start delta for the main process; confirming that on
a real device, rather than trusting the code-reading argument alone, is this check's own job.

---

## 16. MC.0 — action registry: hardware keyboard, gestures, rooms

`docs/agent/DESIGN-MC0-ACTION-REGISTRY.md` retired the two dead shortcut-policy tables
(`workspace/KeyboardShortcutPolicy.kt`, `workspace/DesktopWorkspacePolicy.kt` — reachable from
nothing before MC.0e) and made `actions/ActionRegistry.kt`/`actions/BuiltInActions.kt` the only
source of shortcuts, gestures and menu content. Everything below was verified only against
Robolectric/plain-JVM unit tests (`KeyRouterTest`, `GestureDispatchTest`, `ShortcutTableTest`,
`ActionResolverGoldenTest`) in this sandboxed container, which has no real device, emulator, or
physical/Bluetooth keyboard to attach. **None of this ran on a device.**

**Steps and expected results, on a device or emulator with a hardware keyboard attached:**

1. With no text field focused, press each chord below once; it must fire the named action exactly
   once (`ShortcutTableTest`'s own table — 19 chords across 17 actions, two of them doubly bound):
   Ctrl+X → Cut · Ctrl+C → Copy · Ctrl+V → Paste · Ctrl+A → Select all · Escape → Clear selection ·
   Delete → Recycle · F2 → Rename · Enter → Open (the focused row/card) · Ctrl+N → New text file ·
   Ctrl+Shift+N → New folder · Ctrl+T → Add a location · Ctrl+W → Close (the active tab) · Ctrl+F →
   focus the search field · F5 **and** Ctrl+R → Refresh (both must work) · Alt+Up **and** Backspace
   → Parent folder (both must work) · Ctrl+K → open Commands · Ctrl+Shift+H → Operation history.
2. Tap into the search field, then: Delete removes a character (does not recycle a selection),
   Ctrl+V pastes text into the field (does not paste the Fylz clipboard), Ctrl+A selects the
   field's text (does not select every visible entry), and Escape leaves the field (clears focus)
   without closing anything else. This is the `onKeyEvent`-vs-`onPreviewKeyEvent` choice (design
   §2.6) — a regression here would mean the root key handler is pre-empting the field.
3. Ctrl+K opens the Commands palette; typing narrows the list by label; Enter runs the highlighted
   (topmost) entry and closes the palette.
4. Enter with a row/card focused (not the search field) opens that entry, same as tapping it.
5. Ctrl+W closes the active tab (the one shown in the Locations room), not whichever tab happens
   to be focused elsewhere.
6. A chord not in the table above (e.g. Ctrl+H, Ctrl+P, Ctrl+Shift+P, Shift+Delete, Alt+Left,
   Alt+Right, Alt+Backspace) does nothing — these are deliberately unregistered (design §2.5's
   "Shortcut table" paragraph).

**Gestures and touch, on a real device:**

7. Shake the device: the current folder still refreshes (now dispatched through
   `ActionDispatcher.gesture(SHAKE, ...)` rather than calling `refresh()` directly — same visible
   result).
8. Drag in from the left edge, right edge, and bottom edge: the same three rooms open as before
   MC.0 (Locations, Tools, Recovery respectively) — `registry.edgeRooms()` now supplies this
   mapping, but the drag itself is still handled entirely inside `SpatialShell` (design §2.6: this
   is declarative, not an intercepted gesture).
9. Double-tap a folder row/card: opens the folder. Double-tap a file row/card: opens it in another
   app (the `fylz.open-with` chooser), not inside Fylz — unchanged from before MC.0.
10. Long-press a row/card: toggles its checkbox/selection state.
11. Rotate the device, or send the app to background and forward, while the Commands palette is
    open: the palette should not crash and should either stay open with its typed filter intact or
    close cleanly — no stuck dialog, no lost `textFieldFocused` state that would leave hardware
    shortcuts silently routed to "text field focused" (Escape-only) after the dialog is gone.

**What this verifies:** MC.0 (all six commits, `ed9e61b`..`(MC.0f)`) — the action registry itself,
`docs/agent/DESIGN-MC0-ACTION-REGISTRY.md` §2.6/§2.7's acceptance bar, and MASTER_PLAN_ADDENDUM_1
§C1/§D's "registry is the only source of shortcuts."

## 17. M3.2 — seekable descriptors into `:decoders`

`docs/agent/DESIGN-M32-SEEKABLE-PFD.md` moved every archive inspection off zip4j and its
whole-archive copy onto `fylz-archive` in the isolated decoder process, reading through a seekable
`ParcelFileDescriptor` that `archive.ArchiveSource` resolves (the provider's own descriptor when
`statSize >= 0`, a cache copy only when the provider can merely stream). Everything below was
verified only in this sandbox: Rust tests on the committed ZIP/7z/ISO fixtures (pipe-fed negative
controls included), Robolectric tests through the `bind`/`unbind`, `isSeekable` and `engine` seams,
and a pipe-backed test provider. Robolectric's `createPipe()` is file-backed and reports a size, so
the *default* seekability probe on a real pipe, real Binder descriptor passing, SELinux, real
process death and real transaction sizes all need a device. **None of this ran on a device.**

**Steps and expected results:**

1. Copy a ZIP, a 7z (LZMA2, as 7-Zip writes by default) and an ISO 9660 image into `Downloads`
   (the local `FylzFilesDocumentsProvider`), open Archive tools → Inspect on each, and separately
   give an APK preview focus in the browser. Expected: the dialog/preview shows the format family
   ("ZIP archive", "7-Zip archive", "ISO 9660 image"), file/folder counts, archive and expanded
   sizes, and a verdict; `adb shell run-as io.github.mbaliga.fylz ls cache/archive-work` (or
   `adb shell ls /data/data/io.github.mbaliga.fylz/cache/archive-work` on a debuggable build) is
   **empty throughout** -- a seekable descriptor means nothing was copied. Before M3.2 every one
   of these inspections copied the whole archive there first.
2. Inspect the same ZIP through a third-party provider that streams -- Google Drive with a file
   not yet downloaded offline, or any provider whose `openFileDescriptor` returns a pipe. Expected:
   the dialog carries the line "Copied to temporary storage first: this location could not be read
   in place", and `archive-work` is **empty again once the dialog is up**: the copy is released as
   soon as the summary is in hand (the dialog shows the summary, not the archive). This is also
   the one check of the default `statSize >= 0` probe on a genuine pipe (`ArchiveSourceTest`'s
   ignored case): if the dialog does *not* say "copied", the probe called a pipe seekable and the
   engine would have refused it as `NOT_SEEKABLE` ("could not be opened" plus a `Log.w` from
   `ArchiveInspector`).
3. Build a ZIP with 100,000 entries (`python3 -c "import zipfile; z=zipfile.ZipFile('many.zip','w'); [z.writestr(f'd{i//1000}/f{i}.txt', b'') for i in range(100000)]; z.close()"`),
   push it to `Downloads`, Inspect it. Expected: the dialog appears with the counts (100,000
   entries), `adb logcat` shows **no** `TransactionTooLargeException`, and the preview (rename it
   `.zip` so the ZIP preview mounts) lists 500 rows and says "Only the first 500 entries are
   shown." The Parcelable carries at most 500 rows by design; the full listing is M3.3's.
4. SELinux: while running steps 1-3, `adb logcat | grep avc` shows **no denial** for
   `isolated_app` reading the archive descriptor. `sniff` already reads through a passed
   descriptor (section 14), but libarchive's reads are larger and seek; a denial here would
   surface as every inspection ending in "could not be read safely" or `CORRUPT`.
5. Hang: no debug-only `inspectArchive` variant was added. Use section 14 step 4's approach
   against the new client -- a hostile input that keeps libarchive busy (a compressed tarball of a
   few GB, whose header pass decompresses the whole stream, is the honest way to hit the budget).
   Expected: the coroutine returns within about 30 s (`STRUCTURE_TIMEOUT_MILLIS`) with "The
   archive took too long to read", not a frozen dialog and not "could not be read safely";
   `io.github.mbaliga.fylz:decoders` **disappears** from `adb shell ps -A` shortly after (the
   unbind lets the platform reap it); the next Inspect of a small archive works (a fresh
   `:decoders` PID appears).
6. Kill `:decoders` mid-inspect (`adb shell am kill io.github.mbaliga.fylz:decoders`, or
   `kill -9 <pid>` from `ps -A | grep decoders`, while a large archive is being inspected).
   Expected: "The archive could not be read safely."; the app neither crashes nor ANRs; the next
   Inspect works (rebind, new PID). Also drive `DecoderClientTest`'s dropped-bind case for real:
   kill the process *while the bind is still connecting* (kill immediately after tapping Inspect
   on a cold `:decoders`) -- the same message, never a crash.
7. A legacy ZIP with CP437 names (one made by an old Windows archiver, or
   `zip -n .txt legacy.zip caf$'\xe9'.txt` in a `LANG=C` shell) inspects: names appear with
   replacement characters, the dialog notes "Some entry names use a legacy encoding", and the
   verdict is the policy's, **not** "This file is not an archive Fylz can open" -- lossy names are
   flagged, never fatal, until M3.7 adds charset detection.

**What this verifies:** M3.2 (`8e35497`, `1983c0c`, and the M3.2c commit) -- MASTER_PLAN's
"Kotlin passes a seekable `ParcelFileDescriptor` into the decoder process. ZIP, 7z and ISO are read
with seeks. Only non-seekable remote streams stage to cache, with a space check", section 4.4's
structure budget and kill-and-restart, and the design's section 2.9 list, item for item.
