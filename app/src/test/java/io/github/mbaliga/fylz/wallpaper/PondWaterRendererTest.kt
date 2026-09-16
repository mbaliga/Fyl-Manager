package io.github.mbaliga.fylz.wallpaper

import kotlin.math.hypot
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PondWaterRenderer.step] takes its `dt` from the caller and never touches `android.graphics.*`
 * -- only [PondWaterRenderer.draw] does, and nothing here calls it -- so this suite runs as a
 * plain JVM unit test, no Robolectric.
 */
class PondWaterRendererTest {

    private fun renderer(seed: Long = 42L, w: Int = 800, h: Int = 480, density: Float = 2f): PondWaterRenderer {
        val r = PondWaterRenderer(random = Random(seed))
        r.resize(w, h, density)
        return r
    }

    @Test
    fun `step advances mote and blob positions`() {
        val r = renderer()
        val motesBefore = r.motePositions()
        val blobsBefore = r.blobPositions()

        repeat(10) { r.step(1f / 30f) }

        val motesAfter = r.motePositions()
        val blobsAfter = r.blobPositions()
        assertTrue(
            "expected at least one mote to have moved",
            motesBefore.indices.any { motesBefore[it] != motesAfter[it] },
        )
        assertTrue(
            "expected at least one blob to have moved",
            blobsBefore.indices.any { blobsBefore[it] != blobsAfter[it] },
        )
    }

    @Test
    fun `dt of zero is an identity on positions`() {
        val r = renderer()
        val motesBefore = r.motePositions()
        val blobsBefore = r.blobPositions()

        r.step(0f)

        assertEquals(motesBefore, r.motePositions())
        assertEquals(blobsBefore, r.blobPositions())
    }

    @Test
    fun `a non-positive dt never reverses or corrupts state`() {
        val r = renderer()
        r.step(1f / 30f) // give it some position/velocity history first
        val before = r.motePositions()

        r.step(-1f)
        r.step(0f)

        assertEquals(before, r.motePositions())
    }

    @Test
    fun `poke displaces nearby motes more than distant ones`() {
        val r = renderer(seed = 7L)
        val before = r.motePositions()

        // Poke the corner rather than any mote's own position -- poking exactly on top of a mote
        // gives it a zero-length (undefined) direction vector, not a strong push. Ranking every
        // mote's distance from a fixed point outside the field keeps "near" and "far" well
        // defined regardless of the seed's actual layout.
        r.poke(0f, 0f)
        val nearIndex = before.indices.minBy { hypot(before[it].first, before[it].second) }
        val farIndex = before.indices.maxBy { hypot(before[it].first, before[it].second) }

        r.step(1f / 30f)

        val after = r.motePositions()
        val nearDelta = hypot(
            after[nearIndex].first - before[nearIndex].first,
            after[nearIndex].second - before[nearIndex].second,
        )
        val farDelta = hypot(
            after[farIndex].first - before[farIndex].first,
            after[farIndex].second - before[farIndex].second,
        )

        assertTrue(
            "the mote nearest the poke should displace more than the farthest one " +
                "(near=$nearDelta far=$farDelta)",
            nearDelta > farDelta,
        )
    }

    @Test
    fun `resize keeps every entity within the new bounds`() {
        val r = renderer(w = 300, h = 200)

        assertTrue(r.motePositions().all { (x, y) -> x in 0f..300f && y in 0f..200f })
        assertTrue(r.blobPositions().all { (x, y) -> x in 0f..300f && y in 0f..200f })

        // Motes wrap every step (unlike blobs, which are allowed a soft margin), so they stay
        // strictly within bounds even after sustained motion.
        repeat(20) { r.step(1f / 30f) }
        assertTrue(r.motePositions().all { (x, y) -> x in 0f..300f && y in 0f..200f })
    }

    @Test
    fun `a fresh field seeds the documented population sizes`() {
        val r = renderer()
        assertTrue("blob count ${r.blobCount()} outside 8..14", r.blobCount() in 8..14)
        assertTrue("mote count ${r.moteCount()} below the documented floor", r.moteCount() >= 10)
    }

