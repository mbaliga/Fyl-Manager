package io.github.mbaliga.fylz.decoder

import android.os.ParcelFileDescriptor
import io.github.mbaliga.fylz.core.ArchiveEngineException
import io.github.mbaliga.fylz.core.ArchiveEntryKindRecord
import io.github.mbaliga.fylz.core.ArchiveEntryRecord
import io.github.mbaliga.fylz.core.ArchiveInspectionRecord
import io.github.mbaliga.fylz.core.ArchiveLimitsRecord
import io.github.mbaliga.fylz.core.InternalException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [DecoderService.inspectArchive]'s mapping, through the constructor-injected `engine` seam
 * (docs/agent/DESIGN-M32-SEEKABLE-PFD.md section 2.4's outcome table): each generated
 * `ArchiveEngineException` subclass to its outcome with the engine's own text, any other
 * `Throwable` to `OUTCOME_INTERNAL` with the class name, and the uniffi record copied into the
 * Parcelable field for field. No native library is loaded -- the generated record and exception
 * classes are plain Kotlin -- which is the point of the seam. Robolectric only for a real
 * [ParcelFileDescriptor].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DecoderServiceMappingTest {

    private val limits = ArchiveLimits()

    private fun pipe(): ParcelFileDescriptor = ParcelFileDescriptor.createPipe()[0]

    private fun serviceThrowing(failure: Throwable) = DecoderService(engine = { _, _, _ -> throw failure })

    private val record = ArchiveInspectionRecord(
        archiveBytes = 1_514uL,
        formatCode = 0x50000u,
        formatName = "ZIP 2.0 (uncompressed)",
        filters = listOf("zstd"),
        entryCount = 8u,
        fileCount = 5u,
        directoryCount = 3u,
        linkCount = 0u,
        totalUncompressed = 1_626uL,
        hasEncryptedEntries = true,
        hasEncryptedMetadata = false,
        hasLossyNames = true,
        policyAllowed = false,
        policyReason = "Archive contains an unsafe path segment.",
        rows = listOf(
            ArchiveEntryRecord(
                path = "docs/",
                nameLossy = false,
                kind = ArchiveEntryKindRecord.DIRECTORY,
                linkTarget = null,
                uncompressed = 0uL,
                mtime = 1_577_836_800L,
                mode = 0x1edu,
                encryptedData = false,
                encryptedMetadata = false,
            ),
            ArchiveEntryRecord(
                path = "escape",
                nameLossy = true,
                kind = ArchiveEntryKindRecord.SYMLINK,
                linkTarget = "/etc/passwd",
                uncompressed = null,
                mtime = null,
                mode = 0x1ffu,
                encryptedData = true,
                encryptedMetadata = true,
            ),
        ),
        rowsTruncated = true,
    )

    @Test
    fun `a successful engine call is copied into the Parcelable field for field, with OK`() {
        var seen: Triple<Int, ArchiveLimitsRecord, Int>? = null
        val service = DecoderService(engine = { fd, limitsRecord, maxRows -> seen = Triple(fd, limitsRecord, maxRows); record })
        val pfd = pipe()
        val fdBefore = pfd.fd
        val customLimits = ArchiveLimits(
            maxEntries = 7,
            maxArchiveBytes = 8L,
            maxFileBytes = 9L,
            maxTotalUncompressedBytes = 10L,
            maxCompressionRatio = 11.5,
            maxPathDepth = 12,
            maxNameLength = 13,
            maxListingEntries = 14,
        )

        val inspection = service.inspectArchive(pfd, customLimits, maxRows = 2)

        assertEquals(ArchiveInspection.OUTCOME_OK, inspection.outcome)
        assertTrue(inspection.isOk)
        assertNull(inspection.message)
        assertEquals(0x50000, inspection.formatCode)
        assertEquals("ZIP 2.0 (uncompressed)", inspection.formatName)
        assertEquals(listOf("zstd"), inspection.filters)
        assertEquals(1_514L, inspection.archiveBytes)
        assertEquals(8, inspection.entryCount)
        assertEquals(5, inspection.fileCount)
        assertEquals(3, inspection.directoryCount)
        assertEquals(0, inspection.linkCount)
        assertEquals(1_626L, inspection.totalUncompressedBytes)
        assertTrue(inspection.hasEncryptedEntries)
        assertFalse(inspection.hasEncryptedMetadata)
        assertTrue(inspection.hasLossyNames)
        assertFalse(inspection.policyAllowed)
        assertEquals("Archive contains an unsafe path segment.", inspection.policyReason)
        assertTrue(inspection.rowsTruncated)
        assertEquals(
            listOf(
                ArchiveEntryInfo("docs/", ArchiveEntryInfo.KIND_DIRECTORY, null, 0L, 1_577_836_800L, 0x1ed, false, false, false),
                ArchiveEntryInfo(
                    "escape", ArchiveEntryInfo.KIND_SYMLINK, "/etc/passwd",
                    ArchiveEntryInfo.UNKNOWN_SIZE, ArchiveEntryInfo.UNKNOWN_MTIME, 0x1ff, true, true, true,
                ),
            ),
            inspection.rows,
        )

        // The engine saw this process's fd as a plain int, the limits as the uniffi record field
        // for field, and the row cap unchanged.
        val (fd, limitsRecord, maxRows) = requireNotNull(seen)
        assertEquals(fdBefore, fd)
        assertEquals(2, maxRows)
        assertEquals(
            ArchiveLimitsRecord(7u, 8uL, 9uL, 10uL, 11.5, 12u, 13u, 14u),
            limitsRecord,
        )
        // And the service closed its descriptor once the engine returned (`use`).
        assertThrows(IllegalStateException::class.java) { pfd.fd }
    }

    @Test
    fun `an unknown total and every kind map to their sentinels and codes`() {
        val kinds = ArchiveEntryKindRecord.values().map { kind ->
            ArchiveEntryRecord("p", false, kind, null, null, null, 0u, false, false)
        }
        val service = DecoderService(engine = { _, _, _ -> record.copy(totalUncompressed = null, rows = kinds) })
        val inspection = service.inspectArchive(pipe(), limits, 500)
        assertEquals(ArchiveInspection.UNKNOWN_SIZE, inspection.totalUncompressedBytes)
        assertEquals(
            listOf(
                ArchiveEntryInfo.KIND_FILE,
                ArchiveEntryInfo.KIND_DIRECTORY,
                ArchiveEntryInfo.KIND_SYMLINK,
                ArchiveEntryInfo.KIND_HARDLINK,
                ArchiveEntryInfo.KIND_OTHER,
            ),
            inspection.rows.map { it.kind },
        )
    }

    @Test
    fun `each engine exception maps to its outcome with the engine's own text`() {
        val cases = listOf(
            ArchiveEngineException.NotSeekable("not seekable (fifo)") to
                (ArchiveInspection.OUTCOME_NOT_SEEKABLE to "not seekable (fifo)"),
            ArchiveEngineException.Unsupported("Unrecognized archive format") to
                (ArchiveInspection.OUTCOME_UNSUPPORTED to "Unrecognized archive format"),
            ArchiveEngineException.Corrupt("Truncated input file (needed 512 bytes, only 0 available)") to
                (ArchiveInspection.OUTCOME_CORRUPT to "Truncated input file (needed 512 bytes, only 0 available)"),
            ArchiveEngineException.LimitExceeded("e10000", "listing") to
                (ArchiveInspection.OUTCOME_LIMIT_EXCEEDED to "limit exceeded (listing) at entry e10000"),
            ArchiveEngineException.Internal("entry pathname is not valid UTF-8") to
                (ArchiveInspection.OUTCOME_INTERNAL to "entry pathname is not valid UTF-8"),
        )
        for ((failure, expected) in cases) {
            val inspection = serviceThrowing(failure).inspectArchive(pipe(), limits, 500)
            assertEquals(failure.toString(), expected.first, inspection.outcome)
            assertEquals(failure.toString(), expected.second, inspection.message)
            assertFalse(inspection.isOk)
            assertFalse(inspection.policyAllowed)
            assertTrue(inspection.rows.isEmpty())
            assertEquals(ArchiveInspection.UNKNOWN_SIZE, inspection.archiveBytes)
        }
    }

    @Test
    fun `any other Throwable is INTERNAL with the class name, never a stack trace or a throw`() {
        val cases = listOf(
            RuntimeException("boom with details that must not leak") to "RuntimeException",
            InternalException("Rust panic") to "InternalException",
            OutOfMemoryError("simulated") to "OutOfMemoryError",
            IllegalStateException("x") to "IllegalStateException",
        )
        for ((failure, className) in cases) {
            val inspection = serviceThrowing(failure).inspectArchive(pipe(), limits, 500)
            assertEquals(ArchiveInspection.OUTCOME_INTERNAL, inspection.outcome)
            assertEquals(className, inspection.message)
        }
    }

    @Test
    fun `the descriptor is closed even when the engine throws`() {
        val pfd = pipe()
        serviceThrowing(ArchiveEngineException.Corrupt("x")).inspectArchive(pfd, limits, 500)
        assertThrows(IllegalStateException::class.java) { pfd.fd }
    }
}
