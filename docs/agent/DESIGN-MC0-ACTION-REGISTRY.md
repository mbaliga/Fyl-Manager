# MC.0 design: the action registry

Design for `docs/agent/MASTER_PLAN_ADDENDUM_1.md` §C1 and §D's MC.0 row, written against the
command inventory taken at `76865be` (every file under `ui/`, `ui/components/`, `workspace/`,
`model/Models.kt`, the activities, the cell-shell gesture sources). The addendum pre-decides the
shape (`ActionDef`, placements, "no hard-coded menus", "registry is the only source of shortcuts");
this document decides the refactor mechanics, the identifier set, what "identical before and after"
is measured against, and what MC.0 deliberately leaves alone. It is the review object for the
short Opus pass the addendum's model note asks for, and the implementation brief after that.

## 1. What the inventory found (the facts the design rests on)

- Exactly one file builds every menu: `ui/FylzV1App.kt`, 2529 lines. It holds the top-bar
  overflow `DropdownMenu` (5 items — the only `DropdownMenuItem` uses in `app/src/main`), the
  `SortMenu`, the `SelectionActionBar` (a private composable with 13 `on*` lambdas and inline
  `can*` booleans), `ToolsRoom` (5 hard-coded rows plus 3 theme rows), `LibraryRail` (3 buttons,
  rendered only at `maxWidth >= 900.dp`), `LocationsRoom`, and every handler.
- There is **no working keyboard path**: `workspace/KeyboardShortcutPolicy.kt` and
  `workspace/DesktopWorkspacePolicy.kt` are complete, unit-tested (the first only) and reachable
  from nothing — no `onKeyEvent`/`onPreviewKeyEvent` exists in `ui/`. Their tables conflict
  (Ctrl+F → `FIND` vs `FOCUS_SEARCH`; `MOVE_TO_OTHER_PANE` → Ctrl+Shift+X vs Ctrl+Shift+M;
  `REFRESH` → F5 vs Ctrl+R; Ctrl+P vs Ctrl+Shift+P; Ctrl+H vs Ctrl+Shift+H) and use incompatible
  modifier models (ctrl/meta folded vs kept separate).
- Gestures that work come from `dev.aarso.cellshell` with single fixed callbacks: edge drags open
  rooms (left = Locations, right = Tools, bottom = Recovery; top unclaimed), shake → `refresh()`.
  In-app: tap/long-press/double-tap on a row or card.
- State a condition needs is split: `BrowserViewModel` owns `tabs`, `activeTabId`, `selectedUris`,
  `sortSpec`, `viewMode`, `previewMode`, `query`, `searchRecursive`, `clipboard`; the current
  folder's `entries`/`visibleEntries` and `focusedEntry` are `remember {}` state inside
  `FylzV1Workspace`. No `location.kind`, `volume.fs`, `screen` or `input` value exists anywhere.
- There is no Compose UI test harness. Existing tests of this area are pure-Kotlin tests of helpers
  extracted from `FylzV1App.kt` (`FylzV1AppLogicTest`) and a 4-case `KeyboardShortcutPolicyTest`.
- Duplicates the registry must not migrate three times: "Open with" is implemented in
  `openExternal()`, `SpecializedDocumentPreview.ExternalOpenButton` and the row double-tap; "Share"
  in the selection bar and `ExternalDocumentDialog`; "Create ZIP" as a plain `archiveCreator`
  (selection bar) and a password-capable `ArchiveToolsOverlay` path. `OperationHistoryActivity` is
  declared in the manifest and launched by nothing.

## 2. Decisions

### 2.1 Scope of MC.0 versus MC.1/MC.2

- MC.0 is the **Kotlin** `ActionRegistry` plus the refactor of every existing surface onto it. The
  `core/crates/fylz-actions` crate is created in MC.0 **with type definitions only** (`ActionDef`
  and its enums, mirroring the Kotlin types field for field, plus the action-id grammar and one
  test for it) so the two sides are defined together as §C1 asks; TOML loading, validation and
  migrations stay MC.1, the condition language stays MC.2.
- Availability in MC.0 is a Kotlin predicate over a `BrowserState` snapshot
  (`when: (BrowserState) -> Boolean`). Each `Action` also carries `whenExpr: String?`, unused in
  MC.0, reserved so MC.2 can attach the typed-syntax condition without changing the type's shape.
  Every built-in's predicate is written so that MC.2 can replace it with an expression over the
  same fields (`selection.count`, `selection.kinds`, `location.kind`, `screen`, …).
