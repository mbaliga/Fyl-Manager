package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

internal object Archive7zInspector : ArcBase() {
    override val formatIds: Set<String> = setOf("7z")
    private val SIG = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
    override fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val h = ArcUtil.readAt(source, 0, 32)
        if (h.size < 6 || !(0 until 6).all { h[it] == SIG[it] }) return null
        val facts = ArrayList<Fact>(); val warnings = ArrayList<String>()
        if (h.size < 32) return InspectionResult("7z", "7-Zip archive", "7-Zip archive (truncated header)", facts, listOf("Truncated signature header"))
        facts += Fact("Format version", "${Bytes.u8(h, 6)}.${Bytes.u8(h, 7)}", "Signature header")
        val crcOk = ArcUtil.crc32(h, 12, 20) == Bytes.u32le(h, 8)
        facts += Fact("Start header CRC", if (crcOk) "valid" else "mismatch", "Signature header")
        if (!crcOk) warnings += "Start header CRC mismatch"
        val off = Bytes.u64le(h, 12); val len = Bytes.u64le(h, 20); val ncrc = Bytes.u32le(h, 28)
        facts += Fact("Next header offset", off.toString(), "Signature header")
        facts += Fact("Next header size", len.toString(), "Signature header")
        val size = source.size
        if (len == 0L) {
            facts += Fact("Contents", "empty archive (no header)", "Header")
            return InspectionResult("7z", "7-Zip archive", "7-Zip archive, empty", facts, warnings)
        }
        if (off < 0 || len < 0 || off > size - 32 || len > size - 32 - off) {
            warnings += "Next header lies outside the file: archive is truncated or damaged"
            return InspectionResult("7z", "7-Zip archive", "7-Zip archive (header not reachable)", facts, warnings)
        }
        val n = ArcUtil.clampInt(len, 4 * 1024 * 1024)
        val nh = ArcUtil.readAt(source, 32 + off, n)
        if (len <= n) {
            val ok = ArcUtil.crc32(nh, 0, nh.size) == ncrc
            facts += Fact("Next header CRC", if (ok) "valid" else "mismatch", "Header")
            if (!ok) warnings += "Next header CRC mismatch"
        }
        var summary = "7-Zip archive"
        when (Bytes.u8(nh, 0)) {
            0x01 -> facts += Fact("Header type", "plain header (file names stored uncompressed)", "Header")
            0x17 -> {
                facts += Fact("Header type", "encoded (compressed) header", "Header")
                val scan = nh.copyOf(minOf(nh.size, 4096))
                var aes = false
                for (i in 0..scan.size - 4) if (scan[i].toInt() == 0x06 && Bytes.u8(scan, i + 1) == 0xF1 && scan[i + 2].toInt() == 0x07 && scan[i + 3].toInt() == 0x01) { aes = true; break }
                facts += Fact("Header encryption", if (aes) "likely: AES-256 coder id (06F10701) found in header stream info; file names are hidden" else "no AES coder id seen in header stream info", "Header")
                if (aes) summary = "7-Zip archive, headers encrypted"
            }
            else -> warnings += "Unrecognised next header type 0x%02x".format(Bytes.u8(nh, 0))
        }
        facts += Fact("Archive size", ArcUtil.size(size), "Header")
        return InspectionResult("7z", "7-Zip archive", summary, facts, warnings)
    }
}

internal object ArchiveRarInspector : ArcBase() {
    override val formatIds: Set<String> = setOf("rar")
    private const val MAX_WALK = 2000
    private const val MAX_LISTED = 20

