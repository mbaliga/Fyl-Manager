package io.github.mbaliga.fylz.archive

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin half of the FZX1 extraction frame codec (`docs/agent/DESIGN-M34-SELECTIVE-EXTRACT.md`
 * section 2.3 step 4): the golden `tree.fzx` the Rust writer produced from `tree.tar.zst` decodes
 * to the fixture's 51 entries with every file's bytes equal to the same file in `tree.zip`; every
 * frame kind including a stand-alone `FAIL`; an empty stream; each bounds rule a hostile decoder
 * process could break is a protocol error; a cut stream is a transport loss. Plain JVM.
 */
class ExtractFrameReaderTest {

    private class Frame(val kind: String, val ordinal: Int, val detail: Any? = null)

    private open class Recording : ExtractFrameSink {
        val frames = ArrayList<Frame>()
        val bodies = HashMap<Int, java.io.ByteArrayOutputStream>()
        val paths = HashMap<Int, String>()
        val kinds = HashMap<Int, Int>()
        val declared = HashMap<Int, Long>()
        val warnings = HashMap<Int, String>()

        override fun begin(ordinal: Int, declaredBytes: Long, kind: Int, rawPath: ByteArray) {
            frames += Frame("begin", ordinal)
            paths[ordinal] = String(rawPath, Charsets.UTF_8)
            kinds[ordinal] = kind
            declared[ordinal] = declaredBytes
            bodies[ordinal] = java.io.ByteArrayOutputStream()
        }

        override fun data(ordinal: Int, buffer: ByteArray, offset: Int, length: Int) {
            frames += Frame("data", ordinal, length)
            bodies.getValue(ordinal).write(buffer, offset, length)
        }

        override fun end(ordinal: Int, bytes: Long, warning: String?) {
            frames += Frame("end", ordinal, bytes)
            if (warning != null) warnings[ordinal] = warning
        }

        override fun fail(ordinal: Int, kind: Int, message: String) {
            frames += Frame("fail", ordinal, kind to message)
        }
    }

    private fun read(bytes: ByteArray, planned: (Int) -> Boolean = { true }, total: Long = Long.MAX_VALUE, sink: Recording = Recording()): Pair<ExtractStreamEnd, Recording> =
        ExtractFrameReader(planned, total).read(ByteArrayInputStream(bytes), sink) to sink

    private fun protocolError(bytes: ByteArray, planned: (Int) -> Boolean = { true }, total: Long = Long.MAX_VALUE): ExtractProtocolException =
        assertThrows(ExtractProtocolException::class.java) { read(bytes, planned, total) }

    private fun fixture(vararg relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            relative.forEach { path -> dir?.let { File(it, path) }?.takeIf { it.isFile }?.let { return it } }
            dir = dir?.parentFile
        }
        error("none of ${relative.toList()} found from ${System.getProperty("user.dir")}")
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `the golden tree fzx decodes to 51 entries whose file bytes equal the same files in tree zip`() {
        val golden = fixture("app/src/test/resources/fixtures/archives/tree.fzx", "src/test/resources/fixtures/archives/tree.fzx").readBytes()
        val (end, sink) = read(golden)
        val begins = sink.frames.filter { it.kind == "begin" }.map { it.ordinal }
        assertEquals(51, begins.size)
        assertEquals("ordinals are 0..50 in order", (0..50).toList(), begins)
        val byKind = sink.kinds.values.groupingBy { it }.eachCount()
        assertEquals(40, byKind[ExtractFrameReader.KIND_FILE])
        assertEquals(9, byKind[ExtractFrameReader.KIND_DIRECTORY])
        assertEquals(1, byKind[ExtractFrameReader.KIND_SYMLINK])
        assertEquals(1, byKind[ExtractFrameReader.KIND_HARDLINK])
        assertTrue(sink.frames.none { it.kind == "fail" })
        val totalBytes = sink.kinds.filterValues { it == ExtractFrameReader.KIND_FILE }.keys.sumOf { sink.bodies.getValue(it).size().toLong() }
        assertEquals(ExtractStreamEnd.Done(entries = 51, bytes = totalBytes, failed = 0), end)
        // Every file's bytes are the fixture's: tree.zip holds the same 40 files (same generator, same bodies).
        ZipFile(fixture("core/fixtures/archives/tree.zip")).use { zip ->
            var compared = 0
            sink.kinds.filterValues { it == ExtractFrameReader.KIND_FILE }.keys.forEach { ordinal ->
                val path = sink.paths.getValue(ordinal)
                val entry = requireNotNull(zip.getEntry(path)) { "tree.zip has no $path" }
                val expected = zip.getInputStream(entry).use { it.readBytes() }
                val actual = sink.bodies.getValue(ordinal).toByteArray()
                assertEquals(path, expected.size.toLong(), sink.declared.getValue(ordinal))
                assertEquals(path, sha256(expected), sha256(actual))
                compared += 1
            }
            assertEquals(40, compared)
        }
        // Each END's byte count is the body it closed; a directory or link ends with zero.
        sink.frames.filter { it.kind == "end" }.forEach { frame ->
            assertEquals("entry ${frame.ordinal}", sink.bodies.getValue(frame.ordinal).size().toLong(), frame.detail as Long)
        }
        assertTrue(sink.warnings.isEmpty())
    }

