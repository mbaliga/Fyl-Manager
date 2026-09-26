package io.github.mbaliga.fylz.actions

import androidx.compose.ui.input.key.Key

/**
 * What [KeyRouter.route] hands back: either an action to dispatch, or [Routed.ClearFocus] -- the
 * one case Escape means inside a focused text field, which isn't an action at all (design §2.6
 * writes it as "a `ClearFocus` sentinel (not an action)"; [Routed] is the sealed type both live
 * under, since a single function can't return an `ActionId` in one branch and something that isn't
 * one in another).
 */
sealed interface Routed {
    data class Dispatch(val id: ActionId, val target: ActionTarget?) : Routed
    data object ClearFocus : Routed
}

private val OPEN_ID = ActionId.parse("fylz.open")
private val TAB_CLOSE_ID = ActionId.parse("fylz.tab.close")
private val ESCAPE_CHORD = KeyChord(Key.Escape)

/**
 * Pure key routing (design §2.6). A focused text field wins outright: every chord but Escape
 * routes to `null` so the field keeps its own editing keys (Delete/Ctrl+V/Ctrl+A/...); Escape
 * routes to [Routed.ClearFocus] rather than `fylz.select.clear`, since a field wants to give up
 * focus, not clear the selection. Otherwise the chord is looked up in the registry's own shortcut
 * table, gated by `visibleWhen`/`enabledWhen` like any other dispatch, with the two documented
 * target conventions supplied here since a [Placement.Shortcut] carries no per-invocation target
 * of its own: Enter (`fylz.open`) resolves to the focused entry, Ctrl+W (`fylz.tab.close`) to the
 * active tab -- either missing means no target, and a `requiresTarget` action with no target never
 * dispatches (never guessed).
 */
object KeyRouter {
    fun route(chord: KeyChord, textFieldFocused: Boolean, registry: ActionRegistry, state: BrowserState): Routed? {
        if (textFieldFocused) {
            return if (chord == ESCAPE_CHORD) Routed.ClearFocus else null
        }

        val id = registry.shortcuts()[chord] ?: return null
        val binding = registry.byId[id] ?: return null
        if (!binding.visibleWhen(state) || !binding.enabledWhen(state)) return null

        val target: ActionTarget? = when (id) {
            OPEN_ID -> state.focused?.let { ActionTarget.Entry(it) } ?: return null
            TAB_CLOSE_ID -> state.activeTabId?.let { ActionTarget.Tab(it) } ?: return null
            else -> null
        }
        if (binding.def.requiresTarget != null && target == null) return null

        return Routed.Dispatch(id, target)
    }
}
