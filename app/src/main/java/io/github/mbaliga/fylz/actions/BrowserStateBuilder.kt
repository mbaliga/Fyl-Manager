package io.github.mbaliga.fylz.actions

import android.net.Uri
import io.github.mbaliga.fylz.browse.SortSpec
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.FolderTab
import io.github.mbaliga.fylz.model.FylzClipboard
import io.github.mbaliga.fylz.model.PreviewMode
import io.github.mbaliga.fylz.model.ThemeMode
import io.github.mbaliga.fylz.model.ViewMode

/**
 * Everything [buildBrowserState] derives a [BrowserState] from, named (`docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md`
 * section 2.8): about eighteen inputs, several of the same type, so positional arguments would be
 * a bug farm. `FylzV1Workspace` fills one of these inside its `remember(...)` keyed on the same
 * inputs, which is what keeps the state from being rebuilt on every recomposition.
 */
data class BrowserStateInputs(
    val activeTab: FolderTab?,
    val activeTabId: String?,
    val entries: List<FileEntry>,
    val visibleEntries: List<FileEntry>,
    val selection: List<FileEntry>,
    /** The selected Uris in selection order (`selectedUris`, a `LinkedHashSet` at runtime). */
    val selectionOrder: Collection<Uri>,
    val focused: FileEntry?,
    val clipboard: FylzClipboard?,
    val sortSpec: SortSpec,
    val viewMode: ViewMode,
    val previewMode: PreviewMode,
    val query: String,
    val searchRecursive: Boolean,
    val themeMode: ThemeMode,
    /** The favourite folders' Uris, for `currentFolderIsFavourite`. */
    val favouriteUris: Collection<Uri>,
    val legacyBinCount: Int,
    val operationsNeedingAttention: Int,
    val registryProblemCount: Int,
    // M3.6: whether the archive location being browsed (if any) is a ZIP-family archive.
    val isZipFamilyArchiveLocation: Boolean = false,
)

/** The one place a [BrowserState] is assembled from the browser's raw state (moved out of `FylzV1App.kt`, M3.3c). */
fun buildBrowserState(inputs: BrowserStateInputs): BrowserState {
    val current = inputs.activeTab?.current?.uri
    return BrowserState(
        hasActiveTab = inputs.activeTab != null,
        canNavigateUp = (inputs.activeTab?.locations?.size ?: 0) > 1,
        entries = inputs.entries,
        visibleEntries = inputs.visibleEntries,
        selection = inputs.selection,
        selectionOrder = inputs.selectionOrder.toList(),
        focused = inputs.focused,
        clipboard = inputs.clipboard,
        sortSpec = inputs.sortSpec,
        viewMode = inputs.viewMode,
        previewMode = inputs.previewMode,
        query = inputs.query,
        searchRecursive = inputs.searchRecursive,
        themeMode = inputs.themeMode,
        currentFolderIsFavourite = current != null && current in inputs.favouriteUris,
        legacyBinCount = inputs.legacyBinCount,
        operationsNeedingAttention = inputs.operationsNeedingAttention,
        activeTabId = inputs.activeTabId,
        registryProblemCount = inputs.registryProblemCount,
        locationKind = LocationKind.of(current),
        isZipFamilyArchiveLocation = inputs.isZipFamilyArchiveLocation,
    )
}
