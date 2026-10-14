package io.github.mbaliga.fylz.actions

sealed interface Origin {
    data object BuiltIn : Origin
    data class Bundle(val id: String, val version: String) : Origin
    data object Local : Origin
}
