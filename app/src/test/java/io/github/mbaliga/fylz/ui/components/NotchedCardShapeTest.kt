package io.github.mbaliga.fylz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
    fun `close-notch counts clamp into the anchor-dock-close range`() {
        assertEquals(QUICK_LOOK_CLOSE_MIN_SLOTS, quickLookCloseSlots(0))
        assertEquals(QUICK_LOOK_CLOSE_MIN_SLOTS, quickLookCloseSlots(-2))
        assertEquals(2, quickLookCloseSlots(2))
        assertEquals(QUICK_LOOK_CLOSE_MAX_SLOTS, quickLookCloseSlots(4))
        assertEquals(3, QUICK_LOOK_CLOSE_MAX_SLOTS)
    }

    @Test
    fun `shapes compare by geometry so recomposition reuses one rail width`() {
        assertEquals(NotchedCardShape(railSlots = 3, closeSlots = 3), NotchedCardShape(railSlots = 3, closeSlots = 3))
        assertNotEquals(NotchedCardShape(railSlots = 3, closeSlots = 3), NotchedCardShape(railSlots = 4, closeSlots = 3))
        assertNotEquals(NotchedCardShape(railSlots = 3, closeSlots = 1), NotchedCardShape(railSlots = 3, closeSlots = 3))
        assertEquals(
            NotchedCardShape(railSlots = 3, closeSlots = 3).hashCode(),
            NotchedCardShape(railSlots = 3, closeSlots = 3).hashCode(),
        )
    }

    /**
     * Mirrors [NotchedCardShape.createOutline]'s width math: each notch is capped by what the
     * other needs at its own ideal size plus one bare slot of card between them. A card below
     * that combined width would let the two notches' inner corners touch.
     */
    private fun railWidth(width: Float, slot: Float, railSlots: Int, closeSlots: Int): Float {
        val railIdeal = slot * quickLookSlots(railSlots)
        val closeIdeal = slot * quickLookCloseSlots(closeSlots)
        return minOf(railIdeal, width - closeIdeal - slot)
    }

    private fun closeWidth(width: Float, slot: Float, railSlots: Int, closeSlots: Int): Float {
        val railIdeal = slot * quickLookSlots(railSlots)
        val closeIdeal = slot * quickLookCloseSlots(closeSlots)
        return minOf(closeIdeal, width - railIdeal - slot)
    }

    @Test
    fun `a card is never allowed to be narrower than the two notches it must cut`() {
        // The shape refuses to cut a notch wider than what the other notch's own reservation
        // leaves behind, so the card's minimum width has to cover both notches at their ideal
        // size plus one spare slot of card between them -- below this, the icon rows overhang
        // their cuts and actions land on the previewed picture, which is what the card exists to
        // keep them off.
        val slot = QuickLookSlot.value
        (QUICK_LOOK_MIN_SLOTS..QUICK_LOOK_MAX_SLOTS).forEach { rail ->
            (QUICK_LOOK_CLOSE_MIN_SLOTS..QUICK_LOOK_CLOSE_MAX_SLOTS).forEach { close ->
                val minCardWidth = slot * (rail + close + 1)
                assertEquals(
                    "rail of $rail slots must fit its own notch at the minimum card width",
                    slot * rail,
                    railWidth(minCardWidth, slot, rail, close),
                    0.001f,
                )
                assertEquals(
                    "close notch of $close slots must fit its own notch at the minimum card width",
                    slot * close,
                    closeWidth(minCardWidth, slot, rail, close),
                    0.001f,
                )
            }
        }
    }

    @Test
    fun `the two notches never overlap at or above the minimum card width`() {
        val slot = QuickLookSlot.value
        (QUICK_LOOK_MIN_SLOTS..QUICK_LOOK_MAX_SLOTS).forEach { rail ->
            (QUICK_LOOK_CLOSE_MIN_SLOTS..QUICK_LOOK_CLOSE_MAX_SLOTS).forEach { close ->
                val minCardWidth = slot * (rail + close + 1)
                listOf(minCardWidth, minCardWidth * 1.5f, minCardWidth * 3f).forEach { width ->
                    val rw = railWidth(width, slot, rail, close)
                    val cw = closeWidth(width, slot, rail, close)
                    assertTrue(
                        "rail ($rw) + close ($cw) + one slot ($slot) must not exceed width ($width)",
                        rw + cw + slot <= width + 0.001f,
                    )
                }
            }
        }
    }
}
