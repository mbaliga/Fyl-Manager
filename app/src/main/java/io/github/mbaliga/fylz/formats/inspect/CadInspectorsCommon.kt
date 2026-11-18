package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*
import java.util.Locale

/** Shared helpers for the CAD / 3D inspectors. Everything is internal and Cad-prefixed. */
internal object CadIo {
    private const val CHUNK = 1 shl 20

    /** Reads up to [max] bytes from [offset], in chunks, never past the end of the source. */
    fun readUpTo(src: ByteSource, offset: Long, max: Int): ByteArray {
        if (offset < 0 || max <= 0) return ByteArray(0)
        val want = minOf(max.toLong(), maxOf(0L, src.size - offset)).toInt()
        if (want <= 0) return ByteArray(0)
        val out = ByteArray(want)
        var got = 0
        while (got < want) {
            val part = src.read(offset + got, minOf(CHUNK, want - got))
            if (part.isEmpty()) break
            val n = minOf(part.size, want - got)
            System.arraycopy(part, 0, out, got, n)
            got += n
        }
        return if (got == want) out else out.copyOf(got)
    }

    fun latin1(b: ByteArray, off: Int = 0, len: Int = b.size - off): String =
        if (off < 0 || len <= 0 || off > b.size) "" else String(b, off, minOf(len, b.size - off), Charsets.ISO_8859_1)

    fun utf8(b: ByteArray, off: Int = 0, len: Int = b.size - off): String =
        if (off < 0 || len <= 0 || off > b.size) "" else String(b, off, minOf(len, b.size - off), Charsets.UTF_8)

    fun num(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return d.toString()
        val s = String.format(Locale.ROOT, "%.4f", d).trimEnd('0').trimEnd('.')
        return if (s.isEmpty() || s == "-0") "0" else s
    }

    fun count(n: Long): String = String.format(Locale.ROOT, "%,d", n)

    /** Calls [f] for each line (CR/LF stripped). At most [maxLines] lines. */
    inline fun forEachLine(s: String, maxLines: Int = Int.MAX_VALUE, f: (String) -> Unit) {
        var i = 0
        var n = 0
        val len = s.length
        while (i < len && n < maxLines) {
            var e = s.indexOf('\n', i)
            if (e < 0) e = len
            var end = e
            if (end > i && s[end - 1] == '\r') end--
            f(s.substring(i, end))
            i = e + 1
            n++
        }
    }

    fun skipBom(b: ByteArray): Int =
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) 3 else 0

    fun unxml(s: String): String =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    fun clip(s: String, n: Int = 200): String = if (s.length <= n) s else s.substring(0, n) + "..."
}

/** Accumulates facts, skipping blank values. */
internal class CadFacts {
    val list = ArrayList<Fact>()
    val warnings = ArrayList<String>()
    fun add(group: String, label: String, value: String?) {
        if (!value.isNullOrBlank()) list.add(Fact(label, value, group))
    }
    fun warn(w: String) { if (warnings.size < 50) warnings.add(w) }
}

/** Axis-aligned bounding box over finite points. */
internal class CadBounds {
    var n = 0L
    var bad = 0L
    val lo = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
    val hi = doubleArrayOf(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)

    fun add(x: Double, y: Double, z: Double) {
        if (!(x.isFinite() && y.isFinite() && z.isFinite())) { bad++; return }
        n++
        if (x < lo[0]) lo[0] = x; if (x > hi[0]) hi[0] = x
        if (y < lo[1]) lo[1] = y; if (y > hi[1]) hi[1] = y
        if (z < lo[2]) lo[2] = z; if (z > hi[2]) hi[2] = z
    }

    private fun tri(a: DoubleArray) = a.joinToString(", ") { CadIo.num(it) }

    fun addFacts(f: CadFacts, group: String, prefix: String = "Bounding box") {
        if (n == 0L) return
        f.add(group, "$prefix min", tri(lo))
        f.add(group, "$prefix max", tri(hi))
        f.add(group, "$prefix size", tri(doubleArrayOf(hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2])))
    }
}

