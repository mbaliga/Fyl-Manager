package io.github.mbaliga.fylz.library

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.github.mbaliga.fylz.preview.FileFormatRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.coroutines.coroutineContext

data class IndexedFile(
    val uri: String,
    val rootUri: String,
    val relativePath: String,
    val name: String,
    val mimeType: String,
    val extension: String,
    val sizeBytes: Long?,
    val modifiedAtMillis: Long?,
    val directory: Boolean,
    val searchableText: String = "",
    val indexedAtMillis: Long = System.currentTimeMillis(),
)

data class IndexStats(
    val roots: Int,
    val entries: Int,
    val files: Int,
    val directories: Int,
    val textSampledFiles: Int,
    val generatedAtMillis: Long,
)

enum class SmartField { NAME, PATH, EXTENSION, MIME, SIZE, MODIFIED, TEXT, TAG }
enum class SmartOperator { CONTAINS, EQUALS, STARTS_WITH, ENDS_WITH, GREATER_THAN, LESS_THAN, BEFORE, AFTER }

data class SmartPredicate(
    val field: SmartField,
    val operator: SmartOperator,
    val value: String,
    val negated: Boolean = false,
)

data class SmartCollection(
    val id: String,
    val name: String,
    val matchAll: Boolean = true,
    val predicates: List<SmartPredicate>,
)

class LocalIndexStore(private val context: Context) {
    private val root = File(context.filesDir, "local-index")
    private val recordsFile = File(root, "records.json")
    private val collectionsFile = File(root, "collections.json")

    suspend fun rebuild(
        roots: List<Uri>,
        sampleText: Boolean = true,
        maxEntries: Int = 500_000,
        maxDepth: Int = 128,
        maxTextBytesPerFile: Int = 32 * 1024,
    ): IndexStats = withContext(Dispatchers.IO) {
        require(roots.isNotEmpty())
        require(maxEntries in 1..2_000_000)
        require(maxDepth in 1..256)
        require(maxTextBytesPerFile in 0..256 * 1024)
        val records = ArrayList<IndexedFile>()
        var textFiles = 0
        roots.distinct().forEach { rootUri ->
            val tree = DocumentFile.fromTreeUri(context, rootUri)
                ?: return@forEach
            scan(
                current = tree,
                rootUri = rootUri.toString(),
                relativePath = "",
                depth = 0,
                output = records,
                sampleText = sampleText,
                maxEntries = maxEntries,
                maxDepth = maxDepth,
                maxTextBytes = maxTextBytesPerFile,
                onTextSampled = { textFiles += 1 },
            )
        }
        writeRecords(records)
        IndexStats(
            roots = roots.distinct().size,
            entries = records.size,
            files = records.count { !it.directory },
            directories = records.count(IndexedFile::directory),
            textSampledFiles = textFiles,
            generatedAtMillis = System.currentTimeMillis(),
        )
    }

    fun records(): List<IndexedFile> = synchronized(GLOBAL_LOCK) { readRecords() }

    fun clear(): Int = synchronized(GLOBAL_LOCK) {
        val count = readRecords().size
        atomicWrite(recordsFile, "[]")
        count
    }

