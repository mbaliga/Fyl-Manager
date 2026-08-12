package io.github.mbaliga.fylz.core.vfs

import io.github.mbaliga.fylz.core.model.ItemCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityPolicyTest {

    @Test
    fun `every action declares at least one requirement`() {
        UserAction.entries.forEach { action ->
            assertTrue("$action must require something", CapabilityPolicy.requirements(action).isNotEmpty())
        }
    }

    @Test
    fun `an offered superset of the requirement is allowed`() {
        val offered = setOf(ItemCapability.RENAME, ItemCapability.LIST, ItemCapability.READ)
        assertTrue(CapabilityPolicy.canPerform(UserAction.RENAME, offered))
    }

    @Test
    fun `missing exactly one required capability refuses the action`() {
        // COPY needs READ + COPY; offering only READ must refuse, not partially allow.
        val offered = setOf(ItemCapability.READ)
        assertFalse(CapabilityPolicy.canPerform(UserAction.COPY, offered))
        assertEquals(setOf(ItemCapability.COPY), CapabilityPolicy.missing(UserAction.COPY, offered))
    }

    @Test
    fun `an empty offered set refuses every action`() {
        UserAction.entries.forEach { action ->
            assertFalse(CapabilityPolicy.canPerform(action, emptySet()))
        }
    }

    @Test
    fun `decide reports no gap when allowed`() {
        val decision = CapabilityPolicy.decide(UserAction.LIST, setOf(ItemCapability.LIST))
        assertTrue(decision.allowed)
        assertTrue(decision.missing.isEmpty())
        assertNull(CapabilityPolicy.explain(decision))
    }

    @Test
    fun `explain names every missing capability and is never blank`() {
        val decision = CapabilityPolicy.decide(UserAction.SEARCH_CONTENT, emptySet())
        val explanation = requireNotNull(CapabilityPolicy.explain(decision))
        assertTrue(explanation.isNotBlank())
        assertTrue(explanation.contains("content search"))
        assertTrue(explanation.contains("read"))
    }

    @Test
    fun `trash restore and permanent delete are three distinct requirements`() {
        // A provider could plausibly offer any subset of these independently (e.g. delete
        // permanently but have no trash concept at all) -- pin that the policy keeps them apart
        // rather than conflating "can trash" with "can permanently delete".
        assertTrue(CapabilityPolicy.canPerform(UserAction.MOVE_TO_TRASH, setOf(ItemCapability.TRASH)))
        assertFalse(CapabilityPolicy.canPerform(UserAction.RESTORE_FROM_TRASH, setOf(ItemCapability.TRASH)))
        assertFalse(CapabilityPolicy.canPerform(UserAction.DELETE_PERMANENTLY, setOf(ItemCapability.TRASH)))
    }
}
