package io.github.mbaliga.fylz.ui.components

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Pure sizing math for the full and docked cards. The shape and its notches are gone, but the
 * card still has to hold the content's own aspect exactly, and the width it hands back has to be
 * a value the caller's `Modifier.width()` can honour outright -- a size that fits the aspect
 * arithmetic but overflows the viewport is what silently letterboxed a panorama before, since
 * `Modifier.width()` coerces the overflow away without touching height.
 */
class QuickLookSizingTest {

    private val viewportW = 411.dp
    private val viewportH = 850.dp
    private val epsilon = 0.01f

    private fun aspectOf(size: DpSize): Float = size.width.value / size.height.value

    private fun assertFitsViewport(size: DpSize) {
        assertTrue("width ${size.width} must not exceed the viewport ($viewportW)", size.width.value <= viewportW.value + 0.5f)
        assertTrue("height ${size.height} must not exceed the viewport ($viewportH)", size.height.value <= viewportH.value + 0.5f)
    }

    @Test
    fun `no aspect falls back to the free two-axis box, unclamped`() {
        val size = resolveFullCardSize(0.8f, 0.5f, null, viewportW, viewportH)
        assertEquals(viewportW.value * 0.8f, size.width.value, 0.001f)
        assertEquals(viewportH.value * 0.5f, size.height.value, 0.001f)
    }

    @Test
    fun `a panorama holds its aspect without asking for a wider-than-viewport card`() {
        listOf(3f, 4f).forEach { aspect ->
            val size = resolveFullCardSize(1f, 1f, aspect, viewportW, viewportH)
            assertFitsViewport(size)
            assertEquals("aspect must hold for $aspect", aspect, aspectOf(size), epsilon)
        }
    }

    @Test
    fun `a portrait holds its aspect without asking for a taller-than-viewport card`() {
        listOf(0.45f, 0.30f).forEach { aspect ->
            val size = resolveFullCardSize(1f, 1f, aspect, viewportW, viewportH)
            assertFitsViewport(size)
            assertEquals("aspect must hold for $aspect", aspect, aspectOf(size), epsilon)
        }
    }

    @Test
    fun `square holds its aspect inside the viewport`() {
        val size = resolveFullCardSize(1f, 1f, 1f, viewportW, viewportH)
        assertFitsViewport(size)
        assertEquals(1f, aspectOf(size), epsilon)
    }

    @Test
    fun `a narrow width request still holds aspect once one is reported`() {
        // A card the user left narrow before the aspect was known must not silently drop the
        // aspect lock once RichImagePreview reports one -- the width shrinks, not the aspect.
        val size = resolveFullCardSize(0.4f, 1f, 0.7f, viewportW, viewportH)
        assertFitsViewport(size)
        assertEquals(0.7f, aspectOf(size), epsilon)
    }

    @Test
    fun `docked with no aspect keeps the free-content default`() {
        val size = resolveDockedSize(null)
        assertEquals(132f, size.width.value, 0.001f)
        assertEquals(84f, size.height.value, 0.001f)
    }

    @Test
    fun `docked fits inside 132x96 preserving aspect instead of pinning the width`() {
        listOf(0.30f, 1f, 1.375f, 2f, 3f).forEach { aspect ->
            val size = resolveDockedSize(aspect)
            assertTrue("docked width ${size.width} must not exceed 132dp", size.width.value <= 132f + 0.01f)
            assertTrue("docked height ${size.height} must not exceed 96dp", size.height.value <= 96f + 0.01f)
            assertEquals("aspect must hold for $aspect", aspect, aspectOf(size), epsilon)
        }
    }

    @Test
    fun `a narrow docked portrait docks tall, not letterboxed`() {
        val size = resolveDockedSize(0.30f)
        assertEquals("height should pin to the 96dp ceiling", 96f, size.height.value, 0.001f)
        assertTrue("width should be far narrower than the 132dp slot", size.width.value < 132f)
    }

    @Test
    fun `a wide docked landscape docks short, not letterboxed`() {
        val size = resolveDockedSize(3f)
        assertEquals("width should pin to the 132dp slot", 132f, size.width.value, 0.001f)
        assertTrue("height should be shorter than the 96dp ceiling", size.height.value < 96f)
        assertTrue(abs(aspectOf(size) - 3f) < epsilon)
    }

