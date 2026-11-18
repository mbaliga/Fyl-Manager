package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*
import java.util.zip.Inflater

internal const val TIMES = "×"

/** Base class: guarantees the "never throws" contract. */
internal abstract class SafeInspector : FormatInspector {
    final override fun inspect(source: ByteSource, fileName: String?): InspectionResult? =
        try { doInspect(source, fileName) } catch (t: Throwable) { null }

    protected abstract fun doInspect(source: ByteSource, fileName: String?): InspectionResult?
}

internal class FactList {
    val list = ArrayList<Fact>()
    fun add(group: String?, label: String, value: String?) {
        if (value != null) list.add(Fact(label, value, group))
    }
}

internal object Img {
    fun ext(fileName: String?): String? {
        val n = fileName ?: return null
        val i = n.lastIndexOf('.')
        return if (i in 0 until n.length - 1) n.substring(i + 1).lowercase() else null
    }

    fun title(fileName: String?, fallback: String): String =
        fileName?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: fallback

    fun dims(w: Long, h: Long) = "$w$TIMES$h"

    fun clip(s: String, max: Int): String = if (s.length <= max) s else s.substring(0, max) + "…"

    fun latin1(b: ByteArray, o: Int, len: Int): String {
        val end = minOf(b.size, o + len)
        if (o < 0 || o >= end) return ""
        return String(b, o, end - o, Charsets.ISO_8859_1)
    }

    /** Printable text with control characters dropped (except newline/tab). */
    fun clean(s: String): String = s.filter { it == '\n' || it == '\t' || it >= ' ' }.trimEnd('\u0000')

    fun inflateBounded(data: ByteArray, max: Int): ByteArray? {
        val inf = Inflater()
        try {
            inf.setInput(data)
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(4096)
            var guard = 0
            while (!inf.finished() && out.size() < max && guard++ < 1000) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, minOf(n, max - out.size()))
            }
            return out.toByteArray()
        } catch (t: Exception) {
            return null
        } finally {
            inf.end()
        }
    }
}

/** Sliding 64 KiB window over a source with a hard cap on the number of loads (bounded total read). */
internal class Window(private val src: ByteSource, private val maxLoads: Int = 128) {
    private var base = -1L
    private var buf = ByteArray(0)
    private var loads = 0
    val size: Long get() = src.size
    var exhausted = false
        private set

    fun u8(pos: Long): Int {
        if (pos < 0 || pos >= src.size) return -1
        if (base < 0 || pos < base || pos >= base + buf.size) {
            if (loads >= maxLoads) { exhausted = true; return -1 }
            loads++
            buf = src.read(pos, 65536)
            base = pos
            if (buf.isEmpty()) { base = -1; return -1 }
        }
        return buf[(pos - base).toInt()].toInt() and 0xFF
    }

    fun u16le(pos: Long): Int { val a = u8(pos); val b = u8(pos + 1); return if (a < 0 || b < 0) -1 else a or (b shl 8) }
}

/** Minimal TIFF/EXIF structure reader over a ByteSource; [base] is the offset of the TIFF header. */
internal class TiffReader(private val src: ByteSource, private val base: Long) {
    var le = true
        private set
    var valid = false
        private set
    var bigTiff = false
        private set
    var ifd0 = 0L
        private set

    class Entry(val tag: Int, val type: Int, val count: Long, val raw: ByteArray)

    init {
        val h = src.read(base, 8)
        if (h.size == 8) {
            val a = h[0].toInt(); val b = h[1].toInt()
            val ii = a == 'I'.code && b == 'I'.code
            val mm = a == 'M'.code && b == 'M'.code
            if (ii || mm) {
                le = ii
                val ver = u16(h, 2)
                if (ver == 42) { valid = true; ifd0 = u32(h, 4) }
                else if (ver == 43) { valid = true; bigTiff = true }
            }
        }
    }

    fun u16(b: ByteArray, o: Int) = if (le) Bytes.u16le(b, o) else Bytes.u16be(b, o)
    fun u32(b: ByteArray, o: Int) = if (le) Bytes.u32le(b, o) else Bytes.u32be(b, o)

    private fun typeSize(t: Int): Int = when (t) {
        1, 2, 6, 7 -> 1
        3, 8 -> 2
        4, 9, 11, 13 -> 4
        5, 10, 12, 16, 17, 18 -> 8
        else -> 0
    }

    /** Returns (entries, next IFD offset) or null if the IFD cannot be read. Capped at 512 entries. */
    fun readIfd(off: Long): Pair<Map<Int, Entry>, Long>? {
        if (!valid || bigTiff || off < 8 || base + off + 2 > src.size) return null
        val c = src.read(base + off, 2)
        if (c.size < 2) return null
        val n = minOf(u16(c, 0), 512)
        val body = src.read(base + off + 2, n * 12 + 4)
        val out = LinkedHashMap<Int, Entry>()
        for (i in 0 until n) {
            val o = i * 12
            if (o + 12 > body.size) break
            val tag = u16(body, o)
            out.putIfAbsent(tag, Entry(tag, u16(body, o + 2), u32(body, o + 4), body.copyOfRange(o + 8, o + 12)))
        }
        val next = if (n * 12 + 4 <= body.size) u32(body, n * 12) else 0L
        return Pair(out, next)
    }

