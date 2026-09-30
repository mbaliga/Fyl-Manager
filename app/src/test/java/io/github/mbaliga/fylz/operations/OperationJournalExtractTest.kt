package io.github.mbaliga.fylz.operations

import android.net.Uri
import io.github.mbaliga.fylz.decoder.ArchiveLimits
import io.github.mbaliga.fylz.storage.VolumeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The journal's extraction-plan half (`docs/agent/DESIGN-M34-SELECTIVE-EXTRACT.md` section 2.5's
 * DAO): the atomic plan write and its round trip, the claim as a conditional state change, the
 * single-row item update, the cancel flag, the retry transaction, the digests, and plan rows going
 * wherever their operation goes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OperationJournalExtractTest {

    private lateinit var journal: OperationJournal

    private val archiveRoot = Uri.parse("content://io.github.mbaliga.fylz.archives/document/cm9vdA")
    private val destination = Uri.parse("content://io.github.mbaliga.fylz.files/tree/primary%3A/document/primary%3Adest")

    @Before
    fun setUp() {
        journal = OperationJournal(RuntimeEnvironment.getApplication())
    }

    private fun operation(id: String, state: OperationState = OperationState.QUEUED, items: Int = 2): FileOperation = FileOperation(
        id = id,
        type = FileOperationType.EXTRACT,
        items = List(items) { i -> OperationItem(id = "$id-item-$i", source = archiveRoot, destination = destination, displayName = "item$i", expectedBytes = 100L * (i + 1), state = state) },
        conflictPolicy = ConflictPolicy.SKIP,
        state = state,
        destination = destination,
    )

    private fun plan(id: String, cancel: Boolean = false): ExtractPlan = ExtractPlan(
        operationId = id,
        archiveUri = archiveRoot,
        catalogKey = "k-$id",
        layout = ExtractLayout.HERE,
        folderName = null,
        ordinals = OrdinalBitmap.of(0, 1, 2, 5, 9),
        limits = ArchiveLimits.forExtraction(VolumeInfo("exfat", 20L * 1024 * 1024 * 1024, true), consent = true),
        consent = true,
        sanitize = true,
        cancelRequested = cancel,
        items = listOf(
            ExtractPlanItem(0, "docs", "docs", ConflictPolicy.KEEP_BOTH, "docs (2)"),
            ExtractPlanItem(1, "hello.txt", "hello.txt", ConflictPolicy.REPLACE, null),
        ),
    )

    @Test
    fun `putWithExtractPlan stores the operation and the plan atomically and the plan round trips field for field`() {
        journal.putWithExtractPlan(operation("op-1"), plan("op-1"))
        assertEquals(operation("op-1").items, journal.find("op-1")!!.items)
        assertEquals(plan("op-1"), journal.extractPlan("op-1"))
        assertTrue(journal.hasExtractPlan("op-1"))
        assertFalse(journal.hasExtractPlan("op-none"))
        assertNull(journal.extractPlan("op-none"))
        assertEquals(1, journal.operations.value.size)
        // A second write of the same operation replaces the plan rather than duplicating it.
        journal.putWithExtractPlan(operation("op-1"), plan("op-1").copy(catalogKey = "k2", items = plan("op-1").items.take(1)))
        assertEquals("k2", journal.extractPlan("op-1")!!.catalogKey)
        assertEquals(1, journal.extractPlan("op-1")!!.items.size)
    }

    @Test
    fun `claimExtract moves the operation and its items to RUNNING once, from the given states only`() {
        journal.putWithExtractPlan(operation("op-2"), plan("op-2"))
        assertFalse("not claimable from a state the caller did not name", journal.claimExtract("op-2", setOf(OperationState.PAUSED_BY_SYSTEM)))
        assertEquals(OperationState.QUEUED, journal.find("op-2")!!.state)
        assertTrue(journal.claimExtract("op-2", ArchiveExtractor.CLAIMABLE))
        val claimed = journal.find("op-2")!!
        assertEquals(OperationState.RUNNING, claimed.state)
        assertTrue(claimed.items.all { it.state == OperationState.RUNNING && it.errorCode == null })
        assertFalse("a second claim of a RUNNING row fails", journal.claimExtract("op-2", ArchiveExtractor.CLAIMABLE))
        assertTrue("unless RUNNING is explicitly claimable (a stale row)", journal.claimExtract("op-2", setOf(OperationState.RUNNING)))
        assertEquals(OperationState.RUNNING, journal.operations.value.single().state)
    }

    @Test
    fun `claimExtract leaves succeeded items alone and only re-runs the unfinished ones`() {
        val op = operation("op-3", state = OperationState.PAUSED_BY_SYSTEM).let { it.copy(items = listOf(it.items[0].copy(state = OperationState.SUCCEEDED), it.items[1].copy(state = OperationState.PAUSED_BY_SYSTEM, errorCode = null))) }
        journal.putWithExtractPlan(op, plan("op-3"))
        assertTrue(journal.claimExtract("op-3", ArchiveExtractor.CLAIMABLE))
        val items = journal.find("op-3")!!.items
        assertEquals(OperationState.SUCCEEDED, items[0].state)
        assertEquals(OperationState.RUNNING, items[1].state)
    }

    @Test
    fun `updateOperationStateIf changes the row only when it is still in the expected state`() {
        journal.putWithExtractPlan(operation("op-4"), plan("op-4"))
        assertFalse(journal.updateOperationStateIf("op-4", OperationState.RUNNING, OperationState.INTERRUPTED))
        assertEquals(OperationState.QUEUED, journal.find("op-4")!!.state)
        assertTrue(journal.updateOperationStateIf("op-4", OperationState.QUEUED, OperationState.FAILED))
        assertEquals(OperationState.FAILED, journal.find("op-4")!!.state)
        assertEquals(OperationState.FAILED, journal.operations.value.single().state)
    }

    @Test
    fun `updateItem rewrites one item by id and leaves the flow stale until refresh when asked`() {
        journal.putWithExtractPlan(operation("op-5"), plan("op-5"))
        val before = journal.find("op-5")!!
        val changed = before.items[1].copy(completedBytes = 77L, state = OperationState.RUNNING, stagingUri = Uri.parse("content://x/.fylz-part-op-5-1-item1"), sha256 = "ab", finalUri = Uri.parse("content://x/final"))
        journal.updateItem("op-5", changed, refresh = false)
        assertEquals("the flow is stale", 0L, journal.operations.value.single().items[1].completedBytes)
        val stored = journal.find("op-5")!!
        assertEquals(changed, stored.items[1])
        assertEquals(before.items[0], stored.items[0])
        journal.refresh()
        assertEquals(77L, journal.operations.value.single().items[1].completedBytes)
        journal.updateItem("op-5", changed.copy(completedBytes = 78L))
        assertEquals(78L, journal.operations.value.single().items[1].completedBytes)
    }

    @Test
    fun `the cancel flag is set and read on the plan row, and cleared by a retry`() {
        journal.putWithExtractPlan(operation("op-6"), plan("op-6"))
        assertFalse(journal.isCancelRequested("op-6"))
        journal.setCancelRequested("op-6")
        assertTrue(journal.isCancelRequested("op-6"))
        assertTrue(journal.extractPlan("op-6")!!.cancelRequested)
        assertFalse("no plan, no flag", journal.isCancelRequested("op-none"))
        journal.updateOperationState("op-6", OperationState.CANCELLED)
        assertTrue(journal.retryExtract("op-6"))
        assertFalse(journal.isCancelRequested("op-6"))
    }

    @Test
    fun `retryExtract moves a finished operation and its unfinished items back to QUEUED in one go`() {
        val op = operation("op-7", state = OperationState.PARTIAL).let {
            it.copy(
                items = listOf(
                    it.items[0].copy(state = OperationState.SUCCEEDED, completedBytes = 100L),
                    it.items[1].copy(state = OperationState.FAILED, errorCode = "ARCHIVE_CRC_MISMATCH", stagingUri = Uri.parse("content://x/part"), completedBytes = 40L),
                ),
            )
        }
        journal.putWithExtractPlan(op, plan("op-7"))
        assertTrue(journal.retryExtract("op-7"))
        val retried = journal.find("op-7")!!
        assertEquals(OperationState.QUEUED, retried.state)
        assertEquals(OperationState.SUCCEEDED, retried.items[0].state)
        assertEquals(100L, retried.items[0].completedBytes)
        assertEquals(OperationItem(id = "op-7-item-1", source = archiveRoot, destination = destination, displayName = "item1", expectedBytes = 200L, completedBytes = 0L, state = OperationState.QUEUED), retried.items[1])
        assertFalse("QUEUED is not retryable", journal.retryExtract("op-7"))
        journal.updateOperationState("op-7", OperationState.SUCCEEDED)
        assertFalse("SUCCEEDED is not retryable", journal.retryExtract("op-7"))
        listOf(OperationState.FAILED, OperationState.CANCELLED, OperationState.NEEDS_ATTENTION, OperationState.INTERRUPTED).forEach { state ->
            journal.updateOperationState("op-7", state)
            assertTrue("$state is retryable", journal.retryExtract("op-7"))
        }
    }

    @Test
    fun `retryExtract refuses an operation without a plan`() {
        journal.put(operation("legacy", state = OperationState.FAILED))
        assertFalse(journal.retryExtract("legacy"))
        assertEquals(OperationState.FAILED, journal.find("legacy")!!.state)
    }

    @Test
    fun `entry digests are stored per ordinal, replaced on conflict, and read back in order`() {
        journal.putWithExtractPlan(operation("op-8"), plan("op-8"))
        journal.putEntryDigests("op-8", mapOf(5 to "e5", 1 to "e1"))
        journal.putEntryDigests("op-8", mapOf(1 to "e1-again"))
        journal.putEntryDigests("op-8", emptyMap())
        assertEquals(mapOf(1 to "e1-again", 5 to "e5"), journal.entryDigests("op-8"))
        assertEquals(listOf(1, 5), journal.entryDigests("op-8").keys.toList())
    }

    @Test
    fun `plan rows go wherever the operation goes -- remove, clearFinished and the record limit`() {
        journal.putWithExtractPlan(operation("op-9"), plan("op-9"))
        journal.putEntryDigests("op-9", mapOf(0 to "x"))
        journal.remove("op-9")
        assertFalse(journal.hasExtractPlan("op-9"))
        assertTrue(journal.entryDigests("op-9").isEmpty())

        journal.putWithExtractPlan(operation("op-10", state = OperationState.FAILED), plan("op-10"))
        journal.putWithExtractPlan(operation("op-11", state = OperationState.RUNNING), plan("op-11"))
        journal.clearFinished()
        assertFalse(journal.hasExtractPlan("op-10"))
        assertTrue(journal.hasExtractPlan("op-11"))

        // 200 records are kept; the oldest beyond that goes with its plan.
        journal.putWithExtractPlan(operation("oldest").copy(updatedAtMillis = 1L, createdAtMillis = 1L), plan("oldest"))
        repeat(200) { n -> journal.put(FileOperation(id = "filler-$n", type = FileOperationType.COPY, items = emptyList(), createdAtMillis = 1_000L + n, updatedAtMillis = 1_000L + n)) }
        assertNull(journal.find("oldest"))
        assertFalse(journal.hasExtractPlan("oldest"))
        assertTrue(journal.hasExtractPlan("op-11"))
    }
}
