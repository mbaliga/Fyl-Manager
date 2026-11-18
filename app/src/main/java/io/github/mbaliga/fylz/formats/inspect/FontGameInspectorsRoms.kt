package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** NES cartridge dumps with an iNES or NES 2.0 header. */
object GameNesInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("nes")
    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = fgSafe { run(source, fileName) }

    private fun nes2Size(lsb: Int, msbNibble: Int, unit: Long): Long =
        if (msbNibble == 0xF) {
            val e = lsb shr 2
            val m = lsb and 3
            if (e > 40) -1 else (1L shl e) * (m * 2 + 1)
        } else ((msbNibble.toLong() shl 8) or lsb.toLong()) * unit

    private fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val io = FgIo(source)
        val h = io.read(0, 16)
        if (h.size < 16 || !Bytes.startsWith(h, "NES") || Bytes.u8(h, 3) != 0x1A) return null
        val warnings = ArrayList<String>()
        val facts = ArrayList<Fact>()
        val f6 = Bytes.u8(h, 6)
        val f7 = Bytes.u8(h, 7)
        val nes2 = (f7 shr 2) and 3 == 2
        var mapper = (f6 shr 4) or (f7 and 0xF0)
        val prg: Long
        val chr: Long
        facts.add(Fact("Header", if (nes2) "NES 2.0" else "iNES", "Header"))
        if (nes2) {
            mapper = mapper or ((Bytes.u8(h, 8) and 0x0F) shl 8)
            val sub = Bytes.u8(h, 8) shr 4
            val msb = Bytes.u8(h, 9)
            prg = nes2Size(Bytes.u8(h, 4), msb and 0x0F, 16384)
            chr = nes2Size(Bytes.u8(h, 5), msb shr 4, 8192)
            facts.add(Fact("Mapper", mapper.toString(), "Cartridge"))
            facts.add(Fact("Submapper", sub.toString(), "Cartridge"))
            val b10 = Bytes.u8(h, 10)
            val b11 = Bytes.u8(h, 11)
            fun shifted(n: Int) = if (n == 0) 0L else 64L shl n
            if (shifted(b10 and 0xF) > 0) facts.add(Fact("PRG RAM", Bytes.humanSize(shifted(b10 and 0xF)), "Memory"))
            if (shifted(b10 shr 4) > 0) facts.add(Fact("PRG NVRAM", Bytes.humanSize(shifted(b10 shr 4)), "Memory"))
            if (shifted(b11 and 0xF) > 0) facts.add(Fact("CHR RAM", Bytes.humanSize(shifted(b11 and 0xF)), "Memory"))
            if (shifted(b11 shr 4) > 0) facts.add(Fact("CHR NVRAM", Bytes.humanSize(shifted(b11 shr 4)), "Memory"))
            val timing = when (Bytes.u8(h, 12) and 3) { 0 -> "NTSC (RP2C02)"; 1 -> "PAL (RP2C07)"; 2 -> "multiple-region"; else -> "Dendy (UMC 6527P)" }
            facts.add(Fact("CPU/PPU timing", timing, "Cartridge"))
        } else {
            prg = Bytes.u8(h, 4) * 16384L
            chr = Bytes.u8(h, 5) * 8192L
            facts.add(Fact("Mapper", mapper.toString(), "Cartridge"))
            val ram = Bytes.u8(h, 8)
            facts.add(Fact("PRG RAM (header byte 8)", if (ram == 0) "0 (8 KiB assumed by convention)" else Bytes.humanSize(ram * 8192L), "Memory"))
            facts.add(Fact("TV system (byte 9)", if (Bytes.u8(h, 9) and 1 != 0) "PAL" else "NTSC", "Cartridge"))
            if (Bytes.u8(h, 12) != 0 || Bytes.u8(h, 13) != 0 || Bytes.u8(h, 14) != 0 || Bytes.u8(h, 15) != 0)
                warnings.add("header bytes 12-15 are not zero; the mapper number may be unreliable (old dumper tag)")
        }
        if (prg >= 0) facts.add(Fact("PRG ROM", Bytes.humanSize(prg), "Memory")) else warnings.add("PRG ROM size field is invalid")
        if (chr > 0) facts.add(Fact("CHR ROM", Bytes.humanSize(chr), "Memory"))
        else if (chr == 0L) facts.add(Fact("CHR ROM", "none (uses CHR RAM)", "Memory"))
        val mirroring = if (f6 and 8 != 0) "four-screen" else if (f6 and 1 != 0) "vertical" else "horizontal"
        facts.add(Fact("Mirroring", mirroring, "Cartridge"))
        facts.add(Fact("Battery-backed", if (f6 and 2 != 0) "yes" else "no", "Cartridge"))
        val trainer = f6 and 4 != 0
        facts.add(Fact("Trainer", if (trainer) "yes (512 bytes)" else "no", "Cartridge"))
        if (f7 and 1 != 0) facts.add(Fact("Console type", "VS System", "Cartridge"))
        if (f7 and 2 != 0) facts.add(Fact("Console type", "PlayChoice-10", "Cartridge"))
        if (prg >= 0 && chr >= 0) {
            val expected = 16 + (if (trainer) 512L else 0L) + prg + chr
            facts.add(Fact("Expected file size (header + data)", Bytes.humanSize(expected), "Header"))
            if (io.size < expected) warnings.add("file is smaller than the header declares (${io.size} < $expected bytes)")
            else if (io.size > expected) facts.add(Fact("Trailing data", Bytes.humanSize(io.size - expected) + " after declared ROM data", "Header"))
        }
        return InspectionResult("nes", fileName ?: "NES ROM",
            "${if (nes2) "NES 2.0" else "iNES"} ROM, mapper $mapper" + (if (prg >= 0) ", PRG ${Bytes.humanSize(prg)}" else ""),
            facts, warnings)
    }
}

