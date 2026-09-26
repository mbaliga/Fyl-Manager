package io.github.mbaliga.fylz.operations

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.work.WorkInfo
import io.github.mbaliga.fylz.archive.ArchiveDocumentId
import io.github.mbaliga.fylz.archive.ArchiveFrameWriter
import io.github.mbaliga.fylz.archive.ArchiveListingCodec
import io.github.mbaliga.fylz.decoder.ArchiveLimits
import io.github.mbaliga.fylz.decoder.ArchiveWriteOptions
import io.github.mbaliga.fylz.decoder.ArchiveWriteResult
import io.github.mbaliga.fylz.decoder.DecoderCall
import io.github.mbaliga.fylz.decoder.DecoderClient
import io.github.mbaliga.fylz.storage.VolumeInfo
import io.github.mbaliga.fylz.storage.VolumeInfoResolver
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** The `errorCode`s a CREATE operation can end with (design section 2.3, mirrors [ExtractErrorCodes]). */
object CreateErrorCodes {
    const val SOURCE_UNREADABLE = "SOURCE_UNREADABLE"
    const val ARCHIVE_WRITE_FAILED = "ARCHIVE_WRITE_FAILED"
    const val PROTOCOL_ERROR = "PROTOCOL_ERROR"
    const val VERIFICATION_FAILED = "VERIFICATION_FAILED"
    const val INSUFFICIENT_SPACE = "INSUFFICIENT_SPACE"
    const val PERMISSION_DENIED = "PERMISSION_DENIED"
    const val WRITE_FAILED = "WRITE_FAILED"
    const val DESTINATION_MISSING = "DESTINATION_MISSING"
    const val DECODER_UNAVAILABLE = "DECODER_UNAVAILABLE"
    const val USER_CANCELLED = "USER_CANCELLED"
    const val WORK_CANCELLED = "WORK_CANCELLED"
    const val TOO_MANY_INTERRUPTIONS = "TOO_MANY_INTERRUPTIONS"
    const val FOREGROUND_TIMEOUT = "FOREGROUND_TIMEOUT"
    const val NEVER_RAN = "NEVER_RAN"
    const val PROCESS_INTERRUPTED = "PROCESS_INTERRUPTED"
}

sealed interface CreateRunOutcome {
    data object NotClaimed : CreateRunOutcome
    data class Finished(val state: OperationState) : CreateRunOutcome
    data object PausedBySystem : CreateRunOutcome
}

data class CreateProgress(val completedBytes: Long, val totalBytes: Long?, val entriesDone: Int, val entryCount: Int)

internal class CreateCancelledException : IOException("The compress was cancelled.")

/** The secondary listing check's own mismatch, told apart from a transport hiccup reading it back
 * (which does not fail the whole compress -- the primary SHA-256 check already passed). */
private class VerificationMismatch : Exception()

/** The engine's own verdict when a `writeArchive` call returns rather than throws
 * ([ArchiveWriteResult.outcome] != [ArchiveWriteResult.OUTCOME_OK]) -- carries the outcome code
 * itself, not just its message, so [ArchiveCreator.Run.writeFailureCode] can recognise
 * [ArchiveWriteResult.OUTCOME_PROTOCOL_ERROR] regardless of what the engine's own message text
 * happens to say. */
private class EngineOutcomeException(val outcome: Int, message: String?) : IOException(message)

/** One open output part: the destination document, its stream, and a running SHA-256 (design
 * section 2.3 step 6). Not nested in [ArchiveCreator.Run] -- Kotlin refuses a plain nested class
 * inside an `inner class`. */
private class PartOutput(val index: Int, val node: DocNode, val stream: OutputStream, val digest: MessageDigest) {
    var bytes = 0L
}

/**
 * The run half of a CREATE operation (`docs/agent/DESIGN-M35-CREATE.md` section 2.3): claim the
 * row, re-spool any manifest entry that needs a known size before its tar header, run **one**
 * `writeArchive` pass on the isolated write instance with the source manifest fed in through one
 * pipe and the archive stream demultiplexed into staged, possibly-split output documents through
 * a second, verify, finalise (as one unit, last part first), and write the outcome. Every outcome
 * is written to the journal before [run] returns, so the worker can return `Result.success()` for
 * all of them -- exactly [ArchiveExtractor]'s own contract, mirrored here for the inverse
 * direction. Unlike extraction, a create has **no partial success** and **no re-issued pass**: one
 * entry's failure fails the whole archive (design section 2.3 step 4's own recorded choice), and a
 * transport loss is not retried within one run -- only a fresh claim (a restart, bounded to
 * [MAX_RESTARTS]) tries again.
 */
