# Fylz — product and architecture

A file manager for Android built around a hard problem the platform creates: **there is no single
API that reaches every place a user's files live.** `MANAGE_EXTERNAL_STORAGE` covers local shared
volumes and nothing else; the Storage Access Framework is the only route to cloud providers, USB
and third-party document providers; and network shares are neither. Fylz's architecture is mostly
a considered answer to that.

- **Package**: `io.github.mbaliga.fylz`
- **UI**: Jetpack Compose, Material 3, `dev.aarso:hyle` design tokens
- **Navigation**: `dev.aarso:cell-shell` — the constellation's shared spatial shell
- **Reliability**: `dev.aarso:crash-recovery`
- **Kotlin**: 2.1.20 (see §5 — this diverges from the rest of the constellation)

---

## 1. Features

### Storage that actually reaches everything

Fylz runs **two storage backends at once**, behind one `StorageProvider` interface:

| Backend | Reaches | Needs |
|---|---|---|
| `FileStorageProvider` | Local shared volumes, whole-volume browsing, no picker round-trips | `MANAGE_EXTERNAL_STORAGE` |
| `SafStorageProvider` | Cloud providers, USB/OTG, removable cards, third-party document providers, individually granted subtrees | Nothing; always available |

SAF is **not a fallback**. It stays live even with full access, because it is the only way to
reach anything that is not a local shared volume. What it *stops* contributing under full access
is duplicates: with the File backend live, a SAF entry for a folder the user can already open is a
second row for one folder, not a second way in. `StorageRoot.isOnSharedVolume` derives that
distinction from the tree URI rather than a stored flag, so it stays correct for roots built by
any provider.

Fylz also **publishes its own `DocumentsProvider`** (`FylzFilesDocumentsProvider`), so other apps
can browse Fylz's locations through the system picker.

### Network locations

WebDAV, SFTP, SMB and S3-compatible object storage (`network/`), each behind a `RemoteProvider`.
Connections are stored locally in `RemoteConnectionStore`.

### File operations, with a journal

Copy, move, rename, batch rename, delete, create, and a **recycle bin** (`.fylz-trash` inside the
root, so a recycled file never leaves the volume it was on).

Every operation is written to an `OperationJournal`. This is the backbone of the Recovery room:
an interrupted copy or move is a recorded, resumable fact rather than a lost one.
`OperationRetryPolicy` decides what can be safely retried — a half-finished move needs its
cleanup step finished, not its copy repeated — and anything it cannot prove safe is offered as
un-retryable rather than guessed at.

### Archives

Browse *inside* ZIP archives without extracting (`ArchiveBrowserService`), extract with an
explicit space check (`ArchiveSpacePolicy`) and an extraction policy that guards against path
traversal. Create archives from a selection.

### Previews and inspection

A format registry (`FileFormatRegistry`) drives previews for text, Markdown, images, PDFs and
structured containers. Beyond the ordinary: DXF and mesh geometry previews with wireframe
rendering, and a structured-container inspector that reads document formats which are really ZIPs
underneath.

Text files open in an **editor**, not just a viewer — edit and save in place.

### PDF tools

Page-level manipulation, merging, and OCR into searchable PDFs (`SearchablePdfService`). Scanning
from the camera to PDF (`ScanPdfService`).

### Search

Two modes. In-folder filtering is immediate. **Recursive search** (`RecursiveSearchEngine`) walks
the tree with progress reporting and can search content, not just names. A separate opt-in
**local index** (`index/`, `library/LocalFileIndex`) makes repeat searches fast and backs smart
collections.

### Organisation

Favourites, tags, smart collections driven by a rule evaluator (`SmartRuleEvaluator`,
`SmartCollectionEngine`), file history (`FileHistoryStore`), duplicate detection and a duplicate
cleanup policy.

### Backup

Scheduled backups with a manifest policy, plus import (`backup/`). `BackupScheduler.reconcile()`
runs at launch so a missed schedule is caught up rather than silently skipped.

### Optional AI (off by default)

Organisation proposals via a bring-your-own-key `AiClient`. An explicit `AiTransmissionPolicy`
governs what may leave the device, keys live in an `ApiKeyVault`, and there is a local model
manager with a signed model catalogue for on-device alternatives.

