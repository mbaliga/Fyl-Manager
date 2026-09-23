package io.github.mbaliga.fylz.operations

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/** One operation's live state while [OperationRunner.run] is executing it. */
data class RunningOperation(
    val id: String,
    val type: FileOperationType,
    val label: String,
    val itemIndex: Int = 0,
    val itemCount: Int = 0,
    val completedBytes: Long = 0,
    val totalBytes: Long? = null,
)

/** What a caller of [OperationRunner.run] reports back as its work progresses. Fields left at
 * their default simply don't update that part of the tracked [RunningOperation]. */
data class OperationProgress(
    val label: String? = null,
    val itemIndex: Int = 0,
    val itemCount: Int = 0,
    val completedBytes: Long = 0,
    val totalBytes: Long? = null,
)

/**
 * Runs a long file operation on an app-scoped [CoroutineScope] (A3), not a Compose composable's
 * `rememberCoroutineScope()`, so rotating, folding or resizing the window never cancels an
 * in-flight copy, move, extract, recycle or PDF job (P0.5, defect 4 interim). The UI only
 * enqueues work through [run] and observes [operations]; it never runs I/O in its own scope.
 *
 * [scope] is expected to be owned by [io.github.mbaliga.fylz.FylzApplication] and live for the
 * process, so a launched job survives every UI teardown short of the process actually dying --
 * P0.6 adds durable staged-write recovery for that remaining case.
 *
 * Because every tracked job runs on [scope] rather than on any UI-owned scope, cancellation is
 * now always explicit: nothing but [cancel] ever cancels one, so a service's own journal is
 * correct to record every cancellation it sees as user-initiated. Before this, composable teardown
 * cancelled the UI's `rememberCoroutineScope()` and looked identical to the user pressing Cancel.
 */
class OperationRunner(private val scope: CoroutineScope) {

    private val _operations = MutableStateFlow<List<RunningOperation>>(emptyList())
    val operations: StateFlow<List<RunningOperation>> = _operations.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    /**
     * Launches [block] on the app scope, tracked in [operations] as [type]/[label] until it
     * completes. [block] is handed a `report` callback to forward its own service's progress
     * callback through; calling it is optional, for operations (like archive extract, which has
     * none today) that have no finer-grained progress to report.
     *
     * Returns immediately with a [Deferred] for the eventual result. The caller awaits it from
     * whatever scope is convenient (typically a Compose `rememberCoroutineScope()`) to react to
     * completion; awaiting from a scope that later gets cancelled only stops that caller from
     * *seeing* the result; it does not cancel the operation itself, which is exactly the point.
     */
    fun <T> run(
        type: FileOperationType,
        label: String,
        block: suspend (report: (OperationProgress) -> Unit) -> T,
    ): Deferred<T> {
        val id = UUID.randomUUID().toString()
        _operations.update { it + RunningOperation(id, type, label) }

        val deferred = scope.async {
            try {
                block { progress ->
                    _operations.update { list ->
                        list.map { existing ->
                            if (existing.id != id) {
                                existing
                            } else {
                                existing.copy(
                                    label = progress.label ?: existing.label,
                                    itemIndex = progress.itemIndex,
                                    itemCount = progress.itemCount,
                                    completedBytes = progress.completedBytes,
                                    totalBytes = progress.totalBytes,
                                )
                            }
                        }
                    }
                }
            } finally {
                _operations.update { list -> list.filterNot { it.id == id } }
            }
        }
        jobs[id] = deferred
        deferred.invokeOnCompletion { jobs.remove(id) }
        return deferred
    }

    /** Cancels the running operation tracked under [id], if any. A no-op once it has already
     * finished (the id is no longer tracked by then). */
    fun cancel(id: String) {
        jobs[id]?.cancel()
    }
}
