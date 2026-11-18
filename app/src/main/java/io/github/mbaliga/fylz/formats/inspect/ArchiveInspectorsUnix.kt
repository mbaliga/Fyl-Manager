package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

internal object ArchiveArInspector : ArcBase() {
    override val formatIds: Set<String> = setOf("ar", "deb")
    private const val MAX_MEMBERS = 10_000

    private class Member(val name: String, val size: Long, val mtime: Long, val dataOff: Long)

    override fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val h = ArcUtil.readAt(source, 0, 8)
        if (!ArcUtil.eq(h, 0, "!<arch>\n")) return null
        val size = source.size
        val members = ArrayList<Member>()
        var longNames: ByteArray? = null
        var pos = 8L; var n = 0; var truncated = false; var capped = false; var bad = false
        var budget = 4L * 1024 * 1024
        while (true) {
            if (n >= MAX_MEMBERS || budget < 60) { capped = true; break }
            budget -= 60
            val b = ArcUtil.readAt(source, pos, 60)
            if (b.isEmpty()) break
            if (b.size < 60) { truncated = true; break }
            if (b[58].toInt() != 0x60 || b[59].toInt() != 0x0A) { bad = true; break }
            val sz = ArcUtil.trimField(Bytes.ascii(b, 48, 10)).trim().toLongOrNull()
            if (sz == null || sz < 0) { bad = true; break }
            val mt = Bytes.ascii(b, 16, 12).trim().toLongOrNull() ?: 0L
            var name = Bytes.ascii(b, 0, 16).trimEnd()
            var dataOff = pos + 60
            var dsize = sz
            n++
            if (name == "//") {
                if (longNames == null) {
                    val want = ArcUtil.clampInt(sz, minOf(256L * 1024, budget).toInt())
                    budget -= want
                    longNames = ArcUtil.readAt(source, dataOff, want)
                }
            } else {
                if (name.startsWith("#1/")) {
                    val l = name.substring(3).trim().toIntOrNull()
                    if (l != null && l in 0..1024 && l <= sz) {
                        budget -= l
                        name = ArcUtil.cstr(ArcUtil.readAt(source, dataOff, l), 0, l)
                        dataOff += l; dsize -= l
                    }
                } else if (name.startsWith("/") && name.length > 1 && name[1].isDigit() && longNames != null) {
                    val idx = name.substring(1).trim().toIntOrNull()
                    if (idx != null && idx < longNames.size) {
                        var e = idx
                        while (e < longNames.size && longNames[e].toInt() != 0x0A) e++
                        name = String(longNames, idx, e - idx, Charsets.ISO_8859_1).trimEnd('/')
                    }
                } else if (name.endsWith("/") && name.length > 1) name = name.dropLast(1)
                members += Member(ArcUtil.clean(name), dsize, mt, dataOff)
            }
            val next = pos + 60 + sz + (sz and 1)
            if (next <= pos) { bad = true; break }
            if (next > size) { if (pos + 60 + sz > size) truncated = true; break }
            pos = next
        }
        val isDeb = members.isNotEmpty() && members[0].name == "debian-binary" && members.any { it.name.startsWith("control.tar") }
        val facts = ArrayList<Fact>(); val warnings = ArrayList<String>()
        var text: String? = null
        facts += Fact("Members", (if (capped) "at least " else "") + members.size, "Archive")
        for ((i, m) in members.withIndex()) {
            if (i >= 20) break
            facts += Fact(m.name.take(300), "${m.size} bytes" + (if (m.mtime > 0) ", " + ArcUtil.fmtEpoch(m.mtime) else ""), "Member list")
        }
        if (truncated) warnings += "Archive appears truncated"
        if (bad) warnings += "Invalid member header encountered; listing stopped"
        if (capped) warnings += "Member scan stopped at $MAX_MEMBERS members"
        var summary = "ar archive, ${members.size} members"
        var fid = "ar"
        if (isDeb) {
            fid = "deb"
            summary = "Debian package"
            val ver = ArcUtil.readAt(source, members[0].dataOff, ArcUtil.clampInt(minOf(members[0].size, 16L), 16))
            facts += Fact("deb format version", ArcUtil.clean(String(ver, Charsets.US_ASCII).trim()), "Debian package")
            val dm = members.firstOrNull { it.name.startsWith("data.tar") }
            if (dm != null) facts += Fact("Data archive", dm.name, "Debian package")
            val cm = members.first { it.name.startsWith("control.tar") }
            facts += Fact("Control archive", cm.name, "Debian package")
            val ctl = readControl(source, cm)
            if (ctl == null) warnings += "Control archive (${cm.name}) is not readable here (only plain tar and gzip are supported)"
            else {
                val f = parseControl(ctl)
                for (k in listOf("Package", "Version", "Architecture", "Maintainer", "Installed-Size", "Depends", "Section", "Priority", "Homepage"))
                    f[k]?.let { facts += Fact(k, ArcUtil.clean(it).take(500), "Control") }
                f["Description"]?.let { facts += Fact("Description", ArcUtil.clean(it.lineSequence().first()).take(300), "Control") }
                text = ctl.take(FormatInspector.MAX_TEXT_CHARS)
                if (f["Package"] != null) summary = "Debian package ${f["Package"]} ${f["Version"] ?: ""} (${f["Architecture"] ?: "?"})".replace("  ", " ")
            }
        }
        return InspectionResult(fid, if (isDeb) "Debian package" else "ar archive", summary, facts, warnings, text)
    }

    private fun readControl(source: ByteSource, m: Member): String? = ArcUtil.safe {
        val raw = ArcUtil.readAt(source, m.dataOff, ArcUtil.clampInt(m.size, 2 * 1024 * 1024))
        val tarBytes: ByteArray = when {
            m.name == "control.tar" -> raw
            m.name == "control.tar.gz" -> {
                val o = java.io.ByteArrayOutputStream()
                java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(raw)).use { gz ->
                    val buf = ByteArray(8192)
                    while (o.size() < 2 * 1024 * 1024) { val r = gz.read(buf); if (r <= 0) break; o.write(buf, 0, minOf(r, 2 * 1024 * 1024 - o.size())) }
                }
                o.toByteArray()
            }
            else -> return@safe null
        }
        val src = ByteArraySource(tarBytes)
        val scan = ArchiveTarParser.scan(src, 200) ?: return@safe null
        val e = scan.entries.firstOrNull { it.name == "./control" || it.name == "control" } ?: return@safe null
        String(ArcUtil.readAt(src, e.dataOff, ArcUtil.clampInt(e.size, 64 * 1024)), Charsets.UTF_8)
    }

    fun parseControl(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>(); var last: String? = null
        for (line in s.replace("\r\n", "\n").split('\n')) {
            if (line.isEmpty()) break
            if ((line[0] == ' ' || line[0] == '\t') && last != null) { out[last] = out[last] + "\n" + line.trim(); continue }
            val c = line.indexOf(':'); if (c <= 0) continue
            last = line.substring(0, c); out[last] = line.substring(c + 1).trim()
        }
        return out
    }
}

