package io.github.mbaliga.fylz.actions

import android.net.Uri
import androidx.compose.runtime.Immutable
import io.github.mbaliga.fylz.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.FylzClipboard
import io.github.mbaliga.fylz.model.PreviewMode
import io.github.mbaliga.fylz.model.ThemeMode
import io.github.mbaliga.fylz.model.ViewMode
import io.github.mbaliga.fylz.browse.SortSpec

/**
 * The one snapshot every built-in's `visibleWhen`/`enabledWhen`/`label`/`checked` reads (design
 * §2.3). Computed in `remember(...)` in `FylzV1Workspace`, keyed on its inputs, so it isn't
 * rebuilt on every recomposition.
 */
@Immutable
data class BrowserState(
    val hasActiveTab: Boolean,
    val canNavigateUp: Boolean,
    val entries: List<FileEntry>,
    val visibleEntries: List<FileEntry>,
    val selection: List<FileEntry>,
    val selectionOrder: List<Uri>,
    val focused: FileEntry?,
    val clipboard: FylzClipboard?,
    val sortSpec: SortSpec,
    val viewMode: ViewMode,
    val previewMode: PreviewMode,
    val query: String,
    val searchRecursive: Boolean,
    val themeMode: ThemeMode,
    val currentFolderIsFavourite: Boolean,
    val legacyBinCount: Int,
    val operationsNeedingAttention: Int,
) {
    val selectionCount: Int get() = selection.size
    val selectionKinds: Set<EntryKind> get() = selection.mapTo(HashSet()) { it.kind }
}
