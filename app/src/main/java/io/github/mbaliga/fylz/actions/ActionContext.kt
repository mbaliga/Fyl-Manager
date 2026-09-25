package io.github.mbaliga.fylz.actions

import io.github.mbaliga.fylz.browse.SortField
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.ThemeMode

/**
 * The callbacks a built-in's `run` actually calls -- the same lambdas/functions
 * `FylzV1Workspace`/`FylzAppShell` already own (design §2.4). MC.0a wires a real implementation
 * of this in `FylzV1Workspace` and constructs the registry with it, but nothing dispatches through
 * it yet: the old menus still call their own lambdas directly. A handful of built-ins have no
 * existing external trigger to delegate to at all (the self-contained Recovery-room overlays, the
 * Archive tools menu, the not-yet-built command palette and customisation-problems surface,
 * search-field focus) -- those built-ins' `run` bodies are documented no-ops in
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
}
