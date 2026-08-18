package io.github.mbaliga.fylz.canvas

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** One tile's spot on the canvas: [x]/[y] are fractions of the viewport, clamped [0,1] both ends. */
data class TilePlacement(val x: Float, val y: Float, val z: Int)

/**
 * Persisted freeform-canvas geometry, one location at a time.
 *
 * [ShelfStore]-modelled: SharedPreferences with its own file, a flat JSON array per key, a
 * per-key `:backup` slot holding the last parseable write so a torn write never loses a whole
 * location's layout, `@Synchronized` methods, `check(editor.commit())`. The one thing ShelfStore
 * does not need and this does: many independent keys (one per location) rather than one, so
 * eviction has to track write recency itself -- [LOCATION_ORDER_KEY] is that bookkeeping, a
 * JSON array of location URIs oldest-written first, updated wherever [place] touches a location
 * and consulted to decide which location's layout is forgotten once [MAX_LOCATIONS] is exceeded.
 */
class CanvasLayoutStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun placements(locationUri: Uri): Map<Uri, TilePlacement> =
        decodeForKey(layoutKey(locationUri)).associate { it.uri to it.placement }

    /** Upserts one tile's placement for [locationUri]. Called on drag end only -- never per-frame. */
    @Synchronized
    fun place(locationUri: Uri, uri: Uri, placement: TilePlacement) {
        if (tooLong(locationUri) || tooLong(uri)) return
        val key = layoutKey(locationUri)
        val clamped = placement.copy(x = placement.x.coerceIn(0f, 1f), y = placement.y.coerceIn(0f, 1f))
        // Re-read under this lock, not a snapshot the caller might be holding stale: a prune or a
        // migrate landing between the caller's decision and this call must not be clobbered.
        val current = decodeForKey(key).toMutableList()
        current.removeAll { it.uri == uri }
        current += TileRecord(uri, clamped)
        val cap = CanvasLayoutPolicy.MAX_TILES
        val bounded = if (current.size > cap) current.takeLast(cap) else current
        persist(key, bounded)
        touchLocation(locationUri)
    }

    /** Drops every stored placement whose uri is not in [present] -- re-reads fresh under the lock. */
    @Synchronized
    fun prune(locationUri: Uri, present: Set<Uri>) {
        val key = layoutKey(locationUri)
        val current = decodeForKey(key)
        val kept = current.filter { it.uri in present }
        if (kept.size != current.size) persist(key, kept)
    }

    /**
     * Rewrites [old] to [new] wherever it appears: as a tile's own uri inside any location's
     * layout (a rename or move of a placed item), and as a location key itself (the subject
     * folder moved). Both can be true for the same call only in principle -- a location uri and
     * a tile uri live in different namespaces in practice -- so both checks always run. Returns
     * whether anything changed.
     */
    @Synchronized
    fun migrateUri(old: Uri, new: Uri): Boolean {
        if (old == new) return false
        var changed = false

        val oldLocationKey = layoutKey(old)
        if (preferences.contains(oldLocationKey) && !tooLong(new)) {
            val records = decodeForKey(oldLocationKey)
            persist(layoutKey(new), records)
            commitOrThrow(preferences.edit().remove(oldLocationKey).remove(backupKey(oldLocationKey)))
            val order = locationOrder().toMutableList()
            val index = order.indexOf(old.toString())
            if (index >= 0) {
                order[index] = new.toString()
                persistOrder(order)
            }
            changed = true
        }

        if (!tooLong(new)) {
            locationOrder().forEach { locationStr ->
                val key = LAYOUT_PREFIX + locationStr
                val current = decodeForKey(key)
                val index = current.indexOfFirst { it.uri == old }
                if (index >= 0) {
                    val migrated = current.toMutableList()
                    migrated[index] = migrated[index].copy(uri = new)
                    persist(key, migrated)
                    changed = true
                }
            }
        }

        return changed
    }

    /** Forgets a location's layout entirely -- e.g. the subject was cleared or its grant revoked. */
    @Synchronized
    fun clear(locationUri: Uri) {
        val key = layoutKey(locationUri)
        commitOrThrow(preferences.edit().remove(key).remove(backupKey(key)))
        val order = locationOrder().toMutableList()
        if (order.remove(locationUri.toString())) persistOrder(order)
    }

    private fun touchLocation(locationUri: Uri) {
        val order = locationOrder().toMutableList()
        val locationStr = locationUri.toString()
        order.remove(locationStr)
        order.add(locationStr)
        while (order.size > MAX_LOCATIONS) {
            val evicted = order.removeAt(0)
            val evictedKey = LAYOUT_PREFIX + evicted
            commitOrThrow(preferences.edit().remove(evictedKey).remove(backupKey(evictedKey)))
        }
        persistOrder(order)
    }

    private fun locationOrder(): List<String> {
        val raw = preferences.getString(LOCATION_ORDER_KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList { for (index in 0 until array.length()) add(array.getString(index)) }
        }.getOrDefault(emptyList())
    }

    private fun persistOrder(order: List<String>) {
        val array = JSONArray()
        order.forEach { array.put(it) }
        commitOrThrow(preferences.edit().putString(LOCATION_ORDER_KEY, array.toString()))
    }

    private fun persist(key: String, records: List<TileRecord>) {
        val encoded = encode(records)
        val currentRaw = preferences.getString(key, null)
        val editor = preferences.edit().putString(key, encoded)

        // Retain the last parseable layout for this location, exactly as ShelfStore retains the
        // Shelf's: a partially written or externally corrupted current value must never replace
        // the only known-good manifest.
        if (!currentRaw.isNullOrBlank() && decode(currentRaw) != null) {
            editor.putString(backupKey(key), currentRaw)
        }

        commitOrThrow(editor)
    }

    private fun commitOrThrow(editor: SharedPreferences.Editor) {
        check(editor.commit()) { "Unable to persist the canvas layout." }
    }

    /** Returns null only when a non-empty payload is malformed -- mirrors ShelfStore. */
    private fun decode(raw: String?): List<TileRecord>? {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        TileRecord(
                            uri = Uri.parse(item.getString("uri")),
                            placement = TilePlacement(
                                x = item.getDouble("x").toFloat().coerceIn(0f, 1f),
                                y = item.getDouble("y").toFloat().coerceIn(0f, 1f),
                                z = item.getInt("z"),
                            ),
                        ),
                    )
                }
            }
        }.getOrNull()
    }

    private fun decodeForKey(key: String): List<TileRecord> {
        val current = decode(preferences.getString(key, null))
        if (current != null) return current
        return decode(preferences.getString(backupKey(key), null)).orEmpty()
    }

    private fun encode(records: List<TileRecord>): String {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject()
                    .put("schemaVersion", SCHEMA_VERSION)
                    .put("uri", record.uri.toString())
                    .put("x", record.placement.x.toDouble())
                    .put("y", record.placement.y.toDouble())
                    .put("z", record.placement.z),
            )
        }
        return array.toString()
    }

    private fun tooLong(uri: Uri): Boolean = uri.toString().length > MAX_URI_LENGTH

    private fun layoutKey(locationUri: Uri): String = LAYOUT_PREFIX + locationUri
    private fun backupKey(layoutKey: String): String = layoutKey + BACKUP_SUFFIX

    private data class TileRecord(val uri: Uri, val placement: TilePlacement)

    private companion object {
        const val PREFERENCES_NAME = "fylz_canvas"
        const val LAYOUT_PREFIX = "layout:"
        const val BACKUP_SUFFIX = ":backup"
        const val LOCATION_ORDER_KEY = "location_order"
        const val SCHEMA_VERSION = 1
        const val MAX_LOCATIONS = 64
        const val MAX_URI_LENGTH = 8_192
    }
}
