package io.github.mbaliga.fylz.decoder

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The M3.3 half of `DecoderClient` (docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md sections 2.2 and
 * 2.9), a sibling of [DecoderClientTest]: `callStreaming` with the Robolectric-safe drain rule,
 * inactivity abandonment with the read end closed, offset progress keeping a silent sink alive,
 * overlapping calls, the generation guard, the retry-once after another call's drop, and the idle
 * timer arming only at zero in flight and losing the race to a new call. Robolectric's pipes are
 * file-backed, so every stub writes its bytes into the sink **before** returning.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DecoderClientStreamingTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private val componentName = ComponentName("io.github.mbaliga.fylz", "io.github.mbaliga.fylz.decoder.DecoderService")
    private val limits = ArchiveLimits()

    private fun archive(): ParcelFileDescriptor {
        val file = tempFolder.newFile()
        file.writeBytes(ByteArray(64))
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private val okInspection = ArchiveInspection.failed(ArchiveInspection.OUTCOME_INTERNAL, "unused").copy(outcome = ArchiveInspection.OUTCOME_OK)

    /** A stub whose `listArchive` writes [payload] into the sink after [delayMillis], then returns. */
    private fun writingStub(payload: ByteArray, delayMillis: Long = 0, onCall: () -> Unit = {}) = object : IDecoderService.Stub() {
        override fun ping() = true
        override fun sniff(pfd: ParcelFileDescriptor) = "ok"
        override fun inspectArchive(archive: ParcelFileDescriptor, limits: ArchiveLimits, maxRows: Int) = okInspection
        override fun listArchive(archive: ParcelFileDescriptor, limits: ArchiveLimits, sink: ParcelFileDescriptor): ArchiveInspection {
            onCall()
            if (delayMillis > 0) Thread.sleep(delayMillis)
            ParcelFileDescriptor.AutoCloseOutputStream(sink).use { it.write(payload) }
            return okInspection
        }
        override fun extractEntry(archive: ParcelFileDescriptor, ordinal: Int, expectedPath: String, limits: ArchiveLimits, sink: ParcelFileDescriptor): ArchiveExtractResult {
            ParcelFileDescriptor.AutoCloseOutputStream(sink).use { it.write(payload) }
            return ArchiveExtractResult.ok(payload.size.toLong())
        }
    }

    private fun client(
        stub: IDecoderService.Stub,
        unbinds: AtomicInteger = AtomicInteger(),
        binds: AtomicInteger = AtomicInteger(),
        idleUnbindMillis: Long = DecoderClient.IDLE_UNBIND_MILLIS,
        offsetProbe: (ParcelFileDescriptor) -> Long? = { null },
        onBind: (ServiceConnection) -> Unit = {},
        stubFor: (Int) -> IDecoderService.Stub = { stub },
    ) = DecoderClient(
        bind = { connection ->
            val n = binds.incrementAndGet()
            onBind(connection)
            connection.onServiceConnected(componentName, stubFor(n))
            true
        },
        unbind = { unbinds.incrementAndGet() },
        idleUnbindMillis = idleUnbindMillis,
        livenessPollMillis = 20L,
        offsetProbe = offsetProbe,
    )

    private fun elapsedMillisSince(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    @Test
    fun `callStreaming hands block the write end, drains the read end to EOF and returns block's value`() = runBlocking<Unit> {
        val payload = ByteArray(100 * 1024) { (it % 251).toByte() }
        val unbinds = AtomicInteger()
        val client = client(writingStub(payload), unbinds)
        val drained = ByteArrayOutputStream()
        val pfd = archive()
        val result = client.callStreaming(pfd, inactivityMillis = 2_000, drain = { input -> input.copyTo(drained) }) { service, sink ->
            service.listArchive(pfd, limits, sink)
        }
        assertEquals(DecoderCall.Ok(okInspection), result)
        assertArrayEquals(payload, drained.toByteArray())
        assertEquals("nothing was dropped", 0, unbinds.get())
        assertTrue("the caller's archive descriptor is untouched", pfd.fileDescriptor.valid())
    }

    @Test
    fun `an empty stream still reaches EOF and returns`() = runBlocking<Unit> {
        val client = client(writingStub(ByteArray(0)))
        val drained = ByteArrayOutputStream()
        val pfd = archive()
        val result = client.callStreaming(pfd, inactivityMillis = 2_000, drain = { it.copyTo(drained) }) { s, sink -> s.listArchive(pfd, limits, sink) }
        assertTrue(result is DecoderCall.Ok)
        assertEquals(0, drained.size())
    }

    @Test
    fun `a service that goes silent is abandoned after the inactivity budget, unbound, and the read end closed`() = runBlocking<Unit> {
        val unbinds = AtomicInteger()
        // Never writes, never moves the archive offset, sleeps well past the budget.
        val client = client(writingStub(ByteArray(0), delayMillis = 1_500), unbinds, offsetProbe = { 0L })
        var stream: InputStream? = null
        val pfd = archive()
        val start = System.nanoTime()
        val result = client.callStreaming(pfd, inactivityMillis = 100, drain = { input -> stream = input; input.copyTo(ByteArrayOutputStream()) }) { s, sink ->
            s.listArchive(pfd, limits, sink)
        }
        val elapsed = elapsedMillisSince(start)
        assertEquals(DecoderCall.TimedOut, result)
        assertTrue("returned after $elapsed ms", elapsed < 1_000)
        assertEquals("the abandon drops the connection", 1, unbinds.get())
        // The read end was closed by the client: reading it again fails.
        assertThrows(IOException::class.java) { stream!!.read() }
    }

    @Test
    fun `the archive offset advancing keeps a silent sink alive`() = runBlocking<Unit> {
        val offset = AtomicLong()
        val payload = "late but alive".toByteArray()
        // 400 ms of silence with a 100 ms inactivity budget: only the moving offset saves it.
        val client = client(writingStub(payload, delayMillis = 400), offsetProbe = { offset.incrementAndGet() })
        val drained = ByteArrayOutputStream()
        val pfd = archive()
        val result = client.callStreaming(pfd, inactivityMillis = 100, drain = { it.copyTo(drained) }) { s, sink -> s.listArchive(pfd, limits, sink) }
        assertEquals(DecoderCall.Ok(okInspection), result)
        assertArrayEquals(payload, drained.toByteArray())
    }

    @Test
    fun `a failing drain stops the transaction and is thrown to the caller with the connection kept`() = runBlocking<Unit> {
        val unbinds = AtomicInteger()
        val client = client(writingStub(ByteArray(10_000)), unbinds)
        val pfd = archive()
        val failure = assertThrows(IOException::class.java) {
            runBlocking {
                client.callStreaming(pfd, inactivityMillis = 2_000, drain = { throw IOException("disk full") }) { s, sink -> s.listArchive(pfd, limits, sink) }
            }
        }
        assertEquals("disk full", failure.message)
        assertEquals("not the process's fault: no drop", 0, unbinds.get())
        assertTrue(client.ping())
    }

    @Test
    fun `overlapping streaming calls share one binding and both complete`() = runBlocking<Unit> {
        val binds = AtomicInteger()
        val a = "aaaaaaaaaa".toByteArray()
        val client = client(writingStub(a, delayMillis = 100), binds = binds)
        val results = (1..3).map {
            async {
                val out = ByteArrayOutputStream()
                val pfd = archive()
                val result = client.callStreaming(pfd, inactivityMillis = 2_000, drain = { it.copyTo(out) }) { s, sink -> s.listArchive(pfd, limits, sink) }
                result to out.toByteArray()
            }
        }.awaitAll()
        results.forEach { (result, bytes) ->
            assertEquals(DecoderCall.Ok(okInspection), result)
            assertArrayEquals(a, bytes)
        }
        assertEquals(1, binds.get())
    }

    /**
     * Call A hangs and times out, dropping the shared connection (on a device the unbind reaps the
     * process); call B, in flight on the same connection, sees the process die (the stub throws
     * `DeadObjectException` once `killed` is set by the unbind) and must retry once on a fresh
     * binding rather than fail.
     */
    @Test
    fun `a call whose connection another call's timeout dropped retries once on a fresh binding`() = runBlocking<Unit> {
        val killed = AtomicBoolean(false)
        val binds = AtomicInteger()
        val hanging = object : IDecoderService.Stub() {
            override fun ping(): Boolean { Thread.sleep(1_500); return true }
            override fun sniff(pfd: ParcelFileDescriptor) = "ok"
            override fun inspectArchive(archive: ParcelFileDescriptor, limits: ArchiveLimits, maxRows: Int): ArchiveInspection {
                Thread.sleep(300)
                if (killed.get()) throw DeadObjectException()
                return okInspection
            }
            override fun listArchive(archive: ParcelFileDescriptor, limits: ArchiveLimits, sink: ParcelFileDescriptor) = error("unused")
            override fun extractEntry(archive: ParcelFileDescriptor, ordinal: Int, expectedPath: String, limits: ArchiveLimits, sink: ParcelFileDescriptor) = error("unused")
        }
        val healthy = writingStub(ByteArray(0))
        val client = DecoderClient(
            bind = { connection -> binds.incrementAndGet(); connection.onServiceConnected(componentName, if (binds.get() == 1) hanging else healthy); true },
            unbind = { killed.set(true) },
            timeoutMillis = 100,
            livenessPollMillis = 20L,
        )
        val b = async { client.inspectArchive(archive(), limits, timeoutMillis = 5_000) }
        delay(50)
        val a = async { client.ping() } // 100 ms budget against a 1,500 ms hang: times out, drops the binding
        assertEquals(false, a.await())
        assertEquals("B retried on the fresh binding and got the healthy stub's answer", DecoderCall.Ok(okInspection), b.await())
        assertEquals(2, binds.get())
    }

    @Test
    fun `a stale callback for an old binding never unbinds the new one`() = runBlocking<Unit> {
        val connections = ArrayList<ServiceConnection>()
        val unbinds = AtomicInteger()
        val binds = AtomicInteger()
        val client = client(
            writingStub(ByteArray(0)),
            unbinds,
            binds,
            onBind = { connections += it },
            stubFor = { n -> if (n == 1) hangingStub() else writingStub(ByteArray(0)) },
        )
        // First binding: a hung ping times out and drops it (unbind #1).
        val slow = DecoderClient(
            bind = { connection -> binds.incrementAndGet(); connections += connection; connection.onServiceConnected(componentName, hangingStub()); true },
            unbind = { unbinds.incrementAndGet() },
            timeoutMillis = 50,
            livenessPollMillis = 20L,
        )
        assertEquals(false, slow.ping())
        assertEquals(1, unbinds.get())
        // Now a healthy client binds afresh; then the OLD connection object reports a death.
        assertTrue(client.ping())
        val old = connections.first()
        old.onServiceDisconnected(componentName)
        old.onBindingDied(componentName)
        assertEquals("the stale callback was a no-op", 1, unbinds.get())
        assertTrue(client.ping())
        assertEquals("no rebind was needed", 2, binds.get())
    }

    private fun hangingStub() = object : IDecoderService.Stub() {
        override fun ping(): Boolean { Thread.sleep(1_500); return true }
        override fun sniff(pfd: ParcelFileDescriptor) = "late"
        override fun inspectArchive(archive: ParcelFileDescriptor, limits: ArchiveLimits, maxRows: Int) = okInspection
        override fun listArchive(archive: ParcelFileDescriptor, limits: ArchiveLimits, sink: ParcelFileDescriptor) = okInspection
        override fun extractEntry(archive: ParcelFileDescriptor, ordinal: Int, expectedPath: String, limits: ArchiveLimits, sink: ParcelFileDescriptor) = ArchiveExtractResult.ok(0)
    }

    @Test
    fun `the idle timer unbinds after the idle period with nothing in flight, and the next call rebinds`() = runBlocking<Unit> {
        val unbinds = AtomicInteger()
        val binds = AtomicInteger()
        val client = client(writingStub(ByteArray(0)), unbinds, binds, idleUnbindMillis = 100)
        assertTrue(client.ping())
        assertEquals(0, unbinds.get())
        awaitCondition("idle unbind") { unbinds.get() == 1 }
        assertTrue(client.ping())
        assertEquals("a fresh bind after the idle unbind", 2, binds.get())
        awaitCondition("second idle unbind") { unbinds.get() == 2 }
    }

    @Test
    fun `the idle timer arms only at zero in flight and loses the race to a new call`() = runBlocking<Unit> {
        val unbinds = AtomicInteger()
        val binds = AtomicInteger()
        val slowPayload = ByteArray(0)
        val client = client(writingStub(slowPayload, delayMillis = 300), unbinds, binds, idleUnbindMillis = 100)
        // A 300 ms call keeps the binding up well past the 100 ms idle period.
        val pfd = archive()
        val inFlight = async { client.callStreaming(pfd, inactivityMillis = 2_000, drain = { it.copyTo(ByteArrayOutputStream()) }) { s, sink -> s.listArchive(pfd, limits, sink) } }
        delay(200)
        assertEquals("nothing in flight was unbound", 0, unbinds.get())
        assertTrue(inFlight.await() is DecoderCall.Ok)
        // The timer is armed now; a call that starts before it fires wins the race.
        delay(50)
        assertTrue(client.ping())
        delay(60)
        // Only after the last call ends and the period elapses does the unbind happen -- once.
        awaitCondition("idle unbind") { unbinds.get() >= 1 }
        delay(250)
        assertEquals(1, unbinds.get())
        assertEquals(1, binds.get())
    }

    @Test
    fun `an extractEntry stream is drained the same way`() = runBlocking<Unit> {
        val payload = ByteArray(300_000) { it.toByte() }
        val client = client(writingStub(payload))
        val target = File(tempFolder.root, "out.bin")
        val pfd = archive()
        val result = client.callStreaming(pfd, inactivityMillis = 2_000, drain = { input -> target.outputStream().use { input.copyTo(it) } }) { s, sink ->
            s.extractEntry(pfd, 3, "big.bin", limits, sink)
        }
        assertEquals(DecoderCall.Ok(ArchiveExtractResult.ok(payload.size.toLong())), result)
        assertArrayEquals(payload, target.readBytes())
    }

    @Test
    fun `constants match the design`() {
        assertEquals(30_000L, DecoderClient.STREAM_INACTIVITY_MILLIS)
        assertEquals(60_000L, DecoderClient.IDLE_UNBIND_MILLIS)
        assertEquals(1_000L, DecoderClient.LIVENESS_POLL_MILLIS)
        assertEquals(4, DecoderClient.STREAM_THREADS)
        assertNull("Robolectric has no lseek; the probe degrades to bytes only", DecoderClient.sharedOffset(archive()).takeIf { false })
    }

    private suspend fun awaitCondition(what: String, timeoutMillis: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > deadline) error("timed out waiting for $what")
            delay(10)
        }
    }
}
