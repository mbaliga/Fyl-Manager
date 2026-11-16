package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** Bounded big-endian (Java edition) NBT parser that records only the root compound's direct children. */
internal class FgNbt(private val b: ByteArray) {
    class Entry(val name: String, val type: Int, val summary: String)

    var p = 0
    var ok = true
    var rootName: String? = null
    val entries = ArrayList<Entry>()
    var steps = 0
    var truncated = false
    var children = 0
    var dataLevelName: String? = null
    private var currentTop = ""

    private fun need(n: Long): Boolean {
        if (n < 0 || p + n > b.size) { ok = false; truncated = true; return false }
        return true
    }
    private fun u8(): Int { if (!need(1)) return 0; return b[p++].toInt() and 0xFF }
    private fun u16(): Int { if (!need(2)) return 0; val v = Bytes.u16be(b, p); p += 2; return v }
    private fun i32(): Int { if (!need(4)) return 0; val v = Bytes.u32be(b, p).toInt(); p += 4; return v }
    private fun i64(): Long { if (!need(8)) return 0; val v = Bytes.u64be(b, p); p += 8; return v }
    private fun str(): String {
        val n = u16()
        if (!need(n.toLong())) return ""
        val s = decodeModifiedUtf8(b, p, n)
        p += n
        return Fg.clean(s)
    }

    private fun decodeModifiedUtf8(a: ByteArray, off: Int, len: Int): String {
        val sb = StringBuilder()
        var i = off
        val end = off + len
        while (i < end) {
            val c = a[i].toInt() and 0xFF
            when {
                c < 0x80 -> { sb.append(c.toChar()); i++ }
                c and 0xE0 == 0xC0 && i + 1 < end -> { sb.append((((c and 0x1F) shl 6) or (a[i + 1].toInt() and 0x3F)).toChar()); i += 2 }
                c and 0xF0 == 0xE0 && i + 2 < end -> {
                    sb.append((((c and 0x0F) shl 12) or ((a[i + 1].toInt() and 0x3F) shl 6) or (a[i + 2].toInt() and 0x3F)).toChar()); i += 3
                }
                else -> { sb.append('?'); i++ }
            }
        }
        return sb.toString()
    }

    fun typeName(t: Int) = when (t) {
        1 -> "byte"; 2 -> "short"; 3 -> "int"; 4 -> "long"; 5 -> "float"; 6 -> "double"; 7 -> "byte[]"
        8 -> "string"; 9 -> "list"; 10 -> "compound"; 11 -> "int[]"; 12 -> "long[]"; else -> "tag $t"
    }

    /** Reads one payload, returning a short summary. Skips content deeper than [depth] limits. */
    private fun payload(t: Int, depth: Int): String {
        if (!ok || ++steps > 200_000) { ok = false; return "" }
        return when (t) {
            1 -> u8().toByte().toString()
            2 -> u16().toShort().toString()
            3 -> i32().toString()
            4 -> i64().toString()
            5 -> java.lang.Float.intBitsToFloat(i32()).toString()
            6 -> java.lang.Double.longBitsToDouble(i64()).toString()
            7 -> { val n = i32(); if (n < 0 || !need(n.toLong())) { ok = false; "" } else { p += n; "$n bytes" } }
            8 -> str().let { if (it.length > 200) it.take(200) + "..." else it }
            9 -> {
                val et = u8()
                val n = i32()
                if (n < 0 || et > 12 || (et == 0 && n > 0)) { ok = false; "" }
                else {
                    if (depth > 24) { ok = false; return "" }
                    var i = 0
                    while (ok && i < n) {
                        // fixed-size element types can be skipped arithmetically
                        val fixed = when (et) { 1 -> 1; 2 -> 2; 3, 5 -> 4; 4, 6 -> 8; else -> 0 }
                        if (fixed > 0) {
                            val total = fixed.toLong() * (n - i)
                            if (!need(total)) break
                            p += total.toInt(); i = n
                        } else { payload(et, depth + 1); i++ }
                    }
                    "$n ${typeName(et)} element(s)"
                }
            }
            10 -> {
                if (depth > 24) { ok = false; return "" }
                var count = 0
                while (ok) {
                    val ct = u8()
                    if (!ok) break
                    if (ct == 0) break
                    val cname = str()
                    val sum = payload(ct, depth + 1)
                    if (depth == 2 && currentTop == "Data" && ct == 8 && cname == "LevelName" && ok && dataLevelName == null) dataLevelName = sum
                    count++
                }
                "$count entries"
            }
            11 -> { val n = i32(); if (n < 0 || !need(n * 4L)) { ok = false; "" } else { p += n * 4; "$n ints" } }
            12 -> { val n = i32(); if (n < 0 || !need(n * 8L)) { ok = false; "" } else { p += n * 8; "$n longs" } }
            else -> { ok = false; "" }
        }
    }

