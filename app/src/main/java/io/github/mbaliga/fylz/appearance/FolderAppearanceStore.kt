package io.github.mbaliga.fylz.appearance

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persisted per-folder look: one [FolderAppearance] record per folder [Uri].
 *
 * [io.github.mbaliga.fylz.canvas.CanvasLayoutStore]-modelled: SharedPreferences with its own file,
 * a per-key `:backup` slot holding the last parseable write so a torn write never loses a whole
 * folder's look, `@Synchronized` methods, `check(editor.commit())`, a schema version stamped on
 * every record, and a [MAX_URI_LENGTH] guard so a malformed or hostile uri never becomes an
 * unbounded preference key. As there, many independent keys (one per folder) rather than one mean
 * eviction has to track write recency itself -- [FOLDER_ORDER_KEY] is that bookkeeping, updated
 * wherever [set] touches a folder and consulted to decide which folder's look is forgotten once
 * [MAX_FOLDERS] is exceeded.
 */
class FolderAppearanceStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun get(folderUri: Uri): FolderAppearance? = decodeForKey(appearanceKey(folderUri))

    /** Upserts [folderUri]'s appearance wholesale -- callers own merging a partial change onto the current record. */
    @Synchronized
    fun set(folderUri: Uri, appearance: FolderAppearance) {
        if (tooLong(folderUri)) return
        val key = appearanceKey(folderUri)
        persist(key, appearance.copy(stickers = appearance.stickers.take(MAX_FOLDER_STICKERS)))
        touchFolder(folderUri)
    }

    /** Drops [folderUri]'s record entirely -- back to whatever the active theme would draw unadorned. */
    @Synchronized
    fun clear(folderUri: Uri) {
        val key = appearanceKey(folderUri)
        commitOrThrow(preferences.edit().remove(key).remove(backupKey(key)))
        val order = folderOrder().toMutableList()
        if (order.remove(folderUri.toString())) persistOrder(order)
    }

    /**
     * Rewrites [old]'s record to live under [new] -- a rename or move carrying a customised folder
     * along with it. Returns whether a record existed to migrate, mirroring
     * [io.github.mbaliga.fylz.canvas.CanvasLayoutStore.migrateUri]'s contract so the relocation
     * fan-out that already calls the canvas and library equivalents can call this the same way.
     */
    @Synchronized
    fun migrateUri(old: Uri, new: Uri): Boolean {
        if (old == new) return false
        val oldKey = appearanceKey(old)
        if (!preferences.contains(oldKey) || tooLong(new)) return false
        val appearance = decodeForKey(oldKey) ?: return false

        persist(appearanceKey(new), appearance)
        commitOrThrow(preferences.edit().remove(oldKey).remove(backupKey(oldKey)))

        val order = folderOrder().toMutableList()
        val index = order.indexOf(old.toString())
        if (index >= 0) {
            order[index] = new.toString()
            persistOrder(order)
        }
        return true
    }

    private fun touchFolder(folderUri: Uri) {
        val order = folderOrder().toMutableList()
        val folderStr = folderUri.toString()
        order.remove(folderStr)
        order.add(folderStr)
        while (order.size > MAX_FOLDERS) {
            val evicted = order.removeAt(0)
            val evictedKey = APPEARANCE_PREFIX + evicted
            commitOrThrow(preferences.edit().remove(evictedKey).remove(backupKey(evictedKey)))
        }
        persistOrder(order)
    }

    private fun folderOrder(): List<String> {
        val raw = preferences.getString(FOLDER_ORDER_KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList { for (index in 0 until array.length()) add(array.getString(index)) }
        }.getOrDefault(emptyList())
    }

    private fun persistOrder(order: List<String>) {
        val array = JSONArray()
        order.forEach { array.put(it) }
        commitOrThrow(preferences.edit().putString(FOLDER_ORDER_KEY, array.toString()))
    }

    private fun persist(key: String, appearance: FolderAppearance) {
        val encoded = encode(appearance)
        val currentRaw = preferences.getString(key, null)
        val editor = preferences.edit().putString(key, encoded)

        // Retain the last parseable record for this folder, same reasoning as CanvasLayoutStore's
        // own backup slot: a partially written or externally corrupted current value must never
        // replace the only known-good appearance.
        if (!currentRaw.isNullOrBlank() && decode(currentRaw) != null) {
            editor.putString(backupKey(key), currentRaw)
        }

        commitOrThrow(editor)
    }

    private fun commitOrThrow(editor: SharedPreferences.Editor) {
        check(editor.commit()) { "Unable to persist the folder appearance." }
    }

    /** Returns null for a missing, blank or malformed record -- there is no meaningful "empty but present" JSON here. */
    private fun decode(raw: String?): FolderAppearance? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val obj = JSONObject(raw)
            FolderAppearance(
                iconKey = obj.optString("iconKey", "").ifBlank { null },
                colorSlug = obj.optString("colorSlug", "").ifBlank { null },
                stickers = obj.optJSONArray("stickers")?.let { array ->
                    buildList { for (index in 0 until array.length()) add(array.getString(index)) }
                }.orEmpty().take(MAX_FOLDER_STICKERS),
            )
        }.getOrNull()
    }

    private fun decodeForKey(key: String): FolderAppearance? =
        decode(preferences.getString(key, null)) ?: decode(preferences.getString(backupKey(key), null))

    private fun encode(appearance: FolderAppearance): String {
        val obj = JSONObject().put("schemaVersion", SCHEMA_VERSION)
        appearance.iconKey?.let { obj.put("iconKey", it) }
        appearance.colorSlug?.let { obj.put("colorSlug", it) }
        if (appearance.stickers.isNotEmpty()) {
            val array = JSONArray()
            appearance.stickers.forEach { array.put(it) }
            obj.put("stickers", array)
        }
        return obj.toString()
    }

    private fun tooLong(uri: Uri): Boolean = uri.toString().length > MAX_URI_LENGTH

    private fun appearanceKey(folderUri: Uri): String = APPEARANCE_PREFIX + folderUri
    private fun backupKey(appearanceKey: String): String = appearanceKey + BACKUP_SUFFIX

    private companion object {
        const val PREFERENCES_NAME = "fylz_folder_appearance"
        const val APPEARANCE_PREFIX = "appearance:"
        const val BACKUP_SUFFIX = ":backup"
        const val FOLDER_ORDER_KEY = "folder_order"
        const val SCHEMA_VERSION = 1
        const val MAX_FOLDERS = 256
        const val MAX_URI_LENGTH = 8_192
    }
}