- Behaviour is preserved exactly. Every gap the inventory found (Favourite has no phone placement;
  `ViewMode.DETAILS` unreachable; no "toggle hidden", "jump to path", undo/redo) stays a gap in
  MC.0 and is listed in §6 as a follow-up, because §D's acceptance is "identical actions per
  selection before and after". The two exceptions, both deliberate and both recorded as deviations:
  the dead shortcut tables are replaced by one live one (there was no live one to preserve), and
  three duplicate implementations of the same intent collapse to one handler each (same intent
  fired, same observable behaviour).

### 2.2 Types (Kotlin, `app/src/main/java/io/github/mbaliga/fylz/actions/`)

```kotlin
@JvmInline value class ActionId(val value: String)   // "fylz.copy", "user.resize-for-web"
// grammar: <namespace>.<name>; namespace ∈ {fylz, user, <bundle-id>}; name = [a-z0-9-]+ segments

data class Action(
    val id: ActionId,
    val title: (BrowserState) -> String,   // most are constant; "Cut (3)"-style labels stay dynamic
    val icon: IconRef,                     // IconRef.Builtin(ImageVector) now; IconRef.Bundle later
    val whenAvailable: (BrowserState) -> Boolean,
    val whenExpr: String? = null,          // MC.2
    val placements: List<Placement>,
    val confirm: ConfirmPolicy,            // None | Always | IfCountAbove(n) | IfDestructive
    val body: ActionBody,                  // BuiltIn(handlerId) | Steps(...) MC.5 | Script(...) MC.7
    val origin: Origin,                    // BuiltIn | Bundle(id, version) | Local
    val destructive: Boolean = false,
)

sealed interface Placement {
    data class SelectionBar(val order: Int) : Placement
    data class Toolbar(val order: Int, val overflow: Boolean = false) : Placement // overflow ⇒ the ⋮ menu
    data class ContextMenu(val group: String, val order: Int) : Placement        // MC.0: registered, no renderer yet (see §2.6)
    data class Room(val room: RoomId, val order: Int) : Placement                 // LEFT_RAIL, RIGHT_TOOLS, BOTTOM_RECOVERY
    data object CommandPalette : Placement
    data class Shortcut(val chord: KeyChord) : Placement
    data class Gesture(val gesture: GestureId) : Placement
    data object QuickSettingsTile : Placement                                     // registered type only; M9.8
    data object HomeCard : Placement                                              // registered type only; M9.1
}

data class KeyChord(val key: Key, val ctrl: Boolean = false, val shift: Boolean = false,
                    val alt: Boolean = false, val meta: Boolean = false)
// One model. Ctrl and Meta stay distinct; the registry treats a Meta chord as its Ctrl twin only on
// a platform predicate (MC.2) — in MC.0 nothing registers a Meta chord.

enum class GestureId { EDGE_LEFT, EDGE_RIGHT, EDGE_BOTTOM, EDGE_TOP, SHAKE, ITEM_TAP, ITEM_LONG_PRESS, ITEM_DOUBLE_TAP }
```

`Placement.Room` is not in §C1's list. It is added because §C1's own text says "refactor the
existing selection bar, overflow menu **and room items** to registry lookups"; without it room rows
could not be registry lookups. Recorded as a deviation in `PROGRESS.md`.

### 2.3 `BrowserState` — the one snapshot conditions read

A pure Kotlin `data class` in `actions/BrowserState.kt`, assembled once per composition by
`FylzV1Workspace` from `BrowserViewModel` and its local state, and passed to every renderer:

```kotlin
data class BrowserState(
    val hasActiveTab: Boolean,
    val canNavigateUp: Boolean,          // activeTab.locations.size > 1
    val entries: List<FileEntry>,         // current folder listing (raw)
    val selection: List<FileEntry>,       // orderedBySelection(entries, selectedUris)
    val focused: FileEntry?,
    val clipboard: FylzClipboard?,
    val sortSpec: SortSpec, val viewMode: ViewMode, val previewMode: PreviewMode,
    val query: String, val searchRecursive: Boolean,
    val screen: ScreenClass,              // NARROW | WIDE  (today's `maxWidth >= 900.dp`)
    val legacyBinCount: Int,
) {
    val selectionCount get() = selection.size
    val selectionKinds: Set<EntryKind> get() = selection.mapTo(HashSet()) { it.kind }
}
```

