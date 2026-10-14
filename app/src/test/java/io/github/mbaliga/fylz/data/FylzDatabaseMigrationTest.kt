package io.github.mbaliga.fylz.data

import android.content.Context
import android.net.Uri
import io.github.mbaliga.fylz.operations.ConflictPolicy
import io.github.mbaliga.fylz.operations.FileOperation
import io.github.mbaliga.fylz.operations.FileOperationType
import io.github.mbaliga.fylz.operations.OperationItem
import io.github.mbaliga.fylz.operations.OperationState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P1.1: [FylzDatabase] migrates the SharedPreferences-encoded journal
 * [io.github.mbaliga.fylz.operations.OperationJournal] used to write directly, into the database,
 * exactly once -- inside [android.database.sqlite.SQLiteOpenHelper.onCreate], which only ever
 * runs the first time a given install creates the database file. This writes that same legacy
 * encoding by hand (mirroring the exact shape the pre-P1.1 journal wrote) and confirms both the
 * round-trip and the "once" half of "migrate once, idempotently".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FylzDatabaseMigrationTest {

    @Test
    fun `migrates the legacy SharedPreferences journal into the database exactly once`() {
        val context = RuntimeEnvironment.getApplication()
        val legacyOperation = FileOperation(
            id = "legacy-op",
            type = FileOperationType.COPY,
            items = listOf(
                OperationItem(
                    id = "legacy-item",
                    source = Uri.parse("content://legacy/source"),
                    destination = Uri.parse("content://legacy/dest"),
                    displayName = "photo.jpg",
                    expectedBytes = 1024L,
                    completedBytes = 512L,
                    state = OperationState.RUNNING,
                    stagingUri = Uri.parse("content://legacy/.fylz-part-x"),
                ),
            ),
            conflictPolicy = ConflictPolicy.REPLACE,
            state = OperationState.RUNNING,
            createdAtMillis = 1_000L,
            updatedAtMillis = 2_000L,
        )
        writeLegacyJournal(context, listOf(legacyOperation))

        val migrated = OperationsDao.list(FylzDatabase(context).writableDatabase)

        assertEquals(1, migrated.size)
        val result = migrated.single()
        assertEquals(legacyOperation.id, result.id)
        assertEquals(legacyOperation.type, result.type)
        assertEquals(legacyOperation.conflictPolicy, result.conflictPolicy)
        assertEquals(legacyOperation.state, result.state)
        assertEquals(legacyOperation.createdAtMillis, result.createdAtMillis)
        assertEquals(legacyOperation.updatedAtMillis, result.updatedAtMillis)
        assertEquals(legacyOperation.items, result.items)

        val preferences = context.getSharedPreferences(
            FylzDatabase.LEGACY_PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        assertNull("the migrated blob is cleared, not left around meaning nothing", preferences.getString(FylzDatabase.LEGACY_RECORDS_KEY, null))

        // A second launch (a second FylzDatabase against the same, now-existing file) finds
        // nothing left to migrate -- onCreate doesn't run again -- and the row isn't duplicated.
        val afterSecondOpen = OperationsDao.list(FylzDatabase(context).writableDatabase)
        assertEquals(1, afterSecondOpen.size)
    }

    private fun writeLegacyJournal(context: Context, operations: List<FileOperation>) {
        val root = JSONArray()
        operations.forEach { operation ->
            val items = JSONArray()
            operation.items.forEach { item ->
                items.put(
                    JSONObject()
                        .put("id", item.id)
                        .put("source", item.source.toString())
                        .put("destination", item.destination?.toString())
                        .put("displayName", item.displayName)
                        .put("expectedBytes", item.expectedBytes)
                        .put("completedBytes", item.completedBytes)
                        .put("state", item.state.name)
                        .put("errorCode", item.errorCode)
                        .put("stagingUri", item.stagingUri?.toString()),
                )
            }
            root.put(
                JSONObject()
                    .put("id", operation.id)
                    .put("type", operation.type.name)
                    .put("conflictPolicy", operation.conflictPolicy.name)
                    .put("state", operation.state.name)
                    .put("createdAtMillis", operation.createdAtMillis)
                    .put("updatedAtMillis", operation.updatedAtMillis)
                    .put("items", items),
            )
        }
        context.getSharedPreferences(FylzDatabase.LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(FylzDatabase.LEGACY_RECORDS_KEY, root.toString())
            .commit()
    }
}
