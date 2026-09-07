package io.github.mbaliga.fylz.desktop

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import io.github.mbaliga.fylz.canvas.TilePlacement
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persisted desktop layout: every [DesktopItem] the user has placed, one flat list.
 *
 * [io.github.mbaliga.fylz.canvas.CanvasLayoutStore]-modelled: its own SharedPreferences file, a
 * single JSON-array key plus a `:backup` slot holding the last parseable write so a torn write
 * never loses the whole desktop, `@Synchronized` methods, `check(editor.commit())`. Unlike
 * [io.github.mbaliga.fylz.canvas.CanvasLayoutStore] there is only ever one list (one desktop, not
 * one layout per location), so there is no location-eviction bookkeeping to speak of -- [MAX_ITEMS]
 * bounds the one list directly, oldest item dropped first, same as that store's per-location cap.
 *
 * Placements persist as x/y FRACTIONS of the live grid ([DesktopPolicy.WORLD_MIN_HEIGHT_DP] for y,
 * the real viewport width for x), not absolute dp -- so a change to either basis (Build 11.5 moved
 * both: [DesktopPolicy.WORLD_MIN_HEIGHT_DP] and the widget column x formulas) re-anchors any
 * already-persisted fraction against a new grid the next time it renders, drifting visually until
 * the user drags the item once (which runs it through [DesktopPolicy.clampWidget]/`snapWidget` and
 * re-settles it). No migration re-clamps a stored placement on load today, and [SCHEMA_VERSION] is
 * write-only -- deliberately so, not an oversight: per this repo's own README/CHANGELOG, no build
 * up to and including Build 11.5 has ever shipped through a channel that would leave persisted
 * desktop data on a real device, so there is nothing yet for a migration to correct. Add one (bump
 * [SCHEMA_VERSION], re-run every loaded placement through `clampWidget`/`snapWidget` once) before
 * the first release build that could carry a user's desktop layout across a geometry-basis change
 * like this one.
 */
class DesktopStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun items(): List<DesktopItem> = decodeCurrent()

    /** Upserts by [DesktopItem.id]; past [DesktopPolicy.MAX_ITEMS] the oldest item is dropped --
     *  reading never drops one, only this write path does. */
    @Synchronized
    fun upsert(item: DesktopItem) {
        val current = decodeCurrent().toMutableList()
        current.removeAll { it.id == item.id }
        current += item
        val cap = DesktopPolicy.MAX_ITEMS
        val bounded = if (current.size > cap) current.takeLast(cap) else current
        persist(bounded)
    }

    @Synchronized
    fun remove(id: String) {
        val current = decodeCurrent()
        val kept = current.filterNot { it.id == id }
        if (kept.size != current.size) persist(kept)
    }

    /** Moves one existing item; a [id] with nothing on the desktop is a silent no-op. */
    @Synchronized
    fun place(id: String, placement: TilePlacement) {
        val current = decodeCurrent()
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return
        val updated = current.toMutableList()
        updated[index] = updated[index].withPlacement(placement)
        persist(updated)
    }

    /** Wholesale replace -- e.g. a drag-reorder pass that touched many items at once. Still bound
     *  by [DesktopPolicy.MAX_ITEMS], trimming from the end of [items] if it is over cap. */
    @Synchronized
    fun replaceAll(items: List<DesktopItem>) {
        val cap = DesktopPolicy.MAX_ITEMS
        persist(if (items.size > cap) items.take(cap) else items)
    }

    /**
     * Rewrites [old] to [new] wherever it appears: a shortcut's own uri/treeUri/folderUri, or a
     * QUICK_ACCESS widget's `"tree:<treeUri>|folder:<folderUri>"` config target. Returns whether
     * anything changed.
     */
    @Synchronized
    fun migrateUri(old: Uri, new: Uri): Boolean {
        if (old == new) return false
        val current = decodeCurrent()
        var changed = false
        val migrated = current.map { item ->
            val next = migrateItem(item, old, new)
            if (next != null) {
                changed = true
                next
            } else {
                item
            }
        }
        if (changed) persist(migrated)
        return changed
    }

    /**
     * Writes [seed] the one time the desktop has never been decided one way or the other. The
     * first call always resolves that question -- whether it seeds (desktop was empty) or not
     * (items already existed) -- and marks [SEEDED_KEY] so no later call reseeds a desktop the
     * user has since emptied on purpose.
     */
    @Synchronized
    fun seedIfEmpty(seed: List<DesktopItem>): Boolean {
        if (preferences.getBoolean(SEEDED_KEY, false)) return false
        val seeded = decodeCurrent().isEmpty()
        if (seeded) persist(seed)
        commitOrThrow(preferences.edit().putBoolean(SEEDED_KEY, true))
        return seeded
    }

    private fun migrateItem(item: DesktopItem, old: Uri, new: Uri): DesktopItem? = when (item) {
        is DesktopItem.FolderShortcut -> {
            val treeChanged = item.treeUri == old
            val folderChanged = item.folderUri == old
            if (!treeChanged && !folderChanged) {
                null
            } else {
                item.copy(
                    treeUri = if (treeChanged) new else item.treeUri,
                    folderUri = if (folderChanged) new else item.folderUri,
                )
            }
        }
        is DesktopItem.FileShortcut -> {
            val uriChanged = item.uri == old
            val treeChanged = item.treeUri == old
            if (!uriChanged && !treeChanged) {
                null
            } else {
                item.copy(
                    uri = if (uriChanged) new else item.uri,
                    treeUri = if (treeChanged) new else item.treeUri,
                )
            }
        }
        is DesktopItem.Widget -> migrateWidgetConfig(item, old, new)
    }

    private fun migrateWidgetConfig(item: DesktopItem.Widget, old: Uri, new: Uri): DesktopItem.Widget? {
        if (item.type != DesktopWidgetType.QUICK_ACCESS) return null
        val target = item.config[QUICK_ACCESS_TARGET_KEY] ?: return null
        val migratedTarget = migrateQuickAccessTarget(target, old, new) ?: return null
        return item.copy(config = item.config + (QUICK_ACCESS_TARGET_KEY to migratedTarget))
    }

    /** Parses `"tree:<treeUri>|folder:<folderUri>"`, rewrites either half that matches [old], and
     *  re-serializes -- returns null when [target] is not that shape, or neither half matches. */
    private fun migrateQuickAccessTarget(target: String, old: Uri, new: Uri): String? {
        if (!target.startsWith(QUICK_ACCESS_TREE_PREFIX)) return null
        val body = target.removePrefix(QUICK_ACCESS_TREE_PREFIX)
        val markerIndex = body.indexOf(QUICK_ACCESS_FOLDER_MARKER)
        if (markerIndex < 0) return null
        val treeUri = Uri.parse(body.substring(0, markerIndex))
        val folderUri = Uri.parse(body.substring(markerIndex + QUICK_ACCESS_FOLDER_MARKER.length))
        val treeChanged = treeUri == old
        val folderChanged = folderUri == old
        if (!treeChanged && !folderChanged) return null
        val rewrittenTree = if (treeChanged) new else treeUri
        val rewrittenFolder = if (folderChanged) new else folderUri
        return "$QUICK_ACCESS_TREE_PREFIX$rewrittenTree$QUICK_ACCESS_FOLDER_MARKER$rewrittenFolder"
    }

    private fun DesktopItem.withPlacement(placement: TilePlacement): DesktopItem = when (this) {
        is DesktopItem.FolderShortcut -> copy(placement = placement)
        is DesktopItem.FileShortcut -> copy(placement = placement)
        is DesktopItem.Widget -> copy(placement = placement)
    }

    private fun decodeCurrent(): List<DesktopItem> {
        val current = decode(preferences.getString(ITEMS_KEY, null))
        if (current != null) return current
        return decode(preferences.getString(BACKUP_KEY, null)).orEmpty()
    }

    private fun persist(items: List<DesktopItem>) {
        val encoded = encode(items)
        val currentRaw = preferences.getString(ITEMS_KEY, null)
        val editor = preferences.edit().putString(ITEMS_KEY, encoded)

        // Retain the last parseable list, exactly as CanvasLayoutStore retains a location's: a
        // partially written or externally corrupted current value must never replace the only
        // known-good manifest.
        if (!currentRaw.isNullOrBlank() && decode(currentRaw) != null) {
            editor.putString(BACKUP_KEY, currentRaw)
        }

        commitOrThrow(editor)
    }

    private fun commitOrThrow(editor: SharedPreferences.Editor) {
        check(editor.commit()) { "Unable to persist the desktop." }
    }

    /** Returns null only when a non-empty payload is malformed at the top level -- an individual
     *  malformed record inside an otherwise-valid array is skipped, never fatal to the rest. */
    private fun decode(raw: String?): List<DesktopItem>? {
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

    private fun decodeRecord(record: JSONObject): DesktopItem? = runCatching {
        val id = record.getString("id")
        val placement = TilePlacement(
            x = record.getDouble("x").toFloat(),
            y = record.getDouble("y").toFloat(),
            z = record.getInt("z"),
        )
        when (record.getString("kind")) {
            KIND_FOLDER -> DesktopItem.FolderShortcut(
                id = id,
                treeUri = Uri.parse(record.getString("treeUri")),
                folderUri = Uri.parse(record.getString("folderUri")),
                displayName = record.getString("displayName"),
                placement = placement,
            )
            KIND_FILE -> DesktopItem.FileShortcut(
                id = id,
                uri = Uri.parse(record.getString("uri")),
                treeUri = record.optStringOrNull("treeUri")?.let { Uri.parse(it) },
                displayName = record.getString("displayName"),
                placement = placement,
            )
            KIND_WIDGET -> {
                val type = DesktopWidgetType.fromId(record.getString("widgetType"))
                if (type == null) {
                    null
                } else {
                    val size = runCatching { DesktopItemSize.valueOf(record.getString("size")) }
                        .getOrDefault(DesktopItemSize.MEDIUM)
                    DesktopItem.Widget(
                        id = id,
                        type = type,
                        size = size,
                        config = record.optJSONObject("config").toStringMap(),
                        placement = placement,
                    )
                }
            }
            else -> null
        }
    }.getOrNull()

    private fun encode(items: List<DesktopItem>): String {
        val array = JSONArray()
        items.forEach { item -> array.put(encodeRecord(item)) }
        return array.toString()
    }

    private fun encodeRecord(item: DesktopItem): JSONObject {
        val record = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("id", item.id)
            .put("x", item.placement.x.toDouble())
            .put("y", item.placement.y.toDouble())
            .put("z", item.placement.z)
        when (item) {
            is DesktopItem.FolderShortcut -> record
                .put("kind", KIND_FOLDER)
                .put("treeUri", item.treeUri.toString())
                .put("folderUri", item.folderUri.toString())
                .put("displayName", item.displayName)
            is DesktopItem.FileShortcut -> {
                record.put("kind", KIND_FILE)
                    .put("uri", item.uri.toString())
                    .put("displayName", item.displayName)
                if (item.treeUri != null) record.put("treeUri", item.treeUri.toString())
            }
            is DesktopItem.Widget -> {
                val config = JSONObject()
                item.config.forEach { (key, value) -> config.put(key, value) }
                record.put("kind", KIND_WIDGET)
                    .put("widgetType", item.type.id)
                    .put("size", item.size.name)
                    .put("config", config)
            }
        }
        return record
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        val result = LinkedHashMap<String, String>()
        val keys = keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = optString(key, "")
        }
        return result
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (has(name) && !isNull(name)) getString(name) else null

    private companion object {
        const val PREFERENCES_NAME = "fylz_desktop"
        const val ITEMS_KEY = "items"
        const val BACKUP_SUFFIX = ":backup"
        const val BACKUP_KEY = ITEMS_KEY + BACKUP_SUFFIX
        const val SEEDED_KEY = "seeded"
        const val SCHEMA_VERSION = 1

        const val KIND_FOLDER = "folder"
        const val KIND_FILE = "file"
        const val KIND_WIDGET = "widget"

        const val QUICK_ACCESS_TARGET_KEY = "target"
        const val QUICK_ACCESS_TREE_PREFIX = "tree:"
        const val QUICK_ACCESS_FOLDER_MARKER = "|folder:"
    }
}
