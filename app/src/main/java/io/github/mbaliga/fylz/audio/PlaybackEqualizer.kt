package io.github.mbaliga.fylz.audio

import android.media.audiofx.Equalizer

/**
 * Thin, releasable wrapper around [Equalizer], rebuilt every time the owning player's audio
 * session id changes -- an ExoPlayer's session id can change more than once across its life
 * (format switches between playlist items, tunneling toggling, stop()+re-prepare), so this must
 * never be constructed once and kept for the player's whole lifetime.
 */
class PlaybackEqualizer private constructor(private val equalizer: Equalizer) {

    val bandCount: Int get() = equalizer.numberOfBands.toInt()

    fun bandLevelRange(): IntRange {
        val range = equalizer.bandLevelRange
        return range[0].toInt()..range[1].toInt()
    }

    fun centerFrequencyHz(band: Int): Int = equalizer.getCenterFreq(band.toShort()) / 1000

    fun bandLevel(band: Int): Int = equalizer.getBandLevel(band.toShort()).toInt()

    fun setBandLevel(band: Int, level: Int) {
        equalizer.setBandLevel(band.toShort(), level.toShort())
    }

    fun setEnabled(enabled: Boolean) {
        equalizer.enabled = enabled
    }

    fun release() = equalizer.release()

    companion object {
        /**
         * Null if [audioSessionId] is unset (0) -- a real device hasn't created the underlying
         * AudioTrack yet at that point, and attaching an effect to session 0 targets the GLOBAL
         * output mix (every app's audio), not just this player, which additionally needs a
         * permission this app does not request. Callers must re-invoke this (releasing whatever
         * they got back first) every time the session id actually changes, not just once.
         */
        fun attach(audioSessionId: Int): PlaybackEqualizer? {
            if (audioSessionId == 0) return null
            return PlaybackEqualizer(Equalizer(0, audioSessionId).apply { enabled = true })
        }
    }
}