/** Game Boy and Game Boy Color cartridges. */
object GameGameBoyInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("gb", "gbc")

    private val LOGO = intArrayOf(
        0xCE, 0xED, 0x66, 0x66, 0xCC, 0x0D, 0x00, 0x0B, 0x03, 0x73, 0x00, 0x83, 0x00, 0x0C, 0x00, 0x0D,
        0x00, 0x08, 0x11, 0x1F, 0x88, 0x89, 0x00, 0x0E, 0xDC, 0xCC, 0x6E, 0xE6, 0xDD, 0xDD, 0xD9, 0x99,
        0xBB, 0xBB, 0x67, 0x63, 0x6E, 0x0E, 0xEC, 0xCC, 0xDD, 0xDC, 0x99, 0x9F, 0xBB, 0xB9, 0x33, 0x3E,
    )
    private val TYPES = mapOf(
        0x00 to "ROM ONLY", 0x01 to "MBC1", 0x02 to "MBC1+RAM", 0x03 to "MBC1+RAM+BATTERY", 0x05 to "MBC2",
        0x06 to "MBC2+BATTERY", 0x08 to "ROM+RAM", 0x09 to "ROM+RAM+BATTERY", 0x0B to "MMM01", 0x0C to "MMM01+RAM",
        0x0D to "MMM01+RAM+BATTERY", 0x0F to "MBC3+TIMER+BATTERY", 0x10 to "MBC3+TIMER+RAM+BATTERY", 0x11 to "MBC3",
        0x12 to "MBC3+RAM", 0x13 to "MBC3+RAM+BATTERY", 0x19 to "MBC5", 0x1A to "MBC5+RAM", 0x1B to "MBC5+RAM+BATTERY",
        0x1C to "MBC5+RUMBLE", 0x1D to "MBC5+RUMBLE+RAM", 0x1E to "MBC5+RUMBLE+RAM+BATTERY", 0x20 to "MBC6",
        0x22 to "MBC7+SENSOR+RUMBLE+RAM+BATTERY", 0xFC to "POCKET CAMERA", 0xFD to "BANDAI TAMA5", 0xFE to "HuC3",
        0xFF to "HuC1+RAM+BATTERY",
    )
    private val RAM = mapOf(0 to "none", 2 to "8 KiB", 3 to "32 KiB (4 banks of 8 KiB)", 4 to "128 KiB (16 banks of 8 KiB)", 5 to "64 KiB (8 banks of 8 KiB)")

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = fgSafe { run(source, fileName) }

    private fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val io = FgIo(source)
        val b = io.read(0x100, 0x50)
        if (b.size < 0x50) return null
        val logoOk = LOGO.indices.all { Bytes.u8(b, 4 + it) == LOGO[it] }
        if (!logoOk) return null
        val warnings = ArrayList<String>()
        val facts = ArrayList<Fact>()
        val cgb = Bytes.u8(b, 0x43)
        val titleLen = if (cgb == 0x80 || cgb == 0xC0) 11 else 16
        val title = Fg.cstr(b, 0x34, titleLen)
        facts.add(Fact("Title", title, "Header"))
        if (titleLen == 11) {
            val mfr = Bytes.ascii(b, 0x3F, 4)
            if (b[0x3F].toInt() != 0) facts.add(Fact("Manufacturer code", mfr, "Header"))
        }
        facts.add(Fact("Color support", when (cgb) {
            0xC0 -> "Game Boy Color only"
            0x80 -> "works on Game Boy and Game Boy Color"
            else -> "original Game Boy (no CGB flag)"
        }, "Header"))
        facts.add(Fact("Super Game Boy support", if (Bytes.u8(b, 0x46) == 3) "yes" else "no", "Header"))
        val ct = Bytes.u8(b, 0x47)
        facts.add(Fact("Cartridge type", TYPES[ct]?.let { "$it (${Fg.hex(ct.toLong(), 2)})" } ?: "unknown (${Fg.hex(ct.toLong(), 2)})", "Cartridge"))
        val rs = Bytes.u8(b, 0x48)
        if (rs <= 8) {
            val bytes = 32768L shl rs
            facts.add(Fact("ROM size (header)", "${Bytes.humanSize(bytes)}, ${2 shl rs} banks of 16 KiB", "Cartridge"))
            if (io.size != bytes) warnings.add("file size ${io.size} differs from the ${bytes}-byte ROM size in the header")
        } else facts.add(Fact("ROM size code", Fg.hex(rs.toLong(), 2) + " (unrecognised)", "Cartridge"))
        val rm = Bytes.u8(b, 0x49)
        facts.add(Fact("RAM size", RAM[rm] ?: "unknown code ${Fg.hex(rm.toLong(), 2)}", "Cartridge"))
        facts.add(Fact("Destination", if (Bytes.u8(b, 0x4A) == 0) "Japan" else if (Bytes.u8(b, 0x4A) == 1) "outside Japan" else "unknown", "Header"))
        val lic = Bytes.u8(b, 0x4B)
        facts.add(Fact("Old licensee code", Fg.hex(lic.toLong(), 2) + if (lic == 0x33) " (see new licensee code)" else "", "Header"))
        if (lic == 0x33) facts.add(Fact("New licensee code", Bytes.ascii(b, 0x44, 2), "Header"))
        facts.add(Fact("ROM version", Bytes.u8(b, 0x4C).toString(), "Header"))
        var x = 0
        for (i in 0x34..0x4C) x = (x - Bytes.u8(b, i) - 1) and 0xFF
        val stored = Bytes.u8(b, 0x4D)
        facts.add(Fact("Header checksum", "${Fg.hex(stored.toLong(), 2)} (" + (if (x == stored) "valid" else "INVALID, expected ${Fg.hex(x.toLong(), 2)}") + ")", "Checksums"))
        if (x != stored) warnings.add("header checksum does not match")
        facts.add(Fact("Global checksum (stored)", Fg.hex(Bytes.u16be(b, 0x4E).toLong(), 4) + " (not verified)", "Checksums"))
        facts.add(Fact("Nintendo logo", "present", "Header"))
        val gbc = cgb and 0x80 != 0
        val enhanced = cgb == 0x80
        return InspectionResult(if (gbc) "gbc" else "gb", title.ifEmpty { fileName ?: "Game Boy ROM" },
            "${if (gbc) "Game Boy Color" else "Game Boy"} ROM${if (enhanced) " (Color-enhanced, also runs on original Game Boy)" else ""}, ${TYPES[ct] ?: "unknown cartridge"}", facts, warnings)
    }
}

