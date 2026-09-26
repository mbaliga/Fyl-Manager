package io.github.mbaliga.fylz.operations

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P1.11: [OperationJournal.operations] replaces the 1-second poll `FylzAppShell` used to run --
 * every mutator must republish it immediately, and only that same instance's own mutations do so
 * (see [OperationJournal]'s own KDoc on two instances over one database file).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OperationJournalStateFlowTest {

    private fun operation(id: String, state: OperationState = OperationState.QUEUED) = FileOperation(
        id = id,
        type = FileOperationType.COPY,
        items = listOf(
            OperationItem(
                id = "item-$id",
                source = Uri.parse("content://test/source-$id"),
                displayName = "file-$id.txt",
                state = state,
            ),
        ),
        state = state,
    )

    @Test
    fun `put republishes operations immediately`() {
        val journal = OperationJournal(RuntimeEnvironment.getApplication())

        journal.put(operation("a"))

        assertEquals(setOf("a"), journal.operations.value.map(FileOperation::id).toSet())
    }

    @Test
    fun `remove republishes operations immediately`() {
        val journal = OperationJournal(RuntimeEnvironment.getApplication())
        journal.put(operation("a"))

        journal.remove("a")

        assertTrue(journal.operations.value.isEmpty())
    }

    @Test
    fun `clearFinished republishes operations immediately`() {
        val journal = OperationJournal(RuntimeEnvironment.getApplication())
        journal.put(operation("a", OperationState.SUCCEEDED))
        journal.put(operation("b", OperationState.RUNNING))

        journal.clearFinished()

        assertEquals(setOf("b"), journal.operations.value.map(FileOperation::id).toSet())
    }

    @Test
    fun `a second instance over the same database does not see the first's mutation until it mutates too`() {
        val context = RuntimeEnvironment.getApplication()
        val first = OperationJournal(context)
        val second = OperationJournal(context)

        first.put(operation("a"))

        assertTrue(
            "a fresh instance's own operations must not change just because another instance wrote",
            second.operations.value.isEmpty(),
        )
        assertEquals(
            "the write is still durable -- a one-off list() read sees it",
            setOf("a"),
            second.list().map(FileOperation::id).toSet(),
        )

        second.put(operation("b"))

        assertEquals(
            "the second instance's own mutation refreshes it to the database's full current state",
            setOf("a", "b"),
            second.operations.value.map(FileOperation::id).toSet(),
        )
    }
}
