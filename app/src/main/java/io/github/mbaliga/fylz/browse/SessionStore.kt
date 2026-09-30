package io.github.mbaliga.fylz.browse

import android.content.Context

/**
 * The durable half of session restoration (P1.10) -- a SharedPreferences-backed spot to persist
 * [io.github.mbaliga.fylz.ui.BrowserViewModel]'s own encoded [SessionSnapshot], so a genuinely cold
 * launch (the process gone long enough, or the task swiped away, that the OS discarded the saved
 * instance state `SavedStateHandle` relies on) still comes back to where the user left off, not
 * just a config change or an OS-restored process death. See
 * [io.github.mbaliga.fylz.ui.BrowserViewModel]'s own KDoc for how the two persistence layers fit
 * together.
 *
 * Replaces the old `OpenTabsStore` (P0.10), which persisted only each tab's root Uri, never its
 * navigation stack, active tab or sort/view/preview/search preferences.
 */
class SessionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun save(json: String) {
        preferences.edit().putString(KEY_SESSION, json).apply()
    }

    @Synchronized
    fun restore(): String? = preferences.getString(KEY_SESSION, null)

    private companion object {
        const val PREFERENCES_NAME = "fylz_session"
        const val KEY_SESSION = "session"
    }
}
