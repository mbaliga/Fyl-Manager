package io.github.mbaliga.fylz.archive

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.StatFs
import io.github.mbaliga.fylz.data.ArchiveSpacePolicy
import io.github.mbaliga.fylz.decoder.ArchiveExtractResult
import io.github.mbaliga.fylz.decoder.ArchiveLimits
import io.github.mbaliga.fylz.decoder.DecoderCall
import io.github.mbaliga.fylz.decoder.DecoderClient
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Entry bytes, materialised to cache and served as regular-file descriptors
 * (`docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md` section 2.4). `openDocument` must hand back a
 * descriptor synchronously and seekable (`PdfRenderer`, `Typeface.Builder`, ExoPlayer), and the
 * only channel out of `:decoders` is a pipe, so an entry is streamed once into
 * `cacheDir/archive-entries/<listingKey>/<ordinal>` and opened from there.
 *
 * **Refusals come first**, before any byte moves, each a [Refused] (a `FileNotFoundException`
 * with the message shown): the archive's policy decision is refused (browsing a refused archive
 * is allowed, opening or copying out of it is not, so a flat bomb that passes the per-entry cap is
 * still stopped by the archive-level rules); symlinks and special files; encrypted entries (until
 * M3.9); directories and implicit entries; an entry larger than `min(maxFileBytes, budget)` or
 * than the cache volume can hold with headroom. A hardlink resolves to its target and opens that.
 *
 * **Fills** stream through [DecoderClient.callStreaming] into a `.part` next to the final name;
 * success is an OK outcome whose byte count equals the file's length and the declared size when
 * known, then a rename; anything else deletes the `.part` and throws. Concurrent opens of one
 * entry share one fill through a per-key [Fill] with reference-counted waiters: a caller's
 * cancellation detaches that caller, and only the last one's aborts the fill. At most
 * [maxConcurrentFills] fills run at once. Eviction is LRU by mtime to the budget (the sweeper's
 * job), never touching a [pin]ned file -- a materialised inner archive an open handle reads.
 *
 * Cost model, stated honestly: one fill is one `extract_entry_at` pass to the entry -- cheap on a
 * seekable ZIP (a central-directory walk and one seek), a decompression up to the entry on
 * `tar.*` and solid 7z. M3.4 routes bulk extraction through one pass.
 */
