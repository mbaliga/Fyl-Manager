package io.github.mbaliga.fylz.operations

/**
 * Runs an [OperationRetryPlan] (M3.4, design section 2.3 step 9): what `FylzAppShell`'s history
 * dialog used to do inline. A transfer replays through [fileOperations] (a new operation), a move
 * cleanup finishes through it, and a planned extraction or compression is **re-claimed**: the
 * journal moves the same operation and its unfinished items back to `QUEUED` in one transaction
 * and the runner enqueues it again. Throws with a user-facing message when a plan cannot be run.
 */
class RetryDispatcher(
    private val fileOperations: FileOperationService,
    private val journal: OperationJournal,
    private val enqueueExtract: suspend (operationId: String) -> Unit,
    private val enqueueCreate: suspend (operationId: String) -> Unit = {},
) {
    suspend fun dispatch(plan: OperationRetryPlan) {
        when (plan) {
            is OperationRetryPlan.Transfer -> when (plan.type) {
                FileOperationType.COPY -> fileOperations.copy(
                    sourceUris = plan.sourceUris,
                    destinationTreeUri = plan.destinationTreeUri,
                    conflictPolicy = plan.conflictPolicy,
                )
                FileOperationType.MOVE -> fileOperations.move(
                    sourceUris = plan.sourceUris,
                    destinationTreeUri = plan.destinationTreeUri,
                    conflictPolicy = plan.conflictPolicy,
                )
                else -> error("Unsupported retry type.")
            }
            is OperationRetryPlan.FinishMoveCleanup -> fileOperations.finishMoveCleanup(plan.operationId)
            is OperationRetryPlan.ReclaimExtract -> {
                check(journal.retryExtract(plan.operationId)) { "This extraction can no longer be retried." }
                enqueueExtract(plan.operationId)
            }
            is OperationRetryPlan.ReclaimCreate -> {
                check(journal.retryCreate(plan.operationId)) { "This compression can no longer be retried." }
                enqueueCreate(plan.operationId)
            }
        }
    }
}
