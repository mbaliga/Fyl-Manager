package io.github.mbaliga.fylz.storage

import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.FileNotFoundException
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import kotlin.concurrent.thread

/**
 * P0.0 test double: wraps a real, temp-directory-backed [FylzFilesDocumentsProvider] and can be
 * configured to simulate the provider failures the Phase 0 recovery paths need proof against —
 * refused rename, delete or create, and a write that stops partway through (see [throwAfterBytes]
 * for what that can and can't signal to the caller under Robolectric).
 *
 * Every call this double doesn't specifically fault-inject is forwarded unchanged to the real
 * provider, so a test only needs to describe the one failure it's proving recovery from.
 */
class FaultyDocumentsProvider : DocumentsProvider() {

    /** When true, [renameDocument] throws instead of delegating. */
    var refuseRename: Boolean = false

    /**
     * When set, [renameDocument] throws on exactly its Nth call (1-indexed) instead of
     * delegating -- independent of [refuseRename], which refuses unconditionally from the start.
     * Every other call, earlier or later, delegates normally. Lets a test prove recovery from a
     * single failed rename in a sequence: "the second rename in a sequence fails, and the
     * rollback rename that follows it must still succeed" (P0.2's Replace rollback) or "the 6th
     * rename in a batch fails, and the rest of the batch still succeeds" (P0.4).
     */
    var refuseRenameAtCall: Int? = null
    private var renameCallCount = 0

    /** When true, [deleteDocument] throws instead of delegating. */
    var refuseDelete: Boolean = false

    /** When true, [createDocument] throws instead of delegating. */
    var refuseCreate: Boolean = false

    /**
     * When set, a write-mode [openDocument] returns a pipe that stops forwarding bytes to the
     * underlying file after this many bytes, simulating a provider that fails partway through a
     * write. Read-mode opens are never affected.
     *
     * Real `DocumentsProvider`s signal this failure to the caller's `write()`/`close()` as a
     * broken-pipe [java.io.IOException]. Under Robolectric's `ParcelFileDescriptor` shadow that
     * signal never reaches the writer -- confirmed empirically: neither a plain pipe's OS
     * backpressure nor a reliable pipe's `closeWithError()` propagates to the write side here, so
     * the caller's write and close calls both silently "succeed" regardless of this setting.
     * What IS reliable, because it's controlled entirely on this side rather than depending on
     * that signal: the real underlying file ends up truncated at exactly this many bytes. Tests
     * assert that -- join [lastTruncationThread] first so the assertion doesn't race the
     * background copy.
     */
    var throwAfterBytes: Long? = null

    /** The background thread the most recent truncating [openDocument] call started, if any.
     * Join it before asserting on the resulting file -- see [throwAfterBytes]. */
    internal var lastTruncationThread: Thread? = null
        private set

    /**
     * When set, a [queryDocument] call whose projection asks for exactly `COLUMN_SIZE` (as
     * [io.github.mbaliga.fylz.data.DocumentRepository.writeText]'s write-then-verify step does)
     * reports this value instead of the real on-disk size -- simulating a provider whose
     * post-write size read doesn't match what was actually written, deterministically and without
     * racing a background thread the way [throwAfterBytes] would. Every other query, and every
     * other projection, is unaffected.
     */
    var reportedSizeOverride: Long? = null

    /**
     * The real provider this double wraps. Attached with a plain, direct `attachInfo()` call --
     * not through Robolectric's [Robolectric.buildContentProvider]/`ContentProviderController`,
     * which would also register it with `ShadowContentResolver` under the same authority this
     * double itself registers under in [install], racing to decide which of the two instances
     * `ContentResolver` calls actually reach. `attachInfo` alone satisfies
     * [android.provider.DocumentsProvider]'s own security checks (exported, grantUriPermissions,
     * the MANAGE_DOCUMENTS permissions -- built by hand below, since this instance has no
     * manifest entry of its own to read them from) and runs `onCreate()`, without touching the
     * resolver's routing table at all. Every call reaches this instance only via this double's
     * own forwarding methods below.
     */
    private val real: FylzFilesDocumentsProvider by lazy {
        FylzFilesDocumentsProvider().apply {
            attachInfo(RuntimeEnvironment.getApplication(), testProviderInfo(FylzFilesDocumentsProvider::class.java))
        }
    }

    /** Pass-through to the real provider's P0.0 test seam; set this before any operation. */
    internal var volumeOverride: List<VolumeDescriptor>?
        get() = real.volumeOverride
        set(value) {
            real.volumeOverride = value
        }

