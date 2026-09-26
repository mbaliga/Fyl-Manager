package io.github.mbaliga.fylz.operations

import android.content.Context
import android.util.Log
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.mbaliga.fylz.archive.ArchiveCatalog
import io.github.mbaliga.fylz.archive.ArchiveRef
import io.github.mbaliga.fylz.archive.ExtractFrameReader
import io.github.mbaliga.fylz.archive.FakeArchive
import io.github.mbaliga.fylz.archive.FakeArchiveDecoder
import io.github.mbaliga.fylz.decoder.ArchiveExtractResult
import io.github.mbaliga.fylz.storage.FaultyDocumentsProvider
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import io.github.mbaliga.fylz.storage.VolumeDescriptor
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * `TransferWorker`'s EXTRACT path (`docs/agent/DESIGN-M34-SELECTIVE-EXTRACT.md` section 2.3 step
 * 8) through `work-testing`: every journaled outcome -- success, partial, failed, not claimed -- is
 * `Result.success()` so a queued transfer behind it is never poisoned; `OperationRunner.enqueueExtract`
 * tags and tracks the real request; `recover` reconciles against the real `WorkManager`
 * (`NEVER_RAN` for an old queued row with no work, left alone with enqueued work); the cancel
 * receiver sets the flag; the notification's numbers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TransferWorkerExtractTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var rootDir: File
    private lateinit var destinationDir: File
    private lateinit var stub: FakeArchiveDecoder
    private lateinit var catalog: ArchiveCatalog
    private lateinit var journal: OperationJournal
    private lateinit var extractor: ArchiveExtractor
    private lateinit var factory: WorkerFactory

    @Before
    fun setUp() {
        rootDir = tempFolder.newFolder("root")
        destinationDir = File(rootDir, "dest").apply { mkdirs() }
        val faulty = FaultyDocumentsProvider.install()
        faulty.volumeOverride = listOf(VolumeDescriptor(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, "Internal storage", rootDir, primary = true, removable = false, readOnly = false))
        stub = FakeArchiveDecoder()
        catalog = ExtractTestArchives.catalog(context, stub)
        journal = OperationJournal(context)
        extractor = ArchiveExtractor(
            context = context,
            journal = journal,
            catalog = catalog,
            extractionClient = { FakeArchiveDecoder.client(stub) },
            volumeFor = { ExtractTestArchives.ext4() },
            workLookup = WorkLookup.NONE,
            streamInactivityMillis = 5_000L,
            cancelWaitMillis = 500L,
        )
        factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
                if (workerClassName == TransferWorker::class.java.name) TransferWorker(appContext, workerParameters, extractorFactory = { extractor }) else null
        }
        val configuration = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .setTaskExecutor(SynchronousExecutor())
            .setWorkerFactory(factory)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
    }

    private fun archive(name: String = "a.zip", fake: FakeArchive = ExtractTestArchives.sample()): ArchiveRef = ExtractTestArchives.write(rootDir, name, fake)

    private fun planAndStore(ref: ArchiveRef): String {
        val result = runBlocking {
            ExtractPlanner(context.contentResolver, catalog, volumeFor = { ExtractTestArchives.ext4() })
                .plan(ExtractRequest(ref, ExtractSelection.All, ExtractTestArchives.treeUri("dest"), ExtractLayoutRequest.Here), HeadlessPlannerUi())
        }
        check(result is ExtractPlanResult.Planned) { "expected Planned, got $result" }
        journal.putWithExtractPlan(result.operation, result.plan)
        return result.operation.id
    }

    private fun worker(operationId: String): TransferWorker =
        TestListenableWorkerBuilder<TransferWorker>(context, TransferWorker.extractInputData(operationId)).setWorkerFactory(factory).build()

    @Test
    fun `a successful extraction returns success and the journal holds the outcome`() {
        val id = planAndStore(archive())
        val result = runBlocking { worker(id).doWork() }
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(OperationState.SUCCEEDED, journal.find(id)!!.state)
        assertEquals(ExtractTestArchives.expectedFiles.keys, ExtractTestArchives.filesUnder(destinationDir).keys)
    }

    @Test
    fun `a second run on the same id refuses the claim and still returns success`() {
        val id = planAndStore(archive())
        runBlocking { worker(id).doWork() }
        val before = journal.find(id)!!
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker(id).doWork() })
        assertEquals(before, journal.find(id))
        assertEquals(1, stub.rangesCalls.size)
    }

    @Test
    fun `PARTIAL and FAILED outcomes are journaled and still return success -- the chain is never poisoned`() {
        stub.failOrdinals[ExtractTestArchives.GUIDE] = ExtractFrameReader.FAIL_CRC
        val partial = planAndStore(archive())
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker(partial).doWork() })
        assertEquals(OperationState.PARTIAL, journal.find(partial)!!.state)

        destinationDir.listFiles()!!.forEach { it.deleteRecursively() }
        stub.rangesFailure = ArchiveExtractResult.failed(ArchiveExtractResult.OUTCOME_REFUSED, "refused")
        val failed = planAndStore(archive("b.zip"))
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker(failed).doWork() })
        assertEquals(OperationState.FAILED, journal.find(failed)!!.state)
    }

    @Test
    fun `a missing operation id is the one failure, and an unknown id is success with nothing journaled`() {
        val noId = TestListenableWorkerBuilder<TransferWorker>(context, androidx.work.Data.Builder().putString("type", FileOperationType.EXTRACT.name).build()).setWorkerFactory(factory).build()
        assertTrue(runBlocking { noId.doWork() } is ListenableWorker.Result.Failure)
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker("never-planned").doWork() })
        assertTrue(journal.list().isEmpty())
    }

    @Test
    fun `enqueueExtract tags the request, tracks the operation by its id, and runs it through the real queue`() {
        val id = planAndStore(archive())
        val runner = OperationRunner(CoroutineScope(SupervisorJob() + Dispatchers.Default), context)
        runBlocking { runner.enqueueExtract(id, itemCount = 5) }
        assertEquals(OperationState.SUCCEEDED, journal.find(id)!!.state)
        val infos = WorkManager.getInstance(context).getWorkInfosByTag(OperationRunner.extractTag(id)).get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.SUCCEEDED, infos.single().state)
        assertTrue(infos.single().tags.contains("op:$id"))
        assertTrue("tracking ends with the work", runner.operations.value.isEmpty())
    }

    @Test
    fun `cancel on an enqueued extraction sets the plan's flag rather than cancelling the work`() {
        val id = planAndStore(archive())
        val flagged = ArrayList<String>()
        val runner = OperationRunner(CoroutineScope(SupervisorJob() + Dispatchers.Default), context, cancelExtract = { flagged += it; true })
        stub.beforeData = { ordinal -> if (ordinal == ExtractTestArchives.HELLO) runner.cancel(id); true }
        runBlocking { runner.enqueueExtract(id) }
        assertEquals("flagged once per DATA frame of that entry, never anything else", setOf(id), flagged.toSet())
        val infos = WorkManager.getInstance(context).getWorkInfosByTag(OperationRunner.extractTag(id)).get()
        assertEquals("the work itself was never cancelled", WorkInfo.State.SUCCEEDED, infos.single().state)
    }

    @Test
    fun `cancel on a planned extraction this runner never enqueued still flags it through the journal`() {
        val id = planAndStore(archive())
        val runner = OperationRunner(CoroutineScope(SupervisorJob() + Dispatchers.Default), context)
        runner.cancel(id)
        assertTrue(journal.isCancelRequested(id))
        runner.cancel(java.util.UUID.randomUUID().toString())
    }

    @Test
    fun `recover against the real WorkManager -- NEVER_RAN for an old queued row with no work, left alone with enqueued work`() {
        val orphan = planAndStore(archive("orphan.zip"))
        journal.put(journal.find(orphan)!!.copy(updatedAtMillis = 1_000L))
        val pending = planAndStore(archive("pending.zip"))
        journal.put(journal.find(pending)!!.copy(updatedAtMillis = 1_000L))
        // Enqueued but held back by an unmet constraint: alive, not running.
        val request = OneTimeWorkRequestBuilder<TransferWorker>()
            .setInputData(TransferWorker.extractInputData(pending))
            .addTag(OperationRunner.extractTag(pending))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueue(request).result.get()
        assertEquals(WorkInfo.State.ENQUEUED, WorkManager.getInstance(context).getWorkInfoById(request.id).get()!!.state)

        runBlocking { OperationRunner.recover(journal, context.contentResolver, WorkLookup.viaWorkManager(context), nowMillis = { 10_000_000L }) }

        assertEquals(OperationState.FAILED, journal.find(orphan)!!.state)
        assertTrue(journal.find(orphan)!!.items.all { it.errorCode == ExtractErrorCodes.NEVER_RAN })
        assertEquals(OperationState.QUEUED, journal.find(pending)!!.state)
    }

    @Test
    fun `the cancel receiver sets the plan's flag for the operation it names`() {
        val id = planAndStore(archive())
        ExtractCancelReceiver().onReceive(context, ExtractCancelReceiver.intent(context, id))
        assertTrue(journal.isCancelRequested(id))
        ExtractCancelReceiver().onReceive(context, android.content.Intent("something.else").putExtra(ExtractCancelReceiver.EXTRA_OPERATION_ID, "x"))
        ExtractCancelReceiver().onReceive(context, ExtractCancelReceiver.intent(context, ""))
    }

    @Test
    fun `the notification's bar is permille and its byte line is readable past 2 to the 31`() {
        assertEquals(0, TransferWorker.permille(0L, 5_000_000_000L))
        assertEquals(500, TransferWorker.permille(2_500_000_000L, 5_000_000_000L))
        assertEquals(1_000, TransferWorker.permille(5_000_000_000L, 5_000_000_000L))
        assertEquals(1_000, TransferWorker.permille(6_000_000_000L, 5_000_000_000L))
        assertEquals(0, TransferWorker.permille(10L, 0L))
        assertEquals("5.0 GB", TransferWorker.formatBytes(5_000_000_000L))
        assertEquals("512 B", TransferWorker.formatBytes(512L))
        assertEquals("1.5 KB", TransferWorker.formatBytes(1_500L))
        assertEquals("EXTRACT", TransferWorker.extractInputData("op").getString("type"))
        assertEquals("op", TransferWorker.extractInputData("op").getString("operation_id"))
    }
}
