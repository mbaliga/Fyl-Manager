package io.github.mbaliga.fylz.actions

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
    fun compress()
    fun extract()
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
