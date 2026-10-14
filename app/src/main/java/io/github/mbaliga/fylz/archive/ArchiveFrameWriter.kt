package io.github.mbaliga.fylz.archive

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The writer half of the create frame codec (`docs/agent/DESIGN-M35-CREATE.md` section 2.5;
 * `fylz-archive/src/write.rs` is the reader, the `FZW1` mirror of how [ExtractFrameReader] reads
 * what `fylz-ffi-android/src/frames.rs` writes). One instance per create pass, driven by
 * `ArchiveCreator`'s own feeder: [entry] for each manifest row in order, [data] zero or more times
 * for a file entry (chunked to at most [MAX_DATA_FRAME_BYTES] itself, so a caller may hand a whole
 * buffer larger than that), [end] once per file entry started, [finish] when every entry is done,
 * or [abort] instead of [finish] when the operation is cancelled or a source could not be read.
 * Writes the magic on the first call; [started] tells a caller (whose own error path might need to
 * decide between [finish]/[abort] and doing nothing at all) whether any frame has gone out yet.
 *
 * Format, all integers little-endian (mirrors `write.rs`'s own module doc exactly):
 *
 * ```text
 * MAGIC  "FZW1"
 * ENTRY  0x01 ordinal:u32 kind:u8(1 file,2 dir) size:i64(-1 unknown) mtime:i64(epoch ms; 0 -> "now") mode:u32(masked 0o7777) path_len:u32(<=65536) utf8_path
 * DATA   0x02 ordinal:u32 len:u32(1..=1 MiB) bytes
 * END    0x03 ordinal:u32 bytes:u64
 * FINISH 0x04
 * ABORT  0x05 msg_len:u16 msg
 * ```
 */
class ArchiveFrameWriter(private val out: OutputStream) {
    var started: Boolean = false
        private set

    private fun start() {
        if (!started) {
            started = true
            out.write(MAGIC)
        }
    }

    fun entry(ordinal: Int, isDirectory: Boolean, sizeBytes: Long?, mtimeEpochMillis: Long, mode: Int, path: String) {
        start()
        val pathBytes = path.toByteArray(Charsets.UTF_8)
        require(pathBytes.size <= MAX_PATH_BYTES) { "entry path is longer than $MAX_PATH_BYTES bytes" }
        val header = ByteBuffer.allocate(4 + 1 + 8 + 8 + 4 + 4).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(ordinal)
        header.put((if (isDirectory) KIND_DIRECTORY else KIND_FILE).toByte())
        header.putLong(sizeBytes ?: SIZE_UNKNOWN)
        header.putLong(mtimeEpochMillis)
        header.putInt(mode and 0xFFF) // 0o7777: Kotlin has no octal literal syntax
        header.putInt(pathBytes.size)
        out.write(byteArrayOf(TAG_ENTRY.toByte()))
        out.write(header.array())
        out.write(pathBytes)
    }

    /** Splits [length] bytes from [buffer] starting at [offset] into frames of at most
     * [MAX_DATA_FRAME_BYTES] each -- a caller need not chunk its own reads to that bound. */
    fun data(ordinal: Int, buffer: ByteArray, offset: Int, length: Int) {
        var at = offset
        var remaining = length
        while (remaining > 0) {
            val chunk = minOf(remaining, MAX_DATA_FRAME_BYTES)
            val header = ByteBuffer.allocate(4 + 4).order(ByteOrder.LITTLE_ENDIAN)
            header.putInt(ordinal)
            header.putInt(chunk)
            out.write(byteArrayOf(TAG_DATA.toByte()))
            out.write(header.array())
            out.write(buffer, at, chunk)
            at += chunk
            remaining -= chunk
        }
    }

    fun end(ordinal: Int, bytesWritten: Long) {
        val header = ByteBuffer.allocate(4 + 8).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(ordinal)
        header.putLong(bytesWritten)
        out.write(byteArrayOf(TAG_END.toByte()))
        out.write(header.array())
    }

    /** Closes the archive normally; only valid when no entry is open. */
    fun finish() {
        start()
        out.write(byteArrayOf(TAG_FINISH.toByte()))
    }

    /** Cancels the write; only valid when no entry is open (the caller finishes an in-flight
     * entry with [end] first). The engine poisons its writer and reports `Cancelled`. */
    fun abort(message: String) {
        start()
        val messageBytes = message.toByteArray(Charsets.UTF_8).let { if (it.size > 65_535) it.copyOf(65_535) else it }
        val header = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(messageBytes.size.toShort())
        out.write(byteArrayOf(TAG_ABORT.toByte()))
        out.write(header.array())
        out.write(messageBytes)
    }

    companion object {
        private val MAGIC = "FZW1".toByteArray(Charsets.US_ASCII)

        const val TAG_ENTRY = 0x01
        const val TAG_DATA = 0x02
        const val TAG_END = 0x03
        const val TAG_FINISH = 0x04
        const val TAG_ABORT = 0x05

        const val KIND_FILE = 1
        const val KIND_DIRECTORY = 2

        const val SIZE_UNKNOWN = -1L
        const val MAX_PATH_BYTES = 64 * 1024
        const val MAX_DATA_FRAME_BYTES = 1024 * 1024
    }
}
