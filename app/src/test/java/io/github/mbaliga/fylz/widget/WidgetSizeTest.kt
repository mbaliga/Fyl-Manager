package io.github.mbaliga.fylz.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins which layout a launcher shows for common placements.
 *
 * [hostChoice] restates the platform rule (`RemoteViews` sized mapping, API 31+): among the
 * layouts whose ideal size fits the widget, the one nearest to it; if none fits, the smallest.
 * The test checks that Fylz's four ideal sizes produce the intended layout per grid size.
 */
class WidgetSizeTest {

    private fun hostChoice(widthDp: Float, heightDp: Float): WidgetSize {
        val fitting = WidgetSize.entries.filter { it.widthDp <= widthDp && it.heightDp <= heightDp }
        if (fitting.isEmpty()) return WidgetSize.entries.minBy { it.widthDp * it.heightDp }
        return fitting.minBy {
            val dw = widthDp - it.widthDp
            val dh = heightDp - it.heightDp
            dw * dw + dh * dh
        }
    }

    /** The classic cell formula the sizes follow. */
    private fun cells(n: Int): Float = 70f * n - 30f

    @Test
    fun `each grid size gets the intended layout`() {
        assertEquals(WidgetSize.COMPACT, hostChoice(cells(2), cells(1)))
        assertEquals(WidgetSize.COMPACT, hostChoice(cells(4), cells(1)))
        assertEquals(WidgetSize.SMALL, hostChoice(cells(2), cells(2)))
        assertEquals(WidgetSize.SMALL, hostChoice(cells(3), cells(2)))
        assertEquals(WidgetSize.MEDIUM, hostChoice(cells(4), cells(2)))
        assertEquals(WidgetSize.MEDIUM, hostChoice(cells(5), cells(2)))
        assertEquals(WidgetSize.LARGE, hostChoice(cells(4), cells(3)))
        assertEquals(WidgetSize.LARGE, hostChoice(cells(4), cells(4)))
        assertEquals(WidgetSize.SMALL, hostChoice(cells(2), cells(4)))
    }

    @Test
    fun `a placement smaller than every layout falls back to the compact one`() {
        assertEquals(WidgetSize.COMPACT, hostChoice(cells(1), cells(1)))
    }

    @Test
    fun `sizes grow monotonically so a bigger widget never shows less`() {
        val sizes = WidgetSize.entries
        assertEquals(4, sizes.size)
        sizes.zipWithNext().forEach { (a, b) ->
            assertTrue("$b is smaller than $a", b.widthDp >= a.widthDp && b.heightDp >= a.heightDp)
        }
    }

    @Test
    fun `row and pad capacities never shrink as the size grows`() {
        val space = WidgetSize.entries.map { SpaceWidgetModel.capacity(it) }
        val operations = WidgetSize.entries.map { OperationsWidgetModel.capacity(it) }
        val folders = WidgetSize.entries.map { FoldersWidgetModel.capacity(it) }
        // LARGE holds the same two rows as MEDIUM for these, as 48dp two-line rows (WidgetFitTest).
        assertEquals(listOf(1, 1, 2, 2), space)
        assertEquals(listOf(1, 1, 2, 2), operations)
        assertEquals(listOf(1, 2, 4, 6), folders)
    }
}
