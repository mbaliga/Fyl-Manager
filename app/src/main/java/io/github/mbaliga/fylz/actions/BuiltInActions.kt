package io.github.mbaliga.fylz.actions

import androidx.compose.ui.input.key.Key
import io.github.mbaliga.fylz.browse.SortField
import io.github.mbaliga.fylz.model.BrowsableArchiveFormats
import io.github.mbaliga.fylz.model.EntryKind
import io.github.mbaliga.fylz.model.ThemeMode
import io.github.mbaliga.fylz.model.ViewMode
import io.github.mbaliga.fylz.model.isBrowsableArchive
import io.github.mbaliga.fylz.ui.clipboardChipLabel

private val ALWAYS: (BrowserState) -> Boolean = { true }
private val HAS_SELECTION: (BrowserState) -> Boolean = { it.selectionCount > 0 }

/** M3.4 (design §2.1): `fylz.extract`/its Extract-sheet children are visible from a FOLDER
 * location with the selection's own extractability; `fylz.extract.selected` takes over inside an
 * ARCHIVE location, where the selection is already entries of the browsed archive, not a file to
 * open. Mutually exclusive by [LocationKind]: exactly one `SelectionBar(90)` action ever renders. */
private val CAN_EXTRACT: (BrowserState) -> Boolean = { state ->
    state.selection.size == 1 && BrowsableArchiveFormats.matches(state.selection.first().name)
}
private val EXTRACT_VISIBLE: (BrowserState) -> Boolean = { HAS_SELECTION(it) && it.locationKind != LocationKind.ARCHIVE }

/** M3.3 (DESIGN-M33 §2.6): an archive location is read-only -- every action that writes the
 * selection's source or the current location is disabled there, except the two M3.6 re-enables
 * just below. `ActionResolverGoldenTest`'s `LegacyOracle.writableLocation` is this rule's frozen
 * twin. */
private val WRITABLE_LOCATION: (BrowserState) -> Boolean = { it.locationKind != LocationKind.ARCHIVE }

/** M3.6: `fylz.rename`/`fylz.recycle` act on a ZIP-family archive's own entries (add/delete/rename
 * in place) exactly as they act on a folder's; every other archive location -- a non-ZIP format, or
 * the archive root itself, which is never a selectable entry -- stays read-only under
 * [WRITABLE_LOCATION]. `ActionResolverGoldenTest`'s `LegacyOracle.archiveEntryWritable` mirrors
 * this. */
private val ARCHIVE_ENTRY_WRITABLE: (BrowserState) -> Boolean = { state ->
    WRITABLE_LOCATION(state) || (state.locationKind == LocationKind.ARCHIVE && state.isZipFamilyArchiveLocation)
}

private fun def(
    id: String,
    title: String,
    icon: String,
    placements: List<Placement>,
    destructive: Boolean = false,
    requiresTarget: TargetKind? = null,
) = ActionDef(
    id = ActionId.parse(id),
    titleKey = title,
    icon = IconRef.Builtin(icon),
    placements = placements,
    confirm = ConfirmPolicy.None,
    body = ActionBody.BuiltIn(id),
    origin = Origin.BuiltIn,
    destructive = destructive,
    requiresTarget = requiresTarget,
)

/**
 * The complete built-in table (design §2.5), transcribed literally: every id, placement, order,
 * `visibleWhen`/`enabledWhen`, label, `checked` and shortcut. Handler bodies are **not** moved in
 * MC.0a (design §2.8 item 1) -- each `run` calls the matching [ActionContext] method for the
 * actions that already have one named/externally-callable today; `fylz.commands` gained one in
 * MC.0c, `fylz.customisation.problems` in MC.0e, once the surface each opens existed. Some
 * built-ins still have no such thing to delegate to (the self-contained Recovery-room overlays and
 * Archive Tools menu, each still triggered by its own embedded FAB with no external "open" hook;
 * search-field focus, which has no keyboard wiring to a `FocusRequester` yet) -- those `run` bodies
 * stay documented no-ops rather than invented behaviour.
 */
