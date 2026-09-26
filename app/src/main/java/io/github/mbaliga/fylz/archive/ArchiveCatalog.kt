package io.github.mbaliga.fylz.archive

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Log
import io.github.mbaliga.fylz.decoder.ArchiveInspection
import io.github.mbaliga.fylz.decoder.ArchiveLimits
import io.github.mbaliga.fylz.decoder.DecoderCall
import io.github.mbaliga.fylz.decoder.DecoderClient
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where an open archive's bytes come from, pinned for the handle's lifetime -- **the source, never
 * a descriptor**. A `ParcelFileDescriptor` passed over Binder is a dup that shares the open file
 * description, i.e. the file offset, and libarchive reads with `read` + `lseek(SEEK_CUR)` after a
 * rewind to 0, so two concurrent `:decoders` calls on dups of one descriptor (two fills, or a fill
 * during a listing) would interleave and silently corrupt what they read (tar, ISO and cpio carry
 * no checksum to notice). Every call therefore [open]s its own descriptor: `openFileDescriptor`
 * again for a document the provider serves seekably, `ParcelFileDescriptor.open` on the staged or
 * materialised file otherwise. (`pfd.dup()` would not help: same open file description.)
 */
sealed class PinnedSource : java.io.Closeable {
    /** A fresh descriptor with its own offset, for exactly one `:decoders` call. */
    @Throws(IOException::class)
    abstract fun open(): ParcelFileDescriptor

    /** The provider's own document, which it served as a seekable descriptor; re-opened per call. */
    class Document(private val resolver: android.content.ContentResolver, val uri: Uri) : PinnedSource() {
        override fun open(): ParcelFileDescriptor =
            resolver.openFileDescriptor(uri, "r") ?: throw IOException("The provider returned no descriptor for $uri")

        override fun close() = Unit
    }

    /** A staged copy (the provider could only stream); closing deletes the workspace. */
    class Staged(private val staged: ArchiveSource.Resolved.Staged) : PinnedSource() {
        val file: File get() = File(staged.workspace, "input")

        override fun open(): ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

        override fun close() = staged.close()
    }

    /** An inner archive materialised by the entry cache and pinned there until closed. */
    class Materialised(val file: File, private val unpin: () -> Unit) : PinnedSource() {
        override fun open(): ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

        override fun close() = unpin()
    }
}

/**
 * One open archive: its listing as a tree, the summary the decoder returned with it, and the
 * [PinnedSource] every entry fill reads from -- a staged (non-seekable) source is copied once
 * per open archive, not once per entry, and a materialised inner archive stays pinned in the
 * entry cache while this handle is held. Reference counted: the catalog holds one reference while
 * the handle is in its LRU, a fill holds one for its duration, and the source closes at zero.
 */
class ArchiveHandle internal constructor(
    val ref: ArchiveRef,
    /** `sha256(src | size | mtime)`, chained for nested archives (section 2.3). */
    val key: String,
    val tree: ArchiveTree,
    /** What `listArchive` answered: format, counts, policy verdict, `partial`. Rows are empty. */
    val summary: ArchiveInspection,
    val source: PinnedSource,
    /** The archive had to be copied to cache to be read at all (a provider that only streams). */
    val staged: Boolean,
) {
    private var references = 1
    private var closed = false

    val partial: Boolean get() = summary.partial
    val quarantined: Int get() = tree.quarantined

    /** A descriptor of this archive for one `:decoders` call (see [PinnedSource]); the caller closes it. */
    @Throws(IOException::class)
    fun openDescriptor(): ParcelFileDescriptor = source.open()

    /** The raw path `extract_entry_at` must see for [entry]: what the listing carried for its ordinal. */
    fun rawPathOf(entry: ArchiveTreeEntry): String = rawPaths[entry.ordinal] ?: entry.path

    internal lateinit var rawPaths: Map<Int, String>

    /** `false` when the handle is already closed (evicted with no reader left). */
    @Synchronized
    fun retain(): Boolean {
        if (closed) return false
        references += 1
        return true
    }

    @Synchronized
    fun release() {
        references -= 1
        if (references <= 0 && !closed) {
            closed = true
            runCatching { source.close() }
        }
    }
}

