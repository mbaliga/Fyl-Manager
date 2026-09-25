package io.github.mbaliga.fylz.operations

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.work.WorkInfo
import io.github.mbaliga.fylz.archive.ArchiveCatalog
import io.github.mbaliga.fylz.archive.ArchiveDocumentId
import io.github.mbaliga.fylz.archive.ArchiveHandle
import io.github.mbaliga.fylz.archive.ArchiveTreeEntry
import io.github.mbaliga.fylz.archive.ExtractFrameReader
import io.github.mbaliga.fylz.archive.ExtractFrameSink
import io.github.mbaliga.fylz.archive.ExtractProtocolException
import io.github.mbaliga.fylz.archive.ExtractStreamEnd
import io.github.mbaliga.fylz.decoder.ArchiveExtractResult
import io.github.mbaliga.fylz.decoder.ArchiveLimits
import io.github.mbaliga.fylz.decoder.DecoderCall
import io.github.mbaliga.fylz.decoder.DecoderClient
import io.github.mbaliga.fylz.storage.VolumeInfo
import io.github.mbaliga.fylz.storage.VolumeInfoResolver
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** The `errorCode`s an EXTRACT item can end with (design section 2.3). Coarse, never a stack trace. */
object ExtractErrorCodes {
    const val ARCHIVE_CHANGED = "ARCHIVE_CHANGED"
    const val ARCHIVE_UNAVAILABLE = "ARCHIVE_UNAVAILABLE"
    const val ARCHIVE_REFUSED = "ARCHIVE_REFUSED"
    const val ARCHIVE_FATAL = "ARCHIVE_FATAL"
    const val ARCHIVE_CRC_MISMATCH = "ARCHIVE_CRC_MISMATCH"
    const val ARCHIVE_ENTRY_UNREADABLE = "ARCHIVE_ENTRY_UNREADABLE"
    const val SIZE_MISMATCH = "SIZE_MISMATCH"
    const val INSUFFICIENT_SPACE = "INSUFFICIENT_SPACE"
    const val FILE_TOO_LARGE = "FILE_TOO_LARGE"
    const val PERMISSION_DENIED = "PERMISSION_DENIED"
    const val WRITE_FAILED = "WRITE_FAILED"
    const val PROTOCOL_ERROR = "PROTOCOL_ERROR"
    const val LIMIT_EXCEEDED = "LIMIT_EXCEEDED"
    const val DESTINATION_MISSING = "DESTINATION_MISSING"
    const val DECODER_UNAVAILABLE = "DECODER_UNAVAILABLE"
    const val CHECKSUM_MISMATCH = "CHECKSUM_MISMATCH"
    const val USER_CANCELLED = "USER_CANCELLED"
    const val WORK_CANCELLED = "WORK_CANCELLED"
    const val SKIPPED_CONFLICT = "SKIPPED_CONFLICT"
    const val NEVER_RAN = "NEVER_RAN"
    const val PROCESS_INTERRUPTED = "PROCESS_INTERRUPTED"
}

/** How one run of [ArchiveExtractor.run] ended, for the worker's return value. */
sealed interface ExtractRunOutcome {
    /** The row was not claimable (another run has it, it already ended, or it has no plan): nothing was done. */
    data object NotClaimed : ExtractRunOutcome

    /** The operation ended and its state is in the journal: `SUCCEEDED`, `PARTIAL`, `FAILED` or `CANCELLED`. */
    data class Finished(val state: OperationState) : ExtractRunOutcome

    /** WorkManager stopped the worker; the items are `PAUSED_BY_SYSTEM` with their staging kept. */
    data object PausedBySystem : ExtractRunOutcome
}

data class ExtractProgress(
    val itemIndex: Int,
    val itemCount: Int,
    val completedBytes: Long,
    val totalBytes: Long?,
    /** No frame has arrived yet: the engine is in its header pass ("Reading archive…"). */
    val readingArchive: Boolean,
)

/** The extractor's own cancel, thrown out of the drain when `extract_plans.cancel_requested` is seen. */
internal class ExtractCancelledException : IOException("The extraction was cancelled.")

/**
 * The run half of an EXTRACT operation (`docs/agent/DESIGN-M34-SELECTIVE-EXTRACT.md` section 2.3):
 * claim the row, re-open the archive through the catalog, lay the plan's ordinals out onto staged
 * targets, run **one** `extractRanges` pass on the isolated extraction instance with the frame
 * stream demultiplexed straight into the destination documents (a second pass once after a fatal
 * or a transport loss), verify, finalise, and write the outcome. Every outcome is written to the
 * journal before [run] returns, so the worker can return `Result.success()` for all of them.
 *
 * What differs from the design, recorded in REVIEW_QUEUE: a hardlink's target is **tee-written** to
 * every destination path during the pass (the design copies after it); the plan holds the archive's
 * root document Uri and catalog key, so staging (if any) is redone by the catalog rather than
 * persisted; an item is `SUCCEEDED` only when every planned entry of it completed.
 */