object BuiltInActions {
    fun all(): List<BuiltInBinding> = selectionBar() + topAppBar() + browserRow() + rowsAndCards() +
        locationsRoom() + libraryRail() + toolsRoom() + recoveryRoom() + archiveToolsMenu() + extractMenu() + roomOpenGestures()

    private fun selectionBar(): List<BuiltInBinding> = listOf(
        BuiltInBinding(
            def = def("fylz.cut", "Cut", "ContentCut", listOf(Placement.SelectionBar(10), Placement.Shortcut(KeyChord(Key.X, ctrl = true)))),
            visibleWhen = HAS_SELECTION,
            enabledWhen = WRITABLE_LOCATION,
            run = { ctx, _, _ -> ctx.cut() },
        ),
        BuiltInBinding(
            def = def("fylz.copy", "Copy", "ContentCopy", listOf(Placement.SelectionBar(20), Placement.Shortcut(KeyChord(Key.C, ctrl = true)))),
            visibleWhen = HAS_SELECTION,
            enabledWhen = ALWAYS,
            run = { ctx, _, _ -> ctx.copy() },
        ),
        BuiltInBinding(
            def = def("fylz.copy-to", "Copy to…", "FolderCopy", listOf(Placement.SelectionBar(30))),
            visibleWhen = HAS_SELECTION,
            enabledWhen = ALWAYS,
            run = { ctx, _, _ -> ctx.copyTo() },
        ),
        BuiltInBinding(
            def = def("fylz.move-to", "Move to…", "DriveFileMove", listOf(Placement.SelectionBar(40))),
            visibleWhen = HAS_SELECTION,
            enabledWhen = WRITABLE_LOCATION,
            run = { ctx, _, _ -> ctx.moveTo() },
        ),
        BuiltInBinding(
            def = def(
                "fylz.recycle", "Recycle", "Delete",
                listOf(Placement.SelectionBar(50), Placement.Shortcut(KeyChord(Key.Delete))),
                destructive = true,
            ),
            visibleWhen = HAS_SELECTION,
            enabledWhen = ARCHIVE_ENTRY_WRITABLE,
            run = { ctx, _, _ -> ctx.recycleSelection() },
        ),
        BuiltInBinding(
            def = def("fylz.rename", "Rename", "Edit", listOf(Placement.SelectionBar(60), Placement.Shortcut(KeyChord(Key.F2)))),
            visibleWhen = HAS_SELECTION,
            enabledWhen = { it.selection.size == 1 && ARCHIVE_ENTRY_WRITABLE(it) },
            run = { ctx, _, _ -> ctx.rename() },
        ),
        BuiltInBinding(
            def = def("fylz.tags", "Tags", "Tag", listOf(Placement.SelectionBar(70))),
            visibleWhen = HAS_SELECTION,
            enabledWhen = WRITABLE_LOCATION,
            run = { ctx, _, _ -> ctx.tags() },
        ),
        BuiltInBinding(
            def = def("fylz.compress", "Archive", "Archive", listOf(Placement.SelectionBar(80))),
            visibleWhen = HAS_SELECTION,
            enabledWhen = ALWAYS,
            run = { ctx, _, _ -> ctx.compress() },
        ),
        BuiltInBinding(
            def = def("fylz.extract", "Extract", "FolderOpen", listOf(Placement.SelectionBar(90))),
            visibleWhen = EXTRACT_VISIBLE,
            enabledWhen = CAN_EXTRACT,
            run = { ctx, _, _ -> ctx.extract() },
        ),
        BuiltInBinding(
            def = def("fylz.extract.selected", "Extract selected entries", "FolderOpen", listOf(Placement.SelectionBar(90))),
            visibleWhen = { HAS_SELECTION(it) && it.locationKind == LocationKind.ARCHIVE },
            enabledWhen = { it.selection.isNotEmpty() },
            run = { ctx, _, _ -> ctx.extractSelected() },
        ),
        BuiltInBinding(
            def = def("fylz.rename.batch", "Batch rename", "TextSnippet", listOf(Placement.SelectionBar(100))),
            visibleWhen = HAS_SELECTION,
            enabledWhen = WRITABLE_LOCATION,
            run = { ctx, _, _ -> ctx.batchRename() },
        ),
        BuiltInBinding(
            def = def("fylz.pdf.tools", "PDF tools", "PictureAsPdf", listOf(Placement.SelectionBar(110))),
            visibleWhen = HAS_SELECTION,
            enabledWhen = { state -> state.selection.isNotEmpty() && state.selection.all { it.kind == EntryKind.PDF } },
            run = { ctx, _, _ -> ctx.pdfTools() },
        ),
        BuiltInBinding(
            def = def("fylz.share", "Share", "Share", listOf(Placement.SelectionBar(120))),
            visibleWhen = HAS_SELECTION,
            enabledWhen = ALWAYS,
            run = { ctx, _, _ -> ctx.share() },
        ),
        BuiltInBinding(
            def = def("fylz.select.clear", "Clear", "Close", listOf(Placement.SelectionBar(130), Placement.Shortcut(KeyChord(Key.Escape)))),
            visibleWhen = HAS_SELECTION,
            enabledWhen = ALWAYS,
            run = { ctx, _, _ -> ctx.clearSelection() },
        ),
    )