internal object ArchiveRpmInspector : ArcBase() {
    override val formatIds: Set<String> = setOf("rpm")
    private val ARCH = mapOf(1 to "i386", 2 to "Alpha", 3 to "Sparc", 4 to "MIPS", 5 to "PowerPC", 6 to "m68k", 7 to "SGI", 8 to "RS6000", 9 to "ia64", 10 to "sparc64", 11 to "mipsel", 12 to "arm")

    private val TAGS = linkedMapOf(
        1000 to "Name", 1001 to "Version", 1002 to "Release", 1003 to "Epoch", 1004 to "Summary", 1005 to "Description",
        1006 to "Build time", 1007 to "Build host", 1009 to "Installed size", 1011 to "Vendor", 1014 to "License",
        1015 to "Packager", 1016 to "Group", 1020 to "URL", 1021 to "OS", 1022 to "Architecture", 1044 to "Source RPM",
        1124 to "Payload format", 1125 to "Payload compressor",
    )

    override fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val lead = ArcUtil.readAt(source, 0, 96)
        if (lead.size < 4 || Bytes.u32be(lead, 0) != 0xEDABEEDBL) return null
        val facts = ArrayList<Fact>(); val warnings = ArrayList<String>()
        if (lead.size < 96) return InspectionResult("rpm", "RPM package", "RPM package (truncated lead)", facts, listOf("Truncated lead"))
        facts += Fact("Lead version", "${Bytes.u8(lead, 4)}.${Bytes.u8(lead, 5)}", "Lead")
        val type = Bytes.u16be(lead, 6)
        facts += Fact("Package type", when (type) { 0 -> "binary"; 1 -> "source"; else -> "type $type" }, "Lead")
        val arch = Bytes.u16be(lead, 8)
        facts += Fact("Lead architecture number", (ARCH[arch]?.let { "$arch ($it)" } ?: arch.toString()), "Lead")
        facts += Fact("Lead name", ArcUtil.cstr(lead, 10, 66), "Lead")
        val os = Bytes.u16be(lead, 76)
        facts += Fact("Lead OS number", if (os == 1) "1 (Linux)" else os.toString(), "Lead")
        facts += Fact("Signature type", Bytes.u16be(lead, 78).toString(), "Lead")

