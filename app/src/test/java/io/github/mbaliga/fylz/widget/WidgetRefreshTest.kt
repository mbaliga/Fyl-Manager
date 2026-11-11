package io.github.mbaliga.fylz.widget

import io.github.mbaliga.fylz.model.StoreTopic
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetRefreshTest {

    @Test
    fun `a provider redraws only the widgets bound to it, whatever ids an update names`() {
        val placed = intArrayOf(4, 9)
        assertArrayEquals("no ids means every placed widget", intArrayOf(4, 9), WidgetIds.owned(null, placed))
        assertArrayEquals(intArrayOf(9), WidgetIds.owned(intArrayOf(9, 12, 7), placed))
        assertArrayEquals("another provider's widget is ignored", intArrayOf(), WidgetIds.owned(intArrayOf(12), placed))
        assertArrayEquals(intArrayOf(4), WidgetIds.owned(intArrayOf(4, 4), placed))
        assertArrayEquals(intArrayOf(), WidgetIds.owned(intArrayOf(4), intArrayOf()))
    }

    @Test
    fun `every store write redraws the widgets that show its data`() {
        assertEquals(
            setOf(WidgetKind.OPERATIONS, WidgetKind.RECOVERY, WidgetKind.SPACE, WidgetKind.FOLDERS),
            FylzWidgets.kindsFor(StoreTopic.OPERATION_JOURNAL).toSet(),
        )
        assertEquals(setOf(WidgetKind.RECOVERY, WidgetKind.SPACE), FylzWidgets.kindsFor(StoreTopic.RECYCLE_BIN).toSet())
        assertEquals(setOf(WidgetKind.BACKUPS, WidgetKind.OPERATIONS), FylzWidgets.kindsFor(StoreTopic.BACKUPS).toSet())
        assertEquals(setOf(WidgetKind.INDEX), FylzWidgets.kindsFor(StoreTopic.LOCAL_INDEX).toSet())
        // Every widget follows at least one store.
        assertEquals(WidgetKind.entries.toSet(), StoreTopic.entries.flatMap { FylzWidgets.kindsFor(it).toList() }.toSet())
    }
}
