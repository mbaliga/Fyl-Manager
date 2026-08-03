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
    fun `negative values are rejected`() {
        assertNull(ArchiveSpacePolicy.requirements(-1L, 100L))
        assertFalse(ArchiveSpacePolicy.evaluate(-1L, 100L, "temporary").allowed)
    }
}
