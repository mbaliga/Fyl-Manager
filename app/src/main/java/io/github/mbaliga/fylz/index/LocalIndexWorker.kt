package io.github.mbaliga.fylz.index

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.mbaliga.fylz.preview.FileFormatRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class LocalIndexController(private val context: Context) {
    private val store = LocalIndexStore(context)
    private val manager = WorkManager.getInstance(context)

    fun rebuild() {
        store.putState(store.state().copy(lastError = null))
        manager.enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<LocalIndexWorker>().build(),
        )
    }

    fun pause() {
        store.setPaused(true)
        manager.cancelUniqueWork(WORK_NAME)
    }

    fun resumeAndRebuild() {
        store.setPaused(false)
        rebuild()
    }

    fun deleteIndex() {
        manager.cancelUniqueWork(WORK_NAME)
        store.clearFiles()
        store.putState(IndexState(paused = store.state().paused))
    }

    companion object { const val WORK_NAME = "fylz-local-index-rebuild" }
}

class LocalIndexWorker(appContext: Context, parameters: WorkerParameters) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = LocalIndexStore(applicationContext)
        val initial = store.state()
        if (initial.paused) return@withContext Result.success()
        store.putState(initial.copy(lastStartedAtMillis = System.currentTimeMillis(), lastError = null, truncated = false))
        var total = 0
        var truncated = false
        return@withContext try {
            store.scopes().filter(IndexScope::enabled).forEach { scope ->
                coroutineContext.ensureActive()
                val root = DocumentFile.fromTreeUri(applicationContext, Uri.parse(scope.rootUri))
                    ?: error("Index root ${scope.displayName} is unavailable.")
                val entries = mutableListOf<IndexedFile>()
                val queue = ArrayDeque<Pair<DocumentFile, Int>>()
                queue.add(root to 0)
                while (queue.isNotEmpty()) {
                    coroutineContext.ensureActive()
                    if (store.state().paused) return@withContext Result.success()
                    val (current, depth) = queue.removeFirst()
                    require(depth <= MAX_DEPTH) { "Index root nesting exceeds the safety limit." }
                    current.listFiles().forEach { child ->
                        if (total >= MAX_FILES) { truncated = true; return@forEach }
                        val name = child.name ?: "Untitled"
                        val directory = child.isDirectory
                        entries += IndexedFile(
                            uri = child.uri.toString(),
                            rootUri = scope.rootUri,
                            name = name,
                            mimeType = child.type ?: if (directory) "vnd.android.document/directory" else "application/octet-stream",
                            extension = if (directory) "" else FileFormatRegistry.compoundExtension(name),
                            sizeBytes = child.length().takeIf { it >= 0L },
                            modifiedAtMillis = child.lastModified().takeIf { it > 0L },
                            directory = directory,
                        )
                        total += 1
                        if (directory && total < MAX_FILES) queue.add(child to depth + 1)
                    }
                    if (truncated) break
                }
                store.replaceFiles(scope.rootUri, entries)
                if (truncated) return@forEach
            }
            store.putState(
                store.state().copy(
                    lastCompletedAtMillis = System.currentTimeMillis(),
                    indexedFiles = total,
                    truncated = truncated,
                    lastError = null,
                ),
            )
            Result.success()
        } catch (failure: Throwable) {
            store.putState(store.state().copy(lastError = failure.message ?: "Index rebuild failed.", indexedFiles = total, truncated = truncated))
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val MAX_FILES = 500_000
        const val MAX_DEPTH = 128
    }
}
