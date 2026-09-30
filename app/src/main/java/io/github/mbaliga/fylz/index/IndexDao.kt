package io.github.mbaliga.fylz.index

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hand-written CRUD for [IndexModels.kt]'s types over [io.github.mbaliga.fylz.data.FylzDatabase]'s
 * `index_*` tables (P1.12), the same plain-SQLite-DAO idiom
 * [io.github.mbaliga.fylz.data.OperationsDao] already established for `operations`/
 * `operation_items`. Every function takes the database explicitly rather than opening its own,
 * so a caller controls the transaction boundary.
 */
internal object IndexDao {

    // ---- files -------------------------------------------------------------------------------

    fun files(db: SQLiteDatabase): List<IndexedFile> = db.query(
        TABLE_FILES, FILE_COLUMNS, null, null, null, null, null,
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toIndexedFile()) } }

    fun findByUri(db: SQLiteDatabase, uri: String): IndexedFile? = db.query(
        TABLE_FILES, FILE_COLUMNS, "uri = ?", arrayOf(uri), null, null, null,
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toIndexedFile() else null }

    fun filesUnderPath(db: SQLiteDatabase, rootUri: String, pathPrefix: String): List<IndexedFile> {
        val selection = if (pathPrefix.isEmpty()) {
            "root_uri = ?"
        } else {
            "root_uri = ? AND (path = ? OR path LIKE ? ESCAPE '\\')"
        }
        val args = if (pathPrefix.isEmpty()) {
            arrayOf(rootUri)
        } else {
            arrayOf(rootUri, pathPrefix, likePrefix(pathPrefix) + "/%")
        }
        return db.query(TABLE_FILES, FILE_COLUMNS, selection, args, null, null, null)
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toIndexedFile()) } }
    }

    /** Substring-style listing backing [LocalIndexStore.query]'s pre-P1.12 behaviour: every term
     *  is matched case-insensitively against name, extension or any tag -- not FTS token
     *  matching, which would (for example) miss "report.pdf" for the query "port". */
    fun likeSearch(db: SQLiteDatabase, normalized: String): List<IndexedFile> {
        if (normalized.isBlank()) {
            return db.query(TABLE_FILES, FILE_COLUMNS, null, null, null, null, null)
                .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toIndexedFile()) } }
        }
        val like = "%" + likePrefix(normalized) + "%"
        val selection = "lower(name) LIKE ? ESCAPE '\\' OR lower(extension) LIKE ? ESCAPE '\\' OR lower(tags) LIKE ? ESCAPE '\\'"
        return db.query(TABLE_FILES, FILE_COLUMNS, selection, arrayOf(like, like, like), null, null, null)
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toIndexedFile()) } }
    }

    /**
     * Content search (P1.12): matches [terms] against the FTS `text` column -- populated only for
     * text-previewable files, see [IndexedFile.textSnippet] -- within [rootUri]/[pathPrefix],
     * returning each hit's own FTS-generated snippet. This is a genuinely different match
     * semantics than the live walk's per-line substring check (FTS tokenizes; "port" will not
     * match inside "report"), stated plainly rather than silently assumed identical -- see
     * `RecursiveSearchEngine`'s own note at its index fast-path call site.
     */
    fun contentMatches(
        db: SQLiteDatabase,
        rootUri: String,
        pathPrefix: String,
        terms: List<String>,
    ): List<Pair<String, String>> {
        val match = ftsMatchExpression(terms) ?: return emptyList()
        val pathCondition = if (pathPrefix.isEmpty()) "" else " AND (f.path = ? OR f.path LIKE ? ESCAPE '\\')"
        val sql = """
            SELECT f.uri, snippet($TABLE_FILES_FTS, 3, '', '', '…', 12)
            FROM $TABLE_FILES_FTS
            JOIN $TABLE_FILES f ON f.uri = $TABLE_FILES_FTS.uri
            WHERE $TABLE_FILES_FTS.text MATCH ? AND f.root_uri = ?$pathCondition
        """.trimIndent()
        val args = buildList {
            add(match)
            add(rootUri)
            if (pathPrefix.isNotEmpty()) {
                add(pathPrefix)
                add(likePrefix(pathPrefix) + "/%")
            }
        }.toTypedArray()
        return db.rawQuery(sql, args).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1).orEmpty()) }
        }
    }

    fun replaceFilesForRoot(db: SQLiteDatabase, rootUri: String, entries: List<IndexedFile>) {
        db.beginTransaction()
        try {
            deleteFilesForRoot(db, rootUri)
            entries.forEach { insertFile(db, it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun putFile(db: SQLiteDatabase, file: IndexedFile) {
        db.beginTransaction()
        try {
            db.delete(TABLE_FILES_FTS, "uri = ?", arrayOf(file.uri))
            insertFile(db, file)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearFiles(db: SQLiteDatabase) {
        db.delete(TABLE_FILES, null, null)
        db.delete(TABLE_FILES_FTS, null, null)
    }

    private fun deleteFilesForRoot(db: SQLiteDatabase, rootUri: String) {
        db.rawQuery("SELECT uri FROM $TABLE_FILES WHERE root_uri = ?", arrayOf(rootUri)).use { cursor ->
            while (cursor.moveToNext()) db.delete(TABLE_FILES_FTS, "uri = ?", arrayOf(cursor.getString(0)))
        }
        db.delete(TABLE_FILES, "root_uri = ?", arrayOf(rootUri))
    }

    private fun insertFile(db: SQLiteDatabase, file: IndexedFile) {
        db.insertWithOnConflict(
            TABLE_FILES,
            null,
            ContentValues().apply {
                put("uri", file.uri)
                put("root_uri", file.rootUri)
                put("parent_uri", file.parentUri)
                put("path", file.path)
                put("name", file.name)
                put("mime_type", file.mimeType)
                put("extension", file.extension)
                if (file.sizeBytes == null) putNull("size_bytes") else put("size_bytes", file.sizeBytes)
                if (file.modifiedAtMillis == null) putNull("modified_at_millis") else put("modified_at_millis", file.modifiedAtMillis)
                put("directory", if (file.directory) 1 else 0)
                put("tags", encodeTags(file.tags))
                put("indexed_at_millis", file.indexedAtMillis)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        if (!file.directory) {
            db.insertWithOnConflict(
                TABLE_FILES_FTS,
                null,
                ContentValues().apply {
                    put("uri", file.uri)
                    put("name", file.name)
                    put("path", file.path)
                    put("text", file.textSnippet.orEmpty())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    // ---- scopes --------------------------------------------------------------------------------

    fun scopes(db: SQLiteDatabase): List<IndexScope> = db.query(
        TABLE_SCOPES, SCOPE_COLUMNS, null, null, null, null, "display_name COLLATE NOCASE ASC",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toIndexScope()) } }

    fun putScope(db: SQLiteDatabase, scope: IndexScope) {
        db.insertWithOnConflict(
            TABLE_SCOPES,
            null,
            ContentValues().apply {
                put("root_uri", scope.rootUri)
                put("display_name", scope.displayName)
                put("enabled", if (scope.enabled) 1 else 0)
                if (scope.lastMediaStoreGeneration == null) {
                    putNull("last_media_store_generation")
                } else {
                    put("last_media_store_generation", scope.lastMediaStoreGeneration)
                }
                if (scope.lastScannedAtMillis == null) putNull("last_scanned_at_millis") else put("last_scanned_at_millis", scope.lastScannedAtMillis)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun removeRoot(db: SQLiteDatabase, rootUri: String) {
        db.beginTransaction()
        try {
            deleteFilesForRoot(db, rootUri)
            db.delete(TABLE_SCOPES, "root_uri = ?", arrayOf(rootUri))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---- smart collections ----------------------------------------------------------------------

    fun collections(db: SQLiteDatabase): List<SmartCollection> = db.query(
        TABLE_COLLECTIONS, COLLECTION_COLUMNS, null, null, null, null, "name COLLATE NOCASE ASC",
    ).use { cursor -> buildList { while (cursor.moveToNext()) cursor.toSmartCollection()?.let(::add) } }

    fun putCollection(db: SQLiteDatabase, collection: SmartCollection) {
        db.insertWithOnConflict(
            TABLE_COLLECTIONS,
            null,
            ContentValues().apply {
                put("id", collection.id)
                put("name", collection.name)
                put("join_mode", collection.join.name)
                put("rules_json", encodeRules(collection.rules))
                put("created_at_millis", collection.createdAtMillis)
                put("updated_at_millis", collection.updatedAtMillis)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun removeCollection(db: SQLiteDatabase, id: String) {
        db.delete(TABLE_COLLECTIONS, "id = ?", arrayOf(id))
    }

    // ---- state -----------------------------------------------------------------------------------

    fun state(db: SQLiteDatabase): IndexState = db.query(
        TABLE_STATE, STATE_COLUMNS, "id = 0", null, null, null, null,
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toIndexState() else IndexState() }

    fun putState(db: SQLiteDatabase, state: IndexState) {
        db.insertWithOnConflict(
            TABLE_STATE,
            null,
            ContentValues().apply {
                put("id", 0)
                put("paused", if (state.paused) 1 else 0)
                if (state.lastStartedAtMillis == null) putNull("last_started_at_millis") else put("last_started_at_millis", state.lastStartedAtMillis)
                if (state.lastCompletedAtMillis == null) putNull("last_completed_at_millis") else put("last_completed_at_millis", state.lastCompletedAtMillis)
                if (state.lastError == null) putNull("last_error") else put("last_error", state.lastError)
                put("indexed_files", state.indexedFiles)
                put("truncated", if (state.truncated) 1 else 0)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    // ---- encoding helpers --------------------------------------------------------------------

    private fun encodeTags(tags: Set<String>): String = tags.joinToString(TAG_SEPARATOR)
    private fun decodeTags(stored: String?): Set<String> =
        stored?.split(TAG_SEPARATOR)?.filter(String::isNotBlank).orEmpty().toSet()

    private fun encodeRules(rules: List<SmartRule>): String = JSONArray().apply {
        rules.forEach { rule ->
            put(
                JSONObject().apply {
                    put("field", rule.field.name)
                    put("operator", rule.operator.name)
                    put("value", rule.value)
                    put("negate", rule.negate)
                },
            )
        }
    }.toString()

    private fun decodeRules(json: String): List<SmartRule> = runCatching {
        val array = JSONArray(json)
        List(array.length()) { index ->
            val value = array.getJSONObject(index)
            SmartRule(
                field = RuleField.valueOf(value.getString("field")),
                operator = RuleOperator.valueOf(value.getString("operator")),
                value = value.getString("value"),
                negate = value.optBoolean("negate", false),
            )
        }
    }.getOrElse { emptyList() }

    /** Escapes `%`/`_`/the escape character itself for a `LIKE ... ESCAPE '\'` clause. */
    private fun likePrefix(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /**
     * Builds a safe FTS `MATCH` expression: each term becomes its own quoted phrase (FTS treats
     * a quoted phrase as literal text, sidestepping the query-syntax operators a raw term could
     * otherwise trigger, such as a leading minus, a trailing wildcard or a column filter colon),
     * ANDed together implicitly by space-separating them -- both FTS4 and FTS5 use identical
     * syntax for this. Returns null for no usable terms.
     */
    private fun ftsMatchExpression(terms: List<String>): String? {
        val quoted = terms.filter(String::isNotBlank).map { "\"${it.replace("\"", "\"\"")}\"" }
        return quoted.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    private fun android.database.Cursor.toIndexedFile(): IndexedFile = IndexedFile(
        uri = getString(getColumnIndexOrThrow("uri")),
        rootUri = getString(getColumnIndexOrThrow("root_uri")),
        parentUri = getStringOrNull("parent_uri"),
        path = getString(getColumnIndexOrThrow("path")),
        name = getString(getColumnIndexOrThrow("name")),
        mimeType = getString(getColumnIndexOrThrow("mime_type")),
        extension = getString(getColumnIndexOrThrow("extension")),
        sizeBytes = getLongOrNull("size_bytes"),
        modifiedAtMillis = getLongOrNull("modified_at_millis"),
        directory = getInt(getColumnIndexOrThrow("directory")) != 0,
        tags = decodeTags(getStringOrNull("tags")),
        indexedAtMillis = getLong(getColumnIndexOrThrow("indexed_at_millis")),
    )

    private fun android.database.Cursor.toIndexScope(): IndexScope = IndexScope(
        rootUri = getString(getColumnIndexOrThrow("root_uri")),
        displayName = getString(getColumnIndexOrThrow("display_name")),
        enabled = getInt(getColumnIndexOrThrow("enabled")) != 0,
        lastMediaStoreGeneration = getLongOrNull("last_media_store_generation"),
        lastScannedAtMillis = getLongOrNull("last_scanned_at_millis"),
    )

    private fun android.database.Cursor.toSmartCollection(): SmartCollection? = runCatching {
        SmartCollection(
            id = getString(getColumnIndexOrThrow("id")),
            name = getString(getColumnIndexOrThrow("name")),
            join = RuleJoin.valueOf(getString(getColumnIndexOrThrow("join_mode"))),
            rules = decodeRules(getString(getColumnIndexOrThrow("rules_json"))),
            createdAtMillis = getLong(getColumnIndexOrThrow("created_at_millis")),
            updatedAtMillis = getLong(getColumnIndexOrThrow("updated_at_millis")),
        )
    }.getOrNull()

    private fun android.database.Cursor.toIndexState(): IndexState = IndexState(
        paused = getInt(getColumnIndexOrThrow("paused")) != 0,
        lastStartedAtMillis = getLongOrNull("last_started_at_millis"),
        lastCompletedAtMillis = getLongOrNull("last_completed_at_millis"),
        lastError = getStringOrNull("last_error"),
        indexedFiles = getInt(getColumnIndexOrThrow("indexed_files")),
        truncated = getInt(getColumnIndexOrThrow("truncated")) != 0,
    )

    private fun android.database.Cursor.getLongOrNull(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }

    private fun android.database.Cursor.getStringOrNull(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    const val TABLE_FILES = "index_files"
    const val TABLE_FILES_FTS = "index_files_fts"
    const val TABLE_SCOPES = "index_scopes"
    const val TABLE_COLLECTIONS = "index_smart_collections"
    const val TABLE_STATE = "index_state"

    private const val TAG_SEPARATOR = "\u001F"

    private val FILE_COLUMNS = arrayOf(
        "uri", "root_uri", "parent_uri", "path", "name", "mime_type", "extension",
        "size_bytes", "modified_at_millis", "directory", "tags", "indexed_at_millis",
    )
    private val SCOPE_COLUMNS = arrayOf(
        "root_uri", "display_name", "enabled", "last_media_store_generation", "last_scanned_at_millis",
    )
    private val COLLECTION_COLUMNS = arrayOf("id", "name", "join_mode", "rules_json", "created_at_millis", "updated_at_millis")
    private val STATE_COLUMNS = arrayOf(
        "paused", "last_started_at_millis", "last_completed_at_millis", "last_error", "indexed_files", "truncated",
    )
}