    override fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val h = ArcUtil.readAt(source, 0, 8)
        if (!ArcUtil.eq(h, 0, "Rar!\u001A\u0007")) return null
        return when (Bytes.u8(h, 6)) {
            0 -> rar4(source)
            1 -> if (Bytes.u8(h, 7) == 0) rar5(source) else null
            else -> null
        }
    }

    private fun rar4(source: ByteSource): InspectionResult {
        val facts = ArrayList<Fact>(); val warnings = ArrayList<String>()
        facts += Fact("Version", "RAR 1.5-4.x (RAR4 format)", "Archive")
        val size = source.size
        var pos = 7L; var files = 0; var dirs = 0; var encFiles = 0; var unp = 0L; var n = 0
        var sawMain = false; var encHeaders = false
        var listed = 0
        while (n++ < MAX_WALK) {
            val b = ArcUtil.readAt(source, pos, 11 + 2048)
            if (b.size < 7) break
            val type = Bytes.u8(b, 2); val fl = Bytes.u16le(b, 3); val hs = Bytes.u16le(b, 5)
            if (hs < 7) { warnings += "Invalid header size at offset $pos"; break }
            var add = 0L
            if (type == 0x73) {
                sawMain = true
                val f = ArrayList<String>()
                if (fl and 1 != 0) f += "volume"; if (fl and 2 != 0) f += "comment"; if (fl and 4 != 0) f += "locked"
                if (fl and 8 != 0) f += "solid"; if (fl and 0x10 != 0) f += "new volume naming"; if (fl and 0x20 != 0) f += "authenticity info"
                if (fl and 0x40 != 0) f += "recovery record"; if (fl and 0x80 != 0) f += "headers encrypted"; if (fl and 0x100 != 0) f += "first volume"
                facts += Fact("Main header flags", if (f.isEmpty()) "none" else f.joinToString(", "), "Archive")
                if (fl and 0x80 != 0) { encHeaders = true; break }
            } else if (type == 0x74 && b.size >= 32) {
                add = Bytes.u32le(b, 7)
                val u = Bytes.u32le(b, 11)
                val nl = Bytes.u16le(b, 26)
                var nameOff = 32
                if (fl and 0x100 != 0) { nameOff += 8 }
                val isDir = (fl and 0xE0) == 0xE0
                if (isDir) dirs++ else { files++; unp += u }
                if (fl and 4 != 0) encFiles++
                if (listed < MAX_LISTED && nameOff + nl <= b.size) {
                    facts += Fact(ArcUtil.cstr(b, nameOff, minOf(nl, 1024)), if (isDir) "directory" else "$u bytes unpacked" + (if (fl and 4 != 0) ", encrypted" else ""), "Entry list")
                    listed++
                }
            } else if (type == 0x7B) break
            if (fl and 0x8000 != 0 && type != 0x74) add = Bytes.u32le(b, 7)
            val next = pos + hs + add
            if (next <= pos || next > size) { if (type == 0x74 && next > size) warnings += "Archive is truncated"; break }
            pos = next
        }
        if (!sawMain && !encHeaders) warnings += "Main archive header not found"
        if (!encHeaders) {
            facts += Fact("Files", files.toString() + if (n > MAX_WALK) " (scan limit reached)" else "", "Archive")
            facts += Fact("Directories", dirs.toString(), "Archive")
            facts += Fact("Total unpacked size (low 32 bits per file)", ArcUtil.size(unp), "Archive")
            if (encFiles > 0) facts += Fact("Encrypted files", encFiles.toString(), "Archive")
        } else warnings += "File headers are encrypted; contents cannot be listed"
        return InspectionResult("rar", "RAR archive", "RAR4 archive" + if (encHeaders) ", headers encrypted" else ", $files files", facts, warnings)
    }

    private fun rar5(source: ByteSource): InspectionResult {
        val facts = ArrayList<Fact>(); val warnings = ArrayList<String>()
        facts += Fact("Version", "RAR 5.0+ (RAR5 format)", "Archive")
        val size = source.size
        var pos = 8L; var files = 0; var dirs = 0; var unp = 0L; var n = 0; var listed = 0
        var encHeaders = false; var sawMain = false; var ended = false
        while (n++ < MAX_WALK) {
            val b = ArcUtil.readAt(source, pos, 4096)
            if (b.size < 7) break
            val r = ArcRd(b, 4)
            val hsize = r.vint(3)
            if (hsize <= 0 || hsize > 2 * 1024 * 1024) { warnings += "Invalid header size at offset $pos"; break }
            val bodyStart = r.p
            val type = r.vint(); val hflags = r.vint()
            if (type < 0 || hflags < 0) break
            var extra = 0L; var dataSize = 0L
            if (hflags and 1L != 0L) extra = r.vint()
            if (hflags and 2L != 0L) dataSize = r.vint()
            if (extra < 0 || dataSize < 0) break
            when (type.toInt()) {
                1 -> {
                    sawMain = true
                    val af = r.vint()
                    if (af >= 0) {
                        val f = ArrayList<String>()
                        if (af and 1L != 0L) f += "volume"; if (af and 2L != 0L) f += "volume number present"
                        if (af and 4L != 0L) f += "solid"; if (af and 8L != 0L) f += "recovery record"; if (af and 0x10L != 0L) f += "locked"
                        facts += Fact("Archive flags", if (f.isEmpty()) "none" else f.joinToString(", "), "Archive")
                    }
                }
                4 -> { encHeaders = true }
                2 -> {
                    val ff = r.vint(); val u = r.vint(); r.vint()
                    if (ff >= 0 && u >= 0) {
                        if (ff and 2L != 0L) r.p += 4
                        if (ff and 4L != 0L) r.p += 4
                        r.vint(); r.vint()
                        val nl = r.vint()
                        val isDir = ff and 1L != 0L
                        if (isDir) dirs++ else { files++; if (ff and 8L == 0L) unp += u }
                        if (listed < MAX_LISTED && nl in 0..1024 && r.p + nl <= b.size) {
                            facts += Fact(ArcUtil.utf8(b, r.p, nl.toInt()), if (isDir) "directory" else if (ff and 8L == 0L) "$u bytes unpacked" else "size unknown", "Entry list")
                            listed++
                        }
                    }
                }
                5 -> ended = true
            }
            if (encHeaders || ended) break
            val next = pos + bodyStart + hsize + dataSize
            if (next <= pos || next > size) { if (next > size) warnings += "Archive is truncated"; break }
            pos = next
        }
        if (!sawMain && !encHeaders) warnings += "Main archive header not found"
        if (encHeaders) warnings += "Headers are encrypted; contents cannot be listed"
        else {
            facts += Fact("Files", files.toString() + if (n > MAX_WALK) " (scan limit reached)" else "", "Archive")
            facts += Fact("Directories", dirs.toString(), "Archive")
            facts += Fact("Total unpacked size", ArcUtil.size(unp), "Archive")
        }
        facts += Fact("Headers encrypted", if (encHeaders) "yes" else "no", "Archive")
        return InspectionResult("rar", "RAR archive", "RAR5 archive" + if (encHeaders) ", headers encrypted" else ", $files files", facts, warnings)
    }
}

