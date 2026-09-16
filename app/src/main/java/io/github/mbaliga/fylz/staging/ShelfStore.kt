package io.github.mbaliga.fylz.staging

import android.content.Context
import android.net.Uri
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.core.model.ItemRef
import io.github.mbaliga.fylz.storage.toItemRef
import org.json.JSONArray
import org.json.JSONObject

/**
 * The persistent Shelf: one cross-location tray of [ShelfItem]s that survives restarts.
 *
 * Its own preferences file and its own tiny codec, deliberately not a
 * [io.github.mbaliga.fylz.library.LibraryStore] table -- the Shelf is a queue of items pending
 * an operation, not location metadata. The codec mirrors `OperationJournal`'s: a flat JSON array
 * under one key,
 * each element carrying its own `schemaVersion`, [ItemRef] encoded as the same 3-field object.
 * Persistence keeps `RecycleBinStore`'s last-known-good idiom: [decode] returns null only for a
 * malformed non-empty payload, and the previous parseable write survives under [BACKUP_KEY] so a
 * torn write never loses the whole Shelf.
 */
class ShelfStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun items(): List<ShelfItem> {
        val current = decode(preferences.getString(ITEMS_KEY, null))
        if (current != null) return current
        return decode(preferences.getString(BACKUP_KEY, null)).orEmpty()
    }

    @Synchronized
    fun size(): Int = items().size

    /** Idempotent per [ShelfItem.ref]; returns how many of [additions] were genuinely new. */
    @Synchronized
    fun add(additions: List<ShelfItem>): Int {
        val current = items()
        val aboard = current.mapTo(HashSet()) { it.ref }
        val fresh = additions.filter { aboard.add(it.ref) }
        if (fresh.isEmpty()) return 0
        persist((current + fresh).takeLast(MAX_ITEMS))
        return fresh.size
    }

    @Synchronized
    fun remove(ref: ItemRef) {
        persist(items().filterNot { it.ref == ref })
    }

    /** Batched [remove]: one read/encode/commit for the whole set instead of one per ref. */
    @Synchronized
    fun removeAll(refs: Collection<ItemRef>) {
        if (refs.isEmpty()) return
        val refSet = refs.toHashSet()
        persist(items().filterNot { it.ref in refSet })
    }

    @Synchronized
    fun clear() {
        persist(emptyList())
    }

    /** Wholesale replacement, e.g. after a Shelf-open probe refreshes every member's metadata. */
    @Synchronized
    fun replaceAll(items: List<ShelfItem>) {
        persist(items.takeLast(MAX_ITEMS))
    }

    /**
     * Metadata-only refresh keyed by [ShelfItem.ref], reading the store fresh under the lock
     * right before writing -- a Shelf-open probe can run for a while, and every member the store
     * actually still has when this commits keeps whatever [updates] has for it (or its own
     * fields, unprobed or unchanged); a ref the live read no longer has is never reintroduced.
     * A concurrent [remove]/[removeAll]/[clear]/[migrateRef] mid-probe therefore always wins,
     * unlike [replaceAll] against a snapshot taken when the probe started.
     */
    @Synchronized
    fun refreshMetadata(updates: Map<ItemRef, ShelfItem>) {
        if (updates.isEmpty()) return
        persist(items().map { existing -> updates[existing.ref] ?: existing })
    }

    /**
     * Rewrites one member's [ItemRef] after a provider hands back a new URI for a rename or
     * move; every other field -- including the add-time [ShelfItem.sizeBytes] /
     * [ShelfItem.modifiedAtMillis] stamp -- is left untouched. False when nothing on the Shelf
     * carried [oldUri].
     */
    @Synchronized
    fun migrateRef(oldUri: Uri, newUri: Uri): Boolean {
        val oldRef = oldUri.toItemRef()
        val newRef = newUri.toItemRef()
        if (oldRef == newRef) return false
        val current = items()
        val index = current.indexOfFirst { it.ref == oldRef }
        if (index < 0) return false
        val migrated = current.toMutableList()
        migrated[index] = migrated[index].copy(ref = newRef)
        persist(migrated)
        return true
    }

    private fun persist(items: List<ShelfItem>) {
        val encoded = encode(items)
        val currentRaw = preferences.getString(ITEMS_KEY, null)
        val editor = preferences.edit().putString(ITEMS_KEY, encoded)

        // Retain the last parseable Shelf, exactly as RecycleBinStore does: a partially written
        // or externally corrupted current value must never replace the only known-good manifest.
        if (!currentRaw.isNullOrBlank() && decode(currentRaw) != null) {
            editor.putString(BACKUP_KEY, currentRaw)
        }

        check(editor.commit()) { "Unable to persist the Shelf." }
    }

    private fun encode(items: List<ShelfItem>): String {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("schemaVersion", SCHEMA_VERSION)
                    .put("ref", item.ref.toJson())
                    .put("displayName", item.displayName)
                    .put("kind", item.kind.name)
                    .put("isDirectory", item.isDirectory)
                    .put("sizeBytes", item.sizeBytes)
                    .put("modifiedAtMillis", item.modifiedAtMillis)
                    .put("addedAtMillis", item.addedAtMillis)
                    .put("sourceCrumb", item.sourceCrumb),
            )
        }
        return array.toString()
    }

    private fun ItemRef.toJson(): JSONObject = JSONObject()
        .put("providerId", providerId)
        .put("locationId", locationId)
        .put("opaqueItemId", opaqueItemId)

    /** Returns null only when a non-empty payload is malformed -- mirrors RecycleBinStore. */
    private fun decode(raw: String?): List<ShelfItem>? {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val refJson = item.getJSONObject("ref")
                    add(
                        ShelfItem(
                            ref = ItemRef(
                                providerId = refJson.getString("providerId"),
                                locationId = refJson.getString("locationId"),
                                opaqueItemId = refJson.getString("opaqueItemId"),
                            ),
                            displayName = item.getString("displayName"),
                            kind = EntryKind.valueOf(item.getString("kind")),
                            isDirectory = item.getBoolean("isDirectory"),
                            sizeBytes = item.optLong("sizeBytes", Long.MIN_VALUE)
                                .takeUnless { it == Long.MIN_VALUE },
                            modifiedAtMillis = item.optLong("modifiedAtMillis", Long.MIN_VALUE)
                                .takeUnless { it == Long.MIN_VALUE },
                            addedAtMillis = item.getLong("addedAtMillis"),
                            sourceCrumb = item.optString("sourceCrumb"),
                        ),
                    )
                }
            }
        }.getOrNull()
    }

    private companion object {
        const val PREFERENCES_NAME = "fylz_shelf"
        const val ITEMS_KEY = "shelf_items"
        const val BACKUP_KEY = "shelf_backup"
        const val SCHEMA_VERSION = 1
        const val MAX_ITEMS = 500
    }
}
