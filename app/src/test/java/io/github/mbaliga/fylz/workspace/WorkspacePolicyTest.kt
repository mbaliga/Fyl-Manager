package io.github.mbaliga.fylz.workspace

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspacePolicyTest {
    private fun location(name: String) = PaneLocation(
        treeUri = Uri.parse("content://tree/$name"),
        folderUri = Uri.parse("content://folder/$name"),
        displayName = name,
    )

    @Test
    fun navigationMaintainsBackAndForwardHistory() {
        var state = DualPaneWorkspaceState()
        state = state.navigate(WorkspacePane.PRIMARY, location("one"))
        state = state.navigate(WorkspacePane.PRIMARY, location("two"))
        state = state.back(WorkspacePane.PRIMARY)
        assertEquals("one", state.primary.location?.displayName)
        assertEquals("two", state.primary.forwardStack.first().displayName)
        state = state.forward(WorkspacePane.PRIMARY)
        assertEquals("two", state.primary.location?.displayName)
    }

    @Test
    fun transferUsesOppositePaneDestination() {
        val source = Uri.parse("content://file/model.obj")
        val state = DualPaneWorkspaceState(
            layout = PaneLayout.SPLIT_VERTICAL,
            primary = PaneState(location("source"), selectedUris = setOf(source)),
            secondary = PaneState(location("destination")),
        )
        val plan = PaneTransferPlanner.plan(state, PaneTransferOperation.COPY)
        assertEquals(listOf(source), plan.sourceUris)
        assertEquals(Uri.parse("content://folder/destination"), plan.destinationFolderUri)
    }

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
        assertTrue(KeyboardShortcutPolicy.label(WorkspaceCommand.TOGGLE_SECONDARY_PANE).contains("Ctrl"))
    }
}
