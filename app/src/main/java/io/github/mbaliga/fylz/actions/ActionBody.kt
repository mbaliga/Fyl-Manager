package io.github.mbaliga.fylz.actions

/** [Steps] and [Script] are type-only placeholders in MC.0 -- MC.5 gives [Steps] a step list,
 * MC.7 gives [Script] a script reference. Every built-in in MC.0 is [BuiltIn]. */
sealed interface ActionBody {
    data class BuiltIn(val handlerId: String) : ActionBody
    data object Steps : ActionBody
    data object Script : ActionBody
}
