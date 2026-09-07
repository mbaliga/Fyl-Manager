# Fylz against the desktop file managers, and against Apple Files

Asked for by the owner: *"compare to the Apple Files app to understand the quality and UX I
expect… then compare with desktop file managers of Mac and Windows… make sure we meet and exceed
the desktop file managers of Apple, Windows and Fedora/KDE/Ubuntu."*

Two different questions, answered separately, because they have different answers.

- **Apple Files** is the *interaction-quality* bar. It has fewer features than any desktop manager
  here. What it has is a standard for how a touch file manager should feel, and that is what the
  owner is pointing at.
- **Finder, File Explorer, GNOME Files, Dolphin** are the *feature* bar. Meeting them is a
  checklist; exceeding them is where Fylz already stands on several axes and is behind on a few.

## How the Fylz column was built

Every Fylz entry below was read off the code, not off `ROADMAP.md`. Where a capability exists as a
type but nothing calls it, it is recorded as **absent**, with the dead code named. That distinction
is the whole point of the exercise — three of the gaps ranked at the end are exactly this, and a
survey that trusted the type list would have scored them as present.

---

## Part 1 — the Apple Files bar (interaction quality)

Apple Files' drag is the reference the owner has been pointing at all along. Broken into its parts:

| Interaction | Apple Files | Fylz today |
| --- | --- | --- |
| Long-press lifts a proxy that follows the finger | yes | yes — a spring-driven cluster, with squash/stretch and bank off live velocity |
| The proxy is visible past your own thumb | yes | **yes, as of `edf4cdc`** — 96dp card drawn 60dp above the contact point |
| Multiple items ride as a countable pile | yes, with a count badge | yes — fanned stack up to 5 cards, `+N` badge beyond that |
| **Pick up more items mid-drag** (tap others with a second finger) | yes | **no** |
| **Spring-loaded folders** (hover over a folder mid-drag to open it) | yes | **no** |
| **Drop onto a folder in the listing** | yes | **no** — see below |
| Drop onto a destination outside the listing | sidebar, other window | tab chips, recycle tab, and a five-slot corner arc (clipboard, move, shelf, new folder, compress) |
| Long-press context menu with a live preview | yes | yes — `EntryGestures`, `QuickLook` |
| Space-bar / tap preview of any file | Quick Look | `QuickLook` plus a much deeper preview stack (below) |
| Recently Deleted with a retention period | 30 days, fixed | recycle bin with a **user-chosen** retention period |

### The one that matters most: no drop onto a folder row

`clusterReleased()` resolves a drop against exactly three things — the tab chips
(`tabDropTarget`), the recycle tab (`overTrashTab`), and the drag layer's corner arc
(`DropTargetPolicy`). A folder *in the listing you are looking at* is not a drop target.

That is the most natural gesture in every file manager on this page, desktop and touch alike, and
it is the one Fylz cannot do. Dragging into a subfolder currently means opening it in a second tab
first and dropping on that tab's chip — which works, and is genuinely faster for repeated moves,
but it is an accelerator standing in for the basic case rather than beside it.

Spring-loading follows from the same seam: once a folder row is a drop target, dwelling on one to
enter it is a timer on top of the hit-test that already exists.

---

## Part 2 — the feature matrix

`•` present · `—` absent · `~` partial, qualified in the notes

