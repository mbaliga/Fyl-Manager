package io.github.mbaliga.fylz.data

import android.content.Context
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import io.github.mbaliga.fylz.index.IndexDao
import io.github.mbaliga.fylz.index.IndexScope
import io.github.mbaliga.fylz.index.IndexState
import io.github.mbaliga.fylz.index.IndexedFile
import io.github.mbaliga.fylz.index.RuleField
import io.github.mbaliga.fylz.index.RuleJoin
import io.github.mbaliga.fylz.index.RuleOperator
import io.github.mbaliga.fylz.index.SmartCollection
import io.github.mbaliga.fylz.index.SmartRule
import io.github.mbaliga.fylz.operations.ConflictPolicy
import io.github.mbaliga.fylz.operations.FileOperation
import io.github.mbaliga.fylz.operations.FileOperationType
import io.github.mbaliga.fylz.operations.OperationItem
import io.github.mbaliga.fylz.operations.OperationState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * P1.1 (A4): plain SQLite, not Room -- Room needs KSP and this toolchain is frozen. Two tables,
 * [operations] and [operation_items], replace the SharedPreferences-backed journal
 * [io.github.mbaliga.fylz.operations.OperationJournal] used to persist directly; that class is
 * now a thin facade over [OperationsDao], reading and writing through this database instead.
 *
 * The legacy journal is migrated exactly once, inside [onCreate] -- which [SQLiteOpenHelper]
 * itself only ever calls the first time the database file is created -- so a normal app launch on
 * an existing install migrates its history the first time this code runs, and every launch after
 * that (including a fresh install with no legacy data) does no migration work at all.
 *
 * P1.12 (schema version 2, additive): the same shape again, for
 * [io.github.mbaliga.fylz.index.LocalIndexStore] -- see [createIndexTables]/[migrateLegacyIndex].
 */
