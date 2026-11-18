package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

private val U = DocumentInspectorsUtil

/** Legacy Office (doc / xls / ppt) inspector built on the OLE2 compound file reader. */
object DocumentInspectorsOle : FormatInspector {
    override val formatIds: Set<String> = setOf("doc", "xls", "ppt")

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? =
        try { impl(source, fileName) } catch (t: Throwable) { null }

    private class OleTime(val ticks: Long)

    /** First section of a property-set stream -> property id to value (String, Long, or OleTime). */
    private fun parseProps(b: ByteArray): Map<Int, Any> {
        val res = LinkedHashMap<Int, Any>()
        if (b.size < 48 || Bytes.u16le(b, 0) != 0xFFFE) return res
        val sec = Bytes.u32le(b, 44)
        if (sec < 0 || sec + 8 > b.size) return res
        val so = sec.toInt()
        val count = minOf(Bytes.u32le(b, so + 4), 256L).toInt()
        val offs = ArrayList<Pair<Int, Int>>()
        for (i in 0 until count) {
            val o = so + 8 + 8 * i
            if (o + 8 > b.size) break
            val id = Bytes.u32le(b, o)
            val vo = Bytes.u32le(b, o + 4)
            if (vo < 0 || so + vo + 8 > b.size || id > 0xFFFF) continue
            offs.add(id.toInt() to (so + vo.toInt()))
        }
        var cp = 1252
        for ((id, p) in offs) if (id == 1 && (Bytes.u16le(b, p) and 0x0FFF) == 2) cp = Bytes.u16le(b, p + 4)
        val cs = U.charsetFor(if (cp == 0) 1252 else cp)
        for ((id, p) in offs) {
            val type = Bytes.u16le(b, p) and 0x0FFF
            if ((Bytes.u16le(b, p) and 0xF000) != 0) continue
            when (type) {
                0x02 -> res[id] = Bytes.u16le(b, p + 4).toShort().toLong()
                0x12 -> res[id] = Bytes.u16le(b, p + 4).toLong()
                0x03 -> res[id] = Bytes.u32le(b, p + 4).toInt().toLong()
                0x13 -> res[id] = Bytes.u32le(b, p + 4)
                0x1E -> {
                    val len = Bytes.u32le(b, p + 4)
                    if (len in 1..4096 && p + 8 + len <= b.size) {
                        var l = len.toInt()
                        while (l > 0 && b[p + 8 + l - 1].toInt() == 0) l--
                        res[id] = String(b, p + 8, l, cs)
                    }
                }
                0x1F -> {
                    val len = Bytes.u32le(b, p + 4)
                    if (len in 1..4096 && p + 8 + len * 2 <= b.size) {
                        var l = len.toInt()
                        while (l > 0 && Bytes.u16le(b, p + 8 + (l - 1) * 2) == 0) l--
                        res[id] = String(b, p + 8, l * 2, Charsets.UTF_16LE)
                    }
                }
                0x40 -> if (p + 12 <= b.size) res[id] = OleTime(Bytes.u64le(b, p + 4))
            }
        }
        return res
    }

    private fun str(m: Map<Int, Any>, id: Int): String? = (m[id] as? String)?.trim()?.ifEmpty { null }
    private fun num(m: Map<Int, Any>, id: Int): Long? = m[id] as? Long
    private fun time(m: Map<Int, Any>, id: Int): String? = (m[id] as? OleTime)?.let { U.filetimeIso(it.ticks) }

    private fun clsidText(c: ByteArray): String {
        if (c.size < 16) return ""
        return "%08X-%04X-%04X-%02X%02X-%02X%02X%02X%02X%02X%02X".format(
            Bytes.u32le(c, 0), Bytes.u16le(c, 4), Bytes.u16le(c, 6),
            c[8].toInt() and 0xFF, c[9].toInt() and 0xFF, c[10].toInt() and 0xFF, c[11].toInt() and 0xFF,
            c[12].toInt() and 0xFF, c[13].toInt() and 0xFF, c[14].toInt() and 0xFF, c[15].toInt() and 0xFF,
        )
    }

    private fun clsidName(text: String): String? = when (text) {
        "00020906-0000-0000-C000-000000000046" -> "Microsoft Word 97-2003 document"
        "00020820-0000-0000-C000-000000000046" -> "Microsoft Excel 97-2003 worksheet"
        "64818D10-4F9B-11CF-86EA-00AA00B929E8" -> "Microsoft PowerPoint 97-2003 presentation"
        else -> null
    }

    private fun wordVersion(nFib: Int): String? = when (nFib) {
        0x00C1 -> "Word 97"
        0x00D9 -> "Word 2000"
        0x0101 -> "Word 2002"
        0x010C -> "Word 2003"
        0x0112 -> "Word 2007 or later"
        else -> null
    }

    private class Sheet(val name: String, val state: Int, val type: Int)

