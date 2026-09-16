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

/**
 * Pins the "one source of truth" half of the trash-reconciliation fix: [RecycleBinStore] is
 * durable SharedPreferences, not process-scoped state, so a second instance opened later --
 * standing in for the settings dialog opening after the sheet that recycled an item, or the app
 * restarting entirely -- must see exactly what the first instance wrote. The bug this guards
 * against was never in this store; it was a UI-side `mutableStateListOf` tracking which items
 * *this composition* had recycled and filtering the rich sheet down to just those, so a restart
 * (a fresh composition, an empty list) hid every record this store still has. Nothing here
 * should ever need a second, narrower index to agree with.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecycleBinStoreTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private fun record(itemId: String) = RecycleRecord(
        itemId = itemId,
        originalUri = Uri.parse("content://fylz/document/$itemId"),
        recycledUri = Uri.parse("content://fylz/document/trash-$itemId"),
        originalParentUri = null,
        originalDisplayName = "$itemId.txt",
        providerAuthority = "fylz",
        sizeBytes = 42L,
        recycledAtMillis = 1_000L,
    )

    @Test
    fun `a record written by one store instance is visible from a fresh instance`() {
        RecycleBinStore(context).put(record("a"))

        val reopened = RecycleBinStore(context)

        assertEquals(listOf("a"), reopened.list().map { it.itemId })
    }

    @Test
    fun `a fresh store instance starts with nothing to hide -- an empty list, not a stale one`() {
        assertTrue(RecycleBinStore(context).list().isEmpty())
    }

    @Test
    fun `every record is returned regardless of which instance recycled it`() {
        val first = RecycleBinStore(context)
        first.put(record("a"))
        val second = RecycleBinStore(context)
        second.put(record("b"))

        // Whichever instance a caller reads through next -- the trash sheet's, the settings
        // dialog's -- must see both records. There is exactly one manifest on disk.
        assertEquals(setOf("a", "b"), first.list().map { it.itemId }.toSet())
        assertEquals(setOf("a", "b"), second.list().map { it.itemId }.toSet())
    }
}
