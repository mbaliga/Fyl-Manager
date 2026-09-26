package io.github.mbaliga.fylz.archive

import android.os.ParcelFileDescriptor
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Documents the Robolectric limitation [FakeArchiveDecoder.writeArchive]'s own `pollingRead`
 * works around: a real OS pipe's read end never reports EOF while its write end stays open --
 * that is exactly what lets [io.github.mbaliga.fylz.decoder.DecoderClient.callTwoPipes]'s
 * concurrent feeder/engine/drain rendezvous work in production. Robolectric's
 * [ParcelFileDescriptor.createPipe] does not honour that: a read attempted before a concurrent
 * writer -- on a real, separate thread -- has produced anything yet returns EOF (-1) immediately,
 * indistinguishable from the write end having actually closed. A retry once the writer has
 * actually written succeeds: the bytes are not lost, only a same-instant read sees nothing yet.
 *
 * Without a workaround, this would make `writeArchive` fail almost every real call with a
 * spurious "input ended" protocol error the moment the fake stub's transaction coroutine happens
 * to be scheduled even slightly ahead of the feeder's -- exactly the class of Robolectric-specific
 * gap `docs/agent/DESIGN-M35-CREATE.md` section 2.8 names for a plain-JVM test instead of a skip;
 * here the fix is a bounded retry in the test double itself, proven correct by this test, rather
 * than skipping the Robolectric-hosted `ArchiveCreatorTest` altogether.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RobolectricPipeReadTest {

    @Test
    fun `a read attempted before a concurrent writer, on another thread, catches up sees EOF, not the writer's bytes`() {
        val pipe = ParcelFileDescriptor.createPipe()
        val writer = Thread {
            Thread.sleep(150)
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(byteArrayOf(1, 2, 3, 4)) }
        }
        writer.start()
        val input = ParcelFileDescriptor.AutoCloseInputStream(pipe[0])
        val buffer = ByteArray(4)
        val immediate = input.read(buffer)
        writer.join()
        assertEquals("a real pipe would block here, not report EOF, while the write end is still open", -1, immediate)
    }

    @Test
    fun `retrying that same read end after the writer actually writes recovers the bytes -- they were never lost`() {
        val pipe = ParcelFileDescriptor.createPipe()
        val writer = Thread {
            Thread.sleep(150)
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(byteArrayOf(9, 8, 7, 6)) }
        }
        writer.start()
        val input = ParcelFileDescriptor.AutoCloseInputStream(pipe[0])
        val buffer = ByteArray(4)
        var n = input.read(buffer)
        var retries = 0
        while (n < 0 && retries < 100) {
            Thread.sleep(10)
            n = input.read(buffer)
            retries += 1
        }
        writer.join()
        assertEquals(4, n)
        assertEquals(listOf<Byte>(9, 8, 7, 6), buffer.toList())
    }
}
