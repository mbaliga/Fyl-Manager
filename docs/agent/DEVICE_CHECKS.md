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

## 18. M3.3 — archive browsing

`docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md` made every archive a folder: `ArchiveDocumentsProvider`
(authority `io.github.mbaliga.fylz.archives`, `MANAGE_DOCUMENTS`-protected, no picker root) serves
entries as documents, the listing streams out of `:decoders` through a pipe in the `FZL1` codec into
`cache/archive-listings`, entries open by materialising into `cache/archive-entries`, and the
registry disables every writing action inside an archive. Everything below was verified only in
this sandbox: Rust tests on the committed fixtures (including the golden `.fzl` bytes), Robolectric
tests with a fake decoder behind the `bind`/`unbind` seam, and the provider hosted with the
manifest's own `<provider>` attributes. Robolectric's pipes are file-backed, its `StatFs` reports 0
free, one JVM is every "process", and there is no Compose harness, so real pipe EOF and
backpressure, the `Os.lseek` liveness probe on a Binder dup, SELinux for `isolated_app`, Uri grants
to another app, process death, the AndroidRuntime SIGPIPE disposition, and every tap-and-look step
need a device. **None of this ran on a device.**

**Steps and expected results:**

1. Copy `photos.zip` (any ZIP with a folder in it) into `Downloads` and tap it. Expected: it opens
   like a folder -- entries list in archive order, the breadcrumb reads `Downloads / photos.zip`,
   entering a folder inside reads `Downloads / photos.zip / 2024`, Up twice is back in
   `Downloads`. Repeat with a `.7z`, an `.iso`, a `.tar.gz` and a `.deb`: each opens as a folder
   (a `.deb` shows `debian-binary`, `control.tar.*`, `data.tar.*`). A double-tap on the archive
   browses it too; the external "Open with" chooser appears only for a non-archive file.
   `adb shell run-as io.github.mbaliga.fylz ls cache/archive-listings` shows one `<key>.fzl` and
   one `<key>.summary.json` per archive opened and **no** `.part` once a listing is up.
2. Inside the zip, focus a PDF entry and a font entry (`sample-entries.zip` from
   `tools/fixtures/make_archive_fixtures.py` has the layout, but its TTF is a stub -- use a real
   font for this step); play a short video entry; view a PNG. Expected: the PDF pages, the font
   renders its specimen, the video plays *and scrubs* (the descriptor is a seekable file, not a
   pipe), the PNG shows; `cache/archive-entries/<key>/` holds one `<ordinal>` file per entry
   opened, never a lingering `.part`. The grid shows no thumbnails for entries inside the archive
   (by design, REVIEW_QUEUE M3.3 item 11).
3. Share a text entry to another app (Messages, Keep, Files); "Open with…" a PDF entry into an
   external viewer. Expected: the other app receives the content; `adb logcat` shows no
   `SecurityException` on the grant -- the provider is `MANAGE_DOCUMENTS`-protected, so the grant
   must ride on `grantUriPermissions`, which `dumpsys package io.github.mbaliga.fylz` lists for the
   `io.github.mbaliga.fylz.archives` provider together with `exported=true` and no
   `DOCUMENTS_PROVIDER` filter. The system file picker (Files → Browse) does **not** list an
   "archives" root.
4. Select a folder inside the archive, Copy, go to `Downloads`, Paste. Expected: the folder arrives
   with every file; `sha256sum` of each copied file equals `sha256sum` of the same member extracted
   with `unzip`/`tar` on the desktop. Cut, Move to, Recycle, Rename, Tags, Batch rename, Paste,
   New folder, New text file, Scan to PDF, Favourite, AI organize and Find duplicates are
   **disabled** (grey, not hidden where they were visible) while inside the archive; Copy, Copy to,
   Share, Compress, Select all/none/invert, sort and view mode work.
5. Push `nested-depth-4.zip` and `nested-depth-5.zip` (from the fixtures script). Expected:
   `nested-depth-4.zip` opens through all four archives to the innermost file, which previews;
   in `nested-depth-5.zip` the fifth archive does not open and the toast reads "Archives nested
   deeper than 4 levels cannot be browsed". The nested listings re-open from disk after a process
   restart (`am force-stop`, reopen, enter the same path: no new `:decoders` listing pass beyond
   the first, visible as unchanged `.fzl` mtimes).
