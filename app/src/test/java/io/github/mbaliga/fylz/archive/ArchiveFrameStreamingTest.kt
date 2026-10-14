package io.github.mbaliga.fylz.archive

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one case `docs/agent/DESIGN-M35-CREATE.md` section 2.8 names as genuinely
 * Robolectric-impossible, proven here on a plain JVM `PipedInputStream`/`PipedOutputStream` pair
 * instead of skipped: real, concurrent producer/consumer streaming with real blocking-pipe/EOF
 * semantics -- a reader blocks until the writer, on another thread, actually produces more (or
 * closes), rather than seeing a false end of stream the instant nothing is buffered yet. Robolectric's
 * own `ParcelFileDescriptor` pipes do not honour that (proven the other way, over that specific
 * shadow, by [RobolectricPipeReadTest]) -- production `DecoderClient.callTwoPipes` depends on the
 * REAL semantics this test exercises, which is exactly why [FakeArchiveDecoder.writeArchive]'s own
 * `pollingRead` workaround is needed only in the Robolectric-hosted test double, never here.
 *
 * [ArchiveFrameWriter] writes on one real thread into a tiny-buffered `PipedOutputStream` (1 KiB,
 * far smaller than the entries below); a hand-rolled `FZW1` reader, mirroring
 * [FakeArchiveDecoder.writeArchive]'s own parsing exactly, consumes concurrently on another. A
 * large entry (`LARGE_ENTRY_BYTES`, far past that buffer) can only arrive intact if the pipe
 * genuinely blocks the writer until the reader drains and genuinely blocks the reader until the
 * writer produces more -- neither side polls or sleeps.
 */
class ArchiveFrameStreamingTest {

    private class ReadEntry(val ordinal: Int, val isDirectory: Boolean, val declaredSize: Long, val path: String) {
        val body = ByteArrayOutputStream()
    }

    private class ReadResult {
        val entries = ArrayList<ReadEntry>()
        var finished = false
        var abortMessage: String? = null
    }

