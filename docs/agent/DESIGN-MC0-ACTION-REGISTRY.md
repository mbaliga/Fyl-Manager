# MC.0 design: the action registry

Design for `docs/agent/MASTER_PLAN_ADDENDUM_1.md` §C1 and §D's MC.0 row. Revision 2, after the
Opus review the addendum's model note asks for; §8 lists what the review changed. The addendum
pre-decides the shape (`ActionDef`, placements, "no hard-coded menus", "registry is the only source
of shortcuts"); this document decides the refactor mechanics, the identifier set, how "identical
before and after" is measured, and what MC.0 deliberately leaves alone. It is the implementation
brief: Sonnet implements from it literally, so where it is silent the implementer stops and asks.

## 1. Facts the design rests on (verified in code at `6e3ab3f`)

- Menus are built in two files: `ui/FylzV1App.kt` (2529 lines: the top-app-bar overflow
  `DropdownMenu` — the only `DropdownMenuItem` uses in `app/src/main` —, the `SortMenu`, the
  `SelectionActionBar` with 13 `on*` lambdas, `ToolsRoom`, `LibraryRail`, `LocationsRoom`, the
  row/card click handlers, every handler body) and `ui/FylzAppShell.kt` (the bottom Recovery room:
  five `RecoveryActionCard`s — Operation history, File history, Backup plans, Import existing
  backups, Archive tools — plus `showHistory` state).
- **Disabled is not hidden.** Rename, Extract and PDF tools are always drawn in the selection bar
  and only disabled (`FylzV1App.kt:2126–2131`, `enabled = can*`). The overflow items (`:1076–1121`),
  the clipboard chip (`:1041`), navigate-up (`:1736`), select-all (`:1752`) and the rail's Favourite
  (`:1667`) are the same. Only the selection bar as a whole appears/disappears (with a selection),
  and the browser-row controls exist only with an open tab (`:1721`).
- **No live keyboard path.** `workspace/KeyboardShortcutPolicy.kt` and
  `workspace/DesktopWorkspacePolicy.kt` are complete, tested (the first only), and reachable from
  nothing — no `onKeyEvent`/`onPreviewKeyEvent`/`focusable()` exists anywhere under `ui/`. Their
  tables conflict (Ctrl+F `FIND` vs `FOCUS_SEARCH`; `MOVE_TO_OTHER_PANE` Ctrl+Shift+X vs
  Ctrl+Shift+M; `REFRESH` F5 vs Ctrl+R; Ctrl+P vs Ctrl+Shift+P; Ctrl+H vs Ctrl+Shift+H) and use
  different modifier models. `DesktopWorkspacePolicy.kt:5` defines `WorkspacePane`, which
  `workspace/DualPaneModels.kt:35–72` uses.
- Gestures: edge drags are handled *inside* `dev.aarso.cellshell.SpatialShell` (a read-only
  submodule) which only takes room content per edge; the app cannot intercept the drag. Shake
  (`ShakeToRefresh`) takes one callback. In-app: tap / long-press / double-tap on a row or card
  (`FylzV1App.kt:2025–2027`, `:2077–2079`); double-tap opens a folder but opens a *file* in another
  app.
- `OperationHistoryActivity` is reached from the launcher shortcut in `res/xml/shortcuts.xml:18`;
  it is not orphaned.
- Selection today is `entries.filter { it.uri in selectedUris }` (`:452`): listing order, linear.
  `orderedBySelection` (`:238`, quadratic) exists for the PDF-merge dialog's ordering promise only.
  Select-all acts on `visibleEntries` (`:1251`).
- State a condition needs is split: `BrowserViewModel` owns `tabs`, `activeTabId`, `selectedUris`,
  `sortSpec`, `viewMode`, `previewMode`, `query`, `searchRecursive`, `clipboard`; `entries`,
  `visibleEntries`, `focusedEntry`, `legacyBinNames`, dialog flags are `remember {}` state in
  `FylzV1Workspace`; `showHistory` and the theme mode live in `FylzAppShell`.
- There is no Compose UI test harness. The area's tests are pure-Kotlin tests of helpers extracted
  from `FylzV1App.kt` (`FylzV1AppLogicTest`) and a 4-case `KeyboardShortcutPolicyTest`.
- Duplicated intents: "Open with" in `openExternal()` (`:909`), `SpecializedDocumentPreview.
  ExternalOpenButton` (`:269`) and the row double-tap; "Share" in the selection bar (`:1176`) and
  `ExternalDocumentDialog` (`:115`). The copies differ in chooser titles and failure toasts.

## 2. Decisions

### 2.1 Scope of MC.0 versus MC.1/MC.2

- MC.0 is the **Kotlin** `ActionRegistry` plus the refactor of every existing surface onto it.
  `core/crates/fylz-actions` is created in MC.0 with **plain type definitions** (`ActionDef`,
  `Placement`, `ConfirmPolicy`, `ActionBody`, `Origin`, `KeyChord`, `GestureId`, the `ActionId`
  grammar with one test) — no serde, no FFI, no logic. Serde derives that match §C4's TOML shapes
  (`{context_menu={group}}`, bare `"command_palette"`, `"if_count_above:50"`) are MC.1's, together
  with the round-trip test of §C4's example; adding default derives now would bake in shapes that
  don't match.
- Availability in MC.0 is two Kotlin predicates over a `BrowserState` snapshot:
  `visibleWhen` and `enabledWhen` (§1: disabled is not hidden). Each `ActionDef` also carries
  `whenExpr: String?`, unused in MC.0, reserved for MC.2's typed-syntax condition. Rule for MC.2,
  stated now: when `whenExpr` is present it is authoritative and the Kotlin predicate is deleted for
  that action; MC.2 adds a test that every built-in's expression and predicate agree on the golden
  fixtures before the predicate goes.
- Behaviour is preserved exactly. Every gap found (Favourite has no phone placement;
  `ViewMode.DETAILS` unreachable; no toggle-hidden, jump-to-path, undo/redo, back/forward) stays a
  gap in MC.0 and is listed in §6. The deviations MC.0 does make, each recorded in `PROGRESS.md`:
  (a) one live shortcut table replaces two dead ones — there was no live behaviour to preserve;
  (b) a Commands entry appears in the overflow menu and on Ctrl+K; (c) a "Customisation problems"
  row appears in the Tools room only when the registry detects a conflict (never for the shipped
  built-ins).

### 2.2 Types (Kotlin, `app/src/main/java/io/github/mbaliga/fylz/actions/`)

The data that MC.1's TOML will carry is separated from what only a built-in has (predicates,
dynamic labels, a Kotlin handler), so the Rust `ActionDef` can mirror the data half field for field.

