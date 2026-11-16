package io.github.mbaliga.fylz.openwith

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.github.mbaliga.fylz.formats.ByteArraySource
import io.github.mbaliga.fylz.formats.ByteSource
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Random-access bytes for a `content://` or `file://` URI, so format inspectors can read a file's
 * header and tail without loading it.
 *
 * A seekable descriptor (the normal case: a file on storage) is read in place with positional
 * reads, so inspecting a 50 GB file costs what inspecting a 5 KB one does. A source that cannot
 * seek (a pipe, some cloud providers) is read once, up to [STREAM_LIMIT] bytes, into memory, which
 * is all an inspector may use anyway ([io.github.mbaliga.fylz.formats.FormatInspector.MAX_READ_BYTES]).
 */
class ContentByteSource private constructor(
    private val descriptor: ParcelFileDescriptor?,
    private val channel: FileChannel?,
    private val memory: ByteSource?,
    override val size: Long,
) : ByteSource, Closeable {

    override fun read(offset: Long, length: Int): ByteArray {
        memory?.let { return it.read(offset, length) }
        val ch = channel ?: return ByteArray(0)
        if (offset < 0 || length <= 0 || (size >= 0 && offset >= size)) return ByteArray(0)
        val wanted = if (size >= 0) minOf(length.toLong(), size - offset).toInt() else length
        val buffer = ByteBuffer.allocate(wanted)
        return try {
            var position = offset
            while (buffer.hasRemaining()) {
                val n = ch.read(buffer, position)
                if (n <= 0) break
                position += n
            }
            buffer.array().copyOf(buffer.position())
        } catch (_: java.io.IOException) {
            ByteArray(0)
        }
    }

    override fun close() {
        runCatching { channel?.close() }
        runCatching { descriptor?.close() }
    }

    companion object {
        const val STREAM_LIMIT = 8 * 1024 * 1024

        /** Opens [uri] for reading, or null when the provider refuses. Never throws. */
        fun open(context: Context, uri: Uri): ContentByteSource? = runCatching {
            val resolver = context.contentResolver
            val pfd = resolver.openFileDescriptor(uri, "r")
            if (pfd != null && pfd.statSize >= 0) {
                val channel = FileInputStream(pfd.fileDescriptor).channel
                // Probe: a pipe reports a size of -1 or fails on a positional read.
                val seekable = runCatching { channel.read(ByteBuffer.allocate(1), 0L); true }.getOrDefault(false)
                if (seekable) return@runCatching ContentByteSource(pfd, channel, null, pfd.statSize)
                runCatching { channel.close() }
            }
            runCatching { pfd?.close() }
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                while (out.size() < STREAM_LIMIT) {
                    val n = input.read(chunk, 0, minOf(chunk.size, STREAM_LIMIT - out.size()))
                    if (n < 0) break
                    out.write(chunk, 0, n)
                }
                out.toByteArray()
            } ?: return@runCatching null
            ContentByteSource(null, null, ByteArraySource(bytes), bytes.size.toLong())
        }.getOrNull()
    }
}
