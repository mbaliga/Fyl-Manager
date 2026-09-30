package io.github.mbaliga.fylz.actions

sealed interface ConfirmPolicy {
    data object None : ConfirmPolicy
    data object Always : ConfirmPolicy
    data class IfCountAbove(val count: Int) : ConfirmPolicy
    data object IfDestructive : ConfirmPolicy
}
