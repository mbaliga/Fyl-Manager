package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** Budgeted reader: total bytes handed out never exceed the shared budget. */
internal class GsReader(private val src: ByteSource, private var budget: Long = FormatInspector.MAX_READ_BYTES.toLong()) {
    val size: Long get() = src.size

    fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length <= 0 || budget <= 0) return ByteArray(0)
        val avail = src.size - offset
        if (avail <= 0) return ByteArray(0)
        val want = minOf(length.toLong(), avail, budget).toInt()
        val out = java.io.ByteArrayOutputStream(minOf(want, 1 shl 16))
        var got = 0
        while (got < want) {
            val chunk = src.read(offset + got, minOf(want - got, 1 shl 20))
            if (chunk.isEmpty()) break
            val take = minOf(chunk.size, want - got)
            out.write(chunk, 0, take)
            got += take
        }
        budget -= got
        return out.toByteArray()
    }

    fun head(max: Int): ByteArray = read(0, max)
}

/** Collects facts and warnings with sane caps. */
internal class GsFacts {
    val facts = ArrayList<Fact>()
    val warnings = ArrayList<String>()

    fun add(group: String, label: String, value: Any?) {
        if (value == null || facts.size >= 600) return
        val s = value.toString()
        if (s.isNotEmpty()) facts.add(Fact(label, s, group))
    }

    fun warn(w: String) { if (warnings.size < 50) warnings.add(w) }
}

internal object GsKit {
    const val TEXT_READ = 4 * 1024 * 1024

    inline fun guard(block: () -> InspectionResult?): InspectionResult? =
        try { block() } catch (t: Throwable) { null }

    fun decodeText(b: ByteArray): String {
        if (b.size >= 2 && (b[0].toInt() and 0xFF) == 0xFE && (b[1].toInt() and 0xFF) == 0xFF)
            return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        if (b.size >= 2 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xFF) == 0xFE)
            return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        if (b.size >= 3 && (b[0].toInt() and 0xFF) == 0xEF && (b[1].toInt() and 0xFF) == 0xBB && (b[2].toInt() and 0xFF) == 0xBF)
            return String(b, 3, b.size - 3, Charsets.UTF_8)
        return String(b, Charsets.UTF_8)
    }

    fun clip(s: String, n: Int): String = if (s.length <= n) s else s.substring(0, n) + "..."

    fun num(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return "n/a"
        return java.math.BigDecimal(d).round(java.math.MathContext(10)).stripTrailingZeros().toPlainString()
    }

    fun unescape(s: String): String {
        var t = s.trim()
        if (t.startsWith("<![CDATA[") && t.endsWith("]]>")) return t.substring(9, t.length - 3)
        t = t.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
        return t
    }

    fun cstr(b: ByteArray, o: Int, len: Int): String {
        if (o < 0 || o >= b.size) return ""
        val end = minOf(b.size, o + maxOf(len, 0))
        var e = o
        while (e < end && b[e].toInt() != 0) e++
        return Bytes.ascii(b, o, e - o).trim()
    }

    fun mib(n: Int) = "%.1f MiB".format(java.util.Locale.ROOT, n / 1048576.0)

    fun attr(tag: String, name: String): String? {
        val m = Regex("""(?:^|[\s<])""" + Regex.escape(name) + """\s*=\s*(?:"([^"]*)"|'([^']*)')""").find(tag) ?: return null
        return m.groupValues[1].ifEmpty { m.groupValues[2] }
    }
}
