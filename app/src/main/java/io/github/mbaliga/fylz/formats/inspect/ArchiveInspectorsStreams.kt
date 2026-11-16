package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

internal object ArchiveGzInspector : ArcBase() {
    override val formatIds: Set<String> = setOf("gz")
    private fun os(n: Int) = when (n) {
        0 -> "FAT"; 1 -> "Amiga"; 2 -> "VMS"; 3 -> "Unix"; 4 -> "VM/CMS"; 5 -> "Atari TOS"; 6 -> "HPFS"
        7 -> "Macintosh"; 8 -> "Z-System"; 9 -> "CP/M"; 10 -> "TOPS-20"; 11 -> "NTFS"; 12 -> "QDOS"
        13 -> "Acorn RISCOS"; 255 -> "unknown"; else -> "code $n"
    }
    override fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val h = ArcUtil.readAt(source, 0, 4096)
        if (h.size < 10 || Bytes.u8(h, 0) != 0x1F || Bytes.u8(h, 1) != 0x8B) return null
        val facts = ArrayList<Fact>(); val warnings = ArrayList<String>()
        val cm = Bytes.u8(h, 2); val flg = Bytes.u8(h, 3)
        val mtime = Bytes.u32le(h, 4); val xfl = Bytes.u8(h, 8); val osn = Bytes.u8(h, 9)
        facts += Fact("Compression method", if (cm == 8) "deflate" else "code $cm", "Header")
        facts += Fact("Modification time", if (mtime == 0L) "not set" else ArcUtil.fmtEpoch(mtime), "Header")
        facts += Fact("Operating system", os(osn), "Header")
        facts += Fact("Extra flags", when (xfl) { 2 -> "maximum compression (slowest)"; 4 -> "fastest"; 0 -> "none"; else -> xfl.toString() }, "Header")
        val flags = ArrayList<String>()
        if (flg and 1 != 0) flags += "text"; if (flg and 2 != 0) flags += "header CRC"
        if (flg and 4 != 0) flags += "extra field"; if (flg and 8 != 0) flags += "name"; if (flg and 16 != 0) flags += "comment"
        facts += Fact("Header flags", if (flags.isEmpty()) "none" else flags.joinToString(", "), "Header")
        if (flg and 0xE0 != 0) warnings += "Reserved flag bits set"
        var p = 10
        if (flg and 4 != 0) { val xl = Bytes.u16le(h, p); facts += Fact("Extra field length", xl.toString(), "Header"); p += 2 + xl }
        var name: String? = null
        if (flg and 8 != 0) { name = ArcUtil.cstr(h, p, 1024); p += name.length + 1; if (p > h.size) name = null
            if (name != null) facts += Fact("Original name", name, "Header") }
        if (flg and 16 != 0 && p < h.size) { val c = ArcUtil.cstr(h, p, 1024); facts += Fact("Comment", c, "Header"); p += c.length + 1 }
        if (flg and 2 != 0) p += 2
        if (cm != 8) warnings += "Unknown compression method"
        val size = source.size
        if (size >= 18) {
            val t = ArcUtil.readAt(source, size - 8, 8)
            if (t.size == 8 && t.all { it.toInt() == 0 }) {
                warnings += "Last 8 bytes are zero: trailing padding or an empty member; trailer facts not reported"
            } else {
                facts += Fact("CRC-32 (last member)", "%08x".format(java.util.Locale.ROOT, Bytes.u32le(t, 0)), "Trailer")
                facts += Fact("Uncompressed size mod 2^32 (last member)", Bytes.u32le(t, 4).toString(), "Trailer")
            }
        } else warnings += "Too short to contain a trailer"
        // Peek: does the payload start like a tar archive?
        var inner: String? = null
        if (cm == 8 && p in 0 until h.size) {
            val out = ArcUtil.inflateRaw(h, p, h.size - p, 512)
            if (out.size >= 512 && ArcUtil.eq(out, 257, "ustar")) inner = "tar archive"
        }
        if (inner != null) facts += Fact("Payload", inner, "Header")
        val label = if (inner != null) "gzip-compressed tar" else "gzip file"
        return InspectionResult("gz", "gzip", "$label, ${Bytes.humanSize(size)}", facts, warnings)
    }
}