    private fun topAppBar(): List<BuiltInBinding> = listOf(
        BuiltInBinding(
            def = def("fylz.paste", "Paste", "ContentPaste", listOf(Placement.Toolbar(Bar.TOP_APP_BAR, 10), Placement.Shortcut(KeyChord(Key.V, ctrl = true)))),
            visibleWhen = { it.clipboard != null },
            enabledWhen = { it.hasActiveTab && WRITABLE_LOCATION(it) },
            label = { state -> state.clipboard?.let(::clipboardChipLabel) ?: "Paste" },
            run = { ctx, _, _ -> ctx.paste() },
        ),
        BuiltInBinding(
            def = def("fylz.clipboard.clear", "Clear clipboard", "Close", listOf(Placement.Toolbar(Bar.TOP_APP_BAR, 11))),
            visibleWhen = { it.clipboard != null },
            enabledWhen = ALWAYS,
            run = { ctx, _, _ -> ctx.clearClipboard() },
        ),
        BuiltInBinding(
            def = def("fylz.view.toggle", "Change view", "GridView", listOf(Placement.Toolbar(Bar.TOP_APP_BAR, 20))),
            visibleWhen = ALWAYS,
            enabledWhen = ALWAYS,
            label = { state -> if (state.viewMode == io.github.mbaliga.fylz.model.ViewMode.GRID) "Switch to list view" else "Switch to grid view" },
            run = { ctx, _, _ -> ctx.toggleViewMode() },
        ),
        BuiltInBinding(
            def = def(
                "fylz.refresh", "Refresh", "Refresh",
                listOf(
                    Placement.Toolbar(Bar.TOP_APP_BAR, 30),
                    Placement.Gesture(GestureId.SHAKE),
                    Placement.Shortcut(KeyChord(Key.F5)),
                    Placement.Shortcut(KeyChord(Key.R, ctrl = true)),
                ),
            ),
            visibleWhen = ALWAYS,
            enabledWhen = ALWAYS,
            run = { ctx, _, _ -> ctx.refresh() },
        ),
        BuiltInBinding(
            def = def("fylz.new-folder", "New folder", "CreateNewFolder", listOf(Placement.Menu(MenuId.OVERFLOW, 10), Placement.Shortcut(KeyChord(Key.N, ctrl = true, shift = true)))),
            visibleWhen = ALWAYS,
            enabledWhen = { it.hasActiveTab && WRITABLE_LOCATION(it) },
            run = { ctx, _, _ -> ctx.newFolder() },
        ),
        BuiltInBinding(
            def = def("fylz.new-file", "New text file", "TextSnippet", listOf(Placement.Menu(MenuId.OVERFLOW, 20), Placement.Shortcut(KeyChord(Key.N, ctrl = true)))),
            visibleWhen = ALWAYS,
            enabledWhen = { it.hasActiveTab && WRITABLE_LOCATION(it) },
            run = { ctx, _, _ -> ctx.newFile() },
        ),
        BuiltInBinding(
            def = def("fylz.scan-to-pdf", "Scan to PDF", "PictureAsPdf", listOf(Placement.Menu(MenuId.OVERFLOW, 30))),
            visibleWhen = ALWAYS,
            enabledWhen = { it.hasActiveTab && WRITABLE_LOCATION(it) },
            run = { ctx, _, _ -> ctx.scanToPdf() },
        ),
        BuiltInBinding(
            def = def("fylz.find-duplicates", "Find duplicates", "FindReplace", listOf(Placement.Menu(MenuId.OVERFLOW, 40))),
            visibleWhen = ALWAYS,
            // Inside an archive this would materialise every entry to hash it (DESIGN-M33 §2.4).
            enabledWhen = { state -> state.entries.count { !it.isDirectory } > 1 && WRITABLE_LOCATION(state) },
            run = { ctx, _, _ -> ctx.findDuplicates() },
        ),
        BuiltInBinding(
            def = def("fylz.ai.organize", "AI organize proposal", "AutoAwesome", listOf(Placement.Menu(MenuId.OVERFLOW, 50))),
            visibleWhen = ALWAYS,
            enabledWhen = { it.focused != null && WRITABLE_LOCATION(it) },
            run = { ctx, _, _ -> ctx.aiOrganize() },
        ),
        BuiltInBinding(
            def = def("fylz.commands", "Commands", "Search", listOf(Placement.Menu(MenuId.OVERFLOW, 90), Placement.CommandPalette, Placement.Shortcut(KeyChord(Key.K, ctrl = true)))),
            visibleWhen = ALWAYS,
            enabledWhen = ALWAYS,
            run = { ctx, _, _ -> ctx.openCommandPalette() },
        ),
    )