    // ── What the floating chrome can show at a given card size ────────────────────────

    private val fourPinned = listOf(QuickAction.OPEN_WITH, QuickAction.SHARE, QuickAction.COPY, QuickAction.MOVE)

    /** Every chrome cell is a 48dp [QuickLookSlot], plus the 10dp inset each pill floats in. */
    private fun pillWidth(cells: Int): Float = QuickLookSlot.value * cells + 20f

    @Test
    fun `a roomy card shows both pills and the whole pinned rail`() {
        val plan = previewChromePlan(370.dp, 600.dp, fourPinned)
        assertEquals(fourPinned, plan.rail)
        assertFalse(plan.singlePill)
        assertTrue(plan.showAnchor)
        assertTrue(plan.showDock)
        assertEquals("anchor, dock, close", 3, plan.cornerCells)
    }

    @Test
    fun `a rail that outruns the card drops cells rather than overhanging it`() {
        // 220dp of card holds four chrome cells inside the pill's insets, and one of those four is
        // always "more" -- so three of the four pinned actions show and the fourth goes to the
        // overflow. It does NOT run off the card's edge, which is what a fixed-width pill did.
        val plan = previewChromePlan(220.dp, 600.dp, fourPinned)
        assertEquals(fourPinned.take(3), plan.rail)
        assertTrue("the pill must fit across the card", pillWidth(plan.rail.size + 1) <= 220f)
    }

    @Test
    fun `an action dropped from the rail is still reachable in the overflow`() {
        listOf(220.dp, 260.dp, 370.dp).forEach { width ->
            val plan = previewChromePlan(width, 600.dp, fourPinned)
            val reachable = plan.rail + QuickAction.overflowFor(plan.rail)
            assertEquals(
                "no action may be stranded at $width",
                QuickAction.entries.toSet(),
                reachable.toSet(),
            )
        }
    }

    @Test
    fun `a card below the notch floor collapses to one pill`() {
        // Same predicate the carved shape uses to decide it has no room for a bite: two 48dp pills
        // at opposite corners of a 378x76dp panorama would stack on each other with the picture
        // nowhere in between.
        val plan = previewChromePlan(378.dp, 76.dp, fourPinned)
        assertTrue(plan.singlePill)
        assertEquals("every pinned action falls through to the overflow", emptyList<QuickAction>(), plan.rail)
        assertEquals(QuickAction.entries.toSet(), QuickAction.overflowFor(plan.rail).toSet())
        assertEquals("more, anchor, dock, close", 4, plan.cornerCells)
        assertTrue(pillWidth(plan.cornerCells) <= 378f)
    }

    @Test
    fun `even the collapsed pill is measured, and keeps more and close whatever happens`() {
        // A 1:5 portrait resolves to roughly 145x722dp -- not four cells wide. Anchor and Dock are
        // mode toggles and give way; "more" (the only route to any file action here) and "close"
        // (the only exit that does not depend on the scrim or the back gesture) never do.
        val plan = previewChromePlan(145.dp, 722.dp, fourPinned)
        assertTrue(plan.singlePill)
        assertFalse(plan.showAnchor)
        assertFalse(plan.showDock)
        assertEquals("more and close", 2, plan.cornerCells)
        assertTrue("the collapsed pill must still fit across the card", pillWidth(plan.cornerCells) <= 145f)
    }

    @Test
    fun `the corner pill always fits across the card it floats on`() {
        listOf(145.dp, 212.dp, 220.dp, 260.dp, 370.dp, 411.dp).forEach { width ->
            listOf(76.dp, 300.dp, 722.dp).forEach { height ->
                val plan = previewChromePlan(width, height, fourPinned)
                assertTrue(
                    "corner pill of ${plan.cornerCells} cells must fit a ${width}x$height card",
                    pillWidth(plan.cornerCells) <= width.value + 0.01f,
                )
                assertTrue(
                    "rail pill of ${plan.rail.size + 1} cells must fit a ${width}x$height card",
                    plan.singlePill || pillWidth(plan.rail.size + 1) <= width.value + 0.01f,
                )
            }
        }
    }
}
