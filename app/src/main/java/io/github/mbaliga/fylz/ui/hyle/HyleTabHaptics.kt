package io.github.mbaliga.fylz.ui.hyle

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/**
 * Real device haptics for the Hyle folder-tab switcher, matched to Hyle's "heartbeat, not
 * weather" motion idiom: touch feedback is a physical response to the gesture in progress,
 * never a generic buzz. State is shown by motion; haptics are part of that motion, not a
 * substitute for it.
 *
 * Two distinct channels are used deliberately:
 * - [tick] rides on [View.performHapticFeedback], which the user's own "touch feedback"
 *   system setting can suppress — appropriate for the light, continuous tick fired as a
 *   drag crosses each tab boundary while scrubbing.
 * - [settle] uses [VibrationEffect] directly through the platform [VibratorManager] so it
 *   can play a genuine two-stage waveform (a light pre-tick, then a firmer click ~40ms
 *   later) that mirrors a tab visually snapping into its slot -- a single predefined
 *   constant cannot express that shape.
 */
class HyleTabHaptics(
    private val view: View,
    private val context: Context,
) {
    private val vibrator by lazy {
        runCatching {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        }.getOrNull()
    }

    /** A light tick, fired once per tab boundary crossed while dragging/scrubbing. */
    fun tick() {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** The firmer, two-stage pulse played when a tab becomes the active selection. */
    fun settle() {
        val effect = runCatching {
            VibrationEffect.createWaveform(
                longArrayOf(0, 12, 28, 16),
                intArrayOf(0, 110, 0, 210),
                -1,
            )
        }.getOrNull()
        val played = effect?.let { runCatching { vibrator?.vibrate(it) }.isSuccess } == true
        if (!played) view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    /** A short, distinct pulse for dismissing a tab -- deliberately not the settle shape. */
    fun dismiss() {
        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
    }
}

@Composable
fun rememberHyleTabHaptics(): HyleTabHaptics {
    val view = LocalView.current
    val context = LocalContext.current
    return remember(view, context) { HyleTabHaptics(view, context) }
}