/** Game Boy Advance cartridges. */
object GameGbaInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("gba")
    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = fgSafe { run(source, fileName) }

    private fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val io = FgIo(source)
        val b = io.read(0, 0xC0)
        if (b.size < 0xC0) return null
        val logo = intArrayOf(0x24, 0xFF, 0xAE, 0x51, 0x69, 0x9A, 0xA2, 0x21, 0x3D, 0x84, 0x82, 0x0A)
        if (!logo.indices.all { Bytes.u8(b, 4 + it) == logo[it] }) return null
        val warnings = ArrayList<String>()
        val facts = ArrayList<Fact>()
        val title = Fg.cstr(b, 0xA0, 12)
        facts.add(Fact("Title", title, "Header"))
        facts.add(Fact("Game code", Bytes.ascii(b, 0xAC, 4), "Header"))
        val maker = Bytes.ascii(b, 0xB0, 2)
        facts.add(Fact("Maker code", if (maker == "01") "01 (Nintendo)" else maker, "Header"))
        facts.add(Fact("Main unit code", Fg.hex(Bytes.u8(b, 0xB3).toLong(), 2), "Header"))
        facts.add(Fact("Software version", Bytes.u8(b, 0xBC).toString(), "Header"))
        facts.add(Fact("ROM size (file)", Bytes.humanSize(io.size), "Header"))
        var chk = 0
        for (i in 0xA0..0xBC) chk -= Bytes.u8(b, i)
        chk = (chk - 0x19) and 0xFF
        val stored = Bytes.u8(b, 0xBD)
        facts.add(Fact("Header checksum", "${Fg.hex(stored.toLong(), 2)} (" + (if (chk == stored) "valid" else "INVALID, expected ${Fg.hex(chk.toLong(), 2)}") + ")", "Checksums"))
        if (chk != stored) warnings.add("header complement check does not match")
        if (Bytes.u8(b, 0xB2) != 0x96) warnings.add("fixed value at 0xB2 is not 0x96")
        return InspectionResult("gba", title.ifEmpty { fileName ?: "GBA ROM" }, "Game Boy Advance ROM, game code ${Bytes.ascii(b, 0xAC, 4)}", facts, warnings)
    }
}

