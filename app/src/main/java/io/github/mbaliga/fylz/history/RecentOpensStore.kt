package io.github.mbaliga.fylz.history

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * One thing the user opened, for the desktop's Recents widget.
 *
 * [kindName] is a plain string rather than [io.github.mbaliga.fylz.core.model.EntryKind] directly --
 * the same by-name idiom every other persisted enum in this codebase uses (see
 * [io.github.mbaliga.fylz.canvas.CanvasLayoutStore]'s KDoc and friends) so a Kotlin constant rename
 * never breaks a value already sitting in this store. A caller that needs the real enum back reads
 * it with `runCatching { EntryKind.valueOf(item.kindName) }.getOrDefault(EntryKind.OTHER)`, the
 * same pattern [DesktopStore][io.github.mbaliga.fylz.desktop.DesktopStore] uses for
 * [io.github.mbaliga.fylz.desktop.DesktopItemSize].
 */
data class RecentOpen(
    val uri: Uri,
    val displayName: String,
    val kindName: String,
    val openedAtMillis: Long,
)

/**
 * Persisted "opened recently" list, newest first, capped at [MAX_ITEMS].
 *
 * [io.github.mbaliga.fylz.canvas.CanvasLayoutStore]-modelled: its own SharedPreferences file, a
 * single JSON-array key plus a `:backup` slot holding the last parseable write so a torn write
 * never loses the whole list, `@Synchronized` methods, `check(editor.commit())`.
 *
 * [record] dedupes by [RecentOpen.uri]: opening something already on the list drops its old entry
 * and re-inserts it at the front with the new timestamp, rather than letting the same file pile up
 * twice at two different ages.
 */
class RecentOpensStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun items(): List<RecentOpen> = decodeCurrent()

    /** Idempotent-by-uri: an already-present [uri] moves to the front with [nowMillis] rather than
     *  appearing twice. Past [MAX_ITEMS] the oldest entry is dropped. */
    @Synchronized
    fun record(uri: Uri, displayName: String, kindName: String, nowMillis: Long) {
        val current = decodeCurrent().filterNot { it.uri == uri }
        val updated = (listOf(RecentOpen(uri, displayName, kindName, nowMillis)) + current).take(MAX_ITEMS)
        persist(updated)
    }

    /** Rewrites [old] to [new] wherever it appears as a recorded [RecentOpen.uri]. Returns whether
     *  anything changed. */
    @Synchronized
    fun migrateUri(old: Uri, new: Uri): Boolean {
        if (old == new) return false
        val current = decodeCurrent()
        val index = current.indexOfFirst { it.uri == old }
        if (index < 0) return false
        val migrated = current.toMutableList()
        migrated[index] = migrated[index].copy(uri = new)
        persist(migrated)
        return true
    }

    @Synchronized
    fun clear() {
        persist(emptyList())
    }

    private fun decodeCurrent(): List<RecentOpen> {
        val current = decode(preferences.getString(ITEMS_KEY, null))
        if (current != null) return current
        return decode(preferences.getString(BACKUP_KEY, null)).orEmpty()
    }

    private fun persist(items: List<RecentOpen>) {
        val encoded = encode(items)
        val currentRaw = preferences.getString(ITEMS_KEY, null)
        val editor = preferences.edit().putString(ITEMS_KEY, encoded)

        // Retain the last parseable list, exactly as CanvasLayoutStore retains a location's: a
        // partially written or externally corrupted current value must never replace the only
        // known-good list.
        if (!currentRaw.isNullOrBlank() && decode(currentRaw) != null) {
            editor.putString(BACKUP_KEY, currentRaw)
        }

        commitOrThrow(editor)
    }

    private fun commitOrThrow(editor: SharedPreferences.Editor) {
        check(editor.commit()) { "Unable to persist recent opens." }
    }

    /** Returns null only when a non-empty payload is malformed at the top level -- an individual
     *  malformed record inside an otherwise-valid array is skipped, never fatal to the rest. */
    private fun decode(raw: String?): List<RecentOpen>? {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val record = array.optJSONObject(index) ?: continue
                    decodeRecord(record)?.let { add(it) }
                }
            }
        }.getOrNull()
    }

    private fun decodeRecord(record: JSONObject): RecentOpen? = runCatching {
        RecentOpen(
            uri = Uri.parse(record.getString("uri")),
            displayName = record.getString("displayName"),
            kindName = record.getString("kindName"),
            openedAtMillis = record.getLong("openedAtMillis"),
        )
    }.getOrNull()

    private fun encode(items: List<RecentOpen>): String {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("schemaVersion", SCHEMA_VERSION)
                    .put("uri", item.uri.toString())
                    .put("displayName", item.displayName)
                    .put("kindName", item.kindName)
                    .put("openedAtMillis", item.openedAtMillis),
            )
        }
        return array.toString()
    }

    private companion object {
        const val PREFERENCES_NAME = "fylz_recent_opens"
        const val ITEMS_KEY = "items"
        const val BACKUP_KEY = "items:backup"
        const val SCHEMA_VERSION = 1
        const val MAX_ITEMS = 30
    }
}
