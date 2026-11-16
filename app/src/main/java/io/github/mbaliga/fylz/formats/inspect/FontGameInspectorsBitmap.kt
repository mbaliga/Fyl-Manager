package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** X11 Bitmap Distribution Format fonts (.bdf). Text format; "bdf" is also BioSemi data, which is rejected. */
object FontBdfInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("bdf")
    private const val READ = 4 * 1024 * 1024
    private const val MAX_PROPS = 512

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = fgSafe { run(source, fileName) }

    private fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val io = FgIo(source)
        val data = io.read(0, READ.toLong())
        if (data.size < 12) return null
        val text = Fg.utf8(data)
        if (!text.startsWith("STARTFONT ")) return null
        val warnings = ArrayList<String>()
        val facts = ArrayList<Fact>()
        var font: String? = null
        var declaredChars: Int? = null
        var startChars = 0
        var size: String? = null
        var bbox: String? = null
        var declaredProps: Int? = null
        val props = LinkedHashMap<String, String>()
        var version: String? = null
        var inProps = false
        var endFont = false
        val comments = StringBuilder()
        var lines = 0
        for (raw in text.lineSequence()) {
            if (++lines > 2_000_000) break
            val line = raw.trimEnd('\r')
            if (line.startsWith("STARTCHAR")) { startChars++; continue }
            when {
                line.startsWith("STARTFONT ") -> version = line.substring(10).trim()
                line.startsWith("FONT ") && font == null -> font = line.substring(5).trim()
                line.startsWith("SIZE ") && size == null -> size = line.substring(5).trim()
                line.startsWith("FONTBOUNDINGBOX ") && bbox == null -> bbox = line.substring(16).trim()
                line.startsWith("STARTPROPERTIES") -> { declaredProps = line.substring(15).trim().toIntOrNull(); inProps = true }
                line.startsWith("ENDPROPERTIES") -> inProps = false
                line.startsWith("CHARS ") && declaredChars == null -> declaredChars = line.substring(6).trim().toIntOrNull()
                line.startsWith("COMMENT") && comments.length < 4000 -> comments.append(line.substring(7).trim()).append('\n')
                line.startsWith("ENDFONT") -> endFont = true
                inProps && props.size < MAX_PROPS -> {
                    val sp = line.indexOf(' ')
                    if (sp > 0) props.putIfAbsent(line.substring(0, sp), line.substring(sp + 1).trim().trim('"'))
                }
            }
            if (endFont) break
        }
        val truncated = io.size > data.size
        facts.add(Fact("BDF version", version ?: "?", "Header"))
        font?.let { facts.add(Fact("Font name", Fg.clean(it), "Header")) }
        size?.split(' ')?.filter { it.isNotEmpty() }?.let { s ->
            if (s.size >= 3) facts.add(Fact("Size", "${s[0]} pt at ${s[1]}x${s[2]} dpi", "Header"))
        }
        bbox?.split(' ')?.filter { it.isNotEmpty() }?.let { b ->
            if (b.size >= 4) facts.add(Fact("Bounding box", "${b[0]} x ${b[1]} pixels, offset ${b[2]}, ${b[3]}", "Header"))
        }
        declaredChars?.let { facts.add(Fact("Glyph count (declared)", it.toString(), "Glyphs")) }
        if (!truncated) facts.add(Fact("Glyph count (found)", startChars.toString(), "Glyphs"))
        else facts.add(Fact("Glyphs found in first ${Bytes.humanSize(data.size.toLong())}", startChars.toString(), "Glyphs"))
        for (k in listOf("FAMILY_NAME", "WEIGHT_NAME", "SLANT", "SETWIDTH_NAME", "SPACING", "FOUNDRY", "POINT_SIZE", "PIXEL_SIZE",
            "RESOLUTION_X", "RESOLUTION_Y", "CHARSET_REGISTRY", "CHARSET_ENCODING", "FONT_ASCENT", "FONT_DESCENT", "DEFAULT_CHAR", "COPYRIGHT")) {
            props[k]?.let { facts.add(Fact(k, Fg.clean(it).take(300), "Properties")) }
        }
        if (declaredProps != null) facts.add(Fact("Properties (declared)", declaredProps.toString(), "Properties"))
        if (declaredChars != null && !truncated && endFont && declaredChars != startChars)
            warnings.add("CHARS declares $declaredChars glyphs but $startChars STARTCHAR entries were found")
        if (!endFont && !truncated) warnings.add("ENDFONT not found")
        if (truncated) warnings.add("only the first ${Bytes.humanSize(data.size.toLong())} was examined")
        val family = props["FAMILY_NAME"]
        val title = family ?: font?.let { Fg.clean(it) } ?: fileName ?: "BDF font"
        return InspectionResult("bdf", title, "BDF bitmap font" + (declaredChars?.let { ", $it glyphs" } ?: ""), facts, warnings,
            comments.toString().trim().ifEmpty { null })
    }
}