internal object ArchiveBz2Inspector : ArcBase() {
    override val formatIds: Set<String> = setOf("bz2")
    override fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val h = ArcUtil.readAt(source, 0, 10)
        if (h.size < 4 || !ArcUtil.eq(h, 0, "BZh") || Bytes.u8(h, 3) !in 0x31..0x39) return null
        val lvl = Bytes.u8(h, 3) - 0x30
        val facts = ArrayList<Fact>(); val warnings = ArrayList<String>()
        facts += Fact("Block size level", lvl.toString(), "Header")
        facts += Fact("Maximum block size", "${lvl * 100_000} bytes", "Header")
        if (h.size >= 10) {
            val m = (0 until 6).joinToString("") { "%02x".format(Bytes.u8(h, 4 + it)) }
            when (m) {
                "314159265359" -> facts += Fact("First block", "data block (magic 0x314159265359)", "Header")
                "177245385090" -> facts += Fact("First block", "end of stream (empty file)", "Header")
                else -> warnings += "Unexpected first block magic $m"
            }
        } else warnings += "Truncated before first block"
        facts += Fact("Compressed size", ArcUtil.size(source.size), "Header")
        return InspectionResult("bz2", "bzip2", "bzip2 file, level $lvl", facts, warnings)
    }
}

internal object ArchiveXzInspector : ArcBase() {
    override val formatIds: Set<String> = setOf("xz")
    private fun check(n: Int) = when (n) {
        0 -> "none"; 1 -> "CRC32"; 4 -> "CRC64"; 10 -> "SHA-256"; else -> "reserved ($n)"
    }
    override fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val h = ArcUtil.readAt(source, 0, 12)
        if (h.size < 6 || !ArcUtil.eq(h, 0, "ý7zXZ\u0000")) return null
        val facts = ArrayList<Fact>(); val warnings = ArrayList<String>()
        if (h.size < 12) return InspectionResult("xz", "xz", "xz file (truncated header)", facts, listOf("Truncated stream header"))
        val flagsOk = Bytes.u8(h, 6) == 0
        val chk = Bytes.u8(h, 7) and 0x0F
        facts += Fact("Stream flags", "%02x %02x".format(Bytes.u8(h, 6), Bytes.u8(h, 7)), "Stream")
        facts += Fact("Integrity check", check(chk), "Stream")
        if (!flagsOk || Bytes.u8(h, 7) and 0xF0 != 0) warnings += "Reserved stream flag bits set"
        val crcOk = ArcUtil.crc32(h, 6, 2) == Bytes.u32le(h, 8)
        facts += Fact("Header CRC32", if (crcOk) "valid" else "mismatch", "Stream")
        if (!crcOk) warnings += "Stream header CRC32 mismatch"
        val size = source.size
        if (size >= 24) {
            val f = ArcUtil.readAt(source, size - 12, 12)
            if (ArcUtil.eq(f, 10, "YZ")) {
                val back = (Bytes.u32le(f, 4) + 1) * 4
                if (back in 8..(1024 * 1024) && size - 12 - back >= 12) {
                    val idx = ArcUtil.readAt(source, size - 12 - back, back.toInt())
                    if (idx.isNotEmpty() && idx[0].toInt() == 0) {
                        val r = ArcRd(idx, 1)
                        val n = r.vint(9)
                        if (n in 0..100_000_000L) {
                            var unc = 0L; var comp = 0L; var padded = 0L; var ok = true
                            var i = 0L
                            while (i < n && i < 200_000) {
                                val u = r.vint(9); val v = r.vint(9)
                                if (u < 0 || v < 0) { ok = false; break }
                                comp += u; unc += v; padded += (u + 3) / 4 * 4; i++
                            }
                            val single = ok && i == n && 12 + padded + back + 12 == size
                            val sfx = if (single) "" else " (last stream)"
                            facts += Fact("Blocks (index records)$sfx", n.toString(), "Index")
                            if (ok && i == n) {
                                facts += Fact("Uncompressed size$sfx", ArcUtil.size(unc), "Index")
                                facts += Fact("Sum of unpadded block sizes$sfx", ArcUtil.size(comp), "Index")
                            }
                            if (!single) warnings += "File does not consist of one stream (concatenated streams or padding?); index facts describe the last stream only"
                        }
                    } else warnings += "Index indicator byte not found"
                }
                if (Bytes.u8(f, 8) != Bytes.u8(h, 6) || Bytes.u8(f, 9) != Bytes.u8(h, 7)) warnings += "Footer stream flags differ from header (concatenated streams?)"
            } else warnings += "Stream footer not found (truncated, or trailing padding/concatenated streams)"
        } else warnings += "Too short to contain a footer"
        facts += Fact("Compressed size", ArcUtil.size(size), "Stream")
        return InspectionResult("xz", "xz", "xz file, ${check(chk)} check", facts, warnings)
    }
}

