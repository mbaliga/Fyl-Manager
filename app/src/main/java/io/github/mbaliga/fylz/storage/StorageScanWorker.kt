package io.github.mbaliga.fylz.storage

import android.content.Context
import android.webkit.MimeTypeMap
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque
import kotlin.coroutines.coroutineContext

/**
 * Enqueues [StorageScanWorker]. [io.github.mbaliga.fylz.index.LocalIndexScheduler]-modelled, down
 * to the unique-work replace policy -- a tap on the Storage card's refresh while a scan is
 * already running should restart it against whatever changed since, not queue a second walk
 * behind it.
 */
class StorageScanScheduler(private val context: Context) {
    fun scan() {
        val request = OneTimeWorkRequestBuilder<StorageScanWorker>()
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel() = WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)

    companion object {
        const val WORK_NAME = "fylz-storage-usage-scan"
        const val TAG = "fylz-storage-usage"
    }
}

/**
 * Walks every mounted volume under full filesystem access and totals bytes per [StorageKind],
 * writing the result to [StorageUsageStore]. Per-kind totals exist nowhere else in the app --
 * MediaStore is never queried anywhere in this codebase -- so this worker is the Storage card's
 * only source for anything past the plain used/total [StorageRoot] already reports.
 *
 * Runs only from [StorageScanScheduler] (a background job the user's refresh tap or an eventual
 * periodic schedule enqueues), never from composition -- a card that appeared on screen is not
 * license to walk the whole filesystem.
 *
 * A no-op success, not a failure, when [FullAccessPermission] is not granted: a SAF-only install
 * has no route to a device-wide total (see [FileStorageProvider]'s own KDoc), so running this
 * worker there would either scan nothing or scan one arbitrarily-granted subtree and present it
 * as though it were the whole device. Silence is the honest answer -- [StorageUsageStore] is
 * simply never written, and the Storage card's own "no bytes at all" branch already covers it.
 */
class StorageScanWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!FullAccessPermission.isGranted()) return@withContext Result.success()

        try {
            val volumes = FylzFilesDocumentsProvider.discoverVolumes(applicationContext)
            val totals = HashMap<StorageKind, Long>()
            var truncated = false
            volumes.forEach { volume ->
                coroutineContext.ensureActive()
                val result = walk(volume.directory)
                result.bytes.forEach { (kind, bytes) -> totals[kind] = (totals[kind] ?: 0L) + bytes }
                truncated = truncated || result.truncated
            }

            StorageUsageStore(applicationContext).write(
                StorageUsageSnapshot(
                    scannedAtMillis = System.currentTimeMillis(),
                    kindBytes = totals,
                    truncated = truncated,
                ),
            )
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    /** Breadth-first, the same shape as [io.github.mbaliga.fylz.index.LocalIndexWorker]'s own
     *  scope walk: a bounded queue rather than recursion, so neither a deep tree nor a wide one
     *  can blow the stack. */
    private suspend fun walk(root: File): WalkResult {
        val facts = ArrayList<ScannedFileFacts>()
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(root to 0)
        var visited = 0
        var truncated = false
        while (queue.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (directory, depth) = queue.removeFirst()
            if (depth > MAX_DEPTH) {
                truncated = true
                continue
            }
            val children = runCatching { directory.listFiles() }.getOrNull() ?: continue
            for (child in children) {
                if (visited >= MAX_VISITED_ENTRIES) {
                    truncated = true
                    break
                }
                visited += 1
                if (child.isDirectory) {
                    queue.add(child to depth + 1)
                } else {
                    facts += ScannedFileFacts(
                        name = child.name,
                        mimeType = mimeTypeOf(child),
                        sizeBytes = runCatching { child.length() }.getOrDefault(0L),
                    )
                }
            }
        }
        return WalkResult(classifyBytes(facts), truncated)
    }

    private fun mimeTypeOf(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"

    private data class WalkResult(val bytes: Map<StorageKind, Long>, val truncated: Boolean)

    private companion object {
        const val MAX_DEPTH = 64
        const val MAX_VISITED_ENTRIES = 500_000
    }
}