    fun valueBytes(e: Entry, maxBytes: Int = 4096): ByteArray {
        val ts = typeSize(e.type)
        if (ts == 0) return ByteArray(0)
        val total = e.count * ts
        if (total <= 0) return ByteArray(0)
        val want = minOf(total, maxBytes.toLong()).toInt()
        if (total <= 4) return e.raw.copyOf(want)
        val off = u32(e.raw, 0)
        if (off < 0 || base + off >= src.size) return ByteArray(0)
        return src.read(base + off, want)
    }

    fun long(e: Entry, index: Int = 0): Long? {
        val ts = typeSize(e.type)
        if (ts == 0 || index < 0 || index >= e.count) return null
        val v = valueBytes(e, (index + 1) * ts)
        val o = index * ts
        if (o + ts > v.size) return null
        return when (e.type) {
            1, 7 -> Bytes.u8(v, o).toLong()
            6 -> v[o].toLong()
            3 -> u16(v, o).toLong()
            8 -> u16(v, o).toShort().toLong()
            4, 13 -> u32(v, o)
            9 -> u32(v, o).toInt().toLong()
            else -> null
        }
    }

    fun longs(e: Entry, max: Int = 16): List<Long> {
        val out = ArrayList<Long>()
        val n = minOf(e.count, max.toLong()).toInt()
        for (i in 0 until n) long(e, i)?.let { out.add(it) }
        return out
    }

    fun str(e: Entry): String? {
        if (e.type != 2) return null
        val v = valueBytes(e, 1024)
        var end = v.indexOfFirst { it.toInt() == 0 }
        if (end < 0) end = v.size
        val s = Img.clean(String(v, 0, end, Charsets.ISO_8859_1)).trim()
        return s.ifEmpty { null }
    }

    fun rational(e: Entry, index: Int = 0): Pair<Long, Long>? {
        if (e.type != 5 && e.type != 10) return null
        if (index >= e.count) return null
        val v = valueBytes(e, (index + 1) * 8)
        val o = index * 8
        if (o + 8 > v.size) return null
        val n = u32(v, o); val d = u32(v, o + 4)
        return if (e.type == 5) Pair(n, d) else Pair(n.toInt().toLong(), d.toInt().toLong())
    }
}

internal class ExifSummary(val facts: List<Fact>, val hasGps: Boolean, val orientation: Int?, val make: String?, val model: String?)

internal object Exif {
    fun orientationName(o: Int): String? = when (o) {
        1 -> "Normal"
        2 -> "Mirrored horizontally"
        3 -> "Rotated 180°"
        4 -> "Mirrored vertically"
        5 -> "Mirrored horizontally, rotated 270° clockwise"
        6 -> "Rotated 90° clockwise"
        7 -> "Mirrored horizontally, rotated 90° clockwise"
        8 -> "Rotated 270° clockwise"
        else -> null
    }

    fun read(src: ByteSource, base: Long, group: String = "EXIF"): ExifSummary? {
        val t = TiffReader(src, base)
        if (!t.valid || t.bigTiff) return null
        val (ifd0, _) = t.readIfd(t.ifd0) ?: return null
        val f = FactList()
        val make = ifd0[0x010F]?.let { t.str(it) }
        val model = ifd0[0x0110]?.let { t.str(it) }
        f.add(group, "Camera make", make)
        f.add(group, "Camera model", model)
        var orientation: Int? = null
        ifd0[0x0112]?.let { e -> t.long(e)?.toInt()?.let { o -> orientation = o; f.add(group, "Orientation", Exif.orientationName(o) ?: "Unknown ($o)") } }
        f.add(group, "Software", ifd0[0x0131]?.let { t.str(it) })
        f.add(group, "Modified", ifd0[0x0132]?.let { t.str(it) })
        var hasGps = false
        ifd0[0x8825]?.let { hasGps = true }
        ifd0[0x8769]?.let { p ->
            val off = t.long(p)
            val sub = if (off != null) t.readIfd(off) else null
            if (sub != null) {
                val x = sub.first
                f.add(group, "Date taken", x[0x9003]?.let { t.str(it) })
                f.add(group, "Date digitized", x[0x9004]?.let { t.str(it) })
                x[0x829A]?.let { e -> t.rational(e)?.let { (n, d) ->
                    if (n > 0 && d > 0) f.add(group, "Exposure time", if (n < d) "1/${(d + n / 2) / n} s" else "${"%.1f".format(java.util.Locale.ROOT, n.toDouble() / d)} s")
                } }
                x[0x829D]?.let { e -> t.rational(e)?.let { (n, d) -> if (d > 0 && n > 0) f.add(group, "Aperture", "f/" + "%.1f".format(java.util.Locale.ROOT, n.toDouble() / d)) } }
                x[0x8827]?.let { e -> t.long(e)?.let { f.add(group, "ISO", it.toString()) } }
                x[0x920A]?.let { e -> t.rational(e)?.let { (n, d) -> if (d > 0) f.add(group, "Focal length", "%.1f mm".format(java.util.Locale.ROOT, n.toDouble() / d)) } }
                f.add(group, "Lens", x[0xA434]?.let { t.str(it) })
            }
        }
        f.add(group, "GPS data", if (hasGps) "present" else null)
        return ExifSummary(f.list, hasGps, orientation, make, model)
    }
}
