package io.github.mbaliga.fylz.index

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalIndexStoreTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var store: LocalIndexStore

    @Before
    fun freshStore() {
        store = LocalIndexStore(context)
        store.clearFiles()
        store.collections().forEach { store.removeCollection(it.id) }
    }

    private fun file(name: String, textSample: String? = null, tags: Set<String> = emptySet()) = IndexedFile(
        uri = "content://fake/$name",
        rootUri = ROOT,
        name = name,
        mimeType = "application/octet-stream",
        extension = name.substringAfterLast('.', ""),
        sizeBytes = 10L,
        modifiedAtMillis = null,
        directory = false,
        tags = tags,
        textSample = textSample,
    )

    @Test
    fun `a plain search matches the sampled document text, not just names`() {
        store.replaceFiles(
            ROOT,
            listOf(
                file("report.pdf", textSample = "Quarterly invoice total: 1,204.00"),
                file("holiday.jpg"),
            ),
        )

        assertEquals(listOf("report.pdf"), store.query("invoice").map { it.name })
        assertTrue(store.query("nothing-here").isEmpty())
    }

    @Test
    fun `names and tags still match on their own`() {
        store.replaceFiles(ROOT, listOf(file("budget.xlsx"), file("notes.txt", tags = setOf("finance"))))

        assertEquals(listOf("budget.xlsx"), store.query("budget").map { it.name })
        assertEquals(listOf("notes.txt"), store.query("finance").map { it.name })
    }

    @Test
    fun `an applied collection filters the results by its own rules`() {
        store.replaceFiles(
            ROOT,
            listOf(
                file("a.pdf", textSample = "signed contract"),
                file("b.pdf", textSample = "shopping list"),
            ),
        )
        val collection = SmartCollection(
            name = "Contracts",
            rules = listOf(SmartRule(RuleField.TEXT_CONTENT, RuleOperator.CONTAINS, "contract")),
        )
        store.putCollection(collection)

        assertEquals(listOf("a.pdf"), store.query("", collection.id).map { it.name })
        assertEquals(listOf("a.pdf", "b.pdf"), store.query("").map { it.name })
    }

    @Test
    fun `contentSnippet frames the first hit and marks trimmed ends`() {
        val sample = "x".repeat(100) + " the INVOICE is due " + "y".repeat(100)

        val snippet = contentSnippet(sample, "invoice", radius = 8)

        requireNotNull(snippet)
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
        assertTrue(snippet.contains("INVOICE"))
        assertTrue(snippet.length < 40)
    }

    @Test
    fun `contentSnippet is null without a query, a sample or a hit`() {
        assertNull(contentSnippet("some text", ""))
        assertNull(contentSnippet(null, "text"))
        assertNull(contentSnippet("some text", "missing"))
        assertEquals("some text", contentSnippet("some text", "text"))
    }

    private companion object {
        const val ROOT = "content://fake/root"
    }
}
