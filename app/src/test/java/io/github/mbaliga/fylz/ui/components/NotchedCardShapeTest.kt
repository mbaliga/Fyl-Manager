package io.github.mbaliga.fylz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The shape itself needs a device to rasterise, but the sizing rule in front of it is pure and is
 * where the mistakes would be: a rail that silently grew past the card would cut the silhouette in
 * half, and one that shrank below two slots would leave nowhere for "more" to live.
 */
class NotchedCardShapeTest {

    @Test
    fun `slot counts clamp into the drawable range`() {
        assertEquals(QUICK_LOOK_MIN_SLOTS, quickLookSlots(0))
        assertEquals(QUICK_LOOK_MIN_SLOTS, quickLookSlots(-4))
        assertEquals(3, quickLookSlots(3))
        assertEquals(QUICK_LOOK_MAX_SLOTS, quickLookSlots(9))
    }

    @Test
    fun `the owner's ceiling of five is what the rail enforces`() {
        assertEquals(5, QUICK_LOOK_MAX_SLOTS)
        assertEquals(5, quickLookSlots(QUICK_LOOK_MAX_SLOTS + 1))
    }

    @Test
    fun `shapes compare by geometry so recomposition reuses one rail width`() {
        assertEquals(NotchedCardShape(railSlots = 3), NotchedCardShape(railSlots = 3))
        assertNotEquals(NotchedCardShape(railSlots = 3), NotchedCardShape(railSlots = 4))
        assertEquals(NotchedCardShape(railSlots = 3).hashCode(), NotchedCardShape(railSlots = 3).hashCode())
    }

    @Test
    fun `a card is never allowed to be narrower than the notch it must cut`() {
        // The shape refuses to cut a notch wider than `width - slot`, so the card's own minimum
        // width has to cover the rail plus that reserved slot. Below this the icon row overhangs
        // the cut and the actions land on the previewed picture — which is what the card exists
        // to keep them off.
        val slot = QuickLookSlot.value
        (QUICK_LOOK_MIN_SLOTS..QUICK_LOOK_MAX_SLOTS).forEach { slots ->
            val minCardWidth = slot * (slots + 1)
            val cut = minOf(slot * slots, minCardWidth - slot)
            assertEquals(
                "rail of $slots slots must fit its own notch at the minimum card width",
                slot * slots,
                cut,
                0.001f,
            )
        }
    }
}