    private fun browserRow(): List<BuiltInBinding> = listOf(
        BuiltInBinding(
            def = def(
                "fylz.navigate.up", "Parent folder", "ArrowBack",
                listOf(Placement.Toolbar(Bar.BROWSER_ROW, 10), Placement.Shortcut(KeyChord(Key.DirectionUp, alt = true)), Placement.Shortcut(KeyChord(Key.Backspace))),
            ),
            visibleWhen = ALWAYS,
            // BrowserState.canNavigateUp is (activeTab?.locations?.size ?: 0) > 1, computed once
            // where BrowserState is built.
            enabledWhen = { it.canNavigateUp },
            run = { ctx, _, _ -> ctx.navigateUp() },
        ),
        BuiltInBinding(
            def = def("fylz.search.focus", "Search", "Search", listOf(Placement.Shortcut(KeyChord(Key.F, ctrl = true)))),
            visibleWhen = ALWAYS,
            enabledWhen = ALWAYS,
            // No FocusRequester exists on the search field yet; nothing to delegate to today.
            run = { _, _, _ -> },
        ),
        BuiltInBinding(
            def = def("fylz.select.all", "Select all", "SelectAll", listOf(Placement.Toolbar(Bar.BROWSER_ROW, 20), Placement.Shortcut(KeyChord(Key.A, ctrl = true)))),
            visibleWhen = ALWAYS,
            enabledWhen = { it.visibleEntries.isNotEmpty() },
            run = { ctx, _, _ -> ctx.selectAll() },
        ),
        sortAction(SortField.NAME, 10),
        sortAction(SortField.SIZE, 20),
        sortAction(SortField.MODIFIED, 30),
        sortAction(SortField.TYPE, 40),
        BuiltInBinding(
            def = def("fylz.sort.folders-first", "Folders first", "Sort", listOf(Placement.Menu(MenuId.SORT, 50))),
            visibleWhen = ALWAYS,
            enabledWhen = ALWAYS,
            checked = { it.sortSpec.foldersFirst },
            run = { ctx, _, _ -> ctx.toggleFoldersFirst() },
        ),
    )

