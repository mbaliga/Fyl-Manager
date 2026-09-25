package io.github.mbaliga.fylz.actions

/**
 * A built-in's Kotlin-only half: its own availability predicates over [BrowserState] (design §2.1
 * -- visibility and enablement are split; disabled is not hidden), its dynamic label and checked
 * state, and the handler [run] calls into an [ActionContext].
 */
class BuiltInBinding(
    val def: ActionDef,
    val visibleWhen: (BrowserState) -> Boolean,
    val enabledWhen: (BrowserState) -> Boolean,
    val label: (BrowserState) -> String = { def.titleKey },
    val checked: (BrowserState) -> Boolean? = { null },
    val run: (ActionContext, BrowserState, ActionTarget?) -> Unit,
)
