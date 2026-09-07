package io.github.mbaliga.fylz.library

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import kotlin.coroutines.coroutineContext

data class LocalIndexSettings(
    val enabled: Boolean = false,
    val paused: Boolean = false,
    val rootTreeUris: Set<String> = emptySet(),
    val maxEntries: Int = 500_000,
    val maxDepth: Int = 128,
) {
    init {
        require(maxEntries in 1..2_000_000)
        require(maxDepth in 1..256)
        require(rootTreeUris.size <= 256)
    }
}

data class LocalIndexStatus(
    val recordCount: Int,
    val rootCount: Int,
    val lastCompletedAtMillis: Long?,
    val lastError: String?,
    val truncated: Boolean,
    val running: Boolean,
)

data class LocalIndexScanResult(
    val records: Int,
    val rootsScanned: Int,
    val inaccessibleRoots: Int,
    val truncated: Boolean,
)

class LocalIndexStore(context: Context) {
    private val root = File(context.filesDir, "local-index")
    private val recordsFile = File(root, "records.json")
    private val statusFile = File(root, "status.json")
    private val preferences = context.getSharedPreferences("fylz-local-index", Context.MODE_PRIVATE)

    fun settings(): LocalIndexSettings = synchronized(LOCK) {
        LocalIndexSettings(
            enabled = preferences.getBoolean("enabled", false),
            paused = preferences.getBoolean("paused", false),
            rootTreeUris = preferences.getStringSet("roots", emptySet()).orEmpty().toSet(),
            maxEntries = preferences.getInt("maxEntries", 500_000).coerceIn(1, 2_000_000),
            maxDepth = preferences.getInt("maxDepth", 128).coerceIn(1, 256),
        )
    }

    fun updateSettings(value: LocalIndexSettings) = synchronized(LOCK) {
        preferences.edit()
            .putBoolean("enabled", value.enabled)
            .putBoolean("paused", value.paused)
            .putStringSet("roots", value.rootTreeUris)
            .putInt("maxEntries", value.maxEntries)
            .putInt("maxDepth", value.maxDepth)
            .commit()
    }

    fun records(): List<IndexedFileRecord> = synchronized(LOCK) {
        readRecords()
    }

    fun query(collection: SmartCollection): List<IndexedFileRecord> = synchronized(LOCK) {
        OrganizationEngine.evaluate(readRecords(), collection)
    }

    fun replace(records: List<IndexedFileRecord>, result: LocalIndexScanResult) = synchronized(LOCK) {
        require(records.size <= settings().maxEntries)
        atomicWrite(recordsFile, encodeRecords(records))
        writeStatus(
            LocalIndexStatus(
                recordCount = records.size,
                rootCount = result.rootsScanned,
                lastCompletedAtMillis = System.currentTimeMillis(),
                lastError = null,
                truncated = result.truncated,
                running = false,
            ),
        )
    }

    fun markRunning() = synchronized(LOCK) {
        val current = status()
        writeStatus(current.copy(running = true, lastError = null))
    }

    fun markError(message: String) = synchronized(LOCK) {
        val current = status()
        writeStatus(current.copy(running = false, lastError = message.take(1_000)))
    }

    fun status(): LocalIndexStatus = synchronized(LOCK) {
        runCatching {
            if (!statusFile.isFile) return@synchronized LocalIndexStatus(0, 0, null, null, false, false)
            val value = JSONObject(statusFile.readText())
            LocalIndexStatus(
                recordCount = value.optInt("recordCount", 0),
                rootCount = value.optInt("rootCount", 0),
                lastCompletedAtMillis = value.optLongOrNull("lastCompletedAtMillis"),
                lastError = value.optStringOrNull("lastError"),
                truncated = value.optBoolean("truncated", false),
                running = value.optBoolean("running", false),
            )
        }.getOrElse { LocalIndexStatus(0, 0, null, it.message, false, false) }
    }

