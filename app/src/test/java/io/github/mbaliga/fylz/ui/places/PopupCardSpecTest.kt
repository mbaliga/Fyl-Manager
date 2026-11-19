package io.github.mbaliga.fylz.ui.places

import io.github.mbaliga.fylz.ui.places.PopupCardSpec.Seg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupCardSpecTest {

    private val outline = PopupCardSpec.outline()

    /** Walks the outline and returns the pen position after each segment. */
    private fun ends(): List<Pair<Float, Float>> {
        var x = 0f; var y = 0f
        return outline.map { seg ->
            when (seg) {
                is Seg.Move -> { x = seg.x; y = seg.y }
                is Seg.Line -> { x = seg.x; y = seg.y }
                is Seg.Arc -> { x = seg.endX; y = seg.endY }
            }
            x to y
        }
    }

    @Test fun everyPieceStartsWhereThePreviousOneEnded() {
        var penX = 0f; var penY = 0f
        for (seg in outline) {
            when (seg) {
                is Seg.Move -> { penX = seg.x; penY = seg.y }
                is Seg.Line -> { penX = seg.x; penY = seg.y }
                is Seg.Arc -> {
                    assertEquals("arc start x", penX, seg.startX, 0.01f)
                    assertEquals("arc start y", penY, seg.startY, 0.01f)
                    penX = seg.endX; penY = seg.endY
                }
            }
        }
    }

    @Test fun theOutlineClosesOnItsStart() {
        // The last arc ends where the first move began... one corner radius away along the top edge:
        // it is the picture corner right of the top tab, whose end is the start point.
        val start = outline.first() as Seg.Move
        val (endX, endY) = ends().last()
        assertEquals(start.x, endX, 0.01f)
        assertEquals(start.y, endY, 0.01f)
    }

    @Test fun theOutlineStaysInsideTheCard() {
        val side = PopupCardSpec.SIDE
        for (seg in outline) {
            val points = when (seg) {
                is Seg.Move -> listOf(seg.x to seg.y)
                is Seg.Line -> listOf(seg.x to seg.y)
                is Seg.Arc -> listOf(seg.startX to seg.startY, seg.endX to seg.endY)
            }
            for ((x, y) in points) {
                assertTrue("x=$x", x in PopupCardSpec.FRAME - 0.01f..side - PopupCardSpec.FRAME + 0.01f)
                assertTrue("y=$y", y in PopupCardSpec.FRAME - 0.01f..side - PopupCardSpec.FRAME + 0.01f)
            }
        }
    }

    @Test fun theTabsMatchTheMeasuredReference() {
        // Measured: top-left tab 303 x 103, bottom-right tab from (907, 906), frame 2, radii 22 / 24.
        assertEquals(303f, PopupCardSpec.TOP_TAB_W, 0f)
        assertEquals(103f, PopupCardSpec.TOP_TAB_H, 0f)
        assertEquals(907f, PopupCardSpec.BOTTOM_TAB_X, 0f)
        assertEquals(906f, PopupCardSpec.BOTTOM_TAB_Y, 0f)
        assertEquals(2f, PopupCardSpec.FRAME, 0f)
        assertEquals(22f, PopupCardSpec.R_PICTURE, 0f)
        assertEquals(24f, PopupCardSpec.R_TAB, 0f)
    }

    @Test fun theThreeActionsSitInEqualCellsInsideTheTab() {
        assertEquals(48f, PopupCardSpec.actionCenterX(0), 0f)
        assertEquals(144f, PopupCardSpec.actionCenterX(1), 0f)
        assertEquals(240f, PopupCardSpec.actionCenterX(2), 0f)
        // The last cell's right edge is inside the tab, as in the reference.
        assertTrue(PopupCardSpec.ACTION_CELL_W * PopupCardSpec.ACTION_COUNT < PopupCardSpec.TOP_TAB_W)
    }

    @Test fun theCloseMarkSitsInsideTheBottomTab() {
        val half = PopupCardSpec.CLOSE_GLYPH / 2f
        assertTrue(PopupCardSpec.CLOSE_CENTER_X - half > PopupCardSpec.BOTTOM_TAB_X)
        assertTrue(PopupCardSpec.CLOSE_CENTER_Y - half > PopupCardSpec.BOTTOM_TAB_Y)
        assertTrue(PopupCardSpec.CLOSE_CENTER_X + half < PopupCardSpec.SIDE)
        assertTrue(PopupCardSpec.CLOSE_CENTER_Y + half < PopupCardSpec.SIDE)
    }
}