/** Super Nintendo / Super Famicom ROMs: finds and validates the internal header. */
object GameSnesInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("sfc")
    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = fgSafe { run(source, fileName) }

    private class Cand(val name: String, val off: Long, val score: Int, val h: ByteArray)

    private fun score(h: ByteArray, expectMode: Set<Int>): Int {
        if (h.size < 0x40) return -1
        var s = 0
        val csum = Bytes.u16le(h, 0x1E)
        val comp = Bytes.u16le(h, 0x1C)
        if ((csum + comp) and 0xFFFF == 0xFFFF) s += 4
        if ((Bytes.u8(h, 0x15) and 0x0F) in expectMode) s += 2
        if (Bytes.u8(h, 0x15) and 0xE0 == 0x20) s += 1
        if ((0 until 21).all { Bytes.u8(h, it) in 0x20..0x7E || Bytes.u8(h, it) in 0xA0..0xDF || Bytes.u8(h, it) == 0 }) s += 1
        if (Bytes.u16le(h, 0x3C) >= 0x8000) s += 1
        if (Bytes.u8(h, 0x17) in 7..13) s += 1
        return s
    }

    private fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val io = FgIo(source)
        val copier = if (io.size % 1024 == 512L) 512L else 0L
        val cands = ArrayList<Cand>()
        for ((name, off, modes) in listOf(
            Triple("LoROM", 0x7FC0L, setOf(0, 2, 3)),
            Triple("HiROM", 0xFFC0L, setOf(1, 3)),
            Triple("ExHiROM", 0x40FFC0L, setOf(5)),
        )) {
            val o = off + copier
            if (!io.inFile(o, 0x40)) continue
            val h = io.read(o, 0x40)
            if (h.size < 0x40) continue
            cands.add(Cand(name, o, score(h, modes), h))
        }
        val best = cands.maxByOrNull { it.score }
        val facts = ArrayList<Fact>()
        val warnings = ArrayList<String>()
        if (best == null || best.score < 6) {
            if (io.size < 0x8000) return null
            // Without a valid internal header only a SNES file extension justifies a result.
            if (fileName == null || !Regex("""\.(sfc|smc|swc|fig)$""", RegexOption.IGNORE_CASE).containsMatchIn(fileName)) return null
            facts.add(Fact("File size", Bytes.humanSize(io.size), "Header"))
            if (copier > 0) facts.add(Fact("Copier header", "512 bytes", "Header"))
            warnings.add("no valid SNES internal header was found; the ROM may be unusual or damaged")
            return InspectionResult("sfc", fileName ?: "SNES ROM", "SNES ROM, internal header not found", facts, warnings)
        }
        val h = best.h
        val title = Fg.cstr(h, 0, 21)
        val mode = Bytes.u8(h, 0x15)
        facts.add(Fact("Title", title, "Header"))
        facts.add(Fact("Mapping", best.name + " (map mode ${Fg.hex(mode.toLong(), 2)})", "Header"))
        facts.add(Fact("Speed", if (mode and 0x10 != 0) "FastROM" else "SlowROM", "Header"))
        if (copier > 0) facts.add(Fact("Copier header", "512 bytes", "Header"))
        facts.add(Fact("Header offset in file", Fg.hex(best.off), "Header"))
        facts.add(Fact("ROM type (chipset)", Fg.hex(Bytes.u8(h, 0x16).toLong(), 2), "Cartridge"))
        val rs = Bytes.u8(h, 0x17)
        if (rs in 1..20) facts.add(Fact("ROM size (header)", Bytes.humanSize(1024L shl rs), "Cartridge"))
        val ramS = Bytes.u8(h, 0x18)
        facts.add(Fact("SRAM size", if (ramS == 0) "none" else if (ramS in 1..15) Bytes.humanSize(1024L shl ramS) else "unknown code $ramS", "Cartridge"))
        val country = Bytes.u8(h, 0x19)
        facts.add(Fact("Country code", Fg.hex(country.toLong(), 2) + when (country) { 0 -> " (Japan)"; 1 -> " (North America)"; 2 -> " (Europe)"; else -> "" }, "Header"))
        facts.add(Fact("Version", Bytes.u8(h, 0x1B).toString(), "Header"))
        val csum = Bytes.u16le(h, 0x1E)
        val comp = Bytes.u16le(h, 0x1C)
        val ok = (csum + comp) and 0xFFFF == 0xFFFF
        facts.add(Fact("Checksum / complement", "${Fg.hex(csum.toLong(), 4)} / ${Fg.hex(comp.toLong(), 4)} (" + (if (ok) "consistent" else "inconsistent") + ")", "Checksums"))
        if (!ok) warnings.add("checksum and complement do not sum to 0xFFFF")
        return InspectionResult("sfc", title.ifEmpty { fileName ?: "SNES ROM" }, "SNES ROM, ${best.name}", facts, warnings)
    }
}

