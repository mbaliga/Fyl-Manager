package io.github.mbaliga.fylz.archive

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The test-side writer of the FZX1 extraction frame codec (`fylz-ffi-android/src/frames.rs` is the
 * real one; `ExtractFrameReader` reads both), byte for byte the same layout so a stream a test
 * writes here is what the engine would write. Little-endian; the magic goes out with the first
 * frame, as the engine's does.
 */
class ExtractFrameTestWriter(private val out: OutputStream) {
    private var started = false

    private fun frame(size: Int, fill: ByteBuffer.() -> Unit) {
        if (!started) {
            out.write(MAGIC)
            started = true
        }
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.fill()
        out.write(buffer.array(), 0, buffer.position())
    }

    fun begin(ordinal: Int, declared: Long, kind: Int, rawPath: ByteArray) = frame(1 + 4 + 8 + 1 + 4 + rawPath.size) {
        put(ExtractFrameReader.TAG_BEGIN.toByte()); putInt(ordinal); putLong(declared); put(kind.toByte()); putInt(rawPath.size); put(rawPath)
    }

    fun begin(ordinal: Int, declared: Long, kind: Int, path: String) = begin(ordinal, declared, kind, path.toByteArray(Charsets.UTF_8))

    /** One or more `DATA` frames of at most [chunk] bytes each. */
    fun data(ordinal: Int, bytes: ByteArray, chunk: Int = ExtractFrameReader.MAX_DATA_FRAME_BYTES.toInt()) {
        var at = 0
        while (at < bytes.size) {
            val n = minOf(chunk, bytes.size - at)
            frame(1 + 4 + 4 + n) { put(ExtractFrameReader.TAG_DATA.toByte()); putInt(ordinal); putInt(n); put(bytes, at, n) }
            at += n
        }
    }

    /** A raw `DATA` frame with the given length field and payload, for bounds tests. */
    fun rawData(ordinal: Int, declaredLength: Int, payload: ByteArray) = frame(1 + 4 + 4 + payload.size) {
        put(ExtractFrameReader.TAG_DATA.toByte()); putInt(ordinal); putInt(declaredLength); put(payload)
    }

    fun end(ordinal: Int, bytes: Long, warning: String? = null) {
        val message = warning?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        frame(1 + 4 + 8 + 1 + 2 + message.size) {
            put(ExtractFrameReader.TAG_END.toByte()); putInt(ordinal); putLong(bytes)
            put((if (warning == null) ExtractFrameReader.WARN_NONE else ExtractFrameReader.WARN_OTHER).toByte())
            putShort(message.size.toShort()); put(message)
        }
    }

    fun fail(ordinal: Int, kind: Int, message: String) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        frame(1 + 4 + 1 + 2 + bytes.size) { put(ExtractFrameReader.TAG_FAIL.toByte()); putInt(ordinal); put(kind.toByte()); putShort(bytes.size.toShort()); put(bytes) }
    }

    fun done(entries: Int, bytes: Long, failed: Int) = frame(1 + 4 + 8 + 4) {
        put(ExtractFrameReader.TAG_DONE.toByte()); putInt(entries); putLong(bytes); putInt(failed)
    }

    fun abort(message: String) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        frame(1 + 2 + bytes.size) { put(ExtractFrameReader.TAG_ABORT.toByte()); putShort(bytes.size.toShort()); put(bytes) }
    }

    /** Writes the magic alone (for a stream that must start with it and then be cut). */
    fun magicOnly() {
        if (!started) {
            out.write(MAGIC)
            started = true
        }
    }

    /** Arbitrary bytes after whatever was written (a bad tag, trailing garbage). */
    fun raw(bytes: ByteArray) {
        if (!started) {
            out.write(MAGIC)
            started = true
        }
        out.write(bytes)
    }

    companion object {
        val MAGIC: ByteArray = "FZX1".toByteArray(Charsets.US_ASCII)

        /** A complete stream built by [build] in memory. */
        fun bytes(build: ExtractFrameTestWriter.() -> Unit): ByteArray {
            val out = ByteArrayOutputStream()
            ExtractFrameTestWriter(out).build()
            return out.toByteArray()
        }
    }
}
