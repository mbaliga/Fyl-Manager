package io.github.mbaliga.fylz.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * M3.4's schema bump (v2 -> v3, additive): a real `onUpgrade` over a database file that a v2
 * helper created and filled -- the three `extract_*` tables appear, the operation rows survive
 * untouched, and a fresh install gets the same tables from `onCreate`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FylzDatabaseUpgradeTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    /** The v2 shape as P1.1/P1.12 wrote it: the two journal tables and the index tables' presence do not matter here. */
    private class V2Helper(context: Context) : SQLiteOpenHelper(context, FylzDatabase.DATABASE_NAME, null, 2) {
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
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private fun tables(db: SQLiteDatabase): Set<String> =
        db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    @Test
    fun `a v2 database upgrades to v3 with the extract tables added and its operations intact`() {
        val existing = FileOperation(
            id = "op-v2",
            type = FileOperationType.COPY,
            items = listOf(OperationItem(id = "item-v2", source = Uri.parse("content://x/src"), destination = Uri.parse("content://x/dst"), displayName = "a.txt", expectedBytes = 5L, completedBytes = 5L, state = OperationState.SUCCEEDED)),
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            state = OperationState.SUCCEEDED,
            createdAtMillis = 10L,
            updatedAtMillis = 20L,
        )
        V2Helper(context).use { v2 ->
            val db = v2.writableDatabase
            assertEquals(2, db.version)
            OperationsDao.put(db, existing)
            assertEquals(setOf("operations", "operation_items"), tables(db) - setOf("android_metadata", "sqlite_sequence"))
        }

        FylzDatabase(context).use { v3 ->
            val db = v3.writableDatabase
            assertEquals(FylzDatabase.DATABASE_VERSION, db.version)
            assertEquals(3, db.version)
            val names = tables(db)
            listOf(OperationsDao.TABLE_EXTRACT_PLANS, OperationsDao.TABLE_EXTRACT_PLAN_ITEMS, OperationsDao.TABLE_EXTRACT_ENTRY_DIGESTS).forEach { table ->
                assertEquals("$table exists after the upgrade", true, table in names)
            }
            assertEquals(listOf(existing), OperationsDao.list(db))
            // The new tables work: a plan round trips against the upgraded file.
            val operation = FileOperation(id = "op-x", type = FileOperationType.EXTRACT, items = listOf(OperationItem(source = Uri.parse("content://io.github.mbaliga.fylz.archives/document/abc"), destination = Uri.parse("content://x/dst"), displayName = "photos")), destination = Uri.parse("content://x/dst"))
            val plan = ExtractPlan("op-x", Uri.parse("content://io.github.mbaliga.fylz.archives/document/root"), "key", ExtractLayout.INTO_FOLDER, "photos", OrdinalBitmap.of(0, 2, 3), ArchiveLimits.forExtraction(null, false), consent = false, sanitize = true, items = listOf(ExtractPlanItem(0, "", "photos", ConflictPolicy.REPLACE, null)))
            OperationsDao.putWithExtractPlan(db, operation, plan)
            assertEquals(plan, OperationsDao.extractPlan(db, "op-x"))
            assertNotNull(OperationsDao.find(db, "op-v2"))
        }
    }

    @Test
    fun `a fresh database is created at v3 with the extract tables`() {
        FylzDatabase(context).use { fresh ->
            val db = fresh.writableDatabase
            assertEquals(3, db.version)
            val names = tables(db)
            listOf(OperationsDao.TABLE_EXTRACT_PLANS, OperationsDao.TABLE_EXTRACT_PLAN_ITEMS, OperationsDao.TABLE_EXTRACT_ENTRY_DIGESTS, "operations", "operation_items").forEach { table ->
                assertEquals("$table exists", true, table in names)
            }
        }
    }
}
