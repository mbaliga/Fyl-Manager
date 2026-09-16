package io.github.mbaliga.fylz.browse

import io.github.mbaliga.fylz.core.model.EntryKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Locale

class EntryDetailsTest {

    // Sizes are formatted with the default locale, which decides whether 2 KiB reads "2.0" or
    // "2,0". Pinned here so the assertions test the wording rather than the CI runner's region.
    private val original: Locale = Locale.getDefault()

    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    @After
    fun restoreLocale() = Locale.setDefault(original)

    private fun List<DetailFact>.value(label: String): String? = firstOrNull { it.label == label }?.value

    @Test
    fun `a file whose size the provider withheld says so rather than showing zero`() {
        val facts = EntryDetails.facts(
            kind = EntryKind.IMAGE,
            location = "Pictures / Camera",
            sizeBytes = null,
            modified = null,
        )

        assertEquals(EntryDetails.NOT_REPORTED, facts.value("Size"))
        assertEquals(EntryDetails.NOT_REPORTED, facts.value("Modified"))
    }

    @Test
    fun `a folder claims no size of its own`() {
        val facts = EntryDetails.facts(
            kind = EntryKind.DIRECTORY,
            location = "Documents",
            childCount = 3,
        )

        assertNull(facts.value("Size"))
        assertNull(facts.value("Modified"))
        assertEquals("3 items", facts.value("Items"))
        assertEquals("Folder", facts.value("Kind"))
    }

    @Test
    fun `a single child is not pluralised`() {
        val facts = EntryDetails.facts(kind = EntryKind.DIRECTORY, location = "Documents", childCount = 1)

        assertEquals("1 item", facts.value("Items"))
    }

    @Test
    fun `optional rows are omitted when there is nothing to say`() {
        val facts = EntryDetails.facts(kind = EntryKind.TEXT, location = "Notes", mimeType = "  ")

        assertNull(facts.value("Type"))
        assertNull(facts.value("Tags"))
        assertEquals("Notes", facts.value("Location"))
    }

    @Test
    fun `tags are listed when the library has them`() {
        val facts = EntryDetails.facts(
            kind = EntryKind.PDF,
            location = "Documents",
            tags = listOf("invoice", "2026"),
        )

        assertEquals("invoice, 2026", facts.value("Tags"))
        assertEquals("PDF", facts.value("Kind"))
    }

    @Test
    fun `a selection totals only the sizes it actually has`() {
        val facts = EntryDetails.selection(
            kinds = listOf(EntryKind.IMAGE, EntryKind.IMAGE, EntryKind.PDF),
            sizes = listOf(1_024L, null, 1_024L),
        )

        assertEquals("3 items", facts.value("Selected"))
        assertEquals("2.0 KiB · 1 not reported", facts.value("Total size"))
    }

    @Test
    fun `a selection is broken down by kind`() {
        val facts = EntryDetails.selection(
            kinds = listOf(EntryKind.DIRECTORY, EntryKind.DIRECTORY, EntryKind.PDF),
            sizes = listOf(null, null, 10L),
        )

        assertEquals("2 Folders · 1 PDF", facts.value("Kinds"))
        assertEquals("10 B · 2 not reported", facts.value("Total size"))
    }

    /**
     * The mechanical `EntryKind.name` transform this replaced produced "Pdf" and "Directory" —
     * the two words a file manager can least afford to get wrong.
     */
    @Test
    fun `kinds read as words rather than as identifiers`() {
        assertEquals("Folder", EntryKind.DIRECTORY.readableLabel())
        assertEquals("PDF", EntryKind.PDF.readableLabel())
        assertEquals("File", EntryKind.OTHER.readableLabel())
    }
}
