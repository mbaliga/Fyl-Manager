package io.github.mbaliga.fylz.archive

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin half of the FZW1 create frame codec (`docs/agent/DESIGN-M35-CREATE.md` section 2.5):
 * byte-exact against the format `write.rs`'s own module doc and [ArchiveFrameWriter]'s KDoc both
 * declare -- the magic written once, each frame's exact layout, a data frame's own chunking at
 * [ArchiveFrameWriter.MAX_DATA_FRAME_BYTES], the path length bound, and the abort message's
 * truncation at 65,535 bytes. Plain JVM.
 */
class ArchiveFrameWriterTest {

    private fun writer(out: ByteArrayOutputStream = ByteArrayOutputStream()) = ArchiveFrameWriter(out) to out

    private fun le32(bytes: ByteArray, at: Int): Int = ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.LITTLE_ENDIAN).int
    private fun le64(bytes: ByteArray, at: Int): Long = ByteBuffer.wrap(bytes, at, 8).order(ByteOrder.LITTLE_ENDIAN).long
    private fun le16(bytes: ByteArray, at: Int): Short = ByteBuffer.wrap(bytes, at, 2).order(ByteOrder.LITTLE_ENDIAN).short

    @Test
    fun `the magic is written exactly once, on the first call, whichever call it is`() {
        val (writer, out) = writer()
        assertFalse(writer.started)
        writer.entry(0, false, 5L, 0L, 0x1A4, "a.txt")
        assertTrue(writer.started)
        assertEquals("FZW1", String(out.toByteArray(), 0, 4, Charsets.US_ASCII))
        val afterEntry = out.size()
        writer.data(0, "hello".toByteArray(), 0, 5)
        writer.end(0, 5L)
        writer.finish()
        // Only ever at the very front: no second "FZW1" appears anywhere later in the stream.
        val laterBytes = out.toByteArray().copyOfRange(afterEntry, out.size())
        assertFalse(String(laterBytes, Charsets.US_ASCII).contains("FZW1"))
    }

    @Test
    fun `finish alone still writes the magic once`() {
        val (writer, out) = writer()
        writer.finish()
        assertArrayEquals(byteArrayOf('F'.code.toByte(), 'Z'.code.toByte(), 'W'.code.toByte(), '1'.code.toByte(), ArchiveFrameWriter.TAG_FINISH.toByte()), out.toByteArray())
    }

    @Test
    fun `an ENTRY frame is exactly tag, ordinal, kind, size, mtime, mode, path length, path`() {
        val (writer, out) = writer()
        writer.entry(7, false, 1234L, 999L, 0x1FF, "docs/a.txt")
        val bytes = out.toByteArray()
        var at = 4 // past the magic
        assertEquals(ArchiveFrameWriter.TAG_ENTRY, bytes[at].toInt())
        at += 1
        assertEquals(7, le32(bytes, at))
        at += 4
        assertEquals(ArchiveFrameWriter.KIND_FILE, bytes[at].toInt())
        at += 1
        assertEquals(1234L, le64(bytes, at))
        at += 8
        assertEquals(999L, le64(bytes, at))
        at += 8
        assertEquals(0x1FF, le32(bytes, at))
        at += 4
        val pathBytes = "docs/a.txt".toByteArray(Charsets.UTF_8)
        assertEquals(pathBytes.size, le32(bytes, at))
        at += 4
        assertArrayEquals(pathBytes, bytes.copyOfRange(at, at + pathBytes.size))
        assertEquals(bytes.size, at + pathBytes.size)
    }

    @Test
    fun `a directory entry's kind is 2, a null size is SIZE_UNKNOWN, and the mode is masked to 12 bits`() {
        val (writer, out) = writer()
        writer.entry(0, true, null, 0L, 0x1FFFF, "d")
        val bytes = out.toByteArray()
        // Past the magic(4) + tag(1) + ordinal(4): kind(1), size(8), mtime(8), mode(4).
        assertEquals(ArchiveFrameWriter.KIND_DIRECTORY, bytes[9].toInt())
        assertEquals(ArchiveFrameWriter.SIZE_UNKNOWN, le64(bytes, 10))
        assertEquals(0x1FFFF and 0xFFF, le32(bytes, 26))
    }

    @Test
    fun `an entry path over MAX_PATH_BYTES is refused, though the magic it starts with has already gone out`() {
        val (writer, out) = writer()
        val tooLong = "x".repeat(ArchiveFrameWriter.MAX_PATH_BYTES + 1)
        assertThrows(IllegalArgumentException::class.java) { writer.entry(0, false, 0L, 0L, 0, tooLong) }
        // start() runs before the path-length check: the magic is written, and started is true, so
        // a caller's error path correctly sees "something went out" and knows to abort, not do
        // nothing -- but no ENTRY header (or anything past the 4-byte magic) follows.
        assertEquals(4, out.size())
        assertTrue(writer.started)
    }

    @Test
    fun `a DATA frame is tag, ordinal, length, bytes -- and chunks a larger buffer at MAX_DATA_FRAME_BYTES`() {
        val (writer, out) = writer()
        val payload = ByteArray(ArchiveFrameWriter.MAX_DATA_FRAME_BYTES + 10) { (it % 251).toByte() }
        // data() alone never writes the magic (design: DATA only ever follows an ENTRY in real use,
        // which already started the stream) -- so this frame's own bytes start at offset 0.
        writer.data(3, payload, 0, payload.size)
        val bytes = out.toByteArray()
        var at = 0
        assertEquals(ArchiveFrameWriter.TAG_DATA, bytes[at].toInt())
        at += 1
        assertEquals(3, le32(bytes, at))
        at += 4
        assertEquals(ArchiveFrameWriter.MAX_DATA_FRAME_BYTES, le32(bytes, at))
        at += 4
        assertArrayEquals(payload.copyOfRange(0, ArchiveFrameWriter.MAX_DATA_FRAME_BYTES), bytes.copyOfRange(at, at + ArchiveFrameWriter.MAX_DATA_FRAME_BYTES))
        at += ArchiveFrameWriter.MAX_DATA_FRAME_BYTES
        // The second frame carries the remaining 10 bytes.
        assertEquals(ArchiveFrameWriter.TAG_DATA, bytes[at].toInt())
        at += 1
        assertEquals(3, le32(bytes, at))
        at += 4
        assertEquals(10, le32(bytes, at))
        at += 4
        assertArrayEquals(payload.copyOfRange(ArchiveFrameWriter.MAX_DATA_FRAME_BYTES, payload.size), bytes.copyOfRange(at, at + 10))
        assertEquals(bytes.size, at + 10)
    }

    @Test
    fun `data respects the offset it is given, not just the length`() {
        val (writer, out) = writer()
        val buffer = "0123456789".toByteArray()
        writer.data(1, buffer, offset = 3, length = 4)
        val bytes = out.toByteArray()
        assertArrayEquals("3456".toByteArray(), bytes.copyOfRange(9, 13))
    }

    @Test
    fun `an END frame is tag, ordinal, bytesWritten`() {
        val (writer, out) = writer()
        writer.end(5, 999_999_999_999L)
        val bytes = out.toByteArray()
        assertEquals(ArchiveFrameWriter.TAG_END, bytes[0].toInt())
        assertEquals(5, le32(bytes, 1))
        assertEquals(999_999_999_999L, le64(bytes, 5))
        assertEquals(13, bytes.size)
    }

    @Test
    fun `ABORT is tag, message length, message -- truncated at 65,535 bytes`() {
        val (writer, out) = writer()
        writer.abort("boom")
        val bytes = out.toByteArray()
        var at = 4
        assertEquals(ArchiveFrameWriter.TAG_ABORT, bytes[at].toInt())
        at += 1
        assertEquals(4, le16(bytes, at).toInt())
        at += 2
        assertEquals("boom", String(bytes, at, 4, Charsets.UTF_8))
        val (longWriter, longOut) = writer()
        longWriter.abort("x".repeat(70_000))
        val longBytes = longOut.toByteArray()
        assertEquals(65_535, le16(longBytes, 5).toInt() and 0xFFFF)
        assertEquals(4 + 1 + 2 + 65_535, longBytes.size)
    }

    @Test
    fun `a full entry -- header, one data frame, end -- matches the format byte for byte`() {
        val (writer, out) = writer()
        writer.entry(0, false, 3L, 42L, 0x1A4, "a")
        writer.data(0, "abc".toByteArray(), 0, 3)
        writer.end(0, 3L)
        writer.finish()
        val expected = ByteArrayOutputStream()
        expected.write("FZW1".toByteArray(Charsets.US_ASCII))
        expected.write(ArchiveFrameWriter.TAG_ENTRY)
        expected.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array())
        expected.write(ArchiveFrameWriter.KIND_FILE)
        expected.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(3L).array())
        expected.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(42L).array())
        expected.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x1A4).array())
        expected.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1).array())
        expected.write("a".toByteArray())
        expected.write(ArchiveFrameWriter.TAG_DATA)
        expected.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array())
        expected.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(3).array())
        expected.write("abc".toByteArray())
        expected.write(ArchiveFrameWriter.TAG_END)
        expected.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array())
        expected.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(3L).array())
        expected.write(ArchiveFrameWriter.TAG_FINISH)
        assertArrayEquals(expected.toByteArray(), out.toByteArray())
    }
}
