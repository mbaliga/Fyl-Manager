package io.github.mbaliga.fylz.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P1.12: [LocalIndexStore] against the real SQLite-backed [io.github.mbaliga.fylz.data.FylzDatabase],
 * not a fake -- its public API is unchanged from the pre-P1.12 JSON-backed version (see its own
 * class doc), so these pin the exact same behaviour
 * [io.github.mbaliga.fylz.IndexManagerActivity]/[io.github.mbaliga.fylz.PostV1ToolsActivity]
 * already depend on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalIndexStoreTest {

    private fun file(uri: String, name: String, path: String = name, tags: Set<String> = emptySet()) = IndexedFile(
        uri = uri,
        rootUri = "content://docs/root",
        parentUri = "content://docs/root",
        path = path,
        name = name,
        mimeType = "application/octet-stream",
        extension = name.substringAfterLast('.', ""),
        sizeBytes = 10L,
        modifiedAtMillis = 1_000L,
        directory = false,
        tags = tags,
    )

    @Test
    fun `replaceFiles replaces only the given root's own rows`() {
        val store = LocalIndexStore(RuntimeEnvironment.getApplication())
        store.replaceFiles("content://docs/root", listOf(file("content://docs/root/a", "a.txt")))
        val other = file("content://other/b", "b.txt").copy(rootUri = "content://other")
        store.replaceFiles("content://other", listOf(other))

        store.replaceFiles("content://docs/root", listOf(file("content://docs/root/c", "c.txt")))

        val names = store.files().map { it.name }.toSet()
        assertEquals(setOf("c.txt", "b.txt"), names)
    }

    @Test
    fun `removeRoot deletes both the root's files and its scope`() {
        val store = LocalIndexStore(RuntimeEnvironment.getApplication())
        store.putScope(IndexScope("content://docs/root", "Docs"))
        store.replaceFiles("content://docs/root", listOf(file("content://docs/root/a", "a.txt")))

        store.removeRoot("content://docs/root")

        assertTrue(store.files().isEmpty())
        assertTrue(store.scopes().isEmpty())
    }

    @Test
    fun `clearFiles empties the index but keeps scopes`() {
        val store = LocalIndexStore(RuntimeEnvironment.getApplication())
        store.putScope(IndexScope("content://docs/root", "Docs"))
        store.replaceFiles("content://docs/root", listOf(file("content://docs/root/a", "a.txt")))

        store.clearFiles()

        assertTrue(store.files().isEmpty())
        assertEquals(1, store.scopes().size)
    }

    @Test
    fun `query does substring matching across name, extension and tags`() {
        val store = LocalIndexStore(RuntimeEnvironment.getApplication())
        store.replaceFiles(
            "content://docs/root",
            listOf(
                file("content://docs/root/report", "Quarterly report.pdf", tags = setOf("Finance")),
                file("content://docs/root/photo", "beach.jpg"),
            ),
        )

        // Substring, not FTS token matching: "port" must still match "report.pdf".
        assertEquals(listOf("Quarterly report.pdf"), store.query("port").map { it.name })
        assertEquals(listOf("Quarterly report.pdf"), store.query("finance").map { it.name })
        assertEquals(listOf("Quarterly report.pdf"), store.query("pdf").map { it.name })
        assertEquals(2, store.query("").size)
        assertTrue(store.query("nonexistent").isEmpty())
    }

    @Test
    fun `query applies a smart collection filter on top of the text match`() {
        val store = LocalIndexStore(RuntimeEnvironment.getApplication())
        store.replaceFiles(
            "content://docs/root",
            listOf(
                file("content://docs/root/a", "a.pdf"),
                file("content://docs/root/b", "b.txt"),
            ),
        )
        val collection = SmartCollection(
            name = "PDFs",
            rules = listOf(SmartRule(RuleField.EXTENSION, RuleOperator.EQUALS, "pdf")),
        )
        store.putCollection(collection)

        val results = store.query(collectionId = collection.id)

        assertEquals(listOf("a.pdf"), results.map { it.name })
    }

    @Test
    fun `putScope upserts by rootUri`() {
        val store = LocalIndexStore(RuntimeEnvironment.getApplication())
        store.putScope(IndexScope("content://docs/root", "Docs", enabled = true))
        store.putScope(IndexScope("content://docs/root", "Docs (renamed)", enabled = false))

        val scope = store.scopes().single()
        assertEquals("Docs (renamed)", scope.displayName)
        assertFalse(scope.enabled)
    }

    @Test
    fun `state round-trips and setPaused toggles only the paused flag`() {
        val store = LocalIndexStore(RuntimeEnvironment.getApplication())
        store.putState(IndexState(indexedFiles = 42, lastError = "boom"))

        store.setPaused(true)

        val state = store.state()
        assertTrue(state.paused)
        assertEquals(42, state.indexedFiles)
        assertEquals("boom", state.lastError)
    }
}
