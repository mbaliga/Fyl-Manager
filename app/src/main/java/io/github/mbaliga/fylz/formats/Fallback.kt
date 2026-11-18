package io.github.mbaliga.fylz.formats

import kotlin.math.ln

/**
 * What every file gets, however unknown: a hex dump, its printable strings and an entropy figure.
 *
 * This is the floor of "open every file type": a file Fylz has never heard of still opens, and
 * shows enough to tell what it probably is (high entropy means compressed or encrypted; long
 * readable strings hint at a text-bearing container; a recognisable header is visible in the dump).
 */
object Fallback {

    /**
     * A classic hex dump, 16 bytes per line: `00000000  89 50 4e 47 ...  |.PNG....|`.
     * [baseOffset] labels the first byte, so a dump of the middle of a file reads correctly.
     */
    fun hexDump(bytes: ByteArray, baseOffset: Long = 0, bytesPerLine: Int = 16, maxBytes: Int = 4096): String {
        require(bytesPerLine in 4..64) { "bytesPerLine out of range" }
        val n = minOf(bytes.size, maxBytes.coerceAtLeast(0))
        val sb = StringBuilder()
        var i = 0
        while (i < n) {
            sb.append("%08x  ".format(java.util.Locale.ROOT, baseOffset + i))
            for (j in 0 until bytesPerLine) {
                if (i + j < n) sb.append("%02x ".format(java.util.Locale.ROOT, bytes[i + j].toInt() and 0xFF)) else sb.append("   ")
                if (j == bytesPerLine / 2 - 1) sb.append(' ')
            }
            sb.append(" |")
            for (j in 0 until bytesPerLine) {
                if (i + j >= n) break
                val c = bytes[i + j].toInt() and 0xFF
                sb.append(if (c in 0x20..0x7E) c.toChar() else '.')
            }
            sb.append("|\n")
            i += bytesPerLine
        }
        return sb.toString()
    }

    /** A run of printable text found in binary data. */
    data class FoundString(val offset: Long, val text: String)

    /**
     * Printable ASCII strings of at least [minLength] characters (like the Unix `strings` tool),
     * and UTF-16LE strings of the same length, in order of appearance. Stops after [limit] strings.
     */
    fun strings(bytes: ByteArray, baseOffset: Long = 0, minLength: Int = 4, limit: Int = 200): List<FoundString> {
        require(minLength >= 2) { "minLength must be at least 2" }
        val out = mutableListOf<FoundString>()
        // ASCII runs.
        var start = -1
        for (i in 0..bytes.size) {
            val printable = i < bytes.size && isPrintable(bytes[i].toInt() and 0xFF)
            if (printable) { if (start < 0) start = i } else if (start >= 0) {
                if (i - start >= minLength) {
                    out += FoundString(baseOffset + start, String(bytes, start, i - start, Charsets.ISO_8859_1))
                    if (out.size >= limit) return out.sortedBy { it.offset }
                }
                start = -1
            }
        }
        // UTF-16LE runs: printable low byte followed by a zero high byte.
        start = -1
        var i = 0
        while (i <= bytes.size - 1) {
            val ok = i + 1 < bytes.size && isPrintable(bytes[i].toInt() and 0xFF) && bytes[i + 1].toInt() == 0
            if (ok) { if (start < 0) start = i; i += 2 } else {
                if (start >= 0 && (i - start) / 2 >= minLength) {
                    val chars = (i - start) / 2
                    val sb = StringBuilder(chars)
                    for (k in 0 until chars) sb.append((bytes[start + 2 * k].toInt() and 0xFF).toChar())
                    out += FoundString(baseOffset + start, sb.toString())
                    if (out.size >= limit) return out.sortedBy { it.offset }
                }
                start = -1
                i += if (ok) 2 else 1
            }
        }
        return out.sortedBy { it.offset }
    }

    private fun isPrintable(c: Int) = c in 0x20..0x7E

    /** Shannon entropy in bits per byte, 0.0..8.0. Empty input is 0. */
    fun entropy(bytes: ByteArray): Double {
        if (bytes.isEmpty()) return 0.0
        val counts = IntArray(256)
        for (b in bytes) counts[b.toInt() and 0xFF]++
        var h = 0.0
        val n = bytes.size.toDouble()
        for (c in counts) if (c > 0) { val p = c / n; h -= p * (ln(p) / ln(2.0)) }
        return h
    }

    /** A plain-language reading of [entropy], for display. */
    fun describeEntropy(bitsPerByte: Double): String = when {
        bitsPerByte < 1.0 -> "almost empty or highly repetitive"
        bitsPerByte < 4.5 -> "structured data or text"
        bitsPerByte < 7.0 -> "mixed or lightly compressed content"
        bitsPerByte < 7.9 -> "compressed content"
        else -> "compressed or encrypted (looks random)"
    }
}
