package io.github.mbaliga.fylz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [resolveEntryTap] and [resolveEntryLongPress] on their own -- the two decisions
 * [entryGestures] actually branches on, pulled out of the pointer plumbing so they are
 * checkable without a composition or a synthetic pointer stream. There is no `androidTest`
 * source set in this repo to exercise the gesture end to end, which is why these two decisions
 * (and the KDoc on [entryGestures] itself) carry the whole contract.
 */
class EntryGesturesTest {

    @Test
    fun `a plain tap with no selection live opens`() {
        assertEquals(
            EntryTapOutcome.OPEN,
            resolveEntryTap(secondTapArrived = false, selectionActive = false),
        )
    }

    @Test
    fun `a plain tap while a selection is active toggles instead of opening`() {
        assertEquals(
            EntryTapOutcome.TOGGLE_SELECTION,
            resolveEntryTap(secondTapArrived = false, selectionActive = true),
        )
    }

    @Test
    fun `a second tap inside the window wins over toggling, even mid-selection`() {
        assertEquals(
            EntryTapOutcome.DOUBLE_TAP,
            resolveEntryTap(secondTapArrived = true, selectionActive = true),
        )
    }

    @Test
    fun `a second tap inside the window wins when there is no selection either`() {
        assertEquals(
            EntryTapOutcome.DOUBLE_TAP,
            resolveEntryTap(secondTapArrived = true, selectionActive = false),
        )
    }

    @Test
    fun `a long press on a selected row with a live cluster starts the cluster drag`() {
        assertEquals(
            EntryLongPressOutcome.CLUSTER_DRAG,
            resolveEntryLongPress(selected = true, clusterAvailable = true),
        )
    }

    @Test
    fun `a long press on an unselected row toggles even with a cluster available`() {
        assertEquals(
            EntryLongPressOutcome.TOGGLE_SELECTION,
            resolveEntryLongPress(selected = false, clusterAvailable = true),
        )
    }

    @Test
    fun `a long press on a selected row with no cluster hooks toggles instead of dragging`() {
        assertEquals(
            EntryLongPressOutcome.TOGGLE_SELECTION,
            resolveEntryLongPress(selected = true, clusterAvailable = false),
        )
    }

    @Test
    fun `a long press with neither selection nor a cluster toggles`() {
        assertEquals(
            EntryLongPressOutcome.TOGGLE_SELECTION,
            resolveEntryLongPress(selected = false, clusterAvailable = false),
        )
    }
}
