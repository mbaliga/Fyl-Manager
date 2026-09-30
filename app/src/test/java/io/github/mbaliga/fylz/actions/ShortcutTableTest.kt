package io.github.mbaliga.fylz.actions

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design §2.7 item 3: the chord table equals §2.5's literally, `problems` empty for the
 * built-ins, and a synthetic conflict/target-requiring placement each produce exactly one
 * [RegistryProblem]. */
class ShortcutTableTest {

    private fun registry() = ActionRegistry(BuiltInActions.all())

    @Test
    fun `the shortcut table matches design section 2 point 5 literally`() {
        val expected = mapOf(
            KeyChord(Key.X, ctrl = true) to "fylz.cut",
            KeyChord(Key.C, ctrl = true) to "fylz.copy",
            KeyChord(Key.V, ctrl = true) to "fylz.paste",
            KeyChord(Key.A, ctrl = true) to "fylz.select.all",
            KeyChord(Key.Escape) to "fylz.select.clear",
            KeyChord(Key.Delete) to "fylz.recycle",
            KeyChord(Key.F2) to "fylz.rename",
            KeyChord(Key.Enter) to "fylz.open",
            KeyChord(Key.N, ctrl = true) to "fylz.new-file",
            KeyChord(Key.N, ctrl = true, shift = true) to "fylz.new-folder",
            KeyChord(Key.T, ctrl = true) to "fylz.tab.add",
            KeyChord(Key.W, ctrl = true) to "fylz.tab.close",
            KeyChord(Key.F, ctrl = true) to "fylz.search.focus",
            KeyChord(Key.F5) to "fylz.refresh",
            KeyChord(Key.R, ctrl = true) to "fylz.refresh",
            KeyChord(Key.DirectionUp, alt = true) to "fylz.navigate.up",
            KeyChord(Key.Backspace) to "fylz.navigate.up",
            KeyChord(Key.K, ctrl = true) to "fylz.commands",
            KeyChord(Key.H, ctrl = true, shift = true) to "fylz.history.operations",
        ).mapValues { (_, id) -> ActionId.parse(id) }

        assertEquals(expected, registry().shortcuts())
    }

    @Test
    fun `problems is empty for the built-ins`() {
        assertTrue(registry().problems.toString(), registry().problems.isEmpty())
    }

    @Test
    fun `a synthetic duplicate chord conflicts and binds nothing`() {
        val chord = KeyChord(Key.X, ctrl = true) // already claimed by fylz.cut
        val extra = BuiltInBinding(
            def = ActionDef(
                id = ActionId.parse("fylz.test.synthetic-duplicate"),
                titleKey = "Synthetic",
                icon = IconRef.Builtin("Close"),
                placements = listOf(Placement.Shortcut(chord)),
                confirm = ConfirmPolicy.None,
                body = ActionBody.BuiltIn("fylz.test.synthetic-duplicate"),
                origin = Origin.BuiltIn,
            ),
            visibleWhen = { true },
            enabledWhen = { true },
            run = { _, _, _ -> },
        )
        val registry = ActionRegistry(BuiltInActions.all() + extra)

        val conflicts = registry.problems.filterIsInstance<RegistryProblem.ShortcutConflict>()
        assertEquals(1, conflicts.size)
        assertEquals(chord, conflicts.single().chord)
        assertEquals(
            setOf(ActionId.parse("fylz.cut"), ActionId.parse("fylz.test.synthetic-duplicate")),
            conflicts.single().ids.toSet(),
        )
        assertNull(registry.shortcuts()[chord])
    }

    @Test
    fun `a requiresTarget action on a targetless placement is flagged`() {
        val extra = BuiltInBinding(
            def = ActionDef(
                id = ActionId.parse("fylz.test.bad-placement"),
                titleKey = "Bad placement",
                icon = IconRef.Builtin("Close"),
                placements = listOf(Placement.Toolbar(Bar.TOP_APP_BAR, 999)),
                confirm = ConfirmPolicy.None,
                body = ActionBody.BuiltIn("fylz.test.bad-placement"),
                origin = Origin.BuiltIn,
                requiresTarget = TargetKind.ENTRY,
            ),
            visibleWhen = { true },
            enabledWhen = { true },
            run = { _, _, _ -> },
        )
        val registry = ActionRegistry(listOf(extra))

        val problems = registry.problems.filterIsInstance<RegistryProblem.TargetRequiredOnTargetlessPlacement>()
        assertEquals(1, problems.size)
        assertEquals(ActionId.parse("fylz.test.bad-placement"), problems.single().id)
    }
}
