package io.github.mbaliga.fylz.ui.overview

import android.net.Uri
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.library.FavoriteLocation
import io.github.mbaliga.fylz.storage.StorageKind
import io.github.mbaliga.fylz.storage.StorageUsageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// One test below builds FavoriteLocation values, which need a real android.net.Uri -- the
// plain-JVM stub jar throws "not mocked" for it (see PdfPagePlanPolicyTest's own note), so this
// whole class runs under Robolectric rather than splitting off a second file for one test.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OverviewModelsTest {

    // ---- cappedCountLabel --------------------------------------------------------------------

    @Test
    fun `a count under the cap prints as-is`() {
        assertEquals("42", cappedCountLabel(42))
    }

    @Test
    fun `a count at the cap prints as-is, not with a plus`() {
        assertEquals("99", cappedCountLabel(99))
    }

    @Test
    fun `a count over the cap prints as plus-cap`() {
        assertEquals("+99", cappedCountLabel(140))
    }

    // ---- tallyBytes / ByteTally.describe -------------------------------------------------------

    @Test
    fun `tallyBytes sums only the measured sizes and counts the rest`() {
        val tally = tallyBytes(listOf(100L, null, 200L, null, null))

        assertEquals(300L, tally.totalBytes)
        assertEquals(3, tally.unmeasuredCount)
    }

    @Test
    fun `describe omits the not-reported clause when everything was measured`() {
        val tally = tallyBytes(listOf(1_024L))

        assertEquals("1.0 KiB", tally.describe())
    }

    @Test
    fun `describe appends the not-reported count once any size is missing`() {
        val tally = tallyBytes(listOf(1_024L, null, null))

        assertEquals("1.0 KiB · 2 not reported", tally.describe())
    }

    // ---- storageLegendEntries / storageAccountedBytes ------------------------------------------

    @Test
    fun `the legend always lists all six kinds, in the fixed order, even ones the scan found zero`() {
        val snapshot = StorageUsageSnapshot(scannedAtMillis = 1L, kindBytes = mapOf(StorageKind.PHOTOS to 10L))

        val entries = storageLegendEntries(snapshot)

        assertEquals(STORAGE_KIND_ORDER_FOR_TEST, entries.map { it.first })
        assertEquals(10L, entries.first { it.first == StorageKind.PHOTOS }.second)
        assertEquals(0L, entries.first { it.first == StorageKind.ARCHIVES }.second)
        assertEquals(0L, entries.first { it.first == StorageKind.OTHER }.second)
    }

    @Test
    fun `storageAccountedBytes sums whatever the scan actually classified`() {
        val snapshot = StorageUsageSnapshot(
            scannedAtMillis = 1L,
            kindBytes = mapOf(StorageKind.PHOTOS to 10L, StorageKind.VIDEOS to 5L),
        )

        assertEquals(15L, storageAccountedBytes(snapshot))
    }

    // ---- formatLastEdited -----------------------------------------------------------------------

    @Test
    fun `a null modified time is absent, not a formatted date`() {
        assertNull(formatLastEdited(null))
    }

    @Test
    fun `a zero or negative modified time is treated as unknown, not 1 Jan 1970`() {
        assertNull(formatLastEdited(0L))
        assertNull(formatLastEdited(-1L))
    }

    @Test
    fun `a real modified time formats to a date`() {
        assertTrue(formatLastEdited(1_700_000_000_000L)!!.isNotBlank())
    }

    // ---- overviewCliLines / overviewCliHeading ---------------------------------------------------

    @Test
    fun `a quick access card with no access reads as no access, never a fabricated count`() {
        val card = OverviewCard.QuickAccess(root = null, hasFullAccess = false, entryCount = null, thumbnails = emptyList())

        assertEquals(listOf("no access granted"), overviewCliLines(card))
    }

    @Test
    fun `a storage card with no scan yet says so instead of printing zeros`() {
        val card = OverviewCard.Storage(
            hasFullAccess = true,
            usedBytes = 10L,
            totalBytes = 100L,
            usage = null,
            scanning = false,
        )

        val lines = overviewCliLines(card)

        assertTrue(lines.any { it.contains("used") })
        assertTrue(lines.any { it == "not scanned yet" })
    }

    @Test
    fun `a storage card with a completed scan lists every legend kind, including zero ones`() {
        val card = OverviewCard.Storage(
            hasFullAccess = true,
            usedBytes = 10L,
            totalBytes = 100L,
            usage = StorageUsageSnapshot(scannedAtMillis = 1L, kindBytes = mapOf(StorageKind.PHOTOS to 10L)),
            scanning = false,
        )

        val lines = overviewCliLines(card)

        assertTrue(lines.any { it.contains("Archives") })
        assertTrue(lines.any { it.contains("Other") })
    }

    @Test
    fun `a deleted files card's cli lines carry the real retention line verbatim`() {
        val card = OverviewCard.DeletedFiles(
            totalCount = 3,
            recoverableBytes = 1_024L,
            notReportedCount = 0,
            retentionDescription = "Kept until you empty the bin.",
        )

        assertEquals("Kept until you empty the bin.", overviewCliLines(card).last())
    }

    @Test
    fun `an empty pinned card says none yet rather than an empty list`() {
        val card = OverviewCard.Pinned(emptyList())

        assertEquals(listOf("none yet"), overviewCliLines(card))
    }

    @Test
    fun `a pinned card past the visible rows folds the rest into a plus-N more line`() {
        val favorites = (1..6).map { FavoriteLocation(Uri.parse("content://x/$it"), "Folder $it") }
        val card = OverviewCard.Pinned(favorites)

        val lines = overviewCliLines(card)

        assertEquals(OVERVIEW_LIST_CARD_VISIBLE_ROWS + 1, lines.size)
        assertEquals("  +2 more", lines.last())
    }

    @Test
    fun `overviewCliHeading labels a kind folder card with both the kind and the folder`() {
        val card = OverviewCard.KindFolder(folderName = "Pictures", kind = EntryKind.IMAGE, count = 5, lastModifiedMillis = null)

        assertEquals("IMAGE IN PICTURES", overviewCliHeading(card))
    }

    // ---- the grid mechanism itself ---------------------------------------------------------------

    @Test
    fun `every card span is 1 or 2, matching a 2-column grid`() {
        val cards: List<OverviewCard> = listOf(
            OverviewCard.QuickAccess(null, false, null, emptyList()),
            OverviewCard.Storage(false, null, null, null, false),
            OverviewCard.DeletedFiles(0, 0L, 0, "x"),
        )

        cards.forEach { card -> assertTrue(card.span == 1 || card.span == 2) }
    }

    private val STORAGE_KIND_ORDER_FOR_TEST = listOf(
        StorageKind.PHOTOS,
        StorageKind.DOCUMENTS,
        StorageKind.VIDEOS,
        StorageKind.SOUNDS,
        StorageKind.ARCHIVES,
        StorageKind.OTHER,
    )
}
