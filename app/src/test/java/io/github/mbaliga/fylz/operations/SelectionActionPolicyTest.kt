package io.github.mbaliga.fylz.operations

import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.core.model.ItemCapability
import io.github.mbaliga.fylz.storage.FileStorageProvider
import io.github.mbaliga.fylz.storage.SafStorageProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionActionPolicyTest {

    /** What both shipping providers actually declare — the everyday evaluation input. */
    private val local: Set<ItemCapability> = FileStorageProvider().capabilities

    private fun evaluate(kinds: List<EntryKind>, offered: Set<ItemCapability> = local) =
        SelectionActionPolicy.evaluate(kinds, offered)

    @Test
    fun `an empty selection offers nothing`() {
        val actions = evaluate(emptyList())

        assertFalse(actions.any)
        assertEquals(0, actions.count)
        assertFalse(actions.copy)
        assertFalse(actions.move)
        assertFalse(actions.recycle)
        assertFalse(actions.share)
    }

    @Test
    fun `one file renames but does not batch rename`() {
        val actions = evaluate(listOf(EntryKind.TEXT))

        assertTrue(actions.rename)
        assertFalse(actions.batchRename)
    }

    @Test
    fun `two files batch rename but do not rename`() {
        val actions = evaluate(listOf(EntryKind.TEXT, EntryKind.IMAGE))

        assertFalse(actions.rename)
        assertTrue(actions.batchRename)
    }

    @Test
    fun `extract needs exactly one archive`() {
        assertTrue(evaluate(listOf(EntryKind.ARCHIVE)).extract)
        assertFalse(evaluate(listOf(EntryKind.ARCHIVE, EntryKind.ARCHIVE)).extract)
        assertFalse(evaluate(listOf(EntryKind.ARCHIVE, EntryKind.PDF)).extract)
        assertFalse(evaluate(listOf(EntryKind.PDF)).extract)
    }

    @Test
    fun `pdf tools need every entry to be a pdf`() {
        assertTrue(evaluate(listOf(EntryKind.PDF, EntryKind.PDF)).pdfTools)
        assertFalse(evaluate(listOf(EntryKind.PDF, EntryKind.IMAGE)).pdfTools)
    }

    /**
     * A folder URI handed to `ACTION_SEND` produces a share sheet that delivers nothing, which is
     * worse than an action that is not offered.
     */
    @Test
    fun `share refuses a selection containing a folder`() {
        assertTrue(evaluate(listOf(EntryKind.IMAGE, EntryKind.VIDEO)).share)
        assertFalse(evaluate(listOf(EntryKind.IMAGE, EntryKind.DIRECTORY)).share)
        assertFalse(evaluate(listOf(EntryKind.DIRECTORY)).share)
    }

    @Test
    fun `folders still copy move recycle tag and archive`() {
        val actions = evaluate(listOf(EntryKind.DIRECTORY))

        assertTrue(actions.copy)
        assertTrue(actions.move)
        assertTrue(actions.recycle)
        assertTrue(actions.tag)
        assertTrue(actions.archive)
    }

    // ── Phase 2 consultation: acceptance law #1 through CapabilityPolicy ─────────────

    /** Both shipping providers must keep offering every gated action — the no-regression pin. */
    @Test
    fun `both shipping providers offer every gated action`() {
        listOf(FileStorageProvider().capabilities, SafStorageProvider().capabilities).forEach { offered ->
            val actions = evaluate(listOf(EntryKind.TEXT), offered)
            assertTrue(actions.copy)
            assertTrue(actions.move)
            assertTrue(actions.recycle)
            assertTrue(actions.rename)
        }
    }

    @Test
    fun `a provider without trash support does not offer recycle`() {
        val actions = evaluate(listOf(EntryKind.TEXT), local - ItemCapability.TRASH)

        assertFalse(actions.recycle)
        // The rest of the selection's abilities are untouched by the one missing capability.
        assertTrue(actions.copy)
        assertTrue(actions.move)
        assertTrue(actions.rename)
    }

    @Test
    fun `a provider without rename support offers neither rename nor batch rename`() {
        val without = local - ItemCapability.RENAME
        assertFalse(evaluate(listOf(EntryKind.TEXT), without).rename)
        assertFalse(evaluate(listOf(EntryKind.TEXT, EntryKind.IMAGE), without).batchRename)
    }

    @Test
    fun `a read-only provider offers no transfer actions but keeps app-level ones`() {
        val readOnly = setOf(ItemCapability.READ, ItemCapability.STREAM_READ, ItemCapability.LIST)
        val actions = evaluate(listOf(EntryKind.IMAGE), readOnly)

        assertFalse(actions.copy)
        assertFalse(actions.move)
        assertFalse(actions.recycle)
        assertFalse(actions.rename)
        // Tags live in the app's own LibraryStore; share reads through ACTION_SEND. Neither
        // asks the provider to change anything, so neither is provider-gated.
        assertTrue(actions.tag)
        assertTrue(actions.share)
    }

    @Test
    fun `copy requires read as well as copy`() {
        val copyWithoutRead = local - ItemCapability.READ
        assertFalse(evaluate(listOf(EntryKind.TEXT), copyWithoutRead).copy)
    }
}
