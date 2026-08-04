package io.github.mbaliga.fylz.ui.hyle

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.mbaliga.fylz.R

/**
 * Short UI sound cues for the Hyle folder-tab switcher, played through [SoundPool] on the
 * sonification stream so the system's own silent-mode / Do Not Disturb / volume settings
 * govern them exactly the way they govern any other system UI sound -- this class never
 * inspects ringer mode itself, it just asks for the right [AudioAttributes] and lets Android
 * decide.
 *
 * One short click asset (`R.raw.hyle_tab_tick`) is reused at two playback rates rather than
 * shipping two files: a higher-pitched, quieter rate for the continuous tick fired while
 * scrubbing across tabs, and the natural rate/volume for the firmer click when a tab is
 * committed as the active selection.
 */
class HyleTabSoundCues(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private var soundId: Int = 0
    private var loaded = false

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0 && id == soundId) loaded = true }
        soundId = pool.load(context, R.raw.hyle_tab_tick, 1)
    }

    /** A light, high tick for each tab boundary crossed while scrubbing/dragging. */
    fun tick() {
        if (!loaded) return
        pool.play(soundId, 0.32f, 0.32f, 0, 0, 1.55f)
    }

    /** The firmer click for a tab becoming the committed active selection. */
    fun settle() {
        if (!loaded) return
        pool.play(soundId, 0.55f, 0.55f, 1, 0, 1.0f)
    }

    fun release() {
        runCatching { pool.release() }
    }
}

@Composable
fun rememberHyleTabSoundCues(): HyleTabSoundCues {
    val context = LocalContext.current
    val cues = remember(context) { HyleTabSoundCues(context) }
    DisposableEffect(cues) {
        onDispose { cues.release() }
    }
    return cues
}
