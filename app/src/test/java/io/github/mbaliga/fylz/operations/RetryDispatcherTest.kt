package io.github.mbaliga.fylz.operations

import android.net.Uri
import io.github.mbaliga.fylz.decoder.ArchiveLimits
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** `RetryDispatcher` (M3.4): the extraction re-claim path -- the journal's transaction, then the enqueue; nothing enqueued when the journal refuses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RetryDispatcherTest {

    private val archiveRoot = Uri.parse("content://io.github.mbaliga.fylz.archives/document/cm9vdA")
    private val destination = Uri.parse("content://io.github.mbaliga.fylz.files/tree/primary%3A/document/primary%3Adest")

    private fun failedExtract(id: String) = FileOperation(
        id = id,
        type = FileOperationType.EXTRACT,
        items = listOf(OperationItem(id = "$id-0", source = archiveRoot, destination = destination, displayName = "docs", state = OperationState.FAILED, errorCode = "ARCHIVE_FATAL")),
        conflictPolicy = ConflictPolicy.SKIP,
        state = OperationState.FAILED,
        destination = destination,
    )

    private fun plan(id: String) = ExtractPlan(id, archiveRoot, "key", ExtractLayout.HERE, null, OrdinalBitmap.of(1), ArchiveLimits.forExtraction(null, false), false, false, items = listOf(ExtractPlanItem(0, "docs", "docs", ConflictPolicy.SKIP)))

    private val sourceFile = Uri.parse("content://io.github.mbaliga.fylz.files/document/primary%3Asrc.txt")

    private fun failedCreate(id: String) = FileOperation(
        id = id,
        type = FileOperationType.ARCHIVE,
        items = listOf(OperationItem(id = "$id-0", source = sourceFile, destination = destination, displayName = "src.txt", state = OperationState.FAILED, errorCode = "ARCHIVE_WRITE_FAILED")),
        conflictPolicy = ConflictPolicy.SKIP,
        state = OperationState.FAILED,
        destination = destination,
    )

    private fun createPlan(id: String) = CompressPlan(id, CompressFormat.ZIP, 6, SplitSize.Off, relativeToSelection = true, archiveName = "out.zip", destinationUri = destination, totalEstimate = 5L, entryCount = 1, conflictPolicy = ConflictPolicy.SKIP)

    private fun createManifest(id: String) = listOf(CompressManifestEntry(0, false, "src.txt", sourceFile, 0L, needsSpooling = false))

    @Test
    fun `ReclaimExtract re-queues the operation through the journal and then enqueues it`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val journal = OperationJournal(context)
        journal.putWithExtractPlan(failedExtract("op-1"), plan("op-1"))
        val enqueued = ArrayList<String>()
        val dispatcher = RetryDispatcher(
            fileOperations = FileOperationService(context, journal = journal),
            journal = journal,
            enqueueExtract = { id ->
                assertEquals("the journal moved the row before the enqueue", OperationState.QUEUED, journal.find(id)!!.state)
                enqueued += id
            },
        )
        dispatcher.dispatch(OperationRetryPolicy.plan(journal.find("op-1")!!)!!)
        assertEquals(listOf("op-1"), enqueued)
        assertEquals(OperationState.QUEUED, journal.find("op-1")!!.items.single().state)
    }

    @Test
    fun `ReclaimExtract without a plan row, or already queued, throws and enqueues nothing`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val journal = OperationJournal(context)
        journal.put(failedExtract("legacy"))
        val enqueued = ArrayList<String>()
        val dispatcher = RetryDispatcher(
            fileOperations = FileOperationService(context, journal = journal),
            journal = journal,
            enqueueExtract = { enqueued += it },
        )
        val thrown = assertThrows(IllegalStateException::class.java) { runBlocking { dispatcher.dispatch(OperationRetryPlan.ReclaimExtract("legacy")) } }
        assertTrue(thrown.message!!.contains("no longer be retried"))
        assertTrue(enqueued.isEmpty())
    }

    @Test
    fun `ReclaimCreate re-queues the operation through the journal and then enqueues it`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val journal = OperationJournal(context)
        journal.putWithCreatePlan(failedCreate("op-2"), createPlan("op-2"), createManifest("op-2"))
        val enqueued = ArrayList<String>()
        val dispatcher = RetryDispatcher(
            fileOperations = FileOperationService(context, journal = journal),
            journal = journal,
            enqueueExtract = { },
            enqueueCreate = { id ->
                assertEquals("the journal moved the row before the enqueue", OperationState.QUEUED, journal.find(id)!!.state)
                enqueued += id
            },
        )
        dispatcher.dispatch(OperationRetryPolicy.plan(journal.find("op-2")!!)!!)
        assertEquals(listOf("op-2"), enqueued)
        assertEquals(OperationState.QUEUED, journal.find("op-2")!!.items.single().state)
    }

    @Test
    fun `ReclaimCreate without a plan row throws and enqueues nothing`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val journal = OperationJournal(context)
        journal.put(failedCreate("legacy-create"))
        val enqueued = ArrayList<String>()
        val dispatcher = RetryDispatcher(
            fileOperations = FileOperationService(context, journal = journal),
            journal = journal,
            enqueueExtract = { },
            enqueueCreate = { enqueued += it },
        )
        val thrown = assertThrows(IllegalStateException::class.java) { runBlocking { dispatcher.dispatch(OperationRetryPlan.ReclaimCreate("legacy-create")) } }
        assertTrue(thrown.message!!.contains("no longer be retried"))
        assertTrue(enqueued.isEmpty())
    }
}
