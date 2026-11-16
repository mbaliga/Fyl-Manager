package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

private val U = DocumentInspectorsUtil

/** MOBI / AZW / AZW3 (PalmDB + MOBI header + EXTH) inspector. */
object DocumentInspectorsMobi : FormatInspector {
    override val formatIds: Set<String> = setOf("mobi", "azw")

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? =
        try { impl(source, fileName) } catch (t: Throwable) { null }

    private val EXTH_LABELS = mapOf(
        100 to "Author", 101 to "Publisher", 102 to "Imprint", 103 to "Description", 104 to "ISBN",
        105 to "Subject", 106 to "Published", 108 to "Contributor", 109 to "Rights", 113 to "ASIN",
        204 to "Creator software", 501 to "Document type", 503 to "Updated title", 504 to "ASIN (504)",
        524 to "Language",
    )

    private fun impl(source: ByteSource, fileName: String?): InspectionResult? {
        val size = source.size
        if (size < 78 + 8) return null
        val h = U.readSafe(source, 0, 78 + 16)
        if (h.size < 78 + 8) return null
        val type = String(h, 60, 4, Charsets.ISO_8859_1)
        val creator = String(h, 64, 4, Charsets.ISO_8859_1)
        val numRecords = Bytes.u16be(h, 76)
        if (numRecords < 1) return null
        val rec0 = Bytes.u32be(h, 78)
        if (rec0 < 78 || rec0 + 16 > size) return null
        var rec0Len = if (numRecords >= 2 && h.size >= 78 + 16) Bytes.u32be(h, 86) - rec0 else 0L
        if (rec0Len <= 0L || rec0Len > 256 * 1024L) rec0Len = minOf(size - rec0, 64 * 1024L)
        rec0Len = minOf(rec0Len, size - rec0)
        val r = U.readSafe(source, rec0, rec0Len.toInt())
        if (r.size < 16) return null
        val hasMobi = r.size >= 24 && String(r, 16, 4, Charsets.ISO_8859_1) == "MOBI"
        val ext = U.extensionOf(fileName)
        val isBook = type == "BOOK" && creator == "MOBI"
        if (!hasMobi && !isBook) return null

        val out = DocumentInspectorsFacts()
        val gDb = "PalmDB"; val gHdr = "MOBI header"; val gBook = "Book"
        var nameEnd = 0
        while (nameEnd < 32 && h[nameEnd].toInt() != 0) nameEnd++
        out.add(gDb, "Database name", String(h, 0, nameEnd, Charsets.ISO_8859_1).filter { it.code in 0x20..0x7E })
        out.add(gDb, "Type / creator", "$type / $creator".filter { it.code in 0x20..0x7E })
        out.add(gDb, "Record count", numRecords.toLong())
        if (!isBook) out.warn("PalmDB type/creator is not BOOK/MOBI.")

        val comp = Bytes.u16be(r, 0)
        out.add(gHdr, "Compression", when (comp) { 1 -> "none"; 2 -> "PalmDOC (LZ77)"; 17480 -> "HUFF/CDIC"; else -> "code $comp" })
        out.add(gHdr, "Uncompressed text length", Bytes.u32be(r, 4))
        out.add(gHdr, "Text records", Bytes.u16be(r, 8).toLong())
        out.add(gHdr, "Text record size", Bytes.u16be(r, 10).toLong())
        val enc = Bytes.u16be(r, 12)
        out.add(gHdr, "Encryption", when (enc) { 0 -> "none"; 1 -> "old Mobipocket encryption"; 2 -> "Mobipocket encryption"; else -> "type $enc" })
        if (enc != 0) out.warn("The book text is encrypted (DRM); it cannot be read without the key.")

        var title: String? = null
        var encoding = Charsets.ISO_8859_1.let { java.nio.charset.Charset.forName("windows-1252") }
        var kf8 = false
        if (!hasMobi) {
            out.warn("No MOBI header in the first record: this is a plain PalmDOC-style book.")
        } else {
            val hlen = Bytes.u32be(r, 20)
            out.add(gHdr, "Header length", hlen)
            val mtype = Bytes.u32be(r, 24)
            out.add(gHdr, "MOBI type", when (mtype) {
                2L -> "Mobipocket book"; 3L -> "PalmDoc book"; 4L -> "audio"
                257L -> "news"; 258L -> "news feed"; 259L -> "news magazine"
                else -> mtype.toString()
            })
            val cp = Bytes.u32be(r, 28)
            out.add(gHdr, "Text encoding", when (cp) { 65001L -> "UTF-8"; 1252L -> "Windows-1252"; else -> "code page $cp" })
            if (cp == 65001L) encoding = Charsets.UTF_8
            val ver = Bytes.u32be(r, 36)
            out.add(gHdr, "File version", ver)
            if (ver == 8L) { kf8 = true; out.add(gHdr, "Format", "KF8 (AZW3)") }
            else if (ver in 1L..7L) out.add(gHdr, "Format", "MOBI 6 era")
            if (hlen >= 92) out.add(gHdr, "Minimum reader version", Bytes.u32be(r, 104))

            val fno = Bytes.u32be(r, 84)
            val fnl = Bytes.u32be(r, 88)
            if (fnl in 1..1024 && fno >= 0 && fno + fnl <= r.size) {
                title = String(r, fno.toInt(), fnl.toInt(), encoding).trim { it <= ' ' }.ifEmpty { null }
                out.add(gBook, "Title", title)
            }
            // EXTH
            val exthFlags = if (hlen >= 116) Bytes.u32be(r, 128) else 0L
            val exthStart = 16L + hlen
            if ((exthFlags and 0x40L) != 0L && exthStart + 12 <= r.size && hlen in 16..4096) {
                val p0 = exthStart.toInt()
                if (String(r, p0, 4, Charsets.ISO_8859_1) == "EXTH") {
                    val cnt = minOf(Bytes.u32be(r, p0 + 8), 256L).toInt()
                    var p = p0 + 12
                    var n = 0
                    val seen = HashMap<Int, Int>()
                    while (n++ < cnt && p + 8 <= r.size) {
                        val t = Bytes.u32be(r, p).toInt()
                        val l = Bytes.u32be(r, p + 4)
                        if (l < 8 || p + l > r.size) break
                        val dl = (l - 8).toInt()
                        val label = EXTH_LABELS[t]
                        if (label != null && dl in 0..2048) {
                            val k = seen.merge(t, 1, Int::plus) ?: 1
                            val v = String(r, p + 8, dl, encoding).trim { it <= ' ' }
                            val name = if (k > 1) "$label ($k)" else label
                            if (t == 503 && v.isNotEmpty()) title = v
                            out.add(gBook, name, v)
                        } else if (t == 201 && dl == 4) out.add(gBook, "Cover image record offset", Bytes.u32be(r, p + 8))
                        else if (t == 121 && dl == 4) out.add(gHdr, "KF8 boundary record", Bytes.u32be(r, p + 8))
                        p += l.toInt()
                    }
                } else out.warn("EXTH flag is set but no EXTH block was found.")
            }
        }
        if (title == null) title = out.facts.firstOrNull { it.group == gDb && it.label == "Database name" }?.value
        val lowerExt = ext
        if (lowerExt != null && lowerExt in formatIds && kf8 && lowerExt == "mobi") out.warn("The file uses the KF8 format but has a .mobi extension.")
        return InspectionResult(
            formatId = if (lowerExt != null && lowerExt in formatIds) lowerExt else if (kf8) "azw" else "mobi",
            title = title ?: U.baseName(fileName) ?: "MOBI book",
            summary = (if (kf8) "KF8 e-book" else "Mobipocket e-book") + (if (enc != 0) " · encrypted" else ""),
            facts = out.facts,
            warnings = out.warnings,
        )
    }
}
