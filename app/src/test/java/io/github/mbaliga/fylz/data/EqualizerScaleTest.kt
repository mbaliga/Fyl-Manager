package io.github.mbaliga.fylz.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Plain arithmetic, no Android type anywhere -- see [EqualizerScale]'s own KDoc for why the
 * real android.media.audiofx.Equalizer this feeds is not exercised here at all. */
class EqualizerScaleTest {

    private val range = -1500..1500

    @Test
    fun `a fraction of 0 or 1 lands exactly on the range's own ends`() {
        assertEquals(-1500, EqualizerScale.levelForFraction(range, 0f))
        assertEquals(1500, EqualizerScale.levelForFraction(range, 1f))
    }

    @Test
    fun `a fraction of 0-5 lands on the midpoint`() {
        assertEquals(0, EqualizerScale.levelForFraction(range, 0.5f))
    }

    @Test
    fun `an out-of-range fraction is clamped rather than extrapolated`() {
        assertEquals(-1500, EqualizerScale.levelForFraction(range, -2f))
        assertEquals(1500, EqualizerScale.levelForFraction(range, 2f))
    }

    @Test
    fun `fractionForLevel is the exact inverse of levelForFraction at the ends and midpoint`() {
        assertEquals(0f, EqualizerScale.fractionForLevel(range, -1500), 0.0001f)
        assertEquals(1f, EqualizerScale.fractionForLevel(range, 1500), 0.0001f)
        assertEquals(0.5f, EqualizerScale.fractionForLevel(range, 0), 0.0001f)
    }

    @Test
    fun `an out-of-range level is clamped to 0f or 1f rather than escaping the fraction`() {
        assertEquals(0f, EqualizerScale.fractionForLevel(range, -5000), 0.0001f)
        assertEquals(1f, EqualizerScale.fractionForLevel(range, 5000), 0.0001f)
    }

    @Test
    fun `a zero-span range never divides by zero`() {
        assertEquals(0.5f, EqualizerScale.fractionForLevel(3..3, 3), 0.0001f)
    }
}
