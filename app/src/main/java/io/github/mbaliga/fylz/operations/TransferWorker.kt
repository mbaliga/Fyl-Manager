package io.github.mbaliga.fylz.operations

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.mbaliga.fylz.FylzApplication
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
 * Scope was deliberately copy and move: Phase 1's own goal statement is "transfers are durable"
 * specifically, and every other operation type (recycle, restore, permanent delete, archive, PDF,
 * rename, create) keeps running through [OperationRunner.run] unchanged. **M3.4 adds EXTRACT**
 * (`docs/agent/DESIGN-M34-SELECTIVE-EXTRACT.md` section 2.3): the input carries only the operation
 * id ([KEY_OPERATION_ID]); the plan lives in the journal, and [ArchiveExtractor.run] claims it, runs
 * it and writes its outcome. Two rules keep an extraction from poisoning the transfers queued behind
 * it under `APPEND_OR_REPLACE`: **every journaled outcome returns [Result.success]** (SUCCEEDED,
 * PARTIAL, FAILED, CANCELLED and "not claimed" alike -- the journal, not WorkManager, is the record),
 * and a user cancel is a **flag** in the plan row (`extract_plans.cancel_requested`, set by
 * [OperationRunner.cancel] or the notification's [ExtractCancelReceiver]), never
 * `WorkManager.cancelWorkById`, which would cancel the whole unique chain. Only a system stop
 * (`isStopped` with a reason other than `STOP_REASON_CANCELLED_BY_APP`) returns [Result.retry], after
 * the extractor has left the items `PAUSED_BY_SYSTEM` with their staging kept for the re-run to
 * resume. The extraction's notification shows a bar and a byte line ("Reading archive…" until the
 * first entry), which the copy/move notification does not (logged).
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
    /** How an EXTRACT run gets its extractor; the default needs the real [FylzApplication], tests inject a fake. */
    private val extractorFactory: (Context) -> ArchiveExtractor = ::defaultExtractor,
    /** How a CREATE run gets its creator (M3.5); the default needs the real [FylzApplication], tests inject a fake. */
    private val creatorFactory: (Context) -> ArchiveCreator = ::defaultCreator,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val type = inputData.getString(KEY_TYPE)?.let(FileOperationType::valueOf)
            ?: return Result.failure(errorData("Missing transfer type."))
        if (type == FileOperationType.EXTRACT) return doExtract()
        if (type == FileOperationType.ARCHIVE) return doCreate()
        if (type != FileOperationType.COPY && type != FileOperationType.MOVE) {
            return Result.failure(errorData("TransferWorker only handles COPY, MOVE, EXTRACT and ARCHIVE."))
        }
        val sources = inputData.getStringArray(KEY_SOURCES)?.map(Uri::parse)
            ?.takeIf { it.isNotEmpty() }
            ?: return Result.failure(errorData("Missing source items."))
        val destination = inputData.getString(KEY_DESTINATION)?.let(Uri::parse)
            ?: return Result.failure(errorData("Missing destination."))
        val conflictPolicy = inputData.getString(KEY_CONFLICT_POLICY)
            ?.let(ConflictPolicy::valueOf)
            ?: ConflictPolicy.ASK
        val nameOverrides = readNameOverrides()
        val conflictResolutions = readConflictResolutions()
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
                FileOperationType.COPY -> fileOperations.copy(
                    sources, destination, conflictPolicy, nameOverrides, conflictResolutions, onProgress,
                )
                FileOperationType.MOVE -> fileOperations.move(
                    sources, destination, conflictPolicy, nameOverrides, conflictResolutions, onProgress,
                )
                else -> error("unreachable")
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            if (isStopped) Result.retry() else Result.failure(errorData("Cancelled"))
        } catch (failure: Throwable) {
            Result.failure(errorData(failure.message ?: failure::class.java.simpleName))
        }
    }

    /**
     * The EXTRACT path (M3.4). Every outcome the extractor journals is [Result.success]; a missing
     * operation id is the one [Result.failure] (nothing to journal against); a system stop is
     * [Result.retry] once the items are `PAUSED_BY_SYSTEM`.
     */
    private suspend fun doExtract(): Result {
        val operationId = inputData.getString(KEY_OPERATION_ID)?.takeIf { it.isNotBlank() }
            ?: return Result.failure(errorData("Missing operation id."))
        // A refused foreground start (API 31+ background restrictions) must not fail the extraction:
        // the work still runs, only without its notification.
        runCatching { setForeground(extractForegroundInfo(operationId, ExtractProgress(0, 0, 0L, null, readingArchive = true))) }
        return try {
            val extractor = extractorFactory(applicationContext)
            val outcome = extractor.run(
                operationId = operationId,
                ownWorkId = id,
                stopReason = { if (isStopped) stopReason else WorkInfo.STOP_REASON_NOT_STOPPED },
                onProgress = { progress ->
                    setProgressAsync(
                        Data.Builder()
                            .putInt(KEY_PROGRESS_ITEM_INDEX, progress.itemIndex)
                            .putInt(KEY_PROGRESS_ITEM_COUNT, progress.itemCount)
                            .putLong(KEY_PROGRESS_COMPLETED_BYTES, progress.completedBytes)
                            .apply { progress.totalBytes?.let { putLong(KEY_PROGRESS_TOTAL_BYTES, it) } }
                            .build(),
                    )
                    setForegroundAsync(extractForegroundInfo(operationId, progress))
                },
            )
            when (outcome) {
                ExtractRunOutcome.PausedBySystem -> Result.retry()
                ExtractRunOutcome.NotClaimed, is ExtractRunOutcome.Finished -> Result.success()
            }
        } catch (cancelled: CancellationException) {
            // The extractor has already written PAUSED_BY_SYSTEM (or CANCELLED for an app cancel of the
            // work itself) under NonCancellable before rethrowing.
            if (isStopped && stopReason != WorkInfo.STOP_REASON_CANCELLED_BY_APP) Result.retry() else Result.success()
        } catch (failure: Throwable) {
            // The extractor journals its own failures; anything escaping it is a bug, and still must
            // not poison the chain.
            Result.success()
        }
    }

    /**
     * The CREATE path (M3.5, mirrors [doExtract] exactly). Every outcome the creator journals is
     * [Result.success]; a missing operation id is the one [Result.failure]; a system stop is
     * [Result.retry] once the items are `PAUSED_BY_SYSTEM` (bounded to
     * [ArchiveCreator.MAX_RESTARTS] restarts, checked by the creator itself at claim).
     */
    private suspend fun doCreate(): Result {
        val operationId = inputData.getString(KEY_OPERATION_ID)?.takeIf { it.isNotBlank() }
            ?: return Result.failure(errorData("Missing operation id."))
        runCatching { setForeground(createForegroundInfo(operationId, CreateProgress(0L, null, 0, 0))) }
        return try {
            val creator = creatorFactory(applicationContext)
            val outcome = creator.run(
                operationId = operationId,
                ownWorkId = id,
                stopReason = { if (isStopped) stopReason else WorkInfo.STOP_REASON_NOT_STOPPED },
                onProgress = { progress ->
                    setProgressAsync(
                        Data.Builder()
                            .putLong(KEY_PROGRESS_COMPLETED_BYTES, progress.completedBytes)
                            .apply { progress.totalBytes?.let { putLong(KEY_PROGRESS_TOTAL_BYTES, it) } }
                            .build(),
                    )
                    setForegroundAsync(createForegroundInfo(operationId, progress))
                },
            )
            when (outcome) {
                CreateRunOutcome.PausedBySystem -> Result.retry()
                CreateRunOutcome.NotClaimed, is CreateRunOutcome.Finished -> Result.success()
            }
        } catch (cancelled: CancellationException) {
            if (isStopped && stopReason != WorkInfo.STOP_REASON_CANCELLED_BY_APP) Result.retry() else Result.success()
        } catch (failure: Throwable) {
            Result.success()
        }
    }

    private fun createForegroundInfo(operationId: String, progress: CreateProgress): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Transfers", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress for copy and move operations"
            },
        )
        val total = progress.totalBytes
        val text = when {
            total != null && total > 0L -> "${formatBytes(progress.completedBytes)} of ${formatBytes(total)}"
            else -> formatBytes(progress.completedBytes)
        }
        val cancel = PendingIntent.getBroadcast(
            applicationContext,
            operationId.hashCode(),
            CreateCancelReceiver.intent(applicationContext, operationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Compressing")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(0, "Cancel", cancel)
        if (total == null || total <= 0L) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(PROGRESS_MAX, permille(progress.completedBytes, total), false)
        }
        return ForegroundInfo(NOTIFICATION_ID, builder.build())
    }

    private fun extractForegroundInfo(operationId: String, progress: ExtractProgress): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Transfers", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress for copy and move operations"
            },
        )
        val total = progress.totalBytes
        val text = when {
            progress.readingArchive -> "Reading archive…"
            total != null && total > 0L -> "${formatBytes(progress.completedBytes)} of ${formatBytes(total)}"
            else -> formatBytes(progress.completedBytes)
        }
        val cancel = PendingIntent.getBroadcast(
            applicationContext,
            operationId.hashCode(),
            ExtractCancelReceiver.intent(applicationContext, operationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Extracting")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(0, "Cancel", cancel)
        if (progress.readingArchive || total == null || total <= 0L) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(PROGRESS_MAX, permille(progress.completedBytes, total), false)
        }
        return ForegroundInfo(NOTIFICATION_ID, builder.build())
    }

    private fun errorData(message: String) = Data.Builder().putString(KEY_ERROR_MESSAGE, message).build()

    private fun readNameOverrides(): Map<Uri, String> = nameOverridesFrom(inputData)

    private fun readConflictResolutions(): Map<Uri, ConflictPolicy> = conflictResolutionsFrom(inputData)

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
        internal const val KEY_OPERATION_ID = "operation_id"
        internal const val KEY_SOURCES = "sources"
        internal const val KEY_DESTINATION = "destination"
        internal const val KEY_CONFLICT_POLICY = "conflict_policy"
        internal const val KEY_NAME_OVERRIDE_URIS = "name_override_uris"
        internal const val KEY_NAME_OVERRIDE_NAMES = "name_override_names"
        internal const val KEY_CONFLICT_RESOLUTION_URIS = "conflict_resolution_uris"
        internal const val KEY_CONFLICT_RESOLUTION_POLICIES = "conflict_resolution_policies"
        internal const val KEY_ERROR_MESSAGE = "error_message"
        internal const val KEY_PROGRESS_ITEM_INDEX = "progress_item_index"
        internal const val KEY_PROGRESS_ITEM_COUNT = "progress_item_count"
        internal const val KEY_PROGRESS_COMPLETED_BYTES = "progress_completed_bytes"
        internal const val KEY_PROGRESS_TOTAL_BYTES = "progress_total_bytes"

        private const val CHANNEL_ID = "fylz_transfers"
        private const val NOTIFICATION_ID = 8_300

        /** The extraction bar's scale: permille, so a 5 GB extraction moves it smoothly past 2^31 bytes. */
        internal const val PROGRESS_MAX = 1_000

        /** `completed / total` on the 0..[PROGRESS_MAX] scale, overflow-safe for any byte counts. */
        internal fun permille(completed: Long, total: Long): Int {
            if (total <= 0L) return 0
            val fraction = completed.toDouble() / total.toDouble()
            return (fraction * PROGRESS_MAX).toInt().coerceIn(0, PROGRESS_MAX)
        }

        internal fun formatBytes(bytes: Long): String {
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            var value = bytes.toDouble()
            var unit = 0
            while (value >= 1000.0 && unit < units.lastIndex) {
                value /= 1000.0
                unit += 1
            }
            return if (unit == 0) "$bytes B" else String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit])
        }

        /** The real extractor: every collaborator from the application (M3.4). */
        private fun defaultExtractor(context: Context): ArchiveExtractor {
            val app = context.applicationContext as FylzApplication
            return ArchiveExtractor(
                context = app,
                journal = OperationJournal(app),
                catalog = app.archiveCatalog,
                extractionClient = { app.decoderClient.extraction() },
            )
        }

        /** The EXTRACT input: the operation id only; the plan is in the journal. */
        fun extractInputData(operationId: String): Data = Data.Builder()
            .putString(KEY_TYPE, FileOperationType.EXTRACT.name)
            .putString(KEY_OPERATION_ID, operationId)
            .build()

        /** The real creator: every collaborator from the application (M3.5). */
        private fun defaultCreator(context: Context): ArchiveCreator {
            val app = context.applicationContext as FylzApplication
            return ArchiveCreator(
                context = app,
                journal = OperationJournal(app),
                writerClient = { app.decoderClient.writer() },
            )
        }

        /** The CREATE input: the operation id only; the plan is in the journal (M3.5). */
        fun createInputData(operationId: String): Data = Data.Builder()
            .putString(KEY_TYPE, FileOperationType.ARCHIVE.name)
            .putString(KEY_OPERATION_ID, operationId)
            .build()

        fun inputData(
            type: FileOperationType,
            sourceUris: List<Uri>,
            destinationTreeUri: Uri,
            conflictPolicy: ConflictPolicy,
            nameOverrides: Map<Uri, String> = emptyMap(),
            conflictResolutions: Map<Uri, ConflictPolicy> = emptyMap(),
        ): Data {
            // .entries walked once, so the two arrays stay paired by index regardless of which
            // Map implementation the caller happens to pass -- keys/values iterated separately
            // are only guaranteed to agree for a LinkedHashMap specifically.
            val overrideEntries = nameOverrides.entries.toList()
            val resolutionEntries = conflictResolutions.entries.toList()
            return Data.Builder()
                .putString(KEY_TYPE, type.name)
                .putStringArray(KEY_SOURCES, sourceUris.map(Uri::toString).toTypedArray())
                .putString(KEY_DESTINATION, destinationTreeUri.toString())
                .putString(KEY_CONFLICT_POLICY, conflictPolicy.name)
                .putStringArray(KEY_NAME_OVERRIDE_URIS, overrideEntries.map { it.key.toString() }.toTypedArray())
                .putStringArray(KEY_NAME_OVERRIDE_NAMES, overrideEntries.map { it.value }.toTypedArray())
                .putStringArray(KEY_CONFLICT_RESOLUTION_URIS, resolutionEntries.map { it.key.toString() }.toTypedArray())
                .putStringArray(KEY_CONFLICT_RESOLUTION_POLICIES, resolutionEntries.map { it.value.name }.toTypedArray())
                .build()
        }
    }
}