Nothing here is new information — every field is read today by some inline `can*`/`enabled =`
expression in `FylzV1App.kt`. `location.kind`, `volume.fs` and `input` are **not** added in MC.0:
no current command conditions on them, and inventing them now would be speculative state (they
arrive with MC.2's predicate table, fed by `StorageRootKind`/`VolumeInfoResolver`).

### 2.4 Registry, resolver, dispatcher

- `ActionRegistry` (`actions/ActionRegistry.kt`): an immutable list of `Action`s built once at
  startup from `BuiltInActions.all()`; `byId`, `forPlacement(placementType, state)` (filtered by
  `whenAvailable`, sorted by `order`), `shortcuts(): Map<KeyChord, ActionId>`, `problems: List<RegistryProblem>`.
  `RegistryProblem.ShortcutConflict(chord, ids)` and `DuplicateId(id)` are computed at construction.
  A conflict is never silently resolved by "last wins": the chord binds to **none** of the
  conflicting actions and the problem is surfaced (a "Customisation problems (N)" row in the Tools
  room, MC.0's minimal stand-in for Settings › Customisation › Problems).
- `ActionResolver.visible(placementType, state): List<ActionId>` — the pure function the golden
  tests assert on. Renderers call exactly this and nothing else to decide what to draw.
- `ActionDispatcher.run(id, state, ctx)` applies `confirm` (MC.0: only the two existing typed-phrase
  dialogs are `Always`; everything else `None`, matching today) then calls the handler.
- `ActionHandlers` (`actions/ActionHandlers.kt`): the handler-ID table §C9 describes. It receives an
  `ActionContext` (the lambdas `FylzV1Workspace` owns today: open destination chooser, launch
  archive creator, set a dialog flag, navigate, …). Handler bodies **move**, unchanged, out of
  `FylzV1App.kt`; they are not rewritten.

### 2.5 The built-in set (every command from the inventory, one id each)

| Id | Today's surface(s) → MC.0 placements | Condition (today's, verbatim in intent) |
|---|---|---|
| `fylz.open` | row tap → `Gesture(ITEM_TAP)`, `Shortcut(Enter)` | any row |
| `fylz.open-with` | double-tap, preview-pane button → `Gesture(ITEM_DOUBLE_TAP)`, `ContextMenu("open", 10)` | focused/tapped item is a file (one handler, three callers) |
| `fylz.select.toggle` | long-press, checkbox → `Gesture(ITEM_LONG_PRESS)` | any row |
| `fylz.select.all` | toolbar icon → `Toolbar(10)`, `Shortcut(Ctrl+A)` | `entries.isNotEmpty()` |
| `fylz.select.clear` | "Clear" in selection bar → `SelectionBar(0)`, `Shortcut(Escape)` | selection non-empty |
| `fylz.cut` | `SelectionBar(10)`, `Shortcut(Ctrl+X)` | selection non-empty |
| `fylz.copy` | `SelectionBar(20)`, `Shortcut(Ctrl+C)` | selection non-empty |
| `fylz.copy-to` | `SelectionBar(30)` | selection non-empty |
| `fylz.move-to` | `SelectionBar(40)` | selection non-empty |
| `fylz.paste` | clipboard chip → `Toolbar(20)`, `Shortcut(Ctrl+V)` | `clipboard != null && hasActiveTab` |
| `fylz.clipboard.clear` | chip ✕ → `Toolbar(21)` | `clipboard != null` |
| `fylz.recycle` | `SelectionBar(50)`, `Shortcut(Delete)` | selection non-empty; `destructive = true`, `confirm = None` (today has none — see §6) |
| `fylz.rename` | `SelectionBar(60)`, `Shortcut(F2)` | `selectionCount == 1` |
| `fylz.tags` | `SelectionBar(70)` | selection non-empty |
| `fylz.compress` | `SelectionBar(80)` | selection non-empty (plain zip, no password — unchanged) |
| `fylz.extract` | `SelectionBar(90)` | 1 selected, `EntryKind.ARCHIVE`, `isZipFamilyArchive(name)` |
| `fylz.rename.batch` | `SelectionBar(100)` | selection non-empty |
| `fylz.pdf.tools` | `SelectionBar(110)` | selection non-empty and all `EntryKind.PDF` |
| `fylz.share` | `SelectionBar(120)` | selection non-empty (one handler; `ExternalDocumentDialog` calls the same one) |
| `fylz.new-folder` | `Toolbar(overflow, 10)`, `Shortcut(Ctrl+Shift+N)` | `hasActiveTab` |
| `fylz.new-file` | `Toolbar(overflow, 20)`, `Shortcut(Ctrl+N)` | `hasActiveTab` |
| `fylz.scan-to-pdf` | `Toolbar(overflow, 30)` | `hasActiveTab` |
| `fylz.find-duplicates` | `Toolbar(overflow, 40)` | `entries.count { !it.isDirectory } > 1` |
| `fylz.ai.organize` | `Toolbar(overflow, 50)` | `focused != null` |
| `fylz.commands` | `Toolbar(overflow, 90)`, `Shortcut(Ctrl+K)` | always — the command palette itself |
| `fylz.sort.by.<name|size|modified|type>` | `SortMenu` rows → `Toolbar(30)` group "sort" | always |
| `fylz.sort.folders-first` | `Toolbar(31)` | always |
| `fylz.view.toggle` | `Toolbar(40)` | always |
| `fylz.navigate.up` | `Toolbar(0)`, `Shortcut(Alt+Backspace)`, `Shortcut(Alt+Left)`* | `canNavigateUp` |
| `fylz.refresh` | `Toolbar(50)`, `Gesture(SHAKE)`, `Shortcut(F5)`, `Shortcut(Ctrl+R)` | always |
| `fylz.search.focus` | `Shortcut(Ctrl+F)` | always (focuses the existing field; no new UI) |
| `fylz.preview.toggle` | `Shortcut(Ctrl+Shift+P)` | `focused != null` |
| `fylz.room.locations` / `.tools` / `.recovery` | `Gesture(EDGE_LEFT/RIGHT/BOTTOM)` | always |
| `fylz.tab.add`, `fylz.tab.close` | Locations room rows → `Room(LEFT_RAIL, …)`, `Shortcut(Ctrl+T)`, `Shortcut(Ctrl+W)` | always / focused row |
| `fylz.favourite.toggle` | `LibraryRail` → `Room(LEFT_RAIL, 20)` | `hasActiveTab && screen == WIDE` (gap preserved, §6) |
| `fylz.open-root` | `LibraryRail` → `Room(LEFT_RAIL, 10)` | `screen == WIDE` |
| `fylz.recycle-bin` | `ToolsRoom` + rail → `Room(RIGHT_TOOLS, 10)`, `Room(LEFT_RAIL, 30)` | always / wide |
| `fylz.remotes` | `Room(RIGHT_TOOLS, 20)` | always |
| `fylz.webdav.quick` | `Room(RIGHT_TOOLS, 30)` | always |
| `fylz.tools` | `Room(RIGHT_TOOLS, 40)` | always |
| `fylz.index` | `Room(RIGHT_TOOLS, 50)` | always |
| `fylz.theme.<system|light|dark>` | `Room(RIGHT_TOOLS, 60–62)` | always |
| `fylz.history.operations` | `Shortcut(Ctrl+Shift+H)` → opens `OperationHistoryDialog` | always |

\* Alt+Left/Right "back/forward" have no history model today; Alt+Left maps to navigate-up (the only
back-like behaviour that exists), Alt+Right registers nothing. Dialog-internal buttons (preflight,
conflict, destination chooser, backup/file-history/remote dialogs, recycle-bin restore/empty/tidy,
PDF-tool sub-buttons) are **not** registry actions in MC.0: they are per-item choices inside one
operation, not commands over the browser state. The permanent-delete pair keeps its typed-phrase
dialog untouched.

Shortcut resolution of the two dead tables, in one place (`BuiltInActions`): Ctrl+F → search focus;
Refresh → both F5 and Ctrl+R; `MOVE_TO_OTHER_PANE`/`COPY_TO_OTHER_PANE`/`TOGGLE_DUAL_PANE`/
`FOCUS_*PANE` → not registered (no dual pane exists until M12.1; M12.1 registers them, and the
conflict detector is what stops it choosing a taken chord); Ctrl+H (`TOGGLE_HIDDEN`) → not
registered (no such feature); Ctrl+Shift+H → operation history; Ctrl+P → not registered; Ctrl+Shift+P
→ preview toggle; Shift+Delete (`PERMANENT_DELETE`) → not registered (permanent delete exists only
inside the Recycle Bin dialog against a bin record, never on a live selection — binding it would be
new behaviour). `KeyboardShortcutPolicy.kt`, `DesktopWorkspacePolicy.kt` and
`KeyboardShortcutPolicyTest.kt` are deleted in the last MC.0 commit; `DualPaneModels.kt` stays
(M12.1 says "the `workspace/` models exist").

### 2.6 Renderers (`ui/actions/`)

One composable per surface, each taking `(registry, state, dispatcher)` and calling only
`ActionResolver.visible`:

- `SelectionActionBarRenderer` replaces the private `SelectionActionBar` and its 13-lambda signature.
- `ToolbarRenderer` draws `Toolbar` placements in order; those with `overflow = true` go into the
  one `DropdownMenu`. The sort group renders as today's `SortMenu` sub-menu (a nested menu is a
  renderer detail, not a placement kind). **All `DropdownMenuItem` calls in the app live in this
  file after MC.0** — that is what the grep test asserts.
