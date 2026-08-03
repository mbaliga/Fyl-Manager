package io.github.mbaliga.fylz.workspace

enum class ShortcutKey {
    A, C, F, H, L, N, O, P, R, T, V, W, X, Z,
    DELETE, BACKSPACE, ENTER, ESCAPE, TAB,
    ARROW_LEFT, ARROW_RIGHT, ARROW_UP, ARROW_DOWN,
    SPACE, F2, F5,
}

data class ShortcutGesture(
    val key: ShortcutKey,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
)

enum class WorkspaceCommand {
    SELECT_ALL,
    COPY,
    CUT,
    PASTE,
    FIND,
    FOCUS_LOCATION,
    NEW_FOLDER,
    NEW_FILE,
    OPEN,
    PREVIEW,
    RENAME,
    RECYCLE,
    PERMANENT_DELETE,
    REFRESH,
    CLOSE_TAB,
    NEW_TAB,
    UNDO,
    REDO,
    BACK,
    FORWARD,
    TOGGLE_HIDDEN,
    TOGGLE_SECONDARY_PANE,
    FOCUS_NEXT_PANE,
    COPY_TO_OTHER_PANE,
    MOVE_TO_OTHER_PANE,
    CLEAR_SELECTION,
}

object KeyboardShortcutPolicy {
    fun resolve(gesture: ShortcutGesture): WorkspaceCommand? {
        val command = gesture.ctrl || gesture.meta
        return when {
            command && gesture.key == ShortcutKey.A -> WorkspaceCommand.SELECT_ALL
            command && gesture.key == ShortcutKey.C -> WorkspaceCommand.COPY
            command && gesture.key == ShortcutKey.X -> WorkspaceCommand.CUT
            command && gesture.key == ShortcutKey.V -> WorkspaceCommand.PASTE
            command && gesture.key == ShortcutKey.F -> WorkspaceCommand.FIND
            command && gesture.key == ShortcutKey.L -> WorkspaceCommand.FOCUS_LOCATION
            command && gesture.shift && gesture.key == ShortcutKey.N -> WorkspaceCommand.NEW_FOLDER
            command && gesture.key == ShortcutKey.N -> WorkspaceCommand.NEW_FILE
            command && gesture.key == ShortcutKey.T -> WorkspaceCommand.NEW_TAB
            command && gesture.key == ShortcutKey.W -> WorkspaceCommand.CLOSE_TAB
            command && !gesture.shift && gesture.key == ShortcutKey.Z -> WorkspaceCommand.UNDO
            command && gesture.shift && gesture.key == ShortcutKey.Z -> WorkspaceCommand.REDO
            command && gesture.key == ShortcutKey.H -> WorkspaceCommand.TOGGLE_HIDDEN
            command && gesture.key == ShortcutKey.P -> WorkspaceCommand.TOGGLE_SECONDARY_PANE
            command && gesture.shift && gesture.key == ShortcutKey.C -> WorkspaceCommand.COPY_TO_OTHER_PANE
            command && gesture.shift && gesture.key == ShortcutKey.X -> WorkspaceCommand.MOVE_TO_OTHER_PANE
            gesture.alt && gesture.key == ShortcutKey.ARROW_LEFT -> WorkspaceCommand.BACK
            gesture.alt && gesture.key == ShortcutKey.ARROW_RIGHT -> WorkspaceCommand.FORWARD
            gesture.key == ShortcutKey.F2 -> WorkspaceCommand.RENAME
            gesture.key == ShortcutKey.F5 -> WorkspaceCommand.REFRESH
            gesture.key == ShortcutKey.DELETE && gesture.shift -> WorkspaceCommand.PERMANENT_DELETE
            gesture.key == ShortcutKey.DELETE -> WorkspaceCommand.RECYCLE
            gesture.key == ShortcutKey.ENTER -> WorkspaceCommand.OPEN
            gesture.key == ShortcutKey.SPACE -> WorkspaceCommand.PREVIEW
            gesture.key == ShortcutKey.TAB && command -> WorkspaceCommand.FOCUS_NEXT_PANE
            gesture.key == ShortcutKey.ESCAPE -> WorkspaceCommand.CLEAR_SELECTION
            else -> null
        }
    }

    fun label(command: WorkspaceCommand, platformUsesMeta: Boolean = false): String {
        val modifier = if (platformUsesMeta) "Meta" else "Ctrl"
        return when (command) {
            WorkspaceCommand.SELECT_ALL -> "$modifier+A"
            WorkspaceCommand.COPY -> "$modifier+C"
            WorkspaceCommand.CUT -> "$modifier+X"
            WorkspaceCommand.PASTE -> "$modifier+V"
            WorkspaceCommand.FIND -> "$modifier+F"
            WorkspaceCommand.FOCUS_LOCATION -> "$modifier+L"
            WorkspaceCommand.NEW_FOLDER -> "$modifier+Shift+N"
            WorkspaceCommand.NEW_FILE -> "$modifier+N"
            WorkspaceCommand.NEW_TAB -> "$modifier+T"
            WorkspaceCommand.CLOSE_TAB -> "$modifier+W"
            WorkspaceCommand.UNDO -> "$modifier+Z"
            WorkspaceCommand.REDO -> "$modifier+Shift+Z"
            WorkspaceCommand.TOGGLE_HIDDEN -> "$modifier+H"
            WorkspaceCommand.TOGGLE_SECONDARY_PANE -> "$modifier+P"
            WorkspaceCommand.COPY_TO_OTHER_PANE -> "$modifier+Shift+C"
            WorkspaceCommand.MOVE_TO_OTHER_PANE -> "$modifier+Shift+X"
            WorkspaceCommand.BACK -> "Alt+Left"
            WorkspaceCommand.FORWARD -> "Alt+Right"
            WorkspaceCommand.RENAME -> "F2"
            WorkspaceCommand.REFRESH -> "F5"
            WorkspaceCommand.PERMANENT_DELETE -> "Shift+Delete"
            WorkspaceCommand.RECYCLE -> "Delete"
            WorkspaceCommand.OPEN -> "Enter"
            WorkspaceCommand.PREVIEW -> "Space"
            WorkspaceCommand.FOCUS_NEXT_PANE -> "$modifier+Tab"
            WorkspaceCommand.CLEAR_SELECTION -> "Esc"
        }
    }
}
