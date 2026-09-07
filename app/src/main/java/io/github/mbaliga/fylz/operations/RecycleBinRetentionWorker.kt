package io.github.mbaliga.fylz.operations

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Enqueues [RecycleBinRetentionWorker] once a day, [io.github.mbaliga.fylz.backup.BackupScheduler]
 * / [io.github.mbaliga.fylz.index.LocalIndexScheduler]-modelled: a unique periodic work request
 * under a stable name.
 *
 * [reconcile] is safe to call on every app start and after every
 * [RecycleBinRetentionStore.setPeriod] write -- it cancels the periodic job outright for
 * [RecycleBinRetentionPeriod.KEEP_UNTIL_EMPTIED] (nothing ever needs to wake the app for a purge
 * that never has anything to do) and otherwise `UPDATE`s the existing request rather than
 * replacing it, so a purge already running is not orphaned by a reconcile that changed nothing
 * about the schedule itself.
 */
class RecycleBinRetentionScheduler(private val context: Context) {
    fun reconcile(period: RecycleBinRetentionPeriod = RecycleBinRetentionStore(context).period()) {
        if (period == RecycleBinRetentionPeriod.KEEP_UNTIL_EMPTIED) {
            cancel()
            return
        }
        val request = PeriodicWorkRequestBuilder<RecycleBinRetentionWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel() = WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)

    companion object {
        const val WORK_NAME = "fylz-recycle-bin-retention-purge"
        const val TAG = "fylz-recycle-bin-retention"
    }
}

/**
 * Permanently removes whatever [RecycleBinRetentionPolicy.expired] selects under the stored
 * [RecycleBinRetentionPeriod] -- a no-op success for [RecycleBinRetentionPeriod.KEEP_UNTIL_EMPTIED]
 * (the default; [RecycleBinRetentionScheduler] does not even enqueue this worker in that case,
 * this is only the belt-and-suspenders check for a run that was already queued when the
 * preference changed underneath it).
 *
 * One record's failure (a provider that has gone away, a revoked permission) must never abort
 * the rest of the sweep, so each removal is caught and skipped individually rather than letting
 * the whole worker fail and retry work that already succeeded.
 */
class RecycleBinRetentionWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val period = RecycleBinRetentionStore(applicationContext).period()
            if (period == RecycleBinRetentionPeriod.KEEP_UNTIL_EMPTIED) return@withContext Result.success()

            val service = RecycleBinService(applicationContext)
            val expired = RecycleBinRetentionPolicy.expired(
                records = service.records(),
                period = period,
                nowMillis = System.currentTimeMillis(),
            )
            expired.forEach { record ->
                coroutineContext.ensureActive()
                try {
                    // The user's own standing preference (a numbered retention window, chosen
                    // ahead of any single item reaching it) stands in for the explicit per-item
                    // confirmation RecycleBinPolicy.allowPermanentDelete otherwise requires from
                    // the bin's UI.
                    service.permanentlyDelete(record.itemId, confirmed = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    // Skip and keep sweeping -- see the class doc.
                }
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}
