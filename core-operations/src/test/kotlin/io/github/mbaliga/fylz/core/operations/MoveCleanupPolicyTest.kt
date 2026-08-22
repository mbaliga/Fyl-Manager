package io.github.mbaliga.fylz.core.operations

import io.github.mbaliga.fylz.core.model.VersionStamp
import org.junit.Assert.assertEquals
import org.junit.Test

class MoveCleanupPolicyTest {

    private fun stamp(size: Long? = 100L, modified: Long? = 1_000L) =
        VersionStamp.Composite(sizeBytes = size, modifiedAtMillis = modified)

    // ── Proceed: both endpoints restate the journal exactly ──────────────────────────

    @Test
    fun `both endpoints unchanged proceeds`() {
        assertEquals(
            MoveCleanupPolicy.Decision.Proceed,
            MoveCleanupPolicy.decide(stamp(), stamp(), stamp(size = 200L), stamp(size = 200L)),
        )
    }

    @Test
    fun `matching revision tokens on both endpoints proceed`() {
        assertEquals(
            MoveCleanupPolicy.Decision.Proceed,
            MoveCleanupPolicy.decide(
                VersionStamp.Revision("s1"),
                VersionStamp.Revision("s1"),
                VersionStamp.Revision("d1"),
                VersionStamp.Revision("d1"),
            ),
        )
    }

    // ── Blocks: provable change on either endpoint ───────────────────────────────────

    @Test
    fun `destination size change blocks as destination changed`() {
        assertEquals(
            MoveCleanupPolicy.Decision.DestinationChanged,
            MoveCleanupPolicy.decide(stamp(), stamp(), stamp(size = 200L), stamp(size = 300L)),
        )
    }

    @Test
    fun `destination change outranks source change`() {
        // Both provably changed: the destination verdict is reported, because an unverified
        // destination makes the source the only trustworthy copy whatever else happened.
        assertEquals(
            MoveCleanupPolicy.Decision.DestinationChanged,
            MoveCleanupPolicy.decide(
                stamp(modified = 1_000L),
                stamp(modified = 2_000L),
                stamp(size = 200L),
                stamp(size = 300L),
            ),
        )
    }

    @Test
    fun `source modified after copy blocks as source modified`() {
        assertEquals(
            MoveCleanupPolicy.Decision.SourceModified,
            MoveCleanupPolicy.decide(stamp(modified = 1_000L), stamp(modified = 2_000L), stamp(), stamp()),
        )
    }

    @Test
    fun `source change still blocks when destination evidence is absent`() {
        assertEquals(
            MoveCleanupPolicy.Decision.SourceModified,
            MoveCleanupPolicy.decide(stamp(size = 100L), stamp(size = 101L), null, null),
        )
    }

    // ── Insufficient evidence: never a block, never a green light ────────────────────

    @Test
    fun `null journal stamps fall back to legacy checks`() {
        // Every pre-v3 record decodes to null stamps: the policy must reproduce the legacy
        // route, not invent a verdict.
        assertEquals(
            MoveCleanupPolicy.Decision.InsufficientEvidence,
            MoveCleanupPolicy.decide(null, stamp(), null, stamp()),
        )
    }

    @Test
    fun `provider declining a field yields insufficient evidence not a block`() {
        assertEquals(
            MoveCleanupPolicy.Decision.InsufficientEvidence,
            MoveCleanupPolicy.decide(stamp(size = null), stamp(size = null), stamp(), stamp()),
        )
    }

    @Test
    fun `directory-shaped stamps fall back`() {
        // Directories carry no meaningful size; both observations agree on modified time only.
        val dir = VersionStamp.Composite(sizeBytes = null, modifiedAtMillis = 5_000L)
        assertEquals(
            MoveCleanupPolicy.Decision.InsufficientEvidence,
            MoveCleanupPolicy.decide(dir, dir, dir, dir),
        )
    }

    @Test
    fun `one same endpoint and one unknown endpoint is still insufficient`() {
        assertEquals(
            MoveCleanupPolicy.Decision.InsufficientEvidence,
            MoveCleanupPolicy.decide(stamp(), stamp(), null, stamp()),
        )
    }

    @Test
    fun `stamp shape change mid-comparison is unknown not changed`() {
        assertEquals(
            MoveCleanupPolicy.Decision.InsufficientEvidence,
            MoveCleanupPolicy.decide(stamp(), VersionStamp.Revision("r1"), stamp(), stamp()),
        )
    }
}
