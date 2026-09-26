package io.github.mbaliga.fylz.decoder

import android.content.ComponentName
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The M3.4 half of `DecoderClient` (`docs/agent/DESIGN-M34-SELECTIVE-EXTRACT.md` sections 2.3 and
 * 2.5): `callStreaming`'s `busy` pause, `progress` signal and `cancelled` hook, the separate
 * extraction client from `extraction()`, its explicit `unbind()` and the absence of an idle timer.
 * The real `bindIsolatedService` is a device-only concern (DEVICE_CHECKS section 19).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DecoderClientExtractionTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private val componentName = ComponentName("io.github.mbaliga.fylz", "io.github.mbaliga.fylz.decoder.DecoderService")
    private val limits = ArchiveLimits()

    private fun archive(): ParcelFileDescriptor {
        val file = tempFolder.newFile()
        file.writeBytes(ByteArray(64))
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** `extractRanges` sleeps [delayMillis], then writes [payload] and returns; every other call is trivial. */
    private fun stub(payload: ByteArray = ByteArray(0), delayMillis: Long = 0L, onCall: () -> Unit = {}) = object : IDecoderService.Stub() {
        override fun ping() = true
        override fun sniff(pfd: ParcelFileDescriptor) = "ok"
        override fun inspectArchive(archive: ParcelFileDescriptor, limits: ArchiveLimits, maxRows: Int): ArchiveInspection = error("unused")
        override fun listArchive(archive: ParcelFileDescriptor, limits: ArchiveLimits, sink: ParcelFileDescriptor): ArchiveInspection = error("unused")
        override fun extractEntry(archive: ParcelFileDescriptor, ordinal: Int, expectedPath: String, limits: ArchiveLimits, sink: ParcelFileDescriptor): ArchiveExtractResult = error("unused")
        override fun extractRanges(archive: ParcelFileDescriptor, limits: ArchiveLimits, ordinalsBitmap: ByteArray, sink: ParcelFileDescriptor): ArchiveExtractResult {
            onCall()
            if (delayMillis > 0) Thread.sleep(delayMillis)
            ParcelFileDescriptor.AutoCloseOutputStream(sink).use { it.write(payload) }
            return ArchiveExtractResult.ok(payload.size.toLong())
        }
        override fun writeArchive(input: ParcelFileDescriptor, options: ArchiveWriteOptions, output: ParcelFileDescriptor): ArchiveWriteResult = error("unused")
    }

    private fun client(
        stub: IDecoderService.Stub,
        unbinds: AtomicInteger = AtomicInteger(),
        binds: AtomicInteger = AtomicInteger(),
        idleUnbindMillis: Long = DecoderClient.NO_IDLE_UNBIND,
        extractionFactory: (() -> DecoderClient)? = null,
    ) = DecoderClient(
        bind = { connection -> binds.incrementAndGet(); connection.onServiceConnected(componentName, stub); true },
        unbind = { unbinds.incrementAndGet() },
        idleUnbindMillis = idleUnbindMillis,
        livenessPollMillis = 20L,
        offsetProbe = { 0L },
        transactionDispatcher = Dispatchers.IO,
        retryOnDrop = false,
        extractionFactory = extractionFactory,
    )

    private suspend fun stream(
        client: DecoderClient,
        inactivityMillis: Long,
        busy: () -> Boolean = { false },
        progress: () -> Long = { 0L },
        cancelled: () -> Boolean = { false },
    ): DecoderCall<ArchiveExtractResult> {
        val pfd = archive()
        return client.callStreaming(
            archive = pfd,
            inactivityMillis = inactivityMillis,
            drain = { input -> input.copyTo(ByteArrayOutputStream()) },
            busy = busy,
            progress = progress,
            cancelled = cancelled,
            drainFailureWaitMillis = 200L,
        ) { service, sink -> service.extractRanges(pfd, limits, ByteArray(1), sink) }
    }

    @Test
    fun `a silent service is abandoned after the inactivity budget unless the caller says it is busy`() = runBlocking {
        val unbinds = AtomicInteger()
        val silent = client(stub(delayMillis = 400L), unbinds)
        assertEquals(DecoderCall.TimedOut, stream(silent, inactivityMillis = 100L))
        assertEquals(1, unbinds.get())

        val busyUnbinds = AtomicInteger()
        val busy = client(stub(ByteArray(3), delayMillis = 400L), busyUnbinds)
        val result = stream(busy, inactivityMillis = 100L, busy = { true })
        assertEquals(DecoderCall.Ok(ArchiveExtractResult.ok(3L)), result)
        assertEquals("the connection is kept", 0, busyUnbinds.get())
    }

    @Test
    fun `the caller's own progress counts as activity`() = runBlocking {
        val progress = AtomicLong()
        val ticker = Thread { repeat(40) { Thread.sleep(10); progress.incrementAndGet() } }.apply { start() }
        val c = client(stub(ByteArray(2), delayMillis = 350L))
        val result = stream(c, inactivityMillis = 100L, progress = { progress.get() })
        ticker.join()
        assertEquals(DecoderCall.Ok(ArchiveExtractResult.ok(2L)), result)
    }

    @Test
    fun `the cancelled hook closes the pipe, drops the binding and returns Failed without waiting for the service`() = runBlocking {
        val unbinds = AtomicInteger()
        val cancelled = AtomicBoolean(false)
        val c = client(stub(delayMillis = 1_500L, onCall = { cancelled.set(true) }), unbinds)
        val start = System.nanoTime()
        val result = stream(c, inactivityMillis = 5_000L, cancelled = { cancelled.get() })
        val elapsed = (System.nanoTime() - start) / 1_000_000
        assertEquals(DecoderCall.Failed, result)
        assertTrue("returned after the bounded wait, not the service's 1.5 s: $elapsed ms", elapsed < 1_200)
        assertEquals("the binding is dropped so the process can be reaped", 1, unbinds.get())
    }

    @Test
    fun `extraction hands out the factory's client and refuses without one`() = runBlocking {
        val unbinds = AtomicInteger()
        val extraction = client(stub(ByteArray(1)), unbinds)
        val browsing = client(stub(), extractionFactory = { extraction })
        assertTrue(browsing.extraction() === extraction)
        assertEquals(DecoderCall.Ok(ArchiveExtractResult.ok(1L)), stream(browsing.extraction(), inactivityMillis = 1_000L))
        val thrown = assertThrows(IllegalStateException::class.java) { client(stub()).extraction() }
        assertTrue(thrown.message!!.contains("extraction factory"))
    }

    @Test
    fun `an extraction client never unbinds on idle -- unbind releases it and the next call binds afresh`() = runBlocking {
        val unbinds = AtomicInteger()
        val binds = AtomicInteger()
        val c = client(stub(ByteArray(1)), unbinds, binds)
        assertEquals(DecoderCall.Ok(ArchiveExtractResult.ok(1L)), stream(c, inactivityMillis = 1_000L))
        delay(200L)
        assertEquals("no idle timer", 0, unbinds.get())
        c.unbind()
        assertEquals(1, unbinds.get())
        c.unbind()
        assertEquals("a second unbind with nothing bound is a no-op", 1, unbinds.get())
        assertEquals(DecoderCall.Ok(ArchiveExtractResult.ok(1L)), stream(c, inactivityMillis = 1_000L))
        assertEquals(2, binds.get())
    }

    @Test
    fun `a browsing client with the idle policy still unbinds on idle`() = runBlocking {
        val unbinds = AtomicInteger()
        val c = client(stub(ByteArray(1)), unbinds, idleUnbindMillis = 100L)
        assertEquals(DecoderCall.Ok(ArchiveExtractResult.ok(1L)), stream(c, inactivityMillis = 1_000L))
        val deadline = System.nanoTime() + 3_000_000_000L
        while (unbinds.get() == 0 && System.nanoTime() < deadline) delay(20L)
        assertEquals(1, unbinds.get())
    }
}