/** AutoCAD release names shared by DXF ($ACADVER) and DWG (first six bytes). */
internal object CadAcad {
    val releases = mapOf(
        "AC1002" to "AutoCAD R2.5",
        "AC1003" to "AutoCAD R2.6",
        "AC1004" to "AutoCAD R9",
        "AC1006" to "AutoCAD R10",
        "AC1009" to "AutoCAD R11/R12",
        "AC1012" to "AutoCAD R13",
        "AC1014" to "AutoCAD R14",
        "AC1015" to "AutoCAD 2000 (2000/2000i/2002)",
        "AC1018" to "AutoCAD 2004 (2004-2006)",
        "AC1021" to "AutoCAD 2007 (2007-2009)",
        "AC1024" to "AutoCAD 2010 (2010-2012)",
        "AC1027" to "AutoCAD 2013 (2013-2017)",
        "AC1032" to "AutoCAD 2018 (2018 and later)",
    )
}

/** Minimal bounded ZIP reader (central directory + stored/deflate extraction). */
internal class CadZip private constructor(
    private val src: ByteSource,
    val entries: List<Entry>,
    val declaredEntries: Int,
) {
    class Entry(val name: String, val method: Int, val comp: Long, val uncomp: Long, val offset: Long)
    class Out(val data: ByteArray, val truncated: Boolean)

    fun find(name: String): Entry? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun extract(e: Entry, maxOut: Int): Out? {
        val lh = src.read(e.offset, 30)
        if (lh.size < 30 || Bytes.u32le(lh, 0) != 0x04034b50L) return null
        val dataOff = e.offset + 30 + Bytes.u16le(lh, 26) + Bytes.u16le(lh, 28)
        if (dataOff >= src.size) return null
        val avail = src.size - dataOff
        val capIn = 3L * 1024 * 1024
        val compLen = minOf(e.comp, avail, capIn).toInt()
        var truncated = e.comp > compLen
        val comp = CadIo.readUpTo(src, dataOff, compLen)
        when (e.method) {
            0 -> {
                val n = minOf(comp.size, maxOut)
                return Out(comp.copyOf(n), truncated || comp.size > maxOut)
            }
            8 -> {
                val inf = java.util.zip.Inflater(true)
                try {
                    inf.setInput(comp)
                    val bos = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(65536)
                    while (!inf.finished()) {
                        val n = try { inf.inflate(buf) } catch (t: java.util.zip.DataFormatException) { truncated = true; break }
                        if (n == 0) { if (inf.needsInput() || inf.needsDictionary()) { truncated = true }; break }
                        val room = maxOut - bos.size()
                        if (n >= room) { bos.write(buf, 0, room); truncated = true; break }
                        bos.write(buf, 0, n)
                    }
                    return Out(bos.toByteArray(), truncated)
                } finally { inf.end() }
            }
        }
        return null
    }

    companion object {
        /** Null when no (non-ZIP64) central directory can be located. */
        fun open(src: ByteSource): CadZip? {
            val size = src.size
            if (size < 22) return null
            val tailLen = minOf(size, 65557L).toInt()
            val tail = CadIo.readUpTo(src, size - tailLen, tailLen)
            var p = tail.size - 22
            var eocd = -1
            while (p >= 0) {
                if (Bytes.u32le(tail, p) == 0x06054b50L && p + 22 + Bytes.u16le(tail, p + 20) <= tail.size) { eocd = p; break }
                p--
            }
            if (eocd < 0) return null
            val total = Bytes.u16le(tail, eocd + 10)
            val cdSize = Bytes.u32le(tail, eocd + 12)
            val cdOff = Bytes.u32le(tail, eocd + 16)
            if (total == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOff == 0xFFFFFFFFL) return null
            if (cdOff >= size) return null
            val cdLen = minOf(cdSize, 1L shl 20, size - cdOff).toInt()
            val cd = CadIo.readUpTo(src, cdOff, cdLen)
            val list = ArrayList<Entry>()
            var o = 0
            var guard = 0
            while (o + 46 <= cd.size && guard < 20000 && list.size < total) {
                guard++
                if (Bytes.u32le(cd, o) != 0x02014b50L) break
                val nameLen = Bytes.u16le(cd, o + 28)
                val extra = Bytes.u16le(cd, o + 30)
                val cmt = Bytes.u16le(cd, o + 32)
                if (o + 46 + nameLen > cd.size) break
                val name = CadIo.utf8(cd, o + 46, nameLen)
                list.add(Entry(name, Bytes.u16le(cd, o + 10), Bytes.u32le(cd, o + 20), Bytes.u32le(cd, o + 24), Bytes.u32le(cd, o + 42)))
                o += 46 + nameLen + extra + cmt
            }
            return CadZip(src, list, total)
        }
    }
}
