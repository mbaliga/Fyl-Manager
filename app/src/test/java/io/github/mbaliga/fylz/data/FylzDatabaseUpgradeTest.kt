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
 * M3.4's schema bump (v2 -> v3, additive), M3.5's (v3 -> v4, additive) and M3.6's (v4 -> v5,
 * additive): a real `onUpgrade` over a database file that an older helper created and filled --
 * the three `extract_*` tables (v3), the three `create_*` tables (v4) and
 * `create_plans.replace_original_uri` (v5) appear, the operation rows survive untouched, and a
 * fresh install gets every table and column straight from `onCreate`.
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

        FylzDatabase(context).use { upgraded ->
            val db = upgraded.writableDatabase
            assertEquals(FylzDatabase.DATABASE_VERSION, db.version)
            assertEquals(5, db.version)
            val names = tables(db)
            listOf(
                OperationsDao.TABLE_EXTRACT_PLANS, OperationsDao.TABLE_EXTRACT_PLAN_ITEMS, OperationsDao.TABLE_EXTRACT_ENTRY_DIGESTS,
                OperationsDao.TABLE_CREATE_PLANS, OperationsDao.TABLE_CREATE_PLAN_ITEMS, OperationsDao.TABLE_CREATE_MANIFEST,
            ).forEach { table ->
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

    /** The v3 shape M3.4 landed: v2's two tables plus the three `extract_*` ones, nothing from M3.5. */
    private class V3Helper(private val ctx: Context) : SQLiteOpenHelper(ctx, FylzDatabase.DATABASE_NAME, null, 3) {
        override fun onCreate(db: SQLiteDatabase) {
            V2Helper(ctx).onCreate(db)
            db.execSQL(
                """
                CREATE TABLE ${OperationsDao.TABLE_EXTRACT_PLANS} (
                    operation_id TEXT PRIMARY KEY NOT NULL,
                    archive_uri TEXT NOT NULL,
                    catalog_key TEXT NOT NULL,
                    layout TEXT NOT NULL,
                    folder_name TEXT,
                    ordinals BLOB NOT NULL,
                    limits_json TEXT NOT NULL,
                    consent INTEGER NOT NULL,
                    sanitize INTEGER NOT NULL,
                    cancel_requested INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE ${OperationsDao.TABLE_EXTRACT_PLAN_ITEMS} (
                    operation_id TEXT NOT NULL,
                    item_index INTEGER NOT NULL,
                    root_path TEXT NOT NULL,
                    requested_name TEXT NOT NULL,
                    conflict_policy TEXT NOT NULL,
                    name_override TEXT,
                    PRIMARY KEY (operation_id, item_index)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE ${OperationsDao.TABLE_EXTRACT_ENTRY_DIGESTS} (
                    operation_id TEXT NOT NULL,
                    ordinal INTEGER NOT NULL,
                    sha256 TEXT NOT NULL,
                    PRIMARY KEY (operation_id, ordinal)
                )
                """.trimIndent(),
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    @Test
    fun `a v3 database upgrades to v4 with the create tables added and its operations and extract plan intact`() {
        val existing = FileOperation(id = "op-v3", type = FileOperationType.MOVE, items = listOf(OperationItem(id = "item-v3", source = Uri.parse("content://x/src"), destination = Uri.parse("content://x/dst"), displayName = "b.txt", state = OperationState.FAILED)), state = OperationState.FAILED)
        val plan = ExtractPlan("op-v3", Uri.parse("content://io.github.mbaliga.fylz.archives/document/root"), "key", ExtractLayout.HERE, null, OrdinalBitmap.of(0, 1), ArchiveLimits.forExtraction(null, false), consent = false, sanitize = false, items = listOf(ExtractPlanItem(0, "a", "a", ConflictPolicy.SKIP, null)))
        V3Helper(context).use { v3 ->
            val db = v3.writableDatabase
            assertEquals(3, db.version)
            OperationsDao.put(db, existing)
            OperationsDao.putWithExtractPlan(db, existing, plan)
            assertEquals(
                setOf("operations", "operation_items", OperationsDao.TABLE_EXTRACT_PLANS, OperationsDao.TABLE_EXTRACT_PLAN_ITEMS, OperationsDao.TABLE_EXTRACT_ENTRY_DIGESTS),
                tables(db) - setOf("android_metadata", "sqlite_sequence"),
            )
        }

        FylzDatabase(context).use { upgraded ->
            val db = upgraded.writableDatabase
            assertEquals(5, db.version)
            val names = tables(db)
            listOf(OperationsDao.TABLE_CREATE_PLANS, OperationsDao.TABLE_CREATE_PLAN_ITEMS, OperationsDao.TABLE_CREATE_MANIFEST).forEach { table ->
                assertEquals("$table exists after the upgrade", true, table in names)
            }
            assertEquals(plan, OperationsDao.extractPlan(db, "op-v3"))
            assertNotNull(OperationsDao.find(db, "op-v3"))
            // The new tables work: a create plan round trips against the upgraded file.
            val operation = FileOperation(id = "op-y", type = FileOperationType.ARCHIVE, items = listOf(OperationItem(source = Uri.parse("content://x/src"), destination = Uri.parse("content://x/dst"), displayName = "out")), destination = Uri.parse("content://x/dst"))
            val createPlan = io.github.mbaliga.fylz.operations.CompressPlan("op-y", io.github.mbaliga.fylz.operations.CompressFormat.ZIP, 6, io.github.mbaliga.fylz.operations.SplitSize.Off, relativeToSelection = true, archiveName = "out.zip", destinationUri = Uri.parse("content://x/dst"), totalEstimate = 100L, entryCount = 1, conflictPolicy = ConflictPolicy.SKIP)
            val manifest = listOf(io.github.mbaliga.fylz.operations.CompressManifestEntry(0, false, "out.zip", Uri.parse("content://x/src"), 0L, needsSpooling = false))
            OperationsDao.putWithCreatePlan(db, operation, createPlan, manifest)
            assertEquals(createPlan, OperationsDao.createPlan(db, "op-y"))
            assertEquals(manifest, OperationsDao.createManifest(db, "op-y"))
        }
    }

    @Test
    fun `a fresh database is created at v5 with the extract and create tables`() {
        FylzDatabase(context).use { fresh ->
            val db = fresh.writableDatabase
            assertEquals(5, db.version)
            val names = tables(db)
            listOf(
                OperationsDao.TABLE_EXTRACT_PLANS, OperationsDao.TABLE_EXTRACT_PLAN_ITEMS, OperationsDao.TABLE_EXTRACT_ENTRY_DIGESTS,
                OperationsDao.TABLE_CREATE_PLANS, OperationsDao.TABLE_CREATE_PLAN_ITEMS, OperationsDao.TABLE_CREATE_MANIFEST,
                "operations", "operation_items",
            ).forEach { table ->
                assertEquals("$table exists", true, table in names)
            }
            // M3.6's own column is present from a fresh install too, not only after an upgrade.
            db.rawQuery("SELECT replace_original_uri FROM ${OperationsDao.TABLE_CREATE_PLANS} LIMIT 0", null).use { }
        }
    }

    /** The v4 shape M3.5 landed: everything up to and including `create_plans` with no
     * `replace_original_uri` column yet. */
    private class V4Helper(private val ctx: Context) : SQLiteOpenHelper(ctx, FylzDatabase.DATABASE_NAME, null, 4) {
        override fun onCreate(db: SQLiteDatabase) {
            V3Helper(ctx).onCreate(db)
            db.execSQL(
                """
                CREATE TABLE ${OperationsDao.TABLE_CREATE_PLANS} (
                    operation_id TEXT PRIMARY KEY NOT NULL,
                    format TEXT NOT NULL,
                    level INTEGER NOT NULL,
                    split_bytes INTEGER,
                    relative INTEGER NOT NULL,
                    archive_name TEXT NOT NULL,
                    destination_uri TEXT,
                    total_estimate INTEGER,
                    entry_count INTEGER NOT NULL,
                    conflict_policy TEXT NOT NULL,
                    name_override TEXT,
                    cancel_requested INTEGER NOT NULL DEFAULT 0,
                    restart_count INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE ${OperationsDao.TABLE_CREATE_PLAN_ITEMS} (
                    operation_id TEXT NOT NULL,
                    item_index INTEGER NOT NULL,
                    requested_name TEXT NOT NULL,
                    staging_uri TEXT,
                    sha256 TEXT,
                    bytes_written INTEGER NOT NULL DEFAULT 0,
                    state TEXT NOT NULL,
                    PRIMARY KEY (operation_id, item_index)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE ${OperationsDao.TABLE_CREATE_MANIFEST} (
                    operation_id TEXT NOT NULL,
                    ordinal INTEGER NOT NULL,
                    is_directory INTEGER NOT NULL,
                    path TEXT NOT NULL,
                    source_uri TEXT NOT NULL,
                    mtime_millis INTEGER NOT NULL,
                    needs_spooling INTEGER NOT NULL,
                    spooled_path TEXT,
                    PRIMARY KEY (operation_id, ordinal)
                )
                """.trimIndent(),
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    @Test
    fun `a v4 database upgrades to v5 with replace_original_uri added and its create plan intact`() {
        // Written the way a pre-M3.6 app version actually would have: `OperationsDao` itself now
        // assumes the v5 shape everywhere, so this test's own "before" state is a raw insert
        // against the v4 table `V4Helper` creates, exactly as `V2Helper`/`V3Helper` above do for
        // their own tables.
        V4Helper(context).use { v4 ->
            val db = v4.writableDatabase
            assertEquals(4, db.version)
            db.execSQL(
                """
                INSERT INTO ${OperationsDao.TABLE_CREATE_PLANS}
                (operation_id, format, level, split_bytes, relative, archive_name, destination_uri, total_estimate, entry_count, conflict_policy, name_override, cancel_requested, restart_count)
                VALUES ('op-v4', 'ZIP', 6, NULL, 1, 'out.zip', 'content://x/dst', 100, 1, 'SKIP', NULL, 0, 0)
                """.trimIndent(),
            )
        }

        FylzDatabase(context).use { upgraded ->
            val db = upgraded.writableDatabase
            assertEquals(5, db.version)
            val beforeEdit = OperationsDao.createPlan(db, "op-v4")
            assertNotNull(beforeEdit)
            assertEquals("a pre-M3.6 plan has no original to replace", null, beforeEdit!!.replaceOriginalUri)
            // The new column round-trips a real value too, not only its default null.
            db.execSQL("UPDATE ${OperationsDao.TABLE_CREATE_PLANS} SET replace_original_uri = 'content://x/original.zip' WHERE operation_id = 'op-v4'")
            assertEquals(Uri.parse("content://x/original.zip"), OperationsDao.createPlan(db, "op-v4")!!.replaceOriginalUri)
        }
    }
}
