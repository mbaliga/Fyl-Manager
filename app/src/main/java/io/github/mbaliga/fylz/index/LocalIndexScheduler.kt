package io.github.mbaliga.fylz.index

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.mbaliga.fylz.library.LibraryStore
import io.github.mbaliga.fylz.operations.DestinationKind
import io.github.mbaliga.fylz.operations.classifyDestination
import io.github.mbaliga.fylz.preview.FileFormatRegistry
import io.github.mbaliga.fylz.util.FileType
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
                val currentGeneration = currentGenerationFor(scope)
                if (!shouldRescan(scope.lastMediaStoreGeneration, currentGeneration)) {
                    // P1.12: this scope lives on this device's own internal storage and
                    // MediaStore reports no change since the last scan -- the existing rows are
                    // still correct, so skip the whole SAF walk. A removable/USB/third-party
                    // scope (currentGeneration == null) always falls through to a real rescan,
                    // unchanged from before P1.12.
                    store.putScope(scope.copy(lastScannedAtMillis = System.currentTimeMillis()))
                    total += store.files().count { it.rootUri == scope.rootUri }
                    return@forEach
                }
                val result = scanScope(scope, remaining)
                store.replaceFiles(scope.rootUri, result.files)
                store.putScope(
                    scope.copy(
                        lastMediaStoreGeneration = currentGeneration,
                        lastScannedAtMillis = System.currentTimeMillis(),
                    ),
                )
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

    /** Null for a scope not on this device's own internal storage (always rescan) -- see
     *  [IndexScope]'s own doc. */
    private fun currentGenerationFor(scope: IndexScope): Long? {
        val rootUri = runCatching { Uri.parse(scope.rootUri) }.getOrNull() ?: return null
        if (classifyDestination(applicationContext, rootUri) != DestinationKind.INTERNAL) return null
        return runCatching { MediaStore.getGeneration(applicationContext, MediaStore.VOLUME_EXTERNAL_PRIMARY) }.getOrNull()
    }

    private suspend fun scanScope(scope: IndexScope, limit: Int): ScanResult {
        val rootUri = Uri.parse(scope.rootUri)
        val root = DocumentFile.fromTreeUri(applicationContext, rootUri)
            ?: error("Index root ${scope.displayName} is unavailable.")
        require(root.isDirectory && root.canRead()) { "Index root ${scope.displayName} is not readable." }
        val library = LibraryStore(applicationContext)
        val queue = ArrayDeque<Visit>()
        queue.add(Visit(root, 0, ""))
        val files = ArrayList<IndexedFile>(minOf(limit, 16_384))
        var visited = 0
        var truncated = false
        while (queue.isNotEmpty()) {
            coroutineContext.ensureActive()
            if (files.size >= limit || visited >= MAX_VISITED_ENTRIES) {
                truncated = true
                break
            }
            val (directory, depth, parentPath) = queue.removeFirst()
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
                val childPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
                files += IndexedFile(
                    uri = child.uri.toString(),
                    rootUri = scope.rootUri,
                    parentUri = directory.uri.toString(),
                    path = childPath,
                    name = name,
                    mimeType = mime,
                    extension = FileFormatRegistry.compoundExtension(name),
                    sizeBytes = child.length().takeIf { it >= 0L },
                    modifiedAtMillis = child.lastModified().takeIf { it > 0L },
                    directory = child.isDirectory,
                    tags = library.tags(child.uri),
                    textSnippet = if (child.isDirectory) null else readTextSnippet(child.uri, name, mime),
                )
                if (child.isDirectory) queue.add(Visit(child, depth + 1, childPath))
            }
        }
        return ScanResult(files, truncated)
    }

    /** A capped read of a text-previewable file's own content, feeding the FTS `text` column so
     *  content search can use the index instead of a live per-file read (P1.12). Capped well
     *  below a whole file (unlike `RecursiveSearchEngine`'s own 2 MiB live-search cap): this
     *  value is stored once per file across the whole index rather than read transiently during
     *  one search, so a much smaller cap keeps the database from ballooning on a text-heavy
     *  library. Null for anything not classified as text, or on any read failure. */
    private fun readTextSnippet(uri: Uri, name: String, mimeType: String): String? {
        if (!FileType.isTextPreviewable(FileType.classify(name, mimeType))) return null
        return runCatching {
            applicationContext.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(MAX_TEXT_SNIPPET_BYTES)
                var total = 0
                while (total < buffer.size) {
                    val read = stream.read(buffer, total, buffer.size - total)
                    if (read < 0) break
                    total += read
                }
                String(buffer, 0, total, Charsets.UTF_8)
            }
        }.getOrNull()
    }

    private data class Visit(val directory: DocumentFile, val depth: Int, val path: String)
    private data class ScanResult(val files: List<IndexedFile>, val truncated: Boolean)

    companion object {
        private const val MAX_DEPTH = 64
        private const val MAX_VISITED_ENTRIES = 500_000
        private const val MAX_RECORDS = 500_000
        private const val MAX_TEXT_SNIPPET_BYTES = 64 * 1_024
    }
}

/**
 * Pure decision behind P1.12's "incremental updates" requirement: [currentGeneration] is
 * [MediaStore.getGeneration] for a scope on this device's own internal storage, or null for a
 * scope [io.github.mbaliga.fylz.operations.classifyDestination] doesn't call
 * [io.github.mbaliga.fylz.operations.DestinationKind.INTERNAL] (a removable/USB drive or a
 * third-party SAF provider) -- those always rescan on connect/on-demand, unchanged from before
 * this task. For an internal-storage scope, a rescan is only necessary when the volume's
 * generation counter has actually moved since [previousGeneration].
 */
internal fun shouldRescan(previousGeneration: Long?, currentGeneration: Long?): Boolean =
    currentGeneration == null || previousGeneration != currentGeneration
