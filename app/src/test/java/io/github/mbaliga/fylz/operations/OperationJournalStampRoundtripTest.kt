package io.github.mbaliga.fylz.operations

import android.content.Context
import android.net.Uri
import io.github.mbaliga.fylz.core.model.VersionStamp
import io.github.mbaliga.fylz.core.operations.FileOperation
import io.github.mbaliga.fylz.core.operations.FileOperationType
import io.github.mbaliga.fylz.core.operations.OperationItem
import io.github.mbaliga.fylz.core.operations.OperationState
import io.github.mbaliga.fylz.storage.toItemRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Schema-v3 wire-format coverage: the optional version stamps a MOVE item journals at
 * delete-failure time must survive persist→decode intact, and their ABSENCE (every v1/v2
 * record) must decode to null rather than to an invented value — that null is what routes
 * `MoveCleanupPolicy` to its legacy fallback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OperationJournalStampRoundtripTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private fun ref(tail: String) =
        Uri.parse("content://fylz.test/tree/root/document/$tail").toItemRef()

    private fun operation(item: OperationItem) = FileOperation(
        id = "op-1",
        type = FileOperationType.MOVE,
        items = listOf(item),
        state = OperationState.NEEDS_ATTENTION,
        createdAtMillis = 100,
        updatedAtMillis = 110,
    )

    @Test
    fun `composite stamps roundtrip with partial fields preserved`() {
        val journal = OperationJournal(context)
        journal.put(
            operation(
                OperationItem(
                    id = "item-1",
                    source = ref("src"),
                    destination = ref("dst"),
                    displayName = "report.pdf",
                    state = OperationState.NEEDS_ATTENTION,
                    errorCode = "MOVE_SOURCE_DELETE_PENDING",
                    sourceStamp = VersionStamp.Composite(sizeBytes = 42L, modifiedAtMillis = 1_000L),
                    // A provider that reports size but declines the timestamp -- the null field
                    // must come back null, not 0.
                    destinationStamp = VersionStamp.Composite(sizeBytes = 42L, modifiedAtMillis = null),
                ),
            ),
        )

        val decoded = OperationJournal(context).find("op-1")!!.items.single()
        assertEquals(
            VersionStamp.Composite(sizeBytes = 42L, modifiedAtMillis = 1_000L),
            decoded.sourceStamp,
        )
        assertEquals(
            VersionStamp.Composite(sizeBytes = 42L, modifiedAtMillis = null),
            decoded.destinationStamp,
        )
    }

    @Test
    fun `revision stamps roundtrip`() {
        val journal = OperationJournal(context)
        journal.put(
            operation(
                OperationItem(
                    id = "item-1",
                    source = ref("src"),
                    destination = ref("dst"),
                    displayName = "report.pdf",
                    state = OperationState.NEEDS_ATTENTION,
                    sourceStamp = VersionStamp.Revision("etag-1"),
                    destinationStamp = VersionStamp.Revision("etag-2"),
                ),
            ),
        )

        val decoded = OperationJournal(context).find("op-1")!!.items.single()
        assertEquals(VersionStamp.Revision("etag-1"), decoded.sourceStamp)
        assertEquals(VersionStamp.Revision("etag-2"), decoded.destinationStamp)
    }

    @Test
    fun `records without stamps decode to null stamps`() {
        val journal = OperationJournal(context)
        journal.put(
            operation(
                OperationItem(
                    id = "item-1",
                    source = ref("src"),
                    destination = ref("dst"),
                    displayName = "report.pdf",
                    state = OperationState.NEEDS_ATTENTION,
                ),
            ),
        )

        val decoded = OperationJournal(context).find("op-1")!!.items.single()
        assertNull(decoded.sourceStamp)
        assertNull(decoded.destinationStamp)
    }
}