    fun clear(): Int = synchronized(LOCK) {
        val count = readRecords().size
        recordsFile.delete()
        writeStatus(LocalIndexStatus(0, settings().rootTreeUris.size, null, null, false, false))
        count
    }

    private fun readRecords(): List<IndexedFileRecord> = runCatching {
        if (!recordsFile.isFile) return emptyList()
        require(recordsFile.length() <= MAX_INDEX_BYTES)
        val array = JSONArray(recordsFile.readText())
        require(array.length() <= 2_000_000)
        List(array.length()) { index ->
            val value = array.getJSONObject(index)
            IndexedFileRecord(
                uri = value.getString("uri"),
                displayName = value.getString("displayName"),
                mimeType = value.getString("mimeType"),
                sizeBytes = value.optLongOrNull("sizeBytes"),
                modifiedAtMillis = value.optLongOrNull("modifiedAtMillis"),
                extension = value.optString("extension", ""),
                parentUri = value.optStringOrNull("parentUri"),
                tags = value.optJSONArray("tags")?.toStringSet().orEmpty(),
                sha256 = value.optStringOrNull("sha256"),
                indexedAtMillis = value.optLong("indexedAtMillis", 0L),
            )
        }
    }.getOrElse { emptyList() }

    private fun encodeRecords(records: List<IndexedFileRecord>): String = JSONArray().apply {
        records.sortedBy(IndexedFileRecord::uri).forEach { record ->
            put(JSONObject().apply {
                put("uri", record.uri)
                put("displayName", record.displayName)
                put("mimeType", record.mimeType)
                put("sizeBytes", record.sizeBytes ?: JSONObject.NULL)
                put("modifiedAtMillis", record.modifiedAtMillis ?: JSONObject.NULL)
                put("extension", record.extension)
                put("parentUri", record.parentUri ?: JSONObject.NULL)
                put("tags", JSONArray(record.tags.sorted()))
                put("sha256", record.sha256 ?: JSONObject.NULL)
                put("indexedAtMillis", record.indexedAtMillis)
            })
        }
    }.toString()

    private fun writeStatus(value: LocalIndexStatus) {
        atomicWrite(statusFile, JSONObject().apply {
            put("recordCount", value.recordCount)
            put("rootCount", value.rootCount)
            put("lastCompletedAtMillis", value.lastCompletedAtMillis ?: JSONObject.NULL)
            put("lastError", value.lastError ?: JSONObject.NULL)
            put("truncated", value.truncated)
            put("running", value.running)
        }.toString())
    }

    private fun atomicWrite(file: File, content: String) {
        root.mkdirs()
        val temp = File(root, ".${file.name}.tmp")
        val backup = File(root, ".${file.name}.bak")
        temp.writeText(content)
        backup.delete()
        if (file.exists() && !file.renameTo(backup)) error("Unable to preserve the previous local index.")
        if (!temp.renameTo(file)) {
            backup.renameTo(file)
            error("Unable to commit the local index.")
        }
        backup.delete()
    }