```kotlin
@JvmInline value class ActionId(val value: String)
// grammar: <namespace>.<segment>(.<segment>)*; namespace ∈ {fylz, user, <bundle-id>};
// segment = [a-z0-9]+(-[a-z0-9]+)*. Parsed by ActionId.parse(); the Rust crate has the same grammar and test.

data class ActionDef(                         // data only — mirrored in fylz-actions
    val id: ActionId,
    val titleKey: String,                     // string-resource name; built-ins may override the label dynamically
    val icon: IconRef,                        // IconRef.Builtin(name: String) — resolved to an ImageVector by the renderer; IconRef.Bundle later
    val whenExpr: String? = null,             // MC.2
    val placements: List<Placement>,
    val confirm: ConfirmPolicy,               // None | Always | IfCountAbove(n) | IfDestructive
    val body: ActionBody,                     // BuiltIn(handlerId) | Steps (MC.5) | Script (MC.7)
    val origin: Origin,                       // BuiltIn | Bundle(id, version) | Local
    val destructive: Boolean = false,
    val requiresTarget: TargetKind? = null,   // null = acts on BrowserState; ENTRY / FILE / TAB = needs an ActionTarget
)

class BuiltInBinding(                          // Kotlin-only half
    val def: ActionDef,
    val visibleWhen: (BrowserState) -> Boolean,
    val enabledWhen: (BrowserState) -> Boolean,
    val label: (BrowserState) -> String,       // default: the resource for titleKey; "Clear", "Paste" etc. stay constant
    val checked: (BrowserState) -> Boolean?,   // for the sort/theme/folders-first radio and check rows; null = not checkable
    val run: (ActionContext, BrowserState, ActionTarget?) -> Unit,
)

sealed interface Placement {
    data class SelectionBar(val order: Int) : Placement
    data class Toolbar(val bar: Bar, val order: Int) : Placement          // Bar.TOP_APP_BAR | Bar.BROWSER_ROW — two different rows today
    data class Menu(val menu: MenuId, val order: Int) : Placement         // OVERFLOW, SORT, ARCHIVE_TOOLS — every DropdownMenu/AlertDialog-menu is a Menu
    data class Room(val room: RoomId, val order: Int) : Placement         // LOCATIONS, LIBRARY_RAIL, TOOLS, RECOVERY
    data class ContextMenu(val group: String, val order: Int) : Placement // registered, no renderer in MC.0 (§2.6)
    data object CommandPalette : Placement
    data class Shortcut(val chord: KeyChord) : Placement
    data class Gesture(val gesture: GestureId, val targetWhen: ((ActionTarget) -> Boolean)? = null) : Placement
    data object QuickSettingsTile : Placement                             // type only; M9.8
    data object HomeCard : Placement                                      // type only; M9.1
}

data class KeyChord(val key: Key, val ctrl: Boolean = false, val shift: Boolean = false,
                    val alt: Boolean = false, val meta: Boolean = false)  // one model; ctrl and meta distinct; nothing binds meta in MC.0

enum class GestureId { EDGE_LEFT, EDGE_RIGHT, EDGE_BOTTOM, SHAKE, ITEM_TAP, ITEM_LONG_PRESS, ITEM_DOUBLE_TAP }

sealed interface ActionTarget { data class Entry(val entry: FileEntry) : ActionTarget; data class Tab(val id: String) : ActionTarget }
```

