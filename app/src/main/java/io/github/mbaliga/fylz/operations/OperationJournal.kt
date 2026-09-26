package io.github.mbaliga.fylz.operations

import android.content.Context
import io.github.mbaliga.fylz.data.FylzDatabase
import io.github.mbaliga.fylz.data.OperationsDao
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Small durable journal for user-visible file operations.
 *
 * The journal intentionally stores only provider URIs, display names, byte counts, states, and
 * coarse error codes. It never stores file contents, credentials, archive passwords, or AI keys.
 *
 * P1.1: this is now a thin facade over [FylzDatabase] (plain SQLite, A4) -- every caller listed
 * above keeps working unchanged. It previously read and wrote a SharedPreferences-encoded JSON
 * blob directly; [FylzDatabase] migrates that blob into the database once, the first time it's
 * created on a given install, so no caller here needs to know that migration happened.
 *
 * P1.11: [operations] replaces the 1-second polling loop `FylzAppShell` used to run to notice a
 * change -- every mutator below (`put`/`remove`/`clearFinished`) republishes it, so a collector
 * sees a change the moment it lands rather than up to a second late, and the plain SQLite read
 * driving it happens on whatever thread is already calling the mutator (every real caller already
 * runs off the main thread -- see `OperationRunner`/`FileOperationService`) instead of on a timer
 * tied to Compose's own dispatcher. A caller that only wants a one-off snapshot still has [list].
 *
 * Two [OperationJournal] instances over the same database file each keep their own, independent
 * [operations] -- a write through one is invisible to the other's `StateFlow` until IT also
 * mutates or is otherwise told to refresh. A caller that wants a shared, live view (a UI reading
 * [operations] AND retrying through [FileOperationService]) must construct exactly one instance
 * and pass it to every collaborator, rather than letting each default-construct its own.
 */