internal object ArchiveCabInspector : ArcBase() {
    override val formatIds: Set<String> = setOf("cab")
    override fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val h = ArcUtil.readAt(source, 0, 64)
        if (h.size < 4 || !ArcUtil.eq(h, 0, "MSCF")) return null
        if (h.size < 36) return InspectionResult("cab", "Cabinet archive", "Cabinet (truncated)", emptyList(), listOf("Truncated header"))
        val facts = ArrayList<Fact>(); val warnings = ArrayList<String>()
        val cabSize = Bytes.u32le(h, 8); val coffFiles = Bytes.u32le(h, 16)
        val vm = Bytes.u8(h, 24); val vM = Bytes.u8(h, 25)
        val nFolders = Bytes.u16le(h, 26); val nFiles = Bytes.u16le(h, 28); val flags = Bytes.u16le(h, 30)
        facts += Fact("Version", "$vM.$vm", "Header")
        facts += Fact("Cabinet size", ArcUtil.size(cabSize), "Header")
        facts += Fact("Folders", nFolders.toString(), "Header")
        facts += Fact("Files", nFiles.toString(), "Header")
        facts += Fact("Set ID", Bytes.u16le(h, 32).toString(), "Header")
        facts += Fact("Cabinet number in set", Bytes.u16le(h, 34).toString(), "Header")
        var p = 36
        var cbFolder = 0
        if (flags and 4 != 0) { cbFolder = Bytes.u8(h, 38); p += 4 + Bytes.u16le(h, 36) }
        val rest = ArcUtil.readAt(source, 0, p + 1024 + 64)
        if (flags and 1 != 0) { val a = ArcUtil.cstr(rest, p, 256); val b = ArcUtil.cstr(rest, p + a.length + 1, 256); p += a.length + b.length + 2; facts += Fact("Previous cabinet", a, "Header") }
        if (flags and 2 != 0) { val a = ArcUtil.cstr(rest, p, 256); val b = ArcUtil.cstr(rest, p + a.length + 1, 256); p += a.length + b.length + 2; facts += Fact("Next cabinet", a, "Header") }
        if (nFolders > 0) {
            val fb = ArcUtil.readAt(source, p.toLong(), 8)
            if (fb.size >= 8) {
                val tc = Bytes.u16le(fb, 6)
                val comp = when (tc and 0xF) {
                    0 -> "none"; 1 -> "MSZIP"; 2 -> "Quantum"; 3 -> "LZX (window 2^${(tc shr 8) and 0x1F})"; else -> "unknown ($tc)"
                }
                facts += Fact("First folder compression", comp, "Header")
            }
        }
        if (cabSize > source.size) warnings += "Header declares ${cabSize} bytes but the file is ${source.size}"
        var shown = 0; var listedCount = 0; var totalU = 0L
        if (coffFiles in 0..source.size) {
            val b = ArcUtil.readAt(source, coffFiles, 1024 * 1024)
            var q = 0
            while (listedCount < nFiles && q + 17 <= b.size) {
                val sz = Bytes.u32le(b, q)
                val name = ArcUtil.cstr(b, q + 16, 512)
                if (q + 16 + name.length + 1 > b.size) break
                totalU += sz; listedCount++
                if (shown < 20) {
                    val d = ArcUtil.dosDateTime(Bytes.u16le(b, q + 10), Bytes.u16le(b, q + 12))
                    facts += Fact(name, "$sz bytes" + (if (d != null) ", $d" else ""), "Entry list"); shown++
                }
                q += 16 + name.length + 1
            }
            if (listedCount < nFiles) warnings += "Only $listedCount of $nFiles file records could be read"
            else facts += Fact("Total uncompressed size", ArcUtil.size(totalU), "Header")
        } else warnings += "File table offset lies outside the file"
        return InspectionResult("cab", "Cabinet archive", "Cabinet, $nFiles files in $nFolders folders", facts, warnings)
    }
}
