package io.github.mbaliga.fylz.actions

import androidx.compose.ui.input.key.Key
import io.github.mbaliga.fylz.model.ClipboardMode
import io.github.mbaliga.fylz.model.FylzClipboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Design §2.6, MC.0e: [KeyRouter.route] with a focused text field routes every chord to `null`
 * except Escape, which routes to [Routed.ClearFocus]; otherwise it's a lookup in the registry's
 * own shortcut table, gated the same way [ActionDispatcher.run] gates a click, with the two
 * documented target conventions supplied for the chords that need one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeyRouterTest {

    private val registry = ActionRegistry(BuiltInActions.all())
    private val allBoundChords = registry.shortcuts().keys

    @Test
    fun `every bound chord routes to null while a text field is focused, except Escape`() {
        for (chord in allBoundChords) {
            val routed = KeyRouter.route(chord, textFieldFocused = true, registry = registry, state = BrowserStateFixtures.oneFile())
            if (chord == KeyChord(Key.Escape)) {
                assertEquals("Escape while focused", Routed.ClearFocus, routed)
            } else {
                assertNull("$chord while focused", routed)
            }
        }
    }

    @Test
    fun `an unbound chord routes to null`() {
        val chord = KeyChord(Key.Z, ctrl = true, shift = true, alt = true) // claimed by nothing
        assertNull(KeyRouter.route(chord, textFieldFocused = false, registry = registry, state = BrowserStateFixtures.oneFile()))
    }

    @Test
    fun `a bound chord for a disabled action routes to null`() {
        // fylz.rename (F2) is visible with any selection but only enabled for exactly one.
        val chord = KeyChord(Key.F2)
        val threeSelected = BrowserStateFixtures.threePdfsSelected()
        assertNull(KeyRouter.route(chord, textFieldFocused = false, registry = registry, state = threeSelected))
    }

    @Test
    fun `Enter with a focused entry dispatches fylz open targeting it`() {
        val state = BrowserStateFixtures.focusedFileEmptySelection()
        val routed = KeyRouter.route(KeyChord(Key.Enter), textFieldFocused = false, registry = registry, state = state)
        val expected = Routed.Dispatch(ActionId.parse("fylz.open"), ActionTarget.Entry(state.focused!!))
        assertEquals(expected, routed)
    }

    @Test
    fun `Enter with no focused entry never guesses a target`() {
        val state = BrowserStateFixtures.emptyFolder()
        assertNull(KeyRouter.route(KeyChord(Key.Enter), textFieldFocused = false, registry = registry, state = state))
    }

    @Test
    fun `Ctrl W with an active tab dispatches fylz tab close targeting it`() {
        val state = BrowserStateFixtures.oneFile().copy(activeTabId = "tab-1")
        val routed = KeyRouter.route(KeyChord(Key.W, ctrl = true), textFieldFocused = false, registry = registry, state = state)
        assertEquals(Routed.Dispatch(ActionId.parse("fylz.tab.close"), ActionTarget.Tab("tab-1")), routed)
    }

    @Test
    fun `Ctrl W with no active tab never guesses a target`() {
        val state = BrowserStateFixtures.oneFile() // activeTabId defaults to null
        assertNull(KeyRouter.route(KeyChord(Key.W, ctrl = true), textFieldFocused = false, registry = registry, state = state))
    }

    @Test
    fun `every chord in the shortcut table routes to its own id when enabled`() {
        // A single selected, focused item with an active tab, a clipboard and a deep folder
        // satisfies every bound chord's enabledWhen at once (exactly one selected also satisfies
        // fylz.rename's "== 1" gate, which a larger selection wouldn't).
        val base = BrowserStateFixtures.onePdfSelected()
        val enabledEverything = base.copy(
            clipboard = FylzClipboard(ClipboardMode.COPY, base.selection),
            focused = base.selection.first(),
            activeTabId = "tab-1",
        )
        for ((chord, id) in registry.shortcuts()) {
            val routed = KeyRouter.route(chord, textFieldFocused = false, registry = registry, state = enabledEverything)
            val dispatched = routed as? Routed.Dispatch
            assertEquals("$chord -> $id", id, dispatched?.id)
        }
    }
}