/** Nintendo 64 ROMs in any of the three byte orders. */
object GameN64Inspector : FormatInspector {
    override val formatIds: Set<String> = setOf("z64", "n64", "v64")
    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = fgSafe { run(source, fileName) }

    private val REGION = mapOf(
        'E' to "North America", 'J' to "Japan", 'P' to "Europe", 'D' to "Germany", 'F' to "France",
        'U' to "Australia", 'A' to "Asia (NTSC)",
    )

    private fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val io = FgIo(source)
        val raw = io.read(0, 0x40)
        if (raw.size < 0x40) return null
        val first = Bytes.u32be(raw, 0)
        val (id, order) = when (first) {
            0x80371240L -> Pair("z64", "big-endian (z64)")
            0x37804012L -> Pair("v64", "byte-swapped (v64)")
            0x40123780L -> Pair("n64", "little-endian word-swapped (n64)")
            else -> return null
        }
        val b = ByteArray(0x40)
        for (i in 0 until 0x40) {
            b[i] = when (id) {
                "z64" -> raw[i]
                "v64" -> raw[i xor 1]
                else -> raw[i xor 3]
            }
        }
        val facts = ArrayList<Fact>()
        val title = Fg.cstr(b, 0x20, 20)
        facts.add(Fact("Byte order", order, "Header"))
        facts.add(Fact("Title", title, "Header"))
        val media = b[0x3B].toInt().toChar()
        val code = Bytes.ascii(b, 0x3B, 4)
        facts.add(Fact("Game code", code, "Header"))
        val mediaName = when (media) { 'N' -> "game pak"; 'D' -> "64DD disk"; 'C' -> "game pak (expansion)"; 'E' -> "64DD expansion"; 'Z' -> "Aleck64 cartridge"; else -> null }
        mediaName?.let { facts.add(Fact("Media format", it, "Header")) }
        val reg = b[0x3E].toInt().toChar()
        REGION[reg]?.let { facts.add(Fact("Region", it, "Header")) }
        facts.add(Fact("Version", Bytes.u8(b, 0x3F).toString(), "Header"))
        facts.add(Fact("Boot address", Fg.hex(Bytes.u32be(b, 8), 8), "Header"))
        facts.add(Fact("CRC1 / CRC2", "${Fg.hex(Bytes.u32be(b, 0x10), 8)} / ${Fg.hex(Bytes.u32be(b, 0x14), 8)} (not verified)", "Checksums"))
        facts.add(Fact("File size", Bytes.humanSize(io.size), "Header"))
        val warnings = ArrayList<String>()
        if (io.size % 4 != 0L) warnings.add("file size is not a multiple of 4 bytes")
        return InspectionResult(id, title.ifEmpty { fileName ?: "N64 ROM" }, "Nintendo 64 ROM, $order, game code $code", facts, warnings)
    }
}

