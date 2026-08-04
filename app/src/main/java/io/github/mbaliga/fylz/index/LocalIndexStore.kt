package io.github.mbaliga.fylz.index

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class LocalIndexStore(context: Context) {
    private val root = File(context.filesDir, "local-index")
    private val filesFile = File(root, "files.json")
    private val scopesFile = File(root, "scopes.json")
    private val collectionsFile = File(root, "collections.json")
    private val stateFile = File(root, "state.json")

    fun files(): List<IndexedFile> = synchronized(LOCK) {
        readArray(filesFile).mapNotNull(::decodeFile)
    }

    fun replaceFiles(rootUri: String, entries: List<IndexedFile>) = synchronized(LOCK) {
        val retained = files().filterNot { it.rootUri == rootUri }
        writeArray(filesFile, (retained + entries).sortedBy { it.name.lowercase() }.map(::encodeFile))
    }

    fun removeRoot(rootUri: String) = synchronized(LOCK) {
        writeArray(filesFile, files().filterNot { it.rootUri == rootUri }.map(::encodeFile))
        putScopes(scopes().filterNot { it.rootUri == rootUri })
    }

    fun clearFiles() = synchronized(LOCK) { writeArray(filesFile, emptyList()) }

    fun scopes(): List<IndexScope> = synchronized(LOCK) {
        readArray(scopesFile).mapNotNull(::decodeScope).sortedBy(IndexScope::displayName)
    }

    fun putScope(scope: IndexScope) = synchronized(LOCK) {
        putScopes(scopes().filterNot { it.rootUri == scope.rootUri } + scope)
    }

    fun putScopes(scopes: List<IndexScope>) = synchronized(LOCK) {
        writeArray(scopesFile, scopes.sortedBy(IndexScope::displayName).map(::encodeScope))
    }

    fun collections(): List<SmartCollection> = synchronized(LOCK) {
        readArray(collectionsFile).mapNotNull(::decodeCollection).sortedBy(SmartCollection::name)
    }

    fun putCollection(collection: SmartCollection) = synchronized(LOCK) {
        val values = collections().filterNot { it.id == collection.id } + collection.copy(updatedAtMillis = System.currentTimeMillis())
        writeArray(collectionsFile, values.sortedBy(SmartCollection::name).map(::encodeCollection))
    }

    fun removeCollection(id: String) = synchronized(LOCK) {
        writeArray(collectionsFile, collections().filterNot { it.id == id }.map(::encodeCollection))
    }

    fun state(): IndexState = synchronized(LOCK) {
        if (!stateFile.isFile) return@synchronized IndexState()
        runCatching {
            val value = JSONObject(stateFile.readText())
            IndexState(
                paused = value.optBoolean("paused", false),
                lastStartedAtMillis = value.optLongOrNull("lastStartedAtMillis"),
                lastCompletedAtMillis = value.optLongOrNull("lastCompletedAtMillis"),
                lastError = value.optStringOrNull("lastError"),
                indexedFiles = value.optInt("indexedFiles", 0),
                truncated = value.optBoolean("truncated", false),
            )
        }.getOrElse { IndexState(lastError = "Index metadata could not be read.") }
    }

    fun putState(state: IndexState) = synchronized(LOCK) {
        atomicWrite(stateFile, JSONObject().apply {
            put("paused", state.paused)
            put("lastStartedAtMillis", state.lastStartedAtMillis ?: JSONObject.NULL)
            put("lastCompletedAtMillis", state.lastCompletedAtMillis ?: JSONObject.NULL)
            put("lastError", state.lastError ?: JSONObject.NULL)
            put("indexedFiles", state.indexedFiles)
            put("truncated", state.truncated)
        }.toString())
    }

    fun setPaused(paused: Boolean) = putState(state().copy(paused = paused))

    fun query(text: String = "", collectionId: String? = null): List<IndexedFile> {
        val collection = collectionId?.let { id -> collections().firstOrNull { it.id == id } }
        val normalized = text.trim().lowercase()
        return files().asSequence()
            .filter { normalized.isBlank() || normalized in it.name.lowercase() || normalized in it.extension.lowercase() || it.tags.any { tag -> normalized in tag.lowercase() } }
            .filter { collection == null || SmartCollectionEngine.matches(it, collection) }
            .sortedWith(compareByDescending<IndexedFile> { it.directory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .toList()
    }

    private fun encodeFile(value: IndexedFile) = JSONObject().apply {
        put("uri", value.uri); put("rootUri", value.rootUri); put("name", value.name); put("mimeType", value.mimeType)
        put("extension", value.extension); put("sizeBytes", value.sizeBytes ?: JSONObject.NULL)
        put("modifiedAtMillis", value.modifiedAtMillis ?: JSONObject.NULL); put("directory", value.directory)
        put("tags", JSONArray(value.tags.toList())); put("indexedAtMillis", value.indexedAtMillis)
    }

    private fun decodeFile(value: JSONObject): IndexedFile? = runCatching {
        IndexedFile(
            uri = value.getString("uri"), rootUri = value.getString("rootUri"), name = value.getString("name"),
            mimeType = value.getString("mimeType"), extension = value.optString("extension"),
            sizeBytes = value.optLongOrNull("sizeBytes"), modifiedAtMillis = value.optLongOrNull("modifiedAtMillis"),
            directory = value.getBoolean("directory"), tags = value.optJSONArray("tags")?.toStringSet().orEmpty(),
            indexedAtMillis = value.optLong("indexedAtMillis", System.currentTimeMillis()),
        )
    }.getOrNull()

    private fun encodeScope(value: IndexScope) = JSONObject().apply {
        put("rootUri", value.rootUri); put("displayName", value.displayName); put("enabled", value.enabled)
    }

    private fun decodeScope(value: JSONObject): IndexScope? = runCatching {
        IndexScope(value.getString("rootUri"), value.getString("displayName"), value.optBoolean("enabled", true))
    }.getOrNull()

    private fun encodeCollection(value: SmartCollection) = JSONObject().apply {
        put("id", value.id); put("name", value.name); put("join", value.join.name)
        put("createdAtMillis", value.createdAtMillis); put("updatedAtMillis", value.updatedAtMillis)
        put("rules", JSONArray().apply { value.rules.forEach { rule -> put(JSONObject().apply {
            put("field", rule.field.name); put("operator", rule.operator.name); put("value", rule.value); put("negate", rule.negate)
        }) } })
    }

    private fun decodeCollection(value: JSONObject): SmartCollection? = runCatching {
        val rules = value.getJSONArray("rules")
        SmartCollection(
            id = value.getString("id"), name = value.getString("name"), join = RuleJoin.valueOf(value.getString("join")),
            rules = List(rules.length()) { index -> rules.getJSONObject(index).let { rule -> SmartRule(
                RuleField.valueOf(rule.getString("field")), RuleOperator.valueOf(rule.getString("operator")),
                rule.getString("value"), rule.optBoolean("negate", false),
            ) } },
            createdAtMillis = value.optLong("createdAtMillis", System.currentTimeMillis()),
            updatedAtMillis = value.optLong("updatedAtMillis", System.currentTimeMillis()),
        )
    }.getOrNull()

    private fun readArray(file: File): List<JSONObject> = runCatching {
        if (!file.isFile) return emptyList()
        val array = JSONArray(file.readText())
        List(array.length()) { array.getJSONObject(it) }
    }.getOrElse { emptyList() }

    private fun writeArray(file: File, values: List<JSONObject>) {
        val array = JSONArray(); values.forEach(array::put); atomicWrite(file, array.toString())
    }

    private fun atomicWrite(file: File, value: String) {
        root.mkdirs()
        val temp = File(root, ".${file.name}.${UUID.randomUUID()}.tmp")
        val backup = File(root, ".${file.name}.bak")
        FileOutputStream(temp).use { it.write(value.toByteArray()); it.fd.sync() }
        backup.delete()
        if (file.exists() && !file.renameTo(backup)) { temp.delete(); error("Unable to preserve index metadata.") }
        if (!temp.renameTo(file)) { backup.renameTo(file); error("Unable to commit index metadata.") }
        backup.delete()
    }

    private fun JSONArray.toStringSet(): Set<String> = buildSet { repeat(length()) { add(getString(it)) } }
    private fun JSONObject.optStringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
    private fun JSONObject.optLongOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else getLong(key)

    private companion object { val LOCK = Any() }
}