    /** Mirrors [FakeArchiveDecoder.writeArchive]'s own frame parsing, minus anything Android --
     * a real [InputStream.read] here genuinely blocks, so this needs no `pollingRead` at all. */
    private fun readFrames(input: InputStream): ReadResult {
        val result = ReadResult()
        val magic = ByteArray(4)
        readFully(input, magic)
        check(String(magic, Charsets.US_ASCII) == "FZW1") { "bad magic" }
        var openOrdinal: Int? = null
        var openEntry: ReadEntry? = null
        while (true) {
            val tag = input.read()
            check(tag >= 0) { "input ended before FINISH or ABORT" }
            when (tag) {
                ArchiveFrameWriter.TAG_ENTRY -> {
                    check(openOrdinal == null) { "ENTRY while an entry is open" }
                    val header = ByteArray(4 + 1 + 8 + 8 + 4 + 4)
                    readFully(input, header)
                    val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                    val ordinal = buffer.int
                    val kind = buffer.get().toInt()
                    val size = buffer.long
                    buffer.long // mtime, unused here
                    buffer.int // mode, unused here
                    val pathLen = buffer.int
                    val pathBytes = ByteArray(pathLen)
                    readFully(input, pathBytes)
                    val entry = ReadEntry(ordinal, kind == ArchiveFrameWriter.KIND_DIRECTORY, size, String(pathBytes, Charsets.UTF_8))
                    result.entries += entry
                    if (!entry.isDirectory) {
                        openOrdinal = ordinal
                        openEntry = entry
                    }
                }
                ArchiveFrameWriter.TAG_DATA -> {
                    val header = ByteArray(4 + 4)
                    readFully(input, header)
                    val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                    val ordinal = buffer.int
                    val length = buffer.int
                    val chunk = ByteArray(length)
                    readFully(input, chunk)
                    check(openOrdinal == ordinal) { "DATA for $ordinal with no matching open entry" }
                    openEntry!!.body.write(chunk)
                }
                ArchiveFrameWriter.TAG_END -> {
                    val header = ByteArray(4 + 8)
                    readFully(input, header)
                    val ordinal = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
                    check(openOrdinal == ordinal) { "END for $ordinal with no matching open entry" }
                    openOrdinal = null
                    openEntry = null
                }
                ArchiveFrameWriter.TAG_FINISH -> {
                    check(openOrdinal == null) { "FINISH while an entry is open" }
                    result.finished = true
                    return result
                }
                ArchiveFrameWriter.TAG_ABORT -> {
                    check(openOrdinal == null) { "ABORT while an entry is open" }
                    val lengthBytes = ByteArray(2)
                    readFully(input, lengthBytes)
                    val messageLength = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                    val messageBytes = ByteArray(messageLength)
                    readFully(input, messageBytes)
                    result.abortMessage = String(messageBytes, Charsets.UTF_8)
                    return result
                }
                else -> error("unknown frame tag $tag")
            }
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var at = 0
        while (at < buffer.size) {
            val n = input.read(buffer, at, buffer.size - at)
            check(n >= 0) { "input ended mid-frame" }
            at += n
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `a large entry survives real concurrent streaming through a pipe far smaller than it`() {
        val input = PipedInputStream(PIPE_BUFFER_BYTES)
        val output = PipedOutputStream(input)
        val large = ByteArray(LARGE_ENTRY_BYTES) { (it % 253).toByte() }
        val small = "a tiny second entry".toByteArray()

        var writerFailure: Throwable? = null
        val writer = Thread {
            try {
                val frames = ArchiveFrameWriter(output)
                frames.entry(0, isDirectory = false, sizeBytes = large.size.toLong(), mtimeEpochMillis = 1_000L, mode = 0x1A4, path = "big.bin")
                frames.data(0, large, 0, large.size)
                frames.end(0, large.size.toLong())
                frames.entry(1, isDirectory = false, sizeBytes = small.size.toLong(), mtimeEpochMillis = 2_000L, mode = 0x1A4, path = "small.txt")
                frames.data(1, small, 0, small.size)
                frames.end(1, small.size.toLong())
                frames.finish()
            } catch (failure: Throwable) {
                writerFailure = failure
            } finally {
                output.close()
            }
        }
        var result: ReadResult? = null
        var readerFailure: Throwable? = null
        val reader = Thread {
            try {
                result = readFrames(input)
            } catch (failure: Throwable) {
                readerFailure = failure
            }
        }

        reader.start()
        writer.start()
        writer.join(TimeUnit.SECONDS.toMillis(10))
        reader.join(TimeUnit.SECONDS.toMillis(10))
        assertTrue("the writer thread must have finished", !writer.isAlive)
        assertTrue("the reader thread must have finished", !reader.isAlive)

        assertEquals(null, writerFailure)
        assertEquals(null, readerFailure)
        val settled = result!!
        assertTrue(settled.finished)
        assertEquals(2, settled.entries.size)
        assertEquals("big.bin", settled.entries[0].path)
        assertArrayEquals(large, settled.entries[0].body.toByteArray())
        assertEquals(sha256(large), sha256(settled.entries[0].body.toByteArray()))
        assertEquals("small.txt", settled.entries[1].path)
        assertArrayEquals(small, settled.entries[1].body.toByteArray())
    }

    @Test
    fun `an ABORT sent concurrently is received intact after real streaming, never masked by a false EOF`() {
        val input = PipedInputStream(PIPE_BUFFER_BYTES)
        val output = PipedOutputStream(input)
        val body = ByteArray(LARGE_ENTRY_BYTES) { 7 }

        val writer = Thread {
            output.use {
                val frames = ArchiveFrameWriter(output)
                frames.entry(0, isDirectory = false, sizeBytes = body.size.toLong(), mtimeEpochMillis = 0L, mode = 0x1A4, path = "x.bin")
                frames.data(0, body, 0, body.size)
                frames.end(0, body.size.toLong())
                frames.abort("source went away")
            }
        }
        var result: ReadResult? = null
        val reader = Thread { result = readFrames(input) }

        reader.start()
        writer.start()
        writer.join(TimeUnit.SECONDS.toMillis(10))
        reader.join(TimeUnit.SECONDS.toMillis(10))

        val settled = result!!
        assertTrue("the entry's own data still arrived before the abort", settled.entries.single().body.toByteArray().size == body.size)
        assertEquals(false, settled.finished)
        assertEquals("source went away", settled.abortMessage)
    }

    private companion object {
        /** Far smaller than [LARGE_ENTRY_BYTES]: forces the writer to block on a full pipe and the
         * reader to block on an empty one, repeatedly, for the whole entry to cross at all. */
        const val PIPE_BUFFER_BYTES = 1024
        const val LARGE_ENTRY_BYTES = 512 * 1024
    }
}