/**
 * One listing per archive, on disk and in memory, always blocking
 * (`docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md` section 2.3). Application-scoped next to
 * `ArchiveInspector`.
 *
 * - **Key:** top level `sha256(src | size | mtime)` from a `queryDocument(src)`; nested
 *   `sha256(key(outer) | entryPath | uncompressed | mtime)`, so a changed outer archive
 *   invalidates every inner key.
 * - **[open] is single-flight and blocking:** one `Deferred` per key; the first caller lists, the
 *   rest await. Order: the in-memory LRU ([maxTrees] trees) -> the `.fzl` (+ `.summary.json`) on
 *   disk, read and validated -> `:decoders` through [DecoderClient.callStreaming] with the codec
 *   drained into `<key>.<nonce>.part`, then renamed. Every provider method calls this and blocks;
 *   there is no `peek`, no `EXTRA_LOADING`, no `notifyChange` protocol, so a cold process (a
 *   worker resuming, another app opening a granted Uri, session restore) gets the full listing,
 *   never an empty folder.
 * - **Failures are memoised per key** until [forgetFailures] (`fylz.refresh`) or the key changes,
 *   so the provider never loops against `:decoders`.
 * - **Nested archives:** the outer archive is opened first (recursively, depth <= 4), the inner
 *   archive entry is materialised through the entry cache and pinned for the handle's lifetime.
 *
 * The summary (format, counts, the policy verdict, `partial`, `structuralRefusal`) is kept beside
 * the listing in a `<key>.summary.json` sidecar written after the `.fzl` is in place, since the
 * `.fzl` carries records only; a listing without a readable sidecar **fails closed** -- it is
 * treated as corrupt and re-listed -- because the entry cache refuses entries of a policy-refused
 * archive from that summary and must never guess.
 */
