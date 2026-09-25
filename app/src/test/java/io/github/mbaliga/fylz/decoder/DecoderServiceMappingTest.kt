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
                ordinal = 0u,
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
                ordinal = 1u,
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
                ArchiveEntryInfo("docs/", ArchiveEntryInfo.KIND_DIRECTORY, null, 0L, 1_577_836_800L, 0x1ed, false, false, false, ordinal = 0),
                ArchiveEntryInfo(
                    "escape", ArchiveEntryInfo.KIND_SYMLINK, "/etc/passwd",
                    ArchiveEntryInfo.UNKNOWN_SIZE, ArchiveEntryInfo.UNKNOWN_MTIME, 0x1ff, true, true, true, ordinal = 1,
                ),
            ),
            inspection.rows,
        )
        assertFalse(inspection.partial)
        assertNull(inspection.partialMessage)
        assertNull(inspection.structuralRefusal)

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
            ArchiveEntryRecord(0u, "p", false, kind, null, null, null, 0u, false, false)
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

    // ------------------------------------------------------------------------------------------
    // M3.3a: listArchive and extractEntry.
    // ------------------------------------------------------------------------------------------

    @Test
    fun `listArchive copies the summary with partial and structural fields and closes both descriptors`() {
        var seen: Triple<Int, ArchiveLimitsRecord, Int>? = null
        val service = DecoderService(
            listEngine = { fd, limitsRecord, sinkFd ->
                seen = Triple(fd, limitsRecord, sinkFd)
                record.copy(rows = emptyList(), rowsTruncated = false, partial = true, partialMessage = "Damaged tar archive", structuralRefusal = "Archive contains an unsafe path segment.")
            },
        )
        val archive = pipe()
        val sink = pipe()
        val (archiveFd, sinkFd) = archive.fd to sink.fd
        val inspection = service.listArchive(archive, limits, sink)
        assertTrue(inspection.isOk)
        assertTrue(inspection.rows.isEmpty())
        assertFalse(inspection.rowsTruncated)
        assertTrue(inspection.partial)
        assertEquals("Damaged tar archive", inspection.partialMessage)
        assertEquals("Archive contains an unsafe path segment.", inspection.structuralRefusal)
        assertEquals(8, inspection.entryCount)
        assertEquals(Triple(archiveFd, limits.toRecord(), sinkFd), seen)
        assertThrows(IllegalStateException::class.java) { archive.fd }
        assertThrows(IllegalStateException::class.java) { sink.fd }
    }

    @Test
    fun `listArchive maps engine exceptions like inspectArchive, and closes the sink too`() {
        val service = DecoderService(listEngine = { _, _, _ -> throw ArchiveEngineException.LimitExceeded("e10000", "listing") })
        val sink = pipe()
        val inspection = service.listArchive(pipe(), limits, sink)
        assertEquals(ArchiveInspection.OUTCOME_LIMIT_EXCEEDED, inspection.outcome)
        assertEquals("limit exceeded (listing) at entry e10000", inspection.message)
        assertThrows(IllegalStateException::class.java) { sink.fd }
        val internal = DecoderService(listEngine = { _, _, _ -> throw OutOfMemoryError("x") }).listArchive(pipe(), limits, pipe())
        assertEquals(ArchiveInspection.OUTCOME_INTERNAL, internal.outcome)
        assertEquals("OutOfMemoryError", internal.message)
    }

    @Test
    fun `extractEntry returns the bytes written and passes the ordinal and path through`() {
        var seen: List<Any>? = null
        val service = DecoderService(extractEngine = { fd, ordinal, path, limitsRecord, sinkFd -> seen = listOf(fd, ordinal, path, limitsRecord, sinkFd); 4_096L })
        val archive = pipe()
        val sink = pipe()
        val (archiveFd, sinkFd) = archive.fd to sink.fd
        val result = service.extractEntry(archive, 7, "docs/readme.md", limits, sink)
        assertEquals(ArchiveExtractResult.ok(4_096L), result)
        assertTrue(result.isOk)
        assertEquals(listOf(archiveFd, 7, "docs/readme.md", limits.toRecord(), sinkFd), seen)
        assertThrows(IllegalStateException::class.java) { archive.fd }
        assertThrows(IllegalStateException::class.java) { sink.fd }
    }

    @Test
    fun `extractEntry maps NotFound to its own outcome and every other failure like the inspection table`() {
        val cases = listOf(
            ArchiveEngineException.NotFound(5u, "docs/readme.md") to (ArchiveExtractResult.OUTCOME_NOT_FOUND to "no entry \"docs/readme.md\" at header 5"),
            ArchiveEngineException.LimitExceeded("big.bin", "file") to (ArchiveExtractResult.OUTCOME_LIMIT_EXCEEDED to "limit exceeded (file) at entry big.bin"),
            ArchiveEngineException.Corrupt("Truncated input file") to (ArchiveExtractResult.OUTCOME_CORRUPT to "Truncated input file"),
            ArchiveEngineException.NotSeekable("not seekable (fifo)") to (ArchiveExtractResult.OUTCOME_NOT_SEEKABLE to "not seekable (fifo)"),
            ArchiveEngineException.Unsupported("Unrecognized archive format") to (ArchiveExtractResult.OUTCOME_UNSUPPORTED to "Unrecognized archive format"),
            ArchiveEngineException.Internal("bug") to (ArchiveExtractResult.OUTCOME_INTERNAL to "bug"),
            RuntimeException("details that must not leak") to (ArchiveExtractResult.OUTCOME_INTERNAL to "RuntimeException"),
        )
        for ((failure, expected) in cases) {
            val sink = pipe()
            val result = DecoderService(extractEngine = { _, _, _, _, _ -> throw failure }).extractEntry(pipe(), 0, "x", limits, sink)
            assertEquals(failure.toString(), expected.first, result.outcome)
            assertEquals(failure.toString(), expected.second, result.message)
            assertFalse(result.isOk)
            assertEquals(0L, result.bytesWritten)
            assertThrows(IllegalStateException::class.java) { sink.fd }
        }
        assertEquals(6, ArchiveExtractResult.OUTCOME_NOT_FOUND)
    }

    @Test
    fun `the descriptor is closed even when the engine throws`() {
        val pfd = pipe()
        serviceThrowing(ArchiveEngineException.Corrupt("x")).inspectArchive(pfd, limits, 500)
        assertThrows(IllegalStateException::class.java) { pfd.fd }
    }
}
