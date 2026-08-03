package io.github.mbaliga.fylz.index

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.github.mbaliga.fylz.preview.FileFormatRegistry
import io.github.mbaliga.fylz.preview.PreviewFamily
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.ArrayDeque
import java.util.UUID
import kotlin.coroutines.coroutineContext

data class IndexScope(
    val treeUri: String,
    val displayName: String,
    val includeTextContent: Boolean = false,
    val enabled: Boolean = true,
)

data class IndexedFile(
    val uri: String,
    val scopeTreeUri: String,
    val relativePath: String,
    val displayName: String,
    val mimeType: String,
    val extension: String,
    val sizeBytes: Long?,
    val modifiedAtMillis: Long?,
    val directory: Boolean,
    val textSnippet: String? = null,
    val indexedAtMillis: Long = System.currentTimeMillis(),
)

data class IndexStatus(
    val scopes: Int,
    val indexedItems: Int,
    val indexedTextItems: Int,
    val lastCompletedAtMillis: Long?,
    val paused: Boolean,
    val truncated: Boolean,
    val warnings: List<String>,
)

data class IndexSearchQuery(
    val text: String,
    val extensions: Set<String> = emptySet(),
    val mimePrefixes: Set<String> = emptySet(),
    val minimumBytes: Long? = null,
    val maximumBytes: Long? = null,
    val modifiedAfterMillis: Long? = null,
    val includeDirectories: Boolean = false,
    val limit: Int = 500,
)

/**
 * App-private, opt-in metadata index. It only traverses persisted tree URIs explicitly supplied by
 * the user and never requests broad storage access. Text indexing is separately opt-in per scope.
 */
class LocalIndexService(private val context: Context) {
    private val root = File(context.filesDir, "local-index")
    private val recordsFile = File(root, "records.json")
    private val settingsFile = File(root, "settings.json")

    fun scopes(): List<IndexScope> = synchronized(LOCK) { readSettings().scopes }

    fun putScope(scope: IndexScope) = synchronized(LOCK) {
        require(Uri.parse(scope.treeUri).scheme == "content")
        require(scope.displayName.isNotBlank())
        val settings = readSettings()
        val updated = settings.scopes.filterNot { it.treeUri == scope.treeUri } + scope.copy(displayName = scope.displayName.take(255))
        require(updated.size <= MAX_SCOPES) { "Too many index scopes." }
        writeSettings(settings.copy(scopes = updated.sortedBy { it.displayName.lowercase() }))
    }

    fun removeScope(treeUri: String, removeRecords: Boolean = true) = synchronized(LOCK) {
        val settings = readSettings()
        writeSettings(settings.copy(scopes = settings.scopes.filterNot { it.treeUri == treeUri }))
        if (removeRecords) writeRecords(readRecords().filterNot { it.scopeTreeUri == treeUri })
    }

    fun setPaused(paused: Boolean) = synchronized(LOCK) {
        writeSettings(readSettings().copy(paused = paused))
    }

    fun clear() = synchronized(LOCK) {
        writeRecords(emptyList())
        writeSettings(readSettings().copy(lastCompletedAtMillis = null, truncated = false, warnings = emptyList()))
    }

    fun status(): IndexStatus = synchronized(LOCK) {
        val settings = readSettings()
        val records = readRecords()
        IndexStatus(
            scopes = settings.scopes.count(IndexScope::enabled),
            indexedItems = records.size,
            indexedTextItems = records.count { !it.textSnippet.isNullOrBlank() },
            lastCompletedAtMillis = settings.lastCompletedAtMillis,
            paused = settings.paused,
            truncated = settings.truncated,
            warnings = settings.warnings,
        )
    }

