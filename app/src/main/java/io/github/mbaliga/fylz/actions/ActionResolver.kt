package io.github.mbaliga.fylz.actions

/** What a renderer actually draws: `visible` items only, in placement order (design §2.4). */
data class ResolvedAction(
    val id: ActionId,
    val enabled: Boolean,
    val label: String,
    val iconName: String,
    val checked: Boolean?,
)

/** The rendered surfaces MC.0a resolves against -- one per [Placement] shape that has a renderer
 * today or is exercised by the golden test (design §2.7): the selection bar, a [Bar]'s icon row, a
 * [MenuId]'s dropdown, and a [RoomId]'s content. */
sealed interface PlacementQuery {
    data object SelectionBar : PlacementQuery
    data class Toolbar(val bar: Bar) : PlacementQuery
    data class Menu(val menu: MenuId) : PlacementQuery
    data class Room(val room: RoomId) : PlacementQuery
}

/** The only function a renderer calls to decide what to draw (design §2.4). */
class ActionResolver(private val registry: ActionRegistry) {
    fun resolve(placement: PlacementQuery, state: BrowserState): List<ResolvedAction> =
        registry.bindings
            .mapNotNull { binding -> orderFor(placement, binding.def.placements)?.let { it to binding } }
            .sortedBy { (order, _) -> order }
            .mapNotNull { (_, binding) ->
                if (!binding.visibleWhen(state)) return@mapNotNull null
                ResolvedAction(
                    id = binding.def.id,
                    enabled = binding.enabledWhen(state),
                    label = binding.label(state),
                    iconName = iconName(binding.def.icon),
                    checked = binding.checked(state),
                )
            }

    /**
     * Design §2.6: every resolved, enabled, targetless, palette-visible built-in across all
     * placements, sorted by label -- the command palette's own candidate list. `requiresTarget`
     * actions are excluded here (a palette invocation supplies no target); the four self-contained
     * Recovery-room overlay cards are excluded via [BuiltInBinding.paletteVisible] instead, since
     * they have no imperative "open" to dispatch to.
     */
    fun paletteCandidates(state: BrowserState): List<ResolvedAction> = registry.bindings
        .asSequence()
        .filter { it.def.requiresTarget == null && it.paletteVisible }
        .filter { it.visibleWhen(state) && it.enabledWhen(state) }
        .map { binding ->
            ResolvedAction(
                id = binding.def.id,
                enabled = true,
                label = binding.label(state),
                iconName = iconName(binding.def.icon),
                checked = binding.checked(state),
            )
        }
        .sortedBy { it.label }
        .toList()

    private fun orderFor(query: PlacementQuery, placements: List<Placement>): Int? {
        for (placement in placements) {
            val order = when (query) {
                is PlacementQuery.SelectionBar -> (placement as? Placement.SelectionBar)?.order
                is PlacementQuery.Toolbar -> (placement as? Placement.Toolbar)?.takeIf { it.bar == query.bar }?.order
                is PlacementQuery.Menu -> (placement as? Placement.Menu)?.takeIf { it.menu == query.menu }?.order
                is PlacementQuery.Room -> (placement as? Placement.Room)?.takeIf { it.room == query.room }?.order
            }
            if (order != null) return order
        }
        return null
    }

    private fun iconName(icon: IconRef): String = when (icon) {
        is IconRef.Builtin -> icon.name
        is IconRef.Bundle -> icon.path
    }
}
