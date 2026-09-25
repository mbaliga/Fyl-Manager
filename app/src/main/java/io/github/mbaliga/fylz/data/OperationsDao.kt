package io.github.mbaliga.fylz.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import io.github.mbaliga.fylz.decoder.ArchiveLimits
import io.github.mbaliga.fylz.operations.ConflictPolicy
import io.github.mbaliga.fylz.operations.ExtractLayout
import io.github.mbaliga.fylz.operations.ExtractPlan
import io.github.mbaliga.fylz.operations.ExtractPlanItem
import io.github.mbaliga.fylz.operations.FileOperation
import io.github.mbaliga.fylz.operations.FileOperationType
import io.github.mbaliga.fylz.operations.OperationItem
import io.github.mbaliga.fylz.operations.OperationState
import io.github.mbaliga.fylz.operations.OrdinalBitmap

/**
 * Hand-written DAO (A4: plain SQLite, no Room) backing [FylzDatabase]'s `operations` and
 * `operation_items` tables. Every write replaces an operation's full item set inside one
 * transaction -- items have no independent identity worth diffing against, and a whole
 * [FileOperation] is always what a caller has in hand to persist. M3.4 adds the narrower writes an
 * extraction's worker needs -- a claim that is a conditional state change, a single-row item
 * update, a cancel flag -- and the three `extract_*` tables, deleted wherever an operation is.
 */
internal object OperationsDao {
    private const val MAX_RECORDS = 200

    const val TABLE_EXTRACT_PLANS = "extract_plans"
    const val TABLE_EXTRACT_PLAN_ITEMS = "extract_plan_items"
    const val TABLE_EXTRACT_ENTRY_DIGESTS = "extract_entry_digests"

