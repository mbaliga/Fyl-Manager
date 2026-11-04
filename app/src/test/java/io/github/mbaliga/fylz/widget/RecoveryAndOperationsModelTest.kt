package io.github.mbaliga.fylz.widget

import android.net.Uri
import io.github.mbaliga.fylz.operations.FileOperation
import io.github.mbaliga.fylz.operations.FileOperationType
import io.github.mbaliga.fylz.operations.OperationItem
import io.github.mbaliga.fylz.operations.OperationState
import io.github.mbaliga.fylz.operations.RecycleRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Recycle records and journal items carry real `content://` URIs and display names, so these
 * tests run under Robolectric for a working `Uri` and feed the widget models the app's own types.
 * The privacy assertions are the point: names and URIs go in, only kinds and counts come out.
 */
@RunWith(RobolectricTestRunner::class)
class RecoveryAndOperationsModelTest {

    private fun uri(name: String): Uri =
        Uri.parse("content://io.github.mbaliga.fylz.files/tree/primary%3ADocuments/document/primary%3ADocuments%2F$name")

    private fun recycled(name: String, size: Long?, at: Long) = RecycleRecord(
        itemId = name,
        originalUri = uri(name),
        recycledUri = uri(".fylz-trash/$name"),
        originalParentUri = uri(""),
        originalDisplayName = name,
        providerAuthority = "io.github.mbaliga.fylz.files",
        sizeBytes = size,
        recycledAtMillis = at,
    )

    private fun item(name: String, expected: Long?, done: Long = 0, state: OperationState = OperationState.RUNNING, error: String? = null) =
        OperationItem(source = uri(name), destination = uri("copy-of-$name"), displayName = name, expectedBytes = expected, completedBytes = done, state = state, errorCode = error)

    private fun operation(
        type: FileOperationType,
        state: OperationState,
        items: List<OperationItem>,
        updated: Long,
    ) = FileOperation(type = type, items = items, state = state, createdAtMillis = updated, updatedAtMillis = updated)

    @Test
    fun `recycle bin is counted and sized, with an unknown size making the total a floor`() {
        val complete = RecoveryWidgetModel.from(listOf(recycled("a.pdf", 100, 5), recycled("b.jpg", 50, 3)), emptyList())
        assertEquals(2, complete.binCount)
        assertEquals(150, complete.binBytes)
        assertTrue(complete.binSizeComplete)
        assertEquals(3L, complete.oldestRecycledAtMillis)

        val partial = RecoveryWidgetModel.from(listOf(recycled("a.pdf", 100, 5), recycled("folder", null, 9)), emptyList())
        assertEquals(100, partial.binBytes)
        assertFalse(partial.binSizeComplete)
    }

    @Test
    fun `an empty bin and no attention is all clear`() {
        val model = RecoveryWidgetModel.from(emptyList(), listOf(operation(FileOperationType.COPY, OperationState.SUCCEEDED, emptyList(), 1)))
        assertTrue(model.isAllClear)
        assertNull(model.oldestRecycledAtMillis)
    }

    @Test
    fun `operations needing attention are listed newest first with their reason`() {
        val model = RecoveryWidgetModel.from(
            emptyList(),
            listOf(
                operation(FileOperationType.COPY, OperationState.NEEDS_ATTENTION, listOf(item("a", 10, error = "PROCESS_INTERRUPTED")), updated = 10),
                operation(FileOperationType.MOVE, OperationState.NEEDS_ATTENTION, listOf(item("b", 10, error = "MOVE_SOURCE_DELETE_PENDING"), item("c", 10)), updated = 30),
                operation(FileOperationType.EXTRACT, OperationState.NEEDS_ATTENTION, listOf(item("d", 10, error = "IOException")), updated = 20),
                operation(FileOperationType.COPY, OperationState.FAILED, listOf(item("e", 10)), updated = 40),
            ),
        )
        assertEquals(3, model.attentionCount)
        assertEquals(1, model.interruptedCount)
        assertEquals(listOf(FileOperationType.MOVE, FileOperationType.EXTRACT, FileOperationType.COPY), model.attention.map { it.type })
        assertEquals(listOf(AttentionReason.MOVE_CLEANUP, AttentionReason.REVIEW, AttentionReason.INTERRUPTED), model.attention.map { it.reason })
        assertEquals(listOf(2, 1, 1), model.attention.map { it.itemCount })
        assertEquals(AttentionRow(FileOperationType.MOVE, 2, AttentionReason.MOVE_CLEANUP, 30), model.latestAttention)
    }

