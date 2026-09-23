package io.github.mbaliga.fylz.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import io.github.mbaliga.fylz.operations.ConflictPolicy
import io.github.mbaliga.fylz.operations.FileOperation
import io.github.mbaliga.fylz.operations.FileOperationType
import io.github.mbaliga.fylz.operations.OperationItem
import io.github.mbaliga.fylz.operations.OperationState
import org.json.JSONArray

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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No prior schema version exists yet -- this is schema version 1. Future migrations
        // (ALTER TABLE / new tables, gated on oldVersion) go here.
    }

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
        const val DATABASE_VERSION = 1

        internal const val LEGACY_PREFERENCES_NAME = "fylz_operation_journal"
        internal const val LEGACY_RECORDS_KEY = "operations"
    }
}
