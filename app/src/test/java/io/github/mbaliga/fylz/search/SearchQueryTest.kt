package io.github.mbaliga.fylz.search

import android.net.Uri
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Search used to be `entries.filter { it.name.contains(query, true) }` over one folder. These
 * cover the query language that replaced it; the recursive walk itself needs a real
 * `ContentResolver` and is exercised on device instead.
 */
@RunWith(RobolectricTestRunner::class)
class SearchQueryTest {

    private fun entry(
        name: String,
        kind: EntryKind = EntryKind.TEXT,
        size: Long? = 1_000,
        mime: String = "text/plain",
    ) = FileEntry(
        uri = Uri.parse("content://test/$name"),
        name = name,
        mimeType = mime,
        sizeBytes = size,
        lastModifiedMillis = 0,
        flags = 0,
        kind = kind,
    )

    @Test
    fun `blank query is empty and matches nothing`() {
        assertTrue(SearchQuery.parse("   ").isEmpty)
    }

    @Test
    fun `free text terms all have to appear in the name`() {
        val query = SearchQuery.parse("report final")
        assertTrue(query.matchesName("final-report.pdf"))
        assertFalse(query.matchesName("report.pdf"))
    }

    @Test
    fun `name matching is case insensitive`() {
        assertTrue(SearchQuery.parse("REPORT").matchesName("quarterly report.txt"))
    }

    @Test
    fun `ext filter accepts a comma separated list and tolerates leading dots`() {
        val query = SearchQuery.parse("ext:.pdf,PNG")
        assertEquals(setOf("pdf", "png"), query.extensions)
        assertTrue(query.matchesMetadata(entry("a.pdf")))
        assertTrue(query.matchesMetadata(entry("b.PNG")))
        assertFalse(query.matchesMetadata(entry("c.txt")))
    }

    @Test
    fun `type filter maps friendly aliases onto EntryKind`() {
        assertEquals(setOf(EntryKind.IMAGE), SearchQuery.parse("type:photo").kinds)
        assertEquals(setOf(EntryKind.DIRECTORY), SearchQuery.parse("type:folder").kinds)
        assertEquals(setOf(EntryKind.ARCHIVE), SearchQuery.parse("type:zip").kinds)
        // An unknown alias is dropped rather than making the whole query fail.
        assertTrue(SearchQuery.parse("type:widget").kinds.isEmpty())
    }

    @Test
    fun `size bounds parse units and apply the right direction`() {
        val big = SearchQuery.parse("size:>10mb")
        assertEquals(10L * 1_024 * 1_024, big.minSizeBytes)
        assertTrue(big.matchesMetadata(entry("x", size = 20L * 1_024 * 1_024)))
        assertFalse(big.matchesMetadata(entry("x", size = 5L * 1_024 * 1_024)))

        val small = SearchQuery.parse("size:<500kb")
        assertEquals(500L * 1_024, small.maxSizeBytes)
        assertTrue(small.matchesMetadata(entry("x", size = 1_000)))
        assertFalse(small.matchesMetadata(entry("x", size = 900_000)))
    }

    @Test
    fun `an entry with unknown size cannot satisfy a size bound`() {
        assertFalse(SearchQuery.parse("size:>1kb").matchesMetadata(entry("x", size = null)))
    }

    @Test
    fun `quoted phrases stay one term and opt into content search`() {
        val query = SearchQuery.parse("\"deployment plan\"")
        assertEquals(listOf("deployment plan"), query.terms)
        assertTrue(query.searchContent)
    }

    @Test
    fun `content marker enables content search for a bare term`() {
        val query = SearchQuery.parse("content: budget")
        assertTrue(query.searchContent)
        assertEquals(listOf("budget"), query.terms)
    }

    @Test
    fun `content search is not claimed when there is nothing to look for`() {
        assertFalse(SearchQuery.parse("content:").searchContent)
    }

    @Test
    fun `operators combine with free text`() {
        val query = SearchQuery.parse("invoice ext:pdf size:>1mb")
        assertEquals(listOf("invoice"), query.terms)
        assertEquals(setOf("pdf"), query.extensions)
        assertEquals(1_024L * 1_024, query.minSizeBytes)

        assertTrue(query.matchesName("2026-invoice-final.pdf"))
        assertTrue(query.matchesMetadata(entry("2026-invoice-final.pdf", size = 3L * 1_024 * 1_024)))
        assertFalse(query.matchesMetadata(entry("2026-invoice-final.pdf", size = 1_000)))
    }

    @Test
    fun `tokenizer keeps quoted whitespace and drops the quotes`() {
        assertEquals(
            listOf("ext:pdf", "release notes", "draft"),
            SearchQuery.Companion.tokenize("ext:pdf \"release notes\"  draft"),
        )
    }

    @Test
    fun `unterminated quote still yields the trailing token`() {
        assertEquals(listOf("half open"), SearchQuery.Companion.tokenize("\"half open"))
    }
}
