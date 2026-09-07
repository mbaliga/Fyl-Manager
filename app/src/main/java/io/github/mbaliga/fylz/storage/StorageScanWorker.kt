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
import java.util.PriorityQueue
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
            val topFiles = TopFilesTracker(TOP_FILES_LIMIT)
            volumes.forEach { volume ->
                coroutineContext.ensureActive()
                val result = walk(volume, topFiles)
                result.bytes.forEach { (kind, bytes) -> totals[kind] = (totals[kind] ?: 0L) + bytes }
                truncated = truncated || result.truncated
            }

            StorageUsageStore(applicationContext).write(
                StorageUsageSnapshot(
                    scannedAtMillis = System.currentTimeMillis(),
                    kindBytes = totals,
                    truncated = truncated,
                    largestFiles = topFiles.toLargeFileFacts(),
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
     *  can blow the stack. [topFiles] is shared across every volume's own call to this so the
     *  largest-files figure is a device-wide top, not reset per volume. */
    private suspend fun walk(volume: VolumeDescriptor, topFiles: TopFilesTracker): WalkResult {
        val root = volume.directory
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
                    val sizeBytes = runCatching { child.length() }.getOrDefault(0L)
                    facts += ScannedFileFacts(name = child.name, mimeType = mimeTypeOf(child), sizeBytes = sizeBytes)
                    topFiles.offer(volume.rootId, root, child, sizeBytes)
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

        /** How many of the scan's largest files [StorageUsageSnapshot.largestFiles] retains. */
        const val TOP_FILES_LIMIT = 50
    }
}

/**
 * Bounded top-[limit] largest files seen across the whole scan -- a min-heap keyed on size, so
 * this never holds more than [limit] candidates in memory regardless of how many files the walk
 * actually visits. Offering a new candidate once the heap is at capacity evicts the current
 * smallest kept candidate if the new one is larger, in O(log [limit]).
 */
private class TopFilesTracker(private val limit: Int) {
    private val heap = PriorityQueue<Candidate>(limit + 1, compareBy { it.sizeBytes })

    fun offer(rootId: String, rootDirectory: File, file: File, sizeBytes: Long) {
        if (sizeBytes <= 0L) return
        heap.add(Candidate(rootId, rootDirectory, file, sizeBytes))
        if (heap.size > limit) heap.poll()
    }

    /** Largest first -- the heap itself has no useful iteration order, so this sorts the (at
     *  most [limit]) retained candidates once, at the end of the scan, rather than on every offer. */
    fun toLargeFileFacts(): List<LargeFileFact> = heap
        .sortedByDescending { it.sizeBytes }
        .map { candidate ->
            LargeFileFact(
                uriString = FylzFilesDocumentsProvider
                    .documentUri(candidate.rootId, candidate.relativePath())
                    .toString(),
                displayName = candidate.file.name,
                sizeBytes = candidate.sizeBytes,
            )
        }

    private data class Candidate(val rootId: String, val rootDirectory: File, val file: File, val sizeBytes: Long) {
        /** Mirrors [FylzFilesDocumentsProvider]'s own private `documentIdFor` path math -- [file]
         *  is always inside [rootDirectory] here, since every candidate comes from the walk. */
        fun relativePath(): String {
            val rootPath = rootDirectory.absolutePath.trimEnd(File.separatorChar)
            val path = file.absolutePath
            return if (path.startsWith("$rootPath${File.separatorChar}")) {
                path.substring(rootPath.length + 1)
            } else {
                path.trimStart(File.separatorChar)
            }
        }
    }
}