    private fun sortAction(field: SortField, order: Int): BuiltInBinding = BuiltInBinding(
        def = def("fylz.sort.${field.name.lowercase()}", field.label, "Sort", listOf(Placement.Menu(MenuId.SORT, order))),
        visibleWhen = ALWAYS,
        enabledWhen = ALWAYS,
        label = { state ->
            if (state.sortSpec.field == field) "${field.label} · ${state.sortSpec.direction.label}" else field.label
        },
        checked = { it.sortSpec.field == field },
        run = { ctx, _, _ -> ctx.setSortField(field) },
    )

    private fun rowsAndCards(): List<BuiltInBinding> = listOf(
        BuiltInBinding(
            def = def(
                "fylz.open", "Open", "FolderOpen",
                listOf(
                    Placement.Gesture(GestureId.ITEM_TAP),
                    // M3.3: a double-tap on a browsable archive browses it, like a folder.
                    Placement.Gesture(GestureId.ITEM_DOUBLE_TAP, targetWhen = { it is ActionTarget.Entry && (it.entry.isDirectory || it.entry.isBrowsableArchive) }),
                    Placement.Shortcut(KeyChord(Key.Enter)),
                ),
                requiresTarget = TargetKind.ENTRY,
            ),
            visibleWhen = ALWAYS,
            enabledWhen = ALWAYS,
            run = { ctx, _, target -> (target as? ActionTarget.Entry)?.let { ctx.open(it.entry) } },
        ),
        BuiltInBinding(
            def = def(
                "fylz.open-with", "Open with…", "OpenWith",
                listOf(
                    Placement.Gesture(GestureId.ITEM_DOUBLE_TAP, targetWhen = { it is ActionTarget.Entry && !it.entry.isDirectory && !it.entry.isBrowsableArchive }),
                    Placement.ContextMenu("open", 10),
                ),
                requiresTarget = TargetKind.FILE,
            ),
            visibleWhen = ALWAYS,
            enabledWhen = ALWAYS,
            run = { ctx, _, target -> (target as? ActionTarget.Entry)?.let { ctx.openWith(it.entry) } },
        ),
        BuiltInBinding(
            def = def(
                "fylz.select.toggle", "Select", "CheckBox",
                listOf(Placement.Gesture(GestureId.ITEM_LONG_PRESS)),
                requiresTarget = TargetKind.ENTRY,
            ),
            visibleWhen = ALWAYS,
            enabledWhen = ALWAYS,
            run = { ctx, _, target -> (target as? ActionTarget.Entry)?.let { ctx.toggleSelected(it.entry) } },
        ),
    )

    private fun locationsRoom(): List<BuiltInBinding> = listOf(
        BuiltInBinding(
            def = def("fylz.tab.add", "Add a location…", "Add", listOf(Placement.Room(RoomId.LOCATIONS, 10), Placement.Shortcut(KeyChord(Key.T, ctrl = true)))),
            visibleWhen = ALWAYS,
            enabledWhen = ALWAYS,
            run = { ctx, _, _ -> ctx.addTab() },
        ),
        BuiltInBinding(
            def = def(
                "fylz.tab.close", "Close", "Close",
                listOf(Placement.Room(RoomId.LOCATIONS, 20), Placement.Shortcut(KeyChord(Key.W, ctrl = true))),
                requiresTarget = TargetKind.TAB,
            ),
            visibleWhen = ALWAYS,
            enabledWhen = ALWAYS,
            run = { ctx, _, target -> (target as? ActionTarget.Tab)?.let { ctx.closeTab(it.id) } },
        ),
    )

