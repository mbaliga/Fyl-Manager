package io.github.mbaliga.fylz.preview

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class UniversalInspection(
    val sampledBytes: Int,
    val truncated: Boolean,
    val sha256OfSample: String,
    val signatureHex: String,
    val detectedSignature: String?,
    val printableStrings: List<String>,
    val hexLines: List<String>,
    val entropyBitsPerByte: Double,
    val probableText: Boolean,
)

class UniversalFileInspector(private val context: Context) {
    suspend fun inspect(uri: Uri, maxBytes: Int = DEFAULT_MAX_BYTES): UniversalInspection =
        withContext(Dispatchers.IO) {
            require(maxBytes in 1..MAX_BYTES)
            val bytes = readBounded(uri, maxBytes)
            val strings = extractStrings(bytes.data)
            UniversalInspection(
                sampledBytes = bytes.data.size,
                truncated = bytes.truncated,
                sha256OfSample = MessageDigest.getInstance("SHA-256").digest(bytes.data).toHex(),
                signatureHex = bytes.data.take(32).toByteArray().toHex(" "),
                detectedSignature = detectSignature(bytes.data),
                printableStrings = strings.take(MAX_STRINGS),
                hexLines = hexDump(bytes.data, MAX_HEX_LINES),
                entropyBitsPerByte = entropy(bytes.data),
                probableText = probableText(bytes.data),
            )
        }

    private suspend fun readBounded(uri: Uri, maxBytes: Int): BoundedBytes {
        val input = context.contentResolver.openInputStream(uri) ?: error("Unable to read this file.")
        input.use { stream ->
            val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            var truncated = false
            while (true) {
                coroutineContext.ensureActive()
                val count = stream.read(buffer)
                if (count < 0) break
                val remaining = maxBytes - total
                if (remaining <= 0) {
                    truncated = true
                    break
                }
                val accepted = minOf(count, remaining)
                output.write(buffer, 0, accepted)
                total += accepted
                if (accepted < count) {
                    truncated = true
                    break
                }
            }
            if (!truncated && stream.read() >= 0) truncated = true
            return BoundedBytes(output.toByteArray(), truncated)
        }
    }

    private fun probableText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        var printable = 0
        var zeroes = 0
        bytes.forEach { value ->
            val unsigned = value.toInt() and 0xff
            if (unsigned == 0) zeroes += 1
            if (unsigned == 9 || unsigned == 10 || unsigned == 13 || unsigned in 32..126 || unsigned >= 0xc2) {
                printable += 1
            }
        }
        return zeroes == 0 && printable.toDouble() / bytes.size >= 0.85
    }

    private fun extractStrings(bytes: ByteArray): List<String> {
        val output = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.length >= MIN_STRING_LENGTH) output += current.toString()
            current.clear()
        }
        bytes.forEach { value ->
            val unsigned = value.toInt() and 0xff
            if (unsigned in 32..126 || unsigned == 9) current.append(unsigned.toChar()) else flush()
            if (output.size >= MAX_STRINGS) return output
        }
        flush()
        return output
    }

    private fun hexDump(bytes: ByteArray, maxLines: Int): List<String> = buildList {
        bytes.asList().chunked(16).take(maxLines).forEachIndexed { index, row ->
            val offset = "%08x".format(index * 16)
            val hex = row.joinToString(" ") { "%02x".format(it.toInt() and 0xff) }.padEnd(47)
            val ascii = row.joinToString("") {
                val value = it.toInt() and 0xff
                if (value in 32..126) value.toChar().toString() else "."
            }
            add("$offset  $hex  |$ascii|")
        }
    }

    private fun entropy(bytes: ByteArray): Double {
        if (bytes.isEmpty()) return 0.0
        val counts = IntArray(256)
        bytes.forEach { counts[it.toInt() and 0xff] += 1 }
        return counts.filter { it > 0 }.sumOf { count ->
            val probability = count.toDouble() / bytes.size
            -probability * (kotlin.math.ln(probability) / kotlin.math.ln(2.0))
        }
    }

    private fun detectSignature(bytes: ByteArray): String? {
        fun starts(vararg values: Int): Boolean = values.indices.all { index ->
            index < bytes.size && (bytes[index].toInt() and 0xff) == values[index]
        }
        return when {
            starts(0x50, 0x4b, 0x03, 0x04) -> "ZIP-compatible container"
            starts(0x25, 0x50, 0x44, 0x46) -> "PDF document"
            starts(0x89, 0x50, 0x4e, 0x47) -> "PNG image"
            starts(0xff, 0xd8, 0xff) -> "JPEG image"
            starts(0x47, 0x49, 0x46, 0x38) -> "GIF image"
            starts(0x7f, 0x45, 0x4c, 0x46) -> "ELF executable"
            starts(0x4d, 0x5a) -> "DOS/Windows executable"
            starts(0x53, 0x51, 0x4c, 0x69, 0x74, 0x65, 0x20, 0x66) -> "SQLite database"
            starts(0x67, 0x6c, 0x54, 0x46) -> "Binary glTF scene"
            starts(0x52, 0x49, 0x46, 0x46) -> "RIFF container"
            starts(0x1f, 0x8b) -> "GZIP stream"
            starts(0x37, 0x7a, 0xbc, 0xaf, 0x27, 0x1c) -> "7-Zip archive"
            starts(0x52, 0x61, 0x72, 0x21) -> "RAR archive"
            bytes.take(6).toByteArray().toString(Charsets.US_ASCII) == "solid " -> "ASCII STL mesh"
            else -> null
        }
    }

    private fun ByteArray.toHex(separator: String = ""): String =
        joinToString(separator) { "%02x".format(it.toInt() and 0xff) }

    private data class BoundedBytes(val data: ByteArray, val truncated: Boolean)

    companion object {
        const val DEFAULT_MAX_BYTES = 512 * 1024
        const val MAX_BYTES = 4 * 1024 * 1024
        private const val MAX_STRINGS = 200
        private const val MAX_HEX_LINES = 256
        private const val MIN_STRING_LENGTH = 5
    }
}
