package io.github.mbaliga.fylz.decoder

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.ParcelFileDescriptor
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * [DecoderClient]'s own retry/timeout/crash-recovery state machine (docs/agent/MASTER_PLAN.md
 * section 4.4), driven through the `bind`/`unbind` seam with fake [ServiceConnection] callbacks.
 * Because [IDecoderService.Stub.asInterface] recognises a same-process `Stub` and returns it
 * directly, none of this needs a real bound service or a real isolated process -- genuine
 * cross-process kill-on-timeout and crash recovery is a device-only concern, covered in
 * docs/agent/DEVICE_CHECKS.md instead. Robolectric only for a real [ParcelFileDescriptor] and
 * [ShadowLog].
 *
 * The elapsed-time cases are the point of the timeout: a stub hung for 1,500 ms under a 100 ms
 * client timeout must return its failure value well before the hang ends (measured with
 * [System.nanoTime]; the only wall-clock assertion here), because a timeout that merely waits for
 * the blocking call to finish never unbinds while the process is actually hung. Everything else
 * that needs ordering between the test and an abandoned call uses a [CountDownLatch].
 *
 * The M3.2b cases: [DecoderClient.inspectArchive]'s [DecoderCall] results, and the two GATE-M2
 * review points -- a connection dropped from under an in-flight bind is a failure value, never a
 * `CancellationException` to the caller, while the caller's own cancellation still propagates.
 * `sdk = [35]` as the rest of the suite pins it: without it Robolectric ran this class on an
 * SDK old enough to lack `ServiceConnection.onBindingDied` (API 26), which the dropped-bind
 * cases drive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DecoderClientTest {

    private val componentName = ComponentName("io.github.mbaliga.fylz", "io.github.mbaliga.fylz.decoder.DecoderService")

    private val limits = ArchiveLimits()

    private val okInspection = ArchiveInspection(
        outcome = ArchiveInspection.OUTCOME_OK,
        message = null,
        formatCode = 0x50000,
        formatName = "ZIP 2.0 (deflation)",
        filters = emptyList(),
        archiveBytes = 1_514L,
        entryCount = 8,
        fileCount = 5,
        directoryCount = 3,
        linkCount = 0,
        totalUncompressedBytes = 1_626L,
        hasEncryptedEntries = false,
        hasEncryptedMetadata = false,
        hasLossyNames = false,
        policyAllowed = true,
        policyReason = null,
        rows = listOf(ArchiveEntryInfo("hello.txt", ArchiveEntryInfo.KIND_FILE, null, 11L, 1_577_836_800L, 0x1a4, false, false, false)),
        rowsTruncated = true,
    )

    private fun instantBinder(inspection: ArchiveInspection = okInspection): IDecoderService.Stub = object : IDecoderService.Stub() {
        override fun ping() = true
        override fun listArchive(archive: ParcelFileDescriptor, limits: ArchiveLimits, sink: ParcelFileDescriptor): ArchiveInspection = error("not used")
        override fun extractEntry(archive: ParcelFileDescriptor, ordinal: Int, expectedPath: String, limits: ArchiveLimits, sink: ParcelFileDescriptor): ArchiveExtractResult = error("not used")
        override fun extractRanges(archive: ParcelFileDescriptor, limits: ArchiveLimits, ordinalsBitmap: ByteArray, sink: ParcelFileDescriptor): ArchiveExtractResult = error("not used")
        override fun sniff(pfd: ParcelFileDescriptor) = "ok"
        override fun inspectArchive(archive: ParcelFileDescriptor, limits: ArchiveLimits, maxRows: Int) = inspection
    }

    /** Never returns within any test's configured timeout, but does eventually return, so a
     *  leaked real thread from an abandoned call doesn't run forever. */
    private fun hangingBinder(hangMillis: Long = 500): IDecoderService.Stub = object : IDecoderService.Stub() {
        override fun ping(): Boolean {
            Thread.sleep(hangMillis)
            return true
        }
        override fun listArchive(archive: ParcelFileDescriptor, limits: ArchiveLimits, sink: ParcelFileDescriptor): ArchiveInspection = error("not used")
        override fun extractEntry(archive: ParcelFileDescriptor, ordinal: Int, expectedPath: String, limits: ArchiveLimits, sink: ParcelFileDescriptor): ArchiveExtractResult = error("not used")
        override fun extractRanges(archive: ParcelFileDescriptor, limits: ArchiveLimits, ordinalsBitmap: ByteArray, sink: ParcelFileDescriptor): ArchiveExtractResult = error("not used")

        override fun sniff(pfd: ParcelFileDescriptor): String {
            Thread.sleep(hangMillis)
            return "too-late"
        }

        override fun inspectArchive(archive: ParcelFileDescriptor, limits: ArchiveLimits, maxRows: Int): ArchiveInspection {
            Thread.sleep(hangMillis)
            return okInspection
        }
    }

    private fun readEndOfAPipe(): ParcelFileDescriptor = ParcelFileDescriptor.createPipe()[0]

    private fun elapsedMillisSince(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    /** Well under the stub's 1,500 ms hang, well over the 100 ms timeout plus any scheduling slack. */
    private fun assertReturnedBeforeTheHangEnded(what: String, elapsedMillis: Long) {
        assertTrue(
            "$what returned after $elapsedMillis ms: the timeout waited for the hung call instead of abandoning it",
            elapsedMillis < 1_000,
        )
    }

    /**
     * [DecoderClient]'s warnings whose message mentions [ending], once at least one exists; a bounded
     * wait, not a fixed sleep. Filtered by message because a sleeper abandoned by an earlier test in
     * this class may wake up and log its own "returned" warning at any point during a later test.
     */
    private fun awaitAbandonedCallWarnings(ending: String): List<ShadowLog.LogItem> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        fun matching() = ShadowLog.getLogsForTag(CLIENT_LOG_TAG).filter { ending in it.msg }
        while (matching().isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
        return matching()
    }

    @Test
    fun `ping succeeds through a bind that connects immediately`() = runBlocking {
        var bindCalls = 0
        val client = DecoderClient(
            bind = { connection -> bindCalls++; connection.onServiceConnected(componentName, instantBinder()); true },
            unbind = {},
        )
        assertTrue(client.ping())
        assertEquals(1, bindCalls)
    }

    @Test
    fun `a second call reuses the same connection, not a fresh bind`() = runBlocking {
        var bindCalls = 0
        val client = DecoderClient(
            bind = { connection -> bindCalls++; connection.onServiceConnected(componentName, instantBinder()); true },
            unbind = {},
        )
        client.ping()
        client.ping()
        assertEquals(1, bindCalls)
    }

    @Test
    fun `a call past its timeout is treated as failure, not left waiting`() = runBlocking {
        val client = DecoderClient(
            bind = { connection -> connection.onServiceConnected(componentName, hangingBinder()); true },
            unbind = {},
            timeoutMillis = 20,
        )
        assertFalse(client.ping())
    }

    @Test
    fun `a timeout forces a fresh bind on the next call, not the same dead connection`() = runBlocking {
        var bindCalls = 0
        val client = DecoderClient(
            bind = { connection ->
                bindCalls++
                val binder = if (bindCalls == 1) hangingBinder() else instantBinder()
                connection.onServiceConnected(componentName, binder)
                true
            },
            unbind = {},
            timeoutMillis = 20,
        )
        assertFalse(client.ping())
        assertTrue(client.ping())
        assertEquals(2, bindCalls)
    }

    @Test
    fun `sniff past its timeout returns null, matching an unsafe-to-preview file`() = runBlocking {
        val client = DecoderClient(
            bind = { connection -> connection.onServiceConnected(componentName, hangingBinder()); true },
            unbind = {},
            timeoutMillis = 20,
        )
        assertNull(client.sniff(readEndOfAPipe()))
    }

    @Test
    fun `a simulated crash (onServiceDisconnected) is recovered on the next call`() = runBlocking {
        var savedConnection: ServiceConnection? = null
        var bindCalls = 0
        val client = DecoderClient(
            bind = { connection ->
                savedConnection = connection
                bindCalls++
                connection.onServiceConnected(componentName, instantBinder())
                true
            },
            unbind = {},
        )
        assertTrue(client.ping())
        // The test-only "abort": simulate the isolated process dying mid-session, exactly as
        // Android would report it, with no real process ever created or killed.
        savedConnection?.onServiceDisconnected(componentName)
        assertTrue(client.ping())
        assertEquals(2, bindCalls)
    }

    @Test
    fun `a crash mid-call surfaces as sniff returning null, never a thrown exception`() = runBlocking {
        val diesMidCall = object : IDecoderService.Stub() {
            override fun ping() = true
            override fun listArchive(archive: ParcelFileDescriptor, limits: ArchiveLimits, sink: ParcelFileDescriptor): ArchiveInspection = error("not used")
            override fun extractEntry(archive: ParcelFileDescriptor, ordinal: Int, expectedPath: String, limits: ArchiveLimits, sink: ParcelFileDescriptor): ArchiveExtractResult = error("not used")
            override fun extractRanges(archive: ParcelFileDescriptor, limits: ArchiveLimits, ordinalsBitmap: ByteArray, sink: ParcelFileDescriptor): ArchiveExtractResult = error("not used")
            override fun sniff(pfd: ParcelFileDescriptor): String = throw DeadObjectException()
            override fun inspectArchive(archive: ParcelFileDescriptor, limits: ArchiveLimits, maxRows: Int) = okInspection
        }
        val client = DecoderClient(
            bind = { connection -> connection.onServiceConnected(componentName, diesMidCall); true },
            unbind = {},
        )
        assertNull(client.sniff(readEndOfAPipe()))
    }

    @Test
    fun `a crash mid-call also forces a fresh bind on the next call`() = runBlocking {
        var bindCalls = 0
        val client = DecoderClient(
            bind = { connection ->
                bindCalls++
                val binder = if (bindCalls == 1) {
                    object : IDecoderService.Stub() {
                        override fun ping(): Boolean = throw DeadObjectException()
                        override fun listArchive(archive: ParcelFileDescriptor, limits: ArchiveLimits, sink: ParcelFileDescriptor): ArchiveInspection = error("not used")
                        override fun extractEntry(archive: ParcelFileDescriptor, ordinal: Int, expectedPath: String, limits: ArchiveLimits, sink: ParcelFileDescriptor): ArchiveExtractResult = error("not used")
                        override fun extractRanges(archive: ParcelFileDescriptor, limits: ArchiveLimits, ordinalsBitmap: ByteArray, sink: ParcelFileDescriptor): ArchiveExtractResult = error("not used")
                        override fun sniff(pfd: ParcelFileDescriptor) = "unreached"
                        override fun inspectArchive(archive: ParcelFileDescriptor, limits: ArchiveLimits, maxRows: Int) = okInspection
                    }
                } else {
                    instantBinder()
                }
                connection.onServiceConnected(componentName, binder)
                true
            },
            unbind = {},
        )
        assertFalse(client.ping())
        assertTrue(client.ping())
        assertEquals(2, bindCalls)
    }

    @Test
    fun `sniff against a stub hung for 1500 ms returns null well before the hang ends, and unbinds`() = runBlocking {
        var unbindCalls = 0
        val client = DecoderClient(
            bind = { connection -> connection.onServiceConnected(componentName, hangingBinder(1_500)); true },
            unbind = { unbindCalls++ },
            timeoutMillis = 100,
        )
        val start = System.nanoTime()
        val result = client.sniff(readEndOfAPipe())
        val elapsed = elapsedMillisSince(start)
        assertNull(result)
        assertReturnedBeforeTheHangEnded("sniff", elapsed)
        assertEquals("the unbind is what lets the platform reap :decoders; it must happen on timeout", 1, unbindCalls)
    }

    @Test
    fun `ping against a stub hung for 1500 ms returns false well before the hang ends, and unbinds`() = runBlocking {
        var unbindCalls = 0
        val client = DecoderClient(
            bind = { connection -> connection.onServiceConnected(componentName, hangingBinder(1_500)); true },
            unbind = { unbindCalls++ },
            timeoutMillis = 100,
        )
        val start = System.nanoTime()
        val result = client.ping()
        val elapsed = elapsedMillisSince(start)
        assertFalse(result)
        assertReturnedBeforeTheHangEnded("ping", elapsed)
        assertEquals(1, unbindCalls)
    }

    @Test
    fun `after an abandoned hung call the next call rebinds and succeeds against a healthy stub`() = runBlocking {
        var bindCalls = 0
        val client = DecoderClient(
            bind = { connection ->
                bindCalls++
                val binder = if (bindCalls == 1) hangingBinder(1_500) else instantBinder()
                connection.onServiceConnected(componentName, binder)
                true
            },
            unbind = {},
            timeoutMillis = 100,
        )
        val start = System.nanoTime()
        assertFalse(client.ping())
        assertReturnedBeforeTheHangEnded("ping", elapsedMillisSince(start))
        // The first stub is still asleep on an IO thread at this point; the client must not wait for it.
        assertEquals("ok", client.sniff(readEndOfAPipe()))
        assertTrue(client.ping())
        assertEquals(2, bindCalls)
    }

    @Test
    fun `an abandoned call that later dies with DeadObjectException is swallowed, and the client stays usable`() = runBlocking {
        val release = CountDownLatch(1)
        val thrown = CountDownLatch(1)
        // The isolated process as the platform reaps it after our unbind: the transaction we
        // abandoned returns, but with DeadObjectException, only once the test lets it.
        val diesAfterAbandonment = object : IDecoderService.Stub() {
            override fun ping() = true
            override fun listArchive(archive: ParcelFileDescriptor, limits: ArchiveLimits, sink: ParcelFileDescriptor): ArchiveInspection = error("not used")
            override fun extractEntry(archive: ParcelFileDescriptor, ordinal: Int, expectedPath: String, limits: ArchiveLimits, sink: ParcelFileDescriptor): ArchiveExtractResult = error("not used")
            override fun extractRanges(archive: ParcelFileDescriptor, limits: ArchiveLimits, ordinalsBitmap: ByteArray, sink: ParcelFileDescriptor): ArchiveExtractResult = error("not used")
            override fun sniff(pfd: ParcelFileDescriptor): String {
                release.await()
                try {
                    throw DeadObjectException()
                } finally {
                    thrown.countDown()
                }
            }
            override fun inspectArchive(archive: ParcelFileDescriptor, limits: ArchiveLimits, maxRows: Int) = okInspection
        }
        var bindCalls = 0
        val uncaught = AtomicReference<Throwable?>()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.set(e) }
        try {
            val client = DecoderClient(
                bind = { connection ->
                    bindCalls++
                    val binder = if (bindCalls == 1) diesAfterAbandonment else instantBinder()
                    connection.onServiceConnected(componentName, binder)
                    true
                },
                unbind = {},
                timeoutMillis = 100,
            )
            assertNull(client.sniff(readEndOfAPipe())) // timed out; the stub is still parked on `release`
            release.countDown()
            assertTrue(thrown.await(5, TimeUnit.SECONDS))
            val warnings = awaitAbandonedCallWarnings("DeadObjectException")
            assertEquals("exactly one warning for the abandoned call, no spam", 1, warnings.size)
            assertEquals(Log.WARN, warnings.single().type)
            // Nothing propagated anywhere, and the client's own scope survived the sibling's failure.
            assertNull(uncaught.get())
            assertTrue(client.ping())
            assertEquals("ok", client.sniff(readEndOfAPipe()))
            assertEquals(2, bindCalls)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    // ------------------------------------------------------------------------------------------
    // M3.2b: inspectArchive and DecoderCall.
    // ------------------------------------------------------------------------------------------

    @Test
    fun `inspectArchive returns Ok with the service's summary, passing limits and maxRows through`() = runBlocking {
        var seen: Triple<Int, ArchiveLimits, Int>? = null
        val recording = object : IDecoderService.Stub() {
            override fun ping() = true
            override fun listArchive(archive: ParcelFileDescriptor, limits: ArchiveLimits, sink: ParcelFileDescriptor): ArchiveInspection = error("not used")
            override fun extractEntry(archive: ParcelFileDescriptor, ordinal: Int, expectedPath: String, limits: ArchiveLimits, sink: ParcelFileDescriptor): ArchiveExtractResult = error("not used")
            override fun extractRanges(archive: ParcelFileDescriptor, limits: ArchiveLimits, ordinalsBitmap: ByteArray, sink: ParcelFileDescriptor): ArchiveExtractResult = error("not used")
            override fun sniff(pfd: ParcelFileDescriptor) = "ok"
            override fun inspectArchive(archive: ParcelFileDescriptor, limits: ArchiveLimits, maxRows: Int): ArchiveInspection {
                seen = Triple(archive.fd, limits, maxRows)
                return okInspection
            }
        }
        val client = DecoderClient(
            bind = { connection -> connection.onServiceConnected(componentName, recording); true },
            unbind = {},
        )
        val pfd = readEndOfAPipe()
        val customLimits = ArchiveLimits(maxEntries = 7, maxListingEntries = 9)
        val result = client.inspectArchive(pfd, customLimits, maxRows = 3)
        assertEquals(DecoderCall.Ok(okInspection), result)
        assertEquals(Triple(pfd.fd, customLimits, 3), seen)
        // The client never closes the caller's descriptor.
        assertTrue(pfd.fileDescriptor.valid())
        assertEquals(500, DecoderClient.DEFAULT_MAX_ROWS)
        assertEquals(30_000L, DecoderClient.STRUCTURE_TIMEOUT_MILLIS)
    }

    @Test
    fun `a stub answering with outcome CORRUPT is Ok(summary) -- the engine's verdict, not a client failure`() = runBlocking {
        val corrupt = ArchiveInspection.failed(ArchiveInspection.OUTCOME_CORRUPT, "Truncated input file")
        val client = DecoderClient(
            bind = { connection -> connection.onServiceConnected(componentName, instantBinder(corrupt)); true },
            unbind = {},
        )
        val result = client.inspectArchive(readEndOfAPipe(), limits)
        assertTrue(result is DecoderCall.Ok)
        val summary = (result as DecoderCall.Ok).value
        assertEquals(ArchiveInspection.OUTCOME_CORRUPT, summary.outcome)
        assertEquals("Truncated input file", summary.message)
        assertFalse(summary.isOk)
    }

    @Test
    fun `inspectArchive against a stub hung for 1500 ms is TimedOut well before the hang ends, and unbinds`() = runBlocking {
        var unbindCalls = 0
        val client = DecoderClient(
            bind = { connection -> connection.onServiceConnected(componentName, hangingBinder(1_500)); true },
            unbind = { unbindCalls++ },
        )
        val start = System.nanoTime()
        val result = client.inspectArchive(readEndOfAPipe(), limits, timeoutMillis = 100)
        val elapsed = elapsedMillisSince(start)
        assertEquals(DecoderCall.TimedOut, result)
        assertReturnedBeforeTheHangEnded("inspectArchive", elapsed)
        assertEquals(1, unbindCalls)
    }

    @Test
    fun `inspectArchive surfacing DeadObjectException is Failed, and the next call rebinds`() = runBlocking {
        var bindCalls = 0
        val client = DecoderClient(
            bind = { connection ->
                bindCalls++
                val binder = if (bindCalls == 1) {
                    object : IDecoderService.Stub() {
                        override fun ping() = true
                        override fun listArchive(archive: ParcelFileDescriptor, limits: ArchiveLimits, sink: ParcelFileDescriptor): ArchiveInspection = error("not used")
                        override fun extractEntry(archive: ParcelFileDescriptor, ordinal: Int, expectedPath: String, limits: ArchiveLimits, sink: ParcelFileDescriptor): ArchiveExtractResult = error("not used")
                        override fun extractRanges(archive: ParcelFileDescriptor, limits: ArchiveLimits, ordinalsBitmap: ByteArray, sink: ParcelFileDescriptor): ArchiveExtractResult = error("not used")
                        override fun sniff(pfd: ParcelFileDescriptor) = "ok"
                        override fun inspectArchive(archive: ParcelFileDescriptor, limits: ArchiveLimits, maxRows: Int): ArchiveInspection =
                            throw DeadObjectException()
                    }
                } else {
                    instantBinder()
                }
                connection.onServiceConnected(componentName, binder)
                true
            },
            unbind = {},
        )
        assertEquals(DecoderCall.Failed, client.inspectArchive(readEndOfAPipe(), limits))
        assertEquals(DecoderCall.Ok(okInspection), client.inspectArchive(readEndOfAPipe(), limits))
        assertEquals(2, bindCalls)
    }

    @Test
    fun `a bind that returns false is Failed, never a thrown exception`() = runBlocking {
        var unbindCalls = 0
        val client = DecoderClient(bind = { false }, unbind = { unbindCalls++ })
        assertEquals(DecoderCall.Failed, client.inspectArchive(readEndOfAPipe(), limits))
        assertFalse(client.ping())
        assertNull(client.sniff(readEndOfAPipe()))
        // A `bindService` that returned false still needs `unbindService`: exactly one per failure.
        assertEquals(3, unbindCalls)
    }

    @Test
    fun `a bind that throws is Failed, never a thrown exception`() = runBlocking {
        val client = DecoderClient(bind = { throw SecurityException("simulated: bindService refused") }, unbind = {})
        assertEquals(DecoderCall.Failed, client.inspectArchive(readEndOfAPipe(), limits))
        assertFalse(client.ping())
    }

    /**
     * GATE-M2 review point: a `dropConnection()` racing an in-flight `ensureConnected()`. The bind
     * here never connects on its own; while the call is parked waiting for `onServiceConnected`,
     * the binding dies. The caller must get the documented failure value, not the internal
     * deferred's `CancellationException`.
     */
    @Test
    fun `a connection dropped from under an in-flight bind is Failed, not a CancellationException`() = runBlocking {
        var saved: ServiceConnection? = null
        var unbindCalls = 0
        val client = DecoderClient(bind = { connection -> saved = connection; true }, unbind = { unbindCalls++ })
        val inFlight = async { client.inspectArchive(readEndOfAPipe(), limits) }
        while (saved == null) yield()
        yield() // let the call reach its await on the pending connection
        saved!!.onBindingDied(componentName)
        assertEquals(DecoderCall.Failed, inFlight.await())
        assertEquals("the death's own drop unbinds once; the failing call does not unbind again", 1, unbindCalls)
        // And the same through the nullable API.
        saved = null
        val pingInFlight = async { client.ping() }
        while (saved == null) yield()
        yield()
        saved!!.onServiceDisconnected(componentName)
        assertFalse(pingInFlight.await())
    }

    /** The caller's own cancellation keeps its meaning: it propagates, it is not swallowed into `Failed`. */
    @Test
    fun `the caller's own cancellation of an in-flight call still propagates`() = runBlocking {
        var saved: ServiceConnection? = null
        val client = DecoderClient(bind = { connection -> saved = connection; true }, unbind = {})
        var outcome: Any? = null
        val job = launch {
            try {
                outcome = client.inspectArchive(readEndOfAPipe(), limits)
            } catch (e: CancellationException) {
                outcome = e
            }
        }
        while (saved == null) yield()
        yield()
        job.cancel()
        job.join()
        assertTrue("expected the caller's CancellationException, got $outcome", outcome is CancellationException)
    }

    @Test
    fun `after a dropped bind the next call binds afresh and succeeds`() = runBlocking {
        var saved: ServiceConnection? = null
        var bindCalls = 0
        val client = DecoderClient(
            bind = { connection ->
                bindCalls++
                saved = connection
                if (bindCalls == 2) connection.onServiceConnected(componentName, instantBinder())
                true
            },
            unbind = {},
        )
        val inFlight = async { client.ping() }
        while (saved == null) yield()
        yield()
        saved!!.onBindingDied(componentName)
        assertFalse(inFlight.await())
        assertTrue(client.ping())
        assertEquals(2, bindCalls)
    }

    private companion object {
        const val CLIENT_LOG_TAG = "DecoderClient"
    }
}
