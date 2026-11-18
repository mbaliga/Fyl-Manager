package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** Mach-O (thin) and universal ("fat") binaries. */
object BinaryMachOInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("macho")

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = binSafe { run(source) }

    private const val MAX_ARCHS = 32
    private const val MAX_DETAIL_SLICES = 4
    private const val MAX_CMDS = 2048
    private const val MAX_LIST = 128

    private fun cpuName(t: Long): String {
        val abi64 = t and 0x01000000L != 0L
        val abi6432 = t and 0x02000000L != 0L
        return when (t and 0xFFFFFFL) {
            7L -> if (abi64) "x86-64" else "x86"
            12L -> if (abi64) "ARM64" else if (abi6432) "ARM64_32" else "ARM"
            18L -> if (abi64) "PowerPC 64" else "PowerPC"
            6L -> "Motorola 68k"
            14L -> "SPARC"
            else -> "cpu type ${BinFmt.hex(t)}"
        }
    }

    private val FILETYPES = mapOf(
        1L to "object file", 2L to "executable", 3L to "fixed VM shared library", 4L to "core dump", 5L to "preloaded executable",
        6L to "dynamic library", 7L to "dynamic linker", 8L to "bundle", 9L to "dylib stub", 10L to "debug symbols (dSYM)",
        11L to "kernel extension", 12L to "file set",
    )
    private val PLATFORMS = mapOf(
        1L to "macOS", 2L to "iOS", 3L to "tvOS", 4L to "watchOS", 5L to "bridgeOS", 6L to "Mac Catalyst", 7L to "iOS simulator",
        8L to "tvOS simulator", 9L to "watchOS simulator", 10L to "DriverKit", 11L to "visionOS", 12L to "visionOS simulator",
    )

    private class Thin(val title: String)

    private fun run(source: ByteSource): InspectionResult? {
        val rd = BinReader(source)
        val h = rd.read(0, 8)
        if (h.size < 8) return null
        val magic = Bytes.u32be(h, 0)
        val facts = ArrayList<Fact>()
        val warnings = ArrayList<String>()

        if (magic == 0xFEEDFACEL || magic == 0xCEFAEDFEL || magic == 0xFEEDFACFL || magic == 0xCFFAEDFEL) {
            val t = thin(rd, 0, rd.size, "Mach-O", facts, warnings) ?: return null
            return InspectionResult("macho", t.title, t.title, facts, warnings)
        }
        if (magic != 0xCAFEBABEL && magic != 0xCAFEBABFL) return null
        val is64 = magic == 0xCAFEBABFL
        val n = Bytes.u32be(h, 4)
        // CAFEBABE is shared with Java class files; a fat header has a small architecture count.
        if (n < 1 || n > MAX_ARCHS) return null
        val entSz = if (is64) 32 else 20
        val tab = rd.read(8, n * entSz)
        if (tab.size < n * entSz) return null
        class Slice(val cpu: Long, val sub: Long, val off: Long, val size: Long)
        val slices = ArrayList<Slice>()
        for (i in 0 until n.toInt()) {
            val o = i * entSz
            slices += if (is64) Slice(Bytes.u32be(tab, o), Bytes.u32be(tab, o + 4), Bytes.u64be(tab, o + 8), Bytes.u64be(tab, o + 16))
            else Slice(Bytes.u32be(tab, o), Bytes.u32be(tab, o + 4), Bytes.u32be(tab, o + 8), Bytes.u32be(tab, o + 12))
        }
        // The first slice must hold a Mach-O header, otherwise this is not a universal binary (e.g. a class file).
        val first = rd.read(slices[0].off, 4)
        if (first.size < 4) return null
        val fm = Bytes.u32be(first, 0)
        if (fm != 0xFEEDFACEL && fm != 0xCEFAEDFEL && fm != 0xFEEDFACFL && fm != 0xCFFAEDFEL) return null

        facts += Fact("Architectures", n.toString(), "Universal binary")
        for ((i, s) in slices.withIndex()) {
            val fits = rd.inFile(s.off, s.size)
            facts += Fact("Slice ${i + 1}", "${cpuName(s.cpu)}, offset ${BinFmt.hex(s.off)}, size ${Bytes.humanSize(s.size.coerceAtLeast(0))}" +
                (if (fits) "" else " (extends past end of file)"), "Universal binary")
            if (!fits) warnings += "Slice ${i + 1} (${cpuName(s.cpu)}) extends past the end of the file"
        }
        for ((i, s) in slices.withIndex()) {
            if (i >= MAX_DETAIL_SLICES) break
            if (!rd.inFile(s.off, 0)) continue
            thin(rd, s.off, minOf(s.size.coerceAtLeast(0), rd.size - s.off), "Slice ${i + 1} (${cpuName(s.cpu)})", facts, warnings)
        }
        val archs = slices.joinToString(", ") { cpuName(it.cpu) }
        val title = "Universal Mach-O binary ($archs)"
        return InspectionResult("macho", title, title, facts, warnings)
    }

    private fun thin(rd: BinReader, base: Long, limit: Long, grp: String, facts: MutableList<Fact>, warnings: MutableList<String>): Thin? {
        val h = rd.read(base, 32)
        if (h.size < 28) return null
        val magic = Bytes.u32be(h, 0)
        val is64 = magic == 0xFEEDFACFL || magic == 0xCFFAEDFEL
        val le = magic == 0xCEFAEDFEL || magic == 0xCFFAEDFEL
        if (!is64 && magic != 0xFEEDFACEL && magic != 0xCEFAEDFEL) return null
        val hdr = if (is64) 32 else 28
        if (h.size < hdr) return null
        val e = BinEndian(le)
        val cpu = e.u32(h, 4)
        val sub = e.u32(h, 8)
        val ftype = e.u32(h, 12)
        val ncmds = e.u32(h, 16)
        val sizeofcmds = e.u32(h, 20)
        val flags = e.u32(h, 24)
        val arch = cpuName(cpu)
        val kind = FILETYPES[ftype] ?: "file type $ftype"
        facts += Fact("Bitness", if (is64) "64-bit" else "32-bit", "$grp: header")
        facts += Fact("Byte order", if (le) "little-endian" else "big-endian", "$grp: header")
        facts += Fact("CPU", arch, "$grp: header")
        facts += Fact("CPU subtype", BinFmt.hex(sub and 0xFFFFFFL) + if (sub and 0xFF000000L != 0L) " (capability bits ${BinFmt.hex(sub and 0xFF000000L)})" else "", "$grp: header")
        facts += Fact("File type", kind, "$grp: header")
        facts += Fact("Load commands", ncmds.toString(), "$grp: header")
        facts += Fact("Load commands size", "$sizeofcmds bytes", "$grp: header")
        val fl = buildList {
            if (flags and 0x1L != 0L) add("NOUNDEFS")
            if (flags and 0x4L != 0L) add("DYLDLINK")
            if (flags and 0x80L != 0L) add("TWOLEVEL")
            if (flags and 0x20000L != 0L) add("ALLOW_STACK_EXECUTION")
            if (flags and 0x200000L != 0L) add("PIE")
            if (flags and 0x1000000L != 0L) add("NO_HEAP_EXECUTION")
        }
        facts += Fact("Flags", BinFmt.hex(flags) + if (fl.isEmpty()) "" else " (${fl.joinToString(", ")})", "$grp: header")

        val avail = limit - hdr
        val cmdBytes = if (avail <= 0 || sizeofcmds > avail) {
            warnings += "$grp: load commands extend past the available data"
            minOf(maxOf(avail, 0L), 1L shl 20)
        } else minOf(sizeofcmds, 1L shl 20)
        val b = rd.read(base + hdr, cmdBytes)
        var p = 0
        var count = 0
        val libs = ArrayList<String>()
        val rpaths = ArrayList<String>()
        val segs = ArrayList<String>()
        var uuid: String? = null
        var installName: String? = null
        var minOs: String? = null
        var codeSig = false
        var encrypted: Boolean? = null
        val limitCmds = minOf(ncmds, MAX_CMDS.toLong()).toInt()
        if (ncmds > MAX_CMDS) warnings += "$grp: only the first $MAX_CMDS of $ncmds load commands were read"

        fun lcStr(cmdStart: Int, cmdSize: Int, offField: Int): String? {
            val so = e.u32(b, cmdStart + offField)
            if (so < 8 || so >= cmdSize) return null
            val s = cmdStart + so.toInt()
            var end = s
            val stop = minOf(cmdStart + cmdSize, b.size, s + 1024)
            while (end < stop && b[end].toInt() != 0) end++
            return BinFmt.clean(String(b, s, end - s, Charsets.UTF_8))
        }
        fun verStr(v: Long) = BinFmt.packedVersion(v)

        while (count < limitCmds && p + 8 <= b.size) {
            val cmd = e.u32(b, p)
            val sz = e.u32(b, p + 4)
            if (sz < 8 || sz > b.size - p) { warnings += "$grp: malformed load command #${count + 1} (size $sz)"; break }
            val s = sz.toInt()
            when (cmd) {
                0xCL, 0x80000018L, 0x8000001FL, 0x20L, 0x80000023L -> if (s >= 24) {
                    val name = lcStr(p, s, 8)
                    val prefix = when (cmd) { 0x80000018L -> "weak: "; 0x8000001FL -> "re-export: "; 0x20L -> "lazy: "; 0x80000023L -> "upward: "; else -> "" }
                    if (name != null && libs.size < MAX_LIST) libs += prefix + name + " (current " + verStr(e.u32(b, p + 16)) + ")"
                }
                0xDL -> if (s >= 24) installName = lcStr(p, s, 8)?.let { it + " (current " + verStr(e.u32(b, p + 16)) + ")" }
                0x8000001CL -> if (s >= 12) lcStr(p, s, 8)?.let { if (rpaths.size < MAX_LIST) rpaths += it }
                0x1BL -> if (s >= 24) uuid = formatUuid(b.copyOfRange(p + 8, p + 24))
                0x24L, 0x25L, 0x2FL, 0x30L -> if (s >= 16) {
                    val plat = when (cmd) { 0x24L -> "macOS"; 0x25L -> "iOS"; 0x2FL -> "tvOS"; else -> "watchOS" }
                    minOs = "$plat ${verStr(e.u32(b, p + 8))} (SDK ${verStr(e.u32(b, p + 12))})"
                }
                0x32L -> if (s >= 24) {
                    val plat = e.u32(b, p + 8)
                    minOs = "${PLATFORMS[plat] ?: "platform $plat"} ${verStr(e.u32(b, p + 12))} (SDK ${verStr(e.u32(b, p + 16))})"
                }
                0x1L, 0x19L -> if (s >= 24 && segs.size < MAX_LIST) {
                    var l = 0
                    while (l < 16 && b[p + 8 + l].toInt() != 0) l++
                    segs += BinFmt.clean(String(b, p + 8, l, Charsets.ISO_8859_1))
                }
                0x1DL -> codeSig = true
                0x21L, 0x2CL -> if (s >= 20) encrypted = e.u32(b, p + 16) != 0L
            }
            p += s
            count++
        }
        if (count < limitCmds && p + 8 > b.size && count < ncmds) warnings += "$grp: parsed $count of $ncmds load commands before running out of data"

        uuid?.let { facts += Fact("UUID", it, "$grp: identity") }
        installName?.let { facts += Fact("Install name", it, "$grp: identity") }
        minOs?.let { facts += Fact("Minimum OS", it, "$grp: identity") }
        if (segs.isNotEmpty()) facts += Fact("Segments", BinFmt.list(segs.filter { it.isNotEmpty() }, 32), "$grp: layout")
        facts += Fact("Code signature", if (codeSig) "present (not verified)" else "none found", "$grp: layout")
        encrypted?.let { facts += Fact("Encrypted (cryptid)", if (it) "yes" else "no", "$grp: layout") }
        if (rpaths.isNotEmpty()) for (r in rpaths) facts += Fact("RPATH", r, "$grp: dependencies")
        if (libs.isNotEmpty()) {
            facts += Fact("Dependencies", libs.size.toString(), "$grp: dependencies")
            for (l in libs) facts += Fact("Dylib", l, "$grp: dependencies")
        }
        return Thin("${if (is64) "64-bit" else "32-bit"} Mach-O $kind ($arch)")
    }

    private fun formatUuid(b: ByteArray): String {
        val h = BinFmt.hex(b).uppercase()
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }
}
