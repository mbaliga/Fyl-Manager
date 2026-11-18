package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

internal class TarEntry(val name: String, val size: Long, val type: Char, val mtime: Long, val mode: Long, val dataOff: Long)

internal class TarScan(
    val entries: List<TarEntry>, val total: Long, val format: String,
    val pax: Boolean, val gnuLong: Boolean, val truncated: Boolean, val badChecksum: Boolean,
    val capped: Boolean, val totalBytes: Long, val types: Map<Char, Int>,
)

internal object ArchiveTarParser {
    const val MAX_ENTRIES = 10_000
    const val MAX_LISTED = 5_000
    private const val SCAN_BUDGET = 4L * 1024 * 1024
    private const val MAX_CALLS = 10_000

    /** Octal (or GNU base-256) numeric field; -1 when invalid. */
    fun num(b: ByteArray, o: Int, len: Int): Long {
        if (o + len > b.size) return -1
        if (b[o].toInt() and 0x80 != 0) {
            if (b[o].toInt() and 0x40 != 0) return -1
            var v = (b[o].toInt() and 0x3F).toLong()
            for (i in 1 until len) {
                if (v > (Long.MAX_VALUE shr 8)) return Long.MAX_VALUE
                v = (v shl 8) or (b[o + i].toLong() and 0xFF)
            }
            return v
        }
        var v = 0L; var i = o; val end = o + len
        while (i < end && b[i].toInt() == 0x20) i++
        var any = false
        while (i < end) {
            val c = b[i].toInt()
            if (c in 0x30..0x37) { v = v * 8 + (c - 0x30); any = true; if (v > (1L shl 56)) return -1 }
            else if (c == 0 || c == 0x20) break else return -1
            i++
        }
        return if (any) v else if (i > o) 0 else -1
    }

    private fun validChecksum(b: ByteArray): Boolean {
        var u = 0L; var s = 0L
        for (i in 0 until 512) {
            val v = if (i in 148..155) 0x20 else (b[i].toInt() and 0xFF)
            u += v
            s += if (i in 148..155) 0x20 else b[i].toInt()
        }
        val stored = num(b, 148, 8)
        return stored >= 0 && (stored == u || stored == s)
    }

    fun scan(src: ByteSource, maxEntries: Int = MAX_ENTRIES): TarScan? {
        val entries = ArrayList<TarEntry>()
        val types = sortedMapOf<Char, Int>()
        var pos = 0L; var zeros = 0; var truncated = false; var bad = false
        var format = ""; var pax = false; var gnuLong = false
        var longName: String? = null; var paxPath: String? = null; var paxSize = -1L
        var total = 0L; var totalBytes = 0L; var capped = false
        var iter = 0
        var budget = SCAN_BUDGET
        var calls = 0
        while (true) {
            if (iter++ > maxEntries * 3 || budget < 512 || calls >= MAX_CALLS) { capped = true; break }
            budget -= 512; calls++
            val blk = ArcUtil.readAt(src, pos, 512)
            if (blk.size < 512) { if (pos < src.size) truncated = true; break }
            if (blk.all { it.toInt() == 0 }) { zeros++; pos += 512; if (zeros >= 2) break; continue }
            if (!validChecksum(blk)) { if (entries.isEmpty() && total == 0L) return null; bad = true; break }
            val t = if (blk[156].toInt() == 0) '0' else (blk[156].toInt() and 0xFF).toChar()
            var size = num(blk, 124, 12)
            if (size < 0) { bad = true; break }
            if (paxSize >= 0 && t !in "xgLK") { size = paxSize }
            if (format.isEmpty()) {
                format = when {
                    ArcUtil.eq(blk, 257, "ustar\u0000") && ArcUtil.eq(blk, 263, "00") -> "POSIX ustar"
                    ArcUtil.eq(blk, 257, "ustar  \u0000") -> "GNU tar (old GNU format)"
                    ArcUtil.eq(blk, 257, "ustar") -> "ustar (non-standard version field)"
                    else -> "V7 tar (no ustar magic)"
                }
            }
            val posix = ArcUtil.eq(blk, 257, "ustar\u0000")
            val dataOff = pos + 512
            val padded = if (size > (1L shl 50)) Long.MAX_VALUE / 4 else ((size + 511) / 512) * 512
            when (t) {
                'L' -> {
                    gnuLong = true
                    val n = ArcUtil.clampInt(size, minOf(4096L, budget).toInt()); budget -= n; calls++
                    longName = ArcUtil.cstr(ArcUtil.readAt(src, dataOff, n), 0, 4096)
                }
                'x' -> {
                    pax = true
                    val n = ArcUtil.clampInt(size, minOf(64L * 1024, budget).toInt()); budget -= n; calls++
                    val d = ArcUtil.readAt(src, dataOff, n)
                    val r = parsePax(d)
                    r["path"]?.let { paxPath = ArcUtil.clean(it) }
                    r["size"]?.toLongOrNull()?.let { paxSize = it }
                }
                'g' -> pax = true
                'K' -> gnuLong = true
                else -> {
                    var name = ArcUtil.cstr(blk, 0, 100)
                    if (posix) { val pre = ArcUtil.cstr(blk, 345, 155); if (pre.isNotEmpty()) name = "$pre/$name" }
                    val full = paxPath ?: longName ?: name
                    val mtime = num(blk, 136, 12)
                    total++
                    types[t] = (types[t] ?: 0) + 1
                    if (t == '0' || t == '7') totalBytes = totalBytes + minOf(size, Long.MAX_VALUE / 4)
                    if (entries.size < MAX_LISTED) entries += TarEntry(full, size, t, mtime, num(blk, 100, 8), dataOff)
                    longName = null; paxPath = null; paxSize = -1
                    if (total >= maxEntries) { capped = true; pos += 512 + padded; break }
                }
            }
            if (padded >= Long.MAX_VALUE / 8) { truncated = true; break }
            pos += 512 + padded
            if (pos > src.size) { truncated = true; break }
        }
        if (total == 0L && entries.isEmpty() && format.isEmpty()) return null
        return TarScan(entries, total, format, pax, gnuLong, truncated, bad, capped, totalBytes, types)
    }

