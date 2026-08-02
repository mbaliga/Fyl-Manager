package io.github.mbaliga.fylz.storage

import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.SortDirection
import io.github.mbaliga.fylz.model.SortField

object FileSorter {
    fun sort(
        entries: List<FileEntry>,
        field: SortField,
        direction: SortDirection,
    ): List<FileEntry> = entries.sortedWith { left, right ->
        if (left.isDirectory != right.isDirectory) {
            return@sortedWith if (left.isDirectory) -1 else 1
        }

        val valueComparison = compareField(left, right, field, direction)
        if (valueComparison != 0) return@sortedWith valueComparison

        val nameComparison = left.name.compareTo(right.name, ignoreCase = true)
        if (nameComparison != 0) nameComparison else left.documentId.compareTo(right.documentId)
    }

    private fun compareField(
        left: FileEntry,
        right: FileEntry,
        field: SortField,
        direction: SortDirection,
    ): Int = when (field) {
        SortField.NAME -> applyDirection(
            left.name.compareTo(right.name, ignoreCase = true),
            direction,
        )
        SortField.MODIFIED -> compareNullableLast(left.modifiedAt, right.modifiedAt, direction)
        SortField.SIZE -> compareNullableLast(left.size, right.size, direction)
        SortField.TYPE -> applyDirection(
            extensionOf(left).compareTo(extensionOf(right), ignoreCase = true),
            direction,
        )
    }

    private fun <T : Comparable<T>> compareNullableLast(
        left: T?,
        right: T?,
        direction: SortDirection,
    ): Int = when {
        left == null && right == null -> 0
        left == null -> 1
        right == null -> -1
        else -> applyDirection(left.compareTo(right), direction)
    }

    private fun applyDirection(comparison: Int, direction: SortDirection): Int =
        if (direction == SortDirection.ASCENDING) comparison else -comparison

    private fun extensionOf(entry: FileEntry): String =
        entry.name.substringAfterLast('.', "").lowercase()
}
