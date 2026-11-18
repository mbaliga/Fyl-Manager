package io.github.mbaliga.fylz.formats

/**
 * Reads a file's structure and reports what it finds, without rendering it.
 *
 * CONTRACT for every implementation:
 * - **Pure Kotlin/JVM.** No `android.*`.
 * - **Never throws.** Malformed, truncated or hostile input returns `null` (or a result with
 *   [InspectionResult.warnings]). Catch `Throwable`-level surprises you cannot rule out.
 * - **Bounded.** Read at most [MAX_READ_BYTES] in total, in chunks via [ByteSource.read]; never
 *   allocate from an untrusted length field without clamping it first; cap loops by iteration
 *   count. A 50 GB file must inspect as fast as a 5 KB one.
 * - **Honest.** Report only what was actually parsed. Say "unknown" rather than guess.
 */
interface FormatInspector {
    /** The [FormatSpec.id]s this inspector understands. */
    val formatIds: Set<String>

    fun inspect(source: ByteSource, fileName: String?): InspectionResult?

    companion object {
        const val MAX_READ_BYTES = 8 * 1024 * 1024
        const val MAX_TEXT_CHARS = 64 * 1024
    }
}

/** Little helpers inspectors share. All are total: out-of-range reads return 0 / empty. */
object Bytes {
    fun u8(b: ByteArray, o: Int): Int = if (o in b.indices) b[o].toInt() and 0xFF else 0
    fun u16le(b: ByteArray, o: Int): Int = u8(b, o) or (u8(b, o + 1) shl 8)
    fun u16be(b: ByteArray, o: Int): Int = (u8(b, o) shl 8) or u8(b, o + 1)
    fun u32le(b: ByteArray, o: Int): Long =
        (u8(b, o).toLong()) or (u8(b, o + 1).toLong() shl 8) or (u8(b, o + 2).toLong() shl 16) or (u8(b, o + 3).toLong() shl 24)
    fun u32be(b: ByteArray, o: Int): Long =
        (u8(b, o).toLong() shl 24) or (u8(b, o + 1).toLong() shl 16) or (u8(b, o + 2).toLong() shl 8) or u8(b, o + 3).toLong()
    fun u64le(b: ByteArray, o: Int): Long = u32le(b, o) or (u32le(b, o + 4) shl 32)
    fun u64be(b: ByteArray, o: Int): Long = (u32be(b, o) shl 32) or u32be(b, o + 4)

    /** ASCII/Latin-1 text of [length] bytes, NULs and non-printables replaced by '.' */
    fun ascii(b: ByteArray, o: Int, length: Int): String {
        val sb = StringBuilder()
        for (i in o until minOf(b.size, o + length)) {
            val c = b[i].toInt() and 0xFF
            sb.append(if (c in 0x20..0x7E) c.toChar() else '.')
        }
        return sb.toString()
    }

    fun startsWith(b: ByteArray, prefix: String, offset: Int = 0): Boolean {
        if (offset + prefix.length > b.size) return false
        for (i in prefix.indices) if ((b[offset + i].toInt() and 0xFF) != prefix[i].code) return false
        return true
    }

    /** Human size: 1536 -> "1.5 KiB". */
    fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KiB", "MiB", "GiB", "TiB", "PiB")
        var v = bytes.toDouble(); var u = -1
        do { v /= 1024.0; u++ } while (v >= 1024 && u < units.lastIndex)
        return "%.1f %s".format(java.util.Locale.ROOT, v, units[u])
    }
}