    private fun biff(data: ByteArray, out: DocumentInspectorsFacts): List<Sheet> {
        val sheets = ArrayList<Sheet>()
        var pos = 0
        var count = 0
        var biff8 = true
        while (pos + 4 <= data.size && count++ < 20000) {
            val id = Bytes.u16le(data, pos)
            val len = Bytes.u16le(data, pos + 2)
            val body = pos + 4
            if (pos == 0) {
                if (id != 0x0809) {
                    out.warn("The first workbook record is not a BIFF5/8 BOF (record id 0x%04X).".format(id))
                    return sheets
                }
                val ver = Bytes.u16le(data, body)
                val dt = Bytes.u16le(data, body + 2)
                biff8 = ver == 0x0600
                out.add("Workbook", "BIFF version", when (ver) {
                    0x0600 -> "BIFF8 (Excel 97-2003)"
                    0x0500 -> "BIFF5/7 (Excel 5.0-95)"
                    else -> "0x%04X".format(ver)
                })
                out.add("Workbook", "First substream", when (dt) {
                    0x0005 -> "workbook globals"
                    0x0010 -> "worksheet"
                    0x0020 -> "chart"
                    0x0040 -> "macro sheet"
                    else -> "type 0x%04X".format(dt)
                })
            }
            when (id) {
                0x0085 -> if (len >= 8 && body + len <= data.size && sheets.size < 500) {
                    val state = data[body + 4].toInt() and 0xFF
                    val type = data[body + 5].toInt() and 0xFF
                    val cch = data[body + 6].toInt() and 0xFF
                    val name = if (biff8) {
                        val flags = data[body + 7].toInt() and 0xFF
                        val wide = (flags and 1) != 0
                        val bytes = if (wide) cch * 2 else cch
                        val end = minOf(body + len, body + 8 + bytes)
                        String(data, body + 8, maxOf(0, end - (body + 8)), if (wide) Charsets.UTF_16LE else Charsets.ISO_8859_1)
                    } else {
                        val end = minOf(body + len, body + 7 + cch)
                        String(data, body + 7, maxOf(0, end - (body + 7)), Charsets.ISO_8859_1)
                    }
                    sheets.add(Sheet(name, state and 3, type))
                }
                0x002F -> out.add("Workbook", "Encrypted", "yes (FILEPASS record)")
                0x0022 -> if (Bytes.u16le(data, body) == 1) out.add("Workbook", "Date system", "1904")
                0x000A -> return sheets
            }
            pos = body + len
        }
        return sheets
    }