        // signature header, then main header
        val sig = readHeader(source, 96) ?: return InspectionResult("rpm", "RPM package", "RPM package", facts, warnings + "Signature header not readable")
        val mainStart = 96 + ((sig.total + 7) / 8) * 8
        val main = readHeader(source, mainStart)
        if (main == null) { warnings += "Main header not found or damaged" } else {
            facts += Fact("Header tag count", main.entries.size.toString(), "Header")
            var name: String? = null; var ver: String? = null; var rel: String? = null; var archS: String? = null
            for ((tag, label) in TAGS) {
                val v = main.value(tag) ?: continue
                val shown = when (tag) {
                    1006 -> v.toLongOrNull()?.let { ArcUtil.fmtEpoch(it) } ?: v
                    1009 -> v.toLongOrNull()?.let { "$it bytes" } ?: v
                    else -> ArcUtil.clean(v).take(500)
                }
                facts += Fact(label, shown, "Header")
                when (tag) { 1000 -> name = v; 1001 -> ver = v; 1002 -> rel = v; 1022 -> archS = v }
            }
            if (name != null) return InspectionResult("rpm", "RPM package", "RPM package $name-${ver ?: "?"}-${rel ?: "?"}.${archS ?: "?"}", facts, warnings,
                main.value(1005)?.take(FormatInspector.MAX_TEXT_CHARS))
        }
        return InspectionResult("rpm", "RPM package", "RPM package", facts, warnings)
    }

    private class Hdr(val entries: List<IntArray>, val store: ByteArray, val total: Long) {
        fun value(tag: Int): String? {
            val e = entries.firstOrNull { it[0] == tag } ?: return null
            val off = e[2]
            return when (e[1]) {
                4 -> if (off >= 0 && off <= store.size - 4) Bytes.u32be(store, off).toString() else null
                6, 9 -> if (off in 0 until store.size) ArcUtil.cstr(store, off, 65536).let { if (e[1] == 9) it else it } else null
                else -> null
            }
        }
    }

    /** Header structure at [at]: magic 8E AD E8, version, reserved, nindex, hsize, index, store. */
    private fun readHeader(source: ByteSource, at: Long): Hdr? {
        val h = ArcUtil.readAt(source, at, 16)
        if (h.size < 16 || Bytes.u8(h, 0) != 0x8E || Bytes.u8(h, 1) != 0xAD || Bytes.u8(h, 2) != 0xE8) return null
        val nIdx = Bytes.u32be(h, 8); val hsize = Bytes.u32be(h, 12)
        if (nIdx > 20_000 || hsize > 64L * 1024 * 1024) return null
        val total = 16 + nIdx * 16 + hsize
        val idxBytes = ArcUtil.readAt(source, at + 16, (nIdx * 16).toInt())
        val entries = ArrayList<IntArray>()
        for (i in 0 until idxBytes.size / 16) {
            val o = i * 16
            entries += intArrayOf(Bytes.u32be(idxBytes, o).toInt(), Bytes.u32be(idxBytes, o + 4).toInt(),
                Bytes.u32be(idxBytes, o + 8).toInt(), Bytes.u32be(idxBytes, o + 12).toInt())
        }
        val store = ArcUtil.readAt(source, at + 16 + nIdx * 16, ArcUtil.clampInt(hsize, 2 * 1024 * 1024))
        return Hdr(entries, store, total)
    }
}

