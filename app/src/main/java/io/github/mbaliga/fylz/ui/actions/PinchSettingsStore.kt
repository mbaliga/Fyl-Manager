package io.github.mbaliga.fylz.ui.actions

import android.content.Context
import io.github.mbaliga.fylz.model.PinchInBehavior
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The "Pinch in gesture" setting (owner request): Go Up (the default) or step the browse
 * surface's detail-level ladder ([DetailLevelLadder]) -- a single persisted value, following the
 * same SharedPreferences-file-per-store shape every other store in this app uses
 * ([io.github.mbaliga.fylz.operations.VerifySettings], `RecycleBinStore`, ...) rather than
 * DataStore or Room (A4: the toolchain has no KSP).
 *
 * NOTE: the design brief for this feature named `ThemeMode`'s own persistence as the pattern to
 * follow. On inspection, `ThemeMode` itself turns out to hold only in `remember{}` state in
 * `FylzV1App.kt` today -- it does not survive a process death at all (see
 * docs/agent/REVIEW_QUEUE.md). This store follows [io.github.mbaliga.fylz.operations.VerifySettings]'s
 * actual, working shape instead, which is the closest real precedent in this codebase for "one
 * persisted enum setting living next to the Tools room".
 *
 * [behavior]'s [StateFlow] mirrors only writes made through THIS instance -- the same documented
 * caveat [io.github.mbaliga.fylz.operations.VerifySettings] carries for the same reason: a
 * `SharedPreferences` file has no built-in change notification this class subscribes to.
 */
class PinchSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _behavior = MutableStateFlow(readBehavior())
    val behavior: StateFlow<PinchInBehavior> = _behavior.asStateFlow()

    fun setBehavior(behavior: PinchInBehavior) {
        check(preferences.edit().putString(BEHAVIOR_KEY, behavior.name).commit()) {
            "Could not save the Pinch in gesture setting"
        }
        _behavior.value = behavior
    }

    private fun readBehavior(): PinchInBehavior {
        val stored = preferences.getString(BEHAVIOR_KEY, null) ?: return PinchInBehavior.GO_UP
        return runCatching { PinchInBehavior.valueOf(stored) }.getOrDefault(PinchInBehavior.GO_UP)
    }

    private companion object {
        const val PREFERENCES_NAME = "fylz_pinch_settings"
        const val BEHAVIOR_KEY = "behavior"
    }
}
