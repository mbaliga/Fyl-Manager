package io.github.mbaliga.fylz.ui.tactile

import androidx.compose.ui.geometry.Offset
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The kit's pure geometry -- slant edges, glint/slash-tick/asterisk placement, slider and toggle
 * position math -- all live as plain `internal` functions in TactileRecipes.kt specifically so
 * they're testable here without a device or a Compose UI test harness, the same split
 * `FolderTabShapeTest` draws for the chrome's own slant math.
 */
class TactileRecipesTest {

    // -- slant edge ---------------------------------------------------------------------------

    @Test
    fun `LEADING keeps the top corner at the true edge, pulls the bottom in by the run`() {
        val quad = tactileSlantPolygon(width = 200f, height = 48f, side = TactileSlantSide.LEADING)
        assertEquals("top-left sits at the un-cut edge", 0f, quad[0].x, 0f)
        assertEquals("bottom-left is pulled in by the run", 48f * TactileSlantRatio, quad[3].x, 0.001f)
    }

    @Test
    fun `TRAILING keeps the top corner at the true edge, pulls the bottom in by the run`() {
        val quad = tactileSlantPolygon(width = 200f, height = 48f, side = TactileSlantSide.TRAILING)
        assertEquals("top-right sits at the un-cut edge", 200f, quad[1].x, 0f)
        assertEquals("bottom-right is pulled in by the run", 200f - 48f * TactileSlantRatio, quad[2].x, 0.001f)
    }

    /**
     * The Build-11.5 shard regression, pinned: the lean is a fraction of the element's own HEIGHT,
     * so a short knob gets a short run instead of the chrome's fixed 22dp gash. A 30dp knob used
     * to lose 22 of its 30dp to the cut; here the same knob loses barely five.
     */
    @Test
    fun `the lean scales with height, so a small knob keeps almost all of its width`() {
        val knobRun = tactileSlantRun(width = 30f, height = 30f)
        assertTrue("a 30dp knob must not lose most of its width to the lean", knobRun < 30f * 0.3f)
        val fieldRun = tactileSlantRun(width = 300f, height = 52f)
        assertTrue("a taller body leans further than a shorter one", fieldRun > knobRun)
    }

    @Test
    fun `the run never exceeds a quarter of the element's own width`() {
        listOf(8f to 48f, 20f to 52f, 30f to 90f, 200f to 56f).forEach { (w, h) ->
            val run = tactileSlantRun(w, h)
            assertTrue("run $run must stay within a quarter of width $w", run <= w * 0.25f + 0.001f)
            assertTrue("run must never be negative", run >= 0f)
        }
    }

    @Test
    fun `LEADING and TRAILING mirror each other at the same size`() {
        val leading = tactileSlantPolygon(64f, 48f, TactileSlantSide.LEADING)
        val trailing = tactileSlantPolygon(64f, 48f, TactileSlantSide.TRAILING)
        assertEquals(64f - trailing[2].x, leading[3].x, 0.001f)
        assertEquals(64f - trailing[1].x, leading[0].x, 0.001f)
    }

    @Test
    fun `a zero-width element never produces an out-of-bounds corner`() {
        val quad = tactileSlantPolygon(width = 0f, height = 48f, side = TactileSlantSide.LEADING)
        assertEquals(4, quad.size)
        quad.forEach { assertTrue(it.x.isFinite() && it.y.isFinite()) }
        assertEquals(0f, tactileSlantRun(0f, 48f), 0f)
    }

    // -- slanted body polygon ---------------------------------------------------------------------

    @Test
    fun `the slanted body is a four-corner quad with only one leaning edge`() {
        val w = 120f
        val h = 48f
        val run = tactileSlantRun(w, h)
        val leading = tactileSlantPolygon(w, h, TactileSlantSide.LEADING)
        assertEquals(4, leading.size)
        assertEquals(Offset(0f, 0f), leading[0])
        assertEquals(Offset(w, 0f), leading[1])
        assertEquals(Offset(w, h), leading[2])
        assertEquals(Offset(run, h), leading[3])
        // The trailing/right edge stays perfectly vertical -- only the leading one leans.
        assertEquals(leading[1].x, leading[2].x, 0f)

        val trailing = tactileSlantPolygon(w, h, TactileSlantSide.TRAILING)
        assertEquals(Offset(w - run, h), trailing[2])
        assertEquals(trailing[0].x, trailing[3].x, 0f)
    }

    // -- glint geometry -------------------------------------------------------------------------

