package io.github.mbaliga.fylz.decoder

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [DecoderClient]'s own retry/timeout/crash-recovery state machine (docs/agent/MASTER_PLAN.md
 * section 4.4), driven through the `bind`/`unbind` seam with fake [ServiceConnection] callbacks.
 * Because [IDecoderService.Stub.asInterface] recognises a same-process `Stub` and returns it
 * directly, none of this needs a real bound service or a real isolated process -- genuine
 * cross-process kill-on-timeout and crash recovery is a device-only concern, covered in
 * docs/agent/DEVICE_CHECKS.md instead. Robolectric only for a real [ParcelFileDescriptor].
 */
@RunWith(RobolectricTestRunner::class)
class DecoderClientTest {

    private val componentName = ComponentName("io.github.mbaliga.fylz", "io.github.mbaliga.fylz.decoder.DecoderService")

    private fun instantBinder(): IDecoderService.Stub = object : IDecoderService.Stub() {
        override fun ping() = true
        override fun sniff(pfd: ParcelFileDescriptor) = "ok"
    }

    /** Never returns within any test's configured timeout, but does eventually return, so a
     *  leaked real thread from an abandoned call doesn't run forever. */
    private fun hangingBinder(hangMillis: Long = 500): IDecoderService.Stub = object : IDecoderService.Stub() {
        override fun ping(): Boolean {
            Thread.sleep(hangMillis)
            return true
        }

        override fun sniff(pfd: ParcelFileDescriptor): String {
            Thread.sleep(hangMillis)
            return "too-late"
        }
    }

    private fun readEndOfAPipe(): ParcelFileDescriptor = ParcelFileDescriptor.createPipe()[0]

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
            override fun sniff(pfd: ParcelFileDescriptor): String = throw DeadObjectException()
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
                        override fun sniff(pfd: ParcelFileDescriptor) = "unreached"
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
}