internal object ArchiveIsoInspector : ArcBase() {
    override val formatIds: Set<String> = setOf("iso")

    private fun date(b: ByteArray, o: Int): String? {
        val s = Bytes.ascii(b, o, 16)
        if (!s.all { it in '0'..'9' } || s.startsWith("0000")) return null
        val tz = b.getOrNull(o + 16)?.toInt() ?: 0
        return "${s.substring(0, 4)}-${s.substring(4, 6)}-${s.substring(6, 8)} ${s.substring(8, 10)}:${s.substring(10, 12)}:${s.substring(12, 14)} (UTC${if (tz >= 0) "+" else "-"}${Math.abs(tz) * 15 / 60}h${Math.abs(tz) * 15 % 60}m)"
    }

    override fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val vd = ArcUtil.readAt(source, 32768, 2048 * 32)
        if (vd.size < 2048) return null
        // find primary descriptor
        var pvd = -1; var elTorito = false; var bootCatalog = 0L; var joliet = false; var end = false
        var i = 0
        while (i + 2048 <= vd.size && i / 2048 < 32) {
            if (!ArcUtil.eq(vd, i + 1, "CD001")) break
            when (Bytes.u8(vd, i)) {
                0 -> if (ArcUtil.cstr(vd, i + 7, 32).startsWith("EL TORITO SPECIFICATION")) { elTorito = true; bootCatalog = Bytes.u32le(vd, i + 71) }
                1 -> if (pvd < 0) pvd = i
                2 -> { val esc = Bytes.ascii(vd, i + 88, 3); if (esc == "%/@" || esc == "%/C" || esc == "%/E") joliet = true }
                255 -> end = true
            }
            if (end) break
            i += 2048
        }
        if (pvd < 0) return null
        val facts = ArrayList<Fact>(); val warnings = ArrayList<String>()
        fun s(o: Int, l: Int) = ArcUtil.trimField(Bytes.ascii(vd, pvd + o, l)).trim()
        facts += Fact("System identifier", s(8, 32), "Volume")
        facts += Fact("Volume identifier", s(40, 32), "Volume")
        val blocks = Bytes.u32le(vd, pvd + 80); val bs = Bytes.u16le(vd, pvd + 128)
        if (blocks != Bytes.u32be(vd, pvd + 84) || bs != Bytes.u16be(vd, pvd + 130)) warnings += "Little- and big-endian copies of size fields disagree"
        facts += Fact("Logical block size", bs.toString(), "Volume")
        facts += Fact("Volume space size", "$blocks blocks", "Volume")
        facts += Fact("Total size", ArcUtil.size(blocks * bs), "Volume")
        facts += Fact("Volume set identifier", s(190, 128), "Volume")
        facts += Fact("Publisher", s(318, 128), "Volume")
        facts += Fact("Data preparer", s(446, 128), "Volume")
        facts += Fact("Application", s(574, 128), "Volume")
        for ((lbl, off) in listOf("Created" to 813, "Modified" to 830, "Expires" to 847, "Effective" to 864)) {
            val d = date(vd, pvd + off)
            facts += Fact(lbl, d ?: "not set", "Dates")
        }
        facts += Fact("El Torito boot record", if (elTorito) "present (boot catalog at sector $bootCatalog)" else "absent", "Boot")
        facts += Fact("Joliet extension", if (joliet) "present" else "absent", "Extensions")
        if (blocks * bs > source.size) warnings += "Volume declares ${blocks * bs} bytes but the file has ${source.size}: truncated image"
        val label = s(40, 32)
        return InspectionResult("iso", "ISO 9660 image", "ISO 9660 image" + (if (label.isNotEmpty()) " \"$label\"" else "") + ", ${Bytes.humanSize(blocks * bs)}", facts, warnings)
    }
}