class ArchiveExtractor(
    private val context: Context,
    private val journal: OperationJournal,
    private val catalog: ArchiveCatalog,
    /** A fresh client of the extraction instance per operation; it is unbound when the run ends. */
    private val extractionClient: () -> DecoderClient,
    private val recycleBin: RecycleBinService = RecycleBinService(context, journal = journal),
    private val verifySettings: VerifySettings = VerifySettings(context),
    private val volumeFor: (Uri) -> VolumeInfo? = { VolumeInfoResolver.resolveForDestination(context, it) },
    private val workLookup: WorkLookup = WorkLookup.viaWorkManager(context),
    private val streamInactivityMillis: Long = DecoderClient.STREAM_INACTIVITY_MILLIS,
    private val cancelWaitMillis: Long = DecoderClient.EXTRACTION_CANCEL_WAIT_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val resolver: ContentResolver get() = context.contentResolver

    /**
     * Runs the operation [operationId] under the work [ownWorkId] (`null` outside WorkManager).
     * [stopReason] answers `WorkInfo.STOP_REASON_*` when the worker was stopped, so a
     * `CancellationException` can be told apart into a system pause and an app cancel.
     */
    suspend fun run(
        operationId: String,
        ownWorkId: UUID? = null,
        stopReason: () -> Int = { WorkInfo.STOP_REASON_NOT_STOPPED },
        onProgress: (ExtractProgress) -> Unit = {},
    ): ExtractRunOutcome = withContext(Dispatchers.IO) {
        val before = journal.find(operationId) ?: return@withContext ExtractRunOutcome.NotClaimed
        if (before.type != FileOperationType.EXTRACT) return@withContext ExtractRunOutcome.NotClaimed
        val plan = journal.extractPlan(operationId) ?: return@withContext ExtractRunOutcome.NotClaimed
        val claimable = HashSet(CLAIMABLE)
        if (before.state == OperationState.RUNNING) {
            val others = workLookup.activeWorkIds(OperationRunner.extractTag(operationId)) - setOfNotNull(ownWorkId)
            if (others.isEmpty()) claimable += OperationState.RUNNING
        }
        if (!journal.claimExtract(operationId, claimable)) {
            Log.i(TAG, "Extraction $operationId not claimed (state ${before.state})")
            return@withContext ExtractRunOutcome.NotClaimed
        }
        val operation = journal.find(operationId) ?: return@withContext ExtractRunOutcome.NotClaimed
        val client = extractionClient()
        try {
            Run(operation, plan, client, stopReason, onProgress).execute()
        } finally {
            client.unbind()
        }
    }

    // ------------------------------------------------------------------------------------------

    /** One destination path an ordinal is written to: the item and the path relative to its root (`""` = the root file itself). */
    private class Target(val itemIndex: Int, val relativePath: String)

    /** One open output of the entry being streamed. */
    private class Output(val target: Target, val node: DocNode, val stream: OutputStream)

    private class WrittenFile(val itemIndex: Int, val relativePath: String, val node: DocNode, val digest: String)

    /** One pass's verdict: `retryFrom` is the ordinal a second pass resumes at, or `null` when none is due. */
    private class PassResult(val retryFrom: Int?, val bytesCompleted: Long, val entriesDone: Int)

    private inner class Run(
        private var operation: FileOperation,
        private val plan: ExtractPlan,
        private val client: DecoderClient,
        private val stopReason: () -> Int,
        private val onProgress: (ExtractProgress) -> Unit,
    ) : ExtractFrameSink {
        private val id = operation.id
        private val items: MutableList<OperationItem> = operation.items.toMutableList()
        private val planItems: Map<Int, ExtractPlanItem> = plan.items.associateBy { it.itemIndex }

        private lateinit var handle: ArchiveHandle
        private lateinit var destination: DocNode
        private lateinit var index: NameIndex
        private var caseInsensitive = false

        /** Items this run is extracting (not `SUCCEEDED` before it, not skipped by a conflict rule). */
        private val pending = LinkedHashSet<Int>()
        private val itemFailure = HashMap<Int, String>()
        private val targets = HashMap<Int, MutableList<Target>>()
        private val directoryOrdinals = HashSet<Int>()
        private val itemOrdinals = HashMap<Int, OrdinalBitmap>()
        private val itemExpectedBytes = HashMap<Int, Long?>()
        private val itemCompletedBytes = HashMap<Int, Long>()
        private val itemThrottles = HashMap<Int, ProgressWriteThrottle>()
        private val stagedRoots = HashMap<Int, DocNode>()
        private val directoryNodes = HashMap<String, DocNode>()
        private val componentNames = HashMap<String, String>()
        private val siblingNames = HashMap<String, HashSet<String>>()
        private val finalNames = HashMap<Int, String>()
        private val replaceExisting = HashMap<Int, DocNode>()

        private val completed = OrdinalBitmap()
        private val failedOrdinals = OrdinalBitmap()
        private val digests = HashMap<Int, String>()
        private val writtenFiles = ArrayList<WrittenFile>()
        private var lastTerminalOrdinal = -1
        private var open: OpenEntry? = null

        private val busy = AtomicBoolean(false)
        private val drainedBytes = AtomicLong(0L)

        /** Set once the outcome is written; a drain still running then stops at its next frame and writes nothing more. */
        @Volatile
        private var finished = false
        private var completedBytesBefore = 0L
        private var readingArchive = true
        private var lastCancelCheck = 0L
        private var cancelSeen = false
        private var lastProgressReport = 0L
        private val refreshThrottle = ProgressWriteThrottle(now = clock)

        private inner class OpenEntry(val ordinal: Int, val declared: Long, val outputs: MutableList<Output>) {
            val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            var discarding = outputs.isEmpty()
        }

        suspend fun execute(): ExtractRunOutcome {
            try {
                if (cancelRequested(force = true)) return finishCancelled(ExtractErrorCodes.USER_CANCELLED)
                val destinationUri = operation.destination ?: return failEverything(ExtractErrorCodes.DESTINATION_MISSING)
                val ref = try {
                    ArchiveDocumentId.parse(plan.archiveUri).archive
                } catch (failure: IllegalArgumentException) {
                    return failEverything(ExtractErrorCodes.ARCHIVE_UNAVAILABLE)
                }
                val opened = try {
                    catalog.open(ref)
                } catch (failure: ArchiveCatalog.Failure) {
                    Log.w(TAG, "Extraction $id: the archive could not be opened: ${failure.message}")
                    return failEverything(ExtractErrorCodes.ARCHIVE_UNAVAILABLE)
                }
                if (!opened.retain()) return failEverything(ExtractErrorCodes.ARCHIVE_UNAVAILABLE)
                handle = opened
                try {
                    if (handle.key != plan.catalogKey) return failEverything(ExtractErrorCodes.ARCHIVE_CHANGED)
                    destination = DocNode.loadDestination(resolver, destinationUri) ?: return failEverything(ExtractErrorCodes.DESTINATION_MISSING)
                    val volume = volumeFor(destinationUri)
                    caseInsensitive = volume?.caseInsensitive == true || plan.sanitize
                    val limits = plan.limits.copy(
                        maxTotalUncompressedBytes = minOf(plan.limits.maxTotalUncompressedBytes, ArchiveLimits.totalCap(volume?.freeBytes, plan.consent)),
                    )
                    prepareItems()
                    index = NameIndex(destination.children(resolver), caseInsensitive)
                    decideConflicts()
                    layOut()
                    createEmptyItems()
                    runPasses(limits)
                    settleIncompleteItems()
                    verify(destinationUri)
                    finalizeItems()
                    return finish()
                } finally {
                    handle.release()
                }
            } catch (cancelled: ExtractCancelledException) {
                return finishCancelled(ExtractErrorCodes.USER_CANCELLED)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    if (stopReason() == WorkInfo.STOP_REASON_CANCELLED_BY_APP) {
                        finishCancelled(ExtractErrorCodes.WORK_CANCELLED)
                    } else {
                        pauseBySystem()
                    }
                }
                throw cancelled
            } catch (failure: Throwable) {
                Log.w(TAG, "Extraction $id failed with ${failure.javaClass.simpleName}")
                closeOpenEntry(deleteDocuments = true)
                return failEverything(failure.javaClass.simpleName)
            }
        }

        // -------------------------------------------------------------- claim aftermath

        /** Pending items; a staged document a previous run recorded is deleted, its item starts over. */
        private fun prepareItems() {
            items.forEachIndexed { i, item ->
                if (item.state == OperationState.SUCCEEDED) {
                    completedBytesBefore += item.completedBytes
                    return@forEachIndexed
                }
                item.stagingUri?.let { staging -> DocNode.load(resolver, staging)?.delete(resolver) }
                items[i] = item.copy(state = OperationState.RUNNING, stagingUri = null, completedBytes = 0L, errorCode = null)
                journal.updateItem(id, items[i], refresh = false)
                pending += i
            }
        }

        /** The conflict rule of every pending item, against the destination as it is now. */
        private fun decideConflicts() {
            pending.toList().forEach { i ->
                val planItem = planItems[i] ?: run { failItem(i, ExtractErrorCodes.ARCHIVE_CHANGED); return@forEach }
                var name = planItem.finalName
                val existing = index.find(name)
                if (existing == null) {
                    finalNames[i] = name
                    index.reserve(name)
                    return@forEach
                }
                when (planItem.conflictPolicy) {
                    ConflictPolicy.SKIP, ConflictPolicy.ASK -> skipItem(i)
                    ConflictPolicy.KEEP_BOTH -> {
                        // The planned Keep-both name was taken since planning: the next free one, from the requested name.
                        name = index.uniqueName(planItem.requestedName)
                        finalNames[i] = name
                    }
                    ConflictPolicy.REPLACE -> {
                        finalNames[i] = name
                        replaceExisting[i] = existing
                    }
                    ConflictPolicy.REPLACE_IF_NEWER -> {
                        val root = handle.tree.entry(planItem.rootPath)
                        val sourceModified = root?.takeIf { it.mtimeKnown }?.mtimeEpochSeconds?.times(1000L)
                        val existingModified = existing.lastModified
                        if (sourceModified != null && existingModified != null && sourceModified > existingModified) {
                            finalNames[i] = name
                            replaceExisting[i] = existing
                        } else {
                            skipItem(i)
                        }
                    }
                }
            }
        }

        private fun skipItem(i: Int) {
            pending -= i
            items[i] = items[i].copy(state = OperationState.SUCCEEDED, errorCode = ExtractErrorCodes.SKIPPED_CONFLICT, stagingUri = null)
            journal.updateItem(id, items[i], refresh = false)
        }

        /** Every pending item's ordinals and destination paths, from the tree as listed. */
        private fun layOut() {
            pending.toList().forEach { i ->
                val planItem = planItems[i]!!
                val ordinals = OrdinalBitmap()
                var expected = 0L
                var unknown = false
                fun add(ordinal: Int, relativePath: String, bytes: Long?, directory: Boolean) {
                    if (ordinal !in plan.ordinals) return
                    targets.getOrPut(ordinal) { ArrayList(1) } += Target(i, relativePath)
                    if (directory) directoryOrdinals += ordinal
                    ordinals.set(ordinal)
                    if (!directory) {
                        if (bytes == null) unknown = true else expected += bytes
                    }
                }
                fun visit(entry: ArchiveTreeEntry, relativePath: String) {
                    when {
                        entry.isFile -> add(entry.ordinal, relativePath, entry.uncompressedBytes.takeIf { entry.sizeKnown }, directory = false)
                        entry.isDirectory -> {
                            if (!entry.isImplicit) add(entry.ordinal, relativePath, null, directory = true)
                            handle.tree.children(entry.path).forEach { child -> visit(child, if (relativePath.isEmpty()) child.name else "$relativePath/${child.name}") }
                        }
                        entry.isHardlink -> handle.tree.resolveHardlink(entry)?.let { target ->
                            add(target.ordinal, relativePath, target.uncompressedBytes.takeIf { target.sizeKnown }, directory = false)
                        }
                        else -> Unit
                    }
                }
                if (planItem.rootPath.isEmpty()) {
                    handle.tree.children("").forEach { child -> visit(child, child.name) }
                } else {
                    val root = handle.tree.entry(planItem.rootPath)
                    if (root == null) {
                        failItem(i, ExtractErrorCodes.ARCHIVE_CHANGED)
                        return@forEach
                    }
                    if (root.isDirectory) {
                        if (!root.isImplicit) add(root.ordinal, "", null, directory = true)
                        handle.tree.children(root.path).forEach { child -> visit(child, child.name) }
                    } else {
                        visit(root, "")
                    }
                }
                itemOrdinals[i] = ordinals
                itemExpectedBytes[i] = if (unknown) null else expected
                itemCompletedBytes[i] = 0L
            }
        }

        /** An item whose ordinals are all directories the tree never listed (or empty): its root exists, and that is all. */
        private fun createEmptyItems() {
            pending.toList().forEach { i ->
                val ordinals = itemOrdinals[i] ?: return@forEach
                if (!ordinals.isEmpty) return@forEach
                runCatching { stagedRoot(i) }.onFailure { failItem(i, writeFailureCode(it)) }
            }
        }

        // -------------------------------------------------------------- the pass(es)

        private fun runBitmap(): OrdinalBitmap {
            val all = OrdinalBitmap()
            pending.forEach { i -> if (i !in itemFailure) itemOrdinals[i]?.ordinals()?.forEach(all::set) }
            return all.after(0, minus = completed).after(0, minus = failedOrdinals)
        }

        private suspend fun runPasses(limits: ArchiveLimits) {
            var bitmap = runBitmap()
            if (bitmap.isEmpty) return
            reportProgress(force = true)
            var pass = runPass(bitmap, limits)
            if (pass.retryFrom != null) {
                bitmap = runBitmap().after(pass.retryFrom!!)
                if (!bitmap.isEmpty) {
                    val reduced = limits.copy(
                        maxTotalUncompressedBytes = (limits.maxTotalUncompressedBytes - pass.bytesCompleted).coerceAtLeast(0L),
                        maxEntries = (limits.maxEntries - pass.entriesDone).coerceAtLeast(1),
                    )
                    pass = runPass(bitmap, reduced)
                    if (pass.retryFrom != null) Log.w(TAG, "Extraction $id: the re-issued pass did not finish either")
                }
            }
        }

        /** One `extractRanges` call; `retryFrom` is the ordinal a second pass resumes at, or `null` when none is due. */
        private suspend fun runPass(bitmap: OrdinalBitmap, limits: ArchiveLimits): PassResult {
            val reader = ExtractFrameReader(isPlanned = { it in bitmap }, planTotalBytes = limits.maxTotalUncompressedBytes)
            var streamEnd: ExtractStreamEnd? = null
            val bytesBefore = completedBytesBefore + itemCompletedBytes.values.sum()
            val entriesBefore = completed.cardinality + failedOrdinals.cardinality
            val call: DecoderCall<ArchiveExtractResult> = try {
                handle.openDescriptor().use { pfd ->
                    client.callStreaming(
                        archive = pfd,
                        inactivityMillis = streamInactivityMillis,
                        drain = { input -> streamEnd = reader.read(input, this) },
                        busy = { busy.get() },
                        progress = { drainedBytes.get() },
                        cancelled = { cancelRequested(force = false) },
                        drainFailureWaitMillis = cancelWaitMillis,
                    ) { service, sink -> service.extractRanges(pfd, limits, bitmap.toByteArray(), sink) }
                }
            } catch (protocol: ExtractProtocolException) {
                Log.w(TAG, "Extraction $id: protocol error: ${protocol.message}")
                closeOpenEntry(deleteDocuments = true)
                client.unbind()
                failPending(ExtractErrorCodes.PROTOCOL_ERROR)
                return PassResult(null, 0L, 0)
            }
            closeOpenEntry(deleteDocuments = true)
            if (cancelSeen || cancelRequested(force = true)) throw ExtractCancelledException()
            val bytesCompleted = completedBytesBefore + itemCompletedBytes.values.sum() - bytesBefore
            val entriesDone = completed.cardinality + failedOrdinals.cardinality - entriesBefore
            val resumeAfterLoss = PassResult(lastTerminalOrdinal + 1, bytesCompleted, entriesDone)
            return when (call) {
                is DecoderCall.Ok -> {
                    val result = call.value
                    when (result.outcome) {
                        ArchiveExtractResult.OUTCOME_OK -> if (streamEnd is ExtractStreamEnd.Done) PassResult(null, bytesCompleted, entriesDone) else resumeAfterLoss
                        ArchiveExtractResult.OUTCOME_CORRUPT -> {
                            Log.w(TAG, "Extraction $id: the archive went fatal at ${result.stopOrdinal}: ${result.message}")
                            val from = if (result.stopOrdinal >= 0) result.stopOrdinal else lastTerminalOrdinal + 1
                            PassResult(from, bytesCompleted, entriesDone)
                        }
                        ArchiveExtractResult.OUTCOME_REFUSED -> {
                            failPending(ExtractErrorCodes.ARCHIVE_REFUSED)
                            Log.w(TAG, "Extraction $id refused by the size policy: ${result.message}")
                            PassResult(null, bytesCompleted, entriesDone)
                        }
                        ArchiveExtractResult.OUTCOME_LIMIT_EXCEEDED -> {
                            failPending(ExtractErrorCodes.LIMIT_EXCEEDED)
                            PassResult(null, bytesCompleted, entriesDone)
                        }
                        ArchiveExtractResult.OUTCOME_CANCELLED -> resumeAfterLoss
                        else -> {
                            failPending(ExtractErrorCodes.ARCHIVE_UNAVAILABLE)
                            PassResult(null, bytesCompleted, entriesDone)
                        }
                    }
                }
                DecoderCall.TimedOut, DecoderCall.Failed -> resumeAfterLoss
            }
        }

        // -------------------------------------------------------------- the frame sink

        override fun begin(ordinal: Int, declaredBytes: Long, kind: Int, rawPath: ByteArray) {
            if (finished) throw ExtractCancelledException()
            checkCancel()
            val expected = handle.rawPaths[ordinal]?.toByteArray(Charsets.UTF_8)
            if (expected == null || !expected.contentEquals(rawPath)) {
                throw ExtractProtocolException("BEGIN $ordinal carries a path the plan does not (${rawPath.size} bytes)")
            }
            readingArchive = false
            val paths = targets[ordinal].orEmpty().filter { it.itemIndex in pending && it.itemIndex !in itemFailure }
            val outputs = ArrayList<Output>(paths.size)
            if (kind == ExtractFrameReader.KIND_FILE) {
                paths.forEach { target ->
                    try {
                        val node = createFile(target)
                        val stream = withBusy { resolver.openOutputStream(node.uri, "w") } ?: throw IOException("no output stream for ${node.name}")
                        outputs += Output(target, node, stream)
                    } catch (failure: Exception) {
                        if (failure is ExtractCancelledException) throw failure
                        failItem(target.itemIndex, writeFailureCode(failure))
                    }
                }
            } else if (kind == ExtractFrameReader.KIND_DIRECTORY) {
                paths.forEach { target ->
                    try {
                        ensureDirectory(target.itemIndex, target.relativePath)
                    } catch (failure: Exception) {
                        if (failure is ExtractCancelledException) throw failure
                        failItem(target.itemIndex, writeFailureCode(failure))
                    }
                }
            }
            open = OpenEntry(ordinal, declaredBytes, outputs)
            reportProgress(force = false)
        }

        override fun data(ordinal: Int, buffer: ByteArray, offset: Int, length: Int) {
            if (finished) throw ExtractCancelledException()
            val entry = open ?: throw ExtractProtocolException("DATA $ordinal with no open entry")
            drainedBytes.addAndGet(length.toLong())
            if (entry.discarding) return
            entry.digest.update(buffer, offset, length)
            val iterator = entry.outputs.iterator()
            while (iterator.hasNext()) {
                val output = iterator.next()
                try {
                    withBusy { output.stream.write(buffer, offset, length) }
                } catch (failure: IOException) {
                    iterator.remove()
                    discardOutput(output)
                    failItem(output.target.itemIndex, writeFailureCode(failure))
                }
            }
            entry.written += length
            if (entry.outputs.isEmpty()) entry.discarding = true
            reportProgress(force = false)
        }

        override fun end(ordinal: Int, bytes: Long, warning: String?) {
            if (finished) throw ExtractCancelledException()
            val entry = open ?: throw ExtractProtocolException("END $ordinal with no open entry")
            open = null
            lastTerminalOrdinal = ordinal
            if (warning != null) Log.i(TAG, "Extraction $id: entry $ordinal ended with a warning: $warning")
            if (ordinal in directoryOrdinals) {
                completed.set(ordinal)
                return
            }
            val sizeOk = bytes == entry.written && (entry.declared == ExtractFrameReader.DECLARED_UNKNOWN || entry.declared == entry.written)
            if (!sizeOk) {
                entry.outputs.forEach { output -> discardOutput(output); failItem(output.target.itemIndex, ExtractErrorCodes.SIZE_MISMATCH) }
                failedOrdinals.set(ordinal)
                return
            }
            val digest = entry.digest.digest().joinToString("") { "%02x".format(it) }
            entry.outputs.forEach { output ->
                try {
                    withBusy { output.stream.close() }
                    writtenFiles += WrittenFile(output.target.itemIndex, output.target.relativePath, output.node, digest)
                    val i = output.target.itemIndex
                    val total = (itemCompletedBytes[i] ?: 0L) + entry.written
                    itemCompletedBytes[i] = total
                    val throttle = itemThrottles.getOrPut(i) { ProgressWriteThrottle(now = clock) }
                    val expected = itemExpectedBytes[i]
                    if (throttle.shouldWrite(total, isFinal = expected != null && total >= expected)) {
                        items[i] = items[i].copy(completedBytes = total)
                        journal.updateItem(id, items[i], refresh = false)
                    }
                } catch (failure: IOException) {
                    discardOutput(output)
                    failItem(output.target.itemIndex, writeFailureCode(failure))
                }
            }
            digests[ordinal] = digest
            completed.set(ordinal)
            if (refreshThrottle.shouldWrite(drainedBytes.get(), isFinal = false)) journal.refresh()
            reportProgress(force = false)
        }

        override fun fail(ordinal: Int, kind: Int, message: String) {
            if (finished) throw ExtractCancelledException()
            val entry = open
            open = null
            lastTerminalOrdinal = ordinal
            failedOrdinals.set(ordinal)
            val code = when (kind) {
                ExtractFrameReader.FAIL_CRC -> ExtractErrorCodes.ARCHIVE_CRC_MISMATCH
                ExtractFrameReader.FAIL_SIZE -> ExtractErrorCodes.SIZE_MISMATCH
                else -> ExtractErrorCodes.ARCHIVE_ENTRY_UNREADABLE
            }
            Log.i(TAG, "Extraction $id: entry $ordinal failed ($code): $message")
            entry?.outputs?.forEach(::discardOutput)
            targets[ordinal].orEmpty().forEach { target -> if (target.itemIndex in pending) failItem(target.itemIndex, code) }
        }

        /** The entry open when the stream ended or the pass was abandoned: its partial documents go. */
        private fun closeOpenEntry(deleteDocuments: Boolean) {
            val entry = open ?: return
            open = null
            entry.outputs.forEach { output -> if (deleteDocuments) discardOutput(output) else runCatching { output.stream.close() } }
        }

        private fun discardOutput(output: Output) {
            runCatching { output.stream.close() }
            runCatching { output.node.delete(resolver) }
        }

        // -------------------------------------------------------------- destination documents

        private fun stagedRoot(i: Int): DocNode = stagedRoots.getOrPut(i) {
            val planItem = planItems[i]!!
            val root = handle.tree.entry(planItem.rootPath)
            val directory = planItem.rootPath.isEmpty() || root?.isDirectory == true
            val stagingName = stagingName(id, i, finalNames[i] ?: planItem.finalName)
            val node = withBusy {
                if (directory) {
                    destination.createChild(resolver, DocumentsContract.Document.MIME_TYPE_DIR, stagingName)
                } else {
                    destination.createChild(resolver, "application/octet-stream", stagingName)
                }
            }
            items[i] = items[i].copy(stagingUri = node.uri)
            journal.updateItem(id, items[i], refresh = false)
            node
        }

        private fun ensureDirectory(i: Int, relativePath: String): DocNode {
            if (relativePath.isEmpty()) return stagedRoot(i)
            val key = "$i/$relativePath"
            directoryNodes[key]?.let { return it }
            val parent = ensureDirectory(i, relativePath.substringBeforeLast('/', ""))
            val name = componentName(i, relativePath)
            val node = withBusy { parent.createChild(resolver, DocumentsContract.Document.MIME_TYPE_DIR, name) }
            directoryNodes[key] = node
            return node
        }

        private fun createFile(target: Target): DocNode {
            val i = target.itemIndex
            if (target.relativePath.isEmpty()) return stagedRoot(i)
            val parent = ensureDirectory(i, target.relativePath.substringBeforeLast('/', ""))
            val name = componentName(i, target.relativePath)
            return withBusy { parent.createChild(resolver, "application/octet-stream", name) }
        }

        /** The final name of one path component under an item, sanitised on a FAT family and uniquified against its siblings. */
        private fun componentName(i: Int, relativePath: String): String {
            val key = "$i/$relativePath"
            componentNames[key]?.let { return it }
            val raw = relativePath.substringAfterLast('/')
            var name = if (plan.sanitize) PreflightPolicy.sanitizedName(raw) else raw
            val siblings = siblingNames.getOrPut("$i/${relativePath.substringBeforeLast('/', "")}") { HashSet() }
            fun keyOf(candidate: String) = if (caseInsensitive) candidate.lowercase() else candidate
            if (!siblings.add(keyOf(name))) {
                val dot = name.lastIndexOf('.')
                val base = if (dot > 0) name.substring(0, dot) else name
                val extension = if (dot > 0) name.substring(dot) else ""
                var n = 2
                while (!siblings.add(keyOf("$base ($n)$extension"))) n += 1
                name = "$base ($n)$extension"
            }
            componentNames[key] = name
            return name
        }

        private inline fun <T> withBusy(block: () -> T): T {
            busy.set(true)
            try {
                return block()
            } finally {
                busy.set(false)
            }
        }

        private fun writeFailureCode(failure: Throwable): String {
            val message = failure.message.orEmpty()
            Log.w(TAG, "Extraction $id: a destination write failed with ${failure.javaClass.simpleName}: $message")
            return when {
                message.contains("ENOSPC") || message.contains("No space left", ignoreCase = true) -> ExtractErrorCodes.INSUFFICIENT_SPACE
                message.contains("EFBIG") || message.contains("File too large", ignoreCase = true) -> ExtractErrorCodes.FILE_TOO_LARGE
                message.contains("EACCES") || message.contains("Permission denied", ignoreCase = true) || failure is SecurityException -> ExtractErrorCodes.PERMISSION_DENIED
                else -> ExtractErrorCodes.WRITE_FAILED
            }
        }

        // -------------------------------------------------------------- items and outcome

        private fun failItem(i: Int, code: String) {
            if (i in itemFailure) return
            itemFailure[i] = code
            items[i] = items[i].copy(state = OperationState.FAILED, errorCode = code)
            journal.updateItem(id, items[i], refresh = false)
        }

        private fun failPending(code: String) {
            pending.forEach { i -> if (i !in itemFailure && !itemComplete(i)) failItem(i, code) }
        }

        private fun itemComplete(i: Int): Boolean {
            val ordinals = itemOrdinals[i] ?: return false
            return ordinals.ordinals().all { it in completed }
        }

        /** After the pass(es): an item with an entry still missing did not make it. */
        private fun settleIncompleteItems() {
            pending.forEach { i -> if (i !in itemFailure && !itemComplete(i)) failItem(i, ExtractErrorCodes.ARCHIVE_FATAL) }
        }

        private fun verify(destinationUri: Uri) {
            val verifying = shouldVerify(verifySettings.mode.value, classifyDestination(context, destinationUri))
            pending.forEach { i ->
                if (i in itemFailure) return@forEach
                val files = writtenFiles.filter { it.itemIndex == i }
                if (verifying) {
                    for (file in files) {
                        val reread = try {
                            resolver.openInputStream(file.node.uri)?.use(::sha256) ?: throw IOException("no input stream for ${file.node.name}")
                        } catch (failure: IOException) {
                            failItem(i, ExtractErrorCodes.CHECKSUM_MISMATCH)
                            break
                        }
                        drainedBytes.incrementAndGet()
                        if (reread != file.digest) {
                            failItem(i, ExtractErrorCodes.CHECKSUM_MISMATCH)
                            break
                        }
                    }
                }
                val rootFile = files.singleOrNull { it.relativePath.isEmpty() }
                if (rootFile != null && i !in itemFailure) items[i] = items[i].copy(sha256 = rootFile.digest)
            }
            if (verifying) journal.putEntryDigests(id, digests)
        }

        private suspend fun finalizeItems() {
            val planner = TargetPlanner(resolver, recycleBin, destination, index)
            pending.forEach { i ->
                if (i in itemFailure) return@forEach
                val staged = stagedRoots[i]
                if (staged == null) {
                    failItem(i, ExtractErrorCodes.ARCHIVE_FATAL)
                    return@forEach
                }
                try {
                    val fresh = staged.refresh(resolver) ?: throw IOException("the staged item disappeared")
                    val final = planner.finalizeTarget(TargetPlan(finalNames[i] ?: planItems[i]!!.finalName, replaceExisting[i]), fresh)
                    items[i] = items[i].copy(
                        state = OperationState.SUCCEEDED,
                        errorCode = null,
                        stagingUri = null,
                        finalUri = final.uri,
                        completedBytes = itemExpectedBytes[i] ?: itemCompletedBytes[i] ?: 0L,
                    )
                    journal.updateItem(id, items[i], refresh = false)
                } catch (failure: Exception) {
                    Log.w(TAG, "Extraction $id: finalising item $i failed with ${failure.javaClass.simpleName}")
                    failItem(i, failure.javaClass.simpleName)
                }
            }
        }

        private fun finish(): ExtractRunOutcome {
            finished = true
            itemFailure.keys.forEach { i -> deleteStaged(i) }
            val state = when {
                items.all { it.state == OperationState.SUCCEEDED } -> OperationState.SUCCEEDED
                items.any { it.state == OperationState.SUCCEEDED } -> OperationState.PARTIAL
                else -> OperationState.FAILED
            }
            journal.updateOperationState(id, state)
            reportProgress(force = true)
            return ExtractRunOutcome.Finished(state)
        }

        private fun failEverything(code: String): ExtractRunOutcome {
            finished = true
            items.forEachIndexed { i, item ->
                if (item.state == OperationState.SUCCEEDED) return@forEachIndexed
                failItem(i, code)
                deleteStaged(i)
            }
            journal.updateOperationState(id, if (items.any { it.state == OperationState.SUCCEEDED }) OperationState.PARTIAL else OperationState.FAILED)
            return ExtractRunOutcome.Finished(journal.find(id)?.state ?: OperationState.FAILED)
        }

        private fun finishCancelled(code: String): ExtractRunOutcome {
            finished = true
            closeOpenEntry(deleteDocuments = true)
            items.forEachIndexed { i, item ->
                if (item.state == OperationState.SUCCEEDED) return@forEachIndexed
                deleteStaged(i)
                items[i] = item.copy(state = OperationState.CANCELLED, errorCode = code, stagingUri = null)
                journal.updateItem(id, items[i], refresh = false)
            }
            journal.updateOperationState(id, OperationState.CANCELLED)
            return ExtractRunOutcome.Finished(OperationState.CANCELLED)
        }

        private fun pauseBySystem(): ExtractRunOutcome {
            finished = true
            closeOpenEntry(deleteDocuments = false)
            items.forEachIndexed { i, item ->
                if (item.state == OperationState.SUCCEEDED || item.state == OperationState.FAILED) return@forEachIndexed
                items[i] = item.copy(state = OperationState.PAUSED_BY_SYSTEM)
                journal.updateItem(id, items[i], refresh = false)
            }
            journal.updateOperationState(id, OperationState.PAUSED_BY_SYSTEM)
            return ExtractRunOutcome.PausedBySystem
        }

        private fun deleteStaged(i: Int) {
            val staged = stagedRoots.remove(i) ?: items[i].stagingUri?.let { DocNode.load(resolver, it) }
            staged?.let { runCatching { it.delete(resolver) } }
            if (items[i].stagingUri != null) {
                items[i] = items[i].copy(stagingUri = null)
                journal.updateItem(id, items[i], refresh = false)
            }
        }

        // -------------------------------------------------------------- cancel and progress

        /** `extract_plans.cancel_requested`, read at most every [CANCEL_POLL_MILLIS] unless [force]d. */
        private fun cancelRequested(force: Boolean): Boolean {
            if (cancelSeen) return true
            val now = clock()
            if (!force && now - lastCancelCheck < CANCEL_POLL_MILLIS) return false
            lastCancelCheck = now
            cancelSeen = journal.isCancelRequested(id)
            return cancelSeen
        }

        private fun checkCancel() {
            if (cancelRequested(force = false)) throw ExtractCancelledException()
        }

        private fun reportProgress(force: Boolean) {
            val now = clock()
            if (!force && now - lastProgressReport < PROGRESS_REPORT_MILLIS) return
            lastProgressReport = now
            val expected = pending.map { itemExpectedBytes[it] }
            val total = if (expected.any { it == null }) null else expected.sumOf { it!! } + completedBytesBefore
            val completedBytes = completedBytesBefore + itemCompletedBytes.values.sum() + (open?.written ?: 0L)
            val current = pending.firstOrNull { it !in itemFailure && !itemComplete(it) } ?: pending.lastOrNull() ?: 0
            onProgress(ExtractProgress(current, items.size, completedBytes, total, readingArchive))
        }
    }

    companion object {
        private const val TAG = "ArchiveExtractor"

        /** The states a run may claim from unconditionally (design section 2.3 step 1). */
        val CLAIMABLE: Set<OperationState> = setOf(OperationState.QUEUED, OperationState.PAUSED_BY_SYSTEM, OperationState.NEEDS_ATTENTION)

        /** How often the cancel flag is read (a SQLite read) while frames flow. */
        const val CANCEL_POLL_MILLIS = 250L

        const val PROGRESS_REPORT_MILLIS = 250L

        internal fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
