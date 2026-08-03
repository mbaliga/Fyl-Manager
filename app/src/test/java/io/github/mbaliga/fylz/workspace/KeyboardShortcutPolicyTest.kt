package io.github.mbaliga.fylz.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardShortcutPolicyTest {
    @Test
    fun resolvesDesktopShortcuts() {
        assertEquals(
            WorkspaceCommand.COPY_TO_OTHER_PANE,
            KeyboardShortcutPolicy.resolve(ShortcutGesture(ShortcutKey.C, ctrl = true, shift = true)),
        )
        assertEquals(
            WorkspaceCommand.PERMANENT_DELETE,
            KeyboardShortcutPolicy.resolve(ShortcutGesture(ShortcutKey.DELETE, shift = true)),
        )
        assertEquals(
            WorkspaceCommand.BACK,
            KeyboardShortcutPolicy.resolve(ShortcutGesture(ShortcutKey.ARROW_LEFT, alt = true)),
        )
        assertTrue(KeyboardShortcutPolicy.label(WorkspaceCommand.TOGGLE_SECONDARY_PANE).contains("Ctrl"))
    }
}
