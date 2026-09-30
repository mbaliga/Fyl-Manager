package io.github.mbaliga.fylz.decoder

import io.github.mbaliga.fylz.storage.VolumeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ArchiveLimits.forInspection`/`forExtraction` (`docs/agent/DESIGN-M34-SELECTIVE-EXTRACT.md`
 * section 2.4): the per-volume, per-consent caps, the total cap recomputed at claim, the consent
 * thresholds, and the JSON the plan persists. Plain JVM (`org.json` is the real implementation on
 * the test classpath).
 */
class ArchiveLimitsTest {

    private val gib = 1024L * 1024L * 1024L
    private val mib = 1024L * 1024L

    private fun volume(fs: String?, free: Long?) = VolumeInfo(filesystemType = fs, freeBytes = free, caseInsensitive = fs == "vfat" || fs == "exfat")

    @Test
    fun `forInspection is the engine default`() {
        assertEquals(ArchiveLimits(), ArchiveLimits.forInspection())
        assertEquals(10_000, ArchiveLimits().maxEntries)
        assertEquals(200_000, ArchiveLimits().maxListingEntries)
    }

    @Test
    fun `forExtraction caps the per-file size only on vfat and never stages the archive`() {
        val vfat = ArchiveLimits.forExtraction(volume("vfat", 64L * gib), consent = false)
        assertEquals(4L * gib - 1L, vfat.maxFileBytes)
        assertEquals(ArchiveLimits.HARD_CEILING_BYTES, vfat.maxArchiveBytes)
        val ext4 = ArchiveLimits.forExtraction(volume("ext4", 64L * gib), consent = false)
        assertEquals(ArchiveLimits.HARD_CEILING_BYTES, ext4.maxFileBytes)
        val exfat = ArchiveLimits.forExtraction(volume("exfat", 64L * gib), consent = true)
        assertEquals(ArchiveLimits.HARD_CEILING_BYTES, exfat.maxFileBytes)
        val unknownFs = ArchiveLimits.forExtraction(volume(null, 64L * gib), consent = true)
        assertEquals(ArchiveLimits.HARD_CEILING_BYTES, unknownFs.maxFileBytes)
    }

    @Test
    fun `the entry cap is 10000 without consent and the listing bound with it, the rest unchanged`() {
        val without = ArchiveLimits.forExtraction(volume("ext4", 64L * gib), consent = false)
        val with = ArchiveLimits.forExtraction(volume("ext4", 64L * gib), consent = true)
        assertEquals(10_000, without.maxEntries)
        assertEquals(200_000, with.maxEntries)
        listOf(without, with).forEach { limits ->
            assertEquals(200.0, limits.maxCompressionRatio, 0.0)
            assertEquals(64, limits.maxPathDepth)
            assertEquals(255, limits.maxNameLength)
            assertEquals(200_000, limits.maxListingEntries)
        }
    }

    @Test
    fun `the total cap follows the free space with consent and holds at 4 GiB without it`() {
        // 20 GB free: the margin is 5 % (1 GB), above the 100 MiB floor.
        val free = 20L * gib
        assertEquals(free - free / 20L, ArchiveLimits.totalCap(free, consent = true))
        assertEquals(4L * gib, ArchiveLimits.totalCap(free, consent = false))
        // 1 GiB free: the margin is the 100 MiB floor.
        assertEquals(gib - 100L * mib, ArchiveLimits.totalCap(gib, consent = true))
        assertEquals(gib - 100L * mib, ArchiveLimits.totalCap(gib, consent = false))
        // 50 MiB free: nothing fits under the margin.
        assertEquals(0L, ArchiveLimits.totalCap(50L * mib, consent = true))
        // Unknown free space: exactly 4 GiB, consent or not (least knowledge keeps the old cap).
        assertEquals(4L * gib, ArchiveLimits.totalCap(null, consent = true))
        assertEquals(4L * gib, ArchiveLimits.totalCap(-1L, consent = false))
        assertEquals(4L * gib, ArchiveLimits.forExtraction(volume("ext4", null), consent = true).maxTotalUncompressedBytes)
        assertEquals(4L * gib, ArchiveLimits.forExtraction(null, consent = true).maxTotalUncompressedBytes)
        assertEquals(free - free / 20L, ArchiveLimits.forExtraction(volume("ext4", free), consent = true).maxTotalUncompressedBytes)
    }

    @Test
    fun `recomputing at claim keeps the smaller of the planned cap and the current free space`() {
        val planned = ArchiveLimits.forExtraction(volume("ext4", 20L * gib), consent = true)
        val nowFree = 6L * gib
        val recomputed = planned.copy(maxTotalUncompressedBytes = minOf(planned.maxTotalUncompressedBytes, ArchiveLimits.totalCap(nowFree, consent = true)))
        assertEquals(nowFree - nowFree / 20L, recomputed.maxTotalUncompressedBytes)
        val moreFreeNow = planned.copy(maxTotalUncompressedBytes = minOf(planned.maxTotalUncompressedBytes, ArchiveLimits.totalCap(40L * gib, consent = true)))
        assertEquals("never above what was consented to", planned.maxTotalUncompressedBytes, moreFreeNow.maxTotalUncompressedBytes)
    }

    @Test
    fun `consent is needed above 4 GiB expanded or above 10000 entries, not at them`() {
        assertFalse(ArchiveLimits.needsConsent(4L * gib, 10_000))
        assertTrue(ArchiveLimits.needsConsent(4L * gib + 1L, 1))
        assertTrue(ArchiveLimits.needsConsent(1L, 10_001))
        assertFalse(ArchiveLimits.needsConsent(0L, 0))
    }

    @Test
    fun `the JSON round trip keeps every field and refuses garbage`() {
        val limits = ArchiveLimits(maxEntries = 7, maxArchiveBytes = 8L, maxFileBytes = 9L, maxTotalUncompressedBytes = 10L, maxCompressionRatio = 11.5, maxPathDepth = 12, maxNameLength = 13, maxListingEntries = 14)
        assertEquals(limits, ArchiveLimits.fromJson(limits.toJson()))
        val big = ArchiveLimits.forExtraction(volume("vfat", 20L * gib), consent = true)
        assertEquals(big, ArchiveLimits.fromJson(big.toJson()))
        assertThrows(IllegalArgumentException::class.java) { ArchiveLimits.fromJson("{}") }
        assertThrows(IllegalArgumentException::class.java) { ArchiveLimits.fromJson("not json") }
    }
}
