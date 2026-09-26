package io.github.mbaliga.fylz.operations

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
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
 *
 * M3.4 adds [enqueueExtract] (`docs/agent/DESIGN-M34-SELECTIVE-EXTRACT.md` section 2.2): an EXTRACT
 * operation whose plan is already in the journal goes into the same unique queue, tagged
 * `op:<id>`, tracked here under the **operation** id; [cancel] on one sets the plan's cancel flag
 * through [cancelExtract] instead of cancelling the work, so the transfers queued behind it live on.
 */
class OperationRunner(
    private val scope: CoroutineScope,
    /** Only needed for [enqueueTransfer]/[cancel]'s WorkManager path (P1.2) -- every other member
     * here is plain-JVM testable without one, which existing tests rely on. */
    private val context: Context? = null,
    /**
     * How [cancel] flags an extraction (M3.4): `true` when [cancel]'s id names a planned extraction
     * and the flag was set. The default asks the journal (`hasExtractPlan`) and writes
     * `extract_plans.cancel_requested`, so an extraction enqueued by an earlier process is flagged too.
     */
    private val cancelExtract: (String) -> Boolean = { id ->
        val journal = context?.let { OperationJournal(it.applicationContext) }
        if (journal != null && journal.hasExtractPlan(id)) {
            journal.setCancelRequested(id)
            true
        } else {
            false
        }
    },
    /** As [cancelExtract], for a planned CREATE (M3.5): `create_plans.cancel_requested`, never
     * `cancelWorkById`, for the same reason. */
    private val cancelCreate: (String) -> Boolean = { id ->
        val journal = context?.let { OperationJournal(it.applicationContext) }
        if (journal != null && journal.hasCreatePlan(id)) {
            journal.setCreateCancelRequested(id)
            true
        } else {
            false
        }
    },
) {

    private val _operations = MutableStateFlow<List<RunningOperation>>(emptyList())
    val operations: StateFlow<List<RunningOperation>> = _operations.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    /** EXTRACT operations enqueued through this runner, by operation id. Guarded by [extracts]. */
    private val extracts = mutableSetOf<String>()

    /** CREATE operations enqueued through this runner, by operation id. Guarded by [creates]. */
    private val creates = mutableSetOf<String>()

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

    /** Cancels the running operation tracked under [id], if any -- whether it's a [run]-tracked
     * job or, if this runner has a [context] (P1.2), a [enqueueTransfer]-tracked WorkManager
     * request. A no-op once it has already finished (the id is no longer tracked by then). */
    fun cancel(id: String) {
        jobs[id]?.cancel()
        // M3.4/M3.5: an extraction or a create is cancelled by a flag the runner polls, never
        // cancelWorkById (that would cancel the whole unique chain behind it).
        val tracked = synchronized(extracts) { id in extracts } || synchronized(creates) { id in creates }
        if (cancelExtract(id) || cancelCreate(id) || tracked) return
        val appContext = context ?: return
        val workId = runCatching { UUID.fromString(id) }.getOrNull() ?: return
        WorkManager.getInstance(appContext).cancelWorkById(workId)
    }

    /**
     * M3.4: enqueues the planned EXTRACT operation [operationId] -- already written with its plan by
     * `OperationJournal.putWithExtractPlan` -- as durable work in the same unique queue as copy and
     * move, tagged [extractTag]. Tracked in [operations] under the operation id (so the UI's Cancel
     * reaches [cancel]'s flag path) with progress from the worker's own `WorkInfo`. Returns once the
     * work is terminal; never throws for a journaled outcome -- the journal is the record, and the
     * worker returns success for every one of them.
     */
    suspend fun enqueueExtract(operationId: String, label: String = "Extracting", itemCount: Int = 0) {
        val appContext = requireNotNull(context) { "OperationRunner needs a context to enqueue durable extractions." }
        val workManager = WorkManager.getInstance(appContext)
        val request = OneTimeWorkRequestBuilder<TransferWorker>()
            .setInputData(TransferWorker.extractInputData(operationId))
            .addTag(extractTag(operationId))
            .build()
        synchronized(extracts) { extracts += operationId }
        _operations.update { it + RunningOperation(operationId, FileOperationType.EXTRACT, label, itemCount = itemCount) }
        try {
            workManager.enqueueUniqueWork(TransferWorker.UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            workManager.getWorkInfoByIdFlow(request.id)
                .filterNotNull()
                .onEach { info -> applyWorkInfo(operationId, info) }
                .first { it.state.isFinished }
        } finally {
            synchronized(extracts) { extracts -= operationId }
            _operations.update { list -> list.filterNot { it.id == operationId } }
        }
    }

    /**
     * M3.5: enqueues the planned CREATE operation [operationId] -- already written with its plan by
     * `OperationJournal.putWithCreatePlan` -- as durable work in the same unique queue, tagged
     * [createTag]. Mirrors [enqueueExtract] exactly.
     */
    suspend fun enqueueCreate(operationId: String, label: String = "Compressing", itemCount: Int = 0) {
        val appContext = requireNotNull(context) { "OperationRunner needs a context to enqueue durable creates." }
        val workManager = WorkManager.getInstance(appContext)
        val request = OneTimeWorkRequestBuilder<TransferWorker>()
            .setInputData(TransferWorker.createInputData(operationId))
            .addTag(createTag(operationId))
            .build()
        synchronized(creates) { creates += operationId }
        _operations.update { it + RunningOperation(operationId, FileOperationType.ARCHIVE, label, itemCount = itemCount) }
        try {
            workManager.enqueueUniqueWork(TransferWorker.UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            workManager.getWorkInfoByIdFlow(request.id)
                .filterNotNull()
                .onEach { info -> applyWorkInfo(operationId, info) }
                .first { it.state.isFinished }
        } finally {
            synchronized(creates) { creates -= operationId }
            _operations.update { list -> list.filterNot { it.id == operationId } }
        }
    }

    /**
     * P1.2: enqueues a copy or move as durable work (unique work [TransferWorker.UNIQUE_WORK_NAME],
     * `APPEND_OR_REPLACE`) instead of running it on [scope] directly -- see [TransferWorker] for
     * why only copy and move go through this path. Tracks it in [operations] exactly like [run]
     * does, deriving progress from the same [WorkInfo] the notification itself is built from, so
     * the existing progress UI needs no changes to show a transfer alongside every other kind of
     * tracked operation.
     *
     * Suspends until the transfer reaches a terminal [WorkInfo.State], throwing if it didn't
     * succeed -- matching [run]'s `Deferred.await()` contract closely enough that a call site can
     * switch from one to the other with a small, local diff.
     */
    suspend fun enqueueTransfer(
        type: FileOperationType,
        label: String,
        sourceUris: List<Uri>,
        destinationTreeUri: Uri,
        conflictPolicy: ConflictPolicy,
        nameOverrides: Map<Uri, String> = emptyMap(),
        conflictResolutions: Map<Uri, ConflictPolicy> = emptyMap(),
    ) {
        val appContext = requireNotNull(context) { "OperationRunner needs a context to enqueue durable transfers." }
        val workManager = WorkManager.getInstance(appContext)
        val request = OneTimeWorkRequestBuilder<TransferWorker>()
            .setInputData(
                TransferWorker.inputData(type, sourceUris, destinationTreeUri, conflictPolicy, nameOverrides, conflictResolutions),
            )
            .build()
        val trackingId = request.id.toString()
        _operations.update { it + RunningOperation(trackingId, type, label, itemCount = sourceUris.size) }
        try {
            workManager.enqueueUniqueWork(TransferWorker.UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            val terminal = workManager.getWorkInfoByIdFlow(request.id)
                .filterNotNull()
                .onEach { info -> applyWorkInfo(trackingId, info) }
                .first { it.state.isFinished }
            if (terminal.state != WorkInfo.State.SUCCEEDED) {
                error(terminal.outputData.getString(TransferWorker.KEY_ERROR_MESSAGE) ?: "The transfer failed.")
            }
        } finally {
            _operations.update { list -> list.filterNot { it.id == trackingId } }
        }
    }

    private fun applyWorkInfo(id: String, info: WorkInfo) {
        _operations.update { list ->
            list.map { existing ->
                if (existing.id != id) {
                    existing
                } else {
                    existing.copy(
                        itemIndex = info.progress.getInt(TransferWorker.KEY_PROGRESS_ITEM_INDEX, existing.itemIndex),
                        itemCount = info.progress.getInt(TransferWorker.KEY_PROGRESS_ITEM_COUNT, existing.itemCount),
                        completedBytes = info.progress.getLong(TransferWorker.KEY_PROGRESS_COMPLETED_BYTES, existing.completedBytes),
                        totalBytes = info.progress.getLong(TransferWorker.KEY_PROGRESS_TOTAL_BYTES, -1L)
                            .takeIf { it >= 0 } ?: existing.totalBytes,
                    )
                }
            }
        }
    }

    companion object {
        /** The WorkManager tag of the EXTRACT operation [operationId]'s request (design section 2.2). */
        fun extractTag(operationId: String): String = "op:$operationId"

        /** As [extractTag], for a planned CREATE (`docs/agent/DESIGN-M35-CREATE.md` section 2.2). */
        fun createTag(operationId: String): String = "op:$operationId"

        /**
         * Call once at app start (P0.6), with a [journal] the caller just constructed -- its own
         * constructor synchronously marks every operation a dead process left `RUNNING`,
         * `PREFLIGHT` or `PAUSED` as `NEEDS_ATTENTION` with `errorCode = "PROCESS_INTERRUPTED"`
         * (see [OperationRecoveryPolicy]), so that must already have happened by the time this
         * runs.
         *
         * For every copy/move item that recovery touched, this deletes exactly the one
         * `.fylz-part-*` staging document it recorded -- and only that document, never a broader
         * sweep -- and marks it [OperationState.INTERRUPTED] so [OperationRetryPolicy] offers a
         * safe retry. An item that reached `NEEDS_ATTENTION` any other way (for example
         * `MOVE_SOURCE_DELETE_PENDING`) is untouched: that has its own recovery action
         * (`FileOperationService.finishMoveCleanup`).
         *
         * **EXTRACT operations with a plan** (M3.4, design section 2.3 step 9) are reconciled against
         * WorkManager through [workLookup]: one whose tagged work is still alive is left alone (the
         * re-run claims it, `NEEDS_ATTENTION`/`PROCESS_INTERRUPTED` included); a `RUNNING`,
         * `PAUSED_BY_SYSTEM` or interrupted one whose work is gone has its recorded staging deleted and
         * becomes `INTERRUPTED` (a **conditional** state change, so a claim that raced this is not
         * clobbered); a `QUEUED` one older than [NEVER_RAN_AGE_MILLIS] with no work becomes
         * `FAILED / NEVER_RAN`. Legacy EXTRACT rows (no plan: the encrypted-ZIP path) are untouched.
         */
        suspend fun recover(
            journal: OperationJournal,
            resolver: ContentResolver,
            workLookup: WorkLookup = WorkLookup.NONE,
            nowMillis: () -> Long = System::currentTimeMillis,
        ) = withContext(Dispatchers.IO) {
            journal.list()
                .filter { it.type == FileOperationType.COPY || it.type == FileOperationType.MOVE }
                .forEach { operation ->
                    var changed = false
                    val items = operation.items.map { item ->
                        if (item.errorCode != PROCESS_INTERRUPTED) return@map item
                        changed = true
                        item.stagingUri?.let { staging -> DocNode.load(resolver, staging)?.delete(resolver) }
                        item.copy(state = OperationState.INTERRUPTED, stagingUri = null)
                    }
                    if (changed) {
                        journal.put(
                            operation.copy(
                                items = items,
                                state = OperationState.INTERRUPTED,
                                updatedAtMillis = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
            journal.list()
                .filter { it.type == FileOperationType.EXTRACT && journal.hasExtractPlan(it.id) }
                .forEach { operation -> reconcileExtract(journal, resolver, workLookup, nowMillis(), operation) }
            journal.list()
                .filter { it.type == FileOperationType.ARCHIVE && journal.hasCreatePlan(it.id) }
                .forEach { operation -> reconcileCreate(journal, resolver, workLookup, nowMillis(), operation) }
        }

        private fun reconcileExtract(journal: OperationJournal, resolver: ContentResolver, workLookup: WorkLookup, now: Long, operation: FileOperation) {
            val alive = workLookup.activeWorkIds(extractTag(operation.id)).isNotEmpty()
            when (operation.state) {
                OperationState.RUNNING, OperationState.PAUSED_BY_SYSTEM, OperationState.NEEDS_ATTENTION -> {
                    if (alive) return
                    val interrupted = operation.state == OperationState.NEEDS_ATTENTION &&
                        operation.items.none { it.state == OperationState.NEEDS_ATTENTION && it.errorCode != PROCESS_INTERRUPTED }
                    if (operation.state == OperationState.NEEDS_ATTENTION && !interrupted) return
                    if (!journal.updateOperationStateIf(operation.id, operation.state, OperationState.INTERRUPTED)) return
                    operation.items.forEach { item ->
                        if (item.state == OperationState.SUCCEEDED) return@forEach
                        item.stagingUri?.let { staging -> DocNode.load(resolver, staging)?.delete(resolver) }
                        journal.updateItem(operation.id, item.copy(state = OperationState.INTERRUPTED, errorCode = PROCESS_INTERRUPTED, stagingUri = null), refresh = false)
                    }
                    journal.refresh()
                    Log.i(TAG, "Extraction ${operation.id}: its work is gone; marked interrupted")
                }
                OperationState.QUEUED -> {
                    if (alive || now - operation.updatedAtMillis < NEVER_RAN_AGE_MILLIS) return
                    if (!journal.updateOperationStateIf(operation.id, OperationState.QUEUED, OperationState.FAILED)) return
                    operation.items.forEach { item ->
                        if (item.state == OperationState.SUCCEEDED) return@forEach
                        journal.updateItem(operation.id, item.copy(state = OperationState.FAILED, errorCode = ExtractErrorCodes.NEVER_RAN), refresh = false)
                    }
                    journal.refresh()
                    Log.i(TAG, "Extraction ${operation.id}: queued with no work; marked never run")
                }
                else -> Unit
            }
        }

        /**
         * As [reconcileExtract], for a planned CREATE (design section 2.3 step 9, generalised): the
         * staging documents to delete come from [OperationJournal.createPlanItems] (a create's
         * `operation.items` are the *source* items, never staged) rather than `operation.items`
         * itself, and a `NEVER_RAN` create's items are marked failed the same way, once.
         */
        private fun reconcileCreate(journal: OperationJournal, resolver: ContentResolver, workLookup: WorkLookup, now: Long, operation: FileOperation) {
            val alive = workLookup.activeWorkIds(createTag(operation.id)).isNotEmpty()
            when (operation.state) {
                OperationState.RUNNING, OperationState.PAUSED_BY_SYSTEM, OperationState.NEEDS_ATTENTION -> {
                    if (alive) return
                    if (!journal.updateOperationStateIf(operation.id, operation.state, OperationState.INTERRUPTED)) return
                    journal.createPlanItems(operation.id).forEach { item ->
                        item.stagingUri?.let { staging -> DocNode.load(resolver, staging)?.delete(resolver) }
                    }
                    operation.items.forEach { item ->
                        journal.updateItem(operation.id, item.copy(state = OperationState.INTERRUPTED, errorCode = PROCESS_INTERRUPTED), refresh = false)
                    }
                    journal.refresh()
                    Log.i(TAG, "Compress ${operation.id}: its work is gone; marked interrupted")
                }
                OperationState.QUEUED -> {
                    if (alive || now - operation.updatedAtMillis < NEVER_RAN_AGE_MILLIS) return
                    if (!journal.updateOperationStateIf(operation.id, OperationState.QUEUED, OperationState.FAILED)) return
                    operation.items.forEach { item ->
                        journal.updateItem(operation.id, item.copy(state = OperationState.FAILED, errorCode = CreateErrorCodes.NEVER_RAN), refresh = false)
                    }
                    journal.refresh()
                    Log.i(TAG, "Compress ${operation.id}: queued with no work; marked never run")
                }
                else -> Unit
            }
        }

        private const val TAG = "OperationRunner"
        private const val PROCESS_INTERRUPTED = "PROCESS_INTERRUPTED"

        /** A `QUEUED` extraction younger than this is still between its plan write and its enqueue. */
        const val NEVER_RAN_AGE_MILLIS = 60_000L
    }
}