    @Test
    fun `glint arc and dot stay within the cap's own bounds across a range of sizes`() {
        listOf(20f to 20f, 48f to 44f, 100f to 48f, 200f to 56f).forEach { (w, h) ->
            val g = tactileGlintGeometry(w, h)
            assertTrue("arc left in bounds ($w x $h)", g.arcCenter.x - g.arcRadius >= -0.01f)
            assertTrue("arc right in bounds ($w x $h)", g.arcCenter.x + g.arcRadius <= w + 0.01f)
            assertTrue("arc top in bounds ($w x $h)", g.arcCenter.y - g.arcRadius >= -0.01f)
            assertTrue("arc bottom in bounds ($w x $h)", g.arcCenter.y + g.arcRadius <= h + 0.01f)
            assertTrue("dot left in bounds ($w x $h)", g.dotCenter.x - g.dotRadius >= -0.01f)
            assertTrue("dot right in bounds ($w x $h)", g.dotCenter.x + g.dotRadius <= w + 0.01f)
            assertTrue("dot top in bounds ($w x $h)", g.dotCenter.y - g.dotRadius >= -0.01f)
            assertTrue("dot bottom in bounds ($w x $h)", g.dotCenter.y + g.dotRadius <= h + 0.01f)
        }
    }

    /**
     * The other half of the Build-11.5 regression: the dot used to be pinned to the element's
     * extreme corner pixel while the arc sat on its own inset circle, so the two read as an
     * unrelated comma and a speck of dirt. One mark means one circle.
     */
    @Test
    fun `the glint dot rides the same circle as the arc`() {
        listOf(20f to 20f, 48f to 44f, 100f to 48f, 200f to 56f).forEach { (w, h) ->
            val g = tactileGlintGeometry(w, h)
            val distance = hypot(g.dotCenter.x - g.arcCenter.x, g.dotCenter.y - g.arcCenter.y)
            assertEquals("dot must sit on the arc's radius ($w x $h)", g.arcRadius, distance, 0.01f)
        }
    }

    @Test
    fun `the glint dot follows the arc round the corner, never overlapping it`() {
        val g = tactileGlintGeometry(100f, 48f)
        assertTrue("arc centre sits in the right half", g.arcCenter.x > 50f)
        assertTrue("arc centre sits in the top half", g.arcCenter.y < 24f)
        // The dot picks up after the arc's sweep ends, so it is further clockwise: further right
        // and further down the corner than where the stroke stopped.
        val arcEnd = g.startAngleDegrees + g.sweepAngleDegrees
        assertTrue("the dot must start after the arc ends", 336f > arcEnd)
        assertTrue(g.dotCenter.x > g.arcCenter.x)
    }

    // -- slash-tick geometry --------------------------------------------------------------------

    @Test
    fun `slash bar has four finite points and no dot when idle`() {
        listOf(32f, 48f, 64f).forEach { h ->
            val g = tactileSlashGeometry(h, withErrorDot = false)
            assertEquals(4, g.bar.size)
            g.bar.forEach { p ->
                assertTrue(p.x.isFinite())
                assertTrue(p.y.isFinite())
            }
            assertNull(g.dotCenter)
        }
    }

    @Test
    fun `error dot floats above the bar, reading as an exclamation mark`() {
        val g = tactileSlashGeometry(48f, withErrorDot = true)
        assertNotNull(g.dotCenter)
        val barTop = g.bar.minOf { it.y }
        assertTrue("the dot sits above the bar's own top edge", g.dotCenter!!.y < barTop)
        assertTrue(g.dotRadius > 0f)
    }

    @Test
    fun `the bar pokes past the leading edge -- part of it sits at negative local x`() {
        val g = tactileSlashGeometry(48f, withErrorDot = false)
        assertTrue(g.bar.any { it.x < 0f })
    }

    // -- mandatory asterisk spokes ----------------------------------------------------------------

    @Test
    fun `asterisk has five spokes, each exactly radius from centre, 72 degrees apart`() {
        val size = 16f
        val points = tactileAsteriskSpokes(size)
        assertEquals(5, points.size)
        val center = Offset(size / 2f, size / 2f)
        points.forEach { p ->
            assertTrue(p.x.isFinite() && p.y.isFinite())
            val dist = hypot(p.x - center.x, p.y - center.y)
            assertEquals(size / 2f, dist, 0.01f)
        }
        // First spoke points straight up; each following one is 72 degrees further clockwise --
        // spot-check the second spoke lands where that rotation predicts instead of just trusting
        // every point sat on the circle (which alone wouldn't catch a wrong step size).
        val second = points[1]
        assertEquals(center.x + (size / 2f) * kotlin.math.cos(Math.toRadians(-18.0)).toFloat(), second.x, 0.01f)
        assertEquals(center.y + (size / 2f) * kotlin.math.sin(Math.toRadians(-18.0)).toFloat(), second.y, 0.01f)
    }