    /** Parses the root tag; fills [rootName] and [entries]. Returns false if the root is not a compound. */
    fun parse(): Boolean {
        if (u8() != 10) return false
        rootName = str()
        if (!ok) return true
        var guard = 0
        while (ok && guard++ < 100_000) {
            val ct = u8()
            if (!ok || ct == 0) break
            val name = str()
            if (!ok) break
            currentTop = name
            children++
            val s = payload(ct, 1)
            if (ok || ct == 10) { if (entries.size < 500) entries.add(Entry(name, ct, s)) }
        }
        return true
    }
}

internal object FgNbtLoad {
    class Loaded(val data: ByteArray, val container: String, val capped: Boolean, val error: Boolean)

    fun load(raw: ByteArray, cap: Int): Loaded? {
        if (raw.size < 2) return null
        if (Bytes.u8(raw, 0) == 0x1F && Bytes.u8(raw, 1) == 0x8B) {
            val off = Fg.gzipBodyOffset(raw)
            if (off < 0) return null
            val r = Fg.inflate(raw, off, raw.size - off, cap, true)
            return Loaded(r.bytes, "gzip", !r.complete && !r.error, r.error)
        }
        val cmf = Bytes.u8(raw, 0)
        if (cmf and 0x0F == 8 && ((cmf shl 8) or Bytes.u8(raw, 1)) % 31 == 0 && cmf shr 4 <= 7) {
            val r = Fg.inflate(raw, 0, raw.size, cap, false)
            return Loaded(r.bytes, "zlib", !r.complete && !r.error, r.error)
        }
        if (Bytes.u8(raw, 0) == 10) return Loaded(raw, "raw (uncompressed)", false, false)
        return null
    }
}

/** Minecraft (Java edition) NBT files: level.dat, structure files, player data. */
object GameNbtInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("nbt")
    private const val CAP = 4 * 1024 * 1024

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = fgSafe { run(source, fileName) }

    private fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val io = FgIo(source)
        val raw = io.read(0, CAP.toLong())
        // Bedrock level.dat: 8-byte header then little-endian NBT. Detect but do not parse.
        if (raw.size > 9 && Bytes.u8(raw, 8) == 10 && Bytes.u32le(raw, 4) == raw.size - 8L && Bytes.u32le(raw, 0) in 1..20) {
            return InspectionResult("nbt", fileName ?: "NBT", "Minecraft Bedrock NBT (little-endian)",
                listOf(Fact("Edition", "Bedrock (little-endian NBT, storage version ${Bytes.u32le(raw, 0)})", "Header")),
                listOf("Bedrock little-endian NBT is not parsed"))
        }
        val l = FgNbtLoad.load(raw, CAP) ?: return null
        val nbt = FgNbt(l.data)
        if (!nbt.parse()) return null
        if (l.container == "raw (uncompressed)" && nbt.children == 0) return null
        val warnings = ArrayList<String>()
        val facts = ArrayList<Fact>()
        facts.add(Fact("Compression", l.container, "Header"))
        facts.add(Fact("Root tag", "compound named \"${nbt.rootName ?: ""}\"", "Header"))
        facts.add(Fact("Top-level entries", nbt.entries.size.toString() + if (!nbt.ok) " (parsing stopped early)" else "", "Header"))
        if (l.container != "raw (uncompressed)") facts.add(Fact("Decompressed size", Bytes.humanSize(l.data.size.toLong()), "Header"))
        for (e in nbt.entries.take(60)) {
            val simple = e.type in 1..6 || e.type == 8
            facts.add(Fact(e.name.ifEmpty { "(empty)" }, if (simple) "${nbt.typeName(e.type)}: ${e.summary}" else "${nbt.typeName(e.type)}: ${e.summary}", "Entries"))
        }
        if (nbt.entries.size > 60) warnings.add("${nbt.entries.size - 60} more top-level entries not listed")
        if (l.capped) warnings.add("decompression stopped at ${Bytes.humanSize(CAP.toLong())}")
        if (l.error) warnings.add("compressed stream is damaged")
        if (!nbt.ok && !l.capped && !l.error) warnings.add("NBT data is truncated or malformed")
        val levelName = nbt.entries.firstOrNull { it.name == "LevelName" && it.type == 8 }?.summary
            ?: nbt.dataLevelName?.ifEmpty { null }
        val title = levelName ?: nbt.rootName?.ifEmpty { null } ?: fileName ?: "NBT"
        return InspectionResult("nbt", title, "Minecraft NBT (${l.container}), ${nbt.entries.size} top-level entries", facts, warnings)
    }
}

