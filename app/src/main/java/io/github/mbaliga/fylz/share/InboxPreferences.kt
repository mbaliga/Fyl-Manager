package io.github.mbaliga.fylz.share

import android.content.Context
import android.net.Uri
import org.json.JSONObject

/**
 * Where [SendToFylzActivity] copies shared files, once the user has chosen one.
 *
 * [treeUri] is the granted tree (bookkeeping -- SAF permissions are granted per tree, not per
 * document); [folderUri] is the document URI `DocumentsContract.createDocument` expects as a
 * parent, the tree's own root document by construction. [displayName] is shown back to the user;
 * never parsed.
 */
data class InboxLocation(val treeUri: Uri, val folderUri: Uri, val displayName: String)

/**
 * Persisted single-slot Inbox destination.
 *
 * [io.github.mbaliga.fylz.canvas.CanvasLayoutStore]-modelled: its own SharedPreferences file, one
 * JSON blob, a `:backup` slot holding the last parseable write so a torn write never loses the
 * configured Inbox out from under an in-flight share, `@Synchronized` methods,
 * `check(editor.commit())`.
 */
class InboxPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun location(): InboxLocation? {
        val current = decode(preferences.getString(LOCATION_KEY, null))
        if (current != null) return current
        return decode(preferences.getString(BACKUP_KEY, null))
    }

    @Synchronized
    fun setLocation(location: InboxLocation) {
        val encoded = encode(location)
        val currentRaw = preferences.getString(LOCATION_KEY, null)
        val editor = preferences.edit().putString(LOCATION_KEY, encoded)

        // Retain the last parseable location: a partially written or externally corrupted
        // current value must never replace the only known-good Inbox destination.
        if (!currentRaw.isNullOrBlank() && decode(currentRaw) != null) {
            editor.putString(BACKUP_KEY, currentRaw)
        }

        check(editor.commit()) { "Unable to persist the Inbox location." }
    }

    @Synchronized
    fun clear() {
        check(preferences.edit().remove(LOCATION_KEY).remove(BACKUP_KEY).commit()) {
            "Unable to clear the Inbox location."
        }
    }

    private fun encode(location: InboxLocation): String = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put("treeUri", location.treeUri.toString())
        .put("folderUri", location.folderUri.toString())
        .put("displayName", location.displayName)
        .toString()

    /** Returns null only when a non-blank payload is malformed -- mirrors CanvasLayoutStore. */
    private fun decode(raw: String?): InboxLocation? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val value = JSONObject(raw)
            InboxLocation(
                treeUri = Uri.parse(value.getString("treeUri")),
                folderUri = Uri.parse(value.getString("folderUri")),
                displayName = value.optString("displayName"),
            )
        }.getOrNull()
    }

    private companion object {
        const val PREFERENCES_NAME = "fylz_inbox"
        const val LOCATION_KEY = "location"
        const val BACKUP_KEY = "location:backup"
        const val SCHEMA_VERSION = 1
    }
}
