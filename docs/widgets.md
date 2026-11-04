# Home-screen widgets

Fylz ships six standard Android App Widgets (`AppWidgetProvider` + `appwidget-provider` XML +
`RemoteViews`), so any launcher that hosts App Widgets can place them: Pixel Launcher, One UI, Nova,
Orrery Home and others. Every widget is resizable from 2x1 upwards and draws one of four layouts by
size. Each one reads Fylz's real data on the device, and nothing leaves the device.

> **Status: compiled and unit-tested, not yet seen on a device.** No phone or emulator was
> available when these were built. Rendering, resizing, taps, TalkBack and refresh timing are all
> owner-verify items (see the list at the end).

## The six widgets

| Widget | Template | Default size | Shows | Tap |
|---|---|---|---|---|
| **Space** | `stat` | 2x2 | Free space per mounted volume, with a bar | Storage home; at 4x3 a volume row opens that volume |
| **Backups** | `next` | 4x2 | Last backup and how it ended, the next daily window, plans needing attention | Recovery room |
| **Recovery** | `list` | 4x2 | Recycle Bin count and size, operations needing attention | Recovery room; at 4x3 the bin row opens the Recycle Bin and the attention row opens Activity |
| **In progress** | `progress` | 4x2 | Copies, moves, archives, extractions and backups running now, with progress | Activity (operation history) |
| **Quick folders** | `pads` | 4x2 | One-tap pads for the standard folders | That folder, opened in a tab |
| **Index** | `stat` | 2x2 | Indexed item count and when the index was last built | Index manager; LARGE adds a **Rebuild** button |

### Sizes

