package io.github.mbaliga.fylz.operations

import android.content.Context
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Crash injection at every journaled transition (WP-0.6).
 *
 * Every `OperationJournal.put` is a synchronous `commit()`, so each transition a service journals
 * IS a possible on-disk state at process death — including the per-buffer-chunk progress writes
 * during a transfer. These tests reproduce the journal exactly as it stands at each transition,
 * simulate process death, and assert that recovery yields either completion or a clean,
 * explained, retry-or-abandon state: **never repeated copies, never lost cleanup, never a
 * silently forgotten operation.**
 *
 * ### How a crash is simulated
 *
 * Real process death cannot happen inside a JVM test, and does not need to: the journal detects
 * a new process by comparing the persisted `process_session` token against a static per-process
 * UUID. Writing a foreign token into the preferences makes the next `OperationJournal`
 * construction behave exactly as the first construction after a crash does — same code path,
 * same lock, same policy application.
 *
 * The transition fixtures mirror what each service persists, per the state map in
 * `FileOperationService`, `RecycleBinService`, `FileTools` and `ArchiveService`. If a service
 * gains a new journaled transition, add the fixture here — this suite is the safety net the
 * plan's engine work (WP-1.4, WP-3.x) rests on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OperationJournalCrashInjectionTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private fun uri(tail: String): Uri = Uri.parse("content://fylz.test/tree/root/document/$tail")

    /** The picker-shaped destination a pending transfer journals: a plain tree URI. */
    private val destTree: Uri = Uri.parse("content://fylz.test/tree/dest")

    private fun item(
        name: String,
        state: OperationState,
        destination: Uri? = destTree,
        completedBytes: Long = 0,
        expectedBytes: Long? = 1_024L,
        errorCode: String? = null,
    ) = OperationItem(
        source = uri("src-$name"),
        destination = destination,
        displayName = name,
        expectedBytes = expectedBytes,
        completedBytes = completedBytes,
        state = state,
        errorCode = errorCode,
    )

    private fun operation(
        type: FileOperationType,
        state: OperationState,
        items: List<OperationItem>,
    ) = FileOperation(type = type, items = items, state = state)

    /**
     * Seeds the journal as a live process would, then makes that process "die".
     *
     * Starts from empty preferences so parameterised loops inside one test method get one
     * isolated journal per fixture rather than an accumulating one.
     */
    private fun seedThenCrash(vararg operations: FileOperation): OperationJournal {
        val preferences = context.getSharedPreferences("fylz_operation_journal", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val journal = OperationJournal(context)
        operations.forEach(journal::put)
        preferences.edit()
            .putString("process_session", "prior-process-${UUID.randomUUID()}")
            .commit()
        return OperationJournal(context)
    }

    private fun OperationJournal.only(): FileOperation = list().single()

    // ── The interruption rule, at each pre-terminal transition ─────────────────────────

    @Test
    fun `copy crashed in preflight becomes needs attention with interrupted items`() {
        val recovered = seedThenCrash(
            operation(
                FileOperationType.COPY,
                OperationState.PREFLIGHT,
                listOf(item("a.txt", OperationState.QUEUED), item("b.txt", OperationState.QUEUED)),
            ),
        ).only()

        assertEquals(OperationState.NEEDS_ATTENTION, recovered.state)
        recovered.items.forEach {
            assertEquals(OperationState.NEEDS_ATTENTION, it.state)
            assertEquals("PROCESS_INTERRUPTED", it.errorCode)
        }
    }

    @Test
    fun `transfer crashed mid stream keeps its progress and is marked interrupted`() {
        val recovered = seedThenCrash(
            operation(
                FileOperationType.COPY,
                OperationState.RUNNING,
                listOf(item("big.bin", OperationState.RUNNING, completedBytes = 512, expectedBytes = 2_048)),
            ),
        ).only()

        assertEquals(OperationState.NEEDS_ATTENTION, recovered.state)
        val survivor = recovered.items.single()
        assertEquals(OperationState.NEEDS_ATTENTION, survivor.state)
        assertEquals("PROCESS_INTERRUPTED", survivor.errorCode)
        // The chunk-level journal writes are why this byte count exists at all.
        assertEquals(512, survivor.completedBytes)
    }

    @Test
    fun `finished items inside an interrupted operation are preserved exactly`() {
        val done = item("done.txt", OperationState.SUCCEEDED, destination = uri("dest-tree/done.txt"))
        val skipped = item("skip.txt", OperationState.SUCCEEDED, errorCode = "SKIPPED_CONFLICT")
        val live = item("live.txt", OperationState.RUNNING)

        val recovered = seedThenCrash(
            operation(FileOperationType.COPY, OperationState.RUNNING, listOf(done, skipped, live)),
        ).only()

        assertEquals(OperationState.NEEDS_ATTENTION, recovered.state)
        assertEquals(done, recovered.items[0])
        assertEquals(skipped, recovered.items[1])
        assertEquals(OperationState.NEEDS_ATTENTION, recovered.items[2].state)
    }

    @Test
    fun `every pre-terminal state maps to needs attention for every operation type`() {
        // PERMANENT_DELETE journals RUNNING directly with no PREFLIGHT, RESTORE can jump
        // PREFLIGHT→SUCCEEDED on a conflict-skip — but a crash at any pre-terminal put must
        // resolve identically regardless of which of these shapes was on disk. QUEUED is in the
        // list defensively: no service persists a QUEUED operation today, and if one ever does,
        // it must recover like the rest.
        val preTerminal = listOf(
            OperationState.QUEUED,
            OperationState.PREFLIGHT,
            OperationState.RUNNING,
            OperationState.PAUSED,
        )
        val types = FileOperationType.entries

        for (type in types) for (state in preTerminal) {
            val recovered = seedThenCrash(
                operation(type, state, listOf(item("x-$type-$state", state))),
            ).only()
            assertEquals("$type crashed at $state", OperationState.NEEDS_ATTENTION, recovered.state)
            assertEquals("PROCESS_INTERRUPTED", recovered.items.single().errorCode)
        }
    }

    @Test
    fun `terminal operations are untouched by recovery`() {
        val terminalStates = listOf(
            OperationState.SUCCEEDED,
            OperationState.FAILED,
            OperationState.CANCELLED,
            OperationState.NEEDS_ATTENTION,
        )
        for (state in terminalStates) {
            val fixture = operation(
                FileOperationType.RECYCLE,
                state,
                listOf(item("t-$state", state, errorCode = if (state == OperationState.FAILED) "OPERATION_FAILED" else null)),
            )
            val recovered = seedThenCrash(fixture).only()
            assertEquals(fixture.copy(updatedAtMillis = recovered.updatedAtMillis), recovered)
            assertEquals(fixture.updatedAtMillis, recovered.updatedAtMillis)
        }
    }

    // ── Never repeated copies, never lost cleanup ─────────────────────────────────────

    @Test
    fun `retrying an interrupted copy replays only the unfinished items`() {
        val done = item("done.txt", OperationState.SUCCEEDED, destination = uri("dest-tree/done.txt"))
        val live = item("live.txt", OperationState.RUNNING)
        val journal = seedThenCrash(
            operation(FileOperationType.COPY, OperationState.RUNNING, listOf(done, live)),
        )

        val plan = OperationRetryPolicy.plan(journal.only())

        val transfer = plan as OperationRetryPlan.Transfer
        assertEquals(listOf(live.source), transfer.sourceUris)
        assertTrue("A finished item must never be copied again", done.source !in transfer.sourceUris)
    }

    @Test
    fun `move with a committed destination offers cleanup and never a re-copy`() {
        val pendingCleanup = item(
            "moved.txt",
            OperationState.NEEDS_ATTENTION,
            destination = uri("dest-tree/moved.txt"),
            errorCode = OperationRetryPolicy.MOVE_SOURCE_DELETE_PENDING,
        )
        val journal = seedThenCrash(
            operation(FileOperationType.MOVE, OperationState.RUNNING, listOf(pendingCleanup)),
        )

        val recovered = journal.only()
        // Recovery must not overwrite the explained cleanup state with PROCESS_INTERRUPTED —
        // MOVE_SOURCE_DELETE_PENDING is what routes this to cleanup instead of re-copy.
        assertEquals(
            OperationRetryPolicy.MOVE_SOURCE_DELETE_PENDING,
            recovered.items.single().errorCode,
        )
        assertEquals(
            OperationRetryPlan.FinishMoveCleanup(recovered.id),
            OperationRetryPolicy.plan(recovered),
        )
    }

    @Test
    fun `a move mixing cleanup and replay items refuses one-batch retry`() {
        val pendingCleanup = item(
            "moved.txt",
            OperationState.NEEDS_ATTENTION,
            destination = uri("dest-tree/moved.txt"),
            errorCode = OperationRetryPolicy.MOVE_SOURCE_DELETE_PENDING,
        )
        val interrupted = item("live.txt", OperationState.RUNNING)
        val journal = seedThenCrash(
            operation(FileOperationType.MOVE, OperationState.RUNNING, listOf(pendingCleanup, interrupted)),
        )

        assertNull(
            "Mixing a final-file cleanup URI with a replayable tree URI must refuse retry",
            OperationRetryPolicy.plan(journal.only()),
        )
    }

    @Test
    fun `an interrupted paste into a subfolder refuses replay rather than landing in the root`() {
        // A tray paste journals the RESOLVED subfolder document URI. Replaying it through
        // copy() would resolve back to the tree ROOT and put the files in the wrong folder,
        // so the policy must refuse — the tray still holds the items for a one-tap redo.
        val subfolder = Uri.parse("content://fylz.test/tree/dest/document/dest%2Fnested")
        val journal = seedThenCrash(
            operation(
                FileOperationType.COPY,
                OperationState.RUNNING,
                listOf(item("live.txt", OperationState.RUNNING, destination = subfolder)),
            ),
        )

        assertNull(OperationRetryPolicy.plan(journal.only()))
    }

    @Test
    fun `non-transfer operations interrupted by a crash are explained but never auto-replayable`() {
        val nonTransfer = FileOperationType.entries - setOf(FileOperationType.COPY, FileOperationType.MOVE)
        for (type in nonTransfer) {
            val journal = seedThenCrash(
                operation(type, OperationState.RUNNING, listOf(item("x-$type", OperationState.RUNNING))),
            )
            val recovered = journal.only()
            assertEquals(OperationState.NEEDS_ATTENTION, recovered.state)
            assertNull("$type must not offer an automatic retry", OperationRetryPolicy.plan(recovered))
        }
    }

    // ── Never silently forgotten ──────────────────────────────────────────────────────

    @Test
    fun `interrupted operations survive clear finished`() {
        val journal = seedThenCrash(
            operation(FileOperationType.COPY, OperationState.RUNNING, listOf(item("live.txt", OperationState.RUNNING))),
            operation(FileOperationType.COPY, OperationState.SUCCEEDED, listOf(item("done.txt", OperationState.SUCCEEDED))),
            operation(FileOperationType.RECYCLE, OperationState.FAILED, listOf(item("bad.txt", OperationState.FAILED))),
        )

        journal.clearFinished()

        val survivors = journal.list()
        assertEquals(1, survivors.size)
        assertEquals(OperationState.NEEDS_ATTENTION, survivors.single().state)
    }

    @Test
    fun `recovery happens once per process not once per journal instance`() {
        // Multiple services construct their own journals in one live process. The second
        // construction must not misclassify the first's live RUNNING work as interrupted.
        val first = OperationJournal(context)
        val live = operation(
            FileOperationType.COPY,
            OperationState.RUNNING,
            listOf(item("live.txt", OperationState.RUNNING)),
        )
        first.put(live)

        val second = OperationJournal(context)

        assertEquals(OperationState.RUNNING, second.only().state)
    }

    @Test
    fun `the journal round-trips an operation exactly through its codec`() {
        val fixture = operation(
            FileOperationType.MOVE,
            OperationState.RUNNING,
            listOf(
                item("plain.txt", OperationState.RUNNING),
                item("no-dest.txt", OperationState.QUEUED, destination = null, expectedBytes = null),
                item("done.txt", OperationState.SUCCEEDED, destination = uri("dest-tree/done.txt")),
            ),
        )
        val journal = OperationJournal(context)

        journal.put(fixture)

        assertEquals(fixture, OperationJournal(context).only())
    }

    @Test
    fun `corrupted journal payload yields an empty list not a crash`() {
        val journal = OperationJournal(context)
        journal.put(
            operation(FileOperationType.COPY, OperationState.RUNNING, listOf(item("x", OperationState.RUNNING))),
        )
        context.getSharedPreferences("fylz_operation_journal", Context.MODE_PRIVATE)
            .edit()
            .putString("operations", "{not json[")
            .commit()

        // Documented current contract: corruption drops all records rather than crashing the
        // app. (RecycleBinStore keeps a last-known-good backup; the journal does not yet —
        // hardening candidate for the Phase 1 engine extraction, not for this test WP.)
        assertTrue(OperationJournal(context).list().isEmpty())
    }
}