    @Test
    fun `every frame kind decodes, including a stand-alone FAIL and an END with a warning`() {
        val body = ByteArray(3000) { it.toByte() }
        val bytes = ExtractFrameTestWriter.bytes {
            begin(0, 3000L, ExtractFrameReader.KIND_FILE, "a.bin")
            data(0, body, chunk = 1024)
            end(0, 3000L, warning = "Pathname cannot be converted")
            begin(1, 0L, ExtractFrameReader.KIND_DIRECTORY, "d/")
            end(1, 0L)
            fail(2, ExtractFrameReader.FAIL_DECODE, "header unreadable")
            begin(3, 10L, ExtractFrameReader.KIND_FILE, "b.bin")
            data(3, ByteArray(4))
            fail(3, ExtractFrameReader.FAIL_CRC, "ZIP bad CRC")
            begin(4, ExtractFrameReader.DECLARED_UNKNOWN, ExtractFrameReader.KIND_SYMLINK, "l")
            end(4, 0L)
            done(3, 3000L, 2)
        }
        val (end, sink) = read(bytes)
        assertEquals(ExtractStreamEnd.Done(3, 3000L, 2), end)
        assertEquals(listOf("begin", "data", "data", "data", "end", "begin", "end", "fail", "begin", "data", "fail", "begin", "end"), sink.frames.map { it.kind })
        assertEquals(listOf(1024, 1024, 952), sink.frames.filter { it.kind == "data" && it.ordinal == 0 }.map { it.detail })
        assertEquals(body.toList(), sink.bodies.getValue(0).toByteArray().toList())
        assertEquals("Pathname cannot be converted", sink.warnings[0])
        assertEquals(ExtractFrameReader.FAIL_DECODE to "header unreadable", sink.frames.first { it.kind == "fail" && it.ordinal == 2 }.detail)
        assertEquals(ExtractFrameReader.FAIL_CRC to "ZIP bad CRC", sink.frames.first { it.kind == "fail" && it.ordinal == 3 }.detail)
        assertEquals("d/", sink.paths[1])
        assertEquals(ExtractFrameReader.DECLARED_UNKNOWN, sink.declared[4])
    }

    @Test
    fun `an empty stream is Empty, an ABORT is Aborted with its message, and a missing terminal frame is a transport loss`() {
        assertEquals(ExtractStreamEnd.Empty, read(ByteArray(0)).first)
        val aborted = ExtractFrameTestWriter.bytes { begin(0, 5L, ExtractFrameReader.KIND_FILE, "a"); data(0, ByteArray(2)); abort("Truncated input") }
        assertEquals(ExtractStreamEnd.Aborted("Truncated input"), read(aborted).first)
        val abortedFirst = ExtractFrameTestWriter.bytes { abort("bad header") }
        assertEquals(ExtractStreamEnd.Aborted("bad header"), read(abortedFirst).first)
        val noTerminal = ExtractFrameTestWriter.bytes { begin(0, 5L, ExtractFrameReader.KIND_FILE, "a"); data(0, ByteArray(5)); end(0, 5L) }
        assertEquals(ExtractStreamEnd.TransportLoss, read(noTerminal).first)
        val magicOnly = ExtractFrameTestWriter.bytes { magicOnly() }
        assertEquals(ExtractStreamEnd.TransportLoss, read(magicOnly).first)
    }

    @Test
    fun `a stream cut inside a frame is a transport loss, whatever frame it was`() {
        val whole = ExtractFrameTestWriter.bytes {
            begin(0, 5L, ExtractFrameReader.KIND_FILE, "abc.txt")
            data(0, ByteArray(5))
            end(0, 5L)
            done(1, 5L, 0)
        }
        // Every cut after the magic and before the last byte: never an exception, always TransportLoss.
        for (cut in 5 until whole.size) {
            val (end, _) = read(whole.copyOf(cut))
            assertEquals("cut at $cut", ExtractStreamEnd.TransportLoss, end)
        }
    }

