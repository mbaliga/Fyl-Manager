package io.github.mbaliga.fylz.storage

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [StorageUsageStore] is [io.github.mbaliga.fylz.canvas.CanvasLayoutStore]-modelled; these tests
 * pin the same contract that store's own test pins -- round trip, and the corrupted-current-falls-
 * back-to-backup recovery -- plus the "no scan has ever run" case this store alone needs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StorageUsageStoreTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private fun store() = StorageUsageStore(context)

    @Test
    fun `no snapshot yet reports null, not a snapshot of zeros`() {
        assertNull(store().snapshot())
    }

    @Test
    fun `a written snapshot survives a fresh store instance over the same preferences`() {
        val snapshot = StorageUsageSnapshot(
            scannedAtMillis = 1_000L,
            kindBytes = mapOf(StorageKind.PHOTOS to 500L, StorageKind.OTHER to 10L),
        )

        store().write(snapshot)

        assertEquals(snapshot, store().snapshot())
    }

    @Test
    fun `writing again replaces the snapshot rather than merging it`() {
        val store = store()
        store.write(StorageUsageSnapshot(1_000L, mapOf(StorageKind.PHOTOS to 500L)))

        store.write(StorageUsageSnapshot(2_000L, mapOf(StorageKind.VIDEOS to 900L)))

        val latest = store.snapshot()
        assertEquals(2_000L, latest?.scannedAtMillis)
        assertEquals(900L, latest?.bytesFor(StorageKind.VIDEOS))
        assertEquals(0L, latest?.bytesFor(StorageKind.PHOTOS))
    }

    @Test
    fun `bytesFor a kind the scan never saw is zero, not a missing-key crash`() {
        store().write(StorageUsageSnapshot(1_000L, mapOf(StorageKind.PHOTOS to 500L)))

        assertEquals(0L, store().snapshot()!!.bytesFor(StorageKind.ARCHIVES))
    }

    @Test
    fun `truncated survives the round trip`() {
        store().write(StorageUsageSnapshot(1_000L, emptyMap(), truncated = true))

        assertTrue(store().snapshot()!!.truncated)
    }

    @Test
    fun `a corrupted current snapshot falls back to the last known good backup`() {
        val store = store()
        store.write(StorageUsageSnapshot(1_000L, mapOf(StorageKind.PHOTOS to 500L)))
        // This second write is what promotes the first write's payload into the backup slot.
        store.write(StorageUsageSnapshot(2_000L, mapOf(StorageKind.PHOTOS to 600L)))

        context.getSharedPreferences("fylz_storage_usage", Context.MODE_PRIVATE)
            .edit()
            .putString("snapshot", "{not json[")
            .commit()

        val recovered = store().snapshot()
        assertEquals(1_000L, recovered?.scannedAtMillis)
        assertEquals(500L, recovered?.bytesFor(StorageKind.PHOTOS))
    }

    @Test
    fun `an unrecognized kind key in a stored snapshot is skipped rather than crashing decode`() {
        context.getSharedPreferences("fylz_storage_usage", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "snapshot",
                """{"schemaVersion":1,"scannedAtMillis":1000,"truncated":false,"kindBytes":{"PHOTOS":500,"FUTURE_KIND":10}}""",
            )
            .commit()

        val snapshot = store().snapshot()

        assertEquals(500L, snapshot?.bytesFor(StorageKind.PHOTOS))
        assertEquals(1, snapshot?.kindBytes?.size)
    }

    // ---- schema v1 -> v2: largestFiles -----------------------------------------------------

    @Test
    fun `a schema-v1 payload with no largestFiles key still decodes, with an empty list`() {
        context.getSharedPreferences("fylz_storage_usage", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "snapshot",
                """{"schemaVersion":1,"scannedAtMillis":1000,"truncated":false,"kindBytes":{"PHOTOS":500}}""",
            )
            .commit()

        val snapshot = store().snapshot()

        assertEquals(500L, snapshot?.bytesFor(StorageKind.PHOTOS))
        assertEquals(emptyList<LargeFileFact>(), snapshot?.largestFiles)
    }

    @Test
    fun `largestFiles round-trips through a fresh store instance`() {
        val snapshot = StorageUsageSnapshot(
            scannedAtMillis = 1_000L,
            kindBytes = mapOf(StorageKind.PHOTOS to 500L),
            largestFiles = listOf(
                LargeFileFact("content://fylz/doc/a", "big.mp4", 900_000L),
                LargeFileFact("content://fylz/doc/b", "medium.zip", 400_000L),
            ),
        )

        store().write(snapshot)

        assertEquals(snapshot, store().snapshot())
    }

    @Test
    fun `writing a snapshot always encodes schema v2`() {
        store().write(StorageUsageSnapshot(1_000L, mapOf(StorageKind.PHOTOS to 500L)))

        val raw = context.getSharedPreferences("fylz_storage_usage", Context.MODE_PRIVATE).getString("snapshot", null)

        assertEquals(2, org.json.JSONObject(raw!!).getInt("schemaVersion"))
    }

    @Test
    fun `a malformed largestFiles record is skipped without sinking the rest of the snapshot`() {
        context.getSharedPreferences("fylz_storage_usage", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "snapshot",
                """
                {"schemaVersion":2,"scannedAtMillis":1000,"truncated":false,"kindBytes":{"PHOTOS":500},
                 "largestFiles":[
                   {"uriString":"content://a","displayName":"good.mp4","sizeBytes":100},
                   {"uriString":"content://b"}
                 ]}
                """.trimIndent(),
            )
            .commit()

        val snapshot = store().snapshot()

        assertEquals(listOf(LargeFileFact("content://a", "good.mp4", 100L)), snapshot?.largestFiles)
    }
}
