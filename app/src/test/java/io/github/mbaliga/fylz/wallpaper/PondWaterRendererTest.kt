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
}