/** Sega Mega Drive / Genesis ROMs, including SMD-interleaved dumps. */
object GameGenesisInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("md", "smd")
    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = fgSafe { run(source, fileName) }

    private fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val io = FgIo(source)
        var hdr = io.read(0x100, 0x100)
        var interleaved = false
        if (hdr.size < 0x100 || !Bytes.startsWith(hdr, "SEGA")) {
            val top = io.read(0, 512 + 16384)
            if (top.size == 512 + 16384 && Bytes.u8(top, 8) == 0xAA && Bytes.u8(top, 9) == 0xBB) {
                val d = ByteArray(16384)
                for (i in 0 until 8192) {
                    d[2 * i + 1] = top[512 + i]
                    d[2 * i] = top[512 + 8192 + i]
                }
                if (Bytes.startsWith(d, "SEGA", 0x100)) { hdr = d.copyOfRange(0x100, 0x200); interleaved = true }
            }
            if (!interleaved) return null
        }
        val facts = ArrayList<Fact>()
        val warnings = ArrayList<String>()
        val sys = Fg.cstr(hdr, 0, 16)
        facts.add(Fact("System", sys, "Header"))
        if (interleaved) facts.add(Fact("Layout", "SMD interleaved (512-byte header, 16 KiB blocks)", "Header"))
        facts.add(Fact("Copyright / release", Fg.cstr(hdr, 0x10, 16), "Header"))
        val dom = Fg.cstr(hdr, 0x20, 48).replace(Regex(" {2,}"), " ")
        val over = Fg.cstr(hdr, 0x50, 48).replace(Regex(" {2,}"), " ")
        facts.add(Fact("Domestic title", dom, "Header"))
        facts.add(Fact("Overseas title", over, "Header"))
        facts.add(Fact("Serial / type", Fg.cstr(hdr, 0x80, 14), "Header"))
        facts.add(Fact("Checksum (stored)", Fg.hex(Bytes.u16be(hdr, 0x8E).toLong(), 4) + " (not verified)", "Checksums"))
        facts.add(Fact("Device support", Fg.cstr(hdr, 0x90, 16), "Header"))
        facts.add(Fact("ROM address range", "${Fg.hex(Bytes.u32be(hdr, 0xA0), 8)} - ${Fg.hex(Bytes.u32be(hdr, 0xA4), 8)}", "Memory"))
        facts.add(Fact("RAM address range", "${Fg.hex(Bytes.u32be(hdr, 0xA8), 8)} - ${Fg.hex(Bytes.u32be(hdr, 0xAC), 8)}", "Memory"))
        val regions = Fg.cstr(hdr, 0xF0, 3)
        if (regions.isNotEmpty()) facts.add(Fact("Region codes", regions, "Header"))
        facts.add(Fact("File size", Bytes.humanSize(io.size), "Header"))
        val title = over.ifEmpty { dom }
        return InspectionResult(if (interleaved) "smd" else "md", title.ifEmpty { fileName ?: "Mega Drive ROM" }, "$sys ROM", facts, warnings)
    }
}

