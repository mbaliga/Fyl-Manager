package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/** Shared helpers for the archive inspectors. All total and bounded. */
internal object ArcUtil {
    fun readAt(src: ByteSource, off: Long, len: Int): ByteArray = try {
        if (off < 0 || len <= 0) ByteArray(0) else src.read(off, minOf(len, FormatInspector.MAX_READ_BYTES))
    } catch (t: Throwable) { ByteArray(0) }

    fun clampInt(v: Long, max: Int): Int = if (v < 0) 0 else if (v > max) max else v.toInt()

    fun eq(b: ByteArray, o: Int, s: String) = o >= 0 && Bytes.startsWith(b, s, o)

    /** Latin-1 NUL-terminated string within [max] bytes. */
    fun cstr(b: ByteArray, o: Int, max: Int): String {
        if (o < 0 || o >= b.size) return ""
        val lim = minOf(b.size.toLong(), o.toLong() + max).toInt()
        var e = o
        while (e < lim && b[e].toInt() != 0) e++
        return clean(String(b, o, e - o, Charsets.ISO_8859_1))
    }

    fun utf8(b: ByteArray, o: Int, len: Int): String {
        if (o < 0 || len <= 0 || o >= b.size) return ""
        val n = minOf(len, b.size - o)
        return clean(String(b, o, n, Charsets.UTF_8))
    }

    fun clean(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c.code < 0x20 || c.code == 0x7F) '?' else c)
        return sb.toString()
    }

    fun trimField(s: String) = s.trimEnd(' ', '\u0000')

    fun fmtEpoch(sec: Long): String =
        if (sec in 0..32503680000L) java.time.Instant.ofEpochSecond(sec).toString() else sec.toString()

    fun dosDateTime(date: Int, time: Int): String? {
        val y = (date shr 9) + 1980; val mo = (date shr 5) and 15; val d = date and 31
        if (date == 0 || mo !in 1..12 || d !in 1..31) return null
        val h = time shr 11; val mi = (time shr 5) and 63; val s = (time and 31) * 2
        return "%04d-%02d-%02d %02d:%02d:%02d".format(java.util.Locale.ROOT, y, mo, d, h, mi, s)
    }

    fun size(n: Long) = "$n bytes"

    fun ext(fileName: String?): String? {
        val n = fileName?.substringAfterLast('/')?.substringAfterLast('\\') ?: return null
        val i = n.lastIndexOf('.')
        return if (i < 0 || i == n.length - 1) null else n.substring(i + 1).lowercase()
    }

    fun crc32(b: ByteArray, off: Int, len: Int): Long {
        val c = java.util.zip.CRC32()
        if (off >= 0 && len > 0 && off + len <= b.size) c.update(b, off, len)
        return c.value
    }

    fun inflateRaw(src: ByteArray, off: Int, len: Int, maxOut: Int, nowrap: Boolean = true): ByteArray {
        if (off < 0 || len <= 0 || off >= src.size) return ByteArray(0)
        val inf = Inflater(nowrap)
        try {
            inf.setInput(src, off, minOf(len, src.size - off))
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (out.size() < maxOut && !inf.finished()) {
                val n = try { inf.inflate(buf, 0, minOf(buf.size, maxOut - out.size())) } catch (e: java.util.zip.DataFormatException) { break }
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally { inf.end() }
    }

    fun <T> safe(block: () -> T?): T? = try { block() } catch (t: Throwable) { null }
}

/** Cursor over a byte array with variable-length-integer support (7 bits per byte, LSB first). */
internal class ArcRd(val b: ByteArray, var p: Int = 0) {
    val left get() = b.size - p
    /** Returns -1 when truncated or longer than [maxBytes]. */
    fun vint(maxBytes: Int = 10): Long {
        var v = 0L; var shift = 0; var n = 0
        while (n < maxBytes) {
            if (p >= b.size) return -1
            val x = b[p++].toInt() and 0xFF
            if (shift < 63) v = v or ((x and 0x7F).toLong() shl shift)
            n++
            if (x and 0x80 == 0) return if (v < 0) -1 else v
            shift += 7
        }
        return -1
    }
}

/** Common wrapper: never throw. */
internal abstract class ArcBase : FormatInspector {
    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = try {
        run(source, fileName)
    } catch (t: Throwable) { null }
    abstract fun run(source: ByteSource, fileName: String?): InspectionResult?
}
