package io.github.mbaliga.fylz.browse

import android.net.Uri
import io.github.mbaliga.fylz.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

/**
 * The edge scrubber's stops for a folder listing.
 *
 * The property that matters is that the strip is a map of *this* list as displayed. Fylz re-keys
 * the same folder by name, size, date or type as the sort changes, so a scrubber that showed
 * months down an A-Z list would be worse than none — it would be a map of somewhere else.
 */
@RunWith(RobolectricTestRunner::class)
class EntryStopsTest {

    private val utc = ZoneOffset.UTC
    private val english = Locale.UK

    private fun entry(
        name: String,
        sizeBytes: Long? = 100L,
        modified: Long? = 1_700_000_000_000L,
        directory: Boolean = false,
    ) = FileEntry(
        uri = Uri.parse("content://test/${name.hashCode()}"),
        name = name,
        mimeType = if (directory) "vnd.android.document/directory" else "text/plain",
        sizeBytes = if (directory) null else sizeBytes,
        lastModifiedMillis = modified,
        flags = 0,
        kind = if (directory) EntryKind.DIRECTORY else EntryKind.OTHER,
    )

    private fun stops(entries: List<FileEntry>, field: SortField) =
        entryStops(entries, SortSpec(field = field), utc, english)

    @Test
    fun `an empty listing has no stops`() {
        assertTrue(entryStops(emptyList(), SortSpec.Default, utc, english).isEmpty())
    }

    // ── name ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a name sort cuts a stop at each new letter`() {
        val entries = listOf(entry("alpha"), entry("apple"), entry("beta"), entry("cedar"))
        val result = stops(entries, SortField.NAME)
        assertEquals(listOf("A", "B", "C"), result.map { it.label })
        assertEquals(listOf(0, 2, 3), result.map { it.itemIndex })
    }

    @Test
    fun `names that do not start with a letter share one bucket`() {
        // A folder of dated logs — 2024-01-03.log, 2024-01-04.log … — would otherwise produce a
        // stop per file: a strip with a thousand identical labels, which maps nothing.
        val entries = listOf(entry("2024-01-03.log"), entry("2024-01-04.log"), entry("_tmp"), entry("apple"))
        val result = stops(entries, SortField.NAME)
        assertEquals(listOf("#", "A"), result.map { it.label })
        assertEquals(listOf(0, 3), result.map { it.itemIndex })
    }

    @Test
    fun `leading whitespace does not decide the letter`() {
        assertEquals("A", initialOf("  apple", english))
        assertEquals("#", initialOf("   ", english))
        assertEquals("#", initialOf("", english))
    }

    // ── size ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a size sort bands by order of magnitude, not by exact size`() {
        // Consecutive files differ by bytes; an exact-size key would cut a stop at every row.
        val entries = listOf(
            entry("a", sizeBytes = 4L * 1024 * 1024 * 1024),
            entry("b", sizeBytes = 2L * 1024 * 1024 * 1024),
            entry("c", sizeBytes = 900L * 1024),
            entry("d", sizeBytes = 800L * 1024),
            entry("e", sizeBytes = 12L),
        )
        val result = stops(entries, SortField.SIZE)
        assertEquals(listOf("GB", "KB", "B"), result.map { it.label })
        assertEquals(listOf(0, 2, 4), result.map { it.itemIndex })
    }

    @Test
    fun `folders are their own size band`() {
        // A directory reports no size. Merging it into the empty-file band would put the folder
        // block and every zero-byte file under one label.
        assertEquals("dir", sizeBand(null))
        assertEquals("0", sizeBand(0L))
        assertEquals("B", sizeBand(1L))
        assertEquals("KB", sizeBand(1_024L))
        assertEquals("MB", sizeBand(1_024L * 1_024))
        assertEquals("GB", sizeBand(1_024L * 1_024 * 1_024))
    }

    // ── date ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a date sort cuts a stop at each month`() {
        fun at(year: Int, month: Int) =
            LocalDateTime.of(year, month, 5, 9, 0).toInstant(utc).toEpochMilli()
        val entries = listOf(
            entry("a", modified = at(2024, 3)),
            entry("b", modified = at(2024, 3)),
            entry("c", modified = at(2024, 2)),
            entry("d", modified = at(2023, 12)),
        )
        val result = stops(entries, SortField.MODIFIED)
        assertEquals(listOf("Mar 24", "Feb 24", "Dec 23"), result.map { it.label })
        assertEquals(listOf(0, 2, 3), result.map { it.itemIndex })
    }

    @Test
    fun `entries with no usable timestamp fall into one undated band`() {
        // Providers routinely report 0 or null. Formatting those would put them all on January
        // 1970 and hand the strip a stop nobody asked for at one end.
        val entries = listOf(entry("a", modified = null), entry("b", modified = 0L))
        assertEquals(listOf("—"), stops(entries, SortField.MODIFIED).map { it.label })
    }

    // ── type ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a type sort cuts a stop at each extension`() {
        val entries = listOf(entry("a.jpg"), entry("b.jpg"), entry("c.pdf"), entry("notes"))
        val result = stops(entries, SortField.TYPE)
        assertEquals(listOf("JPG", "PDF", "·"), result.map { it.label })
        assertEquals(listOf(0, 2, 3), result.map { it.itemIndex })
    }

    // ── shape ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `stops are strictly ascending and never repeat a run`() {
        // The scrubber scans from the end to find the stop an index belongs to, so the list must
        // ascend; and a key that reappears after an interruption legitimately gets a new stop,
        // but a key that simply continues must not.
        val entries = listOf(entry("apple"), entry("avocado"), entry("beta"), entry("apricot"))
        val result = stops(entries, SortField.NAME)
        assertEquals(listOf("A", "B", "A"), result.map { it.label })
        assertEquals(listOf(0, 2, 3), result.map { it.itemIndex })
        assertEquals(result.map { it.itemIndex }.sorted(), result.map { it.itemIndex })
    }

    @Test
    fun `a descending sort produces the same boundaries in reverse`() {
        // Nothing here reads the direction: cutting where the displayed key changes is correct
        // either way, which is the reason there is no direction branch to get wrong.
        val ascending = listOf(entry("alpha"), entry("beta"), entry("cedar"))
        val descending = ascending.reversed()
        assertEquals(listOf("A", "B", "C"), stops(ascending, SortField.NAME).map { it.label })
        assertEquals(listOf("C", "B", "A"), stops(descending, SortField.NAME).map { it.label })
    }
}
