package io.github.mbaliga.fylz.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape itself needs a device to rasterise, but everything that decides how big a bite it
 * takes is pure and is where the mistakes were: a depth of a whole slot ate a third of a short
 * card, a rail sized only against the slot count spanned nearly the whole top edge, and neither
 * had any floor below which cutting a notch stopped making sense at all.
 */
class NotchedCardShapeTest {

    private val slot = QuickLookSlot

    // ── Slot-count clamps ─────────────────────────────────────────────────────────────

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

    // ── Depth: a shelf, never a step ──────────────────────────────────────────────────

    /** Mirrors what [NotchedCardShape.createOutline] computes for the notch's depth. */
    private fun depth(height: Dp, slotSize: Dp = slot): Dp =
        minOf(slotSize * QUICK_LOOK_STRIP_FRACTION, height * QUICK_LOOK_DEPTH_FRACTION)

    @Test
    fun `depth is the glyph strip, not a whole slot`() {
        // 40dp against the 48dp slot: the notch takes the glyphs out of the card and leaves the
        // touch padding hanging over its edge, where it costs the silhouette nothing.
        assertEquals(40f, QuickLookActionStrip.value, 0.001f)
        assertTrue("the strip must be shallower than a slot", QuickLookActionStrip < QuickLookSlot)
        // Tall cards: the strip binds, and the depth stops there no matter how tall the card gets.
        listOf(300.dp, 600.dp, 2000.dp).forEach { h ->
            assertEquals("depth at $h", QuickLookActionStrip.value, depth(h).value, 0.001f)
        }
    }

    @Test
    fun `depth is capped against the card's own height on a short card`() {
        // The bug this replaces: notchDepth = min(slot, height/3) took a THIRD of any card under
        // 144dp tall. The cap is a fixed share of the height instead, so a short card gets a
        // shallower shelf rather than a step.
        listOf(140.dp, 180.dp, 220.dp).forEach { h ->
            val d = depth(h)
            assertTrue("depth $d at $h must never reach a third of the card", d.value < h.value / 3f)
            assertTrue("depth $d at $h must not exceed the strip", d <= QuickLookActionStrip)
            assertEquals("depth at $h", h.value * QUICK_LOOK_DEPTH_FRACTION, d.value, 0.001f)
        }
    }

    // ── Suppression: the documented floor ─────────────────────────────────────────────

    @Test
    fun `the floor is where the caps stop yielding a usable notch`() {
        // Both floors are derived from the caps rather than typed in beside them, and land within
        // a few dp of the 220x140 the design brief asked for.
        assertEquals(213.33f, quickLookMinNotchWidth().value, 0.01f)
        assertEquals(133.33f, quickLookMinNotchHeight().value, 0.01f)
        // Exactly at the floor, the rail's share is exactly the minimum rail and the shelf is
        // exactly half a slot deep. That equality is what the floor MEANS.
        assertEquals(QUICK_LOOK_MIN_SLOTS, quickLookRailSlotsFor(quickLookMinNotchWidth(), QUICK_LOOK_MAX_SLOTS))
        assertEquals((slot / 2f).value, depth(quickLookMinNotchHeight()).value, 0.01f)
    }

    @Test
    fun `notches are suppressed below the floor and kept at or above it`() {
        val w = quickLookMinNotchWidth()
        val h = quickLookMinNotchHeight()
        assertTrue("a card exactly on the floor keeps its notches", quickLookNotchesFit(w, h))
        assertTrue(quickLookNotchesFit(w + 1.dp, h + 1.dp))
        assertFalse("one dp too narrow", quickLookNotchesFit(w - 1.dp, h))
        assertFalse("one dp too short", quickLookNotchesFit(w, h - 1.dp))
    }

    @Test
    fun `the docked mini card gets no notches at all`() {
        // 132x96 (and the 132x84 free-aspect dock) is the size that rendered as a lopsided lump:
        // a third of its width and a third of its height went to the two bites. It is now a plain
        // rounded rect, and QuickLook floats its close glyph over the card instead.
        assertFalse(quickLookNotchesFit(132.dp, 96.dp))
        assertFalse(quickLookNotchesFit(132.dp, 84.dp))
    }

    @Test
    fun `a squat panorama card is below the floor too`() {
        // resolveFullCardSize clamps a 5:1 image on a 411dp viewport to roughly 378x76dp.
        assertFalse(quickLookNotchesFit(378.dp, 76.dp))
    }

    @Test
    fun `the floor scales with the slot so a miniature is judged against its own slot`() {
        // The settings rail editor draws the card at 220x132 with a 34dp slot. Judged against the
        // real card's 48dp floor it would lose its notch -- and the notch is the entire point of
        // that preview -- so the floor is expressed in slots, not in absolute dp.
        val mini = 34.dp
        assertTrue(quickLookNotchesFit(220.dp, 132.dp, mini))
        assertFalse("the same card judged at the full slot is below the floor", quickLookNotchesFit(220.dp, 132.dp))
    }

