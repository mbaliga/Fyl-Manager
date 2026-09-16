package io.github.mbaliga.fylz.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accumulate/threshold/latch decision on its own, no [androidx.compose.ui.input.nestedscroll]
 * or composition involved -- a fixed 100px threshold and the default 50px latch excess (half of
 * it) make every boundary in here a round number.
 */
class PullDownGestureTest {

    private fun gesture(
        thresholdPx: Float = 100f,
        latchExcessFraction: Float = 0.5f,
        rubberBandFactor: Float = 0.55f,
    ) = PullDownGesture(thresholdPx, latchExcessFraction, rubberBandFactor)

    @Test
    fun `starts at rest`() {
        val g = gesture()
        assertEquals(0f, g.pulledPx, 0.001f)
        assertEquals(0f, g.revealFraction, 0.001f)
        assertFalse(g.isLatched)
        assertFalse(g.isDragging)
    }

    @Test
    fun `reveal fraction tracks the drag 1 to 1 up to the threshold`() {
        val g = gesture(thresholdPx = 100f)
        g.onDrag(40f)
        assertEquals(0.4f, g.revealFraction, 0.001f)
        g.onDrag(40f)
        assertEquals(0.8f, g.revealFraction, 0.001f)
        g.onDrag(20f)
        assertEquals(1f, g.revealFraction, 0.001f)
    }

    @Test
    fun `reveal fraction clamps at 1 past the threshold, never overshoots`() {
        val g = gesture(thresholdPx = 100f)
        g.onDrag(500f)
        assertEquals(1f, g.revealFraction, 0.001f)
    }

    @Test
    fun `a retracting drag never runs the accumulator negative`() {
        val g = gesture()
        g.onDrag(10f)
        g.onDrag(-30f)
        assertEquals(0f, g.pulledPx, 0.001f)
        assertEquals(0f, g.revealFraction, 0.001f)
    }

    @Test
    fun `dragging is true only between the first pixel and a resolved release`() {
        val g = gesture()
        assertFalse(g.isDragging)
        g.onDrag(1f)
        assertTrue(g.isDragging)
        g.onDrag(-1f)
        assertFalse("back to 0px, nothing left to retract", g.isDragging)
    }

    @Test
    fun `overpull is zero at and under the threshold`() {
        val g = gesture(thresholdPx = 100f)
        g.onDrag(60f)
        assertEquals(0f, g.overpull, 0.001f)
        g.onDrag(40f) // exactly at the threshold
        assertEquals(0f, g.overpull, 0.001f)
    }

    @Test
    fun `overpull grows past the threshold but stays bounded well under the raw latch excess`() {
        val g = gesture(thresholdPx = 100f, latchExcessFraction = 0.5f, rubberBandFactor = 0.55f)
        val latchExcessPx = 50f // 0.5 * 100
        val ceiling = latchExcessPx * 0.55f // the asymptote overpull approaches but never reaches

        g.onDrag(110f) // 10px of excess
        val small = g.overpull
        assertTrue("overpull must be positive once past the threshold", small > 0f)
        assertTrue("overpull must stay under its ceiling", small < ceiling)

        g.onDrag(10000f) // an enormous further pull
        val huge = g.overpull
        assertTrue("overpull must grow monotonically with more excess", huge > small)
        assertTrue("overpull must still never reach its ceiling", huge < ceiling)
    }

    @Test
    fun `release short of the latch excess springs shut`() {
        val g = gesture(thresholdPx = 100f, latchExcessFraction = 0.5f)
        g.onDrag(149f) // 49px of excess, 1px short of the 50px latch excess
        g.onRelease()
        assertFalse(g.isLatched)
        assertEquals(0f, g.pulledPx, 0.001f)
        assertEquals(0f, g.revealFraction, 0.001f)
    }