6. Build a `tar.gz` with a 1,000-file folder (`mkdir big; for i in $(seq 1000); do head -c 4096
   /dev/urandom > big/f$i.bin; done; tar czf big.tar.gz big`), copy the folder out. Expected: it
   completes; record the wall time -- the per-entry cost model (design §2.4) predicts about N/2
   full decompressions, so a 1,000-file `tar.gz` of ~4 MB should still be well under a minute.
   Build a tarball with 80,000 entries (`python3 -c "import tarfile,io; t=tarfile.open('many.tar',
   'w'); [t.addfile(tarfile.TarInfo(f'd{i//1000}/f{i}'), io.BytesIO()) for i in range(80000)];
   t.close()"`), open it. Expected: the folder lists (record the time from tap to rows), the tab
   stays responsive while it lists, `dumpsys meminfo io.github.mbaliga.fylz:decoders` peaks well
   under 100 MB (the engine keeps a `Vec` of about 30 MB at the 200,000 bound), and the app
   process holds at most two trees (`dumpsys meminfo io.github.mbaliga.fylz` before and after
   opening a third large archive differs by about one tree, not three).
7. SELinux: while running steps 1-6, `adb logcat | grep avc` shows **no denial** for
   `isolated_app` writing the passed pipe (the listing and entry streams) **or** reading the staged
   and materialised cache files (a nested archive's inner listing reads a file the app process
   materialised). A denial here surfaces as every archive "could not be read safely" or as a nested
   archive that refuses to open while its outer lists fine.
