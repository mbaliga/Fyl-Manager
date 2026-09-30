package io.github.mbaliga.fylz.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveSpacePolicyTest {
    @Test
    fun `requirements include archive expanded bytes and headroom`() {
        val requirements = ArchiveSpacePolicy.requirements(
            archiveBytes = 10L * 1024L * 1024L,
            uncompressedBytes = 100L * 1024L * 1024L,
        )

        assertNotNull(requirements)
        requireNotNull(requirements)
        assertTrue(requirements.temporaryBytes > 110L * 1024L * 1024L)
        assertTrue(requirements.destinationBytes > 100L * 1024L * 1024L)
    }

    @Test
    fun `unknown provider capacity is allowed but remains unknown`() {
        val decision = ArchiveSpacePolicy.evaluate(100L, null, "destination")

        assertTrue(decision.allowed)
        assertNull(decision.availableBytes)
    }

    @Test
    fun `known insufficient capacity is rejected`() {
        val decision = ArchiveSpacePolicy.evaluate(1_000L, 999L, "temporary")

        assertFalse(decision.allowed)
        assertEquals(1L, decision.requiredBytes - requireNotNull(decision.availableBytes))
    }

    @Test
    fun `known sufficient capacity is accepted`() {
        assertTrue(ArchiveSpacePolicy.evaluate(1_000L, 1_000L, "destination").allowed)
    }

    @Test
    fun `overflowed requirements are rejected`() {
        assertNull(ArchiveSpacePolicy.requirements(Long.MAX_VALUE, 1L))
        assertNull(ArchiveSpacePolicy.requirements(1L, Long.MAX_VALUE))
    }

    @Test
    fun `staging requirement is the declared size or the whole limit, plus the minimum headroom`() {
        val headroom = ArchiveSpacePolicy.MIN_TEMPORARY_HEADROOM
        assertEquals(16L * 1024L * 1024L, headroom)
        assertEquals(1_000L + headroom, ArchiveSpacePolicy.stagingRequirement(1_000L, 5_000L))
        // Unknown size: the provider may send anything up to the limit, so that is what is reserved.
        assertEquals(5_000L + headroom, ArchiveSpacePolicy.stagingRequirement(null, 5_000L))
        assertEquals(headroom, ArchiveSpacePolicy.stagingRequirement(0L, 5_000L))
        assertEquals(headroom, ArchiveSpacePolicy.stagingRequirement(-1L, 5_000L))
        assertEquals(Long.MAX_VALUE, ArchiveSpacePolicy.stagingRequirement(Long.MAX_VALUE, 5_000L))
        assertEquals(Long.MAX_VALUE, ArchiveSpacePolicy.stagingRequirement(null, Long.MAX_VALUE))
    }

    @Test
    fun `negative values are rejected`() {
        assertNull(ArchiveSpacePolicy.requirements(-1L, 100L))
        assertFalse(ArchiveSpacePolicy.evaluate(-1L, 100L, "temporary").allowed)
    }
}