| Capability | Finder | Explorer | GNOME Files | Dolphin | **Fylz** |
| --- | :-: | :-: | :-: | :-: | :-: |
| **Navigation & layout** | | | | | |
| Multiple view modes | • | • | • | • | • (list, grid, details, stacks, canvas) |
| Tabs | • | • | • | • | • |
| Split / dual pane | — | — | — | • | **—** ¹ |
| Breadcrumb / path bar | • | • | • | • | • |
| Sidebar of favourites | • | • | • | • | • (library rail) |
| Sort by name/size/date/type | • | • | • | • | • |
| Group / section a listing | • | • | ~ | • | • (stacks: kind, date, size, letter) |
| Hidden-file toggle | • | • | • | • | • |
| Density / icon-size control | • | • | • | • | • (compact / comfortable / detailed) |
| **Operations** | | | | | |
| Copy / move / rename / delete | • | • | • | • | • |
| Conflict policy on collision | • | • | • | • | • (ask / keep both / replace / skip) |
| **Undo / redo of file operations** | • | • | • | • | **—** ² |
| Recycle bin with restore | • | • | • | • | • |
| Automatic bin retention | ~ ³ | ~ ³ | — | — | • (user-chosen period, with a worker) |
| Batch rename | • | • | • | • | • |
| Create archive | • (zip) | • (zip) | • (zip/tar/7z) | • (Ark) | • (zip) |
| Browse *inside* an archive | ~ ⁴ | • | • | • | • (zip, 7z, tar.\*, gz/bz2/xz/lzma, and more via the universal reader) |
| Extract archive to a folder | • | • | • | • | **~ ⁵** (zip only) |
| Permissions editing | • | • | • | • | **—** ⁶ |
| Symlink / alias creation | • | ~ ⁷ | • | • | **—** ⁶ |
| **Finding things** | | | | | |
| Name search | • | • | • | • | • |
| Full-text / indexed content search | • | • | • | • | • (`LocalIndexStore`, scheduled) |
| Boolean / regex query grammar | ~ ⁸ | ~ ⁸ | — | ~ | • (`WORD FACET PHRASE REGEX SEMANTIC AND OR NOT`, with parens) |
| Saved searches / smart folders | • | ~ | — | • | • (`SmartCollectionEngine`, rule-based) |
| Tags | • | — | ~ ⁹ | • | • |
| Ratings / comments on files | — | — | — | • | — |
| **Previews** | | | | | |
| Preview pane / Quick Look | • | • | • | • | • |
| Preview of 3D meshes, CAD/DXF | — | ~ | — | ~ | • (`MeshWireframePreview`, `DxfPreviewParser`) |
| Preview of spreadsheets / decks without the app | ~ | ~ | — | ~ | • (`WorkbookReader`, `PresentationDeckReader`) |
| **Remote & network** | | | | | |
| SMB | • | • | • | • | • |
| SFTP | — | — | • | • | • |
| WebDAV | • | • | • | • | • |
| **S3-compatible object storage** | — | — | — | — | **•** |
| **Beyond the desktops** | | | | | |
| Duplicate finder (content-hashed) | — | — | — | — | **•** (SHA-256 grouping) |
| Per-file version history | ~ ¹⁰ | ~ ¹⁰ | — | — | **•** (`FileHistoryStore`) |
| Scheduled backup of app state | — | ~ | — | — | **•** |
| Per-folder icon, colour **and stickers** | ~ ¹¹ | ~ ¹¹ | ~ | ~ ¹¹ | **•** |
| Home-screen widgets | — | • | — | — | **•** (folder shortcut, quick actions, storage) |
| On-device AI model management | — | — | — | — | **•** |
| **Keyboard** | | | | | |
| Shortcut-driven operation | • | • | • | • | **—** ² |
| **Terminal here** | • (Services) | • | ~ | • | — ⁶ |
| Version-control status in the listing | — | — | — | • | — |

**Notes**

1. `KeyboardCommand.TOGGLE_SECONDARY_PANE` and `COPY_TO_OTHER_PANE` exist in
   `KeyboardShortcutPolicy` and are covered by its unit test. Nothing implements a second pane —
   `grep` for `secondaryPane` in `app/src/main` returns nothing. The policy names a feature the app
   does not have.
2. Same shape. `KeyboardShortcutPolicy` resolves 22 commands including `UNDO`, `REDO`, `SELECT_ALL`
   and `PASTE`, and has a passing test — but **no composable or key handler consumes it.** Fylz has
   a keyboard-shortcut table and no keyboard shortcuts. Separately, `OperationJournal` is a record
   and crash-recovery store, not an undo stack: it has `put`, `remove`, `clearFinished`, and no
   inverse operation of any kind. Undo is absent twice over.
3. macOS can auto-empty the Trash after 30 days; Windows can under Storage Sense. Neither is a
   per-user retention *period* the way `RecycleBinRetentionPeriod` is.
4. Finder expands an archive rather than browsing it in place.
5. Asymmetric, and worth knowing: Fylz *browses* 7z, tar.gz/bz2/xz, bare gz/bz2/xz/lzma and more,
   and can extract a single member out of any of them for preview — but `ArchiveService` is the
   only path that unpacks a whole archive to a folder, and it handles zip alone. A user who can
   open a 7z and read its contents cannot unpack it.
6. Android's storage model, not a Fylz omission — see "Excluded on purpose" below.
7. `mklink` at the command line; not in the Explorer UI.
8. Spotlight and AQS both have real query syntax; neither exposes regex over names the way
   `FylzSearch` does.
