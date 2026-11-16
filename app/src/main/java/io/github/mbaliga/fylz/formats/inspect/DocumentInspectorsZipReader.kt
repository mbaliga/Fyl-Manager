package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/**
 * Reads a ZIP central directory from the tail of a [ByteSource] and extracts a few small parts.
 * Never uses java.util.zip.ZipFile/ZipInputStream; only Inflater, with hard output caps.
 */
internal class DocumentInspectorsZipReader private constructor(
    private val src: ByteSource,
    val entries: List<Entry>,
    private val delta: Long,
    var budget: Long,
    val directoryTruncated: Boolean,
) {
    class Entry(
        val name: String, val method: Int, val flags: Int,
        val compressedSize: Long, val size: Long, val localOffset: Long,
    )

    class Part(val bytes: ByteArray, val truncated: Boolean) {
        fun text(): String {
            val b = bytes
            return when {
                b.size >= 2 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xFF) == 0xFE ->
                    String(b, 2, b.size - 2, Charsets.UTF_16LE)
                b.size >= 2 && (b[0].toInt() and 0xFF) == 0xFE && (b[1].toInt() and 0xFF) == 0xFF ->
                    String(b, 2, b.size - 2, Charsets.UTF_16BE)
                b.size >= 3 && (b[0].toInt() and 0xFF) == 0xEF && (b[1].toInt() and 0xFF) == 0xBB && (b[2].toInt() and 0xFF) == 0xBF ->
                    String(b, 3, b.size - 3, Charsets.UTF_8)
                else -> String(b, Charsets.UTF_8)
            }
        }
    }

    private val byName: Map<String, Entry> by lazy {
        val m = HashMap<String, Entry>()
        for (e in entries) m[e.name] = e
        m
    }

    fun find(name: String): Entry? = byName[name]

    fun has(name: String): Boolean = byName.containsKey(name)

    private fun rd(offset: Long, length: Int): ByteArray {
        if (length <= 0 || budget <= 0) return ByteArray(0)
        val n = minOf(length.toLong(), budget).toInt()
        val r = DocumentInspectorsUtil.readSafe(src, offset, n)
        budget -= r.size
        return r
    }

    /** Decompressed bytes of [e], at most [maxOut]; null when unreadable (encrypted, unknown method...). */
    fun read(e: Entry, maxOut: Int): Part? {
        try {
            if ((e.flags and 1) != 0) return null
            val lh = rd(e.localOffset + delta, 30)
            if (lh.size < 30 || Bytes.u32le(lh, 0) != 0x04034b50L) return null
            val nameLen = Bytes.u16le(lh, 26)
            val extraLen = Bytes.u16le(lh, 28)
            val dataStart = e.localOffset + delta + 30 + nameLen + extraLen
            val csize = e.compressedSize
            when (e.method) {
                0 -> {
                    val want = minOf(csize, maxOut.toLong()).toInt()
                    val b = rd(dataStart, want)
                    return Part(b, csize > b.size)
                }
                8 -> {
                    val cwant = minOf(csize, COMPRESSED_CAP.toLong()).toInt()
                    val comp = rd(dataStart, cwant)
                    var truncated = csize > comp.size
                    val inf = java.util.zip.Inflater(true)
                    try {
                        inf.setInput(comp)
                        val out = java.io.ByteArrayOutputStream(minOf(maxOut, 64 * 1024))
                        val buf = ByteArray(8192)
                        var total = 0
                        var failed = false
                        while (!inf.finished() && total < maxOut) {
                            val n = try {
                                inf.inflate(buf, 0, minOf(buf.size, maxOut - total))
                            } catch (t: java.util.zip.DataFormatException) { failed = true; 0 }
                            if (failed) break
                            if (n == 0) {
                                if (inf.needsInput() || inf.needsDictionary() || inf.finished()) break
                            }
                            out.write(buf, 0, n)
                            total += n
                        }
                        if (!inf.finished()) truncated = true
                        if (failed && total == 0) return null
                        return Part(out.toByteArray(), truncated)
                    } finally {
                        inf.end()
                    }
                }
                else -> return null
            }
        } catch (t: Throwable) {
            return null
        }
    }

    fun readText(name: String, maxOut: Int = 512 * 1024): String? {
        val e = find(name) ?: return null
        return read(e, maxOut)?.text()
    }

    companion object {
        private const val TAIL_MAX = 65535 + 22 + 20
        private const val CD_MAX = 4 * 1024 * 1024
        private const val COMPRESSED_CAP = 2 * 1024 * 1024
        private const val MAX_ENTRIES = 50_000

        fun open(src: ByteSource): DocumentInspectorsZipReader? {
            try {
                val size = src.size
                if (size < 22) return null
                var used = 0L
                val tailLen = minOf(size, TAIL_MAX.toLong()).toInt()
                val tailStart = size - tailLen
                val tail = DocumentInspectorsUtil.readSafe(src, tailStart, tailLen)
                used += tail.size
                var pos = -1
                var i = tail.size - 22
                while (i >= 0) {
                    if (tail[i].toInt() == 0x50 && tail[i + 1].toInt() == 0x4B && tail[i + 2].toInt() == 0x05 && tail[i + 3].toInt() == 0x06) {
                        val commentLen = Bytes.u16le(tail, i + 20)
                        if (i + 22 + commentLen <= tail.size) { pos = i; break }
                    }
                    i--
                }
                if (pos < 0) return null
                val eocdAbs = tailStart + pos
                var total = Bytes.u16le(tail, pos + 10).toLong()
                var cdSize = Bytes.u32le(tail, pos + 12)
                var cdOff = Bytes.u32le(tail, pos + 16)
                var endOfCd = eocdAbs
                if (total == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdOff == 0xFFFFFFFFL) {
                    val loc = DocumentInspectorsUtil.readSafe(src, eocdAbs - 20, 20)
                    used += loc.size
                    if (loc.size == 20 && Bytes.u32le(loc, 0) == 0x07064b50L) {
                        val z64 = Bytes.u64le(loc, 8)
                        if (z64 in 0 until size) {
                            val h = DocumentInspectorsUtil.readSafe(src, z64, 56)
                            used += h.size
                            if (h.size == 56 && Bytes.u32le(h, 0) == 0x06064b50L) {
                                total = Bytes.u64le(h, 32)
                                cdSize = Bytes.u64le(h, 40)
                                cdOff = Bytes.u64le(h, 48)
                                endOfCd = z64
                            }
                        }
                    }
                }
                if (total < 0 || cdSize < 0 || cdOff < 0 || cdOff >= size) return null
                var truncated = false
                val readSize = if (cdSize > CD_MAX) { truncated = true; CD_MAX } else cdSize.toInt()
                // Tolerate prepended data (self-extractors): the directory ends where the EOCD begins.
                var delta = 0L
                var cd = DocumentInspectorsUtil.readSafe(src, cdOff, minOf(readSize, 4))
                if (cd.size < 4 || Bytes.u32le(cd, 0) != 0x02014b50L) {
                    val shifted = endOfCd - cdSize - cdOff
                    if (shifted > 0 && cdOff + shifted + 4 <= size) {
                        val probe = DocumentInspectorsUtil.readSafe(src, cdOff + shifted, 4)
                        if (probe.size == 4 && Bytes.u32le(probe, 0) == 0x02014b50L) delta = shifted else return null
                    } else return null
                }
                cd = DocumentInspectorsUtil.readSafe(src, cdOff + delta, readSize)
                used += cd.size
                val list = ArrayList<Entry>()
                var p = 0
                val cap = minOf(total, MAX_ENTRIES.toLong()).toInt()
                if (total > MAX_ENTRIES) truncated = true
                while (list.size < cap && p + 46 <= cd.size) {
                    if (Bytes.u32le(cd, p) != 0x02014b50L) break
                    val flags = Bytes.u16le(cd, p + 8)
                    val method = Bytes.u16le(cd, p + 10)
                    var csize = Bytes.u32le(cd, p + 20)
                    var usize = Bytes.u32le(cd, p + 24)
                    val nameLen = Bytes.u16le(cd, p + 28)
                    val extraLen = Bytes.u16le(cd, p + 30)
                    val commentLen = Bytes.u16le(cd, p + 32)
                    var lho = Bytes.u32le(cd, p + 42)
                    val end = p + 46 + nameLen + extraLen + commentLen
                    if (end > cd.size) { truncated = true; break }
                    val charset = if ((flags and 0x800) != 0) Charsets.UTF_8 else Charsets.ISO_8859_1
                    val name = String(cd, p + 46, nameLen, charset)
                    if (usize == 0xFFFFFFFFL || csize == 0xFFFFFFFFL || lho == 0xFFFFFFFFL) {
                        var q = p + 46 + nameLen
                        val qEnd = q + extraLen
                        while (q + 4 <= qEnd) {
                            val id = Bytes.u16le(cd, q)
                            val sz = Bytes.u16le(cd, q + 2)
                            if (id == 1) {
                                var r = q + 4
                                if (usize == 0xFFFFFFFFL && r + 8 <= qEnd) { usize = Bytes.u64le(cd, r); r += 8 }
                                if (csize == 0xFFFFFFFFL && r + 8 <= qEnd) { csize = Bytes.u64le(cd, r); r += 8 }
                                if (lho == 0xFFFFFFFFL && r + 8 <= qEnd) { lho = Bytes.u64le(cd, r) }
                                break
                            }
                            q += 4 + sz
                        }
                    }
                    if (csize < 0) csize = Long.MAX_VALUE / 4
                    if (usize < 0) usize = Long.MAX_VALUE / 4
                    if (lho < 0) lho = Long.MAX_VALUE / 4
                    list.add(Entry(name, method, flags, csize, usize, lho, ))
                    p = end
                }
                if (list.isEmpty() && total > 0) return null
                return DocumentInspectorsZipReader(
                    src, list, delta, FormatInspector.MAX_READ_BYTES.toLong() - used, truncated,
                )
            } catch (t: Throwable) {
                return null
            }
        }
    }
}