    @Test
    fun `release exactly at the latch excess latches open at the rest height`() {
        val g = gesture(thresholdPx = 100f, latchExcessFraction = 0.5f)
        g.onDrag(150f) // exactly 50px of excess
        g.onRelease()
        assertTrue(g.isLatched)
        assertEquals(100f, g.pulledPx, 0.001f)
        assertEquals(1f, g.revealFraction, 0.001f)
        assertEquals(0f, g.overpull, 0.001f)
    }

    @Test
    fun `release well past the latch excess also just latches at rest height, not further open`() {
        val g = gesture(thresholdPx = 100f, latchExcessFraction = 0.5f)
        g.onDrag(500f)
        g.onRelease()
        assertTrue(g.isLatched)
        assertEquals(100f, g.pulledPx, 0.001f)
    }

    @Test
    fun `release with nothing pulled is a no-op`() {
        val g = gesture()
        g.onRelease()
        assertFalse(g.isLatched)
        assertEquals(0f, g.pulledPx, 0.001f)
    }

    @Test
    fun `once latched, further drag input is refused until collapse`() {
        val g = gesture(thresholdPx = 100f, latchExcessFraction = 0.5f)
        g.onDrag(150f)
        g.onRelease()
        check(g.isLatched)

        g.onDrag(-100f)
        assertTrue("a latched field ignores drag, even a retracting one", g.isLatched)
        assertEquals(100f, g.pulledPx, 0.001f)
        assertFalse("latched is not dragging -- only a fresh pull or collapse moves it", g.isDragging)

        g.collapse()
        assertFalse(g.isLatched)
        assertEquals(0f, g.pulledPx, 0.001f)
    }

    @Test
    fun `a second release once already latched changes nothing`() {
        val g = gesture(thresholdPx = 100f, latchExcessFraction = 0.5f)
        g.onDrag(150f)
        g.onRelease()
        g.onRelease()
        assertTrue(g.isLatched)
        assertEquals(100f, g.pulledPx, 0.001f)
    }

    @Test
    fun `collapse resets an unlatched, mid-drag pull too`() {
        val g = gesture()
        g.onDrag(30f)
        g.collapse()
        assertFalse(g.isLatched)
        assertEquals(0f, g.pulledPx, 0.001f)
    }

    @Test
    fun `revealFractionFor and overpullFor read an arbitrary value, independent of the instance's own drag`() {
        val g = gesture(thresholdPx = 100f, latchExcessFraction = 0.5f, rubberBandFactor = 0.55f)
        g.onDrag(20f) // instance sits at 20px -- the functions below must ignore that
        assertEquals(0.5f, g.revealFractionFor(50f), 0.001f)
        assertEquals(1f, g.revealFractionFor(999f), 0.001f)
        assertEquals(0f, g.overpullFor(100f), 0.001f)
        assertTrue(g.overpullFor(200f) > 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-positive threshold is rejected outright`() {
        PullDownGesture(0f)
    }

    @Test
    fun `reveal latches open at the rest height with no drag at all`() {
        val g = gesture(thresholdPx = 100f)
        g.reveal()
        assertTrue(g.isLatched)
        assertEquals(100f, g.pulledPx, 0.001f)
        assertEquals(1f, g.revealFraction, 0.001f)
        assertEquals(0f, g.overpull, 0.001f)
        assertFalse("latched is not dragging", g.isDragging)
    }

    @Test
    fun `reveal is a no-op once already latched`() {
        val g = gesture(thresholdPx = 100f, latchExcessFraction = 0.5f)
        g.onDrag(150f)
        g.onRelease()
        check(g.isLatched)

        g.reveal()
        assertTrue(g.isLatched)
        assertEquals(100f, g.pulledPx, 0.001f)
    }

    @Test
    fun `collapse is the only way back to rest after reveal, same as after a real drag`() {
        val g = gesture(thresholdPx = 100f)
        g.reveal()
        g.collapse()
        assertFalse(g.isLatched)
        assertEquals(0f, g.pulledPx, 0.001f)
    }
}