    private fun libraryRail(): List<BuiltInBinding> = listOf(
        BuiltInBinding(
            def = def("fylz.open-root", "Open root", "FolderOpen", listOf(Placement.Room(RoomId.LIBRARY_RAIL, 10))),
            visibleWhen = ALWAYS,
            enabledWhen = ALWAYS,
            run = { ctx, _, _ -> ctx.openRoot() },
        ),
        BuiltInBinding(
            def = def("fylz.favourite.toggle", "Favourite", "Star", listOf(Placement.Room(RoomId.LIBRARY_RAIL, 20))),
            visibleWhen = ALWAYS,
            // Favourites are tree folders; an archive location is not one (DESIGN-M33 §2.6).
            enabledWhen = { it.hasActiveTab && WRITABLE_LOCATION(it) },
            checked = { it.currentFolderIsFavourite },
            run = { ctx, _, _ -> ctx.toggleFavourite() },
        ),
        recycleBinAction,
    )

    // One action, two Room placements (design §2.5: "An action may carry two Room placements
    // (Recycle Bin: rail and Tools) -- the per-surface gate is the renderer's"). A single
    // BuiltInBinding, not two -- ActionId is unique per action, not per placement.
    private val recycleBinAction: BuiltInBinding = BuiltInBinding(
        def = def(
            "fylz.recycle-bin", "Recycle Bin", "RestoreFromTrash",
            listOf(Placement.Room(RoomId.LIBRARY_RAIL, 30), Placement.Room(RoomId.TOOLS, 10)),
        ),
        visibleWhen = ALWAYS,
        enabledWhen = ALWAYS,
        run = { ctx, _, _ -> ctx.openRecycleBin() },
    )

    // fylz.recycle-bin itself is listed once, in libraryRail() -- its def already carries both
    // the LIBRARY_RAIL and TOOLS Room placements; listing the same binding again here would
    // register the same ActionId twice.
    private fun toolsRoom(): List<BuiltInBinding> = listOf(
        BuiltInBinding(
            def = def("fylz.remotes", "Network locations", "Cloud", listOf(Placement.Room(RoomId.TOOLS, 20))),
            visibleWhen = ALWAYS,
            enabledWhen = ALWAYS,
            run = { ctx, _, _ -> ctx.openRemotes() },
        ),
        BuiltInBinding(
            def = def("fylz.webdav.quick", "Quick WebDAV listing", "Cloud", listOf(Placement.Room(RoomId.TOOLS, 30))),
            visibleWhen = ALWAYS,
            enabledWhen = ALWAYS,
            run = { ctx, _, _ -> ctx.openWebDavQuick() },
        ),
        BuiltInBinding(
            def = def("fylz.tools", "Tools", "Build", listOf(Placement.Room(RoomId.TOOLS, 40))),
            visibleWhen = ALWAYS,
            enabledWhen = ALWAYS,
            run = { ctx, _, _ -> ctx.openToolsActivity() },
        ),
        BuiltInBinding(
            def = def("fylz.index", "Local index", "List", listOf(Placement.Room(RoomId.TOOLS, 50))),
            visibleWhen = ALWAYS,
            enabledWhen = ALWAYS,
            run = { ctx, _, _ -> ctx.openIndexActivity() },
        ),
        themeAction(ThemeMode.SYSTEM, "Follow the system", 60),
        themeAction(ThemeMode.LIGHT, "Light", 61),
        themeAction(ThemeMode.DARK, "Dark", 62),
        BuiltInBinding(
            def = def("fylz.customisation.problems", "Customisation problems", "Warning", listOf(Placement.Room(RoomId.TOOLS, 90))),
            // MC.0e (design §2.3 clarification): registryProblemCount is supplied when BrowserState
            // is assembled, once the registry that produced it already exists -- the built-in
            // table's own registry.problems is empty by construction (ShortcutTableTest asserts
            // this), so this stays "never for the shipped built-ins" (design §4) until a user-
            // defined action bundle (MC.1+) can actually conflict with something.
            visibleWhen = { it.registryProblemCount > 0 },
            enabledWhen = ALWAYS,
            run = { ctx, _, _ -> ctx.showRegistryProblems() },
        ),
    )

