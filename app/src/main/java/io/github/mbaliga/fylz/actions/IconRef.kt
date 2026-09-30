package io.github.mbaliga.fylz.actions

/** A built-in's icon is resolved to an `ImageVector` by the renderer, by name; a bundle's own SVG
 * icon (`IconRef.Bundle`) arrives with the bundle format (MC.8) -- the field exists now so
 * `ActionDef` doesn't need to change shape when it does. */
sealed interface IconRef {
    data class Builtin(val name: String) : IconRef
    data class Bundle(val path: String) : IconRef
}