internal object ArchiveZstdInspector : ArcBase() {
    override val formatIds: Set<String> = setOf("zst")
    override fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val h = ArcUtil.readAt(source, 0, 32)
        val facts = ArrayList<Fact>(); val warnings = ArrayList<String>()
        if (h.size >= 8 && Bytes.u8(h, 0) in 0x50..0x5F && Bytes.u8(h, 1) == 0x2A && Bytes.u8(h, 2) == 0x4D && Bytes.u8(h, 3) == 0x18) {
            facts += Fact("First frame", "skippable frame", "Frame")
            facts += Fact("Skippable frame data size", Bytes.u32le(h, 4).toString(), "Frame")
            return InspectionResult("zst", "Zstandard", "Zstandard file starting with a skippable frame", facts, warnings)
        }
        if (h.size < 5 || Bytes.u32le(h, 0) != 0xFD2FB528L) return null
        val d = Bytes.u8(h, 4)
        val fcsFlag = d shr 6; val single = d and 0x20 != 0; val cksum = d and 4 != 0; val dictFlag = d and 3
        if (d and 8 != 0) warnings += "Reserved bit set in frame header descriptor"
        var p = 5
        var window = -1L
        if (!single) {
            val w = Bytes.u8(h, p++)
            val log = 10 + (w shr 3); val base = 1L shl log
            window = base + (base / 8) * (w and 7)
        }
        val dictLen = when (dictFlag) { 0 -> 0; 1 -> 1; 2 -> 2; else -> 4 }
        var dictId = 0L
        for (i in 0 until dictLen) dictId = dictId or (Bytes.u8(h, p + i).toLong() shl (8 * i))
        p += dictLen
        val fcsLen = when (fcsFlag) { 0 -> if (single) 1 else 0; 1 -> 2; 2 -> 4; else -> 8 }
        var content = -1L; var contentHuge = false; var contentRaw = 0L
        if (fcsLen > 0) {
            content = 0
            for (i in 0 until fcsLen) content = content or (Bytes.u8(h, p + i).toLong() shl (8 * i))
            if (content < 0) { contentHuge = true; contentRaw = content; content = -1L }
            if (fcsLen == 2) content += 256
        }
        if (p + fcsLen > h.size) return InspectionResult("zst", "Zstandard", "Zstandard file (truncated frame header)", facts, warnings + "Truncated frame header")
        if (single && !contentHuge) window = content
        facts += Fact("Window size", if (window >= 0) "$window bytes" else "unknown", "Frame")
        facts += Fact("Single segment", if (single) "yes" else "no", "Frame")
        facts += Fact("Dictionary ID", if (dictFlag == 0) "none" else dictId.toString(), "Frame")
        facts += Fact("Content size", if (content >= 0) "$content bytes" else if (contentHuge) java.lang.Long.toUnsignedString(contentRaw) + " bytes" else "not recorded", "Frame")
        facts += Fact("Content checksum", if (cksum) "yes (XXH64)" else "no", "Frame")
        facts += Fact("Compressed size", ArcUtil.size(source.size), "Frame")
        return InspectionResult("zst", "Zstandard", "Zstandard file" + (if (content >= 0) ", $content bytes uncompressed (first frame)" else ""), facts, warnings)
    }
}
