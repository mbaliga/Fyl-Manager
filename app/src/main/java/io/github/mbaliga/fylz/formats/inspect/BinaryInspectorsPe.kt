package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** Windows Portable Executable (.exe, .dll, .sys ...). */
object BinaryPeInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("exe", "dll")

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = binSafe { run(source) }

    private class Sec(val name: String, val vsize: Long, val va: Long, val rawSize: Long, val rawPtr: Long, val chars: Long)

    private val MACHINES = mapOf(
        0x14c to "x86 (i386)", 0x8664 to "x86-64 (AMD64)", 0x1c0 to "ARM", 0x1c4 to "ARM Thumb-2 (ARMNT)",
        0xaa64 to "ARM64", 0x200 to "IA-64", 0x5032 to "RISC-V 32", 0x5064 to "RISC-V 64", 0x6232 to "LoongArch 32",
        0x6264 to "LoongArch 64", 0xebc to "EFI byte code", 0x0 to "unknown / any",
    )
    private val SUBSYSTEMS = mapOf(
        0 to "unknown", 1 to "Native", 2 to "Windows GUI", 3 to "Windows console", 5 to "OS/2 console", 7 to "POSIX console",
        9 to "Windows CE GUI", 10 to "EFI application", 11 to "EFI boot service driver", 12 to "EFI runtime driver",
        13 to "EFI ROM", 14 to "Xbox", 16 to "Windows boot application",
    )
    private val CHARS = listOf(
        0x0001 to "RELOCS_STRIPPED", 0x0002 to "EXECUTABLE_IMAGE", 0x0004 to "LINE_NUMS_STRIPPED",
        0x0008 to "LOCAL_SYMS_STRIPPED", 0x0020 to "LARGE_ADDRESS_AWARE", 0x0100 to "32BIT_MACHINE",
        0x0200 to "DEBUG_STRIPPED", 0x1000 to "SYSTEM", 0x2000 to "DLL", 0x4000 to "UP_SYSTEM_ONLY",
    )
    private val DLLCHARS = listOf(
        0x0020 to "HIGH_ENTROPY_VA", 0x0040 to "DYNAMIC_BASE (ASLR)", 0x0080 to "FORCE_INTEGRITY", 0x0100 to "NX_COMPAT (DEP)",
        0x0200 to "NO_ISOLATION", 0x0400 to "NO_SEH", 0x0800 to "NO_BIND", 0x1000 to "APPCONTAINER",
        0x2000 to "WDM_DRIVER", 0x4000 to "GUARD_CF", 0x8000 to "TERMINAL_SERVER_AWARE",
    )
    private val DIRS = listOf(
        "Export", "Import", "Resource", "Exception", "Certificate", "Base relocation", "Debug", "Architecture",
        "Global pointer", "TLS", "Load config", "Bound import", "IAT", "Delay import", "CLR runtime", "Reserved",
    )

    private const val MAX_SECTIONS = 96
    private const val MAX_IMPORTS = 512

    private fun run(source: ByteSource): InspectionResult? {
        val rd = BinReader(source)
        val dos = rd.read(0, 64)
        if (dos.size < 64 || dos[0] != 'M'.code.toByte() || dos[1] != 'Z'.code.toByte()) return null
        val lfanew = Bytes.u32le(dos, 0x3C)
        if (lfanew < 64 || !rd.inFile(lfanew, 24)) return null
        val ph = rd.read(lfanew, 24L + 240L)
        if (ph.size < 24 || !Bytes.startsWith(ph, "PE\u0000\u0000")) return null

        val facts = ArrayList<Fact>()
        val warnings = ArrayList<String>()
        val machine = Bytes.u16le(ph, 4)
        val nsec = Bytes.u16le(ph, 6)
        val stamp = Bytes.u32le(ph, 8)
        val optSize = Bytes.u16le(ph, 20)
        val chars = Bytes.u16le(ph, 22)
        val isDll = chars and 0x2000 != 0

        facts += Fact("DOS header", "MZ, PE header at ${BinFmt.hex(lfanew)}", "Header")
        facts += Fact("Machine", MACHINES[machine] ?: "unknown (0x${Integer.toHexString(machine)})", "Header")
        facts += Fact("Sections", nsec.toString(), "Header")
        facts += Fact("Timestamp", if (stamp == 0L) "not set" else "${BinFmt.time(stamp)} (may be a build hash in reproducible builds)", "Header")
        facts += Fact("Characteristics", BinFmt.hex(chars.toLong()) + " " + CHARS.filter { chars and it.first != 0 }.joinToString(", ") { it.second }.let { if (it.isEmpty()) "" else "($it)" }, "Header")
        facts += Fact("Type", if (isDll) "DLL (dynamic-link library)" else "Executable image", "Header")

        // ---- optional header
        val opt = 24
        var pe32plus = false
        var subsystem = -1
        var dirs = ArrayList<LongArray>()
        var epRva = 0L
        var optOk = false
        if (optSize >= 2 && ph.size >= opt + 2) {
            val magic = Bytes.u16le(ph, opt)
            if (magic == 0x10b || magic == 0x20b) {
                pe32plus = magic == 0x20b
                val needed = if (pe32plus) 112 else 96
                if (optSize >= needed && ph.size >= opt + needed) {
                    optOk = true
                    facts += Fact("Format", if (pe32plus) "PE32+ (64-bit)" else "PE32 (32-bit)", "Optional header")
                    facts += Fact("Linker version", "${Bytes.u8(ph, opt + 2)}.${Bytes.u8(ph, opt + 3)}", "Optional header")
                    epRva = Bytes.u32le(ph, opt + 16)
                    facts += Fact("Entry point (RVA)", BinFmt.hex(epRva), "Optional header")
                    val imageBase = if (pe32plus) Bytes.u64le(ph, opt + 24) else Bytes.u32le(ph, opt + 28)
                    facts += Fact("Image base", BinFmt.hex(imageBase), "Optional header")
                    facts += Fact("OS version", "${Bytes.u16le(ph, opt + 40)}.${Bytes.u16le(ph, opt + 42)}", "Optional header")
                    facts += Fact("Subsystem version", "${Bytes.u16le(ph, opt + 48)}.${Bytes.u16le(ph, opt + 50)}", "Optional header")
                    facts += Fact("Size of image", "${Bytes.u32le(ph, opt + 56)} bytes", "Optional header")
                    facts += Fact("Checksum", BinFmt.hex(Bytes.u32le(ph, opt + 64)), "Optional header")
                    subsystem = Bytes.u16le(ph, opt + 68)
                    facts += Fact("Subsystem", SUBSYSTEMS[subsystem] ?: "unknown ($subsystem)", "Optional header")
                    val dc = Bytes.u16le(ph, opt + 70)
                    val dcs = DLLCHARS.filter { dc and it.first != 0 }.joinToString(", ") { it.second }
                    facts += Fact("DLL characteristics", BinFmt.hex(dc.toLong()) + if (dcs.isEmpty()) "" else " ($dcs)", "Optional header")
                    val nDirsOff = if (pe32plus) opt + 108 else opt + 92
                    val nDirs = minOf(Bytes.u32le(ph, nDirsOff), 16L).toInt()
                    val dirOff = nDirsOff + 4
                    for (i in 0 until nDirs) {
                        if (dirOff + i * 8 + 8 > opt + optSize || dirOff + i * 8 + 8 > ph.size) break
                        dirs.add(longArrayOf(Bytes.u32le(ph, dirOff + i * 8), Bytes.u32le(ph, dirOff + i * 8 + 4)))
                    }
                }
            } else if (magic == 0x107) {
                warnings += "ROM image optional header; not parsed further"
            } else {
                warnings += "Unknown optional header magic 0x${Integer.toHexString(magic)}"
            }
        } else {
            warnings += "No optional header (size $optSize); this is unusual for an executable image"
        }
        if (!optOk && optSize > 0 && warnings.isEmpty()) warnings += "Optional header is truncated or smaller than expected"

        // ---- sections
        val secs = ArrayList<Sec>()
        val secOff = lfanew + 24 + optSize
        val n = minOf(nsec, MAX_SECTIONS)
        if (nsec > MAX_SECTIONS) warnings += "Only the first $MAX_SECTIONS of $nsec sections were read"
        if (n > 0) {
            val b = rd.read(secOff, n * 40L)
            if (b.size < n * 40) warnings += "Section table extends past the end of the file"
            for (i in 0 until b.size / 40) {
                val o = i * 40
                var len = 0
                while (len < 8 && b[o + len].toInt() != 0) len++
                secs += Sec(BinFmt.clean(String(b, o, len, Charsets.ISO_8859_1)), Bytes.u32le(b, o + 8), Bytes.u32le(b, o + 12),
                    Bytes.u32le(b, o + 16), Bytes.u32le(b, o + 20), Bytes.u32le(b, o + 36))
            }
        }
        for (s in secs) {
            val flags = (if (s.chars and 0x40000000L != 0L) "R" else "-") + (if (s.chars and 0x80000000L != 0L) "W" else "-") +
                (if (s.chars and 0x20000000L != 0L) "X" else "-")
            val kind = buildList {
                if (s.chars and 0x20L != 0L) add("code")
                if (s.chars and 0x40L != 0L) add("initialized data")
                if (s.chars and 0x80L != 0L) add("uninitialized data")
            }.joinToString("/")
            facts += Fact(s.name.ifEmpty { "(unnamed)" },
                "virtual ${s.vsize} bytes at ${BinFmt.hex(s.va)}, raw ${s.rawSize} bytes at ${BinFmt.hex(s.rawPtr)}, $flags${if (kind.isEmpty()) "" else ", $kind"}", "Sections")
        }

        fun rvaToOff(rva: Long): Long {
            for (s in secs) {
                val span = maxOf(s.vsize, s.rawSize)
                if (rva >= s.va && rva - s.va < span && rva - s.va < s.rawSize) return s.rawPtr + (rva - s.va)
            }
            return -1L
        }

        // ---- data directories
        if (dirs.isNotEmpty()) {
            val present = dirs.withIndex().filter { it.value[1] != 0L }.map { iv -> DIRS.getOrElse(iv.index) { "#${iv.index}" } }
            facts += Fact("Data directories present", if (present.isEmpty()) "none" else present.joinToString(", "), "Directories")
            if (dirs.size > 4 && dirs[4][1] != 0L) facts += Fact("Authenticode signature", "present (${dirs[4][1]} bytes; not verified)", "Directories")
        }

        // ---- exports: DLL name
        if (dirs.size > 0 && dirs[0][0] != 0L && dirs[0][1] != 0L) {
            val o = rvaToOff(dirs[0][0])
            if (o >= 0) {
                val ex = rd.read(o, 40)
                if (ex.size >= 28) {
                    rvaToOff(Bytes.u32le(ex, 12)).takeIf { it >= 0 }?.let { no ->
                        rd.cstr(no, 256)?.takeIf { it.isNotEmpty() }?.let { facts += Fact("Export name", it, "Exports") }
                    }
                    facts += Fact("Exported functions", Bytes.u32le(ex, 20).toString(), "Exports")
                    facts += Fact("Named exports", Bytes.u32le(ex, 24).toString(), "Exports")
                }
            }
        }

        // ---- imports
        val imports = ArrayList<String>()
        if (dirs.size > 1 && dirs[1][0] != 0L && dirs[1][1] != 0L) {
            val o = rvaToOff(dirs[1][0])
            if (o >= 0) {
                val b = rd.read(o, 20L * (MAX_IMPORTS + 1))
                var i = 0
                while (i + 20 <= b.size && i / 20 < MAX_IMPORTS) {
                    val nameRva = Bytes.u32le(b, i + 12)
                    val first = Bytes.u32le(b, i + 16)
                    if (nameRva == 0L && first == 0L && Bytes.u32le(b, i) == 0L) break
                    val no = rvaToOff(nameRva)
                    if (no >= 0) rd.cstr(no, 256)?.takeIf { it.isNotEmpty() }?.let { imports += it }
                    i += 20
                }
                if (i / 20 >= MAX_IMPORTS) warnings += "Import table list was capped at $MAX_IMPORTS entries"
            } else warnings += "Import directory RVA does not map into any section"
        }
        if (imports.isNotEmpty()) {
            facts += Fact("Imported DLLs", imports.size.toString(), "Imports")
            for (d in imports.take(200)) facts += Fact("Imports", d, "Imports")
        }

        // ---- .NET
        var dotnet = false
        if (dirs.size > 14 && dirs[14][0] != 0L && dirs[14][1] != 0L) {
            dotnet = true
            facts += Fact(".NET (CLI) assembly", "yes", ".NET")
            val co = rvaToOff(dirs[14][0])
            if (co >= 0) {
                val c = rd.read(co, 72)
                if (c.size >= 16) {
                    facts += Fact("CLR runtime version", "${Bytes.u16le(c, 4)}.${Bytes.u16le(c, 6)}", ".NET")
                    val cflags = Bytes.u32le(c, 16)
                    if (c.size >= 20) facts += Fact("CLR flags", BinFmt.hex(cflags) + if (cflags and 1L != 0L) " (IL only)" else "", ".NET")
                    val mo = rvaToOff(Bytes.u32le(c, 8))
                    if (mo >= 0) {
                        val m = rd.read(mo, 16 + 256)
                        if (m.size >= 16 && Bytes.u32le(m, 0) == 0x424A5342L) {
                            val vlen = minOf(Bytes.u32le(m, 12), 255L).toInt()
                            var l = 0
                            while (l < vlen && 16 + l < m.size && m[16 + l].toInt() != 0) l++
                            val v = BinFmt.clean(String(m, 16, l, Charsets.ISO_8859_1))
                            if (v.isNotEmpty()) facts += Fact("Metadata version", v, ".NET")
                        }
                    }
                }
            }
        }

        val kind = if (isDll) "DLL" else "executable"
        val bitsStr = if (optOk) (if (pe32plus) "PE32+" else "PE32") else "PE"
        val title = "$bitsStr $kind for ${MACHINES[machine] ?: "unknown machine"}" + (if (dotnet) " (.NET)" else "")
        val summary = buildString {
            append(title)
            if (subsystem >= 0) append(", ").append(SUBSYSTEMS[subsystem] ?: "subsystem $subsystem")
            append(", ").append(nsec).append(" sections")
        }
        return InspectionResult("exe", title, summary, facts, warnings)
    }
}
