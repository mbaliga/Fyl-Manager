package io.github.mbaliga.fylz.ui.actions

import io.github.mbaliga.fylz.model.DensityMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The detail-level ladder's pure arithmetic: the rung <-> level mapping ([DetailLevelLadder]),
 * and the pinch accumulator that turns continuous gesture motion into discrete steps
 * ([stepPinch]) -- no Robolectric needed, mirroring Fotoz's own `GalleryZoomLadderTest`
 * (`com.fotoxplorr.app.adaptive.GalleryZoomLadderTest`) in spirit, adapted to this ladder's own
 * four rungs and the decision (see `DetailLevelLadder.kt`'s own doc, and REVIEW_QUEUE.md) to keep
 * the accumulator ladder-agnostic rather than folding both concerns into one function the way
 * that file's `ZoomLadder.step` does.
 */
class DetailLevelLadderTest {

    // ---- rung <-> level ----

    @Test
    fun `the ladder climbs from the densest grid through every density to the list view`() {
        val levels = (0 until DetailLevelLadder.rungCount).map { DetailLevelLadder.levelAt(it) }
        assertEquals(
            listOf(
                DetailLevel.Grid(DensityMode.COMPACT),
                DetailLevel.Grid(DensityMode.COMFORTABLE),
                DetailLevel.Grid(DensityMode.DETAILED),
                DetailLevel.ListView,
            ),
            levels,
        )
    }

    @Test
    fun `rungOf and levelAt round-trip for every rung`() {
        for (rung in 0 until DetailLevelLadder.rungCount) {
            assertEquals(rung, DetailLevelLadder.rungOf(DetailLevelLadder.levelAt(rung)))
        }
    }

    @Test
    fun `levelAt clamps rungs outside the ladder to its two closed ends`() {
        assertEquals(DetailLevel.Grid(DensityMode.COMPACT), DetailLevelLadder.levelAt(-5))
        assertEquals(DetailLevel.ListView, DetailLevelLadder.levelAt(999))
    }

    @Test
    fun `stepped moves by delta and clamps at either end`() {
        val start = DetailLevel.Grid(DensityMode.COMFORTABLE)
        assertEquals(DetailLevel.Grid(DensityMode.DETAILED), DetailLevelLadder.stepped(start, 1))
        assertEquals(DetailLevel.ListView, DetailLevelLadder.stepped(start, 2))
        assertEquals(DetailLevel.ListView, DetailLevelLadder.stepped(start, 99))
        assertEquals(DetailLevel.Grid(DensityMode.COMPACT), DetailLevelLadder.stepped(start, -1))
        assertEquals(DetailLevel.Grid(DensityMode.COMPACT), DetailLevelLadder.stepped(start, -99))
    }

    // ---- pinch accumulation: the threshold/step logic itself ----

    @Test
    fun `a small pinch well under the threshold changes nothing and just charges the residual`() {
        val result = stepPinch(residual = 0f, scaleFactor = 1.02f)
        assertEquals(0, result.steps)
        assertTrue("expected a nonzero residual charge, got ${result.residual}", result.residual != 0f)
    }

    @Test
    fun `crossing the threshold registers exactly one step and keeps the leftover, not the whole delta`() {
        // ln(1.30) > PINCH_STEP_THRESHOLD, so this must register exactly one pinch-out step and
        // retain only the part of the motion the one step didn't consume.
        val result = stepPinch(residual = 0f, scaleFactor = 1.30f)
        assertEquals(1, result.steps)
        val expectedResidual = kotlin.math.ln(1.30f) - PINCH_STEP_THRESHOLD
        assertEquals(expectedResidual, result.residual, 1e-4f)
    }

    @Test
    fun `spreading fingers apart (scaleFactor greater than 1) is a positive, pinch-out step`() {
        assertTrue(stepPinch(residual = 0f, scaleFactor = 1.5f).steps > 0)
    }

    @Test
    fun `pinching fingers together (scaleFactor less than 1) is a negative, pinch-in step`() {
        assertTrue(stepPinch(residual = 0f, scaleFactor = 1f / 1.5f).steps < 0)
    }

    @Test
    fun `a single huge pinch can register more than one step in one frame`() {
        val result = stepPinch(residual = 0f, scaleFactor = 0.01f)
        assertTrue("expected several pinch-in steps, got ${result.steps}", result.steps <= -3)
    }

    @Test
    fun `residual never grows past one threshold's worth, so a later small reversal cannot jump several steps`() {
        // Unlike GalleryZoomLadder.step, this accumulator has no ladder-end concept to bank
        // residual against in the first place -- see DetailLevelLadder.kt's own doc for why that
        // is still thrash-free: every call consumes exactly one threshold per step it registers,
        // regardless of how large scaleFactor is or what the caller does with the result.
        val overPinched = stepPinch(residual = 0f, scaleFactor = 0.0001f)
        assertTrue(kotlin.math.abs(overPinched.residual) < PINCH_STEP_THRESHOLD)
        val tinyReversal = stepPinch(overPinched.residual, scaleFactor = 1.02f)
        assertEquals(0, tinyReversal.steps)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-positive scale factor is rejected -- it cannot come from a real gesture`() {
        stepPinch(residual = 0f, scaleFactor = 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-positive threshold is rejected`() {
        stepPinch(residual = 0f, scaleFactor = 1.1f, threshold = 0f)
    }
}
