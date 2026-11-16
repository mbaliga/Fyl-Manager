package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*
import java.security.MessageDigest
import java.util.Locale

/** Shared helpers for the text / crypto / shortcut inspectors. All total and bounded. */
internal object TC {
    fun readUpTo(source: ByteSource, max: Int): ByteArray {
        val total = try { source.size } catch (t: Throwable) { 0L }
        val want = if (total <= 0L) 0 else minOf(total, max.toLong()).toInt()
        if (want == 0) return ByteArray(0)
        val out = ByteArray(want)
        var got = 0
        while (got < want) {
            val chunk = source.read(got.toLong(), minOf(262144, want - got))
            if (chunk.isEmpty()) break
            val n = minOf(chunk.size, want - got)
            System.arraycopy(chunk, 0, out, got, n)
            got += n
        }
        return if (got == want) out else out.copyOf(got)
    }

    fun truncated(source: ByteSource, data: ByteArray): Boolean =
        try { source.size > data.size } catch (t: Throwable) { false }

    inline fun guard(block: () -> InspectionResult?): InspectionResult? =
        try { block() } catch (t: Throwable) { null }

    fun latin1(b: ByteArray, off: Int, len: Int): String {
        if (off < 0 || off >= b.size || len <= 0) return ""
        return String(b, off, minOf(len, b.size - off), Charsets.ISO_8859_1)
    }

    fun hex(b: ByteArray, off: Int = 0, len: Int = b.size, sep: String = ""): String {
        val sb = StringBuilder()
        val end = minOf(b.size, off + len)
        var i = maxOf(0, off)
        while (i < end) {
            if (sb.isNotEmpty()) sb.append(sep)
            sb.append("%02x".format(Locale.ROOT, b[i].toInt() and 0xFF))
            i++
        }
        return sb.toString()
    }

    fun digest(alg: String, b: ByteArray, off: Int, len: Int): ByteArray {
        val md = MessageDigest.getInstance(alg)
        val o = maxOf(0, off)
        val l = maxOf(0, minOf(len, b.size - o))
        md.update(b, o, l)
        return md.digest()
    }

    fun cap(s: String, n: Int): String = if (s.length <= n) s else s.substring(0, n) + "..."

    fun bitLen(b: ByteArray, off: Int, len: Int): Int {
        var i = off
        val end = minOf(b.size, off + len)
        while (i < end && b[i].toInt() == 0) i++
        if (i >= end) return 0
        val top = b[i].toInt() and 0xFF
        return (end - i - 1) * 8 + (32 - Integer.numberOfLeadingZeros(top))
    }

    /** "YYYY-MM-DD HH:MM:SS UTC" from Unix seconds. */
    fun fmtEpoch(sec: Long): String {
        val s = sec.coerceIn(-62135596800L, 253402300799L)
        val days = Math.floorDiv(s, 86400L)
        val rem = Math.floorMod(s, 86400L).toInt()
        val z = days + 719468
        val era = Math.floorDiv(z, 146097L)
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        var y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        if (m <= 2) y += 1
        return String.format(Locale.ROOT, "%04d-%02d-%02d %02d:%02d:%02d UTC", y, m, d, rem / 3600, (rem % 3600) / 60, rem % 60)
    }

    /** Windows FILETIME (100 ns ticks since 1601) to text; null for 0 or out-of-range. */
    fun fileTime(t: Long): String? {
        if (t <= 0L) return null
        val sec = t / 10_000_000L - 11644473600L
        if (sec < -62135596800L || sec > 253402300799L) return null
        return fmtEpoch(sec)
    }

    fun idFor(fileName: String?, ids: Set<String>, default: String): String {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase() ?: ""
        return if (ext in ids) ext else default
    }

    /** Decode UTF-8 text, dropping a BOM, with replacement for malformed bytes. */
    fun utf8(b: ByteArray): String {
        var o = 0
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) o = 3
        return String(b, o, b.size - o, Charsets.UTF_8)
    }

    fun base64(s: String): ByteArray? = try {
        java.util.Base64.getDecoder().decode(s.filter { !it.isWhitespace() })
    } catch (t: Throwable) { null }

    fun base64NoPad(b: ByteArray): String = java.util.Base64.getEncoder().withoutPadding().encodeToString(b)
}
