package io.github.mbaliga.fylz.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import io.github.mbaliga.fylz.operations.ConflictPolicy
import io.github.mbaliga.fylz.operations.FileOperation
import io.github.mbaliga.fylz.operations.FileOperationType
import io.github.mbaliga.fylz.operations.OperationItem
import io.github.mbaliga.fylz.operations.OperationState

/**
 * Hand-written DAO (A4: plain SQLite, no Room) backing [FylzDatabase]'s `operations` and
 * `operation_items` tables. Every write replaces an operation's full item set inside one
 * transaction -- items have no independent identity worth diffing against, and a whole
 * [FileOperation] is always what a caller has in hand to persist.
 */
internal object OperationsDao {
    private const val MAX_RECORDS = 200

    private val FINISHED_STATES = listOf(
        OperationState.SUCCEEDED,
        OperationState.FAILED,
        OperationState.CANCELLED,
    )

    fun list(db: SQLiteDatabase): List<FileOperation> {
        val operations = db.rawQuery("SELECT * FROM operations ORDER BY updated_at_millis DESC", null)
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toOperation()) } }
        return operations.map { it.copy(items = loadItems(db, it.id)) }
    }

    fun find(db: SQLiteDatabase, id: String): FileOperation? {
        val operation = db.rawQuery("SELECT * FROM operations WHERE id = ?", arrayOf(id))
            .use { cursor -> if (cursor.moveToFirst()) cursor.toOperation() else null }
            ?: return null
        return operation.copy(items = loadItems(db, id))
    }

    fun put(db: SQLiteDatabase, operation: FileOperation) {
        db.beginTransaction()
        try {
            db.delete("operation_items", "operation_id = ?", arrayOf(operation.id))
            db.delete("operations", "id = ?", arrayOf(operation.id))
            db.insertOrThrow("operations", null, operation.toContentValues())
            operation.items.forEachIndexed { index, item ->
                db.insertOrThrow("operation_items", null, item.toContentValues(operation.id, index))
            }
            enforceRecordLimit(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun remove(db: SQLiteDatabase, id: String) {
        db.beginTransaction()
        try {
            db.delete("operation_items", "operation_id = ?", arrayOf(id))
            db.delete("operations", "id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearFinished(db: SQLiteDatabase) {
        val placeholders = FINISHED_STATES.joinToString(",") { "?" }
        val args = FINISHED_STATES.map { it.name }.toTypedArray()
        db.beginTransaction()
        try {
            db.rawQuery("SELECT id FROM operations WHERE state IN ($placeholders)", args).use { cursor ->
                while (cursor.moveToNext()) {
                    db.delete("operation_items", "operation_id = ?", arrayOf(cursor.getString(0)))
                }
            }
            db.delete("operations", "state IN ($placeholders)", args)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Keeps the most-recently-updated [MAX_RECORDS] operations, same cap the SharedPreferences
     * journal enforced before this task. Must run inside the caller's transaction. */
    private fun enforceRecordLimit(db: SQLiteDatabase) {
        db.rawQuery(
            "SELECT id FROM operations ORDER BY updated_at_millis DESC LIMIT -1 OFFSET ?",
            arrayOf(MAX_RECORDS.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                db.delete("operation_items", "operation_id = ?", arrayOf(id))
                db.delete("operations", "id = ?", arrayOf(id))
            }
        }
    }

    private fun loadItems(db: SQLiteDatabase, operationId: String): List<OperationItem> =
        db.rawQuery(
            "SELECT * FROM operation_items WHERE operation_id = ? ORDER BY item_index ASC",
            arrayOf(operationId),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toItem()) } }

    private fun FileOperation.toContentValues() = ContentValues().apply {
        put("id", id)
        put("type", type.name)
        put("state", state.name)
        put("conflict_policy", conflictPolicy.name)
        put("created_at_millis", createdAtMillis)
        put("updated_at_millis", updatedAtMillis)
        put("destination", destination?.toString())
    }

    private fun OperationItem.toContentValues(operationId: String, index: Int) = ContentValues().apply {
        put("id", id)
        put("operation_id", operationId)
        put("item_index", index)
        put("source_uri", source.toString())
        put("destination_parent_uri", destination?.toString())
        put("staging_uri", stagingUri?.toString())
        put("final_uri", finalUri?.toString())
        put("display_name", displayName)
        put("expected_bytes", expectedBytes)
        put("completed_bytes", completedBytes)
        put("sha256", sha256)
        put("error_code", errorCode)
        put("state", state.name)
    }

    private fun Cursor.toOperation(): FileOperation = FileOperation(
        id = getString(getColumnIndexOrThrow("id")),
        type = FileOperationType.valueOf(getString(getColumnIndexOrThrow("type"))),
        items = emptyList(),
        conflictPolicy = ConflictPolicy.valueOf(getString(getColumnIndexOrThrow("conflict_policy"))),
        state = OperationState.valueOf(getString(getColumnIndexOrThrow("state"))),
        createdAtMillis = getLong(getColumnIndexOrThrow("created_at_millis")),
        updatedAtMillis = getLong(getColumnIndexOrThrow("updated_at_millis")),
        destination = getString(getColumnIndexOrThrow("destination"))?.let(Uri::parse),
    )

    private fun Cursor.toItem(): OperationItem {
        val expectedBytesIndex = getColumnIndexOrThrow("expected_bytes")
        return OperationItem(
            id = getString(getColumnIndexOrThrow("id")),
            source = Uri.parse(getString(getColumnIndexOrThrow("source_uri"))),
            destination = getString(getColumnIndexOrThrow("destination_parent_uri"))?.let(Uri::parse),
            displayName = getString(getColumnIndexOrThrow("display_name")),
            expectedBytes = if (isNull(expectedBytesIndex)) null else getLong(expectedBytesIndex),
            completedBytes = getLong(getColumnIndexOrThrow("completed_bytes")),
            state = OperationState.valueOf(getString(getColumnIndexOrThrow("state"))),
            errorCode = getString(getColumnIndexOrThrow("error_code")),
            stagingUri = getString(getColumnIndexOrThrow("staging_uri"))?.let(Uri::parse),
            finalUri = getString(getColumnIndexOrThrow("final_uri"))?.let(Uri::parse),
            sha256 = getString(getColumnIndexOrThrow("sha256")),
        )
    }
}