    private val FINISHED_STATES = listOf(
        OperationState.SUCCEEDED,
        OperationState.FAILED,
        OperationState.PARTIAL,
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
            deleteOperationRows(db, id)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Every row of one operation: its items, its extraction plan and digests, itself. */
    private fun deleteOperationRows(db: SQLiteDatabase, id: String) {
        db.delete("operation_items", "operation_id = ?", arrayOf(id))
        db.delete(TABLE_EXTRACT_PLAN_ITEMS, "operation_id = ?", arrayOf(id))
        db.delete(TABLE_EXTRACT_ENTRY_DIGESTS, "operation_id = ?", arrayOf(id))
        db.delete(TABLE_EXTRACT_PLANS, "operation_id = ?", arrayOf(id))
        db.delete("operations", "id = ?", arrayOf(id))
    }

    // ------------------------------------------------------------------ M3.4: extraction plans

    /** The operation and its plan in one transaction (design section 2.2's atomic write). */
    fun putWithExtractPlan(db: SQLiteDatabase, operation: FileOperation, plan: ExtractPlan) {
        require(plan.operationId == operation.id) { "plan ${plan.operationId} is not operation ${operation.id}'s" }
        db.beginTransaction()
        try {
            put(db, operation)
            db.delete(TABLE_EXTRACT_PLAN_ITEMS, "operation_id = ?", arrayOf(operation.id))
            db.delete(TABLE_EXTRACT_ENTRY_DIGESTS, "operation_id = ?", arrayOf(operation.id))
            db.delete(TABLE_EXTRACT_PLANS, "operation_id = ?", arrayOf(operation.id))
            db.insertOrThrow(
                TABLE_EXTRACT_PLANS,
                null,
                ContentValues().apply {
                    put("operation_id", plan.operationId)
                    put("archive_uri", plan.archiveUri.toString())
                    put("catalog_key", plan.catalogKey)
                    put("layout", plan.layout.name)
                    put("folder_name", plan.folderName)
                    put("ordinals", plan.ordinals.toByteArray())
                    put("limits_json", plan.limits.toJson())
                    put("consent", if (plan.consent) 1 else 0)
                    put("sanitize", if (plan.sanitize) 1 else 0)
                    put("cancel_requested", if (plan.cancelRequested) 1 else 0)
                },
            )
            plan.items.forEach { item ->
                db.insertOrThrow(
                    TABLE_EXTRACT_PLAN_ITEMS,
                    null,
                    ContentValues().apply {
                        put("operation_id", plan.operationId)
                        put("item_index", item.itemIndex)
                        put("root_path", item.rootPath)
                        put("requested_name", item.requestedName)
                        put("conflict_policy", item.conflictPolicy.name)
                        put("name_override", item.nameOverride)
                    },
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun extractPlan(db: SQLiteDatabase, operationId: String): ExtractPlan? {
        val plan = db.rawQuery("SELECT * FROM $TABLE_EXTRACT_PLANS WHERE operation_id = ?", arrayOf(operationId)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            ExtractPlan(
                operationId = operationId,
                archiveUri = Uri.parse(cursor.getString(cursor.getColumnIndexOrThrow("archive_uri"))),
                catalogKey = cursor.getString(cursor.getColumnIndexOrThrow("catalog_key")),
                layout = ExtractLayout.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("layout"))),
                folderName = cursor.getString(cursor.getColumnIndexOrThrow("folder_name")),
                ordinals = OrdinalBitmap.fromByteArray(cursor.getBlob(cursor.getColumnIndexOrThrow("ordinals"))),
                limits = ArchiveLimits.fromJson(cursor.getString(cursor.getColumnIndexOrThrow("limits_json"))),
                consent = cursor.getInt(cursor.getColumnIndexOrThrow("consent")) != 0,
                sanitize = cursor.getInt(cursor.getColumnIndexOrThrow("sanitize")) != 0,
                cancelRequested = cursor.getInt(cursor.getColumnIndexOrThrow("cancel_requested")) != 0,
                items = emptyList(),
            )
        }
        val items = db.rawQuery(
            "SELECT * FROM $TABLE_EXTRACT_PLAN_ITEMS WHERE operation_id = ? ORDER BY item_index ASC",
            arrayOf(operationId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ExtractPlanItem(
                            itemIndex = cursor.getInt(cursor.getColumnIndexOrThrow("item_index")),
                            rootPath = cursor.getString(cursor.getColumnIndexOrThrow("root_path")),
                            requestedName = cursor.getString(cursor.getColumnIndexOrThrow("requested_name")),
                            conflictPolicy = ConflictPolicy.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("conflict_policy"))),
                            nameOverride = cursor.getString(cursor.getColumnIndexOrThrow("name_override")),
                        ),
                    )
                }
            }
        }
        return plan.copy(items = items)
    }

    /** Whether an extraction plan exists for [operationId] (a planned EXTRACT, not a legacy one). */
    fun hasExtractPlan(db: SQLiteDatabase, operationId: String): Boolean =
        db.rawQuery("SELECT 1 FROM $TABLE_EXTRACT_PLANS WHERE operation_id = ?", arrayOf(operationId)).use { it.moveToFirst() }

    /**
     * The worker's claim (design section 2.3 step 1): `from -> RUNNING` on the operation and on
     * every item still in one of [from], in one transaction; `false` when the row was not in a
     * claimable state (another run has it, or it already ended). Also clears a stale cancel flag
     * left by an earlier run that was cancelled while queued and then retried.
     */
    fun claimExtract(db: SQLiteDatabase, operationId: String, from: Set<OperationState>, nowMillis: Long): Boolean {
        db.beginTransaction()
        try {
            val placeholders = from.joinToString(",") { "?" }
            val args = arrayOf(OperationState.RUNNING.name, nowMillis.toString(), operationId) + from.map { it.name }
            val updated = db.compileStatement(
                "UPDATE operations SET state = ?, updated_at_millis = ? WHERE id = ? AND state IN ($placeholders)",
            ).use { statement ->
                args.forEachIndexed { index, value -> statement.bindString(index + 1, value) }
                statement.executeUpdateDelete()
            }
            if (updated != 1) return false
            val itemArgs = arrayOf(OperationState.RUNNING.name, operationId) + from.map { it.name } + OperationState.QUEUED.name
            db.compileStatement(
                "UPDATE operation_items SET state = ?, error_code = NULL WHERE operation_id = ? AND state IN ($placeholders, ?)",
            ).use { statement ->
                itemArgs.forEachIndexed { index, value -> statement.bindString(index + 1, value) }
                statement.executeUpdateDelete()
            }
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    /** `from -> to` only if the row is still in [from] (recovery's conditional update): `true` when it changed. */
    fun updateOperationStateIf(db: SQLiteDatabase, operationId: String, from: OperationState, to: OperationState, nowMillis: Long): Boolean =
        db.compileStatement("UPDATE operations SET state = ?, updated_at_millis = ? WHERE id = ? AND state = ?").use { statement ->
            statement.bindString(1, to.name)
            statement.bindString(2, nowMillis.toString())
            statement.bindString(3, operationId)
            statement.bindString(4, from.name)
            statement.executeUpdateDelete() == 1
        }

    /** One item row rewritten in place (by the item's own id), and the operation's `updated_at_millis`. */
    fun updateItem(db: SQLiteDatabase, operationId: String, item: OperationItem, nowMillis: Long) {
        db.beginTransaction()
        try {
            db.update(
                "operation_items",
                ContentValues().apply {
                    put("destination_parent_uri", item.destination?.toString())
                    put("staging_uri", item.stagingUri?.toString())
                    put("final_uri", item.finalUri?.toString())
                    put("display_name", item.displayName)
                    put("expected_bytes", item.expectedBytes)
                    put("completed_bytes", item.completedBytes)
                    put("sha256", item.sha256)
                    put("error_code", item.errorCode)
                    put("state", item.state.name)
                },
                "id = ? AND operation_id = ?",
                arrayOf(item.id, operationId),
            )
            db.update("operations", ContentValues().apply { put("updated_at_millis", nowMillis) }, "id = ?", arrayOf(operationId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** The operation's own state and timestamp, items untouched. */
    fun updateOperationState(db: SQLiteDatabase, operationId: String, state: OperationState, nowMillis: Long) {
        db.update(
            "operations",
            ContentValues().apply {
                put("state", state.name)
                put("updated_at_millis", nowMillis)
            },
            "id = ?",
            arrayOf(operationId),
        )
    }

    fun setCancelRequested(db: SQLiteDatabase, operationId: String, requested: Boolean = true) {
        db.update(
            TABLE_EXTRACT_PLANS,
            ContentValues().apply { put("cancel_requested", if (requested) 1 else 0) },
            "operation_id = ?",
            arrayOf(operationId),
        )
    }

    fun isCancelRequested(db: SQLiteDatabase, operationId: String): Boolean =
        db.rawQuery("SELECT cancel_requested FROM $TABLE_EXTRACT_PLANS WHERE operation_id = ?", arrayOf(operationId)).use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) != 0
        }

    /**
     * The retry of a planned extraction (design section 2.3 step 9): `FAILED | PARTIAL | CANCELLED |
     * NEEDS_ATTENTION | INTERRUPTED -> QUEUED`, every item not `SUCCEEDED` back to `QUEUED` with its
     * error and staging cleared, the cancel flag cleared -- in one transaction. `false` when the
     * operation has no plan or is not in a retryable state.
     */
    fun retryExtract(db: SQLiteDatabase, operationId: String, nowMillis: Long): Boolean {
        db.beginTransaction()
        try {
            if (!hasExtractPlan(db, operationId)) return false
            val retryable = listOf(
                OperationState.FAILED, OperationState.PARTIAL, OperationState.CANCELLED,
                OperationState.NEEDS_ATTENTION, OperationState.INTERRUPTED,
            )
            val placeholders = retryable.joinToString(",") { "?" }
            val updated = db.compileStatement(
                "UPDATE operations SET state = ?, updated_at_millis = ? WHERE id = ? AND state IN ($placeholders)",
            ).use { statement ->
                val args = arrayOf(OperationState.QUEUED.name, nowMillis.toString(), operationId) + retryable.map { it.name }
                args.forEachIndexed { index, value -> statement.bindString(index + 1, value) }
                statement.executeUpdateDelete()
            }
            if (updated != 1) return false
            db.compileStatement(
                "UPDATE operation_items SET state = ?, error_code = NULL, staging_uri = NULL, completed_bytes = 0 " +
                    "WHERE operation_id = ? AND state != ?",
            ).use { statement ->
                statement.bindString(1, OperationState.QUEUED.name)
                statement.bindString(2, operationId)
                statement.bindString(3, OperationState.SUCCEEDED.name)
                statement.executeUpdateDelete()
            }
            setCancelRequested(db, operationId, false)
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    fun putEntryDigests(db: SQLiteDatabase, operationId: String, digests: Map<Int, String>) {
        if (digests.isEmpty()) return
        db.beginTransaction()
        try {
            digests.forEach { (ordinal, sha256) ->
                db.insertWithOnConflict(
                    TABLE_EXTRACT_ENTRY_DIGESTS,
                    null,
                    ContentValues().apply {
                        put("operation_id", operationId)
                        put("ordinal", ordinal)
                        put("sha256", sha256)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun entryDigests(db: SQLiteDatabase, operationId: String): Map<Int, String> =
        db.rawQuery(
            "SELECT ordinal, sha256 FROM $TABLE_EXTRACT_ENTRY_DIGESTS WHERE operation_id = ? ORDER BY ordinal ASC",
            arrayOf(operationId),
        ).use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getInt(0), cursor.getString(1)) }
        }

    fun clearFinished(db: SQLiteDatabase) {
        val placeholders = FINISHED_STATES.joinToString(",") { "?" }
        val args = FINISHED_STATES.map { it.name }.toTypedArray()
        db.beginTransaction()
        try {
            val finished = db.rawQuery("SELECT id FROM operations WHERE state IN ($placeholders)", args).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            finished.forEach { deleteOperationRows(db, it) }
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
            val stale = buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            stale.forEach { deleteOperationRows(db, it) }
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
