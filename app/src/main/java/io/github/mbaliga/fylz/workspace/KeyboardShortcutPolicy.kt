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

enum class KeyboardCommand {
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
    fun resolve(gesture: ShortcutGesture): KeyboardCommand? {
        val command = gesture.ctrl || gesture.meta
        return when {
            command && gesture.key == ShortcutKey.A -> KeyboardCommand.SELECT_ALL
            command && gesture.key == ShortcutKey.C -> KeyboardCommand.COPY
            command && gesture.key == ShortcutKey.X -> KeyboardCommand.CUT
            command && gesture.key == ShortcutKey.V -> KeyboardCommand.PASTE
            command && gesture.key == ShortcutKey.F -> KeyboardCommand.FIND
            command && gesture.key == ShortcutKey.L -> KeyboardCommand.FOCUS_LOCATION
            command && gesture.shift && gesture.key == ShortcutKey.N -> KeyboardCommand.NEW_FOLDER
            command && gesture.key == ShortcutKey.N -> KeyboardCommand.NEW_FILE
            command && gesture.key == ShortcutKey.T -> KeyboardCommand.NEW_TAB
            command && gesture.key == ShortcutKey.W -> KeyboardCommand.CLOSE_TAB
            command && !gesture.shift && gesture.key == ShortcutKey.Z -> KeyboardCommand.UNDO
            command && gesture.shift && gesture.key == ShortcutKey.Z -> KeyboardCommand.REDO
            command && gesture.key == ShortcutKey.H -> KeyboardCommand.TOGGLE_HIDDEN
            command && gesture.key == ShortcutKey.P -> KeyboardCommand.TOGGLE_SECONDARY_PANE
            command && gesture.shift && gesture.key == ShortcutKey.C -> KeyboardCommand.COPY_TO_OTHER_PANE
            command && gesture.shift && gesture.key == ShortcutKey.X -> KeyboardCommand.MOVE_TO_OTHER_PANE
            gesture.alt && gesture.key == ShortcutKey.ARROW_LEFT -> KeyboardCommand.BACK
            gesture.alt && gesture.key == ShortcutKey.ARROW_RIGHT -> KeyboardCommand.FORWARD
            gesture.key == ShortcutKey.F2 -> KeyboardCommand.RENAME
            gesture.key == ShortcutKey.F5 -> KeyboardCommand.REFRESH
            gesture.key == ShortcutKey.DELETE && gesture.shift -> KeyboardCommand.PERMANENT_DELETE
            gesture.key == ShortcutKey.DELETE -> KeyboardCommand.RECYCLE
            gesture.key == ShortcutKey.ENTER -> KeyboardCommand.OPEN
            gesture.key == ShortcutKey.SPACE -> KeyboardCommand.PREVIEW
            gesture.key == ShortcutKey.TAB && command -> KeyboardCommand.FOCUS_NEXT_PANE
            gesture.key == ShortcutKey.ESCAPE -> KeyboardCommand.CLEAR_SELECTION
            else -> null
        }
    }

    fun label(command: KeyboardCommand, platformUsesMeta: Boolean = false): String {
        val modifier = if (platformUsesMeta) "Meta" else "Ctrl"
        return when (command) {
            KeyboardCommand.SELECT_ALL -> "$modifier+A"
            KeyboardCommand.COPY -> "$modifier+C"
            KeyboardCommand.CUT -> "$modifier+X"
            KeyboardCommand.PASTE -> "$modifier+V"
            KeyboardCommand.FIND -> "$modifier+F"
            KeyboardCommand.FOCUS_LOCATION -> "$modifier+L"
            KeyboardCommand.NEW_FOLDER -> "$modifier+Shift+N"
            KeyboardCommand.NEW_FILE -> "$modifier+N"
            KeyboardCommand.NEW_TAB -> "$modifier+T"
            KeyboardCommand.CLOSE_TAB -> "$modifier+W"
            KeyboardCommand.UNDO -> "$modifier+Z"
            KeyboardCommand.REDO -> "$modifier+Shift+Z"
            KeyboardCommand.TOGGLE_HIDDEN -> "$modifier+H"
            KeyboardCommand.TOGGLE_SECONDARY_PANE -> "$modifier+P"
            KeyboardCommand.COPY_TO_OTHER_PANE -> "$modifier+Shift+C"
            KeyboardCommand.MOVE_TO_OTHER_PANE -> "$modifier+Shift+X"
            KeyboardCommand.BACK -> "Alt+Left"
            KeyboardCommand.FORWARD -> "Alt+Right"
            KeyboardCommand.RENAME -> "F2"
            KeyboardCommand.REFRESH -> "F5"
            KeyboardCommand.PERMANENT_DELETE -> "Shift+Delete"
            KeyboardCommand.RECYCLE -> "Delete"
            KeyboardCommand.OPEN -> "Enter"
            KeyboardCommand.PREVIEW -> "Space"
            KeyboardCommand.FOCUS_NEXT_PANE -> "$modifier+Tab"
            KeyboardCommand.CLEAR_SELECTION -> "Esc"
        }
    }
}
