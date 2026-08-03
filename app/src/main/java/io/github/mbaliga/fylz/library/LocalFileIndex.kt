package io.github.mbaliga.fylz.library

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import kotlin.coroutines.coroutineContext

data class LocalIndexState(
    val roots: Set<String> = emptySet(),
    val paused: Boolean = false,
    val lastCompletedAtMillis: Long? = null,
    val indexedItems: Int = 0,
    val truncated: Boolean = false,
)

data class LocalIndexResult(
    val indexedItems: Int,
    val skippedItems: Int,
    val truncated: Boolean,
)

class LocalFileIndex(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "local-index")
    private val indexFile = File(root, "files.json")
    private val stateFile = File(root, "state.json")
    private val library = LibraryStore(appContext)

    fun state(): LocalIndexState = synchronized(LOCK) { readState() }

    fun entries(): List<IndexedFileMetadata> = synchronized(LOCK) { readEntries() }

    fun setRoots(roots: Collection<Uri>) = synchronized(LOCK) {
        require(roots.size <= MAX_ROOTS)
        val normalized = roots.map(Uri::toString).filter(String::isNotBlank).toSet()
        writeState(readState().copy(roots = normalized))
    }

    fun setPaused(paused: Boolean) = synchronized(LOCK) {
        writeState(readState().copy(paused = paused))
    }

    fun deleteIndex(): Boolean = synchronized(LOCK) {
        val deleted = !indexFile.exists() || indexFile.delete()
        if (deleted) writeState(readState().copy(lastCompletedAtMillis = null, indexedItems = 0, truncated = false))
        deleted
    }

    suspend fun rebuild(): LocalIndexResult = withContext(Dispatchers.IO) {
        val state = state()
        if (state.paused) return@withContext LocalIndexResult(0, 0, false)
        val output = ArrayList<IndexedFileMetadata>()
        val seen = HashSet<String>()
        var skipped = 0
        var truncated = false
        try {
            val queue = ArrayDeque<Node>()
            state.roots.forEach { raw ->
                val uri = Uri.parse(raw)
                val file = DocumentFile.fromTreeUri(appContext, uri)
                    ?: DocumentFile.fromSingleUri(appContext, uri)
                if (file != null) queue += Node(file, 0)
            }
            while (queue.isNotEmpty()) {
                coroutineContext.ensureActive()
                if (output.size >= MAX_ITEMS) {
                    truncated = true
                    break
                }
                val (current, depth) = queue.removeFirst()
                val key = current.uri.toString()
                if (!seen.add(key)) continue
                if (current.isDirectory) {
                    if (depth >= MAX_DEPTH) {
                        skipped += 1
                        continue
                    }
                    runCatching { current.listFiles() }.getOrElse {
                        skipped += 1
                        emptyArray()
                    }.forEach { queue += Node(it, depth + 1) }
                    continue
                }
                if (!current.isFile) {
                    skipped += 1
                    continue
                }
                val name = current.name ?: "Untitled"
                output += IndexedFileMetadata(
                    uri = key,
                    name = name.take(500),
                    mimeType = current.type ?: "application/octet-stream",
                    sizeBytes = current.length().takeIf { it >= 0L },
                    modifiedMillis = current.lastModified().takeIf { it > 0L },
                    tags = library.tags(current.uri),
                )
            }
            synchronized(LOCK) {
                writeEntries(output)
                writeState(
                    state.copy(
                        lastCompletedAtMillis = System.currentTimeMillis(),
                        indexedItems = output.size,
                        truncated = truncated,
                    ),
                )
            }
            LocalIndexResult(output.size, skipped, truncated)
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    fun search(query: String, limit: Int = 500): List<IndexedFileMetadata> {
        require(limit in 1..5_000)
        val needle = query.trim().lowercase(Locale.ROOT)
        if (needle.isBlank()) return emptyList()
        return entries().asSequence()
            .filter { file ->
                needle in file.name.lowercase(Locale.ROOT) ||
                    needle in file.mimeType.lowercase(Locale.ROOT) ||
                    file.tags.any { needle in it.lowercase(Locale.ROOT) }
            }
            .take(limit)
            .toList()
    }

    fun smartCollection(rules: List<SmartRule>, requireAll: Boolean = true, limit: Int = 5_000): List<IndexedFileMetadata> {
        require(limit in 1..5_000)
        return SmartRuleEvaluator.filter(entries(), rules, requireAll).take(limit)
    }

    private fun readEntries(): List<IndexedFileMetadata> = runCatching {
        if (!indexFile.isFile) return emptyList()
        require(indexFile.length() <= MAX_INDEX_BYTES)
        val array = JSONArray(indexFile.readText())
        buildList {
            repeat(minOf(array.length(), MAX_ITEMS)) { index ->
                val item = array.getJSONObject(index)
                add(
                    IndexedFileMetadata(
                        uri = item.getString("uri"),
                        name = item.getString("name"),
                        mimeType = item.getString("mimeType"),
                        sizeBytes = item.optLongOrNull("sizeBytes"),
                        modifiedMillis = item.optLongOrNull("modifiedMillis"),
                        tags = item.optJSONArray("tags")?.toStringSet().orEmpty(),
                    ),
                )
            }
        }
    }.getOrElse { emptyList() }

    private fun writeEntries(values: List<IndexedFileMetadata>) {
        val array = JSONArray()
        values.take(MAX_ITEMS).forEach { value ->
            array.put(
                JSONObject()
                    .put("uri", value.uri)
                    .put("name", value.name)
                    .put("mimeType", value.mimeType)
                    .put("sizeBytes", value.sizeBytes ?: JSONObject.NULL)
                    .put("modifiedMillis", value.modifiedMillis ?: JSONObject.NULL)
                    .put("tags", JSONArray(value.tags.sorted())),
            )
        }
        atomicWrite(indexFile, array.toString())
    }

    private fun readState(): LocalIndexState = runCatching {
        if (!stateFile.isFile) return LocalIndexState()
        val value = JSONObject(stateFile.readText())
        LocalIndexState(
            roots = value.optJSONArray("roots")?.toStringSet().orEmpty(),
            paused = value.optBoolean("paused", false),
            lastCompletedAtMillis = value.optLongOrNull("lastCompletedAtMillis"),
            indexedItems = value.optInt("indexedItems", 0).coerceIn(0, MAX_ITEMS),
            truncated = value.optBoolean("truncated", false),
        )
    }.getOrDefault(LocalIndexState())

    private fun writeState(value: LocalIndexState) {
        atomicWrite(
            stateFile,
            JSONObject()
                .put("roots", JSONArray(value.roots.sorted()))
                .put("paused", value.paused)
                .put("lastCompletedAtMillis", value.lastCompletedAtMillis ?: JSONObject.NULL)
                .put("indexedItems", value.indexedItems)
                .put("truncated", value.truncated)
                .toString(),
        )
    }

    private fun atomicWrite(target: File, value: String) {
        root.mkdirs()
        val temp = File(root, ".${target.name}.tmp")
        temp.writeText(value)
        check(temp.length() <= MAX_INDEX_BYTES) { "Local index exceeds its storage safety limit." }
        val backup = File(root, ".${target.name}.bak")
        backup.delete()
        if (target.exists()) check(target.renameTo(backup)) { "Unable to preserve the previous index." }
        if (!temp.renameTo(target)) {
            backup.renameTo(target)
            error("Unable to commit the local index.")
        }
        backup.delete()
    }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (!has(key) || isNull(key)) null else getLong(key)

    private fun JSONArray.toStringSet(): Set<String> = buildSet {
        repeat(length()) { index -> add(getString(index)) }
    }

    private data class Node(val file: DocumentFile, val depth: Int)

    private companion object {
        val LOCK = Any()
        const val MAX_ROOTS = 64
        const val MAX_DEPTH = 128
        const val MAX_ITEMS = 250_000
        const val MAX_INDEX_BYTES = 96L * 1024L * 1024L
    }
}