---

## 2. Interaction patterns

Fylz uses the **fonebrew spatial pattern** via `dev.aarso:cell-shell` — the same shell, the same
motion constants, as Foto Xplorr. See
[Foto Xplorr's `fonebrew-navigation.md`](https://github.com/mbaliga/Foto-Xplorr/blob/main/docs/fonebrew-navigation.md)
for the pattern itself.

### Four rooms

The file browser is *home*. Four surfaces are parked off its edges:

- **LEFT — locations.** Every open location as a word wheel, plus the storage home surface and
  "Add a location…". This replaced a numbered chip row that made "which folder am I in" a
  horizontal scroll through abbreviations.
- **RIGHT — tools and settings.** Recycle Bin, remotes, WebDAV, Tools, the index manager, theme.
  None of these act on the folder you are looking at, which is exactly why they are not in the
  folder's overflow menu.
- **TOP — details.** What you are looking at, described: a tree of where it lives, then kind,
  size, timestamp, MIME type, path and tags. Read-only apart from the tree rows, which move you
  to a folder already on screen.
- **BOTTOM — actions.** Everything that changes a file: the selection's actions, the folder's own
  (new folder, new text file, scan to PDF, find duplicates, the AI proposal), and recovery last.

The vertical pair is meant to be read together: **up is what you are looking at, down is what to
do about it.** The horizontal pair is *where else you could be* and *what the app itself can do*.

Dragging an edge lifts the browser, shrinks it slightly and parts it to reveal the room; the
browser stays alive behind, and dragging it back is the way out. Rooms push no back-stack entry;
Back closes them.

The room arrives scaled from 0.97 and reaches full size exactly as the drag completes — the room
half of the pattern's reveal note, so both halves of the motion finish together instead of a
full-size panel sliding under a shrinking card. Scale only, never a fade: material that flows is
material that was already there. It is implemented in `FylzV1App` rather than in `cell-shell`
only because Fylz consumes that module; it belongs beside the card's motion, and the melt's edge
distortion is still unbuilt on both sides.

The top room does not change the pull-down rule that made it possible: the gesture belongs to it
and to nothing else, so pull-to-refresh stays banned and refresh stays a shake.

### The edge scrubber

A strip down the right side of the listing. Unlike a photo timeline, this list has **no single
natural key** — the same folder is ordered by name, size, date or type depending on what the user
picked. So the scrubber's stops follow `SortSpec.field`:

| Sort | Stops |
|---|---|
| Name | First letter; everything non-alphabetic collapses into one `#` bucket |
| Size | Bands by powers of 1024, with directories in their own band |
| Date modified | By month, with one bucket for the null/zero timestamps providers routinely report |
| Type | By extension |

The bucketing is not cosmetic. A folder of `2024-01-03.log … 2024-12-30.log` sorted by name would
otherwise produce one stop per file — a strip with a thousand identical labels, which maps
nothing. Nothing reads the sort *direction*: cutting where the displayed key changes is correct
either way, so there is no direction branch to get wrong.

### Refresh is a shake

Same rule as everywhere in the constellation: the pull-down space is reserved, so refresh moved
off the touch plane. The toolbar button stays for anyone who would rather tap.

### The cluster drag and the corner bulges

Press-hold on any **selected** row gathers the whole selection into a card cluster under the
finger; the moment it lifts, the screen's corners grow organic bulges — material swelling out
of the corner, not panels floating over it. **Actions top-left** (clipboard, move tray, new
folder, compress), **trash alone bottom-right**: opposite corners, so a sloppy drop can miss
within a family but never cross from constructive to destructive.

Targets react as the cluster approaches — continuously, driven by one proximity scalar from
`DropTargetPolicy`, so approach and retreat play the same motion both ways. The trash can
tilts, lifts and opens its lid; releasing on it pours the cluster in with a genie squeeze.
The clipboard snaps the files aboard, visibly.

A tray with content keeps a small resting bulge on its corner (count + glyph). Tapping it
expands the tray: an endlessly looped scroll of the staged files (`LoopedCarousel` owns the
wrap-around), **pull a card down** to take it back out (with an equivalent accessibility
action — a gesture is never the only path), and "Paste here" / "Move here" commits into the
folder on screen through the ordinary journaled operations. A paste into a nested folder
resolves the destination by walking display names from the granted root — provider-neutral —
and such operations refuse journal replay rather than risk replaying into the tree root
(`OperationRetryPolicy.isReplayableDestination`).

The trash bulge is the session's can: what went in during this visit, each item offering
**Put back** or **Shred**. Shredding is permanent deletion through the existing
`RecycleBinPolicy` gate, staged behind a confirm with the shredder animation — and its copy is
deliberately honest: flash translation and wear levelling mean no app can promise forensic
erasure, so Fylz says "as gone as software can honestly make it" and never "securely erased".

### Selection, and where its actions live

Selecting entries puts a one-line summary across the bottom of the list — how many, "Clear", and
"Actions" — and the actions themselves are rows in the bottom room. The eleven-button horizontal
scroller this replaces covered the listing it acted on and needed a sideways swipe to read, so
its right-hand half was effectively hidden.

`SelectionActionPolicy` decides what applies, and actions appear only when they do: "Rename"
needs exactly one entry, "Batch rename" at least two, "Extract" exactly one archive, "PDF tools"
an all-PDF selection, and "Share" no folders — `ACTION_SEND` cannot deliver a directory, so
offering it produced a share sheet that silently sent nothing. Inapplicable actions are absent
rather than greyed out; the room is a list read top to bottom, and a shorter one is a faster one.

### Adaptive layout

At ≥900dp Fylz becomes a **three-pane desktop-style workspace**: a library rail on the left, the
file list in the middle, a docked preview on the right. Below that width the preview floats over
the list instead. `workspace/` also carries keyboard-shortcut and dual-pane policies for
large-screen and desktop-mode use.

---

## 3. Information architecture

```text
Storage home surface          ← the entry point when no location is open
│   ├── Granted folders        (SAF subtrees that add reach)
│   ├── Add a location         (picker shortcuts — hidden under full access)
│   ├── Removable storage
│   └── Other providers        (cloud, USB, third-party)
│
└── Location (a tab)          ← an open root, with its own navigation stack
    └── Folder
        └── Entry             ← DIRECTORY | MARKDOWN | TEXT | IMAGE | PDF |
                                ARCHIVE | AUDIO | VIDEO | OTHER
            ├── Preview / editor
            └── Inside an archive → nested entries

Rooms
├── LEFT    Locations wheel
├── RIGHT   Tools & settings
├── TOP     Details → location tree, kind, size, modified, type, path, tags
└── BOTTOM  Actions → selection, this folder, recovery (operations, history,
                      backups, archive tools)
```

Two organising rules:

1. **A location is a workspace, not a route.** Multiple locations stay open at once, each with its
   own folder stack. Switching between them in the left room is lateral movement, not navigation
   into or out of anything.
2. **Capability, not backend, decides what the UI offers.** `StorageCapability` (`CREATE`,
   `RENAME`, `DELETE`, `RECYCLE_BIN`, `RECURSIVE_SEARCH`, `CONTENT_SEARCH`,
   `BROWSE_WITHOUT_PICKER`, `WHOLE_VOLUME`) tells the UI what to show. Downstream services are all
   written against `content://` document URIs, so no service needs to know which backend served a
   file.

---

## 4. Software architecture

### Module map

| Package | Responsibility |
|---|---|
| `storage/` | The two backends, capability model, permission handling, the exported `DocumentsProvider` |
| `data/` | `DocumentRepository` (the read/write surface), archives, scan-to-PDF |
| `operations/` | File operations, the journal, retry policy, recycle bin, duplicate cleanup |
| `browse/` | `SortSpec` and pure sorting; `EntryStops` for the scrubber |
| `search/` | Recursive search engine and query model |
| `index/`, `library/` | Local index, smart collections, organisation engine, metadata transfer |
| `network/` | WebDAV, SFTP, SMB, S3 providers |
| `preview/` | Format registry, DXF/mesh/geometry parsers, container inspectors |
| `pdf/` | Page tools, merging, OCR/searchable PDF |
| `backup/`, `history/` | Scheduled backup and manifests; per-file version history |
| `ai/` | BYOK client, transmission policy, key vault, local model management |
| `workspace/` | Desktop-mode policies: dual pane, keyboard shortcuts |
| `ui/` | `FylzAppShell`, `FylzV1App`, the browser, overlays, theme |

### Layering

```text
UI (Compose)  ──▶  Services (operations, search, archive, pdf, backup)
                        │
                        ▼
                 DocumentRepository  ──▶  ContentResolver (content:// URIs)
                        ▲
                 StorageProvider ×2  ──▶  roots for the home surface
```

The key decision: **every provider hands back document URIs a `ContentResolver` can service.**
Rather than teaching each downstream service a second backend, the capability set tells the UI
what to *offer* while the URI shape stays uniform. Adding a backend does not touch the recycle
bin, the archiver, backup or history.

### Theme ownership

`FylzV1App` owns `FylzTheme`, and everything Fylz renders is composed **inside** it — including
the recovery room, which is passed down as content rather than composed beside the theme.

This is stated as an architectural rule because violating it was a real, visible bug: three
surfaces (`PostV1ToolsActivity`, `IndexManagerActivity`, `MainActivity`'s wrapper) called
`setContent { MaterialTheme { … } }` — the bare platform default — and appeared light inside an
otherwise dark app. An activity of Fylz is themed by Fylz; there is no second place for that
decision to be made.

### Policy objects

A recurring pattern worth noticing: decisions that could be scattered through service code are
extracted into named, pure policy objects — `ArchiveExtractionPolicy`, `ArchiveSpacePolicy`,
`RecycleBinPolicy`, `DuplicateCleanupPolicy`, `OperationRetryPolicy`, `BackupManifestPolicy`,
`AiTransmissionPolicy`, `SmartCollectionPolicy`, `DesktopWorkspacePolicy`,
`KeyboardShortcutPolicy`, `SelectionActionPolicy`. Each is testable without a device, and each
names a decision that would otherwise be an unexamined `if` inside a service.

The rooms follow the same rule for what they *say*, not only what they do: `EntryDetails` and
`locationTree` are framework-free, so the details room's wording and shape are unit-testable —
which matters most for the one surface whose entire job is to be accurate.

### Testing

The pure layers carry the tests: sorting (`sortEntries`), scrubber stops (`EntryStops`), the
details room's facts and tree (`EntryDetails`, `LocationTree`), the selection rules
(`SelectionActionPolicy`), the
shared-volume suppression (`StorageRoot.isOnSharedVolume`, which is quietly bad in *both*
directions if wrong — too eager and a cloud provider vanishes, too shy and the padlock rows come
back), the policies, the archive and backup manifest logic, and the search query model.

CI runs two workflows: `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`, and a
release-readiness job that additionally runs `:app:lintRelease :app:assembleRelease` and captures
the release runtime dependency tree.

### Build composition

`dev.aarso:hyle`, `dev.aarso:crash-recovery` and `dev.aarso:cell-shell` are consumed as **git
submodules + Gradle `includeBuild` dependency substitution**. The explicit `dependencySubstitution`
on the Hyle include is load-bearing — Hyle carries a `:crash-recovery` tombstone declaring the
same coordinate, and declaring any explicit substitution for an included build disables
*automatic* substitution for it, removing the ambiguity that otherwise hard-fails the build.

The Gradle wrapper is pinned at 8.14.3. It was **absent entirely** until recently: a clean clone
could not be built without Gradle already on `PATH`, and nothing pinned which version.

---

## 5. Known limits

- **Kotlin 2.1.20 diverges from the constellation's 2.1.0.** The AGP/Gradle lockstep is codified;
  the Kotlin half is not, and Fylz is the repo that diverges. It consumes 2.1.0-built metadata
  from the shared modules fine today (forward-compatible), but this is the gap to close.
- **The details room describes one subject at a time.** With several entries selected it drops to
  a count, a total and a breakdown by kind; there is no per-entry list.
- **The location tree is windowed at eight children.** A folder with more says how many it left
  out. It is a map of where you are, not a second file listing.
- **The scrubber appears only when there is a listing to map** — not on the storage home surface,
  and not while a selection is live.
- **`MANAGE_EXTERNAL_STORAGE` is a sensitive permission.** The app is fully usable without it,
  running entirely on SAF; granting it removes picker round-trips for local storage only.