    private fun themeAction(mode: ThemeMode, title: String, order: Int): BuiltInBinding = BuiltInBinding(
        // "Brightness" (MC.0a) named no real vector either -- the old theme rows drew no icon at
        // all (a filled/hollow square selection marker, not an `Icon`). "None" documents that the
        // Tools room renders these text-only, exactly as before (MC.0d fix).
        def = def("fylz.theme.${mode.name.lowercase()}", title, "None", listOf(Placement.Room(RoomId.TOOLS, order))),
        visibleWhen = ALWAYS,
        enabledWhen = ALWAYS,
        checked = { it.themeMode == mode },
        run = { ctx, _, _ -> ctx.setThemeMode(mode) },
    )

    private fun recoveryRoom(): List<BuiltInBinding> = listOf(
        BuiltInBinding(
            def = def("fylz.history.operations", "Operation history", "History", listOf(Placement.Room(RoomId.RECOVERY, 10), Placement.Shortcut(KeyChord(Key.H, ctrl = true, shift = true)))),
            visibleWhen = ALWAYS,
            enabledWhen = ALWAYS,
            label = { state ->
                if (state.operationsNeedingAttention == 0) "Operation history" else "Operation history (${state.operationsNeedingAttention})"
            },
            run = { ctx, _, _ -> ctx.showOperationHistory() },
        ),
        BuiltInBinding(
            def = def("fylz.history.files", "File history", "History", listOf(Placement.Room(RoomId.RECOVERY, 20))),
            visibleWhen = ALWAYS,
            enabledWhen = ALWAYS,
            // FileHistoryOverlay is a self-contained composable with its own FAB and dialog state;
            // it has no external "open" trigger to delegate to today (design §2.8 item 1 -- handler
            // bodies move only when they retire their last old caller; that overlay isn't retired
            // in MC.0a). Excluded from the command palette (design §2.6) for the same reason.
            paletteVisible = false,
            run = { _, _, _ -> },
        ),
        BuiltInBinding(
            def = def("fylz.backup.plans", "Backup plans", "Backup", listOf(Placement.Room(RoomId.RECOVERY, 30))),
            visibleWhen = ALWAYS,
            enabledWhen = ALWAYS,
            paletteVisible = false,
            run = { _, _, _ -> },
        ),
        BuiltInBinding(
            def = def("fylz.backup.import", "Import existing backups", "RestorePage", listOf(Placement.Room(RoomId.RECOVERY, 40))),
            visibleWhen = ALWAYS,
            enabledWhen = ALWAYS,
            paletteVisible = false,
            run = { _, _, _ -> },
        ),
        BuiltInBinding(
            def = def("fylz.archive.tools", "Archive tools", "Archive", listOf(Placement.Room(RoomId.RECOVERY, 50))),
            visibleWhen = ALWAYS,
            enabledWhen = ALWAYS,
            paletteVisible = false,
            run = { _, _, _ -> },
        ),
    )