    @Test
    fun `a stream that does not start with the magic, or is cut inside it, is a protocol error`() {
        assertTrue(protocolError("FZL1".toByteArray()).message!!.contains("magic"))
        assertTrue(protocolError("FZ".toByteArray()).message!!.contains("magic"))
    }

    @Test
    fun `an ordinal outside the plan, out of order, or repeated is a protocol error`() {
        val notPlanned = ExtractFrameTestWriter.bytes { begin(7, 0L, ExtractFrameReader.KIND_FILE, "x"); end(7, 0L); done(1, 0L, 0) }
        assertTrue(protocolError(notPlanned, planned = { it != 7 }).message!!.contains("not in the plan"))
        val backwards = ExtractFrameTestWriter.bytes { begin(3, 0L, ExtractFrameReader.KIND_FILE, "x"); end(3, 0L); begin(2, 0L, ExtractFrameReader.KIND_FILE, "y"); end(2, 0L); done(2, 0L, 0) }
        assertTrue(protocolError(backwards).message!!.contains("not increasing"))
        val repeated = ExtractFrameTestWriter.bytes { begin(3, 0L, ExtractFrameReader.KIND_FILE, "x"); end(3, 0L); begin(3, 0L, ExtractFrameReader.KIND_FILE, "x"); end(3, 0L); done(2, 0L, 0) }
        assertTrue(protocolError(repeated).message!!.contains("not increasing"))
        val negative = ExtractFrameTestWriter.bytes { begin(-1, 0L, ExtractFrameReader.KIND_FILE, "x") }
        assertTrue(protocolError(negative).message!!.contains("negative"))
        val standAloneFailBackwards = ExtractFrameTestWriter.bytes { begin(5, 0L, ExtractFrameReader.KIND_FILE, "x"); end(5, 0L); fail(4, ExtractFrameReader.FAIL_OTHER, "late") }
        assertTrue(protocolError(standAloneFailBackwards).message!!.contains("not increasing"))
    }

    @Test
    fun `frames in the wrong place are protocol errors`() {
        val beginWhileOpen = ExtractFrameTestWriter.bytes { begin(0, 1L, ExtractFrameReader.KIND_FILE, "a"); begin(1, 1L, ExtractFrameReader.KIND_FILE, "b") }
        assertTrue(protocolError(beginWhileOpen).message!!.contains("while 0 is open"))
        val dataOutside = ExtractFrameTestWriter.bytes { begin(0, 1L, ExtractFrameReader.KIND_FILE, "a"); end(0, 0L); data(0, ByteArray(1)) }
        assertTrue(protocolError(dataOutside).message!!.contains("outside its entry"))
        val dataOther = ExtractFrameTestWriter.bytes { begin(0, 4L, ExtractFrameReader.KIND_FILE, "a"); data(1, ByteArray(1)) }
        assertTrue(protocolError(dataOther).message!!.contains("outside its entry"))
        val endOther = ExtractFrameTestWriter.bytes { begin(0, 4L, ExtractFrameReader.KIND_FILE, "a"); end(1, 0L) }
        assertTrue(protocolError(endOther).message!!.contains("outside its entry"))
        val failOther = ExtractFrameTestWriter.bytes { begin(0, 4L, ExtractFrameReader.KIND_FILE, "a"); fail(1, ExtractFrameReader.FAIL_CRC, "x") }
        assertTrue(protocolError(failOther).message!!.contains("while 0 is open"))
        val doneWhileOpen = ExtractFrameTestWriter.bytes { begin(0, 4L, ExtractFrameReader.KIND_FILE, "a"); done(0, 0L, 0) }
        assertTrue(protocolError(doneWhileOpen).message!!.contains("DONE while 0 is open"))
        val afterDone = ExtractFrameTestWriter.bytes { done(0, 0L, 0); raw(byteArrayOf(0)) }
        assertTrue(protocolError(afterDone).message!!.contains("after DONE"))
        val afterAbort = ExtractFrameTestWriter.bytes { abort("x"); raw(byteArrayOf(0)) }
        assertTrue(protocolError(afterAbort).message!!.contains("after ABORT"))
        val badTag = ExtractFrameTestWriter.bytes { raw(byteArrayOf(0x09)) }
        assertTrue(protocolError(badTag).message!!.contains("unknown"))
    }