    @Test
    fun `two renderers seeded alike stay identical through the same drive`() {
        val a = renderer(seed = 99L)
        val b = renderer(seed = 99L)
        assertEquals(a.motePositions(), b.motePositions())
        assertEquals(a.blobPositions(), b.blobPositions())

        a.step(1f / 30f); b.step(1f / 30f)
        a.poke(40f, 40f); b.poke(40f, 40f)
        a.step(1f / 30f); b.step(1f / 30f)

        assertEquals(a.motePositions(), b.motePositions())
        assertEquals(a.blobPositions(), b.blobPositions())
    }

    // -- Build 11.5 (design-fidelity pass): vignette, light shafts, mote twinkle, deeper motes,
    // 4-stop background -- added without touching any assertion above. --

    @Test
    fun `motes seed within the widened 1_0-2_5dp radius range`() {
        // density = 1f so `radius = (1.0f + rand * 1.5f) * density` is a direct dp read, not a
        // density-scaled one -- the spec's "1.0-2.5dp" is a dp figure.
        val r = renderer(density = 1f)
        assertTrue(
            "expected every mote radius within 1.0..2.5dp, got ${r.moteRadii()}",
            r.moteRadii().all { it in 1.0f..2.5f },
        )
    }

    @Test
    fun `a fresh field seeds 2 or 3 light shafts, deterministically per seed`() {
        val a = renderer(seed = 55L)
        val b = renderer(seed = 55L)
        assertTrue("light shaft count ${a.lightShaftCount()} outside 2..3", a.lightShaftCount() in 2..3)
        assertEquals(a.lightShaftCount(), b.lightShaftCount())
        assertEquals(a.lightShaftPositions(), b.lightShaftPositions())
    }

    @Test
    fun `light shafts stay anchored in the upper region and drift deterministically`() {
        val a = renderer(seed = 21L, w = 800, h = 480)
        val b = renderer(seed = 21L, w = 800, h = 480)

        repeat(60) { a.step(1f / 30f); b.step(1f / 30f) }

        // Same generous-but-real bound as the resize test uses for motes/blobs: the anchor +
        // sway construction keeps every shaft within a wide margin of the canvas, biased to the
        // upper ~48% (anchor's 0.42h ceiling plus the largest possible 0.06h sway).
        assertTrue(
            "expected every light shaft to stay within a wide margin of the canvas",
            a.lightShaftPositions().all { (x, y) -> x in -120f..920f && y in -60f..300f },
        )
        assertEquals(
            "two identically-seeded renderers driven the same way must stay identical",
            a.lightShaftPositions(),
            b.lightShaftPositions(),
        )
    }

    @Test
    fun `dt of zero leaves twinkle phase and light shaft position unchanged`() {
        val r = renderer()
        val phasesBefore = r.moteTwinklePhases()
        val shaftsBefore = r.lightShaftPositions()

        r.step(0f)

        assertEquals(phasesBefore, r.moteTwinklePhases())
        assertEquals(shaftsBefore, r.lightShaftPositions())
    }

    @Test
    fun `stepping advances mote twinkle phase and light shaft position`() {
        val r = renderer()
        val phasesBefore = r.moteTwinklePhases()
        val shaftsBefore = r.lightShaftPositions()

        repeat(10) { r.step(1f / 30f) }

        assertTrue(
            "expected at least one mote's twinkle phase to have advanced",
            phasesBefore.indices.any { phasesBefore[it] != r.moteTwinklePhases()[it] },
        )
        assertTrue(
            "expected at least one light shaft to have drifted",
            shaftsBefore.indices.any { shaftsBefore[it] != r.lightShaftPositions()[it] },
        )
    }

    @Test
    fun `vignette alpha lands inside the Hyle soft-shadow band`() {
        val r = renderer()
        assertTrue(
            "vignette alpha ${r.vignetteAlpha()} outside the 0.10-0.16 Hyle band",
            r.vignetteAlpha() in 0.10f..0.16f,
        )
    }

    @Test
    fun `background gradient now carries 4 ascending stops from 0 to 1`() {
        val stops = renderer().backgroundStopPositions()
        assertEquals(4, stops.size)
        assertEquals(0f, stops.first())
        assertEquals(1f, stops.last())
        for (i in 1 until stops.size) {
            assertTrue("stops must be strictly ascending, got ${stops.toList()}", stops[i] > stops[i - 1])
        }
    }
}