    // ── Width: capped against the card, quantised down to whole slots ──────────────────

    @Test
    fun `the rail never takes more than its share of the top edge`() {
        // The bug this replaces: railWidth = slot * slots, so a 5-slot rail cut 240dp out of the
        // top edge of a phone-width card and left a thin tab at the corner.
        listOf(220.dp, 300.dp, 360.dp, 411.dp, 700.dp).forEach { width ->
            val rail = quickLookRailWidth(width, QUICK_LOOK_MAX_SLOTS, QUICK_LOOK_CLOSE_MAX_SLOTS)
            assertTrue(
                "rail $rail on a $width card must not exceed ${QUICK_LOOK_MAX_RAIL_FRACTION} of the edge",
                rail.value <= width.value * QUICK_LOOK_MAX_RAIL_FRACTION + 0.01f,
            )
            assertTrue(
                "the top edge must keep a substantial unbroken run on a $width card",
                (width - rail).value >= width.value * 0.5f,
            )
        }
    }

    @Test
    fun `a rail that does not fit loses slots rather than widening the bite`() {
        // 411dp phone card: 45% of the top edge holds three whole slots, so a 5-action rail draws
        // three and the other two belong in the overflow.
        assertEquals(3, quickLookRailSlotsFor(411.dp, 5))
        assertEquals(3, quickLookRailSlotsFor(411.dp, 3))
        assertEquals(2, quickLookRailSlotsFor(411.dp, 2))
        // Whatever the count, the drawn width is always a whole number of slots.
        listOf(220.dp, 300.dp, 411.dp, 700.dp).forEach { width ->
            (QUICK_LOOK_MIN_SLOTS..QUICK_LOOK_MAX_SLOTS).forEach { requested ->
                val drawn = quickLookRailWidth(width, requested, QUICK_LOOK_CLOSE_MIN_SLOTS)
                val slots = quickLookRailSlotsFor(width, requested)
                assertEquals("$requested slots on a $width card", (slot * slots).value, drawn.value, 0.01f)
                assertTrue("a card can never draw MORE slots than asked for", slots <= quickLookSlots(requested))
            }
        }
    }

    @Test
    fun `the close notch is capped against its own share of the bottom edge`() {
        listOf(220.dp, 411.dp, 700.dp).forEach { width ->
            val close = quickLookCloseWidth(width, QUICK_LOOK_CLOSE_MAX_SLOTS)
            assertTrue(
                "close $close on a $width card must not exceed ${QUICK_LOOK_MAX_CLOSE_FRACTION} of the edge",
                close.value <= width.value * QUICK_LOOK_MAX_CLOSE_FRACTION + 0.01f,
            )
        }
    }

    // ── The margin rule: the two notches can never meet ────────────────────────────────

    @Test
    fun `the two notches always leave a bare slot of card between them`() {
        // Without the margin the two inner corners touch and the silhouette reads as a ring rather
        // than as two cuts -- and at the sizes the old code allowed, as an S or a Z.
        val widths = listOf(quickLookMinNotchWidth(), 220.dp, 260.dp, 300.dp, 360.dp, 411.dp, 600.dp, 1000.dp)
        widths.forEach { width ->
            (QUICK_LOOK_MIN_SLOTS..QUICK_LOOK_MAX_SLOTS).forEach { rail ->
                (QUICK_LOOK_CLOSE_MIN_SLOTS..QUICK_LOOK_CLOSE_MAX_SLOTS).forEach { close ->
                    val rw = quickLookRailWidth(width, rail, close)
                    val cw = quickLookCloseWidth(width, close)
                    assertTrue("rail must never be negative (was $rw at $width)", rw.value >= 0f)
                    assertTrue("close must never be negative (was $cw at $width)", cw.value >= 0f)
                    assertTrue(
                        "rail ($rw) + close ($cw) + one slot ($slot) must not exceed $width",
                        rw.value + cw.value + slot.value <= width.value + 0.01f,
                    )
                }
            }
        }
    }

    @Test
    fun `both notches still hold their own minimum at the width floor`() {
        // The margin rule used to be a subtraction that could go negative -- at the docked size it
        // clamped the rail to 36dp and inverted the close rect outright. Above the floor the two
        // edge-share caps guarantee the margin outright, so neither notch is ever starved by it.
        val width = quickLookMinNotchWidth()
        assertEquals(
            (slot * QUICK_LOOK_MIN_SLOTS).value,
            quickLookRailWidth(width, QUICK_LOOK_MAX_SLOTS, QUICK_LOOK_CLOSE_MAX_SLOTS).value,
            0.01f,
        )
        assertEquals(
            (slot * QUICK_LOOK_CLOSE_MIN_SLOTS).value,
            quickLookCloseWidth(width, QUICK_LOOK_CLOSE_MAX_SLOTS).value,
            0.01f,
        )
    }
}
