package io.github.mbaliga.fylz.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardShortcutPolicyTest {
    @Test
    fun resolvesDesktopShortcuts() {
        assertEquals(
            KeyboardCommand.COPY_TO_OTHER_PANE,
            KeyboardShortcutPolicy.resolve(ShortcutGesture(ShortcutKey.C, ctrl = true, shift = true)),
        )
        assertEquals(
            KeyboardCommand.PERMANENT_DELETE,
            KeyboardShortcutPolicy.resolve(ShortcutGesture(ShortcutKey.DELETE, shift = true)),
        )
        assertEquals(
            KeyboardCommand.BACK,
            KeyboardShortcutPolicy.resolve(ShortcutGesture(ShortcutKey.ARROW_LEFT, alt = true)),
        )
        assertTrue(KeyboardShortcutPolicy.label(KeyboardCommand.TOGGLE_SECONDARY_PANE).contains("Ctrl"))
    }
}
