package io.github.mbaliga.fylz.browse

import io.github.mbaliga.fylz.model.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserPoliciesTest {

    private fun act(g: ItemGesture, selecting: Boolean, tapToPreview: Boolean, dir: Boolean = false) =
        TapPolicy.actionFor(g, selecting, tapToPreview, dir)

    @Test fun defaultTapOpensAndLongPressPeeks() {
        assertEquals(ItemAction.OPEN, act(ItemGesture.TAP, selecting = false, tapToPreview = false))
        assertEquals(ItemAction.QUICK_PREVIEW, act(ItemGesture.LONG_PRESS, selecting = false, tapToPreview = false))
    }

    @Test fun tapToPreviewSwapsTheRolesAndLongPressAlsoSelects() {
        assertEquals(ItemAction.QUICK_PREVIEW, act(ItemGesture.TAP, false, true))
        assertEquals(ItemAction.PREVIEW_AND_SELECT, act(ItemGesture.LONG_PRESS, false, true))
    }

    @Test fun whileSelectingEveryTapTogglesAndLongPressPeeks() {
        for (tapToPreview in listOf(false, true)) {
            assertEquals(ItemAction.TOGGLE_SELECT, act(ItemGesture.TAP, true, tapToPreview))
            assertEquals(ItemAction.QUICK_PREVIEW, act(ItemGesture.LONG_PRESS, true, tapToPreview))
        }
    }

    @Test fun foldersHaveNothingToPreview() {
        for (tapToPreview in listOf(false, true)) {
            assertEquals(ItemAction.OPEN, act(ItemGesture.TAP, false, tapToPreview, dir = true))
            assertEquals(ItemAction.BEGIN_SELECTION, act(ItemGesture.LONG_PRESS, false, tapToPreview, dir = true))
            assertEquals(ItemAction.TOGGLE_SELECT, act(ItemGesture.TAP, true, tapToPreview, dir = true))
            assertEquals(ItemAction.TOGGLE_SELECT, act(ItemGesture.LONG_PRESS, true, tapToPreview, dir = true))
        }
    }

    @Test fun folderViewRoundTrips() {
        val view = FolderView(ViewMode.GRID, SortSpec(SortField.MODIFIED, SortDirection.DESCENDING, foldersFirst = false))
        assertEquals(view, FolderViewCodec.decode(FolderViewCodec.encode(view)))
        assertEquals(FolderView(), FolderViewCodec.decode(FolderViewCodec.encode(FolderView())))
    }

    @Test fun unreadableViewsDecodeToNullNotACrash() {
        assertNull(FolderViewCodec.decode(null))
        assertNull(FolderViewCodec.decode(""))
        assertNull(FolderViewCodec.decode("GRID|NAME|ASCENDING"))
        assertNull(FolderViewCodec.decode("TILES|NAME|ASCENDING|1"))
        assertNull(FolderViewCodec.decode("GRID|COLOUR|ASCENDING|1"))
        assertNull(FolderViewCodec.decode("GRID|NAME|SIDEWAYS|1"))
        assertNull(FolderViewCodec.decode("GRID|NAME|ASCENDING|maybe"))
    }

    @Test fun perFolderUsesTheFoldersOwnViewElseTheGlobalOne() {
        val global = FolderView(ViewMode.LIST)
        val own = FolderView(ViewMode.GRID)
        assertEquals(own, FolderViewPolicy.resolve(perFolder = true, saved = own, global = global))
        assertEquals(global, FolderViewPolicy.resolve(perFolder = true, saved = null, global = global))
    }

    @Test fun globalModeIgnoresWhatFoldersSaved() {
        val global = FolderView(ViewMode.LIST)
        assertEquals(global, FolderViewPolicy.resolve(perFolder = false, saved = FolderView(ViewMode.GRID), global = global))
    }
}
