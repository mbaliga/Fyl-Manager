package io.github.mbaliga.fylz.index

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.mbaliga.fylz.library.LibraryStore
import io.github.mbaliga.fylz.core.format.FileFormatRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import kotlin.coroutines.coroutineContext

class LocalIndexScheduler(private val context: Context) {
    fun rebuild() {
        val request = OneTimeWorkRequestBuilder<LocalIndexWorker>()
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel() = WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)

    companion object {
        const val WORK_NAME = "fylz-local-index-rebuild"
        const val TAG = "fylz-local-index"
    }
}

class LocalIndexWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = LocalIndexStore(applicationContext)
        val state = store.state()
        if (state.paused) return@withContext Result.success()
        store.putState(state.copy(lastStartedAtMillis = System.currentTimeMillis(), lastError = null, truncated = false))
        var total = 0
        var truncated = false
        try {
            store.scopes().filter(IndexScope::enabled).forEach { scope ->
                coroutineContext.ensureActive()
                val remaining = MAX_RECORDS - total
                if (remaining <= 0) {
                    truncated = true
                    return@forEach
                }
                val result = scanScope(scope, remaining)
                store.replaceFiles(scope.rootUri, result.files)
                total += result.files.size
                truncated = truncated || result.truncated
            }
            store.putState(
                store.state().copy(
                    lastCompletedAtMillis = System.currentTimeMillis(),
                    lastError = null,
                    indexedFiles = store.files().size,
                    truncated = truncated,
                ),
            )
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            store.putState(store.state().copy(lastError = failure.message ?: "Index rebuild failed."))
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    private suspend fun scanScope(scope: IndexScope, limit: Int): ScanResult {
        val rootUri = Uri.parse(scope.rootUri)
        val root = DocumentFile.fromTreeUri(applicationContext, rootUri)
            ?: error("Index root ${scope.displayName} is unavailable.")
        require(root.isDirectory && root.canRead()) { "Index root ${scope.displayName} is not readable." }
        val library = LibraryStore(applicationContext)
        val queue = ArrayDeque<Pair<DocumentFile, Int>>()
        queue.add(root to 0)
        val files = ArrayList<IndexedFile>(minOf(limit, 16_384))
        var visited = 0
        var truncated = false
        while (queue.isNotEmpty()) {
            coroutineContext.ensureActive()
            if (files.size >= limit || visited >= MAX_VISITED_ENTRIES) {
                truncated = true
                break
            }
            val (directory, depth) = queue.removeFirst()
            if (depth > MAX_DEPTH) {
                truncated = true
                continue
            }
            directory.listFiles().forEach { child ->
                if (files.size >= limit || visited >= MAX_VISITED_ENTRIES) {
                    truncated = true
                    return@forEach
                }
                visited += 1
                val name = child.name ?: "Untitled"
                val mime = child.type ?: if (child.isDirectory) "vnd.android.document/directory" else "application/octet-stream"
                files += IndexedFile(
                    uri = child.uri.toString(),
                    rootUri = scope.rootUri,
                    name = name,
                    mimeType = mime,
                    extension = FileFormatRegistry.compoundExtension(name),
                    sizeBytes = child.length().takeIf { it >= 0L },
                    modifiedAtMillis = child.lastModified().takeIf { it > 0L },
                    directory = child.isDirectory,
                    tags = library.tags(child.uri),
                )
                if (child.isDirectory) queue.add(child to depth + 1)
            }
        }
        return ScanResult(files, truncated)
    }

    private data class ScanResult(val files: List<IndexedFile>, val truncated: Boolean)

    companion object {
        private const val MAX_DEPTH = 64
        private const val MAX_VISITED_ENTRIES = 500_000
        private const val MAX_RECORDS = 500_000
    }
}
