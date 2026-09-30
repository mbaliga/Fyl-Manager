package io.github.mbaliga.fylz.browse

import android.net.Uri
import io.github.mbaliga.fylz.model.FolderLocation
import io.github.mbaliga.fylz.model.FolderTab
import io.github.mbaliga.fylz.model.PreviewMode
import io.github.mbaliga.fylz.model.ViewMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * What [io.github.mbaliga.fylz.ui.BrowserViewModel] persists and restores (P1.10) -- every tab's
 * full navigation stack, not just its root the way the old `OpenTabsStore` (P0.10, removed by this
 * task) recorded, plus the active tab and the browser-wide preferences that used to reset on every
 * rotation. The clipboard is deliberately not part of this snapshot -- see
 * [io.github.mbaliga.fylz.ui.BrowserViewModel]'s own KDoc for why.
 */
internal data class SessionSnapshot(
    val tabs: List<FolderTab>,
    val activeTabId: String?,
    val sortSpec: SortSpec,
    val viewMode: ViewMode,
    val previewMode: PreviewMode,
    val query: String,
    val searchRecursive: Boolean,
)

/**
 * Pure JSON encode/decode for [SessionSnapshot] -- no [android.content.Context], so it is unit
 * testable without Robolectric, the same way the old `OpenTabsStore`'s own JSON handling was
 * (`Uri.parse`/`Uri.toString` and `org.json` both work against the `org.json:json` reference
 * implementation this project already runs plain-JVM tests against).
 */
internal fun encodeSession(snapshot: SessionSnapshot): String {
    val tabsArray = JSONArray()
    snapshot.tabs.forEach { tab ->
        val locationsArray = JSONArray()
        tab.locations.forEach { location ->
            locationsArray.put(
                JSONObject().apply {
                    put("uri", location.uri.toString())
                    put("name", location.name)
                },
            )
        }
        tabsArray.put(
            JSONObject().apply {
                put("id", tab.id)
                put("treeUri", tab.treeUri.toString())
                put("locations", locationsArray)
            },
        )
    }
    return JSONObject()
        .apply {
            put("tabs", tabsArray)
            put("activeTabId", snapshot.activeTabId ?: "")
            put("sortField", snapshot.sortSpec.field.name)
            put("sortDirection", snapshot.sortSpec.direction.name)
            put("sortFoldersFirst", snapshot.sortSpec.foldersFirst)
            put("viewMode", snapshot.viewMode.name)
            put("previewMode", snapshot.previewMode.name)
            put("query", snapshot.query)
            put("searchRecursive", snapshot.searchRecursive)
        }
        .toString()
}

/**
 * Null on anything unparsable -- a corrupt record, a future/foreign format -- so a bad session
 * never crashes restoration, only skips it, the same fail-quiet contract the old `OpenTabsStore`
 * had. A tab whose [FolderTab.locations] came back empty is dropped rather than kept, since
 * [FolderTab.current] requires at least one location and a tab restored with none would crash the
 * very first composable that reads it.
 */
internal fun decodeSession(json: String): SessionSnapshot? = runCatching {
    val root = JSONObject(json)
    val tabsArray = root.getJSONArray("tabs")
    val tabs = buildList {
        for (index in 0 until tabsArray.length()) {
            val tabObject = tabsArray.getJSONObject(index)
            val locationsArray = tabObject.getJSONArray("locations")
            val locations = buildList {
                for (locationIndex in 0 until locationsArray.length()) {
                    val locationObject = locationsArray.getJSONObject(locationIndex)
                    add(
                        FolderLocation(
                            uri = Uri.parse(locationObject.getString("uri")),
                            name = locationObject.getString("name"),
                        ),
                    )
                }
            }
            if (locations.isEmpty()) continue
            add(
                FolderTab(
                    id = tabObject.getString("id"),
                    treeUri = Uri.parse(tabObject.getString("treeUri")),
                    locations = locations,
                ),
            )
        }
    }
    SessionSnapshot(
        tabs = tabs,
        activeTabId = root.optString("activeTabId", "").ifEmpty { null },
        sortSpec = SortSpec(
            field = runCatching { SortField.valueOf(root.getString("sortField")) }
                .getOrDefault(SortField.NAME),
            direction = runCatching { SortDirection.valueOf(root.getString("sortDirection")) }
                .getOrDefault(SortDirection.ASCENDING),
            foldersFirst = root.optBoolean("sortFoldersFirst", true),
        ),
        viewMode = runCatching { ViewMode.valueOf(root.getString("viewMode")) }
            .getOrDefault(ViewMode.LIST),
        previewMode = runCatching { PreviewMode.valueOf(root.getString("previewMode")) }
            .getOrDefault(PreviewMode.DOCKED),
        query = root.optString("query", ""),
        searchRecursive = root.optBoolean("searchRecursive", false),
    )
}.getOrNull()
