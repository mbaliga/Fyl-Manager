package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** id Software Quake PAK archives. */
object GamePakInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("pak")
    private const val ENTRY = 64
    private const val MAX_ENTRIES = 16384

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = fgSafe { run(source, fileName) }

    private fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val io = FgIo(source)
        val h = io.read(0, 12)
        if (h.size < 12 || !Bytes.startsWith(h, "PACK")) return null
        val dirOff = Bytes.u32le(h, 4)
        val dirLen = Bytes.u32le(h, 8)
        val warnings = ArrayList<String>()
        val facts = ArrayList<Fact>()
        val count = dirLen / ENTRY
        facts.add(Fact("Directory offset", dirOff.toString(), "Header"))
        facts.add(Fact("Directory length", dirLen.toString(), "Header"))
        facts.add(Fact("Entries", count.toString(), "Header"))
        if (dirLen % ENTRY != 0L) warnings.add("directory length is not a multiple of $ENTRY bytes")
        if (!io.inFile(dirOff, dirLen)) warnings.add("directory extends beyond the end of the file")
        val use = minOf(count, MAX_ENTRIES.toLong()).toInt()
        val dir = if (io.inFile(dirOff, 0)) io.read(dirOff, use.toLong() * ENTRY) else ByteArray(0)
        val names = ArrayList<String>()
        val ext = java.util.TreeMap<String, Int>()
        var total = 0L
        var outside = 0
        val read = minOf(use, dir.size / ENTRY)
        for (i in 0 until read) {
            val o = i * ENTRY
            val name = Fg.cstr(dir, o, 56)
            val pos = Bytes.u32le(dir, o + 56)
            val len = Bytes.u32le(dir, o + 60)
            total += len
            if (pos < 12 || !io.inFile(pos, len)) outside++
            if (names.size < 40) names.add(name)
            val dot = name.lastIndexOf('.')
            val e = if (dot >= 0 && dot < name.length - 1) name.substring(dot + 1).lowercase() else "(none)"
            if (ext.size < 64 || e in ext) ext[e] = (ext[e] ?: 0) + 1
        }
        if (read > 0) {
            facts.add(Fact("Total stored size" + if (read < count) " (first $read entries)" else "", Bytes.humanSize(total), "Contents"))
            facts.add(Fact("File types", ext.entries.sortedByDescending { it.value }.take(12).joinToString(", ") { "${it.key} x${it.value}" }, "Contents"))
            facts.add(Fact("First entries", Fg.list(names, 40), "Contents"))
        }
        if (outside > 0) warnings.add("$outside entries point outside the file")
        if (count > MAX_ENTRIES) warnings.add("only the first $MAX_ENTRIES of $count entries were read")
        return InspectionResult("pak", fileName ?: "Quake PAK", "Quake PAK archive, $count entries", facts, warnings)
    }
}

/** Doom WAD files (IWAD/PWAD) and Quake/Half-Life texture WADs (WAD2/WAD3). */
object GameWadInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("wad")
    private const val MAX_LUMPS = 32768

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = fgSafe { run(source, fileName) }

    private val MAP_RE = Regex("""^(E[1-9]M[1-9]|MAP[0-9]{2})$""")

    private fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val io = FgIo(source)
        val h = io.read(0, 12)
        if (h.size < 12) return null
        val magic = Bytes.ascii(h, 0, 4)
        val entry = when (magic) { "IWAD", "PWAD" -> 16; "WAD2", "WAD3" -> 32; else -> return null }
        val num = Bytes.u32le(h, 4).toInt().toLong() // treat as signed
        val dirOff = Bytes.u32le(h, 8)
        val warnings = ArrayList<String>()
        val facts = ArrayList<Fact>()
        val kind = when (magic) {
            "IWAD" -> "IWAD (base game data)"
            "PWAD" -> "PWAD (patch / mod data)"
            "WAD2" -> "WAD2 (Quake texture archive)"
            else -> "WAD3 (Half-Life texture archive)"
        }
        facts.add(Fact("Type", kind, "Header"))
        if (num < 0) { warnings.add("negative lump count"); return InspectionResult("wad", fileName ?: "WAD", "WAD file", facts, warnings) }
        facts.add(Fact("Lumps", num.toString(), "Header"))
        facts.add(Fact("Directory offset", dirOff.toString(), "Header"))
        val use = minOf(num, MAX_LUMPS.toLong()).toInt()
        if (!io.inFile(dirOff, num * entry)) warnings.add("lump directory extends beyond the end of the file")
        val dir = if (io.inFile(dirOff, 0)) io.read(dirOff, use.toLong() * entry) else ByteArray(0)
        val read = dir.size / entry
        val names = ArrayList<String>()
        val maps = ArrayList<String>()
        var total = 0L
        var outside = 0
        for (i in 0 until read) {
            val o = i * entry
            val pos = Bytes.u32le(dir, o)
            val len = if (entry == 16) Bytes.u32le(dir, o + 4) else Bytes.u32le(dir, o + 4)
            val name = if (entry == 16) Fg.cstr(dir, o + 8, 8) else Fg.cstr(dir, o + 16, 16)
            total += len
            if (!io.inFile(pos, len)) outside++
            if (names.size < 60) names.add(name)
            if (entry == 16 && MAP_RE.matches(name.uppercase()) && maps.size < 64) maps.add(name.uppercase())
        }
        if (read > 0) {
            facts.add(Fact("Total lump data" + if (read < num) " (first $read)" else "", Bytes.humanSize(total), "Contents"))
            facts.add(Fact("Lump names", Fg.list(names, 60), "Contents"))
        }
        if (maps.isNotEmpty()) facts.add(Fact("Maps", "${maps.size}: " + Fg.list(maps, 32), "Contents"))
        if (outside > 0) warnings.add("$outside lump(s) point outside the file")
        if (num > MAX_LUMPS) warnings.add("only the first $MAX_LUMPS of $num lumps were read")
        return InspectionResult("wad", fileName ?: "WAD", "$magic with $num lumps", facts, warnings)
    }
}

