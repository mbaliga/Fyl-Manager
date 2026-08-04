package io.github.mbaliga.fylz.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class BackupScheduler(private val context: Context) {
    private val workManager = WorkManager.getInstance(context)

    fun reconcile(plans: List<BackupPlan> = BackupStore(context).plans()) {
        plans.forEach(::schedule)
    }

    fun schedule(plan: BackupPlan) {
        cancel(plan.id)
        if (!plan.enabled || !plan.schedule.hasAutomaticTrigger) return
        val constraints = constraints(plan.conditions)
        if (plan.schedule.dailyEnabled) {
            val request = PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delayUntil(plan.schedule.dailyHour, plan.schedule.dailyMinute))
                .setConstraints(constraints)
                .setInputData(data(plan.id, BackupTrigger.DAILY_WINDOW))
                .addTag(tag(plan.id))
                .build()
            workManager.enqueueUniquePeriodicWork(
                dailyName(plan.id),
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
        if (plan.schedule.mediaCountEnabled) {
            val minutes = plan.schedule.mediaScanIntervalMinutes.toLong().coerceAtLeast(15L)
            val request = PeriodicWorkRequestBuilder<BackupWorker>(minutes, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setInputData(data(plan.id, BackupTrigger.MEDIA_THRESHOLD))
                .addTag(tag(plan.id))
                .build()
            workManager.enqueueUniquePeriodicWork(
                mediaName(plan.id),
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }

    fun runNow(planId: String) {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setInputData(data(planId, BackupTrigger.MANUAL))
            .addTag(tag(planId))
            .build()
        workManager.enqueueUniqueWork("fylz-backup-manual-$planId", ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(planId: String) {
        workManager.cancelUniqueWork(dailyName(planId))
        workManager.cancelUniqueWork(mediaName(planId))
        workManager.cancelAllWorkByTag(tag(planId))
    }

    private fun constraints(value: BackupConditions): Constraints = Constraints.Builder()
        .setRequiresCharging(value.requiresCharging)
        .setRequiresDeviceIdle(value.requiresDeviceIdle)
        .setRequiresBatteryNotLow(value.requiresBatteryNotLow)
        .setRequiresStorageNotLow(value.requiresStorageNotLow)
        .setRequiredNetworkType(
            when (value.network) {
                BackupNetworkConstraint.NONE -> NetworkType.NOT_REQUIRED
                BackupNetworkConstraint.CONNECTED -> NetworkType.CONNECTED
                BackupNetworkConstraint.UNMETERED -> NetworkType.UNMETERED
            },
        )
        .build()

    private fun delayUntil(hour: Int, minute: Int): Duration {
        val now = ZonedDateTime.now()
        var next = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }

    private fun data(planId: String, trigger: BackupTrigger): Data = Data.Builder()
        .putString(BackupWorker.KEY_PLAN_ID, planId)
        .putString(BackupWorker.KEY_TRIGGER, trigger.name)
        .build()

    private fun tag(id: String) = "fylz-backup-$id"
    private fun dailyName(id: String) = "fylz-backup-daily-$id"
    private fun mediaName(id: String) = "fylz-backup-media-$id"
}

class BackupWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val planId = inputData.getString(KEY_PLAN_ID) ?: return Result.failure()
        val trigger = runCatching {
            BackupTrigger.valueOf(inputData.getString(KEY_TRIGGER) ?: BackupTrigger.MANUAL.name)
        }.getOrDefault(BackupTrigger.MANUAL)
        val store = BackupStore(applicationContext)
        val plan = store.plan(planId) ?: return Result.failure()
        if (trigger != BackupTrigger.MANUAL && (!plan.enabled || !plan.schedule.hasAutomaticTrigger)) {
            return Result.success()
        }

        val lease = store.acquireLease(plan.id)
        if (lease == null) {
            store.putRun(
                BackupRunRecord(
                    planId = plan.id,
                    trigger = trigger,
                    status = BackupRunStatus.SKIPPED_BUSY,
                    completedAtMillis = System.currentTimeMillis(),
                    message = "Another backup for this plan is already running.",
                ),
            )
            return Result.success()
        }

        return try {
            if (trigger == BackupTrigger.MEDIA_THRESHOLD) {
                val scan = scanMedia(plan, store)
                if (!scan.thresholdReached) {
                    store.putRun(
                        BackupRunRecord(
                            planId = plan.id,
                            trigger = trigger,
                            status = BackupRunStatus.SKIPPED_THRESHOLD,
                            completedAtMillis = System.currentTimeMillis(),
                            message = "${scan.pendingNewMedia}/${plan.schedule.mediaThreshold} new images or videos observed.",
                        ),
                    )
                    return Result.success()
                }
            }

            setForeground(createForegroundInfo(plan.name, "Preparing backup…", 0))
            val run = BackupService(applicationContext).runBackup(plan.id, trigger) { progress ->
                store.refreshLease(plan.id, lease)
                setProgressAsync(
                    Data.Builder()
                        .putString(KEY_PROGRESS_NAME, progress.displayName)
                        .putInt(KEY_PROGRESS_FILES, progress.completedFiles)
                        .putLong(KEY_PROGRESS_BYTES, progress.completedBytes)
                        .build(),
                )
                setForegroundAsync(
                    createForegroundInfo(
                        plan.name,
                        "${progress.completedFiles} files · ${formatBytes(progress.completedBytes)}",
                        progress.completedFiles,
                    ),
                )
            }
            when (run.status) {
                BackupRunStatus.SUCCEEDED,
                BackupRunStatus.SKIPPED_THRESHOLD,
                BackupRunStatus.SKIPPED_BUSY,
                -> Result.success()
                BackupRunStatus.NEEDS_ATTENTION -> Result.failure()
                BackupRunStatus.FAILED -> if (runAttemptCount < 3) Result.retry() else Result.failure()
                BackupRunStatus.CANCELLED -> Result.failure()
                else -> Result.retry()
            }
        } catch (failure: Throwable) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } finally {
            store.releaseLease(plan.id, lease)
        }
    }

    private fun scanMedia(plan: BackupPlan, store: BackupStore): BackupMediaScanResult {
        val root = DocumentFile.fromTreeUri(applicationContext, Uri.parse(plan.sourceTreeUri))
            ?: error("Backup source is unavailable.")
        val current = mutableSetOf<String>()
        collectMedia(root, current, 0)
        val previous = store.mediaState(plan.id)
        val newlyObserved = if (previous.initialized) (current - previous.knownMediaUris).size else 0
        val pending = if (previous.initialized) previous.pendingNewMedia + newlyObserved else 0
        store.putMediaState(
            plan.id,
            BackupMediaState(
                initialized = true,
                knownMediaUris = current,
                pendingNewMedia = pending,
                lastScanAtMillis = System.currentTimeMillis(),
            ),
        )
        return BackupMediaScanResult(
            newlyObserved = newlyObserved,
            pendingNewMedia = pending,
            thresholdReached = pending >= plan.schedule.mediaThreshold,
            initialized = previous.initialized,
        )
    }

    private fun collectMedia(directory: DocumentFile, output: MutableSet<String>, depth: Int) {
        require(depth <= MAX_MEDIA_DEPTH) { "Media scan nesting exceeds the safety limit." }
        directory.listFiles().forEach { child ->
            require(output.size <= MAX_MEDIA_ENTRIES) { "Media collection exceeds the scan safety limit." }
            if (child.isDirectory) collectMedia(child, output, depth + 1)
            else if (isMedia(child)) output += child.uri.toString()
        }
    }

    private fun isMedia(file: DocumentFile): Boolean {
        val mime = file.type.orEmpty()
        if (mime.startsWith("image/") || mime.startsWith("video/")) return true
        val extension = file.name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return extension in MEDIA_EXTENSIONS
    }

    private fun createForegroundInfo(title: String, text: String, progress: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Backups", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress for manual and scheduled Fylz backups"
            },
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(0, progress, true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID_BASE + title.hashCode().and(0x0fff), notification)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "${bytes / 1024L} KiB"
        bytes < 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L)} MiB"
        else -> "${bytes / (1024L * 1024L * 1024L)} GiB"
    }

    companion object {
        const val KEY_PLAN_ID = "plan_id"
        const val KEY_TRIGGER = "trigger"
        const val KEY_PROGRESS_NAME = "progress_name"
        const val KEY_PROGRESS_FILES = "progress_files"
        const val KEY_PROGRESS_BYTES = "progress_bytes"
        private const val CHANNEL_ID = "fylz_backups"
        private const val NOTIFICATION_ID_BASE = 8_200
        private const val MAX_MEDIA_DEPTH = 64
        private const val MAX_MEDIA_ENTRIES = 250_000
        private val MEDIA_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif",
            "mp4", "m4v", "mov", "mkv", "webm", "3gp", "avi",
        )
    }
}
