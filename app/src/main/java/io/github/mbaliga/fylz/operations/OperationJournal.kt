package io.github.mbaliga.fylz.operations

import android.content.Context
import io.github.mbaliga.fylz.data.FylzDatabase
import io.github.mbaliga.fylz.data.OperationsDao
import java.util.UUID

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
 */
class OperationJournal(context: Context) {
    private val database = FylzDatabase(context.applicationContext)
    private val processSessionPreferences =
        context.applicationContext.getSharedPreferences(PROCESS_SESSION_PREFERENCES_NAME, Context.MODE_PRIVATE)

    init {
        recoverFromPriorProcessIfNeeded()
    }

    @Synchronized
    fun list(): List<FileOperation> = OperationsDao.list(database.readableDatabase)

    @Synchronized
    fun find(id: String): FileOperation? = OperationsDao.find(database.readableDatabase, id)

    @Synchronized
    fun put(operation: FileOperation) {
        OperationsDao.put(database.writableDatabase, operation)
    }

    @Synchronized
    fun remove(id: String) {
        OperationsDao.remove(database.writableDatabase, id)
    }

    /** Keeps interrupted records visible until the user explicitly resolves or dismisses them. */
    @Synchronized
    fun clearFinished() {
        OperationsDao.clearFinished(database.writableDatabase)
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
