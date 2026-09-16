package io.github.mbaliga.fylz.audio

import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [PlaybackEqualizer] wraps real android.media.audiofx.Equalizer band-level calls that
 * Robolectric only partially shadows: construction and setEnabled work under
 * ShadowEqualizer/ShadowAudioEffect, but setBandLevel/getBandLevel/getNumberOfBands/
 * getBandLevelRange/getCenterFreq are not shadowed and are likely to throw or no-op
 * unpredictably (a design-time finding, not a guess) -- so this suite covers only the one
 * piece of real logic that runs BEFORE any Equalizer is ever constructed: refusing to attach
 * to audio session id 0. Everything past that line can only be verified on a real device, the
 * same kind of gap PdfDocument has elsewhere in this app for its own, unrelated reason.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackEqualizerTest {

    @Test
    fun `attach refuses session id 0 -- that's the GLOBAL output mix, not this player`() {
        assertNull(PlaybackEqualizer.attach(0))
    }
}
