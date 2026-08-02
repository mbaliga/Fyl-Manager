package io.github.mbaliga.fylz.operations

import android.net.Uri

data class RecycleRecord(
    val itemId: String,
    val originalUri: Uri,
    val recycledUri: Uri,
    val originalParentUri: Uri?,
    val originalDisplayName: String,
    val providerAuthority: String?,
    val sizeBytes: Long?,
    val recycledAtMillis: Long,
)

sealed interface DeleteDecision {
    data class MoveToRecycleBin(val recycleRoot: Uri) : DeleteDecision
    data class Refuse(val reason: String) : DeleteDecision
    data object PermanentDeleteRequiresExplicitAdvancedAction : DeleteDecision
}

object RecycleBinPolicy {
    fun decideDefaultDelete(
        recycleRoot: Uri?,
        canWriteRecycleRoot: Boolean,
    ): DeleteDecision = when {
        recycleRoot == null -> DeleteDecision.Refuse(
            "No writable Fylz recycle location is available for this provider.",
        )
        !canWriteRecycleRoot -> DeleteDecision.Refuse(
            "The selected provider cannot write to its Fylz recycle location.",
        )
        else -> DeleteDecision.MoveToRecycleBin(recycleRoot)
    }

    fun allowPermanentDelete(
        invokedFromRecycleBin: Boolean,
        explicitAdvancedAction: Boolean,
        confirmed: Boolean,
    ): Boolean = confirmed && (invokedFromRecycleBin || explicitAdvancedAction)
}
