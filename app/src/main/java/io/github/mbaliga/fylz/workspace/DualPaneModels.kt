package io.github.mbaliga.fylz.workspace

import android.net.Uri

enum class WorkspacePane { PRIMARY, SECONDARY }
enum class PaneLayout { SINGLE, SPLIT_VERTICAL, SPLIT_HORIZONTAL }

data class PaneLocation(
    val treeUri: Uri,
    val folderUri: Uri,
    val displayName: String,
)

data class PaneState(
    val location: PaneLocation? = null,
    val backStack: List<PaneLocation> = emptyList(),
    val forwardStack: List<PaneLocation> = emptyList(),
    val selectedUris: Set<Uri> = emptySet(),
    val searchQuery: String = "",
) {
    init {
        require(backStack.size <= MAX_HISTORY)
        require(forwardStack.size <= MAX_HISTORY)
        require(selectedUris.size <= MAX_SELECTION)
        require(searchQuery.length <= 500)
    }

    companion object {
        const val MAX_HISTORY = 200
        const val MAX_SELECTION = 10_000
    }
}

data class DualPaneWorkspaceState(
    val layout: PaneLayout = PaneLayout.SINGLE,
    val activePane: WorkspacePane = WorkspacePane.PRIMARY,
    val primary: PaneState = PaneState(),
    val secondary: PaneState = PaneState(),
    val dividerFraction: Float = 0.5f,
) {
    init { require(dividerFraction in 0.2f..0.8f) }

    fun pane(value: WorkspacePane): PaneState = if (value == WorkspacePane.PRIMARY) primary else secondary
    fun update(value: WorkspacePane, transform: (PaneState) -> PaneState): DualPaneWorkspaceState = when (value) {
        WorkspacePane.PRIMARY -> copy(primary = transform(primary))
        WorkspacePane.SECONDARY -> copy(secondary = transform(secondary))
    }

    fun activate(value: WorkspacePane) = copy(activePane = value)
    fun togglePane() = activate(if (activePane == WorkspacePane.PRIMARY) WorkspacePane.SECONDARY else WorkspacePane.PRIMARY)

    fun navigate(value: WorkspacePane, destination: PaneLocation): DualPaneWorkspaceState = update(value) { current ->
        val history = current.location?.let { (current.backStack + it).takeLast(PaneState.MAX_HISTORY) }.orEmpty()
        current.copy(location = destination, backStack = history, forwardStack = emptyList(), selectedUris = emptySet())
    }

    fun back(value: WorkspacePane): DualPaneWorkspaceState = update(value) { current ->
        val target = current.backStack.lastOrNull() ?: return@update current
        current.copy(
            location = target,
            backStack = current.backStack.dropLast(1),
            forwardStack = current.location?.let { listOf(it) + current.forwardStack }.orEmpty().take(PaneState.MAX_HISTORY),
            selectedUris = emptySet(),
        )
    }

    fun forward(value: WorkspacePane): DualPaneWorkspaceState = update(value) { current ->
        val target = current.forwardStack.firstOrNull() ?: return@update current
        current.copy(
            location = target,
            backStack = current.location?.let { (current.backStack + it).takeLast(PaneState.MAX_HISTORY) }.orEmpty(),
            forwardStack = current.forwardStack.drop(1),
            selectedUris = emptySet(),
        )
    }
}

enum class PaneTransferOperation { COPY, MOVE }

data class PaneTransferPlan(
    val operation: PaneTransferOperation,
    val sourcePane: WorkspacePane,
    val destinationPane: WorkspacePane,
    val sourceUris: List<Uri>,
    val destinationFolderUri: Uri,
) {
    init {
        require(sourcePane != destinationPane)
        require(sourceUris.isNotEmpty() && sourceUris.size <= PaneState.MAX_SELECTION)
        require(sourceUris.distinct().size == sourceUris.size)
        require(destinationFolderUri !in sourceUris)
    }
}

object PaneTransferPlanner {
    fun plan(state: DualPaneWorkspaceState, operation: PaneTransferOperation): PaneTransferPlan {
        require(state.layout != PaneLayout.SINGLE) { "Dual-pane transfer requires a split layout." }
        val sourcePane = state.activePane
        val destinationPane = if (sourcePane == WorkspacePane.PRIMARY) WorkspacePane.SECONDARY else WorkspacePane.PRIMARY
        val source = state.pane(sourcePane)
        val destination = state.pane(destinationPane)
        val destinationUri = destination.location?.folderUri ?: error("The destination pane has no open folder.")
        return PaneTransferPlan(operation, sourcePane, destinationPane, source.selectedUris.toList(), destinationUri)
    }
}
