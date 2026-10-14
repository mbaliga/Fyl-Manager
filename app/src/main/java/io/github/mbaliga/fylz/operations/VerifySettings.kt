package io.github.mbaliga.fylz.operations

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How thoroughly [FileOperationService] confirms a copy landed correctly, on top of the size
 * check every copy always gets (P1.4). */
enum class VerifyMode {
    OFF,

    /** The default: verify a copy whose destination is [DestinationKind.REMOVABLE] or
     * [DestinationKind.OTHER] (a third-party provider this app has no special knowledge of, which
     * covers a network mount once one can ever reach [FileOperationService] at all) -- this
     * device's own internal storage is trusted without the extra pass. */
    REMOVABLE_AND_NETWORK,
    ALWAYS,
}

/**
 * The "Verify copies" setting: a single persisted value, following the same
 * SharedPreferences-file-per-store shape every other store in this app uses ([RecycleBinStore],
 * `OpenTabsStore`, ...) rather than DataStore or Room (A4: the toolchain has no KSP).
 *
 * [mode]'s [StateFlow] mirrors only writes made through THIS instance -- the same documented
 * caveat [RecycleBinStore] carries for the same reason: a `SharedPreferences` file has no built-in
 * change notification this class subscribes to.
 */
class VerifySettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _mode = MutableStateFlow(readMode())
    val mode: StateFlow<VerifyMode> = _mode.asStateFlow()

    fun setMode(mode: VerifyMode) {
        check(preferences.edit().putString(MODE_KEY, mode.name).commit()) {
            "Could not save the Verify copies setting"
        }
        _mode.value = mode
    }

    private fun readMode(): VerifyMode {
        val stored = preferences.getString(MODE_KEY, null) ?: return VerifyMode.REMOVABLE_AND_NETWORK
        return runCatching { VerifyMode.valueOf(stored) }.getOrDefault(VerifyMode.REMOVABLE_AND_NETWORK)
    }

    private companion object {
        const val PREFERENCES_NAME = "fylz_verify_settings"
        const val MODE_KEY = "mode"
    }
}