class FylzDatabase(private val context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE operations (
                id TEXT PRIMARY KEY NOT NULL,
                type TEXT NOT NULL,
                state TEXT NOT NULL,
                conflict_policy TEXT NOT NULL,
                created_at_millis INTEGER NOT NULL,
                updated_at_millis INTEGER NOT NULL,
                destination TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE operation_items (
                id TEXT PRIMARY KEY NOT NULL,
                operation_id TEXT NOT NULL,
                item_index INTEGER NOT NULL,
                source_uri TEXT NOT NULL,
                destination_parent_uri TEXT,
                staging_uri TEXT,
                final_uri TEXT,
                display_name TEXT NOT NULL,
                expected_bytes INTEGER,
                completed_bytes INTEGER NOT NULL,
                sha256 TEXT,
                error_code TEXT,
                state TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX index_operation_items_state ON operation_items(state)")
        db.execSQL("CREATE INDEX index_operation_items_operation_id ON operation_items(operation_id)")
        migrateLegacyJournal(db)
        createIndexTables(db)
        migrateLegacyIndex(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createIndexTables(db)
            migrateLegacyIndex(db)
        }
    }

    /**
     * P1.12: the local file index's `files`/`scopes`/`collections`/`state` tables, additive from
     * schema version 1 -- the [operations]/[operation_items] tables above are untouched by this
     * bump. Named with an `index_` prefix throughout to keep them unambiguous next to those two.
     *
     * [INDEX_TABLE_FILES] is backed by an FTS table over name/path/text for the local index's own
     * search (P1.12's own "the main search uses the index when the scope is covered" requirement).
     * FTS5 is used when this device's SQLite build supports it (probed once, since Android's
     * bundled SQLite version varies by OEM/API level rather than being guaranteed at this app's
     * own minSdk); FTS4 otherwise. Basic `MATCH` query syntax (bareword and quoted-phrase terms)
     * is identical between the two, so no other code needs to know which one is live -- only the
     * `CREATE VIRTUAL TABLE` statement itself differs.
     */
    private fun createIndexTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE ${IndexDao.TABLE_FILES} (
                uri TEXT PRIMARY KEY NOT NULL,
                root_uri TEXT NOT NULL,
                parent_uri TEXT,
                path TEXT NOT NULL DEFAULT '',
                name TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                extension TEXT NOT NULL,
                size_bytes INTEGER,
                modified_at_millis INTEGER,
                directory INTEGER NOT NULL,
                tags TEXT NOT NULL DEFAULT '',
                indexed_at_millis INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX index_files_root_uri ON ${IndexDao.TABLE_FILES}(root_uri)")
        db.execSQL("CREATE INDEX index_files_parent_uri ON ${IndexDao.TABLE_FILES}(parent_uri)")
        db.execSQL(
            if (supportsFts5(db)) {
                "CREATE VIRTUAL TABLE ${IndexDao.TABLE_FILES_FTS} USING fts5(uri UNINDEXED, name, path, text)"
            } else {
                "CREATE VIRTUAL TABLE ${IndexDao.TABLE_FILES_FTS} USING fts4(uri, name, path, text, notindexed=uri)"
            },
        )
        db.execSQL(
            """
            CREATE TABLE ${IndexDao.TABLE_SCOPES} (
                root_uri TEXT PRIMARY KEY NOT NULL,
                display_name TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                last_media_store_generation INTEGER,
                last_scanned_at_millis INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE ${IndexDao.TABLE_COLLECTIONS} (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                join_mode TEXT NOT NULL,
                rules_json TEXT NOT NULL,
                created_at_millis INTEGER NOT NULL,
                updated_at_millis INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE ${IndexDao.TABLE_STATE} (
                id INTEGER PRIMARY KEY CHECK (id = 0),
                paused INTEGER NOT NULL DEFAULT 0,
                last_started_at_millis INTEGER,
                last_completed_at_millis INTEGER,
                last_error TEXT,
                indexed_files INTEGER NOT NULL DEFAULT 0,
                truncated INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
    }

    /** Probes for FTS5 by actually creating (and immediately dropping) a scratch virtual table,
     *  since there is no cheaper, reliable way to ask SQLite what modules it was built with from
     *  the public Android API -- `pragma_compile_options` is itself only queryable on FTS-capable
     *  builds in the first place, so it can't tell the two apart any more cheaply than this can. */
    private fun supportsFts5(db: SQLiteDatabase): Boolean = try {
        db.execSQL("CREATE VIRTUAL TABLE fts5_probe USING fts5(x)")
        db.execSQL("DROP TABLE fts5_probe")
        true
    } catch (unsupported: SQLException) {
        false
    }

    /**
     * Reads [io.github.mbaliga.fylz.index.LocalIndexStore]'s pre-P1.12 JSON files
     * (`filesDir/local-index/{files,scopes,collections,state}.json`) and inserts every record
     * into the new tables above, then deletes the JSON files -- the same
     * migrate-once-then-clear-the-old-blob shape [migrateLegacyJournal] already established for
     * the operations journal. Decoding happens here, by hand, rather than by depending on
     * `index/IndexModels.kt`'s data classes, to keep this migration correct even if those classes'
     * shape ever changes later: this step only ever needs to run once per install, against a
     * schema that will never change after the fact.
     */
    private fun migrateLegacyIndex(db: SQLiteDatabase) {
        val root = File(context.filesDir, "local-index")
        if (!root.isDirectory) return
        migrateLegacyIndexFiles(db, File(root, "files.json"))
        migrateLegacyIndexScopes(db, File(root, "scopes.json"))
        migrateLegacyIndexCollections(db, File(root, "collections.json"))
        migrateLegacyIndexState(db, File(root, "state.json"))
    }

    private fun migrateLegacyIndexFiles(db: SQLiteDatabase, file: File) {
        val values = readJsonArray(file) ?: return
        for (index in 0 until values.length()) {
            val value = runCatching { values.getJSONObject(index) }.getOrNull() ?: continue
            val uri = value.optString("uri").takeIf(String::isNotBlank) ?: continue
            val tags = value.optJSONArray("tags")?.let { array ->
                buildSet { repeat(array.length()) { add(array.optString(it)) } }
            }.orEmpty()
            IndexDao.putFile(
                db,
                IndexedFile(
                    uri = uri,
                    rootUri = value.optString("rootUri"),
                    // The pre-P1.12 index kept neither a parent nor a relative path; both are
                    // corrected on the next scan of this file's scope, which every migrated
                    // install still needs to run at least once (see IndexScope's own
                    // lastMediaStoreGeneration doc) -- these placeholders are never trusted for
                    // a PATH rule or FTS path search until then.
                    parentUri = null,
                    path = "",
                    name = value.optString("name"),
                    mimeType = value.optString("mimeType"),
                    extension = value.optString("extension"),
                    sizeBytes = value.optLongOrNull("sizeBytes"),
                    modifiedAtMillis = value.optLongOrNull("modifiedAtMillis"),
                    directory = value.optBoolean("directory", false),
                    tags = tags,
                    textSnippet = null,
                    indexedAtMillis = value.optLong("indexedAtMillis", System.currentTimeMillis()),
                ),
            )
        }
        file.delete()
    }

    private fun migrateLegacyIndexScopes(db: SQLiteDatabase, file: File) {
        val values = readJsonArray(file) ?: return
        for (index in 0 until values.length()) {
            val value = runCatching { values.getJSONObject(index) }.getOrNull() ?: continue
            val rootUri = value.optString("rootUri").takeIf(String::isNotBlank) ?: continue
            IndexDao.putScope(
                db,
                IndexScope(
                    rootUri = rootUri,
                    displayName = value.optString("displayName"),
                    enabled = value.optBoolean("enabled", true),
                    // Unknown after a JSON migration -- forces one real rescan before this
                    // scope's generation-based skip-check (see IndexScope's own doc) applies.
                    lastMediaStoreGeneration = null,
                    lastScannedAtMillis = null,
                ),
            )
        }
        file.delete()
    }

    private fun migrateLegacyIndexCollections(db: SQLiteDatabase, file: File) {
        val values = readJsonArray(file) ?: return
        for (index in 0 until values.length()) {
            val value = runCatching { values.getJSONObject(index) }.getOrNull() ?: continue
            runCatching {
                val rulesJson = value.getJSONArray("rules")
                val rules = List(rulesJson.length()) { ruleIndex ->
                    val rule = rulesJson.getJSONObject(ruleIndex)
                    SmartRule(
                        field = RuleField.valueOf(rule.getString("field")),
                        operator = RuleOperator.valueOf(rule.getString("operator")),
                        value = rule.getString("value"),
                        negate = rule.optBoolean("negate", false),
                    )
                }
                IndexDao.putCollection(
                    db,
                    SmartCollection(
                        id = value.getString("id"),
                        name = value.getString("name"),
                        join = RuleJoin.valueOf(value.getString("join")),
                        rules = rules,
                        createdAtMillis = value.optLong("createdAtMillis", System.currentTimeMillis()),
                        updatedAtMillis = value.optLong("updatedAtMillis", System.currentTimeMillis()),
                    ),
                )
            }
        }
        file.delete()
    }

    private fun migrateLegacyIndexState(db: SQLiteDatabase, file: File) {
        if (!file.isFile) return
        val value = runCatching { JSONObject(file.readText()) }.getOrNull()
        if (value != null) {
            IndexDao.putState(
                db,
                IndexState(
                    paused = value.optBoolean("paused", false),
                    lastStartedAtMillis = value.optLongOrNull("lastStartedAtMillis"),
                    lastCompletedAtMillis = value.optLongOrNull("lastCompletedAtMillis"),
                    lastError = value.optStringOrNull("lastError"),
                    indexedFiles = value.optInt("indexedFiles", 0),
                    truncated = value.optBoolean("truncated", false),
                ),
            )
        }
        file.delete()
    }

    private fun readJsonArray(file: File): JSONArray? {
        if (!file.isFile) return null
        return runCatching { JSONArray(file.readText()) }.getOrNull()
    }

    private fun JSONObject.optLongOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else optLong(key)
    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf(String::isNotBlank)

    /**
     * Reads [LEGACY_PREFERENCES_NAME]'s [LEGACY_RECORDS_KEY] JSON blob -- the same encoding
     * [io.github.mbaliga.fylz.operations.OperationJournal] wrote directly before this task --
     * inserts every record it decodes, and clears the blob so the now-migrated JSON isn't left
     * around meaning nothing. The small [LEGACY_PROCESS_SESSION_KEY] marker in the same
     * preferences file is untouched: it isn't operation data, and
     * [io.github.mbaliga.fylz.operations.OperationJournal] still owns it directly.
     */
    private fun migrateLegacyJournal(db: SQLiteDatabase) {
        val preferences = context.getSharedPreferences(LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE)
        val raw = preferences.getString(LEGACY_RECORDS_KEY, null) ?: return
        val operations = decodeLegacyJournal(raw)
        if (operations.isEmpty()) return
        operations.forEach { OperationsDao.put(db, it) }
        preferences.edit().remove(LEGACY_RECORDS_KEY).apply()
    }

    private fun decodeLegacyJournal(raw: String): List<FileOperation> = runCatching {
        val root = JSONArray(raw)
        buildList {
            for (index in 0 until root.length()) {
                val value = root.getJSONObject(index)
                val itemsJson = value.getJSONArray("items")
                val items = buildList {
                    for (itemIndex in 0 until itemsJson.length()) {
                        val item = itemsJson.getJSONObject(itemIndex)
                        add(
                            OperationItem(
                                id = item.getString("id"),
                                source = Uri.parse(item.getString("source")),
                                destination = item.optString("destination")
                                    .takeIf(String::isNotBlank)
                                    ?.let(Uri::parse),
                                displayName = item.getString("displayName"),
                                expectedBytes = item.optLong("expectedBytes", Long.MIN_VALUE)
                                    .takeUnless { it == Long.MIN_VALUE },
                                completedBytes = item.optLong("completedBytes", 0L),
                                state = OperationState.valueOf(item.getString("state")),
                                errorCode = item.optString("errorCode").takeIf(String::isNotBlank),
                                stagingUri = item.optString("stagingUri")
                                    .takeIf(String::isNotBlank)
                                    ?.let(Uri::parse),
                            ),
                        )
                    }
                }
                add(
                    FileOperation(
                        id = value.getString("id"),
                        type = FileOperationType.valueOf(value.getString("type")),
                        items = items,
                        conflictPolicy = ConflictPolicy.valueOf(value.getString("conflictPolicy")),
                        state = OperationState.valueOf(value.getString("state")),
                        createdAtMillis = value.getLong("createdAtMillis"),
                        updatedAtMillis = value.getLong("updatedAtMillis"),
                    ),
                )
            }
        }
    }.getOrElse { emptyList() }

    companion object {
        const val DATABASE_NAME = "fylz.db"
        const val DATABASE_VERSION = 2

        internal const val LEGACY_PREFERENCES_NAME = "fylz_operation_journal"
        internal const val LEGACY_RECORDS_KEY = "operations"
    }
}
