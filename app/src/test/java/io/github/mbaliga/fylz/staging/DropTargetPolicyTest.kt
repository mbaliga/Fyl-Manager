package io.github.mbaliga.fylz.staging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class DropTargetPolicyTest {

    @Test
    fun `proximity rises continuously from the react radius to the slot centre`() {
        val far = DropTargetPolicy.reactionFor(DropTarget.TRASH, 0f, 0f, DropTargetPolicy.REACT_RADIUS + 1, 0f)
        val mid = DropTargetPolicy.reactionFor(DropTarget.TRASH, 0f, 0f, DropTargetPolicy.REACT_RADIUS / 2, 0f)
        val on = DropTargetPolicy.reactionFor(DropTarget.TRASH, 0f, 0f, 0f, 0f)

        assertEquals(0f, far.proximity, 0.0001f)
        assertTrue(mid.proximity > 0.4f && mid.proximity < 0.6f)
        assertEquals(1f, on.proximity, 0.0001f)
    }

    @Test
    fun `a drop counts only inside the hit radius`() {
        val outside = DropTargetPolicy.reactionFor(DropTarget.TRASH, 0f, 0f, DropTargetPolicy.HIT_RADIUS + 1, 0f)
        val inside = DropTargetPolicy.reactionFor(DropTarget.TRASH, 0f, 0f, DropTargetPolicy.HIT_RADIUS - 1, 0f)

        assertFalse(outside.hit)
        assertTrue(inside.hit)
    }

    @Test
    fun `release between two overlapping slots lands on the nearer one`() {
        val nearClipboard = DropTargetPolicy.reactionFor(DropTarget.CLIPBOARD, 100f, 100f, 120f, 100f)
        val fartherMove = DropTargetPolicy.reactionFor(DropTarget.MOVE, 220f, 100f, 120f, 100f)

        assertEquals(DropTarget.CLIPBOARD, DropTargetPolicy.dropFor(listOf(fartherMove, nearClipboard)))
    }

    @Test
    fun `release over nothing drops nowhere`() {
        val distant = DropTargetPolicy.reactionFor(DropTarget.TRASH, 0f, 0f, 9_999f, 9_999f)
        assertEquals(DropTarget.NONE, DropTargetPolicy.dropFor(listOf(distant)))
        assertEquals(DropTarget.NONE, DropTargetPolicy.dropFor(emptyList()))
    }

    @Test
    fun `the corners keep destructive and constructive targets apart`() {
        // The convention the composables draw from: actions hug the top-left origin, trash
        // hugs the bottom-right — opposite corners, so a sloppy drop can never cross families.
        val actions = DropTargetPolicy.actionSlotCentres(radiusPx = 260f)
        val trash = DropTargetPolicy.trashCentre(widthPx = 1080f, heightPx = 2340f, insetPx = 120f)

        assertEquals(DropTargetPolicy.actionSlots.size, actions.size)
        actions.forEach { (x, y) ->
            assertTrue(x < 1080f / 2 && y < 2340f / 2)
        }
        assertTrue(trash.first > 1080f / 2 && trash.second > 2340f / 2)
    }

    @Test
    fun `every action slot sits on the arc radius`() {
        // The whole reason the bulge can be small: no slot is further from the corner than the
        // radius, so the blob only has to be radius-plus-a-glyph across.
        val radius = 260f
        DropTargetPolicy.actionSlotCentres(radius).forEach { (x, y) ->
            assertEquals(radius, hypot(x, y), 0.01f)
        }
    }

    @Test
    fun `action slots march around the arc without colliding`() {
        val centres = DropTargetPolicy.actionSlotCentres(radiusPx = 260f)
        // Ordered: reading from the top edge down to the side edge.
        centres.zipWithNext().forEach { (first, second) ->
            assertTrue(second.second > first.second)
            assertTrue(second.first < first.first)
        }
        // And far enough apart that adjacent hit circles cannot swallow each other's centre.
        centres.zipWithNext().forEach { (first, second) ->
            assertTrue(hypot(second.first - first.first, second.second - first.second) > 40f)
        }
    }

    @Test
    fun `the arc scales with its radius rather than reshaping`() {
        val small = DropTargetPolicy.actionSlotCentres(radiusPx = 100f)
        val large = DropTargetPolicy.actionSlotCentres(radiusPx = 300f)
        small.zip(large).forEach { (near, far) ->
            assertEquals(near.first * 3f, far.first, 0.01f)
            assertEquals(near.second * 3f, far.second, 0.01f)
        }
    }
}