Every widget hands the launcher one layout per size in a single update
(`RemoteViews(Map<SizeF, RemoteViews>)`, API 31+, which is Fylz's minSdk). The launcher picks the
layout itself on every placement and resize, without waking Fylz. The sizes are the classic
launcher-cell sizes, defined once in `widget/WidgetSize.kt`:

| Layout | Ideal size | About | Template layout |
|---|---|---|---|
| COMPACT | 110x40 dp | 2x1 | `widget_compact` (one line, optional bar, one tap target) |
| SMALL | 110x110 dp | 2x2 | `widget_stat`, or `widget_pads_small` |
| MEDIUM | 250x110 dp | 4x2 | `widget_list_medium` (two one-line rows, one tap target), or `widget_pads_medium` |
| LARGE | 250x180 dp | 4x3 and taller | `widget_list_large` (up to two 48dp rows that can be tap targets), or `widget_pads_large` |

A launcher switches to a layout as soon as the widget reaches that layout's ideal size, so each
widget's fullest content is sized to fit that exact height at normal font size. `WidgetFitTest`
checks this against the layouts' own dimensions (see "Accessibility and layout rules").

What each size adds:

- **Space.** COMPACT: free space on internal storage, with a bar. SMALL: adds the total, the share
  used and an "as of" time. MEDIUM: two volumes as rows with bars. LARGE: the same two volumes as
  two-line rows (total, share used, read-only) that each open their own volume, plus "and N more".
- **Backups.** COMPACT: the overall status with its time ("✓ Backed up · Today 02:04"). SMALL: adds
  the next window. MEDIUM: a status row and a "next" row (the next daily window, or the new-media
  count against the plan's threshold, with a bar). LARGE: a status headline, its time, the last
  success when the headline isn't one, the next window, and one plan row (outcome, time, and the
  plan's schedule as the second line) for the most urgent plan: one needing attention first, then
  one running. Other plans are "and N more".
- **Recovery.** COMPACT: whichever matters most (operations needing attention, else Recycle Bin
  items, else "Nothing needs attention"), and the tap goes to that thing. SMALL: with operations
  needing attention, their count ("Needs attention · 2 operations", tap: Activity) with the bin as
  the caption; otherwise the bin's item count and size (tap: Recycle Bin). MEDIUM: a Recycle Bin row
  and an attention row. LARGE: the same rows with a second line each (the oldest recycled item's
  date; the newest operation awaiting the user by kind, item count and reason), the interrupted
  count in the header, and a tap of their own.
- **In progress.** COMPACT: the first task's verb, percentage and "as of" time, with a bar. SMALL:
  the percentage as the headline, the task as the caption. MEDIUM: two tasks with bars. LARGE: the
  same two, each with bytes done of the total. When idle it shows the last finished operation.
- **Quick folders.** COMPACT: Downloads as a single target. Below the `pads` template's 2x2 minimum
  it collapses to one target, as the strategy requires. SMALL: two pads. MEDIUM: four. LARGE: six.
  Without All files access, every size shows "Needs access" instead of pads (see States).
- **Index.** COMPACT: status word and item count. SMALL: the count as the value under "Indexed
  items", and the status ("Built · Today 02:10"). MEDIUM: the count in the header, a status row
  with the build time, and a folders/smart collections row (or "Stopped at a safety limit"). LARGE:
  the count as the headline, the status line, the folders/smart collections row with the
  safety-limit note, and a **Rebuild** button when a rebuild can start (set up, not paused, not
  already running or queued).

## Data sources

| Widget | Reads | Model (pure, JVM-tested) | Renderer |
|---|---|---|---|
| Space | `FylzFilesDocumentsProvider.discoverVolumes()` + `File.usableSpace/totalSpace` (the numbers the storage home shows) | `SpaceWidgetModel` | `SpaceRenderer` |
| Backups | `BackupStore.plans()`, `runs()`, `mediaState()` | `BackupsWidgetModel` | `BackupsRenderer` |
| Recovery | `RecycleBinStore.list()`, `OperationJournal.list()` | `RecoveryWidgetModel` | `RecoveryRenderer` |
| In progress | `OperationJournal.list()`, running runs from `BackupStore.runs()` | `OperationsWidgetModel` | `OperationsRenderer` |
| Quick folders | `SafStorageProvider.STANDARD_DIRECTORIES`, `FullAccessPermission`, whether each folder exists | `FoldersWidgetModel` | `FoldersRenderer` |
| Index | `LocalIndexStore.state()`, `scopes()`, `collections()`, and WorkManager's state for `LocalIndexScheduler.WORK_NAME` | `IndexWidgetModel` | `IndexRenderer` |

Everything lives in `app/src/main/java/io/github/mbaliga/fylz/widget/`. Renderers map models to the
shared template specs in `WidgetTemplates.kt`. No widget lays out its own views, so all six follow
the same size, touch and text rules. The templates can move to a shared kit later unchanged.

Some details follow the app's own rules:

- A backup run that is still marked running after the backup lease timeout (6 h, as in `BackupStore`)
  is shown as **Did not finish**, not as running. Skipped runs (threshold not reached, plan busy) are
  bookkeeping and never count as "the last backup".
- An operation needing attention is worded by its error code: `PROCESS_INTERRUPTED` → Interrupted,
  `MOVE_SOURCE_DELETE_PENDING` → Originals not removed, anything else → Review.
- A Recycle Bin item without a recorded size makes the total "at least …".
- The "next window" is the next time a daily plan's window opens. WorkManager still waits for the
  plan's conditions (charging, idle, network…), so the widget says "Next window", not "Next backup".
- The index says **Built** with its build time, never "up to date". A rebuild is a one-time
  WorkManager job and nothing follows the folders between rebuilds, so files added since the last
  build are not in it. The count is of indexed **items**, as the index manager says, because
  folders are indexed entries too.

## Refresh: pushed, never polled

`updatePeriodMillis` is `0` for every widget. Fylz redraws its widgets when its data changes. The
stores announce each write through `model/StoreChanges` (a one-way hook with no widget dependency);
`FylzApplication` installs the listener that maps each store to the widgets showing its data
(`FylzWidgets.onStoreChanged`).

| Change | Hook | Widgets redrawn |
|---|---|---|
| Any operation-journal write (copy, move, recycle, restore, archive, extract, progress, recovery after process death) | `OperationJournal.persist` | In progress, Recovery, Space, Quick folders |
| Recycle Bin manifest write | `RecycleBinStore.persist` | Recovery, Space |
| Backup plan, run, snapshot or media-count write (not lease refreshes) | `BackupStore.writeArray` / `writeMediaStates` | Backups, In progress |
| Any index metadata write | `LocalIndexStore.atomicWrite` | Index |
| Leaving the app | `MainActivity.onStop` | all |
| Volume mounted, unmounted, ejected or removed | `MEDIA_*` broadcasts to the Space provider | Space |
| Clock, time zone or locale change | `TIME_SET` / `TIMEZONE_CHANGED` / `LOCALE_CHANGED` to every provider | that provider's widgets |
| Midnight, a backup's daily window passing, or a running backup's lease lapsing | one inexact, non-waking `AlarmManager.set(RTC, …)` per widget kind → `WidgetTickReceiver` | that kind |
| No journal write for 15 minutes while a copy, move or check is running or paused | the same alarm (In progress watchdog) | In progress |

The watchdog retires dead work. If Fylz's process dies mid-copy (an OEM task killer, a crash), the
journal still says the copy is running, and nothing would write again. Fifteen minutes after the
last write the alarm starts Fylz, and building an `OperationJournal` in the new process runs its
existing once-per-process recovery, which marks the copy as interrupted. While the copy is alive it
keeps writing, which pushes the alarm back, so the watchdog never fires during healthy work.

Requests are coalesced for one second (`FylzWidgets.requestRefresh`), so a copy that writes its
journal on every chunk redraws the home screen about once a second. Kinds with no widget placed read
no data. All reads run off the main thread: the providers use `goAsync()` and a single background
`HandlerThread`. No exact-alarm permission is used. A late redraw is harmless because every timed
value is printed with its day word ("Today 02:04", "Mon 08:00") or an "as of" time. Both stay true
until the next redraw.

**No cache.** Each redraw reads the stores again. The widgets write no file of their own, so nothing
new sits outside the existing backup exclusions (`backup_rules.xml` / `data_extraction_rules.xml`
already exclude the journal, recycle manifest and backup state). If a cache is ever added, it goes
under `Context.noBackupFilesDir`.

## Taps

All taps are explicit `PendingIntent`s to Fylz's own components, `FLAG_IMMUTABLE |
FLAG_UPDATE_CURRENT`, with a request code unique per widget id and slot (PendingIntent identity
ignores extras, so shared codes would overwrite each other's destinations). There are no URL schemes.

- Space, Backups, Recovery, Quick folders → `MainActivity` with the widget action and `WidgetLaunch`
  extras. Because `MainActivity` is exported, it reads extras only for that action and inside a
  guard (on Android 12 a Bundle unparcels every value on first read, so a foreign Parcelable would
  otherwise crash the launch), and `WidgetLaunch.parse` treats them as untrusted. It accepts a
  folder only from the six standard directories and a volume only if it is mounted now, and ignores
  anything else. `FylzV1App` then:
  - **Home** shows the storage home.
  - **Recovery** opens the bottom Recovery room. Fylz has no deeper route to the backup plans list:
    that list is an overlay opened from the room's "Backup plans" card, so a Backups tap lands one
    tap short of it.
  - **Recycle Bin** opens the Recycle Bin dialog.
  - **Folder / volume** opens a tab directly when All files access is granted. Without it, the tap
    lands on the storage home, where the access card and picker rows are.
- In progress, Recovery's attention targets → `OperationHistoryActivity` ("Activity"). Index →
  `IndexManagerActivity`. Both reuse the screen already in Fylz's task (`CLEAR_TOP | SINGLE_TOP`),
  so repeated taps never stack copies of it.
- **Rebuild** → `WidgetActionReceiver` (not exported). It checks the action, calls
  `LocalIndexScheduler.rebuild()` (the same WorkManager job the index manager starts) and redraws
  the Index widget.

Rows are separate tap targets only in the LARGE layout, where each is at least 48dp tall. A MEDIUM
row is too short to be a target of its own, so at 4x2 the whole widget is one target: the storage
home (Space) and the Recovery room (Recovery) both reach everything the rows show.

The providers are exported, as launchers require. Any app can therefore send one an
`APPWIDGET_UPDATE` naming arbitrary widget ids, so each provider redraws only the ids the system has
bound to it (`WidgetIds.owned`); a forged broadcast cannot draw one widget's content into another.

## Privacy

A home screen is a public surface. Fylz's widgets show **counts, sizes, times and standard folder
names only**:

- No file or folder names, paths, URIs or previews. Journal items and recycle records carry display
  names and URIs; the models reduce them to an operation kind and an item count before anything is
  drawn. Unit tests feed real names in and assert that none come out.
- Backup **plan names are not shown**, because they often name private folders. Plans are described
  by their trigger ("Daily at 02:00", "After 12 of 25 new photos or videos"). Backup failure
  messages are not shown either, since they can quote a folder name.
- Volumes are named by kind ("Internal storage", "Removable storage 2"), never by the volume's own
  description, which for removable media can be a filesystem label the user chose.
- The Index widget never shows the stored error text (it can name an indexed folder), only "Last
  rebuild failed".
- Quick folders names only the platform's standard folders, with the storage home's own labels
  (Downloads, Camera (DCIM), Documents, Pictures, Movies, Music). It reads only whether each one
  exists; nothing inside is listed or counted.

Because nothing personal is shown by default, there is no "counts only" setting to configure.

## Colour, shape and words

The widgets use the MOSS palette (`res/values/widget_colors.xml`, night values in
`res/values-night/`). They keep this fixed palette instead of wallpaper colours, so no role changes
with the wallpaper. Text contrast is at least 5.9:1 day and night, and bar against track at least
5.2:1. Colour carries no state: the moss accent marks titles and bar fills only. Every state pairs a
glyph with a word:

| Glyph | Used for |
|---|---|
| ✓ | Backed up, Built, Nothing needs attention |
| ✕ | Failed, Last rebuild failed, a widget that couldn't read its data |
| ◆ | Needs attention, Did not finish |
| ◐ | Running: Backing up now, Copying…, Rebuilding |
| ○ | Waiting, Checking, Cancelled, Not run yet, Next window |
| ‖ | Paused |

Glyphs are hidden from TalkBack (`importantForAccessibility="no"`). The word next to each one is
read instead, and every tap target has a content description that begins with what the tap opens
("Open file activity in Fylz", "Open the Recycle Bin in Fylz", "Open the storage home in Fylz").

## States

| State | How it shows |
|---|---|
| First run / empty | "No backup plans yet · Set one up in Recovery", "Nothing in progress", "Recycle Bin is empty", "Index · Not set up · Add folders in Index", "No storage volume is mounted", "No standard folders found" |
| Error | ✕ plus "Couldn't read storage", "Couldn't read backup plans" and so on; the tap opens Fylz |
| Stale | From 2x2 up, every widget whose data can change without Fylz seeing it prints "as of HH:MM" (Space, Backups, Recovery, In progress, Index); In progress prints it at 2x1 too, the other 2x1 layouts have no room. Time-dependent words are redrawn at midnight, a backup lease's end and the In progress watchdog. Quick folders prints no data that can lapse: it shows no counts |
| Withheld | Not reachable: nothing personal is ever drawn (see Privacy) |
| Not connected | Quick folders without All files access: at every size it says "Needs access" / "Needs All files access" instead of drawing pads, and the tap opens the storage home, where the access card is |
| Loading | `widget_loading` ("Fylz · Reading…") until the first update arrives |

## Accessibility and layout rules

- The root of every widget layout is `@android:id/background`, with the system widget corner radius
  (`system_app_widget_background_radius`) and clip-to-outline.
- Text is in `sp`, 12sp or larger (14sp for row titles), single-line rows ellipsize at the end, and
  captions take at most two lines. Gravity and padding use start/end, so layouts work right-to-left.
- Pads, LARGE list rows and the Rebuild button are at least 48dp tall. MEDIUM rows are never tap
  targets of their own.
- Each panel layout pins its title and footer and puts everything else in a weighted body that clips
  at its own bottom. At larger font scales, or when a caption wraps at a narrow width, the last row
  is cut, never the footer with its "as of" time and "and N more".
- At normal font size every widget's fullest content fits its layout's ideal size
  (`WidgetFitTest`). That check uses Roboto's line metrics and counts captions by their explicit
  lines, so at 1.3x and 2x font scale, or at the narrowest widths, expect the last row or the second
  caption line to be cut. Real launcher cells are usually larger than these minimums.

`WidgetResourceContractTest` checks the RemoteViews-safe view classes, the background root, the
minimum text size, pad, row and button heights, the pinned footer, that every view `WidgetTemplates`
sets exists in its layout, the provider declarations, and that day and night palettes match.

## Tests

`app/src/test/java/io/github/mbaliga/fylz/widget/` holds 63 JVM unit tests:

- `WidgetSizeTest`: which layout a launcher picks for common grid sizes (restating the platform's
  nearest-fitting rule), and capacities never shrinking as the size grows.
- `WidgetFitTest`: each widget's fullest content against the height of the size it is drawn at,
  from the layouts' own dimensions, plus row capacities against the rows the layouts hold.
- `WidgetTimeTest`: day words by calendar day, the next daily window (including a daylight-saving
  gap), midnight, and the next redraw time.
- `SpaceWidgetModelTest`, `BackupsWidgetModelTest`, `IndexWidgetModelTest`, `FoldersAndLaunchTest`:
  the mapping from app data to what each widget shows, empty, stale, not-connected and error
  decisions, the most urgent backup row, lease-end redraws, privacy, and launch-extra validation.
- `RecoveryAndOperationsModelTest` (Robolectric, for real `Uri`s): recycle and journal mapping, the
  In progress watchdog, plus the name/URI leak checks.
- `WidgetRefreshTest`: a provider redraws only widgets bound to it, and each store write redraws the
  widgets showing its data.
- `WidgetResourceContractTest`: the layout and manifest rules above.

## Owner-verify (device only)

None of this has been seen on a device. On the phone and on each launcher you use (Pixel Launcher /
One UI / Nova / Orrery Home):

1. All six widgets appear in the widget picker under Fylz, with their preview layouts. They should
   look like the widgets, not a blank card.
2. Placing each widget draws real data within a second or two, not the "Reading…" loading layout.
3. Resizing through 2x1, 2x2, 4x2 and 4x3+ switches between the four layouts, and nothing is cut off
   at normal font size. At 1.3x and 2x font scale the "as of" footer stays visible and only the last
   row may be cut.
4. Day and night system themes both render legibly.
5. Taps: Space → storage home, and at 4x3 a volume row → that volume. Backups → Recovery room.
   Recovery at 4x2 → Recovery room; at 4x3 the bin row → Recycle Bin dialog and the attention row →
   Activity; at 2x2 the tap follows the headline (Activity when something needs attention, else the
   Recycle Bin). In progress → Activity. Each folder pad → that folder. Without All files access,
   Quick folders says "Needs access" at every size and opens the storage home. Index → Index
   manager. Rebuild → the widget turns to "Waiting to rebuild" or "Rebuilding", then "Built" with the
   time.
6. Tapping In progress or Index several times, with Back in between or not, leaves one Activity or
   Index manager screen, not a stack of them.
7. Live updates: start a large copy and watch In progress advance (about once a second). Recycle a
   file and watch Recovery and Space change. Run a backup and watch Backups and In progress.
8. End Fylz's process mid-copy without a force-stop (a force-stop also cancels Fylz's alarms): let
   an OEM task killer do it, or on a debug build run
   `adb shell run-as io.github.mbaliga.fylz kill -9 <pid>`. Within roughly 15 minutes (longer
   under Doze) In progress stops showing the copy and Recovery shows it as interrupted.
9. Insert or remove an SD card or USB drive: Space adds or drops the volume.
10. After midnight, "Today" times become "Yesterday" (expect a delay under Doze).
11. TalkBack reads each widget's description and each row or pad's action, starting with what the
    tap opens, and never a glyph name.
12. OEM battery managers (One UI and others) still deliver the midnight, backup-window and watchdog
    redraws. After a force-stop, Android 15+ greys the widgets out until Fylz is opened again; that
    is platform behaviour.
13. A long-running backup started by WorkManager while Fylz is closed still updates the widgets.
