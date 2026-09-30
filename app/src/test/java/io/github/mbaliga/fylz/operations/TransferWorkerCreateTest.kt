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
import io.github.mbaliga.fylz.archive.FakeArchiveDecoder
import io.github.mbaliga.fylz.decoder.ArchiveWriteResult
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
 * `TransferWorker`'s CREATE path (`docs/agent/DESIGN-M35-CREATE.md` section 2.3 step 8) through
 * `work-testing`, mirroring [TransferWorkerExtractTest] exactly for the inverse direction: every
 * journaled outcome is `Result.success()`; `OperationRunner.enqueueCreate` tags and tracks the
 * real request; `recover` reconciles a planned create against the real `WorkManager`; the create
 * cancel receiver sets the CREATE plan's own flag, never the extract one's; the notification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TransferWorkerCreateTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var rootDir: File
    private lateinit var destinationDir: File
    private lateinit var destination: android.net.Uri
    private lateinit var stub: FakeArchiveDecoder
    private lateinit var journal: OperationJournal
    private lateinit var creator: ArchiveCreator
    private lateinit var factory: WorkerFactory

    @Before
    fun setUp() {
        rootDir = tempFolder.newFolder("root")
        destinationDir = File(rootDir, "dest").apply { mkdirs() }
        destination = ExtractTestArchives.treeUri("dest")
        val faulty = FaultyDocumentsProvider.install()
        faulty.volumeOverride = listOf(VolumeDescriptor(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, "Internal storage", rootDir, primary = true, removable = false, readOnly = false))
        stub = FakeArchiveDecoder()
        journal = OperationJournal(context)
        creator = ArchiveCreator(
            context = context,
            journal = journal,
            writerClient = { FakeArchiveDecoder.client(stub) },
            verifySettings = VerifySettings(context),
            workLookup = WorkLookup.NONE,
            streamInactivityMillis = 5_000L,
            cancelWaitMillis = 500L,
        )
        factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
                if (workerClassName == TransferWorker::class.java.name) TransferWorker(appContext, workerParameters, creatorFactory = { creator }) else null
        }
        val configuration = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .setTaskExecutor(SynchronousExecutor())
            .setWorkerFactory(factory)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
    }

    private fun sourceUri(relative: String): android.net.Uri = FylzFilesDocumentsProvider.documentUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, relative)

    private fun planAndStore(name: String = "f.txt"): String {
        File(rootDir, name).writeText("hello")
        val catalog = ExtractTestArchives.catalog(context, stub)
        val planner = CompressPlanner(context.contentResolver, catalog, volumeFor = { ExtractTestArchives.ext4() })
        val result = runBlocking {
            planner.plan(CompressRequest(listOf(sourceUri(name)), CompressFormat.ZIP, 6, SplitSize.Off, relativeToSelection = true, archiveName = "out", destinationFolder = destination), HeadlessCompressUi())
        }
        check(result is CompressPlanResult.Planned) { "expected Planned, got $result" }
        journal.putWithCreatePlan(result.operation, result.plan, result.manifest)
        return result.operation.id
    }

    private fun worker(operationId: String): TransferWorker =
        TestListenableWorkerBuilder<TransferWorker>(context, TransferWorker.createInputData(operationId)).setWorkerFactory(factory).build()

    @Test
    fun `a successful compress returns success and the journal holds the outcome`() {
        val id = planAndStore()
        val result = runBlocking { worker(id).doWork() }
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(OperationState.SUCCEEDED, journal.find(id)!!.state)
        assertTrue(File(destinationDir, "out.zip").isFile)
    }

    @Test
    fun `a second run on the same id refuses the claim and still returns success`() {
        val id = planAndStore()
        runBlocking { worker(id).doWork() }
        val before = journal.find(id)!!
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker(id).doWork() })
        assertEquals(before, journal.find(id))
        assertEquals(1, stub.writeCalls.size)
    }

    @Test
    fun `a decoder failure is journaled as FAILED and still returns success -- the chain is never poisoned`() {
        stub.writeFailure = ArchiveWriteResult.failed(ArchiveWriteResult.OUTCOME_CORRUPT, "boom")
        val id = planAndStore()
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker(id).doWork() })
        assertEquals(OperationState.FAILED, journal.find(id)!!.state)
    }

    @Test
    fun `a missing operation id is the one failure, and an unknown id is success with nothing journaled`() {
        val noId = TestListenableWorkerBuilder<TransferWorker>(context, androidx.work.Data.Builder().putString("type", FileOperationType.ARCHIVE.name).build()).setWorkerFactory(factory).build()
        assertTrue(runBlocking { noId.doWork() } is ListenableWorker.Result.Failure)
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker("never-planned").doWork() })
        assertTrue(journal.list().isEmpty())
    }

    @Test
    fun `enqueueCreate tags the request, tracks the operation by its id, and runs it through the real queue`() {
        val id = planAndStore()
        val runner = OperationRunner(CoroutineScope(SupervisorJob() + Dispatchers.Default), context)
        runBlocking { runner.enqueueCreate(id, itemCount = 1) }
        assertEquals(OperationState.SUCCEEDED, journal.find(id)!!.state)
        val infos = WorkManager.getInstance(context).getWorkInfosByTag(OperationRunner.createTag(id)).get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.SUCCEEDED, infos.single().state)
        assertTrue(infos.single().tags.contains("op:$id"))
        assertTrue("tracking ends with the work", runner.operations.value.isEmpty())
    }

    @Test
    fun `cancel on a planned create this runner never enqueued still flags it through the journal, never the extract table`() {
        val id = planAndStore()
        val runner = OperationRunner(CoroutineScope(SupervisorJob() + Dispatchers.Default), context)
        runner.cancel(id)
        assertTrue(journal.createPlan(id)!!.cancelRequested)
        runner.cancel(java.util.UUID.randomUUID().toString())
    }

    @Test
    fun `recover against the real WorkManager -- NEVER_RAN for an old queued row with no work, left alone with enqueued work`() {
        val orphan = planAndStore("orphan.txt")
        journal.put(journal.find(orphan)!!.copy(updatedAtMillis = 1_000L))
        val pending = planAndStore("pending.txt")
        journal.put(journal.find(pending)!!.copy(updatedAtMillis = 1_000L))
        val request = OneTimeWorkRequestBuilder<TransferWorker>()
            .setInputData(TransferWorker.createInputData(pending))
            .addTag(OperationRunner.createTag(pending))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueue(request).result.get()
        assertEquals(WorkInfo.State.ENQUEUED, WorkManager.getInstance(context).getWorkInfoById(request.id).get()!!.state)

        runBlocking { OperationRunner.recover(journal, context.contentResolver, WorkLookup.viaWorkManager(context), nowMillis = { 10_000_000L }) }

        assertEquals(OperationState.FAILED, journal.find(orphan)!!.state)
        assertTrue(journal.find(orphan)!!.items.all { it.errorCode == CreateErrorCodes.NEVER_RAN })
        assertEquals(OperationState.QUEUED, journal.find(pending)!!.state)
    }

    @Test
    fun `the create cancel receiver sets the CREATE plan's own flag for the operation it names`() {
        val id = planAndStore()
        CreateCancelReceiver().onReceive(context, CreateCancelReceiver.intent(context, id))
        assertTrue(journal.createPlan(id)!!.cancelRequested)
        CreateCancelReceiver().onReceive(context, android.content.Intent("something.else").putExtra(CreateCancelReceiver.EXTRA_OPERATION_ID, "x"))
        CreateCancelReceiver().onReceive(context, CreateCancelReceiver.intent(context, ""))
    }

    @Test
    fun `createInputData carries the type and operation id`() {
        assertEquals("ARCHIVE", TransferWorker.createInputData("op").getString("type"))
        assertEquals("op", TransferWorker.createInputData("op").getString("operation_id"))
    }
}
