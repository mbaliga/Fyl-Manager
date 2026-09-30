package io.github.mbaliga.fylz.actions

import io.github.mbaliga.fylz.model.FileEntry

sealed interface ActionTarget {
    data class Entry(val entry: FileEntry) : ActionTarget
    data class Tab(val id: String) : ActionTarget
}