Extensions of §C1's placement list, each recorded as a deviation: `Room` (§C1's own text names room
items as registry lookups), `Menu` (the overflow, sort and archive-tools menus are three distinct
menus today, not one "toolbar"), `Toolbar.bar` (two bars exist), `Gesture.targetWhen` (double-tap
does two different things depending on the item), `requiresTarget`/`ActionTarget` (tap, long-press,
double-tap, close-tab, and the external viewer's Share/Open-with act on a specific row or tab that is
not part of the browser state).

### 2.3 `BrowserState` — the one snapshot conditions read

`@Immutable data class BrowserState` in `actions/BrowserState.kt`, computed inside `remember(...)`
keyed on its inputs in `FylzV1Workspace` (not rebuilt on every recomposition), and passed to every
renderer. Its fields are exactly what today's inline expressions read — nothing speculative:

```kotlin
data class BrowserState(
    val hasActiveTab: Boolean,
    val canNavigateUp: Boolean,          // activeTab.locations.size > 1
    val entries: List<FileEntry>,        // raw listing
    val visibleEntries: List<FileEntry>, // after query filter + sort — select-all's domain
    val selection: List<FileEntry>,      // LISTING order: entries.filter { it.uri in selectedUris } (today's definition, linear)
    val selectionOrder: List<Uri>,       // insertion order, for the one handler that needs it (PDF merge → orderedBySelection)
    val focused: FileEntry?,
    val clipboard: FylzClipboard?,
    val sortSpec: SortSpec, val viewMode: ViewMode, val previewMode: PreviewMode,
    val query: String, val searchRecursive: Boolean,
    val themeMode: ThemeMode,
    val currentFolderIsFavourite: Boolean,
    val legacyBinCount: Int,
    val operationsNeedingAttention: Int, // FylzAppShell's attentionCount, for the Operation history card's label
    val registryProblemCount: Int,       // registry.problems.size, supplied when the state is assembled (the registry exists first);
                                         // a (BrowserState) -> Boolean predicate cannot see the registry it belongs to. Added in MC.0e;
                                         // MC.0a's fylz.customisation.problems is unconditionally hidden until then.
) {
    val selectionCount get() = selection.size
    val selectionKinds: Set<EntryKind> get() = selection.mapTo(HashSet()) { it.kind }
}
```

