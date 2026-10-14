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
    /**
     * The `<uuid>` transaction folder [recycledUri] lives in, so restore and permanent delete can
     * remove it directly once empty -- `DocumentFile.fromSingleUri(...).parentFile` is always
     * null (defect 1), so deriving it from [recycledUri] at cleanup time silently leaked these
     * folders forever. Null only for records written before this field existed; such a record's
     * container is left behind exactly as it already was, rather than guessed at.
     */
    val containerUri: Uri? = null,
)

sealed interface DeleteDecision {
    data class MoveToRecycleBin(val recycleRoot: Uri) : DeleteDecision
    data class Refuse(val reason: String) : DeleteDecision
    data object PermanentDeleteRequiresExplicitAdvancedAction : DeleteDecision
}

internal enum class DefaultDeletePolicyDecision {
    MOVE_TO_RECYCLE_BIN,
    REFUSE_NO_RECYCLE_ROOT,
    REFUSE_RECYCLE_ROOT_NOT_WRITABLE,
}

object RecycleBinPolicy {
    internal fun decideDefaultDeletePolicy(
        hasRecycleRoot: Boolean,
        canWriteRecycleRoot: Boolean,
    ): DefaultDeletePolicyDecision = when {
        !hasRecycleRoot -> DefaultDeletePolicyDecision.REFUSE_NO_RECYCLE_ROOT
        !canWriteRecycleRoot -> DefaultDeletePolicyDecision.REFUSE_RECYCLE_ROOT_NOT_WRITABLE
        else -> DefaultDeletePolicyDecision.MOVE_TO_RECYCLE_BIN
    }

    fun decideDefaultDelete(
        recycleRoot: Uri?,
        canWriteRecycleRoot: Boolean,
    ): DeleteDecision = when (decideDefaultDeletePolicy(recycleRoot != null, canWriteRecycleRoot)) {
        DefaultDeletePolicyDecision.REFUSE_NO_RECYCLE_ROOT -> DeleteDecision.Refuse(
            "No writable Fylz recycle location is available for this provider.",
        )
        DefaultDeletePolicyDecision.REFUSE_RECYCLE_ROOT_NOT_WRITABLE -> DeleteDecision.Refuse(
            "The selected provider cannot write to its Fylz recycle location.",
        )
        DefaultDeletePolicyDecision.MOVE_TO_RECYCLE_BIN -> DeleteDecision.MoveToRecycleBin(
            requireNotNull(recycleRoot),
        )
    }

    fun allowPermanentDelete(
        invokedFromRecycleBin: Boolean,
        explicitAdvancedAction: Boolean,
        confirmed: Boolean,
    ): Boolean = confirmed && (invokedFromRecycleBin || explicitAdvancedAction)
}
