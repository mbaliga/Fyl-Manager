package io.github.mbaliga.fylz.storage

import io.github.mbaliga.fylz.core.model.EntryKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [storageKindOf] and [classifyBytes] are the only place the Storage card's per-kind totals come
 * from, so the mapping and the summing both get pinned here, on the JVM, with no filesystem or
 * WorkManager involved -- [StorageScanWorker] itself is exercised on-device only.
 */
class StorageKindTest {

    @Test
    fun `images, videos, sounds and archives each map to their own bucket`() {
        assertEquals(StorageKind.PHOTOS, storageKindOf(EntryKind.IMAGE))
        assertEquals(StorageKind.VIDEOS, storageKindOf(EntryKind.VIDEO))
        assertEquals(StorageKind.SOUNDS, storageKindOf(EntryKind.AUDIO))
        assertEquals(StorageKind.ARCHIVES, storageKindOf(EntryKind.ARCHIVE))
    }

    @Test
    fun `text, markdown and pdf all collapse onto documents, matching FylzSearch's document kinds`() {
        assertEquals(StorageKind.DOCUMENTS, storageKindOf(EntryKind.TEXT))
        assertEquals(StorageKind.DOCUMENTS, storageKindOf(EntryKind.MARKDOWN))
        assertEquals(StorageKind.DOCUMENTS, storageKindOf(EntryKind.PDF))
    }

    @Test
    fun `other and directory both fall into the other bucket rather than being dropped`() {
        assertEquals(StorageKind.OTHER, storageKindOf(EntryKind.OTHER))
        assertEquals(StorageKind.OTHER, storageKindOf(EntryKind.DIRECTORY))
    }

    @Test
    fun `every EntryKind resolves to some StorageKind -- the mapping is exhaustive`() {
        EntryKind.entries.forEach { kind ->
            // Would throw a NoWhenBranchMatchedException at runtime if a new EntryKind were ever
            // added without teaching storageKindOf about it.
            storageKindOf(kind)
        }
    }

    @Test
    fun `classifyBytes sums sizes per kind, keyed off the same classifier the browser uses`() {
        val files = listOf(
            ScannedFileFacts("holiday.jpg", "image/jpeg", 1_000L),
            ScannedFileFacts("selfie.png", "image/png", 500L),
            ScannedFileFacts("report.pdf", "application/pdf", 2_000L),
            ScannedFileFacts("clip.mp4", "video/mp4", 4_000L),
        )

        val totals = classifyBytes(files)

        assertEquals(1_500L, totals.getValue(StorageKind.PHOTOS))
        assertEquals(2_000L, totals.getValue(StorageKind.DOCUMENTS))
        assertEquals(4_000L, totals.getValue(StorageKind.VIDEOS))
        assertEquals(3, totals.size)
    }

    @Test
    fun `classifyBytes never emits a negative size even if the filesystem reported one`() {
        val totals = classifyBytes(listOf(ScannedFileFacts("weird.bin", "application/octet-stream", -1L)))

        assertEquals(0L, totals.getValue(StorageKind.OTHER))
    }

    @Test
    fun `classifyBytes of an empty walk is an empty map, not a map of zeros`() {
        assertEquals(emptyMap<StorageKind, Long>(), classifyBytes(emptyList()))
    }

    @Test
    fun `the legend order names every kind exactly once`() {
        assertEquals(StorageKind.entries.toSet(), STORAGE_KIND_ORDER.toSet())
        assertEquals(StorageKind.entries.size, STORAGE_KIND_ORDER.size)
    }
}