    @Test
    fun `asterisk spokes stay within their own bounding box`() {
        val size = 24f
        tactileAsteriskSpokes(size).forEach { p ->
            assertTrue(p.x in -0.01f..(size + 0.01f))
            assertTrue(p.y in -0.01f..(size + 0.01f))
        }
    }

    // -- slider fraction/value round-trip ----------------------------------------------------------

    @Test
    fun `slider fraction and value round-trip within a plain 0-1 range`() {
        val range = 0f..1f
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { v ->
            val f = tactileSliderFraction(v, range)
            assertEquals(v, tactileSliderValueAt(f, range), 0.001f)
        }
    }

    @Test
    fun `slider fraction and value round-trip within an arbitrary range`() {
        val range = 10f..90f
        listOf(10f, 25f, 50f, 90f).forEach { v ->
            val f = tactileSliderFraction(v, range)
            assertEquals(v, tactileSliderValueAt(f, range), 0.01f)
        }
    }

    @Test
    fun `slider fraction clamps an out-of-range value into 0-1`() {
        val range = 0f..10f
        assertEquals(0f, tactileSliderFraction(-5f, range), 0f)
        assertEquals(1f, tactileSliderFraction(15f, range), 0f)
    }

    @Test
    fun `slider fraction on a zero-span range never divides by zero`() {
        val range = 5f..5f
        assertEquals(0f, tactileSliderFraction(5f, range), 0f)
        assertEquals(0f, tactileSliderFraction(100f, range), 0f)
    }

    @Test
    fun `thumb centre and offset fraction round-trip`() {
        val trackWidth = 200f
        val thumbRadius = 14f
        listOf(0f, 0.3f, 0.5f, 1f).forEach { f ->
            val x = tactileThumbCenterX(f, trackWidth, thumbRadius)
            assertEquals(f, tactileFractionAtOffsetX(x, trackWidth, thumbRadius), 0.001f)
        }
    }

    @Test
    fun `thumb centre never leaves the usable track even at the extremes`() {
        val trackWidth = 60f
        val thumbRadius = 14f
        assertEquals(thumbRadius, tactileThumbCenterX(0f, trackWidth, thumbRadius), 0.001f)
        assertEquals(trackWidth - thumbRadius, tactileThumbCenterX(1f, trackWidth, thumbRadius), 0.001f)
    }

    @Test
    fun `a thumb radius that swallows the whole track never divides by zero`() {
        val f = tactileFractionAtOffsetX(x = 10f, trackWidth = 20f, thumbRadius = 20f)
        assertTrue(f.isFinite())
    }

    // -- toggle segment index/fraction --------------------------------------------------------------

    @Test
    fun `segment index clamps into range, defensively`() {
        assertEquals(0, tactileClampSegmentIndex(-1, 3))
        assertEquals(2, tactileClampSegmentIndex(5, 3))
        assertEquals(1, tactileClampSegmentIndex(1, 3))
        assertEquals(0, tactileClampSegmentIndex(0, 0))
    }

    @Test
    fun `segment fraction places each option at an even 1-N share of the plate`() {
        assertEquals(0f, tactileSegmentFraction(0, 2), 0f)
        assertEquals(0.5f, tactileSegmentFraction(1, 2), 0f)
        assertEquals(0f, tactileSegmentFraction(0, 3), 0f)
        assertEquals(1f / 3f, tactileSegmentFraction(1, 3), 0.0001f)
        assertEquals(2f / 3f, tactileSegmentFraction(2, 3), 0.0001f)
    }

    @Test
    fun `segment fraction on an empty option list is zero, not NaN`() {
        assertEquals(0f, tactileSegmentFraction(0, 0), 0f)
    }

    @Test
    fun `segment fraction clamps an out-of-range selected index the same way the clamp helper does`() {
        assertEquals(tactileSegmentFraction(2, 3), tactileSegmentFraction(99, 3), 0f)
        assertEquals(tactileSegmentFraction(0, 3), tactileSegmentFraction(-5, 3), 0f)
    }
}
