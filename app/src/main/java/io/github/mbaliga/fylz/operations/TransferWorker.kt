package io.github.mbaliga.fylz.operations

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

/**
 * P1.2 (durable transfer queue): runs a copy or move through WorkManager, in the foreground, as
 * unique work [UNIQUE_WORK_NAME] (`APPEND_OR_REPLACE`, [OperationRunner.enqueueTransfer]) instead
 * of only on [OperationRunner]'s app-scoped [kotlinx.coroutines.CoroutineScope] (P0.5). This
 * intentionally delegates the actual transfer to the already-tested [FileOperationService] --
 * every staging, verification and conflict-resolution behaviour it already has is unchanged; what
 * this adds is the OS running it as a `dataSync` foreground service, so it keeps going through
 * more of what would otherwise stop it (backgrounding, memory pressure) and survives a runtime
 * restart via WorkManager's own persistence, not just an in-process `CoroutineScope`.
 *
 * Scope for this task is deliberately copy and move: Phase 1's own goal statement is "transfers
 * are durable" specifically, and every other operation type (recycle, restore, permanent delete,
 * archive, extract, PDF, rename, create) keeps running through [OperationRunner.run] unchanged.
 *
 * The notification's only action today is Cancel (via [WorkManager.createCancelPendingIntent]).
 * True mid-item Pause -- resuming a specific operation from wherever it left off, rather than
 * restarting it -- needs [FileOperationService] to support resuming an existing operation record
 * instead of always building a fresh one from a source list; that's a larger change this task
 * doesn't make, so Pause isn't offered here. A system stop is reported as [Result.retry], which
 * WorkManager itself backs off and re-runs -- there's no separate re-enqueue to write by hand.
 */
class TransferWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val type = inputData.getString(KEY_TYPE)?.let(FileOperationType::valueOf)
            ?: return Result.failure(errorData("Missing transfer type."))
        if (type != FileOperationType.COPY && type != FileOperationType.MOVE) {
            return Result.failure(errorData("TransferWorker only handles COPY and MOVE."))
        }
        val sources = inputData.getStringArray(KEY_SOURCES)?.map(Uri::parse)
            ?.takeIf { it.isNotEmpty() }
            ?: return Result.failure(errorData("Missing source items."))
        val destination = inputData.getString(KEY_DESTINATION)?.let(Uri::parse)
            ?: return Result.failure(errorData("Missing destination."))
        val conflictPolicy = inputData.getString(KEY_CONFLICT_POLICY)
            ?.let(ConflictPolicy::valueOf)
            ?: ConflictPolicy.ASK
        val label = if (type == FileOperationType.COPY) "Copying" else "Moving"

        setForeground(foregroundInfo(label, 0, 0))
        val fileOperations = FileOperationService(applicationContext)

        return try {
            val onProgress: (FileOperationService.Progress) -> Unit = { progress ->
                setProgressAsync(
                    Data.Builder()
                        .putInt(KEY_PROGRESS_ITEM_INDEX, progress.itemIndex)
                        .putInt(KEY_PROGRESS_ITEM_COUNT, progress.itemCount)
                        .putLong(KEY_PROGRESS_COMPLETED_BYTES, progress.completedBytes)
                        .apply { progress.totalBytes?.let { putLong(KEY_PROGRESS_TOTAL_BYTES, it) } }
                        .build(),
                )
                setForegroundAsync(foregroundInfo(label, progress.itemIndex + 1, progress.itemCount))
            }
            when (type) {
                FileOperationType.COPY -> fileOperations.copy(sources, destination, conflictPolicy, onProgress)
                FileOperationType.MOVE -> fileOperations.move(sources, destination, conflictPolicy, onProgress)
                else -> error("unreachable")
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            if (isStopped) Result.retry() else Result.failure(errorData("Cancelled"))
        } catch (failure: Throwable) {
            Result.failure(errorData(failure.message ?: failure::class.java.simpleName))
        }
    }

    private fun errorData(message: String) = Data.Builder().putString(KEY_ERROR_MESSAGE, message).build()

    private fun foregroundInfo(label: String, itemIndex: Int, itemCount: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Transfers", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress for copy and move operations"
            },
        )
        val text = if (itemCount > 0) "Item $itemIndex of $itemCount" else "Starting…"
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(label)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(
                0,
                "Cancel",
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
            )
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "fylz-transfers"

        internal const val KEY_TYPE = "type"
        internal const val KEY_SOURCES = "sources"
        internal const val KEY_DESTINATION = "destination"
        internal const val KEY_CONFLICT_POLICY = "conflict_policy"
        internal const val KEY_ERROR_MESSAGE = "error_message"
        internal const val KEY_PROGRESS_ITEM_INDEX = "progress_item_index"
        internal const val KEY_PROGRESS_ITEM_COUNT = "progress_item_count"
        internal const val KEY_PROGRESS_COMPLETED_BYTES = "progress_completed_bytes"
        internal const val KEY_PROGRESS_TOTAL_BYTES = "progress_total_bytes"

        private const val CHANNEL_ID = "fylz_transfers"
        private const val NOTIFICATION_ID = 8_300

        fun inputData(
            type: FileOperationType,
            sourceUris: List<Uri>,
            destinationTreeUri: Uri,
            conflictPolicy: ConflictPolicy,
        ): Data = Data.Builder()
            .putString(KEY_TYPE, type.name)
            .putStringArray(KEY_SOURCES, sourceUris.map(Uri::toString).toTypedArray())
            .putString(KEY_DESTINATION, destinationTreeUri.toString())
            .putString(KEY_CONFLICT_POLICY, conflictPolicy.name)
            .build()
    }
}
