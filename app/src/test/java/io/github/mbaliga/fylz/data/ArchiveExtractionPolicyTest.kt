package io.github.mbaliga.fylz.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveExtractionPolicyTest {
    @Test
    fun `allows a normal bounded archive`() {
        val decision = ArchiveExtractionPolicy.evaluate(
            archiveBytes = 1_024,
            entries = listOf(
                ArchiveEntryMetadata("docs/readme.md", false, 100, 500),
                ArchiveEntryMetadata("images/", true, 0, 0),
            ),
        )

        assertTrue(decision.allowed)
    }

    @Test
    fun `rejects traversal and absolute paths`() {
        assertFalse(
            ArchiveExtractionPolicy.evaluate(
                archiveBytes = 100,
                entries = listOf(ArchiveEntryMetadata("../escape.txt", false, 10, 20)),
            ).allowed,
        )
        assertFalse(
            ArchiveExtractionPolicy.evaluate(
                archiveBytes = 100,
                entries = listOf(ArchiveEntryMetadata("/absolute.txt", false, 10, 20)),
            ).allowed,
        )
    }

    @Test
    fun `rejects too many entries`() {
        val limits = ArchiveExtractionLimits(maxEntries = 2)
        val entries = List(3) { index ->
            ArchiveEntryMetadata("file-$index.txt", false, 10, 20)
        }

        assertFalse(ArchiveExtractionPolicy.evaluate(100, entries, limits).allowed)
    }

    @Test
    fun `rejects oversized files and total expansion`() {
        val oversized = ArchiveExtractionPolicy.evaluate(
            archiveBytes = 100,
            entries = listOf(ArchiveEntryMetadata("huge.bin", false, 100, 1_001)),
            limits = ArchiveExtractionLimits(maxFileBytes = 1_000),
        )
        assertFalse(oversized.allowed)

        val total = ArchiveExtractionPolicy.evaluate(
            archiveBytes = 100,
            entries = listOf(
                ArchiveEntryMetadata("a.bin", false, 100, 700),
                ArchiveEntryMetadata("b.bin", false, 100, 700),
            ),
            limits = ArchiveExtractionLimits(
                maxFileBytes = 1_000,
                maxTotalUncompressedBytes = 1_000,
            ),
        )
        assertFalse(total.allowed)
    }

    @Test
    fun `rejects suspicious compression ratio`() {
        val decision = ArchiveExtractionPolicy.evaluate(
            archiveBytes = 100,
            entries = listOf(ArchiveEntryMetadata("bomb.txt", false, 1, 1_000)),
            limits = ArchiveExtractionLimits(maxCompressionRatio = 100.0),
        )

        assertFalse(decision.allowed)
    }
}
