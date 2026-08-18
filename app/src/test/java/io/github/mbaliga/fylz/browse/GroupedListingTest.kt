package io.github.mbaliga.fylz.browse

import android.net.Uri
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

/**
 * The Stacks grouper. The property that matters most is not the grouping itself -- that is a
 * straightforward run-length pass -- but that [GroupedListing.entryIndexOf] and
 * [GroupedListing.lazyIndexOf] round-trip correctly once headers are interleaved, since the edge
 * scrubber has no other way to keep mapping the right entry to the right row.
 */
@RunWith(RobolectricTestRunner::class)
class GroupedListingTest {

    private val utc = ZoneOffset.UTC
    private val english = Locale.UK

    private fun at(year: Int, month: Int) = LocalDateTime.of(year, month, 5, 9, 0).toInstant(utc).toEpochMilli()

    private fun entry(name: String, kind: EntryKind, sizeBytes: Long?, modified: Long?) = FileEntry(
        uri = Uri.parse("content://test/${name.hashCode()}"),
        name = name,
        mimeType = if (kind == EntryKind.DIRECTORY) "vnd.android.document/directory" else "application/octet-stream",
        sizeBytes = sizeBytes,
        lastModifiedMillis = modified,
        flags = 0,
        kind = kind,
    )

    // Four entries that land in different buckets under every axis: two share a letter, kind and
    // month; the other two each stand alone under at least one axis.
    private val entries = listOf(
        entry("alpha.jpg", EntryKind.IMAGE, sizeBytes = 500L, modified = at(2024, 1)),
        entry("apple.png", EntryKind.IMAGE, sizeBytes = 2_000L, modified = at(2024, 1)),
        entry("beta.txt", EntryKind.TEXT, sizeBytes = 10L, modified = at(2024, 2)),
        entry("cedar", EntryKind.DIRECTORY, sizeBytes = null, modified = at(2024, 2)),
    )

    private fun group(axis: GroupAxis) = groupedListing(entries, SortSpec(groupBy = axis), utc, english)

    private fun headers(listing: GroupedListing) =
        listing.rows.filterIsInstance<ListingRow.Header>().map { it.label to it.count }

    // ── ungrouped ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `no groupBy passes entries through as plain items with identity index translation`() {
        val listing = groupedListing(entries, SortSpec.Default, utc, english)

        assertEquals(entries, listing.rows.map { (it as ListingRow.Item).entry })
        entries.indices.forEach { i ->
            assertEquals(i, listing.entryIndexOf(i))
            assertEquals(i, listing.lazyIndexOf(i))
        }
    }

    @Test
    fun `an empty listing groups to nothing`() {
        val listing = groupedListing(emptyList(), SortSpec(groupBy = GroupAxis.KIND), utc, english)
        assertTrue(listing.rows.isEmpty())
        assertNull(listing.entryIndexOf(0))
    }

    // ── each axis ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `LETTER groups by the same initial entryStops would cut`() {
        val listing = group(GroupAxis.LETTER)
        assertEquals(listOf("A" to 2, "B" to 1, "C" to 1), headers(listing))
    }

    @Test
    fun `SIZE groups by the same band entryStops would cut, folders included`() {
        val listing = group(GroupAxis.SIZE)
        assertEquals(listOf("B" to 1, "KB" to 1, "B" to 1, "dir" to 1), headers(listing))
    }

    @Test
    fun `DATE groups by the same month entryStops would cut`() {
        val listing = group(GroupAxis.DATE)
        assertEquals(listOf("Jan 24" to 2, "Feb 24" to 2), headers(listing))
    }

    @Test
    fun `KIND groups by entry kind with a human plural label`() {
        val listing = group(GroupAxis.KIND)
        assertEquals(listOf("Images" to 2, "Text" to 1, "Folders" to 1), headers(listing))
    }

    // ── the index translation ─────────────────────────────────────────────────────────────

    @Test
    fun `every header row reports no entry, every item row reports the entry it holds`() {
        val listing = group(GroupAxis.LETTER)
        listing.rows.forEachIndexed { rowIndex, row ->
            when (row) {
                is ListingRow.Header -> assertNull("row $rowIndex is a header", listing.entryIndexOf(rowIndex))
                is ListingRow.Item -> assertEquals(
                    "row $rowIndex holds ${row.entry.name}",
                    entries.indexOf(row.entry),
                    listing.entryIndexOf(rowIndex),
                )
            }
        }
    }

    @Test
    fun `entryIndexOf and lazyIndexOf round-trip for every entry, on every axis`() {
        GroupAxis.entries.forEach { axis ->
            val listing = group(axis)
            entries.indices.forEach { entryIndex ->
                val row = listing.lazyIndexOf(entryIndex)
                assertEquals(
                    "axis $axis: entry $entryIndex round-trips through row $row",
                    entryIndex,
                    listing.entryIndexOf(row),
                )
                assertTrue(
                    "axis $axis: row $row for entry $entryIndex must be an Item, not a Header",
                    listing.rows[row] is ListingRow.Item,
                )
            }
        }
    }

    @Test
    fun `lazyIndexOf points past every header that precedes an entry's own run`() {
        // Row indices only grow: the header count in front of an entry's run pushes it further
        // down the list than its own position among entries, never the other way round.
        val listing = group(GroupAxis.LETTER)
        entries.indices.forEach { entryIndex ->
            assertTrue(listing.lazyIndexOf(entryIndex) >= entryIndex)
        }
    }
}