    override fun onCreate(): Boolean {
        real // force attachment now, so a misconfigured double fails fast in setup, not mid-test
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor = real.queryRoots(projection)

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val overrideSize = reportedSizeOverride
        if (overrideSize != null && projection?.toList() == listOf(DocumentsContract.Document.COLUMN_SIZE)) {
            return MatrixCursor(projection).apply { addRow(arrayOf(overrideSize)) }
        }
        return real.queryDocument(documentId, projection)
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor = real.queryChildDocuments(parentDocumentId, projection, sortOrder)

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        real.isChildDocument(parentDocumentId, documentId)

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val limit = throwAfterBytes
        val pfd = real.openDocument(documentId, mode, signal)
        return if (limit != null && mode.contains('w')) truncatingPipe(pfd, limit) else pfd
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        if (refuseCreate) throw FileNotFoundException("simulated: create refused for $displayName")
        return real.createDocument(parentDocumentId, mimeType, displayName)
    }

    override fun deleteDocument(documentId: String) {
        if (refuseDelete) throw FileNotFoundException("simulated: delete refused for $documentId")
        real.deleteDocument(documentId)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        renameCallCount += 1
        if (refuseRename || renameCallCount == refuseRenameAtCall) {
            throw FileNotFoundException("simulated: rename refused for $documentId")
        }
        return real.renameDocument(documentId, displayName)
    }

    override fun moveDocument(
        sourceDocumentId: String,
        sourceParentDocumentId: String,
        targetParentDocumentId: String,
    ): String = real.moveDocument(sourceDocumentId, sourceParentDocumentId, targetParentDocumentId)

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point,
        signal: CancellationSignal?,
    ) = real.openDocumentThumbnail(documentId, sizeHint, signal)

    /**
     * Hands the caller the write end of a pipe. A background thread ([lastTruncationThread])
     * copies at most [limit] bytes from it into [target], then stops draining and closes both
     * ends -- so [target] ends up truncated at exactly [limit] bytes regardless of how much the
     * caller goes on to write into the pipe. See [throwAfterBytes] for what this can and can't
     * signal back to the caller under Robolectric.
     *
     * On a real OS pipe, `read()` blocks until there's data or the write end has closed, so
     * `read() < 0` reliably means end-of-stream. Robolectric's [ParcelFileDescriptor.createPipe]
     * is backed by a temp file rather than an OS pipe, so a `read()` scheduled before the
     * caller's first `write()` returns EOF immediately instead of blocking -- a false "the
     * caller wrote nothing" that would otherwise truncate the file to 0 bytes regardless of
     * [limit]. This copy treats that as "nothing written *yet*" and retries after a short sleep
     * until [limit] bytes have been copied or [EOF_RETRY_DEADLINE_MS] passes, so the real pipe's
     * blocking behaviour is only ever simulated, never short-circuited.
     */
    private fun truncatingPipe(target: ParcelFileDescriptor, limit: Long): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        lastTruncationThread = thread(isDaemon = true, name = "faulty-provider-write") {
            ParcelFileDescriptor.AutoCloseInputStream(readSide).use { input ->
                ParcelFileDescriptor.AutoCloseOutputStream(target).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var written = 0L
                    val deadline = System.nanoTime() + EOF_RETRY_DEADLINE_MS * 1_000_000L
                    while (written < limit) {
                        val toRead = minOf(buffer.size.toLong(), limit - written).toInt()
                        val n = input.read(buffer, 0, toRead)
                        if (n < 0) {
                            if (System.nanoTime() >= deadline) return@thread
                            Thread.sleep(EOF_RETRY_SLEEP_MS)
                            continue
                        }
                        output.write(buffer, 0, n)
                        written += n
                    }
                }
            }
        }
        return writeSide
    }

    companion object {
        const val AUTHORITY: String = FylzFilesDocumentsProvider.AUTHORITY

        /** How long to sleep between retries after a spurious EOF -- see [truncatingPipe]. */
        private const val EOF_RETRY_SLEEP_MS = 5L

        /** How long [truncatingPipe] retries a spurious EOF before giving up. Generous because
         * it only ever matters under Robolectric's file-backed pipe; a real pipe never hits it. */
        private const val EOF_RETRY_DEADLINE_MS = 10_000L

        /**
         * Registers a fresh [FaultyDocumentsProvider], attached the way Robolectric would from a
         * real manifest `<provider>` entry -- which this test-only class doesn't have one of.
         * [android.provider.DocumentsProvider.attachInfo] throws `SecurityException` unless
         * `exported`, `grantUriPermissions` and both `MANAGE_DOCUMENTS` permissions are set, so
         * this builds that [ProviderInfo] by hand, matching `FylzFilesDocumentsProvider`'s real
         * manifest entry field for field.
         */
        fun install(): FaultyDocumentsProvider {
            val provider = Robolectric.buildContentProvider(FaultyDocumentsProvider::class.java)
                .create(testProviderInfo(FaultyDocumentsProvider::class.java, AUTHORITY))
                .get()
            return registerForContentResolver(provider, AUTHORITY)
        }
    }
}