class ArchiveEntryCache(
    context: Context,
    private val client: DecoderClient,
    private val limits: ArchiveLimits,
    private val sweeper: ArchiveCacheSweeper,
    private val budgetBytes: Long = ArchiveCacheSweeper.ENTRY_BUDGET_BYTES,
    private val availableCacheBytes: () -> Long? = {
        runCatching { StatFs(context.cacheDir.path).availableBytes }.getOrNull()
    },
    private val maxConcurrentFills: Int = MAX_CONCURRENT_FILLS,
    private val streamInactivityMillis: Long = DecoderClient.STREAM_INACTIVITY_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The entry cannot be opened; the message is what the user sees. */
    class Refused(message: String) : FileNotFoundException(message)

    private val root: File = sweeper.entriesRoot
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fillPermits = Semaphore(maxConcurrentFills)
    private val lock = Any()

    /** A fill in progress and how many callers are waiting on it. Guarded by [lock]. */
    private class Fill(val deferred: Deferred<File>) {
        var waiters = 0
    }

    private val fills = HashMap<String, Fill>()
    private val pinned = HashMap<String, Int>()

    /** How many fills have been started, for tests that assert sharing. */
    var fillCount: Int = 0
        private set

    /**
     * The materialised file for [entry] of [handle]'s archive, filling it if needed. Throws
     * [Refused] for the refusals above, [IOException] for a failed fill.
     */
    @Throws(Refused::class, IOException::class)
    suspend fun materialise(handle: ArchiveHandle, entry: ArchiveTreeEntry): File {
        val target = refusalsChecked(handle, entry)
        val existing = fileFor(handle.key, target.ordinal)
        if (existing.isFile && (!target.sizeKnown || existing.length() == target.uncompressedBytes)) {
            existing.setLastModified(clock())
            return existing
        }
        return sharedFill(handle, target)
    }

    /**
     * A seekable, read-only descriptor of the materialised entry: what `openDocument` returns.
     * The file stays valid for the descriptor's lifetime even if evicted afterwards.
     */
    @Throws(Refused::class, IOException::class)
    suspend fun open(handle: ArchiveHandle, entry: ArchiveTreeEntry): ParcelFileDescriptor =
        ParcelFileDescriptor.open(materialise(handle, entry), ParcelFileDescriptor.MODE_READ_ONLY)

    /** Keeps [file] out of eviction until the matching [unpin]; nested pins count. */
    fun pin(file: File) {
        synchronized(lock) { pinned[file.absolutePath] = (pinned[file.absolutePath] ?: 0) + 1 }
    }

    fun unpin(file: File) {
        synchronized(lock) {
            val count = (pinned[file.absolutePath] ?: return) - 1
            if (count <= 0) pinned.remove(file.absolutePath) else pinned[file.absolutePath] = count
        }
    }

    /** The files currently pinned, for the sweeper. */
    fun pinnedFiles(): Set<File> = synchronized(lock) { pinned.keys.mapTo(HashSet()) { File(it) } }

    /** Where [ordinal] of the archive keyed [key] lives once materialised. */
    fun fileFor(key: String, ordinal: Int): File = File(File(root, key), ordinal.toString())

    /** The refusals of section 2.4, in order; returns the entry to fetch (a hardlink's target). */
    @Throws(Refused::class)
    private fun refusalsChecked(handle: ArchiveHandle, entry: ArchiveTreeEntry): ArchiveTreeEntry {
        if (!handle.summary.policyAllowed) {
            throw Refused("This archive failed safety checks: ${handle.summary.policyReason ?: "unknown reason"}")
        }
        val target = when {
            entry.isHardlink -> handle.tree.resolveHardlink(entry) ?: throw Refused(LINKS_REFUSED)
            entry.isSymlink || entry.isOther -> throw Refused(LINKS_REFUSED)
            entry.isDirectory -> throw Refused("Folders cannot be opened as files.")
            else -> entry
        }
        if (target.isImplicit) throw Refused("Folders cannot be opened as files.")
        if (target.encryptedData || target.encryptedMetadata) throw Refused(ENCRYPTED_REFUSED)
        if (target.sizeKnown) {
            val cap = minOf(limits.maxFileBytes, budgetBytes)
            if (target.uncompressedBytes > cap) throw Refused(TOO_LARGE_REFUSED)
            val required = target.uncompressedBytes + ArchiveSpacePolicy.MIN_TEMPORARY_HEADROOM
            if (!ArchiveSpacePolicy.evaluate(required, availableCacheBytes(), "temporary storage").allowed) {
                throw Refused(TOO_LARGE_REFUSED)
            }
        }
        return target
    }

    private suspend fun sharedFill(handle: ArchiveHandle, entry: ArchiveTreeEntry): File {
        val fillKey = "${handle.key}/${entry.ordinal}"
        val fill = synchronized(lock) {
            fills.getOrPut(fillKey) {
                fillCount += 1
                Fill(scope.async { fill(handle, entry) }).also { started ->
                    started.deferred.invokeOnCompletion { synchronized(lock) { fills.remove(fillKey, started) } }
                }
            }.also { it.waiters += 1 }
        }
        try {
            return fill.deferred.await()
        } catch (e: CancellationException) {
            val last = synchronized(lock) { fill.waiters -= 1; fill.waiters == 0 }
            if (last && !fill.deferred.isCompleted) fill.deferred.cancel()
            throw e
        } finally {
            synchronized(lock) { if (fill.waiters > 0) fill.waiters -= 1 }
        }
    }

    private suspend fun fill(handle: ArchiveHandle, entry: ArchiveTreeEntry): File = fillPermits.withPermit {
        val final = fileFor(handle.key, entry.ordinal)
        final.parentFile?.mkdirs()
        val part = File(final.parentFile, "${final.name}.${UUID.randomUUID()}${ArchiveCacheSweeper.PART_SUFFIX}")
        if (!handle.retain()) throw IOException("The archive was closed before the entry could be read.")
        try {
            // A descriptor of this call's own: a Binder dup shares the file offset, so two calls
            // on one descriptor would interleave their reads (see `PinnedSource`).
            val call = handle.openDescriptor().use { pfd ->
                client.callStreaming(
                    archive = pfd,
                    inactivityMillis = streamInactivityMillis,
                    drain = { input -> part.outputStream().use { out -> copy(input, out) } },
                ) { service, sink -> service.extractEntry(pfd, entry.ordinal, handle.rawPathOf(entry), limits, sink) }
            }
            when (call) {
                is DecoderCall.Ok -> {
                    val result = call.value
                    if (!result.isOk) throw IOException(messageFor(result))
                    val length = part.length()
                    if (result.bytesWritten != length) {
                        throw IOException("The entry was not written completely (${result.bytesWritten} of $length bytes).")
                    }
                    if (entry.sizeKnown && length != entry.uncompressedBytes) {
                        throw IOException("The entry's size (${entry.uncompressedBytes}) does not match what was read ($length).")
                    }
                    if (!part.renameTo(final)) {
                        final.delete()
                        if (!part.renameTo(final)) throw IOException("Could not store the entry in the cache.")
                    }
                    final.setLastModified(clock())
                }
                DecoderCall.TimedOut -> throw IOException("The archive took too long to read.")
                DecoderCall.Failed -> throw IOException("The archive could not be read safely.")
            }
        } catch (failure: Throwable) {
            part.delete()
            throw failure
        } finally {
            handle.release()
        }
        sweeper.enforceEntryBudget(pinnedFiles() + final)
        final
    }

    private fun messageFor(result: ArchiveExtractResult): String = when (result.outcome) {
        ArchiveExtractResult.OUTCOME_NOT_FOUND -> "This entry is no longer in the archive; refresh and try again."
        ArchiveExtractResult.OUTCOME_LIMIT_EXCEEDED -> "This entry is larger than the archive declared and was not opened."
        ArchiveExtractResult.OUTCOME_UNSUPPORTED -> "This file is not an archive Fylz can open."
        ArchiveExtractResult.OUTCOME_CORRUPT -> "The archive is damaged or could not be read" + detail(result.message)
        else -> "The archive could not be opened" + detail(result.message)
    }

    private fun detail(message: String?): String = message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."

    private fun copy(input: InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        output.flush()
    }

    companion object {
        /** Fills running at once (section 2.2's executor cap). */
        const val MAX_CONCURRENT_FILLS = 2
        private const val COPY_BUFFER_BYTES = 256 * 1024

        const val LINKS_REFUSED = "Links and special files cannot be opened."
        const val ENCRYPTED_REFUSED = "This entry is password protected. Opening protected entries arrives with the password prompt (M3.9)."
        const val TOO_LARGE_REFUSED = "This entry is too large to open in place. Extract it instead."
    }
}
