package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*
import java.util.zip.Inflater

/** Every font and game-file inspector in this package family. */
object FontGameInspectors {
    val all: List<FormatInspector> = listOf(
        FontSfntInspector,
        FontWoffInspector,
        FontWoff2Inspector,
        FontBdfInspector,
        FontPcfInspector,
        GameNesInspector,
        GameGameBoyInspector,
        GameGbaInspector,
        GameSnesInspector,
        GameN64Inspector,
        GameGenesisInspector,
        GameNdsInspector,
        GameNbtInspector,
        GameMcaInspector,
        GamePakInspector,
        GameWadInspector,
        GameUnityInspector,
        GamePgnInspector,
        GameFenInspector,
    )
}

internal inline fun <T> fgSafe(block: () -> T?): T? = try { block() } catch (t: Throwable) { null }

/** Bounded reader: clamps every request to the file, to a per-call maximum and to the total budget. */
internal class FgIo(private val src: ByteSource) {
    private var budget: Long = FormatInspector.MAX_READ_BYTES.toLong()
    val size: Long = maxOf(0L, src.size)

    fun read(off: Long, len: Long): ByteArray {
        if (off < 0 || len <= 0 || off >= size || budget <= 0) return ByteArray(0)
        val n = minOf(len, size - off, budget, MAX_CHUNK.toLong()).toInt()
        val r = src.read(off, n)
        budget -= r.size
        return r
    }

    fun inFile(off: Long, len: Long): Boolean = off >= 0 && len >= 0 && off <= size && len <= size - off

    companion object { const val MAX_CHUNK = 4 * 1024 * 1024 }
}

class FgInflated(val bytes: ByteArray, val complete: Boolean, val error: Boolean)

internal object Fg {
    fun clean(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c.code < 0x20 || c.code == 0x7F) '.' else c)
        return sb.toString()
    }

    /** ASCII text up to the first NUL within [len] bytes, trimmed; unprintable bytes become '.'. */
    fun cstr(b: ByteArray, off: Int, len: Int): String {
        var n = 0
        val end = minOf(b.size, off + len)
        var i = off
        while (i < end && b[i].toInt() != 0) { n++; i++ }
        return Bytes.ascii(b, off, n).trim()
    }

    fun hex(v: Long, digits: Int = 0): String {
        val h = java.lang.Long.toHexString(v).uppercase()
        return "0x" + h.padStart(digits, '0')
    }

    fun time(secs: Long): String =
        try { java.time.Instant.ofEpochSecond(secs).toString() } catch (t: Throwable) { secs.toString() }

    fun list(items: List<String>, cap: Int): String =
        if (items.size <= cap) items.joinToString(", ") else items.take(cap).joinToString(", ") + ", ... (+${items.size - cap} more)"

    /** Inflate [data] (zlib, or raw deflate when [raw]) producing at most [max] bytes. */
    fun inflate(data: ByteArray, off: Int, len: Int, max: Int, raw: Boolean): FgInflated {
        val inf = Inflater(raw)
        val out = java.io.ByteArrayOutputStream()
        var error = false
        var done = false
        try {
            if (off < 0 || len <= 0 || off >= data.size) return FgInflated(ByteArray(0), false, true)
            inf.setInput(data, off, minOf(len, data.size - off))
            val buf = ByteArray(8192)
            var guard = 0
            while (out.size() < max && guard++ < 100000) {
                val n = inf.inflate(buf, 0, minOf(buf.size, max - out.size()))
                if (n > 0) out.write(buf, 0, n)
                if (inf.finished()) { done = true; break }
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            }
        } catch (t: Throwable) {
            error = true
        } finally {
            inf.end()
        }
        return FgInflated(out.toByteArray(), done, error)
    }

    /** Offset of the deflate stream inside a gzip member, or -1 when the header is malformed. */
    fun gzipBodyOffset(b: ByteArray): Int {
        if (b.size < 10 || Bytes.u8(b, 0) != 0x1F || Bytes.u8(b, 1) != 0x8B || Bytes.u8(b, 2) != 8) return -1
        val flg = Bytes.u8(b, 3)
        var p = 10
        if (flg and 4 != 0) { p += 2 + Bytes.u16le(b, p) }
        if (flg and 8 != 0) { while (p < b.size && b[p].toInt() != 0) p++; p++ }
        if (flg and 16 != 0) { while (p < b.size && b[p].toInt() != 0) p++; p++ }
        if (flg and 2 != 0) p += 2
        return if (p in 0 until b.size) p else -1
    }

    fun fixed(v: Int): String {
        val d = v / 65536.0
        val s = "%.3f".format(java.util.Locale.ROOT, d)
        return s.trimEnd('0').trimEnd('.')
    }

    fun crc16Modbus(b: ByteArray, from: Int, to: Int): Int {
        var crc = 0xFFFF
        for (i in from until minOf(to, b.size)) {
            crc = crc xor (b[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc and 0xFFFF
    }

    /** Decode text bytes as UTF-8 (lenient) with a BOM stripped. */
    fun utf8(b: ByteArray, off: Int = 0, len: Int = b.size - off): String {
        var o = off
        var l = len
        if (l >= 3 && Bytes.u8(b, o) == 0xEF && Bytes.u8(b, o + 1) == 0xBB && Bytes.u8(b, o + 2) == 0xBF) { o += 3; l -= 3 }
        return String(b, o, maxOf(0, minOf(l, b.size - o)), Charsets.UTF_8)
    }
}