internal object ArchiveCpioInspector : ArcBase() {
    override val formatIds: Set<String> = setOf("cpio")
    private const val MAX_ENTRIES = 10_000

    override fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val m = ArcUtil.readAt(source, 0, 6)
        val kind = when {
            ArcUtil.eq(m, 0, "070701") -> "newc"
            ArcUtil.eq(m, 0, "070702") -> "newc with CRC"
            ArcUtil.eq(m, 0, "070707") -> "odc (portable ASCII)"
            m.size >= 2 && Bytes.u16le(m, 0) == 0x71C7 -> "binary (little-endian)"
            m.size >= 2 && Bytes.u16be(m, 0) == 0x71C7 -> "binary (big-endian)"
            else -> return null
        }
        val facts = ArrayList<Fact>(); val warnings = ArrayList<String>()
        facts += Fact("Variant", kind, "Archive")
        if (kind.startsWith("binary")) {
            warnings += "Entry listing is not supported for binary cpio"
            return InspectionResult("cpio", "cpio archive", "cpio archive, $kind", facts, warnings)
        }
        val newc = kind.startsWith("newc")
        var pos = 0L; var count = 0L; var files = 0L; var dirs = 0L; var links = 0L; var other = 0L; var bytes = 0L
        var trailer = false; var truncated = false; var bad = false; var capped = false
        val size = source.size
        var budget = 4L * 1024 * 1024
        while (true) {
            if (count >= MAX_ENTRIES || budget < 110 + 4096) { capped = true; break }
            val hl = if (newc) 110 else 76
            budget -= hl
            val b = ArcUtil.readAt(source, pos, hl)
            if (b.size < hl) { truncated = true; break }
            val mode: Long; val nsz: Long; val fsz: Long
            if (newc) {
                if (!ArcUtil.eq(b, 0, "07070")) { bad = true; break }
                mode = hex(b, 14); fsz = hex(b, 54); nsz = hex(b, 94)
            } else {
                if (!ArcUtil.eq(b, 0, "070707")) { bad = true; break }
                mode = oct(b, 18, 6); nsz = oct(b, 59, 6); fsz = oct(b, 65, 11)
            }
            if (mode < 0 || nsz < 1 || nsz > 4096 || fsz < 0) { bad = true; break }
            val nameOff = pos + hl
            budget -= nsz
            val name = ArcUtil.cstr(ArcUtil.readAt(source, nameOff, nsz.toInt()), 0, nsz.toInt())
            var dataOff = nameOff + nsz
            if (newc) dataOff = (dataOff + 3) / 4 * 4
            if (name == "TRAILER!!!") { trailer = true; break }
            count++
            when (mode and 0xF000L) { 0x8000L -> { files++; bytes += fsz }; 0x4000L -> dirs++; 0xA000L -> links++; else -> other++ }
            if (count <= 20) facts += Fact(name.take(300), (when (mode and 0xF000L) { 0x8000L -> "file"; 0x4000L -> "directory"; 0xA000L -> "symlink"; else -> "special" }) + ", $fsz bytes", "Entry list")
            var next = dataOff + fsz
            if (newc) next = (next + 3) / 4 * 4
            if (next > size) { truncated = true; break }
            pos = next
        }
        facts += Fact("Entries", (if (capped) "at least " else "") + count, "Archive")
        facts += Fact("Regular files", files.toString(), "Archive")
        facts += Fact("Directories", dirs.toString(), "Archive")
        facts += Fact("Symlinks", links.toString(), "Archive")
        facts += Fact("Total file data", ArcUtil.size(bytes), "Archive")
        facts += Fact("Trailer", if (trailer) "present" else "absent", "Archive")
        if (truncated) warnings += "Archive appears truncated"
        if (bad) warnings += "Invalid entry header; listing stopped"
        if (capped) warnings += "Entry scan stopped at $MAX_ENTRIES entries"
        return InspectionResult("cpio", "cpio archive", "cpio archive ($kind), $count entries", facts, warnings)
    }

    private fun hex(b: ByteArray, o: Int): Long = Bytes.ascii(b, o, 8).toLongOrNull(16) ?: -1
    private fun oct(b: ByteArray, o: Int, l: Int): Long = Bytes.ascii(b, o, l).toLongOrNull(8) ?: -1
}

