package io.github.mbaliga.fylz.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Design §2.6, moved up from MC.0e: the command palette's candidate list
 * ([ActionResolver.paletteCandidates]) excludes `requiresTarget` actions, actions with
 * `BuiltInBinding.paletteVisible = false`, and anything currently disabled -- and filtering by
 * label substring narrows it, matching what `CommandPaletteDialog` actually does with the list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CommandPaletteTest {

    private val registry = ActionRegistry(BuiltInActions.all())
    private val resolver = ActionResolver(registry)

    private val nonPaletteVisibleIds = setOf(
        "fylz.history.files", "fylz.backup.plans", "fylz.backup.import", "fylz.archive.tools",
    ).map { ActionId.parse(it) }

    private val requiresTargetIds = registry.bindings.filter { it.def.requiresTarget != null }.map { it.def.id }

    @Test
    fun `candidates exclude target-requiring and paletteVisible-false actions for two fixtures`() {
        for (state in listOf(BrowserStateFixtures.oneFile(), BrowserStateFixtures.threePdfsSelected())) {
            val ids = resolver.paletteCandidates(state).map { it.id }.toSet()
            requiresTargetIds.forEach { id -> assertFalse("$id should not be a candidate", id in ids) }
            nonPaletteVisibleIds.forEach { id -> assertFalse("$id should not be a candidate", id in ids) }
        }
    }

    @Test
    fun `candidates exclude a disabled but visible action`() {
        // fylz.paste is visible whenever a clipboard is set, but disabled without an active tab.
        val noTab = resolver.paletteCandidates(BrowserStateFixtures.noTabClipboardSet())
        assertFalse(noTab.any { it.id == ActionId.parse("fylz.paste") })

        // fylz.rename is visible whenever anything is selected, but disabled unless exactly one is.
        val threeSelected = resolver.paletteCandidates(BrowserStateFixtures.threePdfsSelected())
        assertFalse(threeSelected.any { it.id == ActionId.parse("fylz.rename") })
    }

    @Test
    fun `candidates include an always-visible always-enabled targetless action`() {
        val candidates = resolver.paletteCandidates(BrowserStateFixtures.emptyFolder())
        assertTrue(candidates.any { it.id == ActionId.parse("fylz.refresh") })
    }

    @Test
    fun `filtering by label substring narrows the list`() {
        val candidates = resolver.paletteCandidates(BrowserStateFixtures.emptyFolder())
        val filtered = candidates.filter { it.label.contains("refresh", ignoreCase = true) }
        assertEquals(listOf(ActionId.parse("fylz.refresh")), filtered.map { it.id })
    }
}
