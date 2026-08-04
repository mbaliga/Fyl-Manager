package io.github.mbaliga.fylz.operations

import android.net.Uri

data class DuplicateCleanupSelection(
    val group: DuplicateGroup,
    val keep: Uri,
    val recycle: Set<Uri>,
)

data class DuplicateCleanupValidation(val valid: Boolean, val reason: String? = null)

object DuplicateCleanupPolicy {
    fun defaultSelection(group: DuplicateGroup): DuplicateCleanupSelection {
        require(group.items.size >= 2)
        val sorted = group.items.sortedBy(Uri::toString)
        return DuplicateCleanupSelection(group, sorted.first(), sorted.drop(1).toSet())
    }

    fun validate(selection: DuplicateCleanupSelection): DuplicateCleanupValidation {
        val all = selection.group.items.toSet()
        if (selection.group.items.size < 2) return DuplicateCleanupValidation(false, "A duplicate group must contain at least two files.")
        if (selection.keep !in all) return DuplicateCleanupValidation(false, "The retained item is not part of this duplicate group.")
        if (selection.keep in selection.recycle) return DuplicateCleanupValidation(false, "The retained item cannot also be recycled.")
        if (!all.containsAll(selection.recycle)) return DuplicateCleanupValidation(false, "The cleanup plan includes an item outside the verified duplicate group.")
        if (selection.recycle.isEmpty()) return DuplicateCleanupValidation(false, "Choose at least one duplicate to recycle.")
        if (selection.recycle.size >= all.size) return DuplicateCleanupValidation(false, "At least one verified copy must remain.")
        return DuplicateCleanupValidation(true)
    }

    fun reclaimedBytes(selection: DuplicateCleanupSelection): Long =
        selection.group.sizeBytes * selection.recycle.size
}
