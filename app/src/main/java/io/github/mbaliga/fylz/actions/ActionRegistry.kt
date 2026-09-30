package io.github.mbaliga.fylz.actions

/**
 * Problems found once, at construction (design §2.4). A chord in [RegistryProblem.ShortcutConflict]
 * binds to none of its claimants. [RegistryProblem.TargetRequiredOnTargetlessPlacement] fires for a
 * `requiresTarget` action placed on [Placement.CommandPalette], [Placement.Toolbar],
 * [Placement.Menu] or [Placement.SelectionBar] -- the placements that render a single list over
 * [BrowserState] with no per-invocation target available. [Placement.Shortcut] and [Placement.Room]
 * are deliberately excluded from that check: the built-in table itself gives specific
 * `requiresTarget` actions on those two placements a documented, conventional way to obtain a
 * target (`fylz.open`'s Enter resolves to the focused entry, `fylz.tab.close`'s Ctrl+W resolves to
 * the active tab, its Locations-room placement to the row it is drawn against) -- a generic
 * per-placement-type ban would flag exactly the built-ins design §2.5 requires, contradicting the
 * "problems empty for the built-ins" acceptance bar (design §7). See the implementation report for
 * this resolved ambiguity.
 */
sealed interface RegistryProblem {
    data class ShortcutConflict(val chord: KeyChord, val ids: List<ActionId>) : RegistryProblem
    data class DuplicateId(val id: ActionId) : RegistryProblem
    data class TargetRequiredOnTargetlessPlacement(val id: ActionId, val placement: Placement) : RegistryProblem
}

class ActionRegistry(val bindings: List<BuiltInBinding>) {
    val byId: Map<ActionId, BuiltInBinding> = bindings.associateBy { it.def.id }

    val problems: List<RegistryProblem>
    private val shortcutMap: Map<KeyChord, ActionId>
    private val edgeRoomMap: Map<GestureId, ActionId>

    init {
        val problems = mutableListOf<RegistryProblem>()

        val seenIds = mutableSetOf<ActionId>()
        for (binding in bindings) {
            if (!seenIds.add(binding.def.id)) problems += RegistryProblem.DuplicateId(binding.def.id)
        }

        for (binding in bindings) {
            if (binding.def.requiresTarget == null) continue
            for (placement in binding.def.placements) {
                val targetless = when (placement) {
                    is Placement.CommandPalette, is Placement.Toolbar, is Placement.Menu, is Placement.SelectionBar -> true
                    else -> false
                }
                if (targetless) {
                    problems += RegistryProblem.TargetRequiredOnTargetlessPlacement(binding.def.id, placement)
                }
            }
        }

        val shortcutClaims = mutableMapOf<KeyChord, MutableList<ActionId>>()
        for (binding in bindings) {
            for (placement in binding.def.placements) {
                if (placement is Placement.Shortcut) {
                    shortcutClaims.getOrPut(placement.chord) { mutableListOf() }.add(binding.def.id)
                }
            }
        }
        val shortcuts = mutableMapOf<KeyChord, ActionId>()
        for ((chord, ids) in shortcutClaims) {
            if (ids.size > 1) {
                problems += RegistryProblem.ShortcutConflict(chord, ids)
            } else {
                shortcuts[chord] = ids.single()
            }
        }

        val edgeGestures = setOf(GestureId.EDGE_LEFT, GestureId.EDGE_RIGHT, GestureId.EDGE_BOTTOM)
        val edgeRooms = mutableMapOf<GestureId, ActionId>()
        for (binding in bindings) {
            for (placement in binding.def.placements) {
                if (placement is Placement.Gesture && placement.gesture in edgeGestures) {
                    edgeRooms[placement.gesture] = binding.def.id
                }
            }
        }

        this.problems = problems
        this.shortcutMap = shortcuts
        this.edgeRoomMap = edgeRooms
    }

    fun shortcuts(): Map<KeyChord, ActionId> = shortcutMap
    fun edgeRooms(): Map<GestureId, ActionId> = edgeRoomMap
}