/** X11 Portable Compiled Format fonts (.pcf). */
object FontPcfInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("pcf")
    private const val MAX_TABLES = 32
    private const val T_PROPERTIES = 1
    private const val T_BITMAPS = 8
    private const val T_ENCODINGS = 32
    private const val T_METRICS = 4

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = fgSafe { run(source, fileName) }

    private fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val io = FgIo(source)
        val h = io.read(0, 8)
        if (h.size < 8 || Bytes.u8(h, 0) != 1 || !Bytes.startsWith(h, "fcp", 1)) return null
        val n = Bytes.u32le(h, 4)
        val warnings = ArrayList<String>()
        if (n == 0L) return null
        val use = minOf(n, MAX_TABLES.toLong()).toInt()
        if (n > use) warnings.add("table of contents lists $n tables; only $use were read")
        val toc = io.read(8, use * 16L)
        class Tab(val type: Int, val format: Long, val size: Long, val off: Long)
        val tabs = ArrayList<Tab>()
        for (i in 0 until use) {
            val o = i * 16
            if (o + 16 > toc.size) { warnings.add("table of contents is truncated"); break }
            tabs.add(Tab(Bytes.u32le(toc, o).toInt(), Bytes.u32le(toc, o + 4), Bytes.u32le(toc, o + 8), Bytes.u32le(toc, o + 12)))
        }
        val facts = ArrayList<Fact>()
        facts.add(Fact("Tables", tabs.size.toString(), "Header"))
        var bad = 0
        for (t in tabs) if (!io.inFile(t.off, t.size)) bad++
        if (bad > 0) warnings.add("$bad table(s) extend beyond the end of the file")
        fun be(format: Long) = format and 4L != 0L
        fun i32(b: ByteArray, o: Int, big: Boolean): Long = (if (big) Bytes.u32be(b, o) else Bytes.u32le(b, o)).toInt().toLong()

        val bm = tabs.firstOrNull { it.type == T_BITMAPS }
        var glyphs: Long? = null
        if (bm != null && io.inFile(bm.off, 8)) {
            val b = io.read(bm.off, 8)
            if (b.size == 8) {
                glyphs = i32(b, 4, be(Bytes.u32le(b, 0)))
                facts.add(Fact("Glyph count", glyphs.toString(), "Glyphs"))
            }
        } else {
            val me = tabs.firstOrNull { it.type == T_METRICS }
            if (me != null && io.inFile(me.off, 8)) {
                val b = io.read(me.off, 8)
                if (b.size == 8) {
                    val fmt = Bytes.u32le(b, 0)
                    val c = if (fmt and 0x100L != 0L) (if (be(fmt)) Bytes.u16be(b, 4) else Bytes.u16le(b, 4)).toLong() else i32(b, 4, be(fmt))
                    glyphs = c
                    facts.add(Fact("Glyph count", c.toString(), "Glyphs"))
                }
            }
        }
        if (tabs.any { it.type == T_ENCODINGS }) facts.add(Fact("Encoding table", "present", "Glyphs"))

        var family: String? = null
        val pt = tabs.firstOrNull { it.type == T_PROPERTIES }
        if (pt != null && io.inFile(pt.off, 8) && pt.size in 8..(1L shl 20)) {
            val t = io.read(pt.off, pt.size)
            if (t.size >= 8) {
                val fmt = Bytes.u32le(t, 0)
                val big = be(fmt)
                val np = i32(t, 4, big)
                if (np in 0..2048) {
                    val propEnd = 8 + np * 9
                    val strStart = ((propEnd + 3) / 4) * 4 + 4
                    val strBase = strStart
                    if (strBase <= t.size) {
                        fun str(off: Long): String? {
                            val s = strBase + off
                            if (off < 0 || s >= t.size) return null
                            var e = s.toInt()
                            while (e < t.size && t[e].toInt() != 0) e++
                            return Fg.clean(String(t, s.toInt(), e - s.toInt(), Charsets.ISO_8859_1))
                        }
                        val want = setOf("FONT", "FAMILY_NAME", "WEIGHT_NAME", "SLANT", "SETWIDTH_NAME", "SPACING", "FOUNDRY",
                            "POINT_SIZE", "PIXEL_SIZE", "RESOLUTION_X", "RESOLUTION_Y", "COPYRIGHT", "CHARSET_REGISTRY",
                            "CHARSET_ENCODING", "FONT_ASCENT", "FONT_DESCENT")
                        val got = LinkedHashMap<String, String>()
                        for (i in 0 until np.toInt()) {
                            val o = 8 + i * 9
                            if (o + 9 > t.size) { warnings.add("property table is truncated"); break }
                            val nameOff = i32(t, o, big)
                            val isStr = Bytes.u8(t, o + 4) != 0
                            val value = i32(t, o + 5, big)
                            val name = str(nameOff) ?: continue
                            if (name !in want) continue
                            val v = if (isStr) (str(value) ?: continue) else value.toString()
                            got.putIfAbsent(name, v)
                        }
                        got["FONT"]?.let { facts.add(Fact("Font name", it, "Header")) }
                        family = got["FAMILY_NAME"]
                        for (k in want) if (k != "FONT") got[k]?.let { facts.add(Fact(k, it.take(300), "Properties")) }
                        facts.add(Fact("Properties", np.toString(), "Properties"))
                        if (got["FONT"] != null && family == null) family = got["FONT"]
                    }
                }
            }
        }
        val compressed = tabs.firstOrNull { it.type == T_METRICS }?.let { it.format and 0x100L != 0L }
        compressed?.let { facts.add(Fact("Compressed metrics", if (it) "yes" else "no", "Glyphs")) }
        return InspectionResult("pcf", family ?: fileName ?: "PCF font",
            "PCF bitmap font" + (glyphs?.let { ", $it glyphs" } ?: ""), facts, warnings)
    }
}
