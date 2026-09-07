package io.github.mbaliga.fylz.ui.deck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FileDeckTest {

    @Test
    fun `the front card sits dead centre at rest`() {
        val front = deckTransforms(0f, 0)
        assertEquals(0f, front.translationX, 0.0001f)
        assertEquals(0f, front.translationY, 0.0001f)
        assertEquals(0f, front.rotationZ, 0.0001f)
        assertEquals(1f, front.scale, 0.0001f)
        assertEquals(1f, front.alpha, 0.0001f)
    }

    @Test
    fun `at rest, scale shrinks and alpha fades monotonically with depth`() {
        var previousScale = Float.MAX_VALUE
        var previousAlpha = Float.MAX_VALUE
        for (depth in 0..MAX_FAN_DEPTH) {
            val transform = deckTransforms(0f, depth)
            assertTrue("scale must shrink at depth $depth", transform.scale < previousScale)
            assertTrue("alpha must not grow at depth $depth", transform.alpha <= previousAlpha)
            previousScale = transform.scale
            previousAlpha = transform.alpha
        }
    }

    @Test
    fun `every card sits higher than the one in front of it`() {
        for (depth in 1..MAX_FAN_DEPTH) {
            assertTrue(deckTransforms(0f, depth).translationY < deckTransforms(0f, depth - 1).translationY)
        }
    }

    @Test
    fun `fanned offset and rotation magnitude grow monotonically with depth`() {
        var previousOffset = -1f
        var previousRotation = -1f
        for (depth in 1..MAX_FAN_DEPTH) {
            val transform = deckTransforms(0f, depth)
            val offset = abs(transform.translationX)
            val rotation = abs(transform.rotationZ)
            assertTrue("offset must grow at depth $depth", offset > previousOffset)
            assertTrue("rotation must grow at depth $depth", rotation > previousRotation)
            previousOffset = offset
            previousRotation = rotation
        }
    }

    @Test
    fun `fanned cards alternate sides symmetrically`() {
        for (depth in 1 until MAX_FAN_DEPTH) {
            val here = deckTransforms(0f, depth)
            val next = deckTransforms(0f, depth + 1)
            assertTrue("depth $depth and ${depth + 1} must sit on opposite sides", here.translationX * next.translationX < 0f)
            // Rotation banks the same way the card leans, on both sides of the fan.
            assertTrue(here.rotationZ * here.translationX > 0f)
        }
    }

    @Test
    fun `progress is clamped to a single step either way`() {
        val overshoot = deckTransforms(5f, 2)
        val capped = deckTransforms(1f, 2)
        assertEquals(capped.translationX, overshoot.translationX, 0.0001f)
        assertEquals(capped.scale, overshoot.scale, 0.0001f)

        val undershoot = deckTransforms(-5f, 2)
        val cappedBack = deckTransforms(-1f, 2)
        assertEquals(cappedBack.translationX, undershoot.translationX, 0.0001f)
    }

    @Test
    fun `advancing a full step hands every fanned card the next slot's rest transform`() {
        for (depth in 1..MAX_FAN_DEPTH) {
            val advanced = deckTransforms(1f, depth)
            val nextRest = deckTransforms(0f, depth - 1)
            assertEquals(nextRest.translationX, advanced.translationX, 0.001f)
            assertEquals(nextRest.translationY, advanced.translationY, 0.001f)
            assertEquals(nextRest.scale, advanced.scale, 0.001f)
            assertEquals(nextRest.alpha, advanced.alpha, 0.001f)
        }
    }

    @Test
    fun `the outgoing front card rides all the way to the back of the fan`() {
        val outgoing = deckTransforms(1f, 0)
        val deepest = deckTransforms(0f, MAX_FAN_DEPTH)
        assertEquals(deepest.translationX, outgoing.translationX, 0.001f)
        assertEquals(deepest.translationY, outgoing.translationY, 0.001f)
        assertEquals(deepest.scale, outgoing.scale, 0.001f)
    }

    @Test
    fun `retreating is the exact mirror of advancing`() {
        for (depth in 0 until MAX_FAN_DEPTH) {
            val retreated = deckTransforms(-1f, depth)
            val previousRest = deckTransforms(0f, depth + 1)
            assertEquals(previousRest.translationX, retreated.translationX, 0.001f)
            assertEquals(previousRest.scale, retreated.scale, 0.001f)
        }
        // The deepest slot is the one card arriving from beyond the fan -- it lands exactly
        // where the front card rests, mirroring how the front card rides to the back on advance.
        val incoming = deckTransforms(-1f, MAX_FAN_DEPTH)
        val front = deckTransforms(0f, 0)
        assertEquals(front.translationX, incoming.translationX, 0.001f)
        assertEquals(front.scale, incoming.scale, 0.001f)
    }

    @Test
    fun `a partial riffle interpolates strictly between the two rest positions`() {
        val start = deckTransforms(0f, 2)
        val end = deckTransforms(0f, 1)
        val half = deckTransforms(0.5f, 2)

        assertTrue(half.scale in minOf(start.scale, end.scale)..maxOf(start.scale, end.scale))
        assertEquals((start.translationX + end.translationX) / 2f, half.translationX, 0.001f)
    }
}