/** The [TransferWorker.KEY_NAME_OVERRIDE_URIS]/[TransferWorker.KEY_NAME_OVERRIDE_NAMES] pair of
 * parallel arrays [inputData] carries, rebuilt back into the map [FileOperationService.copy]/
 * [FileOperationService.move] actually take -- `Data` has no map type of its own. A top-level
 * function (not a private instance method) so it can be tested against a [Data] built by
 * [TransferWorker.inputData] without needing a real [TransferWorker] instance. */
internal fun nameOverridesFrom(inputData: Data): Map<Uri, String> {
    val uris = inputData.getStringArray(TransferWorker.KEY_NAME_OVERRIDE_URIS) ?: return emptyMap()
    val names = inputData.getStringArray(TransferWorker.KEY_NAME_OVERRIDE_NAMES) ?: return emptyMap()
    return uris.map(Uri::parse).zip(names.toList()).toMap()
}

/** The [TransferWorker.KEY_CONFLICT_RESOLUTION_URIS]/[TransferWorker.KEY_CONFLICT_RESOLUTION_POLICIES]
 * pair of parallel arrays [inputData] carries -- a `ConflictSheet`'s own per-item choices (P1.6),
 * rebuilt back into the map [FileOperationService.copy]/[FileOperationService.move] actually take. */
internal fun conflictResolutionsFrom(inputData: Data): Map<Uri, ConflictPolicy> {
    val uris = inputData.getStringArray(TransferWorker.KEY_CONFLICT_RESOLUTION_URIS) ?: return emptyMap()
    val policies = inputData.getStringArray(TransferWorker.KEY_CONFLICT_RESOLUTION_POLICIES) ?: return emptyMap()
    return uris.map(Uri::parse).zip(policies.map(ConflictPolicy::valueOf)).toMap()
}
