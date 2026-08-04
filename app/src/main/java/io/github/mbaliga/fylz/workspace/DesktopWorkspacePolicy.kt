package io.github.mbaliga.fylz.workspace

import android.net.Uri

enum class WorkspacePane { PRIMARY, SECONDARY }
enum class TransferDirection { PRIMARY_TO_SECONDARY, SECONDARY_TO_PRIMARY }

data class PaneState(
    val rootUri: Uri? = null,
    val currentUri: Uri? = null,
    val selectedUris: Set<Uri> = emptySet(),
)

data class DualPaneState(
    val enabled: Boolean = false,
    val focusedPane: WorkspacePane = WorkspacePane.PRIMARY,
    val primary: PaneState = PaneState(),
    val secondary: PaneState = PaneState(),
) {
    fun pane(value: WorkspacePane): PaneState = if (value == WorkspacePane.PRIMARY) primary else secondary
    fun focused(): PaneState = pane(focusedPane)
    fun other(): PaneState = pane(if (focusedPane == WorkspacePane.PRIMARY) WorkspacePane.SECONDARY else WorkspacePane.PRIMARY)
}

enum class WorkspaceCommand {
    NEW_FILE,
    NEW_FOLDER,
    OPEN,
    RENAME,
    COPY,
    CUT,
    PASTE,
    MOVE_TO_OTHER_PANE,
    COPY_TO_OTHER_PANE,
    RECYCLE,
    PERMANENT_DELETE,
    SELECT_ALL,
    CLEAR_SELECTION,
    FOCUS_SEARCH,
    REFRESH,
    TOGGLE_PREVIEW,
    TOGGLE_DUAL_PANE,
    FOCUS_PRIMARY,
    FOCUS_SECONDARY,
    GO_PARENT,
    OPEN_OPERATION_HISTORY,
}

data class KeyStroke(
    val key: String,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val meta: Boolean = false,
)

object DesktopWorkspacePolicy {
    val defaultShortcuts: Map<KeyStroke, WorkspaceCommand> = mapOf(
        KeyStroke("n", ctrl = true) to WorkspaceCommand.NEW_FILE,
        KeyStroke("n", ctrl = true, shift = true) to WorkspaceCommand.NEW_FOLDER,
        KeyStroke("enter") to WorkspaceCommand.OPEN,
        KeyStroke("f2") to WorkspaceCommand.RENAME,
        KeyStroke("c", ctrl = true) to WorkspaceCommand.COPY,
        KeyStroke("x", ctrl = true) to WorkspaceCommand.CUT,
        KeyStroke("v", ctrl = true) to WorkspaceCommand.PASTE,
        KeyStroke("c", ctrl = true, shift = true) to WorkspaceCommand.COPY_TO_OTHER_PANE,
        KeyStroke("m", ctrl = true, shift = true) to WorkspaceCommand.MOVE_TO_OTHER_PANE,
        KeyStroke("delete") to WorkspaceCommand.RECYCLE,
        KeyStroke("delete", shift = true) to WorkspaceCommand.PERMANENT_DELETE,
        KeyStroke("a", ctrl = true) to WorkspaceCommand.SELECT_ALL,
        KeyStroke("escape") to WorkspaceCommand.CLEAR_SELECTION,
        KeyStroke("f", ctrl = true) to WorkspaceCommand.FOCUS_SEARCH,
        KeyStroke("r", ctrl = true) to WorkspaceCommand.REFRESH,
        KeyStroke("p", ctrl = true, shift = true) to WorkspaceCommand.TOGGLE_PREVIEW,
        KeyStroke("d", ctrl = true, shift = true) to WorkspaceCommand.TOGGLE_DUAL_PANE,
        KeyStroke("1", ctrl = true) to WorkspaceCommand.FOCUS_PRIMARY,
        KeyStroke("2", ctrl = true) to WorkspaceCommand.FOCUS_SECONDARY,
        KeyStroke("backspace", alt = true) to WorkspaceCommand.GO_PARENT,
        KeyStroke("h", ctrl = true, shift = true) to WorkspaceCommand.OPEN_OPERATION_HISTORY,
    )

    fun commandFor(stroke: KeyStroke, overrides: Map<KeyStroke, WorkspaceCommand> = emptyMap()): WorkspaceCommand? =
        overrides[normalize(stroke)] ?: defaultShortcuts[normalize(stroke)]

    fun transferDirection(state: DualPaneState): TransferDirection = when (state.focusedPane) {
        WorkspacePane.PRIMARY -> TransferDirection.PRIMARY_TO_SECONDARY
        WorkspacePane.SECONDARY -> TransferDirection.SECONDARY_TO_PRIMARY
    }

    fun validateTransfer(state: DualPaneState): String? {
        if (!state.enabled) return "Dual-pane mode is not enabled."
        if (state.focused().selectedUris.isEmpty()) return "Select at least one source item."
        if (state.other().currentUri == null) return "Open a destination folder in the other pane."
        if (state.focused().currentUri == state.other().currentUri) return "Source and destination panes show the same folder."
        return null
    }

    private fun normalize(value: KeyStroke): KeyStroke = value.copy(key = value.key.trim().lowercase())
}
