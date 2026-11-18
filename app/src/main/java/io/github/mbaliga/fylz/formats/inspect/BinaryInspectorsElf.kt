package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** ELF (Executable and Linkable Format): class, endianness, ABI, type, machine, segments, dynamic libs, build-id. */
object BinaryElfInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("elf")

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = binSafe { run(source) }

    private class Ph(val type: Long, val flags: Long, val off: Long, val vaddr: Long, val filesz: Long)
    private class Sh(val name: Long, val type: Long, val off: Long, val size: Long)

    private val TYPES = mapOf(0 to "no type", 1 to "relocatable object", 2 to "executable", 3 to "shared object", 4 to "core dump")
    private val OSABI = mapOf(
        0 to "UNIX System V", 1 to "HP-UX", 2 to "NetBSD", 3 to "GNU/Linux", 6 to "Solaris", 7 to "AIX",
        8 to "IRIX", 9 to "FreeBSD", 10 to "Tru64", 12 to "OpenBSD", 13 to "OpenVMS", 64 to "ARM EABI",
        97 to "ARM", 255 to "standalone",
    )
    private val MACHINES = mapOf(
        2 to "SPARC", 3 to "x86", 4 to "Motorola 68000", 8 to "MIPS", 20 to "PowerPC", 21 to "PowerPC64",
        22 to "S/390", 40 to "ARM", 42 to "SuperH", 43 to "SPARC V9", 50 to "IA-64", 62 to "x86-64",
        94 to "Xtensa", 164 to "Qualcomm Hexagon", 183 to "AArch64", 224 to "AMD GPU", 243 to "RISC-V",
        247 to "Linux BPF", 258 to "LoongArch",
    )
    private val PTYPES = mapOf(
        0L to "NULL", 1L to "LOAD", 2L to "DYNAMIC", 3L to "INTERP", 4L to "NOTE", 5L to "SHLIB", 6L to "PHDR",
        7L to "TLS", 0x6474e550L to "GNU_EH_FRAME", 0x6474e551L to "GNU_STACK", 0x6474e552L to "GNU_RELRO",
        0x6474e553L to "GNU_PROPERTY",
    )

    private fun typeName(t: Int): String = TYPES[t] ?: when (t) {
        in 0xFE00..0xFEFF -> "OS-specific type 0x${Integer.toHexString(t)}"
        in 0xFF00..0xFFFF -> "processor-specific type 0x${Integer.toHexString(t)}"
        else -> "unknown type 0x${Integer.toHexString(t)}"
    }

    private fun machineName(m: Int) = MACHINES[m] ?: "machine 0x${Integer.toHexString(m)}"

    private fun run(source: ByteSource): InspectionResult? {
        val rd = BinReader(source)
        val h = rd.read(0, 64)
        if (h.size < 20 || !Bytes.startsWith(h, "\u007FELF")) return null
        val cls = Bytes.u8(h, 4)
        val data = Bytes.u8(h, 5)
        if ((cls != 1 && cls != 2) || (data != 1 && data != 2)) return null
        val is64 = cls == 2
        val e = BinEndian(data == 1)
        val facts = ArrayList<Fact>()
        val warnings = ArrayList<String>()

        val type = e.u16(h, 16)
        val machine = e.u16(h, 18)
        val osabi = Bytes.u8(h, 7)
        val bits = if (is64) "64-bit" else "32-bit"
        val endian = if (data == 1) "LSB (little-endian)" else "MSB (big-endian)"
        facts += Fact("Class", bits, "Header")
        facts += Fact("Byte order", endian, "Header")
        facts += Fact("OS ABI", OSABI[osabi] ?: "unknown (0x${Integer.toHexString(osabi)})", "Header")
        facts += Fact("ABI version", Bytes.u8(h, 8).toString(), "Header")
        facts += Fact("Type", typeName(type), "Header")
        facts += Fact("Machine", machineName(machine), "Header")
        val title = "ELF $bits ${if (data == 1) "LSB" else "MSB"} ${typeName(type)}, ${machineName(machine)}"

        val hdrLen = if (is64) 64 else 52
        if (h.size < hdrLen) {
            warnings += "ELF header is truncated (${h.size} of $hdrLen bytes); only the identification fields were read"
            return InspectionResult("elf", title, "$bits ${machineName(machine)} ${typeName(type)}", facts, warnings)
        }

        val entry: Long; val phoff: Long; val shoff: Long; val flags: Long
        val phentsize: Int; val phnum: Int; val shentsize: Int; val shnum: Int; val shstrndx: Int
        if (is64) {
            entry = e.u64(h, 24); phoff = e.u64(h, 32); shoff = e.u64(h, 40); flags = e.u32(h, 48)
            phentsize = e.u16(h, 54); phnum = e.u16(h, 56); shentsize = e.u16(h, 58); shnum = e.u16(h, 60); shstrndx = e.u16(h, 62)
        } else {
            entry = e.u32(h, 24); phoff = e.u32(h, 28); shoff = e.u32(h, 32); flags = e.u32(h, 36)
            phentsize = e.u16(h, 42); phnum = e.u16(h, 44); shentsize = e.u16(h, 46); shnum = e.u16(h, 48); shstrndx = e.u16(h, 50)
        }
        facts += Fact("Entry point", BinFmt.hex(entry), "Header")
        facts += Fact("Flags", BinFmt.hex(flags), "Header")

        // ---- program headers
        val phs = ArrayList<Ph>()
        val phMin = if (is64) 56 else 32
        if (phnum > 0 && phoff > 0) {
            if (phentsize < phMin || phentsize > 1024) {
                warnings += "Unusual program header entry size ($phentsize); program headers not read"
            } else {
                val n = minOf(phnum, MAX_PH)
                if (phnum > MAX_PH) warnings += "Only the first $MAX_PH of $phnum program headers were read"
                val b = rd.read(phoff, n.toLong() * phentsize)
                if (b.size < n * phentsize) warnings += "Program header table extends past the end of the file"
                for (i in 0 until minOf(n, b.size / phentsize)) {
                    val o = i * phentsize
                    phs += if (is64) Ph(e.u32(b, o), e.u32(b, o + 4), e.u64(b, o + 8), e.u64(b, o + 16), e.u64(b, o + 32))
                    else Ph(e.u32(b, o), e.u32(b, o + 24), e.u32(b, o + 4), e.u32(b, o + 8), e.u32(b, o + 16))
                }
            }
        }
        facts += Fact("Program headers", phnum.toString(), "Program headers")
        if (phs.isNotEmpty()) {
            val counts = LinkedHashMap<String, Int>()
            for (p in phs) counts.merge(PTYPES[p.type] ?: "0x${java.lang.Long.toHexString(p.type)}", 1, Int::plus)
            facts += Fact("Segment types", counts.entries.joinToString(", ") { if (it.value > 1) "${it.key} x${it.value}" else it.key }, "Program headers")
            val stack = phs.firstOrNull { it.type == 0x6474e551L }
            if (stack != null) {
                facts += Fact("Stack", if (stack.flags and 1L != 0L) "executable" else "non-executable", "Program headers")
            }
        }

        // ---- interpreter
        var hasInterp = false
        phs.firstOrNull { it.type == 3L }?.let { p ->
            hasInterp = true
            if (rd.inFile(p.off, 0)) {
                val s = rd.cstr(p.off, minOf(p.filesz.coerceAtLeast(0L), 256L).toInt())
                if (!s.isNullOrEmpty()) facts += Fact("Interpreter", s, "Dynamic")
            }
        }

        // ---- dynamic section
        val needed = ArrayList<String>()
        var soname: String? = null
        var rpath: String? = null
        var runpath: String? = null
        val dyn = phs.firstOrNull { it.type == 2L }
        if (dyn != null && rd.inFile(dyn.off, 0)) {
            val entSz = if (is64) 16 else 8
            val d = rd.read(dyn.off, minOf(dyn.filesz.coerceAtLeast(0L), 65536L))
            var strtab = -1L
            val neededOffs = ArrayList<Long>()
            var sonameOff = -1L; var rpathOff = -1L; var runpathOff = -1L
            for (i in 0 until minOf(d.size / entSz, 4096)) {
                val tag = if (is64) e.u64(d, i * 16) else e.u32(d, i * 8)
                val v = if (is64) e.u64(d, i * 16 + 8) else e.u32(d, i * 8 + 4)
                if (tag == 0L) break
                when (tag) {
                    1L -> if (neededOffs.size < MAX_NEEDED) neededOffs += v
                    5L -> strtab = v
                    14L -> sonameOff = v
                    15L -> rpathOff = v
                    29L -> runpathOff = v
                }
            }
            if (neededOffs.isNotEmpty() || sonameOff >= 0 || rpathOff >= 0 || runpathOff >= 0) {
                val base = if (strtab >= 0) vaddrToOffset(phs, strtab) else -1L
                if (base < 0) {
                    warnings += "Dynamic string table could not be located; library names are unavailable"
                } else {
                    fun str(o: Long): String? = if (o in 0..0xFFFFFFFFL) rd.cstr(base + o, 256) else null
                    for (o in neededOffs) str(o)?.let { if (it.isNotEmpty()) needed += it }
                    soname = if (sonameOff >= 0) str(sonameOff) else null
                    rpath = if (rpathOff >= 0) str(rpathOff) else null
                    runpath = if (runpathOff >= 0) str(runpathOff) else null
                }
            }
            soname?.let { facts += Fact("SONAME", it, "Dynamic") }
            rpath?.let { facts += Fact("RPATH", it, "Dynamic") }
            runpath?.let { facts += Fact("RUNPATH", it, "Dynamic") }
            if (needed.isNotEmpty()) {
                facts += Fact("Needed libraries", needed.size.toString(), "Dynamic")
                for (n in needed) facts += Fact("NEEDED", n, "Dynamic")
            }
        }
        val linkage = when {
            dyn != null || hasInterp -> "dynamic"
            phs.isNotEmpty() -> "static (no dynamic section)"
            else -> null
        }
        linkage?.let { facts += Fact("Linking", it, "Dynamic") }

        // ---- section headers
        val secs = ArrayList<Sh>()
        var sectionCount = shnum
        val shMin = if (is64) 64 else 40
        var sectionsReadable = false
        var sectionsPartial = false
        if (shoff > 0 && shentsize >= shMin && shentsize <= 1024) {
            if (shnum == 0) {
                val s0 = rd.read(shoff, shentsize.toLong())
                if (s0.size >= shMin) sectionCount = (if (is64) e.u64(s0, 32) else e.u32(s0, 20)).coerceIn(0L, 1_000_000L).toInt()
            }
            val n = minOf(sectionCount, MAX_SECTIONS, MAX_SH_BYTES / shentsize)
            if (sectionCount > n) { warnings += "Only the first $n of $sectionCount sections were read"; sectionsPartial = true }
            if (n > 0) {
                val b = rd.read(shoff, n.toLong() * shentsize)
                if (b.size < n * shentsize) warnings += "Section header table extends past the end of the file"
                for (i in 0 until minOf(n, b.size / shentsize)) {
                    val o = i * shentsize
                    secs += if (is64) Sh(e.u32(b, o), e.u32(b, o + 4), e.u64(b, o + 24), e.u64(b, o + 32))
                    else Sh(e.u32(b, o), e.u32(b, o + 4), e.u32(b, o + 16), e.u32(b, o + 20))
                }
                sectionsReadable = secs.isNotEmpty()
            }
        } else if (shoff > 0 && shnum > 0) {
            warnings += "Unusual section header entry size ($shentsize); section headers not read"
        }
        val secNames = ArrayList<String>()
        var buildId: String? = null
        if (sectionsReadable) {
            val strSec = if (shstrndx != 0xFFFF && shstrndx < secs.size) secs[shstrndx] else null
            val strBytes = if (strSec != null && rd.inFile(strSec.off, 0)) rd.read(strSec.off, minOf(strSec.size.coerceAtLeast(0L), 65536L)) else ByteArray(0)
            fun nm(o: Long): String {
                if (o < 0 || o >= strBytes.size) return ""
                var end = o.toInt()
                while (end < strBytes.size && strBytes[end].toInt() != 0 && end - o < 128) end++
                return BinFmt.clean(String(strBytes, o.toInt(), end - o.toInt(), Charsets.ISO_8859_1))
            }
            for (s in secs) secNames += nm(s.name)
            val hasSymtab = secs.any { it.type == 2L }
            val hasDebug = secNames.any { it.startsWith(".debug") }
            facts += Fact("Section headers", sectionCount.toString(), "Sections")
            if (strBytes.isNotEmpty()) {
                facts += Fact("Sections", BinFmt.list(secNames.filter { it.isNotEmpty() }, 48), "Sections")
            }
            val complete = !sectionsPartial && secs.size >= sectionCount
            facts += Fact("Symbol table (.symtab)", if (hasSymtab) "present" else if (complete) "absent" else "unknown (section table not fully read)", "Sections")
            facts += Fact("Stripped", if (hasSymtab) "no" else if (complete) "yes (no .symtab)" else "unknown (section table not fully read)", "Sections")
            facts += Fact("Debug info", if (hasDebug) "present (.debug_* sections)" else if (complete) "none found" else "unknown (section table not fully read)", "Sections")
            if (strBytes.isNotEmpty()) {
                val i = secNames.indexOf(".note.gnu.build-id")
                if (i >= 0 && secs[i].type == 7L && rd.inFile(secs[i].off, 0)) {
                    buildId = parseBuildId(rd.read(secs[i].off, minOf(secs[i].size.coerceAtLeast(0L), 4096L)), e)
                }
            }
        } else {
            facts += Fact("Stripped", "unknown (no readable section headers)", "Sections")
        }

        // ---- notes: GNU build-id
        if (buildId == null) {
            for (p in phs) {
                if (p.type != 4L || !rd.inFile(p.off, 0)) continue
                val id = parseBuildId(rd.read(p.off, minOf(p.filesz.coerceAtLeast(0L), 4096L)), e)
                if (id != null) { buildId = id; break }
            }
        }
        buildId?.let { facts += Fact("Build ID", it, "Notes") }

        val summary = buildString {
            append(bits).append(' ').append(if (data == 1) "little-endian" else "big-endian").append(", ")
            append(machineName(machine)).append(' ').append(typeName(type))
            linkage?.let { append(", ").append(it.substringBefore(' ')).append(" linking") }
        }
        return InspectionResult("elf", title, summary, facts, warnings)
    }

    private const val MAX_PH = 128
    private const val MAX_SECTIONS = 65536
    private const val MAX_SH_BYTES = 8 * 1024 * 1024
    private const val MAX_NEEDED = 256

    private fun vaddrToOffset(phs: List<Ph>, v: Long): Long {
        for (p in phs) {
            if (p.type != 1L || p.filesz <= 0) continue
            if (v >= p.vaddr && v - p.vaddr < p.filesz) return p.off + (v - p.vaddr)
        }
        return -1L
    }

    private fun parseBuildId(b: ByteArray, e: BinEndian): String? {
        var p = 0L
        var guard = 0
        while (p + 12 <= b.size && guard++ < 64) {
            val pi = p.toInt()
            val nsz = e.u32(b, pi); val dsz = e.u32(b, pi + 4); val t = e.u32(b, pi + 8)
            if (nsz > b.size || dsz > b.size) return null
            val nameStart = p + 12
            val descStart = nameStart + ((nsz + 3) and 3L.inv())
            val next = descStart + ((dsz + 3) and 3L.inv())
            if (descStart + dsz > b.size) return null
            if (t == 3L && nsz == 4L && Bytes.startsWith(b, "GNU", nameStart.toInt()) && dsz in 1..64) {
                return BinFmt.hex(b.copyOfRange(descStart.toInt(), (descStart + dsz).toInt()))
            }
            p = next
        }
        return null
    }
}