9. Starred files rather than arbitrary tags.
10. Finder Versions is per-application, for documents whose app opted in. Windows Previous Versions
    needs File History or restore points configured.
11. All three allow a custom folder *icon*. None allow stickers layered on the folder face.

---

## Part 3 — where Fylz already exceeds all four

Not aspirationally; these are in the code today.

1. **A duplicate finder that hashes content.** None of the four ships one. `FileTools.findDuplicates`
   groups by SHA-256 and size, and `DuplicateCleanupPolicy` refuses a selection that would recycle
   every member of a group — you cannot lose the last copy by mis-tapping.
2. **Per-file version history**, unconditionally, for any file, not just documents from a
   cooperating app.
3. **Preview depth.** 3D meshes, DXF/CAD, spreadsheets, presentation decks and archive interiors
   all render without the originating application. Dolphin gets closest and needs plugins.
4. **S3-compatible object storage as a first-class location.** No desktop manager here mounts a
   bucket without third-party software.
5. **A real query grammar** — facets, phrases, regex, semantic terms, boolean operators and
   grouping, in one parser.
6. **Folder identity.** Icon, colour and stickers, per folder, persisted.
7. **Bin retention as a user setting** rather than a system-wide toggle.

## Part 4 — where Fylz is behind all four, ranked

Ranked by what a user loses, not by implementation cost.

1. **Undo.** Every one of the four can take back a move, a rename or a delete. Fylz cannot take
   back anything. This is a data-safety gap before it is a convenience gap, and it is the single
   biggest hole in the matrix. The recycle bin covers deletion only; a wrong *move* of 400 files
   into the wrong folder is currently unrecoverable except by hand. `OperationJournal` already
   records every operation with its items — the inverse is derivable from what is stored.
2. **Drop onto a folder in the listing** (Part 1). The most-used gesture in a file manager, missing.
3. **Extraction beyond zip.** Fylz will happily open a `.7z` or a `.tar.gz`, show you every file
   inside it, and preview any one of them — and then cannot unpack it. Every one of the four can.
   The reader half is already written (`ArchiveEntryReader` extracts a member from all four
   families); what is missing is the loop that walks the whole listing instead of one entry, and a
   destination picker, both of which `ArchiveService.extractZip` already models.
4. **Keyboard shortcuts.** Fylz already targets tablets and desktop-mode Android, where a keyboard
   is normal. The policy is written and tested; nothing reads it. Wiring is a key handler, not a
   design problem.
5. **Spring-loaded folders.** Follows directly from 2.
6. **Pick up more items mid-drag.** Apple Files only; none of the desktops do it. Lower priority,
   but it is the thing that makes their multi-select drag feel effortless.

## Part 5 — excluded on purpose

Not gaps. Recording them so they are not re-litigated:

- **POSIX permissions, chmod, symlinks, "open terminal here".** Android's Storage Access Framework
  does not expose them for the trees Fylz browses, and a file manager that offers a permissions
  dialog which silently fails is worse than one that does not.
- **Version-control status in the listing** (Dolphin). Real, and genuinely out of scope for a
  phone file manager.
- **Ratings and comments** (Dolphin). Tags already carry the same intent with less ceremony.
- **A general "Add a location" entry point.** Shelved at the owner's request. Roots still arrive
  through SAF folder selection and through the remote-connection dialog; what is shelved is a
  single unified "add a place" affordance sitting beside them.

## Part 6 — recommendation

In order, and each one is a self-contained build:

1. **Undo, over `OperationJournal`.** Derive the inverse of `MOVE` and `RENAME` from records that
   already exist; route it through `FileOperationService` so it takes the same conflict policy and
   the same progress line as any other operation. Surface it as the standard toast-with-Undo on
   every completed operation, and wire `KeyboardCommand.UNDO` to the same call.
2. **Folder rows as drop targets**, then spring-loading on top of the same hit-test.
3. **Whole-archive extraction for the families Fylz already reads** — generalise
   `ArchiveService.extractZip` over `ArchiveFormats.Family` rather than adding a second extractor
   beside it, so bounds checking, the safe-path guard and the rollback stay in one place.
4. **A key handler for `KeyboardShortcutPolicy`** — or, if a second pane is not wanted, delete
   `TOGGLE_SECONDARY_PANE` and `COPY_TO_OTHER_PANE` from the enum rather than leaving the app
   claiming a feature it does not have.
