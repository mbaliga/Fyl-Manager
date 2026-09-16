package io.github.mbaliga.fylz.operations

import android.content.Context
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private const val DAY_MILLIS = 24L * 60L * 60L * 1_000L

private fun record(itemId: String, recycledAtMillis: Long) = RecycleRecord(
    itemId = itemId,
    originalUri = Uri.parse("content://fylz/document/$itemId"),
    recycledUri = Uri.parse("content://fylz/document/trash-$itemId"),
    originalParentUri = null,
    originalDisplayName = "$itemId.txt",
    providerAuthority = "fylz",
    sizeBytes = 10L,
    recycledAtMillis = recycledAtMillis,
)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecycleBinRetentionPolicyTest {

    @Test
    fun `keep until emptied never selects anything, regardless of age`() {
        val now = 10_000L * DAY_MILLIS
        val ancient = record("a", recycledAtMillis = 0L)

        val expired = RecycleBinRetentionPolicy.expired(
            records = listOf(ancient),
            period = RecycleBinRetentionPeriod.KEEP_UNTIL_EMPTIED,
            nowMillis = now,
        )

        assertTrue(expired.isEmpty())
    }

    @Test
    fun `a record exactly at the window's edge is expired`() {
        val now = 1_000_000L
        val atCutoff = record("edge", recycledAtMillis = now - 7 * DAY_MILLIS)

        val expired = RecycleBinRetentionPolicy.expired(
            records = listOf(atCutoff),
            period = RecycleBinRetentionPeriod.SEVEN_DAYS,
            nowMillis = now,
        )

        assertEquals(listOf(atCutoff), expired)
    }

    @Test
    fun `a record inside the window survives`() {
        val now = 1_000_000L
        val recent = record("recent", recycledAtMillis = now - 6 * DAY_MILLIS)

        val expired = RecycleBinRetentionPolicy.expired(
            records = listOf(recent),
            period = RecycleBinRetentionPeriod.SEVEN_DAYS,
            nowMillis = now,
        )

        assertTrue(expired.isEmpty())
    }

    @Test
    fun `only the records past the configured window are selected`() {
        val now = 100 * DAY_MILLIS
        val old = record("old", recycledAtMillis = now - 40 * DAY_MILLIS)
        val young = record("young", recycledAtMillis = now - 10 * DAY_MILLIS)

        val expired = RecycleBinRetentionPolicy.expired(
            records = listOf(old, young),
            period = RecycleBinRetentionPeriod.THIRTY_DAYS,
            nowMillis = now,
        )

        assertEquals(listOf(old), expired)
    }

    @Test
    fun `describe prints the exact promised sentence per period`() {
        assertEquals(
            "Kept until you empty the bin -- nothing is removed automatically.",
            RecycleBinRetentionPeriod.KEEP_UNTIL_EMPTIED.describe(),
        )
        assertEquals(
            "Deleted files are removed automatically after 7 days.",
            RecycleBinRetentionPeriod.SEVEN_DAYS.describe(),
        )
        assertEquals(
            "Deleted files are removed automatically after 30 days.",
            RecycleBinRetentionPeriod.THIRTY_DAYS.describe(),
        )
        assertEquals(
            "Deleted files are removed automatically after 60 days.",
            RecycleBinRetentionPeriod.SIXTY_DAYS.describe(),
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecycleBinRetentionStoreTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private fun store() = RecycleBinRetentionStore(context)

    @Test
    fun `defaults to keep until emptied`() {
        assertEquals(RecycleBinRetentionPeriod.KEEP_UNTIL_EMPTIED, store().period())
    }

    @Test
    fun `period round-trips through the store`() {
        val store = store()
        store.setPeriod(RecycleBinRetentionPeriod.THIRTY_DAYS)

        assertEquals(RecycleBinRetentionPeriod.THIRTY_DAYS, store().period())
    }

    @Test
    fun `a corrupted stored value falls back to keep until emptied`() {
        val store = store()
        context.getSharedPreferences("fylz_recycle_retention", Context.MODE_PRIVATE)
            .edit().putString("period", "NOT_A_PERIOD").commit()

        assertEquals(RecycleBinRetentionPeriod.KEEP_UNTIL_EMPTIED, store.period())
    }
}
