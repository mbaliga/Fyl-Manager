package io.github.mbaliga.fylz.browse

import android.content.Context
import android.net.Uri
import org.json.JSONArray

/**
 * Which persisted SAF grants the user actually opened as a browsing tab (P0.10).
 *
 * Every persisted grant -- a copy/move destination, a backup folder, an index folder -- used to
 * reappear as a tab on every launch, since tab restoration walked the OS's whole
 * `persistedUriPermissions` list rather than what the user actually meant to browse. This is a
 * deliberately small, ordered record of just that; P1.10 replaces it with the real session model.
 */
class OpenTabsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun list(): List<Uri> {
        val raw = preferences.getString(KEY_URIS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList { for (index in 0 until array.length()) add(Uri.parse(array.getString(index))) }
        }.getOrDefault(emptyList())
    }

    /** Idempotent: recording the same tree twice keeps its original position rather than moving
     * it to the end. */
    @Synchronized
    fun record(uri: Uri) {
        val current = list()
        if (uri in current) return
        persist(current + uri)
    }

    private fun persist(uris: List<Uri>) {
        val array = JSONArray()
        uris.forEach { array.put(it.toString()) }
        preferences.edit().putString(KEY_URIS, array.toString()).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "fylz_open_tabs"
        const val KEY_URIS = "uris"
    }
}
