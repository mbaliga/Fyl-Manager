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
}
