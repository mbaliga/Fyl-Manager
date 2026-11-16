package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/**
 * Bounded reader for OLE2 / Compound File Binary containers: header, FAT, directory, mini stream.
 * Every chain walk and read is capped; hostile counts cannot cause large allocations.
 */
internal class DocumentInspectorsOleFile private constructor(
    private val src: ByteSource,
    val sectorSize: Int,
    val miniSectorSize: Int,
    val miniCutoff: Long,
    private val difat: LongArray,
    private val firstDir: Long,
    private val firstMiniFat: Long,
    private val numMiniFat: Long,
    val majorVersion: Int,
    val minorVersion: Int,
    val fatSectorCount: Long,
) {
    class Entry(
        val index: Int, val name: String, val type: Int, val left: Long, val right: Long, val child: Long,
        val clsid: ByteArray, val startSector: Long, val size: Long,
    ) {
        var path: String = name
    }

    val warnings = ArrayList<String>()
    var entries: List<Entry> = emptyList()
        private set
    var directoryTruncated = false
        private set

    private var budget = FormatInspector.MAX_READ_BYTES.toLong()
    private val fatCache = HashMap<Long, ByteArray>()
    private var miniChainCache: LongArray? = null
    private var rootChainCache: LongArray? = null

    private fun sectorOffset(s: Long): Long = (s + 1) * sectorSize

    private fun valid(s: Long): Boolean = s in 0..0xFFFFFFF9L && sectorOffset(s) < src.size

    private fun rd(offset: Long, len: Int): ByteArray {
        if (len <= 0 || budget <= 0) return ByteArray(0)
        val n = minOf(len.toLong(), budget).toInt()
        val b = DocumentInspectorsUtil.readSafe(src, offset, n)
        budget -= b.size
        return b
    }

    private fun fatNext(s: Long): Long {
        val per = sectorSize / 4
        val idx = s / per
        if (idx >= difat.size) return END
        val fs = difat[idx.toInt()]
        if (!valid(fs)) return END
        var data = fatCache[fs]
        if (data == null) {
            data = rd(sectorOffset(fs), sectorSize)
            if (fatCache.size < 512) fatCache[fs] = data
        }
        val o = ((s % per) * 4).toInt()
        if (o + 4 > data.size) return END
        return Bytes.u32le(data, o)
    }

    /** Sector ids of a FAT chain starting at [start], at most [maxLen] long, cycle-safe. */
    fun chain(start: Long, maxLen: Int): LongArray {
        val out = ArrayList<Long>()
        val seen = HashSet<Long>()
        var s = start
        while (out.size < maxLen && valid(s) && seen.add(s)) {
            out.add(s)
            s = fatNext(s)
        }
        return out.toLongArray()
    }

    private fun readChain(start: Long, wantBytes: Int): ByteArray {
        if (wantBytes <= 0) return ByteArray(0)
        val need = (wantBytes + sectorSize - 1) / sectorSize
        val ch = chain(start, need)
        val out = java.io.ByteArrayOutputStream(minOf(wantBytes, 1 shl 20))
        for (s in ch) {
            val remaining = wantBytes - out.size()
            if (remaining <= 0) break
            val b = rd(sectorOffset(s), minOf(sectorSize, remaining))
            out.write(b)
            if (b.size < minOf(sectorSize, remaining)) break
        }
        return out.toByteArray()
    }

    private fun miniFatNext(m: Long): Long {
        var ch = miniChainCache
        if (ch == null) {
            ch = chain(firstMiniFat, minOf(numMiniFat, 4096L).toInt())
            miniChainCache = ch
        }
        val idx = (m * 4) / sectorSize
        if (idx >= ch.size) return END
        val b = rd(sectorOffset(ch[idx.toInt()]) + (m * 4) % sectorSize, 4)
        if (b.size < 4) return END
        return Bytes.u32le(b, 0)
    }

    private fun readMini(start: Long, wantBytes: Int, root: Entry?): ByteArray {
        if (root == null || wantBytes <= 0) return ByteArray(0)
        var rc = rootChainCache
        if (rc == null) {
            val n = minOf((root.size + sectorSize - 1) / sectorSize, 32768L).toInt()
            rc = chain(root.startSector, n)
            rootChainCache = rc
        }
        val out = java.io.ByteArrayOutputStream()
        val seen = HashSet<Long>()
        var m = start
        val maxMini = (wantBytes + miniSectorSize - 1) / miniSectorSize
        var count = 0
        while (count < maxMini && m in 0..0xFFFFFFF9L && seen.add(m)) {
            val pos = m * miniSectorSize
            val si = pos / sectorSize
            if (si >= rc.size) break
            val b = rd(sectorOffset(rc[si.toInt()]) + pos % sectorSize, minOf(miniSectorSize, wantBytes - out.size()))
            if (b.isEmpty()) break
            out.write(b)
            count++
            m = miniFatNext(m)
        }
        return out.toByteArray()
    }

    fun find(name: String): Entry? = entries.firstOrNull { it.type == 2 && it.name == name }

    fun readStream(e: Entry, maxBytes: Int): ByteArray {
        val want = minOf(e.size, maxBytes.toLong()).toInt()
        if (want <= 0) return ByteArray(0)
        return try {
            if (e.type != 5 && e.size < miniCutoff) readMini(e.startSector, want, entries.firstOrNull { it.type == 5 })
            else readChain(e.startSector, want)
        } catch (t: Throwable) { ByteArray(0) }
    }

    private fun loadDirectory() {
        val maxBytes = 1024 * 1024
        val ch = chain(firstDir, maxBytes / sectorSize)
        if (ch.isEmpty()) { warnings.add("Directory chain could not be read."); return }
        val buf = java.io.ByteArrayOutputStream()
        for (s in ch) buf.write(rd(sectorOffset(s), sectorSize))
        if (ch.size >= maxBytes / sectorSize) directoryTruncated = true
        val b = buf.toByteArray()
        val count = minOf(b.size / 128, 8192)
        val list = ArrayList<Entry>(minOf(count, 256))
        for (i in 0 until count) {
            val o = i * 128
            val type = b[o + 66].toInt() and 0xFF
            val nameLen = Bytes.u16le(b, o + 64)
            val chars = if (nameLen in 2..64) nameLen / 2 - 1 else 0
            val name = if (type == 0 || chars <= 0) "" else String(b, o, chars * 2, Charsets.UTF_16LE)
            var size = Bytes.u64le(b, o + 120)
            if (majorVersion == 3 || size < 0) size = Bytes.u32le(b, o + 120)
            list.add(
                Entry(
                    i, name, type, Bytes.u32le(b, o + 68), Bytes.u32le(b, o + 72), Bytes.u32le(b, o + 76),
                    b.copyOfRange(o + 80, o + 96), Bytes.u32le(b, o + 116), size,
                )
            )
        }
        entries = list
        // Assign paths by walking the red-black tree from the root.
        if (list.isNotEmpty() && list[0].type == 5) {
            val visited = HashSet<Int>()
            visited.add(0)
            val stack = ArrayList<Pair<Long, String>>()
            stack.add(list[0].child to "")
            var guard = 0
            while (stack.isNotEmpty() && guard++ < 20000) {
                val (id, prefix) = stack.removeAt(stack.size - 1)
                if (id < 0 || id >= list.size || !visited.add(id.toInt())) continue
                val e = list[id.toInt()]
                if (e.type == 0) continue
                e.path = prefix + e.name
                if (e.right != NOSTREAM) stack.add(e.right to prefix)
                if (e.type == 1 && e.child != NOSTREAM) stack.add(e.child to e.path + "/")
                if (e.left != NOSTREAM) stack.add(e.left to prefix)
            }
        }
    }

    /** Entries reachable from the root, in tree order (root first). */
    fun reachable(): List<Entry> {
        val l = entries
        if (l.isEmpty()) return l
        // Paths of unreachable entries equal their bare names; reachable ones were rewritten (or equal by chance).
        return l.filter { it.type != 0 }
    }

    companion object {
        private const val END = 0xFFFFFFFEL
        private const val NOSTREAM = 0xFFFFFFFFL
        private val MAGIC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())

        fun isCfb(b: ByteArray): Boolean {
            if (b.size < 8) return false
            for (i in 0 until 8) if (b[i] != MAGIC[i]) return false
            return true
        }

        /** Printable form: control characters (e.g. U+0005 prefix) become <05>. */
        fun display(name: String): String {
            val sb = StringBuilder()
            for (c in name) if (c.code < 0x20) sb.append("<%02X>".format(c.code)) else sb.append(c)
            return sb.toString()
        }

        fun open(src: ByteSource): DocumentInspectorsOleFile? {
            try {
                val h = DocumentInspectorsUtil.readSafe(src, 0, 512)
                if (h.size < 512 || !isCfb(h)) return null
                val minor = Bytes.u16le(h, 0x18)
                val major = Bytes.u16le(h, 0x1A)
                val shift = Bytes.u16le(h, 0x1E)
                val miniShift = Bytes.u16le(h, 0x20)
                if (Bytes.u16le(h, 0x1C) != 0xFFFE) return null
                if (shift != 9 && shift != 12) return null
                if (miniShift != 6) return null
                if (major != 3 && major != 4) return null
                val sectorSize = 1 shl shift
                if (src.size < sectorSize + 128) return null
                val numFat = Bytes.u32le(h, 0x2C)
                val firstDir = Bytes.u32le(h, 0x30)
                val cutoff = Bytes.u32le(h, 0x38)
                val firstMini = Bytes.u32le(h, 0x3C)
                val numMini = Bytes.u32le(h, 0x40)
                val firstDifat = Bytes.u32le(h, 0x44)
                val numDifat = Bytes.u32le(h, 0x48)
                val ids = ArrayList<Long>()
                for (i in 0 until 109) {
                    val v = Bytes.u32le(h, 0x4C + 4 * i)
                    if (v > 0xFFFFFFF9L) break
                    ids.add(v)
                }
                var next = firstDifat
                var n = 0L
                val per = sectorSize / 4
                var used = 0L
                while (n < minOf(numDifat, 64L) && next <= 0xFFFFFFF9L && (next + 1) * sectorSize < src.size && ids.size < numFat) {
                    val d = DocumentInspectorsUtil.readSafe(src, (next + 1) * sectorSize, sectorSize)
                    used += d.size
                    if (d.size < sectorSize) break
                    for (i in 0 until per - 1) {
                        val v = Bytes.u32le(d, 4 * i)
                        if (v > 0xFFFFFFF9L) break
                        ids.add(v)
                        if (ids.size >= 8192) break
                    }
                    next = Bytes.u32le(d, 4 * (per - 1))
                    n++
                }
                val fatIds = ids.take(minOf(numFat, 8192L).toInt()).toLongArray()
                if (fatIds.isEmpty()) return null
                val f = DocumentInspectorsOleFile(src, sectorSize, 64, cutoff, fatIds, firstDir, firstMini, numMini, major, minor, numFat)
                f.budget -= 512 + used
                f.loadDirectory()
                if (f.entries.isEmpty()) return null
                return f
            } catch (t: Throwable) {
                return null
            }
        }
    }
}
