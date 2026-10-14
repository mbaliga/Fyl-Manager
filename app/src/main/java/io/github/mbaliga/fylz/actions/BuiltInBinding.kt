package io.github.mbaliga.fylz.actions

/**
 * A built-in's Kotlin-only half: its own availability predicates over [BrowserState] (design §2.1
 * -- visibility and enablement are split; disabled is not hidden), its dynamic label and checked
 * state, and the handler [run] calls into an [ActionContext]. [paletteVisible] is design §2.6's
 * exclusion for the four self-contained Recovery-room overlay cards, which have no imperative
 * "open" the command palette could dispatch to -- `requiresTarget` actions are excluded from the
 * palette separately, by [ActionResolver.paletteCandidates] itself, not through this flag.
 */
class BuiltInBinding(
    val def: ActionDef,
    val visibleWhen: (BrowserState) -> Boolean,
    val enabledWhen: (BrowserState) -> Boolean,
    val label: (BrowserState) -> String = { def.titleKey },
    val checked: (BrowserState) -> Boolean? = { null },
    val paletteVisible: Boolean = true,
    val run: (ActionContext, BrowserState, ActionTarget?) -> Unit,
)
