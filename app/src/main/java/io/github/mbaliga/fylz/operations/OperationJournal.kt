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