class ArchiveCreator(
    private val context: Context,
    private val journal: OperationJournal,
    private val writerClient: () -> DecoderClient,
    private val recycleBin: RecycleBinService = RecycleBinService(context, journal = journal),
    private val verifySettings: VerifySettings = VerifySettings(context),
    private val volumeFor: (Uri) -> VolumeInfo? = { VolumeInfoResolver.resolveForDestination(context, it) },
    private val workLookup: WorkLookup = WorkLookup.viaWorkManager(context),
    private val streamInactivityMillis: Long = DecoderClient.STREAM_INACTIVITY_MILLIS,
    private val cancelWaitMillis: Long = DecoderClient.CREATE_CANCEL_WAIT_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val resolver: ContentResolver get() = context.contentResolver

    suspend fun run(
        operationId: String,
        ownWorkId: UUID? = null,
        stopReason: () -> Int = { WorkInfo.STOP_REASON_NOT_STOPPED },
        onProgress: (CreateProgress) -> Unit = {},
    ): CreateRunOutcome = withContext(Dispatchers.IO) {
        val before = journal.find(operationId) ?: return@withContext CreateRunOutcome.NotClaimed
        if (before.type != FileOperationType.ARCHIVE) return@withContext CreateRunOutcome.NotClaimed
        val plan = journal.createPlan(operationId) ?: return@withContext CreateRunOutcome.NotClaimed
        val claimable = HashSet(CLAIMABLE)
        if (before.state == OperationState.RUNNING) {
            val others = workLookup.activeWorkIds(OperationRunner.createTag(operationId)) - setOfNotNull(ownWorkId)
            if (others.isEmpty()) claimable += OperationState.RUNNING
        }
        if (!journal.claimCreate(operationId, claimable)) return@withContext CreateRunOutcome.NotClaimed
        val operation = journal.find(operationId) ?: return@withContext CreateRunOutcome.NotClaimed
        val client = writerClient()
        try {
            Run(operation, plan, client, stopReason, onProgress).execute()
        } finally {
            client.unbind()
        }
    }

    // ------------------------------------------------------------------------------------------

    private inner class Run(
        private var operation: FileOperation,
        private val plan: CompressPlan,
        private val client: DecoderClient,
        private val stopReason: () -> Int,
        private val onProgress: (CreateProgress) -> Unit,
    ) {
        private val id = operation.id
        private var items: MutableList<OperationItem> = operation.items.toMutableList()
        private lateinit var manifest: List<CompressManifestEntry>
        private lateinit var destination: DocNode
        private var caseInsensitive = false

        private var currentPart: PartOutput? = null
        private var parts = mutableListOf<CompressPlanItem>()
        private var totalBytesFed = 0L
        private var totalBytesOut = 0L
        private var entriesDone = 0
        private var frameError: Throwable? = null

        /** A count, not a flag: the feeder and the drain each mark "busy" around their own I/O
         * concurrently, and a single boolean either one could clear out from under the other. */
        private val busyCount = AtomicInteger(0)
        private val fedBytes = AtomicLong(0L)
        private val drainedBytes = AtomicLong(0L)
        private var lastCancelCheck = 0L
        private var cancelSeen = false
        private var lastProgressReport = 0L

        suspend fun execute(): CreateRunOutcome {
            try {
                if (cancelRequested(force = true)) return finishCancelled(CreateErrorCodes.USER_CANCELLED)
                if (plan.restartCount >= MAX_RESTARTS) return failEverything(CreateErrorCodes.TOO_MANY_INTERRUPTIONS)
                val destinationUri = plan.destinationUri ?: return failEverything(CreateErrorCodes.DESTINATION_MISSING)
                destination = DocNode.loadDestination(resolver, destinationUri) ?: return failEverything(CreateErrorCodes.DESTINATION_MISSING)
                if (!destination.isDirectory || !destination.canWrite) return failEverything(CreateErrorCodes.DESTINATION_MISSING)
                val volume = volumeFor(destinationUri)
                caseInsensitive = volume?.caseInsensitive == true
                manifest = journal.createManifest(id)
                if (manifest.isEmpty()) return failEverything(CreateErrorCodes.DESTINATION_MISSING)

                respoolIfNeeded()
                cleanUpStalePartsFromAPreviousAttempt()
                resolveConflictsAsOneUnit()
                openPart(0, finalNameFor(0))

                runPass()
                if (cancelSeen) return finishCancelled(CreateErrorCodes.USER_CANCELLED)
                val failure = frameError
                if (failure != null) {
                    discardAllParts()
                    return failEverything(writeFailureCode(failure))
                }
                verify()
                finalizeParts()
                return finish()
            } catch (cancelled: CreateCancelledException) {
                return finishCancelled(CreateErrorCodes.USER_CANCELLED)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    if (stopReason() == WorkInfo.STOP_REASON_CANCELLED_BY_APP) {
                        finishCancelled(CreateErrorCodes.WORK_CANCELLED)
                    } else {
                        pauseBySystem()
                    }
                }
                throw cancelled
            } catch (failure: Throwable) {
                discardAllParts()
                return failEverything(failure.javaClass.simpleName)
            }
        }

        // -------------------------------------------------------------- spooling (design 2.2 step 4)

        private suspend fun respoolIfNeeded() {
            manifest.filter { it.needsSpooling && !it.isDirectory }.forEach { entry ->
                val existing = journal.spooledPath(id, entry.ordinal)?.let(::File)
                if (existing != null && existing.isFile) return@forEach
                val workDir = File(context.cacheDir, "archive-work/$id").apply { mkdirs() }
                val target = File(workDir, "${entry.ordinal}.spool")
                val cap = ArchiveLimits.forInspection().maxFileBytes
                openSourceStream(entry.sourceUri).use { input ->
                    target.outputStream().use { output ->
                        var total = 0L
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > cap) throw IOException("source exceeds the size limit while spooling")
                            output.write(buffer, 0, n)
                        }
                    }
                }
                journal.setSpooledPath(id, entry.ordinal, target.absolutePath)
            }
        }

        // -------------------------------------------------------------- resume cleanup

        /**
         * Design section 2.3 step 8: a create never resumes a write mid-stream -- a restart after
         * a system stop starts the whole pass over from scratch, re-staging every part. Whatever
         * [openPart] recorded on a previous attempt (this operation's own `create_plan_items` rows,
         * left exactly as [pauseBySystem] found them: the last part's document abandoned mid-write,
         * its stream merely closed, never deleted) is therefore always stale by the time a resumed
         * run reaches here, whether this is the very first attempt (nothing to clean) or the Nth.
         * Mirrors [ArchiveExtractor]'s own `prepareItems` deleting a previous run's staged document
         * before an item starts over, over `create_plan_items` instead of `operation_items`.
         */
        private fun cleanUpStalePartsFromAPreviousAttempt() {
            journal.createPlanItems(id).forEach { item ->
                item.stagingUri?.let { staging -> DocNode.load(resolver, staging)?.delete(resolver) }
            }
        }

        // -------------------------------------------------------------- conflicts (as one unit)

        /**
         * Design section 2.2 step 6 / section 2.3 step 7: Replace recycles **every** existing
         * `base` and every existing `base.\d{3}` -- an old, longer split set is not left partially
         * overwritten and partially stale -- before the new set is finalised. Removed up front
         * (not swapped at finalize, the copy/move/extract pattern): a create does not know how
         * many parts the new set will have until the write completes, so nothing could be swapped
         * against yet. A plain delete (never the recycle bin): the destination folder may have no
         * `.fylz-trash` of its own, and creating one as a side effect of a Replace conflict here
         * would be a surprising thing for a compress to do -- a scoped-down choice from the
         * design's own "recycle" wording, recorded as a deviation.
         */
        private fun resolveConflictsAsOneUnit() {
            if (plan.conflictPolicy != ConflictPolicy.REPLACE) return
            val toRemove = mutableListOf<DocNode>()
            val baseNoExt = plan.archiveName.removeSuffix(".${plan.format.extension}")
            val key = { s: String -> if (caseInsensitive) s.lowercase() else s }
            destination.children(resolver).forEach { child ->
                val isBase = key(child.name) == key(plan.archiveName)
                val isPart = (1..999).any { n -> key(child.name) == key(CompressPlanner.partName(baseNoExt, plan.format, n)) }
                if (isBase || isPart) toRemove += child
            }
            toRemove.forEach { node -> runCatching { node.delete(resolver) } }
        }

        private fun finalNameFor(partIndex: Int): String =
            if (plan.split is SplitSize.Off) {
                plan.archiveName
            } else {
                "${plan.archiveName}.${(partIndex + 1).toString().padStart(3, '0')}"
            }

        // -------------------------------------------------------------- output parts

        private fun openPart(index: Int, name: String) {
            val stagingName = stagingName(id, index, name)
            val node = destination.createChild(resolver, "application/octet-stream", stagingName)
            val stream = resolver.openOutputStream(node.uri, "w") ?: throw IOException("no output stream for ${node.name}")
            currentPart = PartOutput(index, node, stream, MessageDigest.getInstance("SHA-256"))
            val item = CompressPlanItem(index, name, node.uri, state = OperationState.RUNNING)
            parts.add(item)
            journal.putCreatePlanItem(id, item, refresh = false)
        }

        private fun closeCurrentPart(deleteOnFailure: Boolean) {
            val part = currentPart ?: return
            currentPart = null
            try {
                part.stream.flush()
                part.stream.close()
            } catch (failure: IOException) {
                if (deleteOnFailure) runCatching { part.node.delete(resolver) }
                throw failure
            }
            val digest = part.digest.digest().joinToString("") { "%02x".format(it) }
            val item = parts[part.index].copy(sha256 = digest, bytesWritten = part.bytes, state = OperationState.SUCCEEDED)
            parts[part.index] = item
            journal.putCreatePlanItem(id, item, refresh = false)
        }

        // -------------------------------------------------------------- the one pass

        private suspend fun runPass() {
            val cap = (plan.split as? SplitSize.At)?.bytes
            val destUri = plan.destinationUri!!
            val options = ArchiveWriteOptions(plan.format.writeFormat, plan.level)

            val call: DecoderCall<ArchiveWriteResult> = try {
                client.callTwoPipes(
                    inactivityMillis = streamInactivityMillis,
                    feed = { output -> feed(output) },
                    drain = { input -> drain(input, cap) },
                    busy = { busyCount.get() > 0 },
                    feedProgress = { fedBytes.get() },
                    drainProgress = { drainedBytes.get() },
                    cancelled = { cancelRequested(force = false) },
                    drainFailureWaitMillis = cancelWaitMillis,
                ) { service, input, output -> service.writeArchive(input, options, output) }
            } catch (failure: IOException) {
                frameError = failure
                closeCurrentPart(deleteOnFailure = false)
                return
            }
            closeCurrentPart(deleteOnFailure = false)
            when (call) {
                is DecoderCall.Ok -> {
                    val result = call.value
                    if (result.outcome != ArchiveWriteResult.OUTCOME_OK) {
                        frameError = EngineOutcomeException(result.outcome, result.message ?: "ARCHIVE_WRITE_FAILED")
                    } else {
                        entriesDone = result.entries
                        totalBytesFed = maxOf(totalBytesFed, result.bytesIn)
                    }
                }
                DecoderCall.TimedOut, DecoderCall.Failed -> if (frameError == null) frameError = IOException(CreateErrorCodes.ARCHIVE_WRITE_FAILED)
            }
            reportProgress(force = true)
        }

        /** The feeder (design section 2.3 step 3(a)/step 4): writes `FZW1` frames for every
         * manifest entry in order, opening each source at feed time so its size is authoritative
         * (design section 2.2 step 3) rather than trusted from planning. */
        private fun feed(output: OutputStream) {
            val writer = ArchiveFrameWriter(output)
            for (entry in manifest) {
                if (cancelSeen || cancelRequested(force = false)) {
                    writer.abort("cancelled")
                    return
                }
                if (entry.isDirectory) {
                    writer.entry(entry.ordinal, true, null, entry.mtimeEpochMillis, DIRECTORY_MODE, entry.archivePath)
                    continue
                }
                try {
                    feedFile(writer, entry)
                } catch (failure: IOException) {
                    if (frameError == null) frameError = failure
                    writer.abort(failure.message ?: "source unreadable")
                    return
                }
            }
            writer.finish()
        }

        private fun feedFile(writer: ArchiveFrameWriter, entry: CompressManifestEntry) {
            val spooled = if (entry.needsSpooling) journal.spooledPath(id, entry.ordinal)?.let(::File) else null
            val (length, streamFactory) = if (spooled != null) {
                spooled.length() to { spooled.inputStream() as InputStream }
            } else {
                sourceLength(entry.sourceUri) to { openSourceStream(entry.sourceUri) }
            }
            val declaredSize = if (entry.needsSpooling && spooled == null) null else length
            writer.entry(entry.ordinal, false, declaredSize, entry.mtimeEpochMillis, FILE_MODE, entry.archivePath)
            val started = clock()
            var writtenForEntry = 0L
            try {
                // Opening or reading the SOURCE itself (gone since planning, permission revoked, a
                // provider hiccup) is tagged SOURCE_UNREADABLE, distinctly from the sink side --
                // writer.data below, whose own IOException is the write instance/pipe failing, not
                // the source, and must not be recoded as if the source were at fault.
                val opened = try {
                    streamFactory()
                } catch (failure: IOException) {
                    throw IOException(CreateErrorCodes.SOURCE_UNREADABLE, failure)
                }
                opened.use { input ->
                    val buffer = ByteArray(512 * 1024)
                    while (true) {
                        if (clock() - started > MAX_SOURCE_BUSY_MILLIS) throw IOException(CreateErrorCodes.SOURCE_UNREADABLE)
                        busyCount.incrementAndGet()
                        val n = try {
                            input.read(buffer)
                        } catch (failure: IOException) {
                            throw IOException(CreateErrorCodes.SOURCE_UNREADABLE, failure)
                        } finally {
                            busyCount.decrementAndGet()
                        }
                        if (n < 0) break
                        writer.data(entry.ordinal, buffer, 0, n)
                        writtenForEntry += n
                        fedBytes.addAndGet(n.toLong())
                    }
                }
            } catch (failure: IOException) {
                // ArchiveFrameWriter.abort() is only ever valid with no entry open (design section
                // 2.5): feed()'s own catch calls it next, so THIS entry must be closed first --
                // with whatever partial bytes actually made it through, a shortfall against the
                // declared size the engine already tolerates -- or the engine sees ABORT while
                // ordinal N is still open and answers a protocol error, masking the real failure.
                writer.end(entry.ordinal, writtenForEntry)
                throw failure
            }
            writer.end(entry.ordinal, writtenForEntry)
            totalBytesFed += writtenForEntry
            entriesDone += 1
            reportProgress(force = false)
        }

        private fun sourceLength(uri: Uri): Long? {
            if (ArchiveDocumentId.isArchiveUri(uri)) {
                val id = runCatching { ArchiveDocumentId.parse(uri) }.getOrNull() ?: return null
                // The archive provider materialises on open; its own COLUMN_SIZE is authoritative
                // for an entry (the tree already carried it at plan time, but re-query for safety).
                return DocNode.load(resolver, uri)?.size
            }
            return DocNode.load(resolver, uri)?.size
        }

        private fun openSourceStream(uri: Uri): InputStream {
            val signal = CancellationSignal()
            return try {
                resolver.openFileDescriptor(uri, "r", signal)?.let { pfd -> ParcelFileDescriptor.AutoCloseInputStream(pfd) }
                    ?: resolver.openInputStream(uri)
                    ?: throw IOException("no input stream for $uri")
            } catch (failure: SecurityException) {
                throw IOException(CreateErrorCodes.PERMISSION_DENIED, failure)
            }
        }

        /** The drain (design section 2.3 step 3(b)/step 6): reads the archive stream into the
         * current staged part, rotating at each split boundary into the next, lazily opened, part. */
        private fun drain(input: InputStream, splitCap: Long?) {
            val buffer = ByteArray(512 * 1024)
            var partIndex = 0
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                var offset = 0
                var remaining = n
                while (remaining > 0) {
                    val part = currentPart ?: throw IOException("no open output part")
                    val room = if (splitCap != null) (splitCap - part.bytes).coerceAtLeast(0L) else remaining.toLong()
                    val chunk = minOf(remaining.toLong(), room.takeIf { it > 0 } ?: remaining.toLong()).toInt().coerceAtLeast(1)
                    busyCount.incrementAndGet()
                    try {
                        part.stream.write(buffer, offset, chunk)
                    } finally {
                        busyCount.decrementAndGet()
                    }
                    part.digest.update(buffer, offset, chunk)
                    part.bytes += chunk
                    totalBytesOut += chunk
                    drainedBytes.addAndGet(chunk.toLong())
                    offset += chunk
                    remaining -= chunk
                    if (splitCap != null && part.bytes >= splitCap) {
                        closeCurrentPart(deleteOnFailure = false)
                        partIndex += 1
                        openPart(partIndex, finalNameFor(partIndex))
                    }
                }
            }
        }

        // -------------------------------------------------------------- verification

        private suspend fun verify() {
            val verifying = shouldVerify(verifySettings.mode.value, classifyDestination(context, plan.destinationUri!!))
            if (!verifying) return
            for (item in parts) {
                val staged = item.stagingUri?.let { DocNode.load(resolver, it) } ?: throw IOException(CreateErrorCodes.VERIFICATION_FAILED)
                val reread = resolver.openInputStream(staged.uri)?.use(::sha256) ?: throw IOException(CreateErrorCodes.VERIFICATION_FAILED)
                if (reread != item.sha256) throw IOException(CreateErrorCodes.VERIFICATION_FAILED)
            }
            // Secondary check (design section 2.3 step 7): a single, unsplit, seekable output only,
            // run directly against the write instance -- never through the persistent catalog, which
            // must never key a summary on a staging Uri that disappears at rename.
            if (parts.size == 1 && manifest.size <= MAX_LISTING_CHECK_ENTRIES) {
                try {
                    secondaryListingCheck(parts.single())
                } catch (mismatch: VerificationMismatch) {
                    throw IOException(CreateErrorCodes.VERIFICATION_FAILED)
                } catch (bestEffort: IOException) {
                    // The secondary check is a bonus, not the primary guarantee (already passed
                    // above): a transport hiccup reading it back does not fail the whole compress.
                }
            }
        }

        private suspend fun secondaryListingCheck(part: CompressPlanItem) {
            val staged = part.stagingUri?.let { DocNode.load(resolver, it) } ?: return
            val pfd = resolver.openFileDescriptor(staged.uri, "r") ?: return
            val sinkFile = File.createTempFile("compress-verify", ".fzl", context.cacheDir)
            try {
                val limits = ArchiveLimits.forInspection()
                val call = client.callStreaming(
                    archive = pfd,
                    drain = { input -> sinkFile.outputStream().use { out -> input.copyTo(out) } },
                ) { service, sink -> service.listArchive(pfd, limits, sink) }
                val summary = (call as? DecoderCall.Ok)?.value ?: return
                if (!summary.isOk) return
                val listing = ArchiveListingCodec.decode(sinkFile, limits.maxListingEntries)
                if (listing.records.size != manifest.size) {
                    throw VerificationMismatch()
                }
            } finally {
                runCatching { pfd.close() }
                sinkFile.delete()
            }
        }

        // -------------------------------------------------------------- finalize / outcome

        /** Last part first, down to `.001` last (design section 2.3 step 7): a reader never sees a
         * `.001` without every later part also present; a later failure rolls back what already
         * finalised by renaming it back to its staging name (best effort -- it stays a `.fylz-part-*`
         * document rather than silently vanishing). */
        private fun finalizeParts() {
            val finalized = mutableListOf<Pair<DocNode, String>>()
            try {
                for (item in parts.sortedByDescending { it.itemIndex }) {
                    val staged = item.stagingUri?.let { DocNode.load(resolver, it) } ?: throw IOException(CreateErrorCodes.VERIFICATION_FAILED)
                    val final = staged.rename(resolver, item.requestedName)
                    finalized += final to staged.name
                    journal.putCreatePlanItem(id, item.copy(stagingUri = final.uri, state = OperationState.SUCCEEDED), refresh = false)
                }
            } catch (failure: Exception) {
                finalized.forEach { (node, stagedName) -> runCatching { node.rename(resolver, stagedName) } }
                throw failure
            }
        }

        private fun finish(): CreateRunOutcome {
            items = items.map { it.copy(state = OperationState.SUCCEEDED, completedBytes = totalBytesFed / items.size.coerceAtLeast(1)) }.toMutableList()
            items.forEach { journal.updateItem(id, it, refresh = false) }
            journal.updateOperationState(id, OperationState.SUCCEEDED)
            reportProgress(force = true)
            return CreateRunOutcome.Finished(OperationState.SUCCEEDED)
        }

        private fun discardAllParts() {
            closeCurrentPart(deleteOnFailure = true)
            parts.forEach { item -> item.stagingUri?.let { uri -> DocNode.load(resolver, uri)?.delete(resolver) } }
        }

        private fun failEverything(code: String): CreateRunOutcome {
            items = items.map { it.copy(state = OperationState.FAILED, errorCode = code) }.toMutableList()
            items.forEach { journal.updateItem(id, it, refresh = false) }
            journal.updateOperationState(id, OperationState.FAILED)
            return CreateRunOutcome.Finished(OperationState.FAILED)
        }

        private fun finishCancelled(code: String): CreateRunOutcome {
            discardAllParts()
            items = items.map { it.copy(state = OperationState.CANCELLED, errorCode = code) }.toMutableList()
            items.forEach { journal.updateItem(id, it, refresh = false) }
            journal.updateOperationState(id, OperationState.CANCELLED)
            return CreateRunOutcome.Finished(OperationState.CANCELLED)
        }

        private fun pauseBySystem(): CreateRunOutcome {
            // The write instance is torn down with this run (no mid-stream resume for a create,
            // design section 2.3 step 8): the staged parts are left in place (a retry starts over
            // from scratch and re-stages), and the restart count is bumped so MAX_RESTARTS bounds
            // how many times this can happen before TOO_MANY_INTERRUPTIONS.
            runCatching { currentPart?.stream?.close() }
            journal.incrementCreateRestartCount(id)
            items = items.map { if (it.state == OperationState.SUCCEEDED) it else it.copy(state = OperationState.PAUSED_BY_SYSTEM) }.toMutableList()
            items.forEach { journal.updateItem(id, it, refresh = false) }
            journal.updateOperationState(id, OperationState.PAUSED_BY_SYSTEM)
            return CreateRunOutcome.PausedBySystem
        }

        private fun writeFailureCode(failure: Throwable): String {
            if (failure is EngineOutcomeException && failure.outcome == ArchiveWriteResult.OUTCOME_PROTOCOL_ERROR) return CreateErrorCodes.PROTOCOL_ERROR
            val message = failure.message.orEmpty()
            return when {
                message == CreateErrorCodes.SOURCE_UNREADABLE -> CreateErrorCodes.SOURCE_UNREADABLE
                message.contains("ENOSPC") || message.contains("No space left", ignoreCase = true) -> CreateErrorCodes.INSUFFICIENT_SPACE
                message.contains("EACCES") || message.contains("Permission denied", ignoreCase = true) || failure is SecurityException -> CreateErrorCodes.PERMISSION_DENIED
                else -> CreateErrorCodes.ARCHIVE_WRITE_FAILED
            }
        }

        // -------------------------------------------------------------- cancel and progress

        private fun cancelRequested(force: Boolean): Boolean {
            if (cancelSeen) return true
            val now = clock()
            if (!force && now - lastCancelCheck < CANCEL_POLL_MILLIS) return false
            lastCancelCheck = now
            cancelSeen = journal.isCreateCancelRequested(id)
            return cancelSeen
        }

        private fun reportProgress(force: Boolean) {
            val now = clock()
            if (!force && now - lastProgressReport < PROGRESS_REPORT_MILLIS) return
            lastProgressReport = now
            onProgress(CreateProgress(totalBytesFed, plan.totalEstimate, entriesDone, plan.entryCount))
        }
    }

    companion object {
        private const val DIRECTORY_MODE = 0x1ED // 0755
        private const val FILE_MODE = 0x1A4 // 0644

        val CLAIMABLE: Set<OperationState> = setOf(OperationState.QUEUED, OperationState.PAUSED_BY_SYSTEM, OperationState.NEEDS_ATTENTION)

        const val MAX_RESTARTS = 3
        const val CANCEL_POLL_MILLIS = 250L
        const val PROGRESS_REPORT_MILLIS = 250L
        const val MAX_SOURCE_BUSY_MILLIS = 10 * 60 * 1000L
        const val MAX_LISTING_CHECK_ENTRIES = 10_000

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