8. Kill `:decoders` during a listing (`adb shell am kill io.github.mbaliga.fylz:decoders` right
   after tapping the 80,000-entry tarball). Expected: the toast names the failure ("The archive
   could not be read safely" / "Unable to read the archive ..."), the location stays and Up
   works; pull to refresh (or leave and re-enter): it lists (a fresh `:decoders` PID). Then leave
   the app idle for 60 s with nothing in flight: `adb shell ps -A | grep decoders` shows **no**
   `:decoders`; the next archive open brings it back. Interrupting a video entry mid-play by
   killing `:decoders` must not crash the app (the entry is already a local file).
9. With Developer options → "Don't keep activities" on, browse into `photos.zip / 2024`, rotate,
   then background and return. Expected: the location restores and lists (the catalog re-lists
   from disk, blocking on the first query), the breadcrumb is intact, Up works. Delete
   `photos.zip` from another file manager while backgrounded, return: the toast names the
   vanished source, the location stays, Up returns to `Downloads`.
10. Open twenty different archives and thirty different entries, then `adb shell run-as
    io.github.mbaliga.fylz du -sk cache/archive-listings cache/archive-entries`. Expected:
    `archive-listings` under 64 MB, `archive-entries` under 512 MiB, no `*.part` anywhere under
    either (cancel a large entry open mid-fill by leaving the folder to provoke one, wait, and
    check again: the `.part` is gone within the hour's grace or on the next process start's sweep).
11. Push `messy-paths.tar` (fixtures script). Expected: it browses without a crash; the entries
    with `..`, absolute and drive-qualified paths are **absent** from the folder view; give the
    archive file preview focus in `Downloads`: the archive preview shows "N entries with unsafe
    paths are hidden." (Archive tools → Inspect shows the M3.2 summary and verdict only); the
    `dot-rooted.tar` fixture lists its members at the top level (no `.` folder). Push
    `damaged-after-3.tar`: it lists three entries and the inspection view says "Damaged after 3
    entries; showing what could be read".

**What this verifies:** M3.3 (`05c3531`, `9b1bceb`, `c7f6a86` and the M3.3d commit) -- the
design's section 2.11 list, item for item, MASTER_PLAN's "archives open as folders", section 4.4's
kill-and-restart under the streaming client, and the idle unbind that closes M3.2's open question.

## 19. M3.4 — selective extract through the transfer queue

`docs/agent/DESIGN-M34-SELECTIVE-EXTRACT.md` moves extraction of a plain archive onto the Rust
engine, through the same durable queue copy and move use: four registry actions
(`fylz.extract`/`.here`/`.folder`/`.to`, `fylz.extract.selected` inside a browsed archive) hand off
to `ui/actions/ExtractFlow.kt`, `operations.ExtractPlanner` plans entirely in the UI process, and
`operations.ArchiveExtractor` runs the one pass on a **second isolated decoder instance**
(`:decoders:extract`) with the frame protocol demultiplexed straight into staged destination
documents. Everything below was verified only in this sandbox: Rust tests on the fixtures (including
the golden `.fzx` bytes), Robolectric tests with a fake `IDecoderService.Stub` writing real frames,
and a simulated system stop (cancelling the run's coroutine with a stop reason). Real `bindIsolatedService`
binding of a second instance, `:decoders:extract` actually appearing in `ps` and being reaped, SELinux
for the pipe and the isolated process, a real WorkManager stop mid-extraction, and every wall-clock/
memory number below need a device. **None of this ran on a device.**

**Steps and expected results:**

1. Extract here / to `<name>/` / to… for a ZIP, a 7z, a `tar.xz` and an ISO (any archive with a
   folder in it). Expected: a progress notification with a bar and a byte line ("Reading archive…"
   until the first entry, then bytes-of-total); the result is byte-identical to `unzip`/`7z x`/`tar
   xf`/mounting the ISO on a desktop (`sha256sum` each extracted file and compare); `adb shell run-as
   io.github.mbaliga.fylz ls cache/archive-work cache/archive-entries` shows nothing left over
   (extraction stages through the destination provider's own `.fylz-part-*` names, not these
   caches); `adb shell ps -A | grep decoders` shows **both** `io.github.mbaliga.fylz:decoders` (if a
   browse is open) and `io.github.mbaliga.fylz:decoders:extract` while the extraction runs, and only
   the first once it finishes.
2. **Acceptance, "Extract here":** build a 5 GB 7z with one 5 GB member at the root and a thousand
   small ones (`7z a -mx=1 big.7z bigfile.bin small*.bin`), extract it to internal storage with
   Settings → (wherever `operations.VerifySettings` is exposed) → Always verify, ticking the consent
   checkbox on the confirm sheet. Record: wall time, peak RSS of `io.github.mbaliga.fylz:decoders:extract`
   (`adb shell dumpsys meminfo io.github.mbaliga.fylz:decoders:extract` sampled during the run), and
   the big file's `sha256` from the operation history against a desktop `sha256sum`. Repeat onto an
   exFAT card. Repeat onto a vfat card: the 5 GB member is a preflight problem (`FileTooLargeForVfat`);
   Skip extracts everything else. While the 5 GB extraction runs, browse into a *different* archive
   in another tab: expected both finish byte-exact and the browse never blocks on the extraction's own
   `:decoders:extract` instance (they are different processes).
3. Start a large extraction, cancel it mid-way from the notification's Cancel action. Expected: the
   staged folder/files disappear (nothing partial left under the destination), the operation history
   shows CANCELLED, `adb shell ps -A | grep decoders` shows `:decoders:extract` gone within a couple
   of seconds (the demuxer joins, then unbinds) while a `:decoders` browse elsewhere stays alive; a
   copy or move queued behind the cancelled extraction (paste something while the extraction is
   running) still runs to completion once the extraction's slot in the unique queue clears.
4. Start a large extraction, `adb shell am kill io.github.mbaliga.fylz` mid-way (not `:decoders`,
   the whole app). Expected: on relaunch the operation shows `PAUSED_BY_SYSTEM`/`NEEDS_ATTENTION`
   briefly then resumes (WorkManager re-runs the worker, which claims the row and continues items not
   yet `SUCCEEDED`); exactly **one** operation appears in history for the whole run, not two.
5. Push `crc-bad.zip` (`tools/fixtures/make_archive_fixtures.py`) and extract it: the one file with
   the flipped byte fails with a CRC-mismatch error, everything else extracts, the operation ends
   PARTIAL. `inflate-bad.zip`: the pass aborts on the corrupted member; watch `adb logcat` for one
   re-issue call to `extractRanges` for the ordinals after it (time the gap -- the re-issue re-reads
   the compressed stream from the start for a `tar.*`, cheaply reopens for a ZIP/7z). `solid-bad.7z`:
   both members of the corrupted LZMA2 folder fail, the *next* folder still extracts (no hang).
   Build a `tar.xz` with a corrupted member partway through a large tarball and extract it: the
   re-issue re-decompresses from byte 0, so time how long a bad member near the end takes relative to
   one near the start.
6. Browse into an archive with a folder and a hardlink whose target sits *outside* the folder, select
   the folder plus the hardlink, choose "Extract selected entries", pick a destination. Expected: the
   folder's contents and the hardlink (as a real, independent copy, not a link) all land correctly;
   focus a symlink entry inside the same archive and confirm the inspection/preview side says it is
   skipped, not silently dropped with no explanation.
7. Open an AES-encrypted ZIP (Archive tools → Inspect, or tap it directly): Extract still asks for a
   password and runs the old zip4j path (`ArchiveService.extractZip`) -- confirm the result is
   byte-identical to `unzip -P`. Browse into that same archive (once M3.6 or the read path allows it)
   and try "Extract selected entries" on one of its entries: expected the M3.9 message ("Extracting
   protected entries arrives with the password prompt"), not a silent failure or wrong bytes.
8. Repeat steps 1-6 while running `adb logcat | grep avc` continuously. Expected: **no denial** for
   `isolated_app` writing the sink pipe, reading the archive descriptor, or anything about the second
   isolated instance specifically (a denial here would surface as every extraction ending
   FAILED/ARCHIVE_UNAVAILABLE with no other symptom).
9. Build a `tar.zst` with exactly 10,000 files and one with 10,001 (`many-entries.tar.zst`'s fixture
   shape). Expected: the 10,001-file archive is refused ("needs explicit confirmation") without the
   consent tick and extracts once it is given; record the wall time for the 10,000-file case (one
   pass; `tar.*` still costs a header pass plus the extraction pass -- REVIEW_QUEUE item 4).
10. Queue two extractions back to back (start one, immediately start a second from another archive
    before the first finishes). Expected: the second sits queued (visible in the running-operations
    list) and starts only once the first's WorkManager slot frees up; cancel the *first* via its
    notification and confirm the second is untouched (still queued, then runs); make the first end
    PARTIAL (a fixture with a mid-archive CRC failure) and confirm the second still runs to completion
    (the `Result.success()` rule, "Durable operation queue" in `docs/ARCHITECTURE.md`).

**What this verifies:** M3.4 (`5fdc515` (a), `075ecab` (b) and the M3.4c commit) -- the design's
section 2.9 list, item for item, and the acceptance line MASTER_PLAN's own M3.4 entry states ("a 5 GB
7z extracts through the queue with verification").

## 20. M3.5 — Compress

`docs/agent/DESIGN-M35-CREATE.md` moves archive creation onto the Rust engine, through the same
durable queue extraction uses: `fylz.compress`/`ArchiveToolsOverlay`'s plain "Create ZIP" hand off
to `ui/actions/CompressFlow.kt`, `operations.CompressPlanner` plans entirely in the UI process, and
`operations.ArchiveCreator` runs the one pass on a **third isolated decoder instance**
(`:decoders:write`) with `FZW1` frames fed in and the archive stream demultiplexed into staged,
possibly-split output documents. Everything below was verified only in this sandbox: Rust tests on
the write engine (including the locale-pin and poisoning proofs against the host build), Robolectric
tests with a fake `IDecoderService.Stub` parsing real frames and writing a readable fake archive
back, a plain-JVM real-pipe streaming proof of the frame codec (`ArchiveFrameStreamingTest`), and a
simulated system stop (cancelling the run's coroutine with a stop reason). Real `bindIsolatedService`
binding of a third instance, `:decoders:write` actually appearing in `ps` and being reaped, SELinux
for the two pipes and the isolated process, a real WorkManager stop mid-create, non-ASCII names
against the on-device bionic locale, and every wall-clock/memory number below need a device. **None
of this ran on a device.**

**Steps and expected results:**

1. Compress a folder tree containing a non-ASCII name (e.g. "café", "日本語.txt") to `zip`,
   `tar.gz`, `tar.xz` and `tar.zst` into Downloads. Expected: each opens correctly in a third-party
   app and in Fylz's own M3.3 browsing, with the non-ASCII name intact (not mojibake, not a write
   failure) -- this is the direct proof that `write_frames`' locale pin actually works against
   bionic, not just the host build the Rust tests ran against; mtimes within a few seconds of the
   source; `adb shell ps -A | grep decoders` shows `io.github.mbaliga.fylz:decoders:write` appear
   for the run and disappear once it finishes (alongside `:decoders` if a browse is open, and
   `:decoders:extract` if one is running -- all three coexist).
2. Compress a 6 GB folder to `zip` on internal storage. Expected: a progress notification with a
   bar and a byte line ("N of M files · X of Y MB", source bytes fed over the plan-time estimate);
   record whether `:decoders:write` is distinguishable from a concurrent `:decoders` browse by pid
   and by `adb shell dumpsys meminfo io.github.mbaliga.fylz:decoders:write` alone, or only by the
   process name suffix, and how that was found (REVIEW_QUEUE/`ARCHITECTURE.md`'s own `UNVERIFIED`).
   Repeat with `tar.xz` at Best (level 6) and confirm peak RSS stays under the 256 MB `:decoders`
   target throughout.
3. Compress the same large tree with a 700 MB split. Expected: parts named `name.zip.001`,
   `.002`, … ; `cat name.zip.* > whole.zip` then open `whole.zip` normally. On a vfat card, compress
   a source known to exceed 4 GiB with split Off: expected a dismissible warning suggesting a split,
   never a hard block. Compress the same name twice with Keep-both: the second run's whole numbered
   set shares one new base name, not a mix of old and new. Create a name, then Replace it with a
   *shorter* split set than the one on disk (e.g. it now needs 2 parts where 4 existed before):
   expected no stale trailing `.003`/`.004` remains at the destination afterward.
4. Start a large compress from a throttled network share (a slow SMB/SFTP mount, or `tc`/a proxy
   that rate-limits a USB-OTG drive) and Cancel mid-way while a source read is genuinely slow.
   Expected: Cancel actually interrupts the hung read (the `CancellationSignal` path on
   `openFileDescriptor`) within a few seconds, not after riding out the 10-minute
   `SOURCE_UNREADABLE` cap; no staged parts remain at the destination; a copy/move/extract queued
   behind the cancelled compress still runs to completion.
5. Start a large compress, `adb shell am kill io.github.mbaliga.fylz` mid-way. Expected: on
   relaunch the operation restarts the whole pass from scratch (no partial resume: the design's own
   stated rule), the previous attempt's staged output is gone, not orphaned. Repeatedly kill the app
   during the same operation's restarts to force `create_plans.restart_count` past 3: expected the
   operation ends `FAILED`/`TOO_MANY_INTERRUPTIONS` cleanly, not one more restart loop. If reachable,
   force a foreground-service execution-limit stop on a very long compress (Settings → background
   restrictions, or a device known to enforce the 6-hour `dataSync` cap aggressively) and confirm the
   mapped failure rather than a silent hang.
6. Compress a folder browsed from inside an archive (M3.3), including one containing an encrypted
   or oversized entry: expected that entry is refused as a skippable problem *at plan time*, before
   any output byte exists, not discovered mid-archive. Compress into a different tab's own folder
   via "Choose folder…". Compress via "Save as…" into a destination with no tree grant (a
   `DocumentsUI` provider Fylz never mounted a tab in, e.g. Drive if installed): expected a single
   output document, no split option offered, no operation-history row (this path never reaches the
   queue).
7. Open the Archive tools FAB, "Create ZIP", flip the AES switch on, encrypt and create an archive:
   expected the pre-M3.5 zip4j path still runs unchanged and still refuses a folder source. Flip the
   switch off instead: expected the new Compress sheet opens (not zip4j).
8. Repeat steps 1-6 while running `adb logcat | grep avc` continuously. Expected: **no denial** for
   `isolated_app` writing or reading either pipe, or anything about the third isolated instance
   specifically (a denial here would surface as every compress ending FAILED/`ARCHIVE_WRITE_FAILED`
   with no other symptom).

**What this verifies:** M3.5 (`7f2c9db` (a), `f83713a` (b) and the M3.5c commit) -- the design's
section 2.9 list, item for item.

## 21. M3.6 — Edit archives in place

M3.6 adds no new Rust and opens no new `:decoders` instance: an edit is planned in
`operations/EditPlanner.kt` as a mixed manifest (old entries read back through
`ArchiveDocumentsProvider`, new entries read from picked Uris) and run through M3.5's own
`:decoders:write` engine and `ArchiveCreator`, ending with a `RecycleBinService.replaceWithRecycleFallback`
swap of the new archive for the old one. Everything below was verified only in this sandbox:
the planner's tree walk and refusal rules, the round-trip byte-for-byte proof, `.fylz-trash`
recycling, and cancel-mid-edit — all against the real, hosted `ArchiveDocumentsProvider`, not a
mock, but on the JVM under Robolectric. The Compose surfaces this milestone re-enables or adds
(Rename/Recycle inside a browsed ZIP entry, the Tools-menu "Add entries" item and its picker, the
confirm/cancel dialogs) have no unit coverage at all — this project's Compose UI has no test
harness — so they are entirely unverified until read on a device. **None of this ran on a device.**

**Steps and expected results:**

1. Browse into a plain `.zip` (M3.3) and select a single entry. Expected: Rename and Recycle are
   now enabled in the selection bar (previously greyed for every archive entry); Rename opens the
   same rename dialog a normal file uses; confirm it and expect a short pause (an edit is a full
   archive rewrite, not an in-place patch) followed by "Archive updated" and the entry's new name
   reflected immediately on re-listing. Repeat inside a `.jar`, `.cbz` and `.apk` (the rest of the
   zip-family set) and confirm the same. Browse into a `.7z`, `.tar.gz` or `.iso` and confirm
   Rename/Recycle stay disabled for every entry (7z/tar/iso have no writer; this is the M3.5 scope
   narrowing, not a bug).
2. Inside a browsed `.zip`, select 2-3 entries (including one inside a subfolder) and Recycle them
   together. Expected: a single "Archive updated" toast, not one per entry; re-listing shows them
   gone; `adb shell` (or a third-party file manager) shows the *old* archive file now sitting inside
   a `.fylz-trash` folder next to where the archive lives, with its original bytes intact, and the
   live archive at the original path/name is the newly rewritten one.
3. Open the archive's Tools menu (`ArchiveToolsOverlay`) on a zip-family archive and confirm a new
   "Add entries" item is present (and absent for a `.7z`/`.tar.gz`). Tap it, pick 2-3 files (including
   one whose name collides with an existing entry at the target folder) through the system picker.
   Expected: the picked files appear as new entries after the edit completes; a name collision is
   resolved by uniquification (a suffix), never silently overwriting or refusing the whole edit.
4. Start an edit on a large archive (several hundred MB, many entries) and cancel it mid-way
   (leave the app, kill the write process if reachable, or use whatever Cancel affordance the running
   operation exposes). Expected: the original archive is completely untouched afterward — same
   bytes, same name, same location — and no partial/staged output is left behind at the destination.
5. Repeat step 1-3 while running `adb logcat | grep avc` continuously. Expected: no denial for
   `isolated_app` reading the picked-file Uris or writing through `:decoders:write`'s existing pipe
   (an edit reuses that same isolated instance and pipe contract M3.5 already exercises; a denial
   here would surface as every edit ending in a generic write failure with no other symptom).
6. With an edit queued behind an ordinary copy/move on the same destination folder, confirm the
   queue runs both to completion in order and neither one starves or corrupts the other — an edit is
   just another `ARCHIVE` operation through the same durable queue, so this should need no special
   handling, but a queue-ordering bug specific to the new `replaceOriginalUri` finalize step would
   only show up under real WorkManager scheduling on a device.

**What this verifies:** M3.6 (this commit) — the brief's own scope: Rename/Delete re-enabled for
zip-family archive entries only, "Add entries" via the Tools menu, the old archive recycled to
`.fylz-trash`, and cancel-mid-edit leaving the original untouched.

## 22. M3.7 — Legacy ZIP filename charset

M3.7 touches Rust only in the listing writer (one new field, gated on the pre-existing
`FLAG_NAME_LOSSY` flag) and adds no new `:decoders` instance or pipe contract — everything else is
Kotlin: a small heuristic, an in-memory per-archive map, and a header-bar control. Everything below
was verified only in this sandbox: the wire format round trip against a real, committed CP-437
fixture (`legacy-cp437.zip`/`legacy-cp437.fzl`), the detector's heuristic on hand-built byte
patterns per charset family, and the override's effect through the real, hosted
`ArchiveDocumentsProvider` (document id, ordinal and extracted bytes unchanged; no re-listing).
None of the five real JDK charsets (`Cp437`, `Cp866`, `GBK`, `Shift_JIS`, `EUC-KR`) has been tried
against Android's own ICU-backed charset provider on a device — only against this sandbox's host
JDK, which resolves all five (`LegacyZipCharsetDetectorTest`'s own smoke test). **None of this ran
on a device.**

**Steps and expected results:**

1. Obtain (or build with a legacy tool — an old Windows `zip.exe`, 7-Zip/WinRAR with its charset
   option set away from UTF-8, or Info-ZIP under a non-UTF-8 locale) a real ZIP whose entries carry
   non-ASCII names in CP-437, CP-866, GBK, Shift-JIS and EUC-KR respectively (five separate
   archives, or one archive per family is fine). Browse each into Fylz. Expected: a non-ASCII name
   shows *something* readable rather than the U+FFFD replacement-character mush of the pre-M3.7
   lossy string, for at least the Western (CP-437) and East Asian cases where Auto's heuristic is
   expected to succeed; the Cyrillic (CP-866) case may still show mojibake under Auto per the
   heuristic's own documented limit (`REVIEW_QUEUE.md`'s M3.7 entry, item 4) until the manual
   override is used.
2. With one of those archives browsed, open the header bar's new charset control (the icon next to
   the folder name) and step through all six choices (Auto, CP-437, CP-866, GBK, Shift-JIS,
   EUC-KR). Expected: the lossy-named entries' displayed names change immediately (no spinner, no
   re-listing) with each choice, and the *correct* choice for that archive's own real charset shows
   the name a person would recognise; every non-lossy (plain ASCII/UTF-8) entry's name is
   unaffected by any choice.
3. With the override changed to something other than Auto, tap into the lossy-named entry (open,
   preview, or copy it out) and confirm it opens/extracts the *correct* file's bytes — the same
   entry as before the override was touched, never a different one. Rename or Recycle it (a
   ZIP-family archive, M3.6): confirm the edit operates on the entry the override is currently
   *displaying*, correctly, regardless of which charset is selected — i.e. the display change never
   silently retargets an action at the wrong header.
4. Force-stop and relaunch Fylz, then browse back into the same archive. Expected: the override is
   gone (back to Auto) — it was never meant to survive past the session, per the brief's own
   "remembered for the session only... never persisted."
5. Browse into a *nested* archive (an archive inside an archive, M3.3) that itself has a lossy
   name, and confirm the header-bar control still appears and works for the nested archive's own
   entries, independently of any override set for the outer archive.
6. Repeat step 2 on a large archive (thousands of entries, at least one lossy-named) and confirm
   switching the override redraws promptly (sub-second) — this is a `queryChildDocuments` re-query
   against an already-cached tree (sandbox-confirmed: `stub.listCalls.get()` stays at 1 across every
   override change in `ArchiveDocumentsProviderTest`), so a real device slowdown here would point at
   something else (a `LazyColumn` recomposition cost, not a re-list).

**What this verifies:** M3.7 (this commit) — the brief's own scope: raw bytes carried for a lossy
name only, the five real charsets resolving on-device, the auto-detect heuristic's real-world
behaviour (including its documented limits), the manual override changing display only, and the
override lasting the session and no longer.

## 23. M3.8 — Test archive (verify CRCs without extracting)

M3.8 adds zero Rust code and no new `:decoders` instance or pipe contract — it reuses M3.4's own
`extractRanges`/FZX1 frame stream, the same isolated extraction instance a real Extract binds, with
a Kotlin-side sink that discards every byte instead of writing it. Everything below was verified
only in this sandbox, against `FakeArchiveDecoder` writing real FZX1 frames, never a real
libarchive-backed `:decoders:extract` process on a device. The one thing that genuinely cannot be
faked here is the process-isolation contract itself: whether a hostile or crashing decoder process
mid-test really is contained and reaped the way `docs/agent/DEVICE_CHECKS.md` section 19 (M3.4)
already established for a real Extract.

**Steps and expected results:**

1. Pick "Test archive" from the Archive Tools menu (the FAB) and choose a real, clean archive of
   each vendored format (ZIP, 7z, tar, tar.gz/.xz/.zst, ISO) from actual device storage, including
   at least one large enough (thousands of entries, hundreds of MB) that the test visibly runs for
   more than an instant. Expected: a progress dialog showing "`N` of `M` entries tested" that
   advances smoothly to completion, then a result dialog reporting every entry passed, with no
   crash, no visible file created anywhere (check the archive's own folder and Downloads/cache
   before and after), and no `:decoders:extract` process left running afterward (`adb shell ps -A
   | grep decoders` — a second Test on another archive should show the same isolated-instance
   binding, not two lingering processes; compare against section 19's own `:decoders:extract`
   check).
2. Repeat step 1 against one of this repo's own known-bad fixtures if it can be pushed to the
   device (`core/fixtures/archives/crc-bad.zip`, `crc-bad.7z`, `inflate-bad.zip`, `solid-bad.7z`) or
   a hand-corrupted copy of a real archive (flip a byte inside a compressed entry's body with a hex
   editor, leaving the header alone). Expected: the result dialog reports the corrupted entry as
   failed with a CRC-mismatch (or decode-failure) reason, every other entry still passes, and —
   again — nothing was created on disk.
3. Start a Test on a large archive and tap Cancel partway through. Expected: the progress dialog
   closes (or shows a "stopped early" result, per whichever the landed UI shows), the archive tools
   FAB/menu is usable again promptly (not stuck `busy`), and nothing was created on disk. Then
   confirm `:decoders:extract` is not left running (same `adb shell ps` check as step 1) — this is
   the one thing sandbox-verified only against a fake stub whose "process" is just a same-process
   `Stub`, never a real cross-process kill.
4. Pick "Test archive" on a real password-protected ZIP (created with a real tool's AES or
   ZipCrypto encryption, not this app's own zip4j create flow, to also exercise a `hasEncryptedMetadata`
   case if the tool supports encrypting filenames too). Expected: a clear message that the archive
   is password-protected and cannot be tested (`REVIEW_QUEUE.md`'s M3.8 entry, item 5) — never a
   password prompt, and never a silent hang.
5. Pick "Test archive" and choose a file that is not an archive Fylz can browse at all (a plain
   `.jpg`, or a format `BrowsableArchiveFormats` does not list). Expected: a toast saying Fylz
   cannot test this format, with no attempt to open a decoder connection at all.
6. Background the app (or rotate the device) while a large Test is in progress. Expected: the test
   either continues and the result dialog is there when the app resumes foregrounded, or the
   composition is torn down cleanly with no crash and no orphaned `:decoders:extract` process --
   this composable has no `WorkManager`/durable-across-process-death story at all (deliberately: it
   is a quick, non-queued action, not a queued operation), so "the test silently stops when the
   screen is put away" is expected behaviour, not a bug, but a hang or a crash is not.

**What this verifies:** M3.8 (this commit) — the brief's own scope: CRCs verified without ever
writing a file, a bad entry reported without derailing the rest, cancellation leaving nothing
behind, and an encrypted archive refused rather than silently mishandled.

## 24. M3.9 — Shared, session-only password prompt across formats

M3.9 touches no Rust and adds nothing new at the `:decoders` boundary — it is Kotlin/Compose state
management (one shared dialog, one in-memory session store) plus zip4j, the same real AES-256/
ZipCrypto path M3.4c/M3.5c already exercise. Everything below was verified only in this sandbox
(Robolectric's `ShadowLog`/`ContentResolver`, never a real device); the one thing that cannot be
faked here is whether "session-only" genuinely means "gone the instant the process dies" versus
merely "gone until some Android-side cache or crash-report layer holds onto it longer than
expected.

**Steps and expected results:**

1. From Archive Tools, pick "Create ZIP", add a couple of files, turn on "Encrypt with AES-256",
   type a password, and create the archive. Expected: the same create flow as before this
   milestone (no remember checkbox is offered here — there is no archive identity yet before a
   destination is chosen, per `REVIEW_QUEUE.md`'s M3.9 entry).
2. Browse to a real, password-protected ZIP (not one this app just created) and choose "Inspect and
   extract ZIP" / Extract. Expected: the password prompt now shows a "Remember for this session"
   checkbox, unticked by default. Leave it unticked, enter the correct password, extract. Then
   extract the *same* archive again. Expected: prompted again — the default (opt-out) behaviour is
   unchanged from before this milestone.
3. Repeat step 2, this time ticking "Remember for this session" before extracting. Expected: the
   extraction succeeds as before; a *second* Extract of the *same* archive skips the password dialog
   entirely and goes straight to the destination picker.
4. With that same password remembered (step 3), browse to a *different* encrypted archive and
   Extract it. Expected: prompted for a password — the remembered one from step 3 is never offered
   or auto-tried against a different archive.
5. With a password remembered (step 3), force-stop and relaunch Fylz, then Extract the same archive
   again. Expected: prompted again — the remembered password did not survive the process, per the
   brief's own "remember for the session only," device-verifying what a JVM unit test can only
   assert about the in-memory map's own lifetime, not about the real process.
6. Trigger the *other* password call site: browse to a folder (not an archive), Extract onto a
   folder that already contains an encrypted ZIP inside it discovered through the plain `fylz
   .extract` flow on that whole-archive selection (the `ExtractPlanner.LegacyEncryptedZip` path,
   `FylzV1App.kt`'s own dialog, not `ArchiveToolsOverlay`'s). Expected: the same shared dialog
   appears (same title, same "Remember for this session" checkbox) as step 2's — confirming the
   consolidation actually reached both call sites, not only the one under Archive Tools. Tick
   remember, extract, then repeat step 3's own "does it skip the second time" check against this
   call site specifically.
7. With `adb logcat` running and its buffer cleared, repeat steps 2–3 typing a real, memorable
   password, then `adb logcat -d | grep -i password` and separately search the captured log text
   for the literal password string itself. Expected: nothing — this is the one part of
   `ArchivePasswordSessionTest`'s own "nothing about a password ever reaches `Log`" case that a JVM
   unit test cannot cover (Android's own Binder/Compose/crash-reporting layers, not just this app's
   own `Log.*` calls, per `REVIEW_QUEUE.md`'s M3.9 entry, item 9).
8. Low-memory test: with a password remembered (step 3), open several other memory-heavy apps to
   pressure Android into reclaiming Fylz's process in the background, then return to Fylz and
   Extract the same archive again. Expected: prompted again if the process was actually killed and
   restarted (indistinguishable from step 5 in that case), or skipped if Android merely paused the
   Activity without killing the process — either is correct; a stale password surviving an actual
   process restart would not be.

**What this verifies:** M3.9 (this commit) — the brief's own scope: one shared dialog reaching both
real call sites, the remember tick genuinely opt-in and per-archive, and the remembered password
never outliving the process it was typed into.
