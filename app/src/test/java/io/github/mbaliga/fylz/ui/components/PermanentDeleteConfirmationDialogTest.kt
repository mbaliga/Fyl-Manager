package io.github.mbaliga.fylz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P0.8: the confirmation copy's count/size wording and the empty-bin size aggregation, pulled out
 * of the dialog composable itself so they're testable without Compose.
 */
class PermanentDeleteConfirmationDialogTest {

    @Test
    fun `a single named item states its name and size`() {
        assertEquals(
            "This permanently deletes “notes.txt” (2.4 MiB). It cannot be restored by Fylz.",
            permanentDeleteMessage(itemCount = 1, totalBytes = 2_516_582, itemName = "notes.txt"),
        )
    }

    @Test
    fun `a single named item with unknown size omits the size`() {
        assertEquals(
            "This permanently deletes “notes.txt”. It cannot be restored by Fylz.",
            permanentDeleteMessage(itemCount = 1, totalBytes = null, itemName = "notes.txt"),
        )
    }

    @Test
    fun `emptying the bin states the item count and total size, plural`() {
        assertEquals(
            "This permanently deletes 3 items (1.0 MiB). It cannot be restored by Fylz.",
            permanentDeleteMessage(itemCount = 3, totalBytes = 1_048_576),
        )
    }

    @Test
    fun `emptying a bin of one item is singular, not plural`() {
        assertEquals(
            "This permanently deletes 1 item. It cannot be restored by Fylz.",
            permanentDeleteMessage(itemCount = 1, totalBytes = null),
        )
    }

    @Test
    fun `sizes under a KiB are shown in bytes`() {
        assertEquals(
            "This permanently deletes 2 items (512 B). It cannot be restored by Fylz.",
            permanentDeleteMessage(itemCount = 2, totalBytes = 512),
        )
    }

    @Test
    fun `totalKnownBytes sums the known sizes and ignores unknown ones`() {
        assertEquals(300L, totalKnownBytes(listOf(100L, 200L, null)))
    }

    @Test
    fun `totalKnownBytes is null when every size is unknown`() {
        assertNull(totalKnownBytes(listOf(null, null)))
    }

    @Test
    fun `totalKnownBytes is null for an empty list`() {
        assertNull(totalKnownBytes(emptyList()))
    }
}
