package io.github.mbaliga.fylz.browse

import android.net.Uri
import io.github.mbaliga.fylz.model.FolderLocation
import io.github.mbaliga.fylz.model.FolderTab
import io.github.mbaliga.fylz.model.PreviewMode
import io.github.mbaliga.fylz.model.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [encodeSession]/[decodeSession] pinned the same way `PdfPagePlanPolicyTest` opts into
 * Robolectric (see `app/build.gradle.kts`'s own comment on why): both build real
 * [android.net.Uri] instances via `Uri.parse`, which the plain-JVM `android.jar` stub cannot
 * provide a working implementation of, unlike `org.json`'s calls elsewhere in this file, which
 * already work against the real `org.json:json` reference implementation on the test classpath.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionCodecTest {

    private fun tab(id: String, treeUri: String, vararg locations: Pair<String, String>) = FolderTab(
        id = id,
        treeUri = Uri.parse(treeUri),
        locations = locations.map { (uri, name) -> FolderLocation(Uri.parse(uri), name) },
    )

    @Test
    fun `a full session round-trips through encode and decode`() {
        val snapshot = SessionSnapshot(
            tabs = listOf(
                tab("a", "content://fylz/tree/a", "content://fylz/tree/a" to "Root A"),
                tab(
                    "b",
                    "content://fylz/tree/b",
                    "content://fylz/tree/b" to "Root B",
                    "content://fylz/tree/b/document/sub" to "Sub",
                ),
            ),
            activeTabId = "b",
            sortSpec = SortSpec(field = SortField.SIZE, direction = SortDirection.DESCENDING, foldersFirst = false),
            viewMode = ViewMode.GRID,
            previewMode = PreviewMode.FLOATING,
            query = "type:pdf",
            searchRecursive = true,
        )

        val decoded = decodeSession(encodeSession(snapshot))

        assertEquals(snapshot, decoded)
    }

    @Test
    fun `a tab's full navigation stack survives, not just its root`() {
        val snapshot = SessionSnapshot(
            tabs = listOf(
                tab(
                    "a",
                    "content://fylz/tree/a",
                    "content://fylz/tree/a" to "Root",
                    "content://fylz/tree/a/document/x" to "X",
                    "content://fylz/tree/a/document/x%2Fy" to "Y",
                ),
            ),
            activeTabId = "a",
            sortSpec = SortSpec.Default,
            viewMode = ViewMode.LIST,
            previewMode = PreviewMode.DOCKED,
            query = "",
            searchRecursive = false,
        )

        val decoded = decodeSession(encodeSession(snapshot))

        assertEquals(3, decoded?.tabs?.single()?.locations?.size)
        assertEquals("Y", decoded?.tabs?.single()?.current?.name)
    }

    @Test
    fun `no active tab round-trips as null, not the empty-string sentinel`() {
        val snapshot = SessionSnapshot(
            tabs = emptyList(),
            activeTabId = null,
            sortSpec = SortSpec.Default,
            viewMode = ViewMode.LIST,
            previewMode = PreviewMode.DOCKED,
            query = "",
            searchRecursive = false,
        )

        val decoded = decodeSession(encodeSession(snapshot))

        assertNull(decoded?.activeTabId)
    }

    @Test
    fun `malformed json decodes to null rather than throwing`() {
        assertNull(decodeSession("not json at all"))
        assertNull(decodeSession("{}"))
        assertNull(decodeSession("""{"tabs": "not an array"}"""))
    }

    @Test
    fun `a tab with no locations is dropped -- FolderTab requires at least one`() {
        val json = """
            {
              "tabs": [
                {"id": "empty", "treeUri": "content://fylz/tree/a", "locations": []},
                {"id": "real", "treeUri": "content://fylz/tree/b", "locations": [
                  {"uri": "content://fylz/tree/b", "name": "Root B"}
                ]}
              ],
              "activeTabId": "real",
              "sortField": "NAME", "sortDirection": "ASCENDING", "sortFoldersFirst": true,
              "viewMode": "LIST", "previewMode": "DOCKED",
              "query": "", "searchRecursive": false
            }
        """.trimIndent()

        val decoded = decodeSession(json)

        assertEquals(listOf("real"), decoded?.tabs?.map { it.id })
    }

    @Test
    fun `an unrecognised enum value falls back to its default rather than failing the whole decode`() {
        val json = """
            {
              "tabs": [],
              "activeTabId": "",
              "sortField": "SOME_FUTURE_FIELD", "sortDirection": "ASCENDING", "sortFoldersFirst": true,
              "viewMode": "SOME_FUTURE_MODE", "previewMode": "DOCKED",
              "query": "", "searchRecursive": false
            }
        """.trimIndent()

        val decoded = decodeSession(json)

        assertEquals(SortField.NAME, decoded?.sortSpec?.field)
        assertEquals(ViewMode.LIST, decoded?.viewMode)
    }

    @Test
    fun `tab order is preserved`() {
        val snapshot = SessionSnapshot(
            tabs = listOf(
                tab("a", "content://fylz/tree/a", "content://fylz/tree/a" to "A"),
                tab("b", "content://fylz/tree/b", "content://fylz/tree/b" to "B"),
                tab("c", "content://fylz/tree/c", "content://fylz/tree/c" to "C"),
            ),
            activeTabId = "b",
            sortSpec = SortSpec.Default,
            viewMode = ViewMode.LIST,
            previewMode = PreviewMode.DOCKED,
            query = "",
            searchRecursive = false,
        )

        val decoded = decodeSession(encodeSession(snapshot))

        assertEquals(listOf("a", "b", "c"), decoded?.tabs?.map { it.id })
        assertEquals("b", decoded?.activeTabId)
    }
}
