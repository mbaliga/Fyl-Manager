package io.github.mbaliga.fylz.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Random

class ArchiveExtractionPolicyFuzzTest {
    @Test
    fun `hostile path corpus is always rejected`() {
        val hostile = listOf(
            "../escape",
            "..\\escape",
            "/absolute",
            "\\absolute",
            "C:/windows/system32",
            "C:\\windows\\system32",
            "folder/./file",
            "folder/../file",
            "folder//../file",
            "\u0000payload",
            "",
            "   ",
        )

        hostile.forEach { path ->
            val result = ArchiveExtractionPolicy.evaluate(
                archiveBytes = 100L,
                entries = listOf(ArchiveEntryMetadata(path, false, 10L, 20L)),
            )
            assertFalse("Expected rejection for $path", result.allowed)
            assertNotNull(result.reason)
        }
    }

    @Test
    fun `random traversal variants never pass`() {
        val random = Random(0xF71A)
        repeat(2_000) { iteration ->
            val prefix = buildString {
                repeat(random.nextInt(8)) {
                    append(('a'.code + random.nextInt(26)).toChar())
                }
            }
            val separator = if (random.nextBoolean()) '/' else '\\'
            val path = "$prefix$separator..$separator${iteration}.bin"
            val result = ArchiveExtractionPolicy.evaluate(
                archiveBytes = 128L,
                entries = listOf(ArchiveEntryMetadata(path, false, 16L, 32L)),
            )
            assertFalse("Traversal variant passed: $path", result.allowed)
        }
    }

    @Test
    fun `random bounded normal paths do not crash policy evaluation`() {
        val random = Random(44L)
        repeat(5_000) { iteration ->
            val depth = 1 + random.nextInt(8)
            val path = (0 until depth).joinToString("/") { level ->
                "segment-${iteration}-${level}-${random.nextInt(10_000)}"
            } + ".txt"
            ArchiveExtractionPolicy.evaluate(
                archiveBytes = 1_024L,
                entries = listOf(
                    ArchiveEntryMetadata(
                        name = path,
                        directory = false,
                        compressedBytes = 100L,
                        uncompressedBytes = 500L,
                    ),
                ),
            )
        }
    }

    @Test
    fun `overflow and implausible compression corpus is rejected`() {
        val cases = listOf(
            ArchiveEntryMetadata("negative-compressed", false, -1L, 10L),
            ArchiveEntryMetadata("negative-expanded", false, 1L, -1L),
            ArchiveEntryMetadata("zero-compressed", false, 0L, 10L),
            ArchiveEntryMetadata("ratio-bomb", false, 1L, 1_000_000L),
            ArchiveEntryMetadata("oversized", false, 1_000L, Long.MAX_VALUE),
        )

        cases.forEach { entry ->
            assertFalse(
                ArchiveExtractionPolicy.evaluate(
                    archiveBytes = 1_024L,
                    entries = listOf(entry),
                    limits = ArchiveExtractionLimits(
                        maxFileBytes = 10_000L,
                        maxTotalUncompressedBytes = 20_000L,
                        maxCompressionRatio = 100.0,
                    ),
                ).allowed,
            )
        }
    }
}