    @Test
    fun `in-progress work is running first, then most recent, with byte progress`() {
        val model = OperationsWidgetModel.from(
            listOf(
                operation(FileOperationType.MOVE, OperationState.QUEUED, listOf(item("q", 10)), updated = 50),
                operation(FileOperationType.COPY, OperationState.RUNNING, listOf(item("x", 1000, done = 420), item("y", 1000, done = 0)), updated = 20),
                operation(FileOperationType.ARCHIVE, OperationState.PAUSED, listOf(item("z", null)), updated = 40),
                operation(FileOperationType.COPY, OperationState.SUCCEEDED, listOf(item("done", 5)), updated = 60),
            ),
            runningBackupStarts = listOf(30),
        )
        assertEquals(4, model.active.size)
        assertEquals(
            listOf(ActivityKind.Backup, ActivityKind.File(FileOperationType.COPY), ActivityKind.File(FileOperationType.MOVE), ActivityKind.File(FileOperationType.ARCHIVE)),
            model.active.map { it.kind },
        )
        val copy = model.active[1]
        assertEquals(210, copy.progressPermille)
        assertEquals(21, copy.progressPercent)
        assertEquals(2, copy.itemCount)
        assertNull("an unknown item size leaves the total unknown", model.active[3].progressPermille)
        assertNull("a backup run reports no bytes", model.active[0].progressPermille)
        assertEquals(FinishedSummary(FileOperationType.COPY, OperationState.SUCCEEDED, 60), model.lastFinished)
        assertEquals(1, model.rowsFor(WidgetSize.SMALL).size)
        assertEquals(2, model.rowsFor(WidgetSize.LARGE).size)
        assertEquals(2, model.hiddenAt(WidgetSize.LARGE))
    }

    @Test
    fun `live or paused file work arms the watchdog, and a running backup its lease`() {
        val now = 1_000_000L
        val copying = OperationsWidgetModel.from(
            listOf(operation(FileOperationType.COPY, OperationState.RUNNING, listOf(item("x", 10)), updated = 5)),
            runningBackupStarts = listOf(400L),
        )
        assertEquals(
            listOf(now + OperationsWidgetModel.WATCHDOG_MILLIS, 400L + BackupsWidgetModel.RUNNING_STALE_AFTER_MILLIS),
            copying.recheckAtMillis(now),
        )
        listOf(OperationState.PREFLIGHT, OperationState.PAUSED).forEach { state ->
            val model = OperationsWidgetModel.from(listOf(operation(FileOperationType.MOVE, state, listOf(item("y", 10)), updated = 5)), emptyList())
            assertEquals(state.name, listOf(now + OperationsWidgetModel.WATCHDOG_MILLIS), model.recheckAtMillis(now))
        }
        // Process-death recovery leaves QUEUED work alone, so a redraw would retire nothing.
        val queued = OperationsWidgetModel.from(listOf(operation(FileOperationType.MOVE, OperationState.QUEUED, listOf(item("q", 10)), updated = 5)), emptyList())
        assertTrue(queued.recheckAtMillis(now).isEmpty())
        assertTrue(OperationsWidgetModel.from(emptyList(), emptyList()).recheckAtMillis(now).isEmpty())
    }

    @Test
    fun `progress is clamped and a zero total is unknown`() {
        val over = ActivityRow(ActivityKind.Backup, ActivityPhase.RUNNING, null, completedBytes = 150, totalBytes = 100, updatedAtMillis = 0)
        assertEquals(1000, over.progressPermille)
        val zero = over.copy(completedBytes = 0, totalBytes = 0)
        assertNull(zero.progressPermille)
    }

    @Test
    fun `nothing in progress is idle`() {
        val model = OperationsWidgetModel.from(emptyList(), emptyList())
        assertTrue(model.isIdle)
        assertNull(model.lastFinished)
    }

    @Test
    fun `file names and URIs never reach either model`() {
        val secret = "Divorce settlement draft.docx"
        val records = listOf(recycled(secret, 10, 1))
        val operations = listOf(
            operation(FileOperationType.COPY, OperationState.RUNNING, listOf(item(secret, 100, 10)), 5),
            operation(FileOperationType.MOVE, OperationState.NEEDS_ATTENTION, listOf(item(secret, 100, error = "PROCESS_INTERRUPTED")), 6),
        )
        val shown = RecoveryWidgetModel.from(records, operations).toString() + OperationsWidgetModel.from(operations, emptyList()).toString()
        listOf("Divorce", "content://", "fylz-trash", "Documents").forEach {
            assertFalse("a widget model leaked '$it': $shown", shown.contains(it))
        }
    }
}