class OperationJournal(context: Context) {
    private val database = FylzDatabase(context.applicationContext)
    private val processSessionPreferences =
        context.applicationContext.getSharedPreferences(PROCESS_SESSION_PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val _operations = MutableStateFlow<List<FileOperation>>(emptyList())
    val operations: StateFlow<List<FileOperation>> = _operations.asStateFlow()

    init {
        recoverFromPriorProcessIfNeeded()
        refreshOperations()
    }

    @Synchronized
    fun list(): List<FileOperation> = OperationsDao.list(database.readableDatabase)

    @Synchronized
    fun find(id: String): FileOperation? = OperationsDao.find(database.readableDatabase, id)

    @Synchronized
    fun put(operation: FileOperation) {
        OperationsDao.put(database.writableDatabase, operation)
        refreshOperations()
    }

    @Synchronized
    fun remove(id: String) {
        OperationsDao.remove(database.writableDatabase, id)
        refreshOperations()
    }

    /** Keeps interrupted records visible until the user explicitly resolves or dismisses them. */
    @Synchronized
    fun clearFinished() {
        OperationsDao.clearFinished(database.writableDatabase)
        refreshOperations()
    }

    // ------------------------------------------------------------------ M3.4: extraction plans

    /** The operation and its plan, atomically (`docs/agent/DESIGN-M34-SELECTIVE-EXTRACT.md` section 2.2). */
    @Synchronized
    fun putWithExtractPlan(operation: FileOperation, plan: ExtractPlan) {
        OperationsDao.putWithExtractPlan(database.writableDatabase, operation, plan)
        refreshOperations()
    }

    @Synchronized
    fun extractPlan(operationId: String): ExtractPlan? = OperationsDao.extractPlan(database.readableDatabase, operationId)

    @Synchronized
    fun hasExtractPlan(operationId: String): Boolean = OperationsDao.hasExtractPlan(database.readableDatabase, operationId)

    /** See [OperationsDao.claimExtract]; republishes [operations] on success. */
    @Synchronized
    fun claimExtract(operationId: String, from: Set<OperationState>): Boolean {
        val claimed = OperationsDao.claimExtract(database.writableDatabase, operationId, from, System.currentTimeMillis())
        if (claimed) refreshOperations()
        return claimed
    }

    @Synchronized
    fun updateOperationStateIf(operationId: String, from: OperationState, to: OperationState): Boolean {
        val changed = OperationsDao.updateOperationStateIf(database.writableDatabase, operationId, from, to, System.currentTimeMillis())
        if (changed) refreshOperations()
        return changed
    }

    /**
     * One item rewritten in place. [refresh] `false` leaves [operations] stale until the next
     * refreshing write or [refresh] -- the extraction worker's per-frame journal writes would
     * otherwise reload every operation with all its items on each one (quadratic in a large
     * `Here` extraction); it refreshes on a 250 ms / 8 MiB throttle and at the end instead.
     */
    @Synchronized
    fun updateItem(operationId: String, item: OperationItem, refresh: Boolean = true) {
        OperationsDao.updateItem(database.writableDatabase, operationId, item, System.currentTimeMillis())
        if (refresh) refreshOperations()
    }

    @Synchronized
    fun updateOperationState(operationId: String, state: OperationState) {
        OperationsDao.updateOperationState(database.writableDatabase, operationId, state, System.currentTimeMillis())
        refreshOperations()
    }

    @Synchronized
    fun setCancelRequested(operationId: String) {
        OperationsDao.setCancelRequested(database.writableDatabase, operationId)
    }

    @Synchronized
    fun isCancelRequested(operationId: String): Boolean = OperationsDao.isCancelRequested(database.readableDatabase, operationId)

    /** See [OperationsDao.retryExtract]. */
    @Synchronized
    fun retryExtract(operationId: String): Boolean {
        val retried = OperationsDao.retryExtract(database.writableDatabase, operationId, System.currentTimeMillis())
        if (retried) refreshOperations()
        return retried
    }

    @Synchronized
    fun putEntryDigests(operationId: String, digests: Map<Int, String>) =
        OperationsDao.putEntryDigests(database.writableDatabase, operationId, digests)

    @Synchronized
    fun entryDigests(operationId: String): Map<Int, String> = OperationsDao.entryDigests(database.readableDatabase, operationId)

    // ------------------------------------------------------------------ M3.5: create plans

    @Synchronized
    fun putWithCreatePlan(operation: FileOperation, plan: CompressPlan, manifest: List<CompressManifestEntry>) {
        OperationsDao.putWithCreatePlan(database.writableDatabase, operation, plan, manifest)
        refreshOperations()
    }

    @Synchronized
    fun createPlan(operationId: String): CompressPlan? = OperationsDao.createPlan(database.readableDatabase, operationId)

    @Synchronized
    fun createManifest(operationId: String): List<CompressManifestEntry> = OperationsDao.createManifest(database.readableDatabase, operationId)

    @Synchronized
    fun spooledPath(operationId: String, ordinal: Int): String? = OperationsDao.spooledPath(database.readableDatabase, operationId, ordinal)

    @Synchronized
    fun setSpooledPath(operationId: String, ordinal: Int, path: String?) =
        OperationsDao.setSpooledPath(database.writableDatabase, operationId, ordinal, path)

    @Synchronized
    fun hasCreatePlan(operationId: String): Boolean = OperationsDao.hasCreatePlan(database.readableDatabase, operationId)

    @Synchronized
    fun claimCreate(operationId: String, from: Set<OperationState>): Boolean {
        val claimed = OperationsDao.claimCreate(database.writableDatabase, operationId, from, System.currentTimeMillis())
        if (claimed) refreshOperations()
        return claimed
    }

    @Synchronized
    fun incrementCreateRestartCount(operationId: String) = OperationsDao.incrementCreateRestartCount(database.writableDatabase, operationId)

    @Synchronized
    fun createRestartCount(operationId: String): Int = OperationsDao.createRestartCount(database.readableDatabase, operationId)

    @Synchronized
    fun setCreateCancelRequested(operationId: String) = OperationsDao.setCreateCancelRequested(database.writableDatabase, operationId)

    @Synchronized
    fun isCreateCancelRequested(operationId: String): Boolean = OperationsDao.isCreateCancelRequested(database.readableDatabase, operationId)

    @Synchronized
    fun putCreatePlanItem(operationId: String, item: CompressPlanItem, refresh: Boolean = true) {
        OperationsDao.putCreatePlanItem(database.writableDatabase, operationId, item)
        if (refresh) refreshOperations()
    }

    @Synchronized
    fun createPlanItems(operationId: String): List<CompressPlanItem> = OperationsDao.createPlanItems(database.readableDatabase, operationId)

    @Synchronized
    fun retryCreate(operationId: String): Boolean {
        val retried = OperationsDao.retryCreate(database.writableDatabase, operationId, System.currentTimeMillis())
        if (retried) refreshOperations()
        return retried
    }

    /** Republishes [operations] from the database (for a caller that wrote with `refresh = false`). */
    @Synchronized
    fun refresh() = refreshOperations()

    private fun refreshOperations() {
        _operations.value = OperationsDao.list(database.readableDatabase)
    }

    /**
     * Marks in-flight records as interrupted once per real app-process session.
     *
     * Multiple services may construct their own [OperationJournal] in the same process. A static
     * process session identifier prevents the second instance from misclassifying live work as an
     * interrupted operation merely because it read the same database.
     */
    private fun recoverFromPriorProcessIfNeeded() {
        synchronized(PROCESS_SESSION_LOCK) {
            val previousSession = processSessionPreferences.getString(PROCESS_SESSION_KEY, null)
            if (previousSession == PROCESS_SESSION_ID) return

            val recoveredAt = System.currentTimeMillis()
            val db = database.writableDatabase
            val current = OperationsDao.list(db)
            current.forEach { operation ->
                val recovered = OperationRecoveryPolicy.recoverAfterProcessDeath(operation, recoveredAt)
                if (recovered != operation) OperationsDao.put(db, recovered)
            }

            check(
                processSessionPreferences.edit()
                    .putString(PROCESS_SESSION_KEY, PROCESS_SESSION_ID)
                    .commit(),
            ) { "Unable to initialise the operation journal session." }
        }
    }

    private companion object {
        const val PROCESS_SESSION_PREFERENCES_NAME = "fylz_operation_journal"
        const val PROCESS_SESSION_KEY = "process_session"

        val PROCESS_SESSION_ID: String = UUID.randomUUID().toString()
        val PROCESS_SESSION_LOCK = Any()
    }
}
