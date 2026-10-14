package io.github.mbaliga.fylz.decoder

import android.os.Parcel
import android.os.Parcelable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The three `@Parcelize` classes that cross the `IDecoderService` boundary survive a real
 * `Parcel` write/read (docs/agent/DESIGN-M32-SEEKABLE-PFD.md section 2.8), including an
 * [ArchiveInspection] carrying the full 500 default rows -- and that one stays far inside the
 * Binder transaction buffer the design sizes it against.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArchiveParcelablesTest {

    private inline fun <reified T : Parcelable> roundTrip(value: T): Pair<T, Int> {
        val parcel = Parcel.obtain()
        try {
            parcel.writeParcelable(value, 0)
            val size = parcel.dataSize()
            parcel.setDataPosition(0)
            val read = parcel.readParcelable(T::class.java.classLoader, T::class.java)
            return requireNotNull(read) { "readParcelable returned null" } to size
        } finally {
            parcel.recycle()
        }
    }

    private fun row(index: Int) = ArchiveEntryInfo(
        path = "dir${index / 100}/file$index.txt",
        kind = when (index % 5) {
            0 -> ArchiveEntryInfo.KIND_DIRECTORY
            1 -> ArchiveEntryInfo.KIND_SYMLINK
            2 -> ArchiveEntryInfo.KIND_HARDLINK
            3 -> ArchiveEntryInfo.KIND_OTHER
            else -> ArchiveEntryInfo.KIND_FILE
        },
        linkTarget = if (index % 5 == 1) "../target$index" else null,
        uncompressedBytes = if (index % 7 == 0) ArchiveEntryInfo.UNKNOWN_SIZE else index * 1_000L,
        mtimeEpochSeconds = if (index % 11 == 0) ArchiveEntryInfo.UNKNOWN_MTIME else 1_577_836_800L + index,
        mode = 0x1a4,
        encryptedData = index % 13 == 0,
        encryptedMetadata = index % 17 == 0,
        nameLossy = index % 19 == 0,
    )

    @Test
    fun `ArchiveLimits survives a Parcel round trip`() {
        val limits = ArchiveLimits(
            maxEntries = 1,
            maxArchiveBytes = Long.MAX_VALUE,
            maxFileBytes = 3L,
            maxTotalUncompressedBytes = 4L,
            maxCompressionRatio = 5.5,
            maxPathDepth = 6,
            maxNameLength = 7,
            maxListingEntries = 8,
        )
        val (read, _) = roundTrip(limits)
        assertEquals(limits, read)
        // The defaults are the engine's own (fylz-archive's Limits::default), field for field.
        val defaults = ArchiveLimits()
        assertEquals(10_000, defaults.maxEntries)
        assertEquals(2L * 1024 * 1024 * 1024, defaults.maxArchiveBytes)
        assertEquals(1L * 1024 * 1024 * 1024, defaults.maxFileBytes)
        assertEquals(4L * 1024 * 1024 * 1024, defaults.maxTotalUncompressedBytes)
        assertEquals(200.0, defaults.maxCompressionRatio, 0.0)
        assertEquals(64, defaults.maxPathDepth)
        assertEquals(255, defaults.maxNameLength)
        assertEquals(200_000, defaults.maxListingEntries)
    }

    @Test
    fun `ArchiveEntryInfo survives a Parcel round trip, sentinels included`() {
        for (index in 0 until 25) {
            val entry = row(index)
            val (read, _) = roundTrip(entry)
            assertEquals(entry, read)
            assertEquals(entry.isDirectory, read.isDirectory)
            assertEquals(entry.isLink, read.isLink)
        }
    }

    @Test
    fun `ArchiveInspection with 500 rows survives a Parcel round trip and stays small`() {
        val inspection = ArchiveInspection(
            outcome = ArchiveInspection.OUTCOME_OK,
            message = null,
            formatCode = 0x50000,
            formatName = "ZIP 2.0 (deflation)",
            filters = listOf("gzip", "xz"),
            archiveBytes = 123_456_789L,
            entryCount = 80_000,
            fileCount = 79_000,
            directoryCount = 990,
            linkCount = 10,
            totalUncompressedBytes = 9_876_543_210L,
            hasEncryptedEntries = true,
            hasEncryptedMetadata = false,
            hasLossyNames = true,
            policyAllowed = false,
            policyReason = "Archive contains too many entries.",
            rows = (0 until 500).map(::row),
            rowsTruncated = true,
        )
        val (read, size) = roundTrip(inspection)
        assertEquals(inspection, read)
        assertEquals(500, read.rows.size)
        assertTrue(read.isOk)
        // The design's sizing: about 100 bytes per row, so 500 rows is on the order of 50 KB, an
        // order of magnitude inside the 1 MB Binder buffer even with these long-ish paths.
        assertTrue("500 rows marshalled to $size bytes", size < 256 * 1024)
    }

    @Test
    fun `a failed ArchiveInspection survives a Parcel round trip`() {
        val failed = ArchiveInspection.failed(ArchiveInspection.OUTCOME_UNSUPPORTED, "Unrecognized archive format")
        val (read, _) = roundTrip(failed)
        assertEquals(failed, read)
        assertEquals(ArchiveInspection.OUTCOME_UNSUPPORTED, read.outcome)
        assertEquals(ArchiveInspection.UNKNOWN_SIZE, read.archiveBytes)
        assertTrue(read.rows.isEmpty())
        assertTrue(!read.isOk && !read.policyAllowed)
    }

    @Test
    fun `ArchiveExtractResult survives a Parcel round trip`() {
        val ok = ArchiveExtractResult.ok(1_234_567L)
        assertEquals(ok, roundTrip(ok).first)
        assertTrue(roundTrip(ok).first.isOk)
        val failed = ArchiveExtractResult.failed(ArchiveExtractResult.OUTCOME_NOT_FOUND, "no entry \"x\" at header 3")
        assertEquals(failed, roundTrip(failed).first)
        assertFalse(roundTrip(failed).first.isOk)
    }

    @Test
    fun `the M3 3 fields of ArchiveInspection and ArchiveEntryInfo survive a Parcel round trip`() {
        val inspection = ArchiveInspection.failed(ArchiveInspection.OUTCOME_CORRUPT, "x").copy(
            outcome = ArchiveInspection.OUTCOME_OK,
            rows = listOf(row(1).copy(ordinal = 41)),
            partial = true,
            partialMessage = "Damaged tar archive",
            structuralRefusal = "Archive contains an unsafe path segment.",
        )
        val (read, _) = roundTrip(inspection)
        assertEquals(inspection, read)
        assertTrue(read.partial)
        assertEquals("Damaged tar archive", read.partialMessage)
        assertEquals("Archive contains an unsafe path segment.", read.structuralRefusal)
        assertEquals(41, read.rows.single().ordinal)
    }
}