class ArchiveCatalog(
    context: Context,
    private val source: ArchiveSource,
    private val client: DecoderClient,
    private val limits: ArchiveLimits,
    private val entryCache: ArchiveEntryCache,
    private val sweeper: ArchiveCacheSweeper,
    private val maxTrees: Int = MAX_TREES,
    private val streamInactivityMillis: Long = DecoderClient.STREAM_INACTIVITY_MILLIS,
) {
    /** Why an archive could not be listed; the message is what the user sees. */
    sealed class Failure(message: String) : IOException(message) {
        class TookTooLong : Failure("The archive took too long to read.")
        class Unavailable : Failure("The archive could not be read safely.")
        class Refused(val outcome: Int, val detail: String?) : Failure(refusedMessage(outcome, detail))
        class SourceFailed(cause: ArchiveSourceException) : Failure(cause.message ?: "Unable to read the archive.") {
            init {
                initCause(cause)
            }
        }
        class TooDeep : Failure(ArchiveDocumentId.DEPTH_REFUSED)
        class NotAnEntry(path: String) : Failure("\"$path\" is not an archive inside this archive.")
    }

    private val resolver = context.contentResolver
    private val root: File = sweeper.listingsRoot
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    /** In flight, by key. Guarded by [lock]. */
    private val inFlight = HashMap<String, Deferred<ArchiveHandle>>()

    /** Open handles, least recently used first. Guarded by [lock]. */
    private val trees = LinkedHashMap<String, ArchiveHandle>(4, 0.75f, true)

    /** Memoised failures, by key. Guarded by [lock]. */
    private val failures = HashMap<String, Failure>()

    /** How many listings ran against `:decoders`, for tests that assert caching. */
    var listingCount: Int = 0
        private set

    /** Lists the archive [id] belongs to (see [open]). */
    @Throws(Failure::class)
    suspend fun open(id: ArchiveDocumentId): ArchiveHandle = open(id.archive)

    /**
     * The handle of [ref], listing it if needed. Blocks the caller until the listing is complete;
     * throws a [Failure] (memoised until [forgetFailures]) when it cannot be listed.
     */
    @Throws(Failure::class)
    suspend fun open(ref: ArchiveRef): ArchiveHandle {
        sweeper.sweepOnce()
        if (ref.depth > ArchiveDocumentId.MAX_DEPTH) throw Failure.TooDeep()
        val outer = if (ref.chain.isEmpty()) null else open(ArchiveRef(ref.source, ref.chain.dropLast(1)))
        val innerPath = ref.chain.lastOrNull()
        val innerEntry = if (outer != null && innerPath != null) {
            outer.tree.entry(innerPath)?.takeIf { it.isFile } ?: throw Failure.NotAnEntry(innerPath)
        } else {
            null
        }
        val key = if (outer == null) topLevelKey(ref.source) else nestedKey(outer.key, innerEntry!!)
        synchronized(lock) {
            failures[key]?.let { throw it }
            trees[key]?.let { return it }
        }
        val deferred = synchronized(lock) {
            inFlight.getOrPut(key) {
                scope.async { load(ref, key, outer, innerEntry) }.also { started ->
                    started.invokeOnCompletion { synchronized(lock) { inFlight.remove(key, started) } }
                }
            }
        }
        return deferred.await()
    }

    /** Forgets every memoised failure, so the next open tries `:decoders` again (`fylz.refresh`). */
    fun forgetFailures() {
        synchronized(lock) { failures.clear() }
    }

    /** The keys with a listing in flight, so the sweeper never evicts one mid-write. */
    private fun inFlightKeys(): Set<String> = synchronized(lock) { inFlight.keys.toSet() }

    private suspend fun load(ref: ArchiveRef, key: String, outer: ArchiveHandle?, innerEntry: ArchiveTreeEntry?): ArchiveHandle {
        try {
            val pinned = resolveSource(ref, outer, innerEntry)
            var handle: ArchiveHandle? = null
            try {
                val cached = readCached(key)
                val (listing, summary) = cached ?: pinned.open().use { pfd -> list(pfd, key) }
                val tree = ArchiveTree.build(listing, summary.formatCode)
                val rawPaths = HashMap<Int, String>(listing.records.size * 4 / 3 + 16)
                listing.records.forEach { rawPaths[it.ordinal] = it.path }
                handle = ArchiveHandle(ref, key, tree, summary, pinned, pinned is PinnedSource.Staged).also { it.rawPaths = rawPaths }
                val evicted = synchronized(lock) {
                    trees[key] = handle
                    val victims = ArrayList<ArchiveHandle>()
                    val iterator = trees.entries.iterator()
                    while (trees.size > maxTrees && iterator.hasNext()) {
                        victims += iterator.next().value
                        iterator.remove()
                    }
                    victims
                }
                evicted.forEach { it.release() }
                return handle
            } catch (failure: Throwable) {
                if (handle == null) runCatching { pinned.close() }
                throw failure
            }
        } catch (failure: Failure) {
            synchronized(lock) { failures[key] = failure }
            throw failure
        } catch (failure: IOException) {
            val wrapped = Failure.Refused(ArchiveInspection.OUTCOME_INTERNAL, failure.message)
            synchronized(lock) { failures[key] = wrapped }
            throw wrapped
        }
    }

    /**
     * The [PinnedSource] for [ref]: the document itself when the provider serves it seekably (the
     * probe descriptor `ArchiveSource` opened is closed again; every call re-opens its own), the
     * staged copy otherwise, or the materialised inner archive for a nested one.
     */
    private suspend fun resolveSource(ref: ArchiveRef, outer: ArchiveHandle?, innerEntry: ArchiveTreeEntry?): PinnedSource {
        if (outer == null || innerEntry == null) {
            val resolved = try {
                source.resolve(ref.source)
            } catch (failure: ArchiveSourceException) {
                throw Failure.SourceFailed(failure)
            }
            return when (resolved) {
                is ArchiveSource.Resolved.Direct -> {
                    resolved.close()
                    PinnedSource.Document(resolver, ref.source)
                }
                is ArchiveSource.Resolved.Staged -> {
                    runCatching { resolved.pfd.close() }
                    PinnedSource.Staged(resolved)
                }
            }
        }
        val file = try {
            entryCache.materialise(outer, innerEntry)
        } catch (failure: ArchiveEntryCache.Refused) {
            throw Failure.Refused(ArchiveInspection.OUTCOME_UNSUPPORTED, failure.message)
        } catch (failure: IOException) {
            throw Failure.Refused(ArchiveInspection.OUTCOME_CORRUPT, failure.message)
        }
        entryCache.pin(file)
        return PinnedSource.Materialised(file) { entryCache.unpin(file) }
    }

    /**
     * The `.fzl` and its `.summary.json` sidecar when both are present and valid. Fails closed: a
     * listing without a readable sidecar (or a sidecar without its listing) is deleted and treated
     * as cold, so the entry cache never opens an entry on a guessed policy verdict.
     */
    private fun readCached(key: String): Pair<ArchiveListing, ArchiveInspection>? {
        val listingFile = listingFile(key)
        val metaFile = metaFile(key)
        if (!listingFile.isFile || !metaFile.isFile) {
            listingFile.delete()
            metaFile.delete()
            return null
        }
        return try {
            val listing = ArchiveListingCodec.decode(listingFile, limits.maxListingEntries)
            val summary = decodeSummary(metaFile.readText())
            listingFile.setLastModified(System.currentTimeMillis())
            metaFile.setLastModified(System.currentTimeMillis())
            listing to summary
        } catch (failure: IOException) {
            Log.w(TAG, "Discarding an unreadable cached listing for $key: ${failure.message}")
            listingFile.delete()
            metaFile.delete()
            null
        } catch (failure: IllegalArgumentException) {
            Log.w(TAG, "Discarding an unreadable cached summary for $key: ${failure.message}")
            listingFile.delete()
            metaFile.delete()
            null
        }
    }

    /** Lists through `:decoders` into `<key>.<nonce>.part`, then renames. */
    private suspend fun list(pfd: ParcelFileDescriptor, key: String): Pair<ArchiveListing, ArchiveInspection> {
        root.mkdirs()
        val part = File(root, "$key.${UUID.randomUUID()}${ArchiveCacheSweeper.PART_SUFFIX}")
        synchronized(lock) { listingCount += 1 }
        val call = try {
            client.callStreaming(
                archive = pfd,
                inactivityMillis = streamInactivityMillis,
                drain = { input -> part.outputStream().use { out -> copy(input, out) } },
            ) { service, sink -> service.listArchive(pfd, limits, sink) }
        } catch (failure: IOException) {
            part.delete()
            throw Failure.Refused(ArchiveInspection.OUTCOME_INTERNAL, failure.message)
        }
        val summary = when (call) {
            is DecoderCall.Ok -> call.value
            DecoderCall.TimedOut -> {
                part.delete()
                throw Failure.TookTooLong()
            }
            DecoderCall.Failed -> {
                part.delete()
                throw Failure.Unavailable()
            }
        }
        if (!summary.isOk) {
            part.delete()
            throw Failure.Refused(summary.outcome, summary.message)
        }
        val listing = try {
            ArchiveListingCodec.decode(part, limits.maxListingEntries)
        } catch (failure: ArchiveListingCorrupt) {
            part.delete()
            throw Failure.Refused(ArchiveInspection.OUTCOME_INTERNAL, "The listing could not be read: ${failure.message}")
        }
        val listingFile = listingFile(key)
        listingFile.delete()
        if (!part.renameTo(listingFile)) {
            part.delete()
            throw Failure.Refused(ArchiveInspection.OUTCOME_INTERNAL, "The listing could not be stored.")
        }
        metaFile(key).writeText(encodeSummary(summary))
        sweeper.enforceListingBudget(inFlightKeys() + key)
        return listing to summary
    }

    private fun listingFile(key: String) = File(root, "$key.fzl")
    private fun metaFile(key: String) = File(root, "$key$SUMMARY_SUFFIX")

    /** `sha256(src | size | mtime)` over what the provider says about the file right now. */
    private fun topLevelKey(src: Uri): String {
        var size: Long? = null
        var mtime: Long? = null
        runCatching {
            resolver.query(
                src,
                arrayOf(DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null as Bundle?,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    if (!cursor.isNull(0)) size = cursor.getLong(0)
                    if (!cursor.isNull(1)) mtime = cursor.getLong(1)
                }
            }
        }
        return sha256("$src|${size ?: "?"}|${mtime ?: "?"}")
    }

    private fun nestedKey(outerKey: String, entry: ArchiveTreeEntry): String =
        sha256("$outerKey|${entry.path}|${entry.uncompressedBytes}|${entry.mtimeEpochSeconds}")

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
        private const val TAG = "ArchiveCatalog"
        private const val COPY_BUFFER_BYTES = 256 * 1024

        /** How many trees stay in memory (section 2.3's LRU). */
        const val MAX_TREES = 2

        /** The summary sidecar beside `<key>.fzl`. */
        const val SUMMARY_SUFFIX = ".summary.json"

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        private fun refusedMessage(outcome: Int, detail: String?): String {
            val suffix = detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."
            return when (outcome) {
                ArchiveInspection.OUTCOME_UNSUPPORTED -> "This file is not an archive Fylz can open" + suffix
                ArchiveInspection.OUTCOME_CORRUPT -> "The archive is damaged or could not be read" + suffix
                ArchiveInspection.OUTCOME_LIMIT_EXCEEDED -> "The archive has too many entries to list" + suffix
                else -> "The archive could not be opened" + suffix
            }
        }

        /** The `.summary.json` sidecar: the summary's fields as flat JSON (rows are never stored). */
        internal fun encodeSummary(summary: ArchiveInspection): String = JSONObject()
            .put("v", 1)
            .put("outcome", summary.outcome)
            .put("formatCode", summary.formatCode)
            .put("formatName", summary.formatName ?: JSONObject.NULL)
            .put("filters", JSONArray(summary.filters))
            .put("archiveBytes", summary.archiveBytes)
            .put("entryCount", summary.entryCount)
            .put("fileCount", summary.fileCount)
            .put("directoryCount", summary.directoryCount)
            .put("linkCount", summary.linkCount)
            .put("totalUncompressedBytes", summary.totalUncompressedBytes)
            .put("hasEncryptedEntries", summary.hasEncryptedEntries)
            .put("hasEncryptedMetadata", summary.hasEncryptedMetadata)
            .put("hasLossyNames", summary.hasLossyNames)
            .put("policyAllowed", summary.policyAllowed)
            .put("policyReason", summary.policyReason ?: JSONObject.NULL)
            .put("partial", summary.partial)
            .put("partialMessage", summary.partialMessage ?: JSONObject.NULL)
            .put("structuralRefusal", summary.structuralRefusal ?: JSONObject.NULL)
            .toString()

        @Throws(IllegalArgumentException::class)
        internal fun decodeSummary(json: String): ArchiveInspection = try {
            val root = JSONObject(json)
            require(root.getInt("v") == 1) { "unknown summary version" }
            val filters = root.getJSONArray("filters")
            ArchiveInspection(
                outcome = root.getInt("outcome"),
                message = null,
                formatCode = root.getInt("formatCode"),
                formatName = root.optString("formatName").takeUnless { root.isNull("formatName") },
                filters = List(filters.length()) { filters.getString(it) },
                archiveBytes = root.getLong("archiveBytes"),
                entryCount = root.getInt("entryCount"),
                fileCount = root.getInt("fileCount"),
                directoryCount = root.getInt("directoryCount"),
                linkCount = root.getInt("linkCount"),
                totalUncompressedBytes = root.getLong("totalUncompressedBytes"),
                hasEncryptedEntries = root.getBoolean("hasEncryptedEntries"),
                hasEncryptedMetadata = root.getBoolean("hasEncryptedMetadata"),
                hasLossyNames = root.getBoolean("hasLossyNames"),
                policyAllowed = root.getBoolean("policyAllowed"),
                policyReason = root.optString("policyReason").takeUnless { root.isNull("policyReason") },
                rows = emptyList(),
                rowsTruncated = false,
                partial = root.getBoolean("partial"),
                partialMessage = root.optString("partialMessage").takeUnless { root.isNull("partialMessage") },
                structuralRefusal = root.optString("structuralRefusal").takeUnless { root.isNull("structuralRefusal") },
            )
        } catch (e: org.json.JSONException) {
            throw IllegalArgumentException("summary sidecar is not readable: ${e.message}", e)
        }
    }
}