    suspend fun rebuild(onProgress: (Int, String) -> Unit = { _, _ -> }): IndexStatus = withContext(Dispatchers.IO) {
        val settings = synchronized(LOCK) { readSettings() }
        if (settings.paused) return@withContext status()
        val output = ArrayList<IndexedFile>()
        val warnings = mutableListOf<String>()
        var truncated = false
        try {
            settings.scopes.filter(IndexScope::enabled).forEach { scope ->
                coroutineContext.ensureActive()
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(scope.treeUri))
                if (tree == null || !tree.isDirectory || !tree.canRead()) {
                    warnings += "${scope.displayName}: folder is unavailable or unreadable."
                    return@forEach
                }
                val queue = ArrayDeque<QueueItem>()
                queue += QueueItem(tree, "", 0)
                while (queue.isNotEmpty()) {
                    coroutineContext.ensureActive()
                    if (synchronized(LOCK) { readSettings().paused }) {
                        warnings += "Indexing paused before completion."
                        break
                    }
                    val current = queue.removeFirst()
                    if (current.depth > MAX_DEPTH) {
                        truncated = true
                        continue
                    }
                    val children = runCatching { current.file.listFiles().toList() }.getOrElse {
                        warnings += "${scope.displayName}: a folder could not be listed."
                        emptyList()
                    }
                    children.forEach { child ->
                        coroutineContext.ensureActive()
                        if (output.size >= MAX_RECORDS) {
                            truncated = true
                            return@forEach
                        }
                        val name = child.name?.take(512) ?: "Untitled"
                        val relativePath = if (current.relativePath.isEmpty()) name else "${current.relativePath}/$name"
                        val mime = child.type ?: if (child.isDirectory) "vnd.android.document/directory" else "application/octet-stream"
                        val descriptor = FileFormatRegistry.describe(name, mime)
                        val snippet = if (
                            scope.includeTextContent && child.isFile &&
                            descriptor.family in TEXT_FAMILIES && child.length() in 0..MAX_TEXT_SOURCE_BYTES
                        ) readTextSnippet(child.uri) else null
                        output += IndexedFile(
                            uri = child.uri.toString(),
                            scopeTreeUri = scope.treeUri,
                            relativePath = relativePath,
                            displayName = name,
                            mimeType = mime,
                            extension = descriptor.extension,
                            sizeBytes = child.length().takeIf { it >= 0L },
                            modifiedAtMillis = child.lastModified().takeIf { it > 0L },
                            directory = child.isDirectory,
                            textSnippet = snippet,
                        )
                        onProgress(output.size, relativePath)
                        if (child.isDirectory) queue += QueueItem(child, relativePath, current.depth + 1)
                    }
                    if (truncated) break
                }
            }
            synchronized(LOCK) {
                writeRecords(output.sortedBy { it.relativePath.lowercase() })
                writeSettings(
                    readSettings().copy(
                        lastCompletedAtMillis = System.currentTimeMillis(),
                        truncated = truncated,
                        warnings = warnings.take(MAX_WARNINGS),
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
        status()
    }

    fun search(query: IndexSearchQuery): List<IndexedFile> = synchronized(LOCK) {
        require(query.limit in 1..5_000)
        val needle = query.text.trim().lowercase()
        val extensions = query.extensions.map { it.lowercase().removePrefix(".") }.toSet()
        readRecords().asSequence()
            .filter { query.includeDirectories || !it.directory }
            .filter { needle.isEmpty() || it.displayName.lowercase().contains(needle) || it.relativePath.lowercase().contains(needle) || it.textSnippet?.lowercase()?.contains(needle) == true }
            .filter { extensions.isEmpty() || it.extension in extensions }
            .filter { query.mimePrefixes.isEmpty() || query.mimePrefixes.any(it.mimeType::startsWith) }
            .filter { query.minimumBytes == null || (it.sizeBytes != null && it.sizeBytes >= query.minimumBytes) }
            .filter { query.maximumBytes == null || (it.sizeBytes != null && it.sizeBytes <= query.maximumBytes) }
            .filter { query.modifiedAfterMillis == null || (it.modifiedAtMillis != null && it.modifiedAtMillis >= query.modifiedAfterMillis) }
            .sortedWith(compareByDescending<IndexedFile> { relevance(it, needle) }.thenBy { it.displayName.lowercase() })
            .take(query.limit)
            .toList()
    }

    private fun relevance(item: IndexedFile, query: String): Int = when {
        query.isEmpty() -> 0
        item.displayName.equals(query, true) -> 5
        item.displayName.startsWith(query, true) -> 4
        item.displayName.contains(query, true) -> 3
        item.relativePath.contains(query, true) -> 2
        item.textSnippet?.contains(query, true) == true -> 1
        else -> 0
    }

    private fun readTextSnippet(uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            InputStreamReader(input, Charsets.UTF_8).use { reader ->
                val output = StringBuilder()
                val buffer = CharArray(4_096)
                while (output.length < MAX_TEXT_CHARS) {
                    val count = reader.read(buffer, 0, minOf(buffer.size, MAX_TEXT_CHARS - output.length))
                    if (count < 0) break
                    output.append(buffer, 0, count)
                }
                output.toString().replace('\u0000', ' ').takeIf(String::isNotBlank)
            }
        }
    }.getOrNull()

    private fun readRecords(): List<IndexedFile> = runCatching {
        if (!recordsFile.isFile) return emptyList()
        val array = JSONArray(recordsFile.readText())
        List(minOf(array.length(), MAX_RECORDS)) { index ->
            val value = array.getJSONObject(index)
            IndexedFile(
                uri = value.getString("uri"),
                scopeTreeUri = value.getString("scopeTreeUri"),
                relativePath = value.getString("relativePath"),
                displayName = value.getString("displayName"),
                mimeType = value.getString("mimeType"),
                extension = value.optString("extension"),
                sizeBytes = value.optLongOrNull("sizeBytes"),
                modifiedAtMillis = value.optLongOrNull("modifiedAtMillis"),
                directory = value.optBoolean("directory"),
                textSnippet = value.optStringOrNull("textSnippet"),
                indexedAtMillis = value.optLong("indexedAtMillis", 0L),
            )
        }
    }.getOrDefault(emptyList())

    private fun writeRecords(values: List<IndexedFile>) {
        val array = JSONArray()
        values.take(MAX_RECORDS).forEach { value ->
            array.put(JSONObject().apply {
                put("uri", value.uri)
                put("scopeTreeUri", value.scopeTreeUri)
                put("relativePath", value.relativePath)
                put("displayName", value.displayName)
                put("mimeType", value.mimeType)
                put("extension", value.extension)
                put("sizeBytes", value.sizeBytes ?: JSONObject.NULL)
                put("modifiedAtMillis", value.modifiedAtMillis ?: JSONObject.NULL)
                put("directory", value.directory)
                put("textSnippet", value.textSnippet ?: JSONObject.NULL)
                put("indexedAtMillis", value.indexedAtMillis)
            })
        }
        atomicWrite(recordsFile, array.toString())
    }

    private fun readSettings(): Settings = runCatching {
        if (!settingsFile.isFile) return Settings()
        val root = JSONObject(settingsFile.readText())
        val scopes = root.optJSONArray("scopes") ?: JSONArray()
        Settings(
            scopes = List(minOf(scopes.length(), MAX_SCOPES)) { index ->
                val item = scopes.getJSONObject(index)
                IndexScope(
                    treeUri = item.getString("treeUri"),
                    displayName = item.getString("displayName"),
                    includeTextContent = item.optBoolean("includeTextContent"),
                    enabled = item.optBoolean("enabled", true),
                )
            },
            paused = root.optBoolean("paused"),
            lastCompletedAtMillis = root.optLongOrNull("lastCompletedAtMillis"),
            truncated = root.optBoolean("truncated"),
            warnings = root.optJSONArray("warnings").stringList(MAX_WARNINGS),
        )
    }.getOrDefault(Settings())

    private fun writeSettings(value: Settings) {
        val root = JSONObject().apply {
            put("scopes", JSONArray().apply {
                value.scopes.forEach { scope ->
                    put(JSONObject().apply {
                        put("treeUri", scope.treeUri)
                        put("displayName", scope.displayName)
                        put("includeTextContent", scope.includeTextContent)
                        put("enabled", scope.enabled)
                    })
                }
            })
            put("paused", value.paused)
            put("lastCompletedAtMillis", value.lastCompletedAtMillis ?: JSONObject.NULL)
            put("truncated", value.truncated)
            put("warnings", JSONArray(value.warnings))
        }
        atomicWrite(settingsFile, root.toString())
    }

    private fun atomicWrite(target: File, value: String) {
        root.mkdirs()
        val temp = File(root, ".${target.name}.${UUID.randomUUID()}.tmp")
        FileOutputStream(temp).use { output ->
            output.write(value.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        val backup = File(root, ".${target.name}.bak")
        backup.delete()
        if (target.exists() && !target.renameTo(backup)) error("Unable to preserve the previous index state.")
        if (!temp.renameTo(target)) {
            backup.renameTo(target)
            error("Unable to commit index state.")
        }
        backup.delete()
    }

    private data class QueueItem(val file: DocumentFile, val relativePath: String, val depth: Int)
    private data class Settings(
        val scopes: List<IndexScope> = emptyList(),
        val paused: Boolean = false,
        val lastCompletedAtMillis: Long? = null,
        val truncated: Boolean = false,
        val warnings: List<String> = emptyList(),
    )

    private fun JSONObject.optLongOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else getLong(key)
    private fun JSONObject.optStringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
    private fun JSONArray?.stringList(max: Int): List<String> = if (this == null) emptyList() else List(minOf(length(), max)) { getString(it) }

    private companion object {
        val LOCK = Any()
        val TEXT_FAMILIES = setOf(PreviewFamily.TEXT, PreviewFamily.MARKDOWN)
        const val MAX_SCOPES = 100
        const val MAX_DEPTH = 128
        const val MAX_RECORDS = 1_000_000
        const val MAX_TEXT_SOURCE_BYTES = 4L * 1024L * 1024L
        const val MAX_TEXT_CHARS = 32 * 1024
        const val MAX_WARNINGS = 100
    }
}
