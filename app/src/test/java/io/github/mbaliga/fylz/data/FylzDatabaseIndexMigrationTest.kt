package io.github.mbaliga.fylz.data

import android.content.Context
import io.github.mbaliga.fylz.index.IndexDao
import io.github.mbaliga.fylz.index.RuleField
import io.github.mbaliga.fylz.index.RuleOperator
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * P1.12: [FylzDatabase] migrates [io.github.mbaliga.fylz.index.LocalIndexStore]'s pre-P1.12 JSON
 * files (`filesDir/local-index/{files,scopes,collections,state}.json`) into the new `index_*`
 * tables, exactly once, the same way [FylzDatabaseMigrationTest] already proves for the operation
 * journal. Writes those JSON files by hand, in the exact shape the pre-P1.12 store wrote them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FylzDatabaseIndexMigrationTest {

    @Test
    fun `migrates the legacy JSON index files into the database exactly once`() {
        val context = RuntimeEnvironment.getApplication()
        val root = File(context.filesDir, "local-index").apply { mkdirs() }

        File(root, "files.json").writeText(
            JSONArray().put(
                JSONObject()
                    .put("uri", "content://docs/a")
                    .put("rootUri", "content://docs/root")
                    .put("name", "a.txt")
                    .put("mimeType", "text/plain")
                    .put("extension", "txt")
                    .put("sizeBytes", 100L)
                    .put("modifiedAtMillis", 1_000L)
                    .put("directory", false)
                    .put("tags", JSONArray(listOf("legacy")))
                    .put("indexedAtMillis", 2_000L),
            ).toString(),
        )
        File(root, "scopes.json").writeText(
            JSONArray().put(
                JSONObject()
                    .put("rootUri", "content://docs/root")
                    .put("displayName", "Docs")
                    .put("enabled", true),
            ).toString(),
        )
        File(root, "collections.json").writeText(
            JSONArray().put(
                JSONObject()
                    .put("id", "collection-1")
                    .put("name", "Text files")
                    .put("join", "ALL")
                    .put("createdAtMillis", 3_000L)
                    .put("updatedAtMillis", 4_000L)
                    .put(
                        "rules",
                        JSONArray().put(
                            JSONObject()
                                .put("field", "EXTENSION")
                                .put("operator", "EQUALS")
                                .put("value", "txt")
                                .put("negate", false),
                        ),
                    ),
            ).toString(),
        )
        File(root, "state.json").writeText(
            JSONObject()
                .put("paused", false)
                .put("lastStartedAtMillis", 5_000L)
                .put("lastCompletedAtMillis", 6_000L)
                .put("indexedFiles", 1)
                .put("truncated", false)
                .toString(),
        )

        val db = FylzDatabase(context).writableDatabase

        val files = IndexDao.files(db)
        assertEquals(1, files.size)
        assertEquals("content://docs/a", files.single().uri)
        assertEquals(setOf("legacy"), files.single().tags)

        val scopes = IndexDao.scopes(db)
        assertEquals(1, scopes.size)
        assertEquals("content://docs/root", scopes.single().rootUri)
        assertEquals("Docs", scopes.single().displayName)

        val collections = IndexDao.collections(db)
        assertEquals(1, collections.size)
        assertEquals("collection-1", collections.single().id)
        assertEquals(RuleField.EXTENSION, collections.single().rules.single().field)
        assertEquals(RuleOperator.EQUALS, collections.single().rules.single().operator)

        val state = IndexDao.state(db)
        assertEquals(6_000L, state.lastCompletedAtMillis)
        assertEquals(1, state.indexedFiles)

        assertFalse("the migrated JSON files are cleared, not left around meaning nothing", File(root, "files.json").exists())
        assertFalse(File(root, "scopes.json").exists())
        assertFalse(File(root, "collections.json").exists())
        assertFalse(File(root, "state.json").exists())

        // A second launch finds nothing left to migrate -- onCreate/onUpgrade don't run again --
        // and nothing is duplicated.
        val afterSecondOpen = IndexDao.files(FylzDatabase(context).writableDatabase)
        assertEquals(1, afterSecondOpen.size)
    }

    @Test
    fun `a fresh install with no legacy JSON files migrates nothing and starts empty`() {
        val context = RuntimeEnvironment.getApplication()
        val db = FylzDatabase(context).writableDatabase

        assertTrue(IndexDao.files(db).isEmpty())
        assertTrue(IndexDao.scopes(db).isEmpty())
        assertTrue(IndexDao.collections(db).isEmpty())
    }
}