internal object ArchiveSquashfsInspector : ArcBase() {
    override val formatIds: Set<String> = setOf("squashfs")
    override fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val b = ArcUtil.readAt(source, 0, 96)
        val le = ArcUtil.eq(b, 0, "hsqs")
        if (!le && !ArcUtil.eq(b, 0, "sqsh")) return null
        if (b.size < 48) return InspectionResult("squashfs", "SquashFS image", "SquashFS image (truncated)", emptyList(), listOf("Truncated superblock"))
        fun u16(o: Int) = if (le) Bytes.u16le(b, o) else Bytes.u16be(b, o)
        fun u32(o: Int) = if (le) Bytes.u32le(b, o) else Bytes.u32be(b, o)
        fun u64(o: Int) = if (le) Bytes.u64le(b, o) else Bytes.u64be(b, o)
        val facts = ArrayList<Fact>(); val warnings = ArrayList<String>()
        val major = u16(28); val minor = u16(30)
        facts += Fact("Version", "$major.$minor", "Superblock")
        facts += Fact("Byte order", if (le) "little-endian" else "big-endian", "Superblock")
        facts += Fact("Inode count", u32(4).toString(), "Superblock")
        facts += Fact("Modification time", u32(8).let { if (it == 0L) "not set" else ArcUtil.fmtEpoch(it) }, "Superblock")
        val bsz = u32(12)
        facts += Fact("Block size", "$bsz bytes", "Superblock")
        facts += Fact("Fragment count", u32(16).toString(), "Superblock")
        val comp = u16(20)
        facts += Fact("Compression", when (comp) { 1 -> "gzip"; 2 -> "lzma"; 3 -> "lzo"; 4 -> "xz"; 5 -> "lz4"; 6 -> "zstd"; else -> "unknown ($comp)" }, "Superblock")
        val bl = u16(22)
        if (bsz <= 0 || (1L shl bl.coerceIn(0, 40)) != bsz) warnings += "Block size does not match block_log"
        val fl = u16(24)
        val f = ArrayList<String>()
        if (fl and 0x1 != 0) f += "inodes uncompressed"; if (fl and 0x2 != 0) f += "data blocks uncompressed"
        if (fl and 0x8 != 0) f += "fragments uncompressed"; if (fl and 0x10 != 0) f += "no fragments"
        if (fl and 0x20 != 0) f += "always fragments"; if (fl and 0x40 != 0) f += "duplicates removed"
        if (fl and 0x80 != 0) f += "exportable"; if (fl and 0x100 != 0) f += "xattrs uncompressed"; if (fl and 0x200 != 0) f += "no xattrs"
        if (fl and 0x400 != 0) f += "compressor options present"; if (fl and 0x800 != 0) f += "ids uncompressed"
        facts += Fact("Flags", if (f.isEmpty()) "none" else f.joinToString(", "), "Superblock")
        facts += Fact("ID count", u16(26).toString(), "Superblock")
        val used = u64(40)
        facts += Fact("Bytes used", ArcUtil.size(used), "Superblock")
        if (major != 4) warnings += "Superblock layout beyond version 4 is not verified"
        if (used > source.size) warnings += "Superblock declares $used bytes but the file has ${source.size}"
        return InspectionResult("squashfs", "SquashFS image", "SquashFS $major.$minor, ${u32(4)} inodes", facts, warnings)
    }
}

object ArchiveInspectors {
    val all: List<FormatInspector> = listOf(
        ArchiveZipInspector, ArchiveTarInspector, ArchiveGzInspector, ArchiveBz2Inspector, ArchiveXzInspector,
        ArchiveZstdInspector, Archive7zInspector, ArchiveRarInspector, ArchiveCabInspector, ArchiveArInspector,
        ArchiveRpmInspector, ArchiveIsoInspector, ArchiveCpioInspector, ArchiveSquashfsInspector,
    )
}