- `RoomActionsRenderer` draws `Room(room, …)` rows for the right Tools room and the left rail's
  three buttons; the Locations room's tab list (a list of places, not commands) keeps its own
  `WordWheelRail`, with its "add" and "close" affordances dispatching `fylz.tab.add`/`fylz.tab.close`.
- `CommandPaletteDialog`: a text field filtering `visible(CommandPalette ∪ every placement, state)`
  by title; Enter dispatches. Minimal, as §D says; every action is palette-visible unless it opts out.
- `ContextMenu` placements are registered (so the ids and groups exist for MC.3's editors) but MC.0
  draws no per-item popup — there is none today and adding one is new UI, not a refactor.
- Shortcuts: a `Modifier.onPreviewKeyEvent` on the workspace root converts the event to a
  `KeyChord`, looks it up in `registry.shortcuts()`, checks `whenAvailable(state)`, dispatches.
  Text fields keep focus priority (an event consumed by a focused `TextField` never reaches the
  registry), so Ctrl+A in the search box still selects text.
- Gestures: the cell-shell callbacks stay exactly where they are wired; each now dispatches the
  action registered for its `GestureId` instead of calling a handler directly.

### 2.7 Golden tests (the acceptance bar, made concrete)

`app/src/test/java/io/github/mbaliga/fylz/actions/ActionResolverGoldenTest.kt`, pure JVM:

1. **Commit 1 writes the oracle before anything moves.** `LegacyVisibility` encodes today's
   `FylzV1App.kt` logic as a table: for each of ~20 `BrowserState` fixtures (no tab; empty folder;
   one file; one directory; two files; one zip; one 7z; one PDF; three PDFs; PDF + image;
   clipboard set/unset; `focused` set/unset; narrow vs wide; `legacyBinCount` 0/1) the exact
   ordered list of visible command ids per surface, transcribed from the inventory's conditions.
   The test asserts `ActionResolver.visible(surface, state) == LegacyVisibility[surface][state]` for
   every cell. It fails until the registry exists, which is the point: the expected side is written
   from the old code, not from the new one.
2. `ShortcutTableTest`: the full chord → id map equals a literal table; no `RegistryProblem` for the
   built-ins; a synthetic duplicate produces exactly one `ShortcutConflict` and binds nothing.
3. `ActionIdGrammarTest` (Kotlin) and the same in `fylz-actions` (Rust): accepted and rejected ids.
4. `NoHardCodedMenusTest`: walks `app/src/main/java` and fails if `DropdownMenuItem(` appears in
   any file other than `ui/actions/ToolbarRenderer.kt` — §D's grep test as a unit test, the same
   pattern the project already uses for source scans.
5. `FylzV1AppSizeTest`: `ui/FylzV1App.kt` has fewer lines than at `76865be` (2529), asserting
   §2.3's "must shrink" rule rather than trusting it.
6. Existing tests keep passing unchanged: `FylzV1AppLogicTest` (its helpers move to
   `actions/`, imports only), every `operations/*PolicyTest`, `SessionCodecTest`/`SessionStoreTest`.

### 2.8 Commit plan (one task, four commits, each green on `./gradlew --no-daemon :app:testDebugUnitTest`)

1. `MC.0a: BrowserState, Action types, registry, resolver + legacy golden oracle` — types,
   registry, dispatcher, handlers table (handlers still called from the old menus), `fylz-actions`
   crate with type definitions and the id test, all golden tests (oracle passing against a
   registry that mirrors the old logic; renderers not yet used). Nothing user-visible changes.
2. `MC.0b: selection bar, toolbar/overflow, sort menu and rooms render from the registry` — the
   renderers replace the hard-coded composables; the old ones are deleted in the same commit.
   `NoHardCodedMenusTest` passes from here.
3. `MC.0c: shortcuts, gestures and a minimal command palette read from the registry` — the
   `onPreviewKeyEvent` plumbing, gesture dispatch, palette, the Problems row in the Tools room.
4. `MC.0d: delete the dead shortcut policies and the orphaned OperationHistoryActivity` — grep-
   verified deletions (P1.13's own procedure), `FylzV1AppSizeTest`, `PROGRESS.md` row,
   `DEVICE_CHECKS.md` entry (hardware keyboard: every registered chord fires once, text fields keep
   priority, a conflicting chord fires nothing), `REVIEW_QUEUE.md` note.

## 3. Rust side in MC.0 (`core/crates/fylz-actions`)

`src/lib.rs` defines `ActionId` (with `parse()` enforcing the grammar), `ActionDef`, `Placement`,
`ConfirmPolicy`, `ActionBody`, `Origin`, `KeyChord`, `GestureId` with `serde` derives (MC.1 needs
them for TOML) and nothing else. One unit test for the grammar, `cargo clippy -D warnings` clean,
added to `deny.toml`'s workspace like every other crate. `serde` (MIT/Apache-2.0) is the only new
dependency; verify its licence at the pinned version and record it in `THIRD_PARTY_NOTICES.md`.
No FFI yet: the Kotlin registry does not call Rust in MC.0.

## 4. What MC.0 changes for the user

Nothing they can see, by design, except: hardware-keyboard shortcuts work for the first time (they
never did — both tables were dead), a Commands entry appears in the overflow menu (and Ctrl+K), and
a "Customisation problems" row appears in the Tools room only if the registry detects a conflict
(never, for the shipped built-ins).

## 5. Risks and how the design answers them

| Inventory risk | Answer |
|---|---|
| State split between ViewModel and Compose-local `remember{}` | `BrowserState` is assembled at the one place both are in scope (`FylzV1Workspace`); the snapshot is a plain value, so the resolver and its tests never touch Compose. |
| No Compose UI test harness | Golden tests assert on `ActionResolver.visible`, the only decision point renderers use. Rendering itself is trusted to Compose, as it is today. |
| Favourite has no phone placement | Preserved (a `screen == WIDE` condition), listed as a follow-up for MC.3, where a phone placement is a Tier 1 editor decision. |
| Three "Open with", two "Share", two "Create ZIP" | One handler per intent for the first two (same intent object fired). Compress and Protect stay separate actions with today's placements; M5.1 decides the Protect sheet. |
| Async `busy` flags gating dialog buttons | Out of scope: dialog-internal buttons are not registry actions in MC.0. |
| Capability-driven root list in `StorageHomeScreen` | Not a command list; untouched. MC.2 decides whether "provider available" is a predicate. |
| Orphaned `OperationHistoryActivity`; duplicated Index/Tools activities | The orphan is deleted (grep-verified). The Index/PostV1Tools duplication is real but is a screen consolidation, not a menu refactor — follow-up, not MC.0. |
| `recycle` has no confirmation today | Preserved (`ConfirmPolicy.None`) because the recycle bin is the safety net; flagged in §6 for the owner's product call, not changed silently. |

## 6. Follow-ups this design deliberately leaves (log in `PROGRESS.md` when MC.0 lands)

1. Favourite (and Open root, Recycle Bin) have no narrow-screen placement — MC.3.
2. `fylz.recycle` runs without confirmation — product decision for Madhav (`REVIEW_QUEUE.md`).
3. `IndexManagerActivity` vs `PostV1ToolsActivity` duplication — consolidate in M9/MC.3.
4. `ViewMode.DETAILS` unreachable — M12.5.
5. No back/forward history, no jump-to-path — M12.3/M12.6.
6. Checksum reachable only inside `ConflictSheet` — M4.6 / M7.2.
7. Per-item context-menu popup — first renderer for `Placement.ContextMenu`, with MC.3.

## 7. Acceptance (restating §D in checkable form)

- `ActionResolverGoldenTest` green with the oracle written from the pre-refactor code.
- `NoHardCodedMenusTest` green; `grep -rn DropdownMenuItem app/src/main` hits only `ui/actions/ToolbarRenderer.kt`.
- `ShortcutTableTest` green; `registry.problems` empty for built-ins; conflict detection proven by test.
- `FylzV1AppSizeTest` green (file smaller than 2529 lines).
- `./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` green after each of the four commits; `cargo test/clippy/fmt/deny` green for `fylz-actions`.
- `KeyboardShortcutPolicy.kt`, `DesktopWorkspacePolicy.kt`, `OperationHistoryActivity.kt` (and its manifest entry) gone, each deletion grep-verified first.
