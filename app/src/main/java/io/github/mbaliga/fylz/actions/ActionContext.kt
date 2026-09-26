package io.github.mbaliga.fylz.actions

import android.net.Uri
import io.github.mbaliga.fylz.browse.SortField
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.ThemeMode

/**
 * The callbacks a built-in's `run` actually calls -- the same lambdas/functions
 * `FylzV1Workspace`/`FylzAppShell` already own (design §2.4). MC.0a wired a real implementation of
 * this in `FylzV1Workspace`; MC.0b/c wired the selection bar, top app bar, overflow, browser row,
 * sort menu and the command palette (`openCommandPalette`) through it. A handful of built-ins still
 * have no existing external trigger to delegate to at all (the self-contained Recovery-room
 * overlays, the Archive tools menu, search-field focus, the customisation-problems surface) --
 * those built-ins' `run` bodies are documented no-ops in
 * [io.github.mbaliga.fylz.actions.BuiltInActions] rather than calling a method here, since there
 * is nothing today to preserve the behaviour of.
 */
interface ActionContext {
    fun cut()
    fun copy()
    fun copyTo()
    fun moveTo()
    fun recycleSelection()
    fun rename()
    fun tags()

    /** `fylz.compress` (design M3.5 §2.1): opens the Compress sheet for the current selection. */
    fun compress()

    /** `fylz.extract` (design M3.4 §2.1): opens the Extract sheet for the single selected archive. */
    fun extract()

    /** `fylz.extract.here`: destination is the current tab's own folder, `Selection.All`. */
    fun extractHere()

    /** `fylz.extract.folder`: destination is the current tab's own folder, into a new folder. */
    fun extractIntoFolder()

    /** `fylz.extract.to`: the destination chooser, then as [extractIntoFolder]. */
    fun extractTo()

    /** `fylz.extract.selected` (a browsed archive's own selection bar): the destination chooser,
     * then the selected entries, `Selection.Entries`. */
    fun extractSelected()

    /** `ArchiveToolsOverlay`'s own "Extract" button (design §2.1: "the overlay's extract calls
     * the same flow"): opens the same Extract sheet [extract] does, for the archive its own
     * picker chose rather than the current selection. */
    fun openExtractMenu(archive: Uri)

    /** `ArchiveToolsOverlay`'s own "Create ZIP" button, for the sources its own picker chose
     * rather than the current selection: opens the same Compress sheet [compress] does, unless
     * the AES switch is on, which stays on the legacy zip4j path (no password support here yet). */
    fun openCompressMenu(sources: List<Uri>)

    /** `fylz.archive.add-entries` (M3.6): opens the picker for files to add to the ZIP-family
     * archive currently being browsed, at the folder within it currently being browsed. */
    fun addArchiveEntries()

    fun batchRename()
    fun pdfTools()
    fun share()
    fun clearSelection()

    fun paste()
    fun clearClipboard()
    fun toggleViewMode()
    fun refresh()

    fun newFolder()
    fun newFile()
    fun scanToPdf()
    fun findDuplicates()
    fun aiOrganize()

    fun navigateUp()
    fun selectAll()

    fun setSortField(field: SortField)
    fun toggleFoldersFirst()

    fun open(entry: FileEntry)
    fun openWith(entry: FileEntry)
    fun toggleSelected(entry: FileEntry)

    fun addTab()
    fun closeTab(tabId: String)

    fun openRoot()
    fun toggleFavourite()
    fun openRecycleBin()

    fun openRemotes()
    fun openWebDavQuick()
    fun openToolsActivity()
    fun openIndexActivity()
    fun setThemeMode(mode: ThemeMode)

    fun showOperationHistory()

    fun openRoom(room: RoomId)

    fun openCommandPalette()

    /** `fylz.customisation.problems`'s handler (MC.0e): a `(BrowserState) -> Boolean` predicate
     * can't see the registry it belongs to, so the row's own `run` reaches back out through this
     * method instead, the same way the four self-contained Recovery overlays and Archive Tools
     * menu each needed their own escape hatch. */
    fun showRegistryProblems()
}