/** Minecraft Anvil / region files (.mca). */
object GameMcaInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("mca")

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = fgSafe { run(source, fileName) }

    private fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val io = FgIo(source)
        val h = io.read(0, 8192)
        if (h.size < 8192) return null
        var chunks = 0
        var outOfRange = 0
        var firstOff = -1L
        var minT = Long.MAX_VALUE
        var maxT = 0L
        var maxSector = 0L
        for (i in 0 until 1024) {
            val e = Bytes.u32be(h, i * 4)
            val off = e shr 8
            val cnt = e and 0xFF
            if (e == 0L) continue
            chunks++
            if (off < 2 || cnt == 0L || !io.inFile(off * 4096, cnt * 4096L)) outOfRange++
            else if (firstOff < 0) firstOff = off
            maxSector = maxOf(maxSector, off + cnt)
            val t = Bytes.u32be(h, 4096 + i * 4)
            if (t != 0L) { minT = minOf(minT, t); maxT = maxOf(maxT, t) }
        }
        val nameMatches = Regex("""r\.(-?\d+)\.(-?\d+)\.mc[a]?$""").containsMatchIn(fileName ?: "")
        // No magic number exists: require a plausible header (most chunk entries valid, or an empty region with a region file name).
        if (chunks == 0) { if (!nameMatches) return null }
        else if (outOfRange * 2 > chunks) return null
        val facts = ArrayList<Fact>()
        val warnings = ArrayList<String>()
        Regex("""r\.(-?\d+)\.(-?\d+)\.mc[a]?$""").find(fileName ?: "")?.let {
            facts.add(Fact("Region coordinates", "x=${it.groupValues[1]}, z=${it.groupValues[2]}", "Header"))
        }
        facts.add(Fact("Chunks present", "$chunks of 1024", "Header"))
        if (minT != Long.MAX_VALUE) {
            facts.add(Fact("Oldest chunk timestamp", Fg.time(minT), "Header"))
            facts.add(Fact("Newest chunk timestamp", Fg.time(maxT), "Header"))
        }
        facts.add(Fact("Sectors used (4 KiB)", maxSector.toString(), "Header"))
        if (outOfRange > 0) warnings.add("$outOfRange chunk location(s) point outside the file or into the header")
        if (io.size % 4096 != 0L) warnings.add("file size is not a multiple of 4096 bytes")
        if (firstOff >= 0) {
            val ch = io.read(firstOff * 4096, 5)
            if (ch.size == 5) {
                val len = Bytes.u32be(ch, 0)
                val ctype = Bytes.u8(ch, 4)
                val external = ctype and 0x80 != 0
                val ct = ctype and 0x7F
                val name = when (ct) { 1 -> "gzip"; 2 -> "zlib"; 3 -> "uncompressed"; 4 -> "LZ4"; else -> "unknown ($ctype)" }
                facts.add(Fact("First chunk compression", name + if (external) " (stored externally)" else "", "Chunks"))
                if (!external && len in 2..(2 * 1024 * 1024) && (ct == 1 || ct == 2 || ct == 3)) {
                    val raw = io.read(firstOff * 4096 + 5, len - 1)
                    val data = when (ct) {
                        1 -> FgNbtLoad.load(raw, 4 * 1024 * 1024)?.takeIf { it.container == "gzip" }?.data
                        2 -> Fg.inflate(raw, 0, raw.size, 4 * 1024 * 1024, false).bytes
                        else -> raw
                    }
                    if (data != null && data.isNotEmpty()) {
                        val nbt = FgNbt(data)
                        if (nbt.parse()) {
                            nbt.entries.firstOrNull { it.name == "DataVersion" && it.type == 3 }?.let {
                                facts.add(Fact("First chunk DataVersion", it.summary, "Chunks"))
                            }
                            nbt.entries.firstOrNull { it.name == "Status" && it.type == 8 }?.let {
                                facts.add(Fact("First chunk status", it.summary, "Chunks"))
                            }
                            facts.add(Fact("First chunk keys", Fg.list(nbt.entries.map { it.name }, 20), "Chunks"))
                        }
                    }
                }
            }
        }
        return InspectionResult("mca", fileName ?: "Minecraft region", "Minecraft region file, $chunks chunks", facts, warnings)
    }
}
