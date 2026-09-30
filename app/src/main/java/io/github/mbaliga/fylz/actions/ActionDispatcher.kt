package io.github.mbaliga.fylz.actions

/**
 * Dispatches by id (design §2.4): checks `visibleWhen && enabledWhen` and, for a `requiresTarget`
 * action, that a target was actually supplied, applies `confirm` (MC.0: always [ConfirmPolicy.None],
 * so a no-op here), then calls the binding's `run`. Not called from any renderer in MC.0a.
 */
class ActionDispatcher(private val registry: ActionRegistry) {
    fun run(id: ActionId, state: BrowserState, target: ActionTarget?, ctx: ActionContext) {
        val binding = registry.byId[id] ?: return
        if (!binding.visibleWhen(state) || !binding.enabledWhen(state)) return
        if (binding.def.requiresTarget != null && target == null) return
        binding.run(ctx, state, target)
    }

    /**
     * Dispatches a [GestureId] rather than an id (design §2.6): finds the first binding carrying a
     * `Placement.Gesture(gesture, targetWhen)` whose `targetWhen` accepts [target] (or is null, so
     * every target -- including none -- is accepted, as `SHAKE`'s `fylz.refresh` needs), requires
     * `visibleWhen && enabledWhen` and a target if the binding's `requiresTarget` demands one, then
     * runs it. `ITEM_DOUBLE_TAP` carries two bindings whose `targetWhen`s are mutually exclusive
     * (open a directory, open-with a file), so exactly one ever matches a given target.
     */
    fun gesture(gesture: GestureId, target: ActionTarget?, state: BrowserState, ctx: ActionContext) {
        for (binding in registry.bindings) {
            val placement = binding.def.placements.firstOrNull { it is Placement.Gesture && it.gesture == gesture }
                as? Placement.Gesture ?: continue
            val targetWhen = placement.targetWhen
            if (targetWhen != null && (target == null || !targetWhen(target))) continue
            if (!binding.visibleWhen(state) || !binding.enabledWhen(state)) continue
            if (binding.def.requiresTarget != null && target == null) continue
            binding.run(ctx, state, target)
            return
        }
    }
}