    @Test
    fun `every size bound is enforced before a byte is acted on`() {
        val zeroLength = ExtractFrameTestWriter.bytes { begin(0, 4L, ExtractFrameReader.KIND_FILE, "a"); rawData(0, 0, ByteArray(0)) }
        assertTrue(protocolError(zeroLength).message!!.contains("of 0 bytes"))
        val huge = ExtractFrameTestWriter.bytes { begin(0, 4L, ExtractFrameReader.KIND_FILE, "a"); rawData(0, ExtractFrameReader.MAX_DATA_FRAME_BYTES.toInt() + 1, ByteArray(0)) }
        assertTrue(protocolError(huge).message!!.contains("bytes"))
        val hugeUnsigned = ExtractFrameTestWriter.bytes { begin(0, 4L, ExtractFrameReader.KIND_FILE, "a"); rawData(0, -1, ByteArray(0)) }
        assertTrue(protocolError(hugeUnsigned).message!!.contains("bytes"))
        val pastDeclared = ExtractFrameTestWriter.bytes { begin(0, 4L, ExtractFrameReader.KIND_FILE, "a"); data(0, ByteArray(5)) }
        assertTrue(protocolError(pastDeclared).message!!.contains("past its declared"))
        val pastPlan = ExtractFrameTestWriter.bytes { begin(0, 100L, ExtractFrameReader.KIND_FILE, "a"); data(0, ByteArray(60)); data(0, ByteArray(30)) }
        assertTrue(protocolError(pastPlan, total = 80L).message!!.contains("past the plan"))
        val unknownDeclaredIsFine = ExtractFrameTestWriter.bytes { begin(0, -1L, ExtractFrameReader.KIND_FILE, "a"); data(0, ByteArray(5)); end(0, 5L); done(1, 5L, 0) }
        assertEquals(ExtractStreamEnd.Done(1, 5L, 0), read(unknownDeclaredIsFine).first)
        val badDeclared = ExtractFrameTestWriter.bytes { begin(0, -2L, ExtractFrameReader.KIND_FILE, "a") }
        assertTrue(protocolError(badDeclared).message!!.contains("declares"))
        val badKind = ExtractFrameTestWriter.bytes { begin(0, 0L, 9, "a") }
        assertTrue(protocolError(badKind).message!!.contains("kind 9"))
        val badWarn = ExtractFrameTestWriter.bytes { begin(0, 0L, ExtractFrameReader.KIND_FILE, "a"); raw(byteArrayOf(0x03, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 7, 0, 0)) }
        assertTrue(protocolError(badWarn).message!!.contains("warn 7"))
        val badFailKind = ExtractFrameTestWriter.bytes { fail(0, 9, "x") }
        assertTrue(protocolError(badFailKind).message!!.contains("kind 9"))
        // A path length above the cap is refused from its header alone: nothing is allocated for it.
        val longPath = ExtractFrameTestWriter.bytes { raw(byteArrayOf(0x01, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 1, 0)) }
        assertTrue(protocolError(longPath).message!!.contains("exceeds"))
        // Exactly the cap is fine.
        val atCap = ExtractFrameTestWriter.bytes { begin(0, 0L, ExtractFrameReader.KIND_FILE, ByteArray(ExtractFrameReader.MAX_PATH_BYTES) { 'a'.code.toByte() }); end(0, 0L); done(1, 0L, 0) }
        assertEquals(ExtractStreamEnd.Done(1, 0L, 0), read(atCap).first)
    }

    @Test
    fun `a data frame is handed to the sink in slices no larger than its copy buffer, in order`() {
        val body = ByteArray(200 * 1024) { (it * 7).toByte() }
        val bytes = ExtractFrameTestWriter.bytes { begin(0, body.size.toLong(), ExtractFrameReader.KIND_FILE, "big"); data(0, body); end(0, body.size.toLong()); done(1, body.size.toLong(), 0) }
        val (_, sink) = read(bytes)
        val slices = sink.frames.filter { it.kind == "data" }.map { it.detail as Int }
        assertTrue(slices.all { it in 1..(64 * 1024) })
        assertEquals(body.size, slices.sum())
        assertEquals(body.toList(), sink.bodies.getValue(0).toByteArray().toList())
    }

    @Test
    fun `an exception from the sink ends the read and propagates`() {
        val bytes = ExtractFrameTestWriter.bytes { begin(0, 1L, ExtractFrameReader.KIND_FILE, "a"); data(0, ByteArray(1)); end(0, 1L); done(1, 1L, 0) }
        val sink = object : Recording() {
            override fun data(ordinal: Int, buffer: ByteArray, offset: Int, length: Int) = throw IOException("disk full")
        }
        val thrown = assertThrows(IOException::class.java) { read(bytes, sink = sink) }
        assertEquals("disk full", thrown.message)
        assertEquals(listOf("begin"), sink.frames.map { it.kind })
    }
}