    fun search(query: String, limit: Int = 500): List<IndexedFile> {
        require(limit in 1..10_000)
        val tokens = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotBlank)
        if (tokens.isEmpty()) return emptyList()
        return records().asSequence().mapNotNull { file ->
            val haystack = buildString {
                append(file.name).append('\n').append(file.relativePath).append('\n')
                append(file.mimeType).append('\n').append(file.extension).append('\n').append(file.searchableText)
            }.lowercase(Locale.ROOT)
            if (tokens.all(haystack::contains)) file to score(file, tokens) else null
        }.sortedByDescending(Pair<IndexedFile, Int>::second).take(limit).map(Pair<IndexedFile, Int>::first).toList()
    }

    fun collections(): List<SmartCollection> = synchronized(GLOBAL_LOCK) {
        runCatching {
            val array = JSONArray(collectionsFile.takeIf(File::isFile)?.readText() ?: "[]")
            List(array.length()) { index -> decodeCollection(array.getJSONObject(index)) }
        }.getOrDefault(emptyList())
    }

    fun putCollection(value: SmartCollection) = synchronized(GLOBAL_LOCK) {
        require(value.name.isNotBlank() && value.name.length <= 120)
        require(value.predicates.isNotEmpty() && value.predicates.size <= 32)
        val current = collections().filterNot { it.id == value.id } + value
        val array = JSONArray()
        current.sortedBy(SmartCollection::name).forEach { array.put(encodeCollection(it)) }
        atomicWrite(collectionsFile, array.toString())
    }

    fun removeCollection(id: String) = synchronized(GLOBAL_LOCK) {
        val array = JSONArray()
        collections().filterNot { it.id == id }.forEach { array.put(encodeCollection(it)) }
        atomicWrite(collectionsFile, array.toString())
    }

    fun evaluate(collection: SmartCollection, tags: (Uri) -> Set<String> = { emptySet() }): List<IndexedFile> =
        records().filter { file ->
            val outcomes = collection.predicates.map { predicate ->
                val matched = matches(file, predicate, tags)
                if (predicate.negated) !matched else matched
            }
            if (collection.matchAll) outcomes.all(Boolean::booleanValue) else outcomes.any(Boolean::booleanValue)
        }

    suspend fun exportMetadata(destinationUri: Uri) = withContext(Dispatchers.IO) {
        val objectRoot = JSONObject().apply {
            put("schemaVersion", 1)
            put("exportedAtMillis", System.currentTimeMillis())
            put("collections", JSONArray().apply { collections().forEach { put(encodeCollection(it)) } })
        }
        context.contentResolver.openOutputStream(destinationUri, "w")?.bufferedWriter(Charsets.UTF_8)?.use {
            it.write(objectRoot.toString(2))
        } ?: error("Unable to write metadata export.")
    }

    suspend fun importMetadata(sourceUri: Uri): Int = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(sourceUri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= 4 * 1024 * 1024) { "Metadata import exceeds 4 MiB." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("Unable to read metadata import.")
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.getInt("schemaVersion") == 1) { "Unsupported metadata schema." }
        val array = json.getJSONArray("collections")
        require(array.length() <= 1_000) { "Too many smart collections in import." }
        repeat(array.length()) { putCollection(decodeCollection(array.getJSONObject(it))) }
        array.length()
    }

    private suspend fun scan(
        current: DocumentFile,
        rootUri: String,
        relativePath: String,
        depth: Int,
        output: MutableList<IndexedFile>,
        sampleText: Boolean,
        maxEntries: Int,
        maxDepth: Int,
        maxTextBytes: Int,
        onTextSampled: () -> Unit,
    ) {
        coroutineContext.ensureActive()
        require(depth <= maxDepth) { "Index nesting exceeds configured limit." }
        require(output.size < maxEntries) { "Index entry count exceeds configured limit." }
        val name = current.name ?: "Untitled"
        val path = if (relativePath.isBlank()) name else "$relativePath/$name"
        val mime = current.type ?: if (current.isDirectory) "vnd.android.document/directory" else "application/octet-stream"
        var sample = ""
        if (sampleText && current.isFile && maxTextBytes > 0 && isTextCandidate(name, mime)) {
            sample = readTextSample(current.uri, maxTextBytes)
            if (sample.isNotBlank()) onTextSampled()
        }
        output += IndexedFile(
            uri = current.uri.toString(),
            rootUri = rootUri,
            relativePath = path,
            name = name,
            mimeType = mime,
            extension = FileFormatRegistry.compoundExtension(name),
            sizeBytes = current.length().takeIf { it >= 0L },
            modifiedAtMillis = current.lastModified().takeIf { it > 0L },
            directory = current.isDirectory,
            searchableText = sample,
        )
        if (current.isDirectory) {
            current.listFiles().sortedBy { it.name.orEmpty().lowercase(Locale.ROOT) }.forEach { child ->
                scan(child, rootUri, path, depth + 1, output, sampleText, maxEntries, maxDepth, maxTextBytes, onTextSampled)
            }
        }
    }

    private fun isTextCandidate(name: String, mime: String): Boolean {
        val descriptor = FileFormatRegistry.describe(name, mime)
        return descriptor.family in setOf(
            io.github.mbaliga.fylz.preview.PreviewFamily.TEXT,
            io.github.mbaliga.fylz.preview.PreviewFamily.MARKDOWN,
            io.github.mbaliga.fylz.preview.PreviewFamily.CAD_2D,
        )
    }

    private fun readTextSample(uri: Uri, maxBytes: Int): String = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = ByteArray(maxBytes)
            val count = input.read(bytes)
            if (count <= 0 || bytes.take(count).any { it == 0.toByte() }) ""
            else bytes.copyOf(count).toString(Charsets.UTF_8).replace('\u0000', ' ')
        }.orEmpty()
    }.getOrDefault("")

    private fun matches(file: IndexedFile, predicate: SmartPredicate, tags: (Uri) -> Set<String>): Boolean {
        val stringValue = when (predicate.field) {
            SmartField.NAME -> file.name
            SmartField.PATH -> file.relativePath
            SmartField.EXTENSION -> file.extension
            SmartField.MIME -> file.mimeType
            SmartField.TEXT -> file.searchableText
            SmartField.TAG -> tags(Uri.parse(file.uri)).joinToString("\n")
            SmartField.SIZE -> file.sizeBytes?.toString().orEmpty()
            SmartField.MODIFIED -> file.modifiedAtMillis?.toString().orEmpty()
        }
        val expected = predicate.value
        return when (predicate.operator) {
            SmartOperator.CONTAINS -> stringValue.contains(expected, ignoreCase = true)
            SmartOperator.EQUALS -> stringValue.equals(expected, ignoreCase = true)
            SmartOperator.STARTS_WITH -> stringValue.startsWith(expected, ignoreCase = true)
            SmartOperator.ENDS_WITH -> stringValue.endsWith(expected, ignoreCase = true)
            SmartOperator.GREATER_THAN -> stringValue.toLongOrNull()?.let { it > (expected.toLongOrNull() ?: Long.MAX_VALUE) } == true
            SmartOperator.LESS_THAN -> stringValue.toLongOrNull()?.let { it < (expected.toLongOrNull() ?: Long.MIN_VALUE) } == true
            SmartOperator.BEFORE -> file.modifiedAtMillis?.let { it < (expected.toLongOrNull() ?: Long.MIN_VALUE) } == true
            SmartOperator.AFTER -> file.modifiedAtMillis?.let { it > (expected.toLongOrNull() ?: Long.MAX_VALUE) } == true
        }
    }

    private fun score(file: IndexedFile, tokens: List<String>): Int {
        val name = file.name.lowercase(Locale.ROOT)
        val path = file.relativePath.lowercase(Locale.ROOT)
        val text = file.searchableText.lowercase(Locale.ROOT)
        return tokens.sumOf { token ->
            when {
                name == token -> 100
                name.startsWith(token) -> 60
                name.contains(token) -> 40
                path.contains(token) -> 20
                text.contains(token) -> 5
                else -> 0
            }
        }
    }

    private fun readRecords(): List<IndexedFile> = runCatching {
        val array = JSONArray(recordsFile.takeIf(File::isFile)?.readText() ?: "[]")
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            IndexedFile(
                uri = item.getString("uri"),
                rootUri = item.getString("rootUri"),
                relativePath = item.getString("relativePath"),
                name = item.getString("name"),
                mimeType = item.getString("mimeType"),
                extension = item.optString("extension"),
                sizeBytes = item.optLongOrNull("sizeBytes"),
                modifiedAtMillis = item.optLongOrNull("modifiedAtMillis"),
                directory = item.getBoolean("directory"),
                searchableText = item.optString("searchableText"),
                indexedAtMillis = item.optLong("indexedAtMillis", 0L),
            )
        }
    }.getOrDefault(emptyList())

    private fun writeRecords(records: List<IndexedFile>) = synchronized(GLOBAL_LOCK) {
        val array = JSONArray()
        records.forEach { value ->
            array.put(JSONObject().apply {
                put("uri", value.uri)
                put("rootUri", value.rootUri)
                put("relativePath", value.relativePath)
                put("name", value.name)
                put("mimeType", value.mimeType)
                put("extension", value.extension)
                put("sizeBytes", value.sizeBytes ?: JSONObject.NULL)
                put("modifiedAtMillis", value.modifiedAtMillis ?: JSONObject.NULL)
                put("directory", value.directory)
                put("searchableText", value.searchableText)
                put("indexedAtMillis", value.indexedAtMillis)
            })
        }
        atomicWrite(recordsFile, array.toString())
    }

    private fun encodeCollection(value: SmartCollection) = JSONObject().apply {
        put("id", value.id)
        put("name", value.name)
        put("matchAll", value.matchAll)
        put("predicates", JSONArray().apply {
            value.predicates.forEach { predicate ->
                put(JSONObject().apply {
                    put("field", predicate.field.name)
                    put("operator", predicate.operator.name)
                    put("value", predicate.value)
                    put("negated", predicate.negated)
                })
            }
        })
    }

    private fun decodeCollection(value: JSONObject): SmartCollection {
        val predicates = value.getJSONArray("predicates")
        return SmartCollection(
            id = value.getString("id"),
            name = value.getString("name"),
            matchAll = value.optBoolean("matchAll", true),
            predicates = List(predicates.length()) { index ->
                val predicate = predicates.getJSONObject(index)
                SmartPredicate(
                    field = SmartField.valueOf(predicate.getString("field")),
                    operator = SmartOperator.valueOf(predicate.getString("operator")),
                    value = predicate.getString("value"),
                    negated = predicate.optBoolean("negated", false),
                )
            },
        )
    }

    private fun atomicWrite(file: File, text: String) {
        root.mkdirs()
        val temp = File(root, ".${file.name}.tmp")
        temp.writeText(text)
        val backup = File(root, ".${file.name}.bak")
        backup.delete()
        if (file.exists() && !file.renameTo(backup)) error("Unable to preserve index metadata.")
        if (!temp.renameTo(file)) {
            backup.renameTo(file)
            error("Unable to commit index metadata.")
        }
        backup.delete()
    }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (!has(key) || isNull(key)) null else getLong(key)

    private companion object {
        val GLOBAL_LOCK = Any()
    }
}