    private fun impl(source: ByteSource, fileName: String?): InspectionResult? {
        val ole = DocumentInspectorsOleFile.open(source) ?: return null
        val out = DocumentInspectorsFacts()
        val gC = "Container"; val gS = "Streams"; val gD = "Document"; val gStat = "Statistics"
        val gApp = "Application"
        for (w in ole.warnings) out.warn(w)

        out.add(gC, "Compound file version", "${ole.majorVersion}.${ole.minorVersion}")
        out.add(gC, "Sector size", "${ole.sectorSize} bytes")
        out.add(gC, "Mini sector size", "${ole.miniSectorSize} bytes")
        out.add(gC, "Mini stream cutoff", "${ole.miniCutoff} bytes")
        out.add(gC, "FAT sectors", ole.fatSectorCount)
        val root = ole.entries.firstOrNull { it.type == 5 }
        if (root != null) {
            val ct = clsidText(root.clsid)
            if (root.clsid.any { it.toInt() != 0 }) {
                out.add(gC, "Root CLSID", ct + (clsidName(ct)?.let { " ($it)" } ?: ""))
            }
        } else out.warn("The directory has no root entry.")
        if (ole.directoryTruncated) out.warn("The directory is very large; only part of it was read.")

        val listing = ole.reachable().filter { it.type == 1 || it.type == 2 }
        val streams = listing.filter { it.type == 2 }
        out.add(gS, "Stream count", streams.size.toLong())
        out.add(gS, "Storage count", listing.count { it.type == 1 }.toLong())
        for (e in listing.take(40)) {
            out.add(gS, DocumentInspectorsOleFile.display(e.path), if (e.type == 1) "storage" else "${e.size} bytes")
        }
        if (listing.size > 40) out.add(gS, "More entries", "${listing.size - 40} not listed")

        val names = streams.map { it.name }.toSet()
        val kind = when {
            "WordDocument" in names -> "doc"
            "Workbook" in names || "Book" in names -> "xls"
            "PowerPoint Document" in names -> "ppt"
            else -> null
        }
        val ext = U.extensionOf(fileName)
        if (kind == null) return null
        val id = kind
        val kindLabel = when (kind) {
            "doc" -> "Word 97-2003 document"
            "xls" -> "Excel 97-2003 workbook"
            "ppt" -> "PowerPoint 97-2003 presentation"
            else -> null
        }
        if (kindLabel != null) out.add(gApp, "Detected as", "$kindLabel (from the \"${when (kind) { "doc" -> "WordDocument"; "xls" -> if ("Workbook" in names) "Workbook" else "Book"; else -> "PowerPoint Document" }}\" stream)")
        else out.warn("No WordDocument, Workbook or PowerPoint Document stream: this is a generic OLE2 file, not a legacy Word/Excel/PowerPoint document.")
        if ("EncryptedPackage" in names) {
            out.add(gD, "Encrypted", "yes (EncryptedPackage stream: password-protected OOXML)")
            out.warn("Password-protected Office Open XML package stored in an OLE2 wrapper.")
        }
        if (kind != null && ext != null && ext in formatIds && ext != kind) out.warn("The file extension .$ext does not match the content, which looks like .$kind.")

        // Word FIB
        if (kind == "doc") {
            val st = ole.find("WordDocument")
            val fib = if (st != null) ole.readStream(st, 64) else ByteArray(0)
            if (fib.size >= 12 && Bytes.u16le(fib, 0) == 0xA5EC) {
                val nFib = Bytes.u16le(fib, 2)
                val flags = Bytes.u16le(fib, 10)
                out.add(gApp, "Word FIB version (nFib)", "0x%04X".format(nFib) + (wordVersion(nFib)?.let { " ($it)" } ?: ""))
                if ((flags and 0x0001) != 0) out.add(gD, "Document template", "yes")
                if ((flags and 0x0100) != 0) {
                    out.add(gD, "Encrypted", "yes (FIB fEncrypted)")
                    out.warn("The Word document is encrypted; contents cannot be read without the password.")
                }
                out.add(gC, "Table stream", if ((flags and 0x0200) != 0) "1Table" else "0Table")
            } else out.warn("The WordDocument stream does not start with a valid FIB (wIdent 0xA5EC).")
        }

        // Excel globals
        var sheets: List<Sheet> = emptyList()
        if (kind == "xls") {
            val st = ole.find("Workbook") ?: ole.find("Book")
            if (st != null) sheets = biff(ole.readStream(st, 512 * 1024), out)
            if (sheets.isNotEmpty()) {
                out.add("Sheets", "Sheet count", sheets.size.toLong())
                for ((i, s) in sheets.withIndex()) {
                    val extra = ArrayList<String>()
                    when (s.state) { 1 -> extra.add("hidden"); 2 -> extra.add("very hidden") }
                    when (s.type) { 1 -> extra.add("macro sheet"); 2 -> extra.add("chart"); 6 -> extra.add("VB module") }
                    out.add("Sheets", "Sheet ${i + 1}", s.name + if (extra.isEmpty()) "" else " (${extra.joinToString(", ")})")
                }
            }
        }

        // Summary information
        var title: String? = null
        var summaryProps: Map<Int, Any> = emptyMap()
        val si = ole.find("\u0005SummaryInformation")
        if (si != null) {
            val p = parseProps(ole.readStream(si, 128 * 1024))
            summaryProps = p
            title = str(p, 2)
            out.add(gD, "Title", title)
            out.add(gD, "Subject", str(p, 3))
            out.add(gD, "Author", str(p, 4))
            out.add(gD, "Keywords", str(p, 5))
            out.add(gD, "Comments", str(p, 6))
            out.add(gD, "Template", str(p, 7))
            out.add(gD, "Last saved by", str(p, 8))
            out.add(gD, "Revision number", str(p, 9))
            out.add(gD, "Created", time(p, 12))
            out.add(gD, "Last saved", time(p, 13))
            out.add(gD, "Last printed", time(p, 11))
            (p[10] as? OleTime)?.let { if (it.ticks > 0) out.add(gD, "Total editing time (minutes)", it.ticks / 600_000_000L) }
            out.add(gStat, "Pages", num(p, 14))
            out.add(gStat, "Words", num(p, 15))
            out.add(gStat, "Characters", num(p, 16))
            out.add(gApp, "Created with", str(p, 18))
            num(p, 19)?.let { sec ->
                val parts = ArrayList<String>()
                if ((sec and 1L) != 0L) parts.add("password protected")
                if ((sec and 2L) != 0L) parts.add("read-only recommended")
                if ((sec and 4L) != 0L) parts.add("read-only enforced")
                if ((sec and 8L) != 0L) parts.add("locked for annotations")
                if (parts.isNotEmpty()) out.add(gD, "Document security", parts.joinToString(", "))
            }
        }
        val ds = ole.find("\u0005DocumentSummaryInformation")
        if (ds != null) {
            val p = parseProps(ole.readStream(ds, 128 * 1024))
            out.add(gD, "Category", str(p, 2))
            out.add(gD, "Manager", str(p, 14))
            out.add(gD, "Company", str(p, 15))
            out.add(gStat, "Lines", num(p, 5))
            out.add(gStat, "Paragraphs", num(p, 6))
            out.add(gStat, "Slides", num(p, 7))
            out.add(gStat, "Notes", num(p, 8))
            out.add(gStat, "Hidden slides", num(p, 9))
        }
        if (kind != null && si == null) out.add(gD, "Summary information", "no SummaryInformation stream")

        val bits = ArrayList<String>()
        bits.add(kindLabel ?: "OLE2 compound file")
        if (sheets.isNotEmpty()) bits.add("${sheets.size} sheets")
        num(summaryProps, 14)?.let { if (kind == "doc") bits.add("$it pages") }
        return InspectionResult(id, title ?: U.baseName(fileName) ?: (kindLabel ?: "OLE2 compound file"), bits.joinToString(" · "), out.facts, out.warnings)
    }
}
