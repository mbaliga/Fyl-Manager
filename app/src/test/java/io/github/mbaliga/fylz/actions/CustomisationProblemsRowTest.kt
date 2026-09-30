package io.github.mbaliga.fylz.actions

import androidx.compose.ui.input.key.Key
import io.github.mbaliga.fylz.browse.SortSpec
import io.github.mbaliga.fylz.model.PreviewMode
import io.github.mbaliga.fylz.model.ThemeMode
import io.github.mbaliga.fylz.model.ViewMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design MC.0e (§2.3 clarification): `fylz.customisation.problems`' `visibleWhen` reads
 * `registryProblemCount > 0`. Fixture-free -- a minimal [BrowserState] built by hand, so this
 * exercises the real pipeline (a registry's own `problems.size` feeding the state the resolver
 * reads) rather than [BrowserStateFixtures]' fixed `registryProblemCount = 0` default.
 */
class CustomisationProblemsRowTest {

    private val problemsId = ActionId.parse("fylz.customisation.problems")

    private fun minimalState(registryProblemCount: Int) = BrowserState(
        hasActiveTab = false,
        canNavigateUp = false,
        entries = emptyList(),
        visibleEntries = emptyList(),
        selection = emptyList(),
        selectionOrder = emptyList(),
        focused = null,
        clipboard = null,
        sortSpec = SortSpec.Default,
        viewMode = ViewMode.LIST,
        previewMode = PreviewMode.DOCKED,
        query = "",
        searchRecursive = false,
        themeMode = ThemeMode.SYSTEM,
        currentFolderIsFavourite = false,
        legacyBinCount = 0,
        operationsNeedingAttention = 0,
        registryProblemCount = registryProblemCount,
    )

    @Test
    fun `the row is hidden for the shipped built-ins`() {
        val registry = ActionRegistry(BuiltInActions.all())
        val resolver = ActionResolver(registry)
        assertTrue(registry.problems.isEmpty())

        val ids = resolver.resolve(PlacementQuery.Room(RoomId.TOOLS), minimalState(registry.problems.size)).map { it.id }
        assertFalse(problemsId in ids)
    }

    @Test
    fun `a synthetic shortcut conflict makes the row visible`() {
        val conflicting = BuiltInBinding(
            def = ActionDef(
                id = ActionId.parse("fylz.test.synthetic-conflict"),
                titleKey = "Synthetic",
                icon = IconRef.Builtin("Close"),
                placements = listOf(Placement.Shortcut(KeyChord(Key.X, ctrl = true))),
                confirm = ConfirmPolicy.None,
                body = ActionBody.BuiltIn("fylz.test.synthetic-conflict"),
                origin = Origin.BuiltIn,
            ),
            visibleWhen = { true },
            enabledWhen = { true },
            run = { _, _, _ -> },
        )
        val registry = ActionRegistry(BuiltInActions.all() + conflicting)
        val resolver = ActionResolver(registry)
        assertTrue(registry.problems.isNotEmpty())

        val ids = resolver.resolve(PlacementQuery.Room(RoomId.TOOLS), minimalState(registry.problems.size)).map { it.id }
        assertTrue(problemsId in ids)
    }
}