    /** pax records: "<len> key=value\n". */
    fun parsePax(d: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var p = 0; var n = 0
        while (p < d.size && n++ < 1000) {
            var sp = p
            while (sp < d.size && d[sp].toInt() != 0x20) sp++
            val len = String(d, p, sp - p, Charsets.US_ASCII).toIntOrNull() ?: break
            if (len <= 0 || p + len > d.size) break
            val rec = String(d, sp + 1, p + len - sp - 1, Charsets.UTF_8).trimEnd('\n')
            val eq = rec.indexOf('=')
            if (eq > 0) out[rec.substring(0, eq)] = rec.substring(eq + 1)
            p += len
        }
        return out
    }

    fun typeName(t: Char) = when (t) {
        '0', '\u0000' -> "file"; '1' -> "hard link"; '2' -> "symlink"; '3' -> "character device"
        '4' -> "block device"; '5' -> "directory"; '6' -> "FIFO"; '7' -> "contiguous file"; 'S' -> "sparse file"
        else -> "type '$t'"
    }
}

internal object ArchiveTarInspector : ArcBase() {
    override val formatIds: Set<String> = setOf("tar")
    override fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val s = ArchiveTarParser.scan(source) ?: return null
        val facts = ArrayList<Fact>(); val warnings = ArrayList<String>()
        facts += Fact("Format variant", s.format + (if (s.pax) " with pax extended headers" else "") + (if (s.gnuLong) " (GNU long-name entries)" else ""), "Archive")
        facts += Fact("Entries", if (s.capped) "at least ${s.total}" else s.total.toString(), "Archive")
        facts += Fact("Total file data", ArcUtil.size(s.totalBytes), "Archive")
        if (s.types.isNotEmpty()) facts += Fact("Entry types", s.types.entries.joinToString(", ") { "${ArchiveTarParser.typeName(it.key)} x${it.value}" }, "Archive")
        val sb = StringBuilder()
        for ((i, e) in s.entries.withIndex()) {
            if (i < 20) {
                val desc = "${ArchiveTarParser.typeName(e.type)}, ${e.size} bytes" + (if (e.mtime > 0) ", " + ArcUtil.fmtEpoch(e.mtime) else "")
                facts += Fact(e.name.take(300), desc, "Entry list")
            }
            if (sb.length < FormatInspector.MAX_TEXT_CHARS) sb.append(e.name).append('\n')
        }
        if (s.capped) warnings += "Entry scan stopped early (entry or read limit reached)"
        if (s.truncated) warnings += "Archive appears truncated"
        if (s.badChecksum) warnings += "A header failed checksum validation; listing stopped there"
        return InspectionResult("tar", "TAR archive", "TAR archive (${s.format}), ${if (s.capped) "at least " else ""}${s.total} entries", facts, warnings, sb.toString().take(FormatInspector.MAX_TEXT_CHARS).ifEmpty { null })
    }
}