    private fun JSONArray.toStringSet(): Set<String> = buildSet {
        repeat(length()) { index -> add(getString(index)) }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (!has(key) || isNull(key)) null else getLong(key)

    private companion object {
        val LOCK = Any()
        const val MAX_INDEX_BYTES = 512L * 1024L * 1024L
    }
}

class LocalIndexScanner(private val context: Context) {
    suspend fun scan(
        settings: LocalIndexSettings,
        tagsByUri: Map<String, Set<String>> = emptyMap(),
        onProgress: (Int) -> Unit = {},
    ): Pair<List<IndexedFileRecord>, LocalIndexScanResult> = withContext(Dispatchers.IO) {
        require(settings.enabled && !settings.paused) { "Local indexing is disabled or paused." }
        val records = ArrayList<IndexedFileRecord>(minOf(settings.maxEntries, 16_384))
        var inaccessible = 0
        var rootsScanned = 0
        var truncated = false
        val seen = hashSetOf<String>()
        settings.rootTreeUris.sorted().forEach { rawUri ->
            coroutineContext.ensureActive()
            val root = DocumentFile.fromTreeUri(context, Uri.parse(rawUri))
            if (root == null || !root.isDirectory || !root.canRead()) {
                inaccessible += 1
                return@forEach
            }
            rootsScanned += 1
            val queue = ArrayDeque<Node>()
            queue.add(Node(root, parentUri = null, depth = 0))
            while (queue.isNotEmpty()) {
                coroutineContext.ensureActive()
                if (records.size >= settings.maxEntries) {
                    truncated = true
                    break
                }
                val node = queue.removeFirst()
                if (!seen.add(node.file.uri.toString())) continue
                if (node.depth > settings.maxDepth) {
                    truncated = true
                    continue
                }
                if (node.file.isDirectory) {
                    runCatching { node.file.listFiles() }.getOrElse { emptyArray() }.forEach { child ->
                        queue.add(Node(child, node.file.uri.toString(), node.depth + 1))
                    }
                } else if (node.file.isFile) {
                    val name = node.file.name ?: "Untitled"
                    val uri = node.file.uri.toString()
                    records += IndexedFileRecord(
                        uri = uri,
                        displayName = name,
                        mimeType = node.file.type ?: "application/octet-stream",
                        sizeBytes = node.file.length().takeIf { it >= 0L },
                        modifiedAtMillis = node.file.lastModified().takeIf { it > 0L },
                        extension = name.substringAfterLast('.', "").lowercase(),
                        parentUri = node.parentUri,
                        tags = tagsByUri[uri].orEmpty(),
                    )
                    if (records.size % 250 == 0) onProgress(records.size)
                }
            }
        }
        records to LocalIndexScanResult(records.size, rootsScanned, inaccessible, truncated)
    }

    private data class Node(val file: DocumentFile, val parentUri: String?, val depth: Int)
}

/**
 * Not wired to any UI -- [io.github.mbaliga.fylz.index.LocalIndexScheduler] is the scheduler
 * `IndexManagerActivity`/`PostV1ToolsActivity` actually construct, and its
 * [io.github.mbaliga.fylz.index.LocalIndexStore] is the index `FylzSearch` reads. This class,
 * [LocalIndexWorker] and [LocalIndexStore] above are an earlier, unreferenced parallel
 * implementation kept only because nothing calls `rebuild()`/`cancel()` here to notice it's
 * unused; [WORK_NAME] used to collide with the wired scheduler's identical literal, so a call to
 * either scheduler's `enqueueUniqueWork` would silently replace or cancel the other's job under
 * WorkManager's own unique-work identity. Renamed here rather than removed -- the smaller, purely
 * additive fix for now, and it means a future rewire to this implementation is not a de-collision
 * problem too.
 */
class LocalIndexScheduler(private val context: Context) {
    fun rebuild() {
        val request = OneTimeWorkRequestBuilder<LocalIndexWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel() = WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)

    companion object { const val WORK_NAME = "fylz-local-index-rebuild-unwired" }
}

class LocalIndexWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val store = LocalIndexStore(applicationContext)
        val settings = store.settings()
        if (!settings.enabled || settings.paused || settings.rootTreeUris.isEmpty()) return Result.success()
        store.markRunning()
        return try {
            val (records, result) = LocalIndexScanner(applicationContext).scan(settings) { count ->
                setProgressAsync(Data.Builder().putInt("indexed", count).build())
            }
            store.replace(records, result)
            Result.success(Data.Builder().putInt("indexed", records.size).build())
        } catch (cancelled: CancellationException) {
            store.markError("Index rebuild cancelled.")
            throw cancelled
        } catch (failure: Throwable) {
            store.markError(failure.message ?: "Local index rebuild failed.")
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}