    private fun archiveToolsMenu(): List<BuiltInBinding> = listOf(
        BuiltInBinding(
            // "Lock" (MC.0a) named no real vector; the old Create-ZIP button actually drew
            // Icons.Outlined.Archive, not a lock (MC.0d fix -- see the implementation report).
            def = def("fylz.protect", "Create ZIP", "Archive", listOf(Placement.Menu(MenuId.ARCHIVE_TOOLS, 10))),
            visibleWhen = ALWAYS,
            enabledWhen = ALWAYS,
            // ArchiveToolsOverlay owns its own menu/dialog state with no external trigger today.
            run = { _, _, _ -> },
        ),
        BuiltInBinding(
            def = def("fylz.archive.inspect", "Inspect and extract ZIP", "Unarchive", listOf(Placement.Menu(MenuId.ARCHIVE_TOOLS, 20))),
            visibleWhen = ALWAYS,
            enabledWhen = ALWAYS,
            run = { _, _, _ -> },
        ),
        BuiltInBinding(
            // M3.8: verifies CRCs without extracting, over the same picker/format gate Inspect
            // uses -- ArchiveToolsOverlay's own picker decides per file whether the picked format is
            // one `BrowsableArchiveFormats` (CAN_EXTRACT's own gate) can actually be tested.
            def = def("fylz.archive.test", "Test archive", "Unarchive", listOf(Placement.Menu(MenuId.ARCHIVE_TOOLS, 25))),
            visibleWhen = ALWAYS,
            enabledWhen = ALWAYS,
            run = { _, _, _ -> },
        ),
        BuiltInBinding(
            // M3.6: "add" is the one edit-in-place action with nothing to select first -- visible
            // only while actually browsing a ZIP-family archive (never the general-purpose slots
            // above, which work from anywhere). Opens the same multi-file picker
            // `CompressPlanner`'s own sources come from.
            def = def("fylz.archive.add-entries", "Add files to this archive", "Archive", listOf(Placement.Menu(MenuId.ARCHIVE_TOOLS, 30))),
            visibleWhen = { it.locationKind == LocationKind.ARCHIVE && it.isZipFamilyArchiveLocation },
            enabledWhen = ALWAYS,
            run = { ctx, _, _ -> ctx.addArchiveEntries() },
        ),
    )

    /** The Extract sheet's three choices (design §2.1), only ever shown from `fylz.extract`
     * (a FOLDER location) -- same visibility/enablement as `fylz.extract` itself, since the sheet
     * that hosts them is `fylz.extract`'s own destination. `ExtractSheet` resolves this menu and
     * maps a click straight to the matching `ActionContext` method, the same pattern
     * `ArchiveToolsMenuDialog` uses for `MenuId.ARCHIVE_TOOLS`. */
    private fun extractMenu(): List<BuiltInBinding> = listOf(
        BuiltInBinding(
            def = def("fylz.extract.here", "Extract here", "FolderOpen", listOf(Placement.Menu(MenuId.EXTRACT, 10))),
            visibleWhen = EXTRACT_VISIBLE,
            enabledWhen = CAN_EXTRACT,
            run = { ctx, _, _ -> ctx.extractHere() },
        ),
        BuiltInBinding(
            def = def("fylz.extract.folder", "Extract to folder", "FolderOpen", listOf(Placement.Menu(MenuId.EXTRACT, 20))),
            visibleWhen = EXTRACT_VISIBLE,
            enabledWhen = CAN_EXTRACT,
            run = { ctx, _, _ -> ctx.extractIntoFolder() },
        ),
        BuiltInBinding(
            def = def("fylz.extract.to", "Extract to…", "FolderOpen", listOf(Placement.Menu(MenuId.EXTRACT, 30))),
            visibleWhen = EXTRACT_VISIBLE,
            enabledWhen = CAN_EXTRACT,
            run = { ctx, _, _ -> ctx.extractTo() },
        ),
    )

    private fun roomOpenGestures(): List<BuiltInBinding> = listOf(
        BuiltInBinding(
            def = def("fylz.room.locations", "Locations", "Room", listOf(Placement.Gesture(GestureId.EDGE_LEFT))),
            visibleWhen = ALWAYS,
            enabledWhen = ALWAYS,
            run = { ctx, _, _ -> ctx.openRoom(RoomId.LOCATIONS) },
        ),
        BuiltInBinding(
            def = def("fylz.room.tools", "Tools", "Room", listOf(Placement.Gesture(GestureId.EDGE_RIGHT))),
            visibleWhen = ALWAYS,
            enabledWhen = ALWAYS,
            run = { ctx, _, _ -> ctx.openRoom(RoomId.TOOLS) },
        ),
        BuiltInBinding(
            def = def("fylz.room.recovery", "Recovery", "Room", listOf(Placement.Gesture(GestureId.EDGE_BOTTOM))),
            visibleWhen = ALWAYS,
            enabledWhen = ALWAYS,
            run = { ctx, _, _ -> ctx.openRoom(RoomId.RECOVERY) },
        ),
    )
}