/** Nintendo DS / DSi cartridges. */
object GameNdsInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("nds", "dsi")
    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = fgSafe { run(source, fileName) }

    private fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val io = FgIo(source)
        val b = io.read(0, 0x160)
        if (b.size < 0x160) return null
        val logo = intArrayOf(0x24, 0xFF, 0xAE, 0x51, 0x69, 0x9A, 0xA2, 0x21, 0x3D, 0x84, 0x82, 0x0A)
        if (!logo.indices.all { Bytes.u8(b, 0xC0 + it) == logo[it] }) return null
        val facts = ArrayList<Fact>()
        val warnings = ArrayList<String>()
        val title = Fg.cstr(b, 0, 12)
        val unit = Bytes.u8(b, 0x12)
        facts.add(Fact("Title", title, "Header"))
        facts.add(Fact("Game code", Bytes.ascii(b, 0x0C, 4), "Header"))
        val maker = Bytes.ascii(b, 0x10, 2)
        facts.add(Fact("Maker code", if (maker == "01") "01 (Nintendo)" else maker, "Header"))
        facts.add(Fact("Unit code", when (unit) { 0 -> "Nintendo DS"; 2 -> "Nintendo DS and DSi"; 3 -> "Nintendo DSi"; else -> Fg.hex(unit.toLong(), 2) }, "Header"))
        val cap = Bytes.u8(b, 0x14)
        if (cap <= 15) facts.add(Fact("Device capacity", Bytes.humanSize(131072L shl cap), "Header"))
        facts.add(Fact("ROM version", Bytes.u8(b, 0x1E).toString(), "Header"))
        facts.add(Fact("ARM9 code", "${Bytes.humanSize(Bytes.u32le(b, 0x2C))} at ${Fg.hex(Bytes.u32le(b, 0x20))}", "Code"))
        facts.add(Fact("ARM7 code", "${Bytes.humanSize(Bytes.u32le(b, 0x3C))} at ${Fg.hex(Bytes.u32le(b, 0x30))}", "Code"))
        val used = Bytes.u32le(b, 0x80)
        facts.add(Fact("Used ROM size (header)", Bytes.humanSize(used), "Header"))
        facts.add(Fact("File size", Bytes.humanSize(io.size), "Header"))
        val logoCrc = Fg.crc16Modbus(b, 0xC0, 0x15C)
        val storedLogo = Bytes.u16le(b, 0x15C)
        facts.add(Fact("Logo checksum", "${Fg.hex(storedLogo.toLong(), 4)} (" + (if (logoCrc == storedLogo) "valid" else "mismatch") + ")", "Checksums"))
        val hdrCrc = Fg.crc16Modbus(b, 0, 0x15E)
        val storedHdr = Bytes.u16le(b, 0x15E)
        facts.add(Fact("Header checksum", "${Fg.hex(storedHdr.toLong(), 4)} (" + (if (hdrCrc == storedHdr) "valid" else "mismatch, computed ${Fg.hex(hdrCrc.toLong(), 4)}") + ")", "Checksums"))
        if (hdrCrc != storedHdr) warnings.add("header CRC-16 does not match")
        if (used > io.size) warnings.add("header declares more ROM data than the file contains")
        return InspectionResult(if (unit == 3) "dsi" else "nds", title.ifEmpty { fileName ?: "Nintendo DS ROM" },
            "Nintendo DS ROM, game code ${Bytes.ascii(b, 0x0C, 4)}", facts, warnings)
    }
}