/** Unity asset bundle headers (UnityFS / UnityWeb / UnityRaw). */
object GameUnityInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("unity3d", "bundle")

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = fgSafe { run(source, fileName) }

    private fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val io = FgIo(source)
        val b = io.read(0, 512)
        val sig = listOf("UnityFS", "UnityWeb", "UnityRaw").firstOrNull { Bytes.startsWith(b, it) && Bytes.u8(b, it.length) == 0 } ?: return null
        var p = sig.length + 1
        if (p + 4 > b.size) return null
        val ver = Bytes.u32be(b, p); p += 4
        fun cs(): String? {
            var e = p
            while (e < b.size && b[e].toInt() != 0 && e - p < 128) e++
            if (e >= b.size || b[e].toInt() != 0) return null
            val s = Fg.clean(String(b, p, e - p, Charsets.ISO_8859_1))
            p = e + 1
            return s
        }
        val minPlayer = cs()
        val engine = cs()
        val facts = ArrayList<Fact>()
        val warnings = ArrayList<String>()
        facts.add(Fact("Signature", sig, "Header"))
        facts.add(Fact("Bundle format version", ver.toString(), "Header"))
        minPlayer?.let { facts.add(Fact("Minimum player version", it, "Header")) }
        engine?.let { facts.add(Fact("Engine version", it, "Header")) }
        if (minPlayer == null || engine == null) warnings.add("version strings are truncated")
        if (sig == "UnityFS" && minPlayer != null && engine != null && p + 20 <= b.size) {
            val size = Bytes.u64be(b, p)
            val comp = Bytes.u32be(b, p + 8)
            val unc = Bytes.u32be(b, p + 12)
            val flags = Bytes.u32be(b, p + 16)
            facts.add(Fact("Bundle size (declared)", if (size < 0) "invalid (${java.lang.Long.toUnsignedString(size)})" else Bytes.humanSize(size), "Header"))
            if (size >= 0 && size != io.size) warnings.add("declared size $size differs from file size ${io.size}")
            facts.add(Fact("Block info size", "$comp compressed, $unc uncompressed", "Header"))
            val c = (flags and 0x3F).toInt()
            facts.add(Fact("Block info compression", when (c) { 0 -> "none"; 1 -> "LZMA"; 2 -> "LZ4"; 3 -> "LZ4HC"; 4 -> "LZHAM"; else -> "unknown ($c)" }, "Header"))
            facts.add(Fact("Directory info", if (flags and 0x40L != 0L) "combined with block info" else "separate", "Header"))
            facts.add(Fact("Block info location", if (flags and 0x80L != 0L) "end of file" else "after header", "Header"))
        }
        return InspectionResult("unity3d", fileName ?: "Unity bundle",
            "Unity $sig bundle" + (engine?.let { ", engine $it" } ?: ""), facts, warnings)
    }
}
