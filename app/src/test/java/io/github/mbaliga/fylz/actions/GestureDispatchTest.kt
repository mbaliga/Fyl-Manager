package io.github.mbaliga.fylz.actions

import android.net.Uri
import io.github.mbaliga.fylz.browse.SortField
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Design §2.6/§2.5, MC.0e: [ActionDispatcher.gesture] finds the binding whose `Placement.Gesture`
 * matches both the gesture id and the target's `targetWhen` (or none, for a targetless gesture
 * like `SHAKE`), gated by `visibleWhen`/`enabledWhen` exactly like [ActionDispatcher.run].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GestureDispatchTest {

    private val registry = ActionRegistry(BuiltInActions.all())
    private val dispatcher = ActionDispatcher(registry)

    @Test
    fun `double tap on a directory opens it`() {
        val ctx = RecordingActionContext()
        val state = BrowserStateFixtures.oneDirectory()
        val dir = state.entries.single()
        dispatcher.gesture(GestureId.ITEM_DOUBLE_TAP, ActionTarget.Entry(dir), state, ctx)
        assertEquals(dir, ctx.openedEntry)
        assertNull(ctx.openedWithEntry)
    }

    @Test
    fun `double tap on a file opens it with an external app`() {
        val ctx = RecordingActionContext()
        val state = BrowserStateFixtures.oneFile()
        val file = state.entries.single()
        dispatcher.gesture(GestureId.ITEM_DOUBLE_TAP, ActionTarget.Entry(file), state, ctx)
        assertEquals(file, ctx.openedWithEntry)
        assertNull(ctx.openedEntry)
    }

    @Test
    fun `long press toggles selection`() {
        val ctx = RecordingActionContext()
        val state = BrowserStateFixtures.oneFile()
        val file = state.entries.single()
        dispatcher.gesture(GestureId.ITEM_LONG_PRESS, ActionTarget.Entry(file), state, ctx)
        assertEquals(file, ctx.toggledEntry)
    }

    @Test
    fun `tap opens regardless of entry kind`() {
        val ctx = RecordingActionContext()
        val state = BrowserStateFixtures.oneDirectory()
        val dir = state.entries.single()
        dispatcher.gesture(GestureId.ITEM_TAP, ActionTarget.Entry(dir), state, ctx)
        assertEquals(dir, ctx.openedEntry)
    }

    @Test
    fun `shake refreshes`() {
        val ctx = RecordingActionContext()
        dispatcher.gesture(GestureId.SHAKE, null, BrowserStateFixtures.emptyFolder(), ctx)
        assertTrue(ctx.refreshed)
    }

    @Test
    fun `a hidden binding does not fire`() {
        val hidden = BuiltInBinding(
            def = ActionDef(
                id = ActionId.parse("fylz.test.hidden-gesture"),
                titleKey = "Hidden",
                icon = IconRef.Builtin("Close"),
                placements = listOf(Placement.Gesture(GestureId.ITEM_TAP)),
                confirm = ConfirmPolicy.None,
                body = ActionBody.BuiltIn("fylz.test.hidden-gesture"),
                origin = Origin.BuiltIn,
            ),
            visibleWhen = { false },
            enabledWhen = { true },
            run = { _, _, _ -> fail("a hidden binding must not run") },
        )
        val isolated = ActionDispatcher(ActionRegistry(listOf(hidden)))
        isolated.gesture(GestureId.ITEM_TAP, ActionTarget.Entry(BrowserStateFixtures.oneFile().entries.single()), BrowserStateFixtures.oneFile(), RecordingActionContext())
    }

    @Test
    fun `a disabled binding does not fire`() {
        val disabled = BuiltInBinding(
            def = ActionDef(
                id = ActionId.parse("fylz.test.disabled-gesture"),
                titleKey = "Disabled",
                icon = IconRef.Builtin("Close"),
                placements = listOf(Placement.Gesture(GestureId.SHAKE)),
                confirm = ConfirmPolicy.None,
                body = ActionBody.BuiltIn("fylz.test.disabled-gesture"),
                origin = Origin.BuiltIn,
            ),
            visibleWhen = { true },
            enabledWhen = { false },
            run = { _, _, _ -> fail("a disabled binding must not run") },
        )
        val isolated = ActionDispatcher(ActionRegistry(listOf(disabled)))
        isolated.gesture(GestureId.SHAKE, null, BrowserStateFixtures.emptyFolder(), RecordingActionContext())
    }
}

/** A no-op [ActionContext] that records the handful of calls this test cares about. */
private class RecordingActionContext : ActionContext {
    var openedEntry: FileEntry? = null
    var openedWithEntry: FileEntry? = null
    var toggledEntry: FileEntry? = null
    var refreshed = false

    override fun cut() {}
    override fun copy() {}
    override fun copyTo() {}
    override fun moveTo() {}
    override fun recycleSelection() {}
    override fun rename() {}
    override fun tags() {}
    override fun compress() {}
    override fun extract() {}
    override fun extractHere() {}
    override fun extractIntoFolder() {}
    override fun extractTo() {}
    override fun extractSelected() {}
    override fun openExtractMenu(archive: Uri) {}
    override fun batchRename() {}
    override fun pdfTools() {}
    override fun share() {}
    override fun clearSelection() {}

    override fun paste() {}
    override fun clearClipboard() {}
    override fun toggleViewMode() {}
    override fun refresh() { refreshed = true }

    override fun newFolder() {}
    override fun newFile() {}
    override fun scanToPdf() {}
    override fun findDuplicates() {}
    override fun aiOrganize() {}

    override fun navigateUp() {}
    override fun selectAll() {}

    override fun setSortField(field: SortField) {}
    override fun toggleFoldersFirst() {}

    override fun open(entry: FileEntry) { openedEntry = entry }
    override fun openWith(entry: FileEntry) { openedWithEntry = entry }
    override fun toggleSelected(entry: FileEntry) { toggledEntry = entry }

    override fun addTab() {}
    override fun closeTab(tabId: String) {}

    override fun openRoot() {}
    override fun toggleFavourite() {}
    override fun openRecycleBin() {}

    override fun openRemotes() {}
    override fun openWebDavQuick() {}
    override fun openToolsActivity() {}
    override fun openIndexActivity() {}
    override fun setThemeMode(mode: ThemeMode) {}

    override fun showOperationHistory() {}

    override fun openRoom(room: RoomId) {}

    override fun openCommandPalette() {}

    override fun showRegistryProblems() {}
}
