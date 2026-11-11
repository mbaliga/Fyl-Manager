package io.github.mbaliga.fylz.widget

import io.github.mbaliga.fylz.operations.FileOperation
import io.github.mbaliga.fylz.operations.FileOperationType
import io.github.mbaliga.fylz.operations.OperationState
import io.github.mbaliga.fylz.operations.RecycleRecord

/** Why an operation is waiting for the user, in the widget's words. */
enum class AttentionReason {
    /** The app process died while the operation ran (`PROCESS_INTERRUPTED`). */
    INTERRUPTED,

    /** A move copied its files but the provider refused to remove the originals. */
    MOVE_CLEANUP,

    /** Any other item the operation flagged for review. */
    REVIEW,
}

/** One operation awaiting the user, reduced to its kind and counts. No names, no URIs. */
data class AttentionRow(
    val type: FileOperationType,
    val itemCount: Int,
    val reason: AttentionReason,
    val atMillis: Long,
)

data class RecoveryWidgetModel(
    val binCount: Int,
    /** Sum of the sizes the providers reported; see [binSizeComplete]. */
    val binBytes: Long,
    /** False when at least one recycled item has no recorded size, so [binBytes] is a floor. */
    val binSizeComplete: Boolean,
    val oldestRecycledAtMillis: Long?,
    val attention: List<AttentionRow>,
) {
    val attentionCount: Int get() = attention.size
    val interruptedCount: Int get() = attention.count { it.reason == AttentionReason.INTERRUPTED }
    val isAllClear: Boolean get() = binCount == 0 && attention.isEmpty()

    /** The most recent operation awaiting the user; the LARGE layout describes it. */
    val latestAttention: AttentionRow? get() = attention.firstOrNull()

    companion object {
        /** Error codes the operation services write; see `OperationRecoveryPolicy`/`FileOperationService`. */
        const val PROCESS_INTERRUPTED = "PROCESS_INTERRUPTED"
        const val MOVE_SOURCE_DELETE_PENDING = "MOVE_SOURCE_DELETE_PENDING"

        fun from(records: List<RecycleRecord>, operations: List<FileOperation>): RecoveryWidgetModel {
            val sizes = records.map(RecycleRecord::sizeBytes)
            val attention = operations
                .filter { it.state == OperationState.NEEDS_ATTENTION }
                .sortedByDescending(FileOperation::updatedAtMillis)
                .map { operation ->
                    val codes = operation.items.mapNotNull { it.errorCode }.toSet()
                    AttentionRow(
                        type = operation.type,
                        itemCount = operation.items.size,
                        reason = when {
                            MOVE_SOURCE_DELETE_PENDING in codes -> AttentionReason.MOVE_CLEANUP
                            PROCESS_INTERRUPTED in codes -> AttentionReason.INTERRUPTED
                            else -> AttentionReason.REVIEW
                        },
                        atMillis = operation.updatedAtMillis,
                    )
                }
            return RecoveryWidgetModel(
                binCount = records.size,
                binBytes = sizes.sumOf { (it ?: 0L).coerceAtLeast(0L) },
                binSizeComplete = sizes.all { it != null && it >= 0L },
                oldestRecycledAtMillis = records.minOfOrNull(RecycleRecord::recycledAtMillis),
                attention = attention,
            )
        }
    }
}
