package io.github.mbaliga.fylz.data

/**
 * Maps a UI slider's 0f..1f fraction onto a device's own dB range and back. Kept separate from
 * [io.github.mbaliga.fylz.audio.PlaybackEqualizer] so this mapping is unit-testable without
 * touching the real android.media.audiofx.Equalizer -- Robolectric shadows its construction and
 * setEnabled, but not setBandLevel/getBandLevel, so band-level correctness can only ever be
 * verified on a real device, not in this test suite.
 */
object EqualizerScale {
    fun levelForFraction(range: IntRange, fraction: Float): Int {
        val clamped = fraction.coerceIn(0f, 1f)
        return (range.first + (range.last - range.first) * clamped).toInt()
    }

    fun fractionForLevel(range: IntRange, level: Int): Float {
        val span = range.last - range.first
        if (span <= 0) return 0.5f
        return ((level - range.first).toFloat() / span).coerceIn(0f, 1f)
    }
}