`location.kind`, `volume.fs`, `input` and `screen` are **not** fields: no current condition reads
them (the rail's wide-only rule is a property of the rail surface, §2.6, not of any action). They
arrive with MC.2's predicate table.

### 2.4 Registry, resolver, dispatcher, handlers

- `ActionRegistry` (`actions/ActionRegistry.kt`): built once at startup from `BuiltInActions.all()`
  (a `List<BuiltInBinding>`); `byId`, `bindings(placementType)`, `shortcuts(): Map<KeyChord, ActionId>`,
  `edgeRooms(): Map<GestureId, ActionId>`, `problems: List<RegistryProblem>`. Problems computed at
  construction: `ShortcutConflict(chord, ids)` — the chord then binds to **none** of them —,
  `DuplicateId(id)`, `TargetRequiredOnTargetlessPlacement(id, placement)` (a `requiresTarget`
  action on `CommandPalette`, `Toolbar`, `Menu` or `SelectionBar` — placements with no item under
  them). `Shortcut` and `Room` may carry target-requiring actions because each has a documented
  target convention: Enter → `focused`, Ctrl+W → the active tab, `fylz.tab.close` in the Locations
  room → the row's own tab. (MC.0a clarification.)
- `ActionResolver.resolve(placement: PlacementQuery, state): List<ResolvedAction>` where
  `ResolvedAction(id, enabled, label, iconName, checked)`; `visible` items in placement `order`.
  This is the only function renderers call to decide what to draw, and what the golden tests
  assert on (id **and** enabled, not id alone).
- `ActionDispatcher.run(id, state, target, ctx)`: checks `visibleWhen && enabledWhen` (and that a
  required target is present), applies `confirm` (MC.0: every built-in is `None` — the two
  typed-phrase permanent-delete confirmations are dialog-internal buttons, not registry actions,
  so no built-in carries `Always` in MC.0), then calls `run`.
- `ActionHandlers`: the handler-ID table §C9 describes. Handler bodies **move**, unchanged, out of
  `FylzV1App.kt`/`FylzAppShell.kt` into `actions/handlers/*.kt`, receiving an `ActionContext` that
  exposes what they touch today (the dialog flags, launchers, `shell`, the ViewModel, `showHistory`,
  navigation), plus a `Caller` hint so the two Share and three Open-with call sites keep their own
  chooser titles and failure toasts (one handler, caller-specific messages — behaviour unchanged).

### 2.5 The built-in set

Orders are copied from today's code (left-to-right / top-to-bottom). `V`/`E` = `visibleWhen` /
`enabledWhen` ("always" = `{ true }`). Surface-level gates (the rail is wide-only, the browser row
needs a tab, the selection bar needs a selection) live in the renderer, §2.6, not here.

**Selection bar** (`FylzV1App.kt:2120–2133`), all `V: selectionCount > 0`, `E` as noted:
`fylz.cut`(10) · `fylz.copy`(20) · `fylz.copy-to`(30) · `fylz.move-to`(40) · `fylz.recycle`(50,
`destructive`, `confirm None` — today has none, §6) · `fylz.rename`(60, `E: selectionCount == 1`) ·
`fylz.tags`(70) · `fylz.compress`(80, plain zip, no password — unchanged) · `fylz.extract`(90,
`E: selectionCount == 1 && kind == ARCHIVE && isZipFamilyArchive(name)`) · `fylz.rename.batch`(100) ·
`fylz.pdf.tools`(110, `E: selectionKinds == {PDF}`) · `fylz.share`(120) · `fylz.select.clear`(130,
the trailing "Clear" text button — last, as today).
Shortcuts: cut Ctrl+X, copy Ctrl+C, recycle Delete, rename F2, select.clear Escape.

**Top app bar** (`:1036–1124`): `fylz.paste` (`Toolbar(TOP_APP_BAR, 10)`, the clipboard chip:
`V: clipboard != null`, `E: hasActiveTab`, label `clipboardChipLabel`; Ctrl+V) · `fylz.clipboard.clear`
(`Toolbar(TOP_APP_BAR, 11)`, the chip's ✕, `V: clipboard != null`) · `fylz.view.toggle`
(`Toolbar(TOP_APP_BAR, 20)`, always; label/icon by `viewMode`) · `fylz.refresh`
(`Toolbar(TOP_APP_BAR, 30)`, always; `Gesture(SHAKE)`, F5, Ctrl+R) · overflow menu items, all
`Menu(OVERFLOW, n)`: `fylz.new-folder`(10, `E: hasActiveTab`, Ctrl+Shift+N) · `fylz.new-file`(20,
`E: hasActiveTab`, Ctrl+N) · `fylz.scan-to-pdf`(30, `E: hasActiveTab`) · `fylz.find-duplicates`(40,
`E: entries.count { !it.isDirectory } > 1`) · `fylz.ai.organize`(50, `E: focused != null`) ·
`fylz.commands`(90, always, Ctrl+K — the palette; deviation (b)).

**Browser row** (`:1733–1776`, exists only with an open tab): `fylz.navigate.up`
(`Toolbar(BROWSER_ROW, 10)`, `E: canNavigateUp`; Alt+Up, Backspace) · the search field stays a
field, not an action; `fylz.search.focus` has only `Shortcut(Ctrl+F)` · `fylz.select.all`
(`Toolbar(BROWSER_ROW, 20)`, `E: visibleEntries.isNotEmpty()`, acts on `visibleEntries`; Ctrl+A) ·
sort menu items, `Menu(SORT, n)`: `fylz.sort.name`(10) · `fylz.sort.size`(20) ·
`fylz.sort.modified`(30) · `fylz.sort.type`(40) (each `checked = sortSpec.field == …`, re-click
flips direction as today) · `fylz.sort.folders-first`(50, `checked = sortSpec.foldersFirst`).
The search-scope chips (this folder / everything below) stay a two-state control, not actions.

**Rows and cards** (`:2025–2027`, `:2077–2079`), all `requiresTarget`: `fylz.open`
(`Gesture(ITEM_TAP)`; `Gesture(ITEM_DOUBLE_TAP, targetWhen = isDirectory)`; `Shortcut(Enter)` →
target = `focused`, nothing if null; `requiresTarget = ENTRY`) · `fylz.open-with`
(`Gesture(ITEM_DOUBLE_TAP, targetWhen = !isDirectory)`; `ContextMenu("open", 10)`;
`requiresTarget = FILE`; also called by the preview pane's `ExternalOpenButton` and
`ExternalDocumentDialog` with their own `Caller`) · `fylz.select.toggle` (`Gesture(ITEM_LONG_PRESS)`,
the checkboxes; `requiresTarget = ENTRY`).

**Locations room** (`:2376–2428`): the tab list stays `WordWheelRail` (places, not commands). Its
two affordances dispatch `fylz.tab.add` (`Room(LOCATIONS, 10)`, always; Ctrl+T) and `fylz.tab.close`
(`Room(LOCATIONS, 20)`, `requiresTarget = TAB` — the focused row's tab; Ctrl+W → the active tab).

**Library rail** (`:1652–1685`, drawn only when wide): `fylz.open-root` (`Room(LIBRARY_RAIL, 10)`,
always) · `fylz.favourite.toggle` (`Room(LIBRARY_RAIL, 20)`, `E: hasActiveTab`, label by
`currentFolderIsFavourite`) · `fylz.recycle-bin` (`Room(LIBRARY_RAIL, 30)`).

**Tools room** (`:2444–2493`): `fylz.recycle-bin` (`Room(TOOLS, 10)`, always) · `fylz.remotes`(20) ·
`fylz.webdav.quick`(30) · `fylz.tools`(40) · `fylz.index`(50) · `fylz.theme.system`(60) ·
`fylz.theme.light`(61) · `fylz.theme.dark`(62) (each `checked = themeMode == …`) ·
`fylz.customisation.problems` (`Room(TOOLS, 90)`, `V: registryProblemCount > 0`; deviation (c)).
An action may carry two `Room` placements (Recycle Bin: rail and Tools) — the per-surface gate is
the renderer's.

**Recovery room** (`FylzAppShell.kt:166–203`): `fylz.history.operations` (`Room(RECOVERY, 10)`;
Ctrl+Shift+H; label carries `operationsNeedingAttention`) · `fylz.history.files`(20) ·
`fylz.backup.plans`(30) · `fylz.backup.import`(40) · `fylz.archive.tools`(50). The Archive tools
overlay's own two-button menu becomes `Menu(ARCHIVE_TOOLS, n)`: `fylz.protect`(10 — "Create ZIP"
with optional AES-256 password, the overlay's existing flow; §C1 names "protect") ·
`fylz.archive.inspect`(20). The three room-open actions `fylz.room.locations` / `.tools` /
`.recovery` carry `Gesture(EDGE_LEFT/RIGHT/BOTTOM)`; see §2.6 for what that can and cannot mean.

**Explicit exclusions** (recorded, so nothing is silently dropped): dialog-internal buttons —
preflight and conflict choices, destination-chooser rows, recycle-bin Restore/Delete permanently/
Empty/Tidy (the two typed-phrase confirmations stay exactly as they are), PDF-tool sub-buttons,
backup/file-history/remote-connection dialog buttons, the archive-password dialog; the
`StorageHomeScreen` buttons (Grant access, Add folder, Network locations — a home surface, M9.1's
`HomeCard` placement is where these become registry items); the preview pane's Close/Dock/Edit/Save
header controls (pane chrome, M6.8); the search field and its scope chips; the launcher shortcuts in
`res/xml/shortcuts.xml` (Android-side entry points, not in-app commands); `IndexManagerActivity`/
`PostV1ToolsActivity` controls (separate activities).

**Shortcut table** (the whole of it; nothing else binds a key): Ctrl+X cut · Ctrl+C copy · Ctrl+V
paste · Ctrl+A select.all · Escape select.clear · Delete recycle · F2 rename · Enter open (focused
entry) · Ctrl+N new-file · Ctrl+Shift+N new-folder · Ctrl+T tab.add · Ctrl+W tab.close (active tab) ·
Ctrl+F search.focus · F5 and Ctrl+R refresh · Alt+Up and Backspace navigate.up · Ctrl+K commands ·
Ctrl+Shift+H history.operations. Resolution of the two dead tables: Ctrl+F → search focus (one
name); refresh keeps both chords; **Alt+Left/Right are left unbound** (M12.3 reserves them for
Back/Forward, which do not exist yet); **Alt+Backspace is not bound** (ChromeOS maps it to
forward-delete); `MOVE_TO_OTHER_PANE`/`COPY_TO_OTHER_PANE`/`TOGGLE_DUAL_PANE`/`FOCUS_*PANE` are not
registered (no dual pane until M12.1; the conflict detector is what stops M12.1 taking a used chord
— note that MASTER_PLAN §M12.1 names F5/F6 for pane copy/move while §M12.3 names F5 refresh; MC.0
follows §M12.3 and records the plan's own conflict for M12 to settle); Ctrl+H (`TOGGLE_HIDDEN`),
Ctrl+P, Ctrl+Shift+P (`TOGGLE_PREVIEW` — a new command, not existing behaviour), Shift+Delete
(`PERMANENT_DELETE` — exists only inside the Recycle Bin dialog against a bin record) are not
registered. `KeyboardShortcutPolicy.kt`, `DesktopWorkspacePolicy.kt` and
`KeyboardShortcutPolicyTest.kt` are deleted in MC.0d **after** `WorkspacePane` is moved into
`DualPaneModels.kt` (which stays: M12.1 says "the `workspace/` models exist").

### 2.6 Renderers (`ui/actions/`) and the surface gates they own

Each renderer takes `(registry, state, dispatcher)` and calls only `ActionResolver.resolve`. The
gates that are properties of a surface, not of an action, live here:

- `SelectionActionBarRenderer` — drawn only when `selectionCount > 0` (as today); replaces the
  13-lambda private composable; the "Clear" text button is the last item.
- `TopAppBarRenderer` — `Toolbar(TOP_APP_BAR)` icons in order, then the one `DropdownMenu` over
  `Menu(OVERFLOW)`. `BrowserRowRenderer` — drawn only with an open tab; `Toolbar(BROWSER_ROW)` icons
  around the existing search field, and the sort `DropdownMenu` over `Menu(SORT)`. **Every
  `DropdownMenuItem` in the app lives in these two files after MC.0.**
- `RoomActionsRenderer(room)` — `LIBRARY_RAIL` drawn only when `wide` (today's `maxWidth >= 900.dp`
  stays where it is, `:1023/:1200`); `TOOLS`, `RECOVERY` (as `RecoveryActionCard`s, keeping each
  card's description text), `LOCATIONS` (the two affordances around the unchanged `WordWheelRail`).
  `ArchiveToolsMenuRenderer` — the overlay's dialog over `Menu(ARCHIVE_TOOLS)`.
  **Recovery cards whose "action" is a composable** (MC.0a finding): four cards today put a
  self-contained overlay in their action slot — `FileHistoryOverlay()`, `BackupOverlay()`,
  `BackupImportOverlay()`, `ArchiveToolsOverlay()` — each owning its own FAB and dialog state, with
  no imperative "open" the registry could call. For these ids the registry supplies order, label,
  description and visibility, and the renderer maps id → overlay composable (a table inside
  `RoomActionsRenderer`, the Compose analogue of §C9's handler-ID table); their `run` stays a
  documented no-op and they are excluded from the palette (`requiresTarget` is not the right flag;
  use a `paletteVisible = false` on the binding). `fylz.history.operations` is the one card with an
  imperative hook (`ctx.showOperationHistory()`) and stays palette-visible.
- `CommandPaletteDialog` — a text field filtering every resolved, enabled, **targetless** action by
  label; Enter dispatches. Minimal, per §D.
- `ContextMenu` placements are registered (so MC.3's editors have ids and groups) but MC.0 draws no
  per-item popup — none exists today.
- **Keys.** A `KeyRouter` (pure: `route(chord, textFieldFocused, registry, state): ActionId?`) and
  thin Compose wiring: the workspace root gets `Modifier.focusable()` with a `FocusRequester` that
  takes focus on first composition and whenever a text field or dialog releases it (in touch mode
  nothing is focused otherwise and key events would not reach Compose at all), and
  `Modifier.onKeyEvent` — **not** `onPreviewKeyEvent`, which would run before the focused text field
  and turn Delete/Ctrl+V/Ctrl+A in the search box into recycle/paste/select-all. When a text field
  has focus the router returns null for every chord except Escape (clear focus) — the text field
  keeps its own editing keys. Dispatch requires `enabledWhen` and, for `requiresTarget` actions, the
  documented target (`focused` for Enter, the active tab for Ctrl+W). A device check covers real
  hardware keyboards (`DEVICE_CHECKS.md`, MC.0d).
- **Gestures.** `SHAKE`, `ITEM_TAP`, `ITEM_LONG_PRESS`, `ITEM_DOUBLE_TAP` dispatch through the
  registry (the row/card `combinedClickable` lambdas become `dispatcher.gesture(ITEM_*, target)`).
  Edge drags cannot: `SpatialShell` owns the drag and only asks which content sits on which edge.
  So `Gesture(EDGE_*)` is **declarative**: the shell wiring reads `registry.edgeRooms()` to decide
  which room action's content goes on which edge — the registry is the single place that says
  "left = Locations", but the drag itself stays in cell-shell. Recorded as a deviation from a
  literal reading of "gestures are views over the registry".

### 2.7 Golden tests — differential, not transcribed

The review's central objection to revision 1 was that an oracle transcribed from the inventory only
confirms itself. MC.0a therefore makes the **old code** the oracle:

1. **MC.0a lifts every existing availability expression, word for word, into pure functions** in
   `actions/legacy/LegacyAvailability.kt` (`canRename(selection)`, `canExtract(selection)`,
   `canPdfTools(selection)`, `overflowEnabled(item, state)`, `selectAllEnabled(...)`, …) and the
   **old composables call them** — so for one commit the shipped app runs through the same
   functions the tests use. `ActionResolverGoldenTest` then asserts, for every fixture and every
   surface, that `ActionResolver.resolve(surface, state)` equals what the legacy functions plus the
   code's literal item order say — `(id, enabled)` pairs, not ids alone. `LegacyAvailability.kt`
   is deleted in MC.0d once nothing else calls it; the golden table it produced is frozen as data
   in the test at that point.
2. Fixtures (`actions/BrowserStateFixtures.kt`): no tab (clipboard null / set); empty folder; one
   file; one directory; a file and a directory; two files (find-duplicates boundary at 1 vs 2);
   one `.zip`, one `.7z`, one `.apk`, one `.cbz`, a `.zip` whose kind is not ARCHIVE; one PDF; three
   PDFs; PDF + image; focused file with empty selection; selection containing a stale uri; a query
   that filters everything out; a recursive-search hit that is selected; folder depth 1 vs 2;
   favourite vs not; each theme mode; `legacyBinCount` 0/1; `operationsNeedingAttention` 0/1/2.
3. `ShortcutTableTest`: the chord → id map equals §2.5's table literally; `problems` is empty for
   the built-ins; a synthetic duplicate yields exactly one `ShortcutConflict` and binds nothing;
   `KeyRouterTest`: with a text field focused every chord but Escape routes to null.
4. `ActionIdGrammarTest` (Kotlin) and the same cases in `fylz-actions` (Rust).
5. `NoHardCodedMenusTest`: walks `app/src/main/java` and fails if `DropdownMenuItem(`,
   `ActionButton(`, `ToolsRow(` or `RecoveryActionCard(` appears outside `ui/actions/`.
6. `FylzV1AppSizeTest`: asserts `ui/FylzV1App.kt`'s line count is **≤ the count measured after
   MC.0d** (a ratchet written into the test at that point, not the one-off 2529), and that
   `FylzAppShell.kt` has not grown.
7. `FylzV1AppLogicTest` is **not** touched: its helpers stay where they are.

### 2.8 Commit plan (each green on `./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`; `cargo test/clippy/fmt/deny` for the crate)

1. `MC.0a: action types, BrowserState, registry/resolver/dispatcher, legacy oracle` — types,
   `BrowserState` (computed in `remember`), registry with the full built-in table and handlers
   table (handlers still invoked by the old menus), `LegacyAvailability` lifted and called by the
   old composables, `fylz-actions` crate (types + grammar test), all §2.7 tests. No renderer in use;
   nothing user-visible changes.
2. `MC.0b: selection bar renders from the registry` — `SelectionActionBarRenderer` replaces the
   private composable; old one deleted.
3. `MC.0c: top app bar, overflow, browser row and sort menu render from the registry` —
   `TopAppBarRenderer`, `BrowserRowRenderer`; `NoHardCodedMenusTest` passes for `DropdownMenuItem`.
4. `MC.0d: rooms render from the registry` — `RoomActionsRenderer` for the rail, Tools, Locations
   affordances and Recovery cards; `ArchiveToolsMenuRenderer`; `NoHardCodedMenusTest` passes fully.
5. `MC.0e: shortcuts, gestures and the command palette` — `KeyRouter` + focus wiring, gesture
   dispatch, `edgeRooms()` wiring, palette, the Problems row.
6. `MC.0f: retire the dead shortcut policies; ratchet the size test; log` — move `WorkspacePane`
   into `DualPaneModels.kt`; delete the two policies and their test (grep-verified); delete
   `LegacyAvailability.kt` and freeze the golden table; write the `FylzV1AppSizeTest` ratchet;
   `PROGRESS.md` row; `DEVICE_CHECKS.md` (hardware keyboard: each chord fires once, text fields keep
   priority, a conflicting chord fires nothing, shake still refreshes); `REVIEW_QUEUE.md` (recycle
   without confirmation — §6.2; the F5 plan conflict; the placement extensions).

## 3. Rust side in MC.0 (`core/crates/fylz-actions`)

`src/lib.rs`: `ActionId` with `parse()` enforcing §2.2's grammar, and plain `ActionDef`,
`Placement`, `ConfirmPolicy`, `ActionBody`, `Origin`, `KeyChord`, `GestureId`, `TargetKind` — the
data half only, matching the Kotlin `ActionDef` field for field. No serde, no dependencies, one
grammar test, `clippy -D warnings` clean, in the workspace and `deny.toml` like every crate. MC.1
adds serde with §C4's shapes and the round-trip test.

## 4. What MC.0 changes for the user

Nothing they can see, by design, except: hardware-keyboard shortcuts work for the first time (both
tables were dead), a Commands entry appears in the overflow menu (and Ctrl+K), and a "Customisation
problems" row appears in the Tools room only if the registry detects a conflict.

## 5. Risks and how the design answers them

| Risk | Answer |
|---|---|
| Disabled items would vanish | `visibleWhen`/`enabledWhen`; resolver returns `(id, enabled, …)`; oracle compares pairs. |
| Root key handler pre-empts text fields; nothing focused in touch mode | `onKeyEvent` (post-child) + `KeyRouter` text-field guard + a focusable root that takes focus when nothing else has it. |
| Actions acting on a row/tab the state doesn't hold | `ActionTarget` + `requiresTarget`; palette/shortcuts exclude or supply the documented target; `Gesture.targetWhen` splits double-tap. |
| Placement model vs today's layout | `Toolbar.bar`, `Menu`, four `RoomId`s; surface gates in renderers; orders copied from code. |
| Selection order and cost | Listing-order `selection` (linear, as today); `selectionOrder` only for PDF merge. |
| Self-confirming oracle | Differential: legacy expressions lifted and used by the old code in MC.0a; frozen after MC.0f. |
| Edge drags not interceptable (read-only submodule) | `Gesture(EDGE_*)` is declarative room-to-edge assignment; only shake and item gestures dispatch. |
| Merging duplicate handlers changes toasts/titles | `Caller` hint keeps each call site's messages. |
| `OperationHistoryActivity` reachable via launcher shortcut | Not deleted; not in MC.0. |
| `WorkspacePane` dependency | Moved to `DualPaneModels.kt` before the policy files go. |
| Recovery room missed by the inventory | Five cards + archive-tools menu registered (§2.5); `RecoveryActionCard(` in the menu scan. |
| Favourite has no phone placement; `recycle` has no confirmation | Preserved; §6. |

## 6. Follow-ups this design deliberately leaves (log in `PROGRESS.md` when MC.0 lands)

1. Favourite / Open root / Recycle Bin have no narrow-screen placement — MC.3.
2. `fylz.recycle` runs without confirmation — product decision for Madhav (`REVIEW_QUEUE.md`).
3. `IndexManagerActivity` vs `PostV1ToolsActivity` duplication — consolidate in M9/MC.3.
4. `ViewMode.DETAILS` unreachable — M12.5. 5. Back/forward history, jump-to-path — M12.3/M12.6.
6. Checksum reachable only inside `ConflictSheet` — M4.6/M7.2. 7. Per-item context-menu popup — MC.3.
8. F5: §M12.1 (pane copy) vs §M12.3 (refresh) — M12 decides; the conflict detector will flag it.
9. `StorageHomeScreen` buttons and the launcher shortcuts as `HomeCard`/registry entries — M9.1.

## 7. Acceptance (§D restated as checks)

- `ActionResolverGoldenTest` green with the differential oracle (MC.0a–e) and the frozen table (MC.0f).
- `NoHardCodedMenusTest` green: `DropdownMenuItem(`/`ActionButton(`/`ToolsRow(`/`RecoveryActionCard(` only under `ui/actions/`.
- `ShortcutTableTest`, `KeyRouterTest`, `ActionIdGrammarTest` (both languages) green; `registry.problems` empty for built-ins.
- `FylzV1AppSizeTest` green with the post-MC.0 ratchet; `FylzV1App.kt` smaller than 2529 lines.
- The gate green after each of the six commits; `KeyboardShortcutPolicy.kt`, `DesktopWorkspacePolicy.kt` gone (grep-verified), `DualPaneModels.kt` compiling with `WorkspacePane` inside it.

## 8. What the review changed (revision 1 → 2)

Split visibility from enablement; `onKeyEvent` + focus strategy instead of `onPreviewKeyEvent`;
`ActionTarget`/`requiresTarget`; `Toolbar.bar`, `Menu`, four room ids, orders copied from code,
"Clear" last; listing-order selection with `selectionOrder` aside; the differential oracle and the
fuller fixture list; edge drags declarative; `Caller`-specific messages; `OperationHistoryActivity`
kept; `WorkspacePane` moved before deletion; the Recovery room and `fylz.protect`/`fylz.archive.inspect`
registered; the explicit exclusion list; Alt+Left/Alt+Backspace/preview-toggle dropped; the
`ActionDef`/`BuiltInBinding` split so the Rust mirror is honest; serde deferred to MC.1; MC.0b split
into three commits; the size test made a ratchet; the menu scan widened; `FylzV1AppLogicTest` left
alone. The inventory's two factual errors (an "orphaned" activity a launcher shortcut opens; the
Recovery room in `FylzAppShell.kt` not surveyed) are why §1 was re-verified line by line.
