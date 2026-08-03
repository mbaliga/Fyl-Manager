package io.github.mbaliga.fylz.data

import org.junit.Assert.assertThrows
import org.junit.Test

class ArchiveSafetyPolicyTest {
    private val limits = ArchiveExtractionLimits(
        maxEntries = 3,
        maxSingleFileBytes = 100,
        maxTotalUncompressedBytes = 150,
        maxCompressionRatio = 10.0,
        maxPathDepth = 3,
        maxNameLength = 40,
    )

    @Test
    fun `normal archive passes`() {
        ArchiveSafetyPolicy.validate(
            listOf(
                ArchiveEntryFacts("folder/", true, 0, 0),
                ArchiveEntryFacts("folder/a.txt", false, 10, 40),
                ArchiveEntryFacts("b.txt", false, 20, 60),
            ),
            limits,
        )
    }

    @Test
    fun `single oversized file is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveSafetyPolicy.validate(
                listOf(ArchiveEntryFacts("large.bin", false, 50, 101)),
                limits,
            )
        }
    }

    @Test
    fun `aggregate expansion is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveSafetyPolicy.validate(
                listOf(
                    ArchiveEntryFacts("a.bin", false, 20, 80),
                    ArchiveEntryFacts("b.bin", false, 20, 80),
                ),
                limits,
            )
        }
    }

    @Test
    fun `compression bomb ratio is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveSafetyPolicy.validate(
                listOf(ArchiveEntryFacts("bomb.bin", false, 1, 100)),
                limits,
            )
        }
    }

    @Test
    fun `deep paths are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveSafetyPolicy.validate(
                listOf(ArchiveEntryFacts("a/b/c/d.txt", false, 10, 20)),
                limits,
            )
        }
    }
}
