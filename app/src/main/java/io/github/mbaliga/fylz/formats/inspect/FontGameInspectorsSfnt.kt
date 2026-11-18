package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** A parsed table directory entry plus a loader that returns the (decompressed) table bytes. */
internal class FgSfntView(val tags: List<String>, val load: (String, Int) -> ByteArray?)

internal object FgFont {
    private const val MAX_TABLES = 256
    private const val NAME_MAX = 1 shl 20
    private val CHARSET_CACHE = HashMap<String, java.nio.charset.Charset?>()

    private fun charset(n: String): java.nio.charset.Charset? = synchronized(CHARSET_CACHE) {
        CHARSET_CACHE.getOrPut(n) { try { java.nio.charset.Charset.forName(n) } catch (t: Throwable) { null } }
    }

    class NameRec(val platform: Int, val enc: Int, val lang: Int, val id: Int, val text: String)

    fun decodeName(platform: Int, enc: Int, raw: ByteArray): String? {
        val cs = when (platform) {
            0 -> Charsets.UTF_16BE
            3 -> when (enc) {
                0, 1, 10 -> Charsets.UTF_16BE
                2 -> charset("Shift_JIS")
                3 -> charset("GBK")
                4 -> charset("Big5")
                else -> null
            }
            1 -> if (enc == 0) (charset("x-MacRoman") ?: Charsets.ISO_8859_1) else null
            else -> null
        } ?: return null
        val s = Fg.clean(String(raw, cs)).trim()
        return s.ifEmpty { null }
    }

    fun readNames(t: ByteArray): List<NameRec> {
        if (t.size < 6) return emptyList()
        val count = minOf(Bytes.u16be(t, 2), 1024)
        val strOff = Bytes.u16be(t, 4)
        val out = ArrayList<NameRec>()
        for (i in 0 until count) {
            val r = 6 + i * 12
            if (r + 12 > t.size) break
            val platform = Bytes.u16be(t, r)
            val enc = Bytes.u16be(t, r + 2)
            val lang = Bytes.u16be(t, r + 4)
            val id = Bytes.u16be(t, r + 6)
            val len = Bytes.u16be(t, r + 8)
            val off = Bytes.u16be(t, r + 10)
            val s = strOff + off
            if (len == 0 || s + len > t.size) continue
            val text = decodeName(platform, enc, t.copyOfRange(s, s + len)) ?: continue
            out.add(NameRec(platform, enc, lang, id, text))
        }
        return out
    }

    private fun rank(n: NameRec): Int = when {
        n.platform == 3 && n.lang == 0x409 -> 0
        n.platform == 3 -> 1
        n.platform == 0 -> 2
        n.platform == 1 && n.lang == 0 -> 3
        else -> 4
    }

    fun pick(names: List<NameRec>, id: Int): String? =
        names.filter { it.id == id }.minByOrNull { rank(it) }?.text

    private val WEIGHTS = mapOf(
        100 to "Thin", 200 to "ExtraLight", 300 to "Light", 400 to "Regular", 500 to "Medium",
        600 to "SemiBold", 700 to "Bold", 800 to "ExtraBold", 900 to "Black",
    )
    private val WIDTHS = mapOf(
        1 to "UltraCondensed", 2 to "ExtraCondensed", 3 to "Condensed", 4 to "SemiCondensed", 5 to "Normal",
        6 to "SemiExpanded", 7 to "Expanded", 8 to "ExtraExpanded", 9 to "UltraExpanded",
    )

    class Described(val full: String?, val family: String?, val sub: String?, val glyphs: Int?, val upem: Int?)

    /** Adds facts for one font to [out]; returns identifying names for the title. */
    fun describe(v: FgSfntView, out: MutableList<Fact>, warnings: MutableList<String>): Described {
        val names = fgSafe { v.load("name", NAME_MAX)?.let { readNames(it) } } ?: emptyList()
        val family = pick(names, 16) ?: pick(names, 1)
        val sub = pick(names, 17) ?: pick(names, 2)
        val full = pick(names, 4)
        fun nm(id: Int, label: String) { pick(names, id)?.let { out.add(Fact(label, it, "Names")) } }
        pick(names, 1)?.let { out.add(Fact("Family", it, "Names")) }
        pick(names, 2)?.let { out.add(Fact("Subfamily", it, "Names")) }
        if (pick(names, 16) != null) out.add(Fact("Typographic family", pick(names, 16)!!, "Names"))
        if (pick(names, 17) != null) out.add(Fact("Typographic subfamily", pick(names, 17)!!, "Names"))
        nm(4, "Full name"); nm(5, "Version"); nm(6, "PostScript name")
        nm(0, "Copyright"); nm(7, "Trademark"); nm(8, "Manufacturer"); nm(9, "Designer")
        nm(10, "Description"); nm(11, "Vendor URL"); nm(12, "Designer URL")
        nm(13, "License"); nm(14, "License URL")

        var upem: Int? = null
        var glyphs: Int? = null
        val head = fgSafe { v.load("head", 4096) }
        if (head != null && head.size >= 54) {
            upem = Bytes.u16be(head, 18)
            out.add(Fact("Units per em", upem.toString(), "Metrics"))
            out.add(Fact("Font revision", Fg.fixed(Bytes.u32be(head, 4).toInt()), "Metrics"))
            fun s16(o: Int) = Bytes.u16be(head, o).toShort().toInt()
            out.add(Fact("Bounding box", "${s16(36)}, ${s16(38)} to ${s16(40)}, ${s16(42)}", "Metrics"))
            val created = Bytes.u64be(head, 20)
            val modified = Bytes.u64be(head, 28)
            if (created != 0L) out.add(Fact("Created", Fg.time(created - 2082844800L), "Metrics"))
            if (modified != 0L) out.add(Fact("Modified", Fg.time(modified - 2082844800L), "Metrics"))
            val mac = Bytes.u16be(head, 44)
            val styles = ArrayList<String>()
            if (mac and 1 != 0) styles.add("bold")
            if (mac and 2 != 0) styles.add("italic")
            if (mac and 4 != 0) styles.add("underline")
            if (mac and 8 != 0) styles.add("outline")
            if (mac and 16 != 0) styles.add("shadow")
            if (mac and 32 != 0) styles.add("condensed")
            if (mac and 64 != 0) styles.add("extended")
            if (styles.isNotEmpty()) out.add(Fact("Mac style", styles.joinToString(", "), "Style"))
            if (Bytes.u32be(head, 12) != 0x5F0F3CF5L) warnings.add("head table magic number is not 0x5F0F3CF5")
            if (upem < 16 || upem > 16384) warnings.add("unitsPerEm $upem is outside the 16..16384 range")
        }
        val hhea = fgSafe { v.load("hhea", 64) }
        if (hhea != null && hhea.size >= 10) {
            fun s16(o: Int) = Bytes.u16be(hhea, o).toShort().toInt()
            out.add(Fact("Ascender / descender / line gap", "${s16(4)} / ${s16(6)} / ${s16(8)}", "Metrics"))
        }
        val maxp = fgSafe { v.load("maxp", 64) }
        if (maxp != null && maxp.size >= 6) {
            glyphs = Bytes.u16be(maxp, 4)
            out.add(Fact("Glyph count", glyphs.toString(), "Metrics"))
        }
        val os2 = fgSafe { v.load("OS/2", 256) }
        if (os2 != null && os2.size >= 10) {
            val w = Bytes.u16be(os2, 4)
            val wd = Bytes.u16be(os2, 6)
            out.add(Fact("Weight class", WEIGHTS[w]?.let { "$w ($it)" } ?: w.toString(), "Style"))
            out.add(Fact("Width class", WIDTHS[wd]?.let { "$wd ($it)" } ?: wd.toString(), "Style"))
            val fsType = Bytes.u16be(os2, 8)
            val emb = ArrayList<String>()
            if (fsType and 0x0E == 0) emb.add("installable") else {
                if (fsType and 2 != 0) emb.add("restricted license")
                if (fsType and 4 != 0) emb.add("preview and print")
                if (fsType and 8 != 0) emb.add("editable")
            }
            if (fsType and 0x100 != 0) emb.add("no subsetting")
            if (fsType and 0x200 != 0) emb.add("bitmap embedding only")
            out.add(Fact("Embedding", emb.joinToString(", "), "Style"))
            if (os2.size >= 64) {
                val sel = Bytes.u16be(os2, 62)
                val fl = ArrayList<String>()
                if (sel and 1 != 0) fl.add("italic")
                if (sel and 0x20 != 0) fl.add("bold")
                if (sel and 0x40 != 0) fl.add("regular")
                if (sel and 0x200 != 0) fl.add("oblique")
                if (fl.isNotEmpty()) out.add(Fact("Selection flags", fl.joinToString(", "), "Style"))
            }
            if (os2.size >= 62) {
                val vendor = Bytes.ascii(os2, 58, 4).trim()
                if (vendor.isNotEmpty()) out.add(Fact("Vendor ID", vendor, "Names"))
            }
        }
        val post = fgSafe { v.load("post", 64) }
        if (post != null && post.size >= 16) {
            out.add(Fact("Monospaced", if (Bytes.u32be(post, 12) != 0L) "yes" else "no", "Style"))
            out.add(Fact("Italic angle", Fg.fixed(Bytes.u32be(post, 4).toInt()), "Style"))
            val pv = Bytes.u32be(post, 0)
            val pn = when (pv) {
                0x00010000L -> "standard Macintosh names (format 1.0)"
                0x00020000L -> "glyph names stored (format 2.0)"
                0x00025000L -> "deprecated format 2.5"
                0x00030000L -> "no glyph names (format 3.0)"
                0x00040000L -> "format 4.0"
                else -> null
            }
            if (pn != null) out.add(Fact("Glyph names", pn, "Style"))
        }

        val outl = ArrayList<String>()
        if ("glyf" in v.tags) outl.add("TrueType (glyf)")
        if ("CFF " in v.tags) outl.add("PostScript (CFF)")
        if ("CFF2" in v.tags) outl.add("PostScript (CFF2)")
        if (outl.isEmpty()) {
            if (v.tags.any { it == "SVG " || it == "CBDT" || it == "sbix" || it == "EBDT" || it == "bdat" })
                outl.add("none (bitmap or SVG glyphs only)")
            else outl.add("none found")
        }
        out.add(Fact("Outlines", outl.joinToString(", "), "Outlines"))
        val feats = ArrayList<String>()
        if ("fvar" in v.tags) feats.add("variable")
        if ("COLR" in v.tags || "SVG " in v.tags || "CBDT" in v.tags || "sbix" in v.tags) feats.add("color glyphs")
        if ("GSUB" in v.tags || "GPOS" in v.tags) feats.add("OpenType layout")
        if ("kern" in v.tags) feats.add("kern table")
        if ("DSIG" in v.tags) feats.add("digital signature")
        if ("gasp" in v.tags) feats.add("gasp")
        if (feats.isNotEmpty()) out.add(Fact("Features", feats.joinToString(", "), "Outlines"))

        if ("fvar" in v.tags) {
            val fvar = fgSafe { v.load("fvar", 8192) }
            if (fvar != null && fvar.size >= 16) {
                val axOff = Bytes.u16be(fvar, 4)
                val axCount = Bytes.u16be(fvar, 8)
                val axSize = Bytes.u16be(fvar, 10)
                out.add(Fact("Variation axes", axCount.toString(), "Outlines"))
                out.add(Fact("Named instances", Bytes.u16be(fvar, 12).toString(), "Outlines"))
                if (axSize >= 20) {
                    val ax = ArrayList<String>()
                    for (i in 0 until minOf(axCount, 16)) {
                        val o = axOff + i * axSize
                        if (o + 20 > fvar.size) break
                        val tag = Bytes.ascii(fvar, o, 4)
                        ax.add("$tag ${Fg.fixed(Bytes.u32be(fvar, o + 4).toInt())}-${Fg.fixed(Bytes.u32be(fvar, o + 12).toInt())} (default ${Fg.fixed(Bytes.u32be(fvar, o + 8).toInt())})")
                    }
                    if (ax.isNotEmpty()) out.add(Fact("Axes", ax.joinToString("; "), "Outlines"))
                }
            }
        }
        out.add(Fact("Tables", "${v.tags.size}: " + Fg.list(v.tags.map { it.trim() }, 40), "Tables"))
        return Described(full, family, sub, glyphs, upem)
    }

    fun titleOf(d: Described, fileName: String?, fallback: String): String =
        d.full ?: listOfNotNull(d.family, d.sub).joinToString(" ").ifEmpty { null } ?: fileName ?: fallback

    /** Parse a plain sfnt table directory at [base]. Returns null if not an sfnt. */
    fun sfntView(io: FgIo, base: Long, warnings: MutableList<String>): Pair<FgSfntView, Long>? {
        val h = io.read(base, 12)
        if (h.size < 12) return null
        val ver = Bytes.u32be(h, 0)
        if (ver != 0x00010000L && ver != 0x4F54544FL && ver != 0x74727565L) return null
        val n = Bytes.u16be(h, 4)
        if (n == 0) return null
        val use = minOf(n, MAX_TABLES)
        if (n > MAX_TABLES) warnings.add("table directory lists $n tables; only the first $MAX_TABLES were read")
        val dir = io.read(base + 12, use * 16L)
        val tags = ArrayList<String>()
        val offs = HashMap<String, Pair<Long, Long>>()
        var bad = 0
        for (i in 0 until use) {
            val o = i * 16
            if (o + 16 > dir.size) { warnings.add("table directory is truncated"); break }
            val tag = Bytes.ascii(dir, o, 4)
            val off = Bytes.u32be(dir, o + 8)
            val len = Bytes.u32be(dir, o + 12)
            if (tag !in offs) { tags.add(tag); offs[tag] = Pair(off, len) }
            if (!io.inFile(off, len)) bad++
        }
        if (bad > 0) warnings.add("$bad table(s) extend beyond the end of the file")
        val view = FgSfntView(tags) { tag, max ->
            val e = offs[tag]
            if (e == null) null else io.read(e.first, minOf(e.second, max.toLong()))
        }
        return Pair(view, ver)
    }
}

/** TrueType / OpenType fonts and collections (.ttf, .otf, .ttc). */
object FontSfntInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("ttf", "otf", "ttc")
    private const val MAX_MEMBERS = 32

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = fgSafe { run(source, fileName) }

    private fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val io = FgIo(source)
        val head = io.read(0, 16)
        if (head.size < 12) return null
        val warnings = ArrayList<String>()
        val facts = ArrayList<Fact>()
        if (Bytes.startsWith(head, "ttcf")) {
            val ver = Bytes.u32be(head, 4)
            val num = Bytes.u32be(head, 8)
            if (num == 0L) return null
            val cnt = minOf(num, MAX_MEMBERS.toLong()).toInt()
            val offBytes = io.read(12, cnt * 4L)
            val members = ArrayList<Pair<Described2, Long>>()
            val first = ArrayList<Fact>()
            var firstDesc: FgFont.Described? = null
            var firstVer = 0L
            for (i in 0 until cnt) {
                if (i * 4 + 4 > offBytes.size) break
                val off = Bytes.u32be(offBytes, i * 4)
                val w = ArrayList<String>()
                val pv = FgFont.sfntView(io, off, w)
                if (pv == null) { warnings.add("collection member $i does not start with a valid sfnt header"); continue }
                if (i == 0) {
                    firstDesc = FgFont.describe(pv.first, first, warnings)
                    firstVer = pv.second
                    warnings.addAll(w)
                } else {
                    val tmp = ArrayList<Fact>()
                    val d = FgFont.describe(pv.first, tmp, ArrayList())
                    members.add(Pair(Described2(d.full ?: listOfNotNull(d.family, d.sub).joinToString(" ")), off))
                }
            }
            facts.add(Fact("Format", "TrueType Collection (ttcf header version ${(ver shr 16)}.${ver and 0xFFFF})", "Collection"))
            facts.add(Fact("Fonts in collection", num.toString(), "Collection"))
            if (num > cnt) warnings.add("only the first $cnt of $num collection members were examined")
            if (firstDesc == null) return null
            val memberNames = ArrayList<String>()
            memberNames.add(FgFont.titleOf(firstDesc, null, "font 0"))
            for (m in members) memberNames.add(m.first.name.ifEmpty { "(unnamed)" })
            facts.add(Fact("Members", Fg.list(memberNames, 32), "Collection"))
            facts.add(Fact("First member outlines flavor", if (firstVer == 0x4F54544FL) "OpenType CFF" else "TrueType", "Collection"))
            facts.addAll(first)
            return InspectionResult(
                "ttc", FgFont.titleOf(firstDesc, fileName, "Font collection"),
                "Font collection with $num member font(s)", facts, warnings,
            )
        }
        val pv = FgFont.sfntView(io, 0, warnings) ?: return null
        val flavor = when (pv.second) {
            0x4F54544FL -> "OpenType with CFF outlines (OTTO)"
            0x74727565L -> "TrueType (Apple 'true')"
            else -> "TrueType / OpenType (0x00010000)"
        }
        facts.add(Fact("Format", flavor, "Header"))
        facts.add(Fact("Table count", pv.first.tags.size.toString(), "Header"))
        val d = FgFont.describe(pv.first, facts, warnings)
        if (pv.second == 0x4F54544FL && "CFF " !in pv.first.tags && "CFF2" !in pv.first.tags)
            warnings.add("OTTO font has no CFF or CFF2 table")
        val id = if (pv.second == 0x4F54544FL) "otf" else "ttf"
        val kind = if (pv.second == 0x4F54544FL) "OpenType (CFF) font" else "TrueType font"
        val summary = kind + (d.glyphs?.let { ", $it glyphs" } ?: "") + (d.upem?.let { ", $it units/em" } ?: "")
        return InspectionResult(id, FgFont.titleOf(d, fileName, "Font"), summary, facts, warnings)
    }

    private class Described2(val name: String)
}

/** WOFF 1.0 web fonts. Table data is zlib-compressed, so names and metrics are read too. */
object FontWoffInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("woff")
    private const val MAX_TABLES = 256

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = fgSafe { run(source, fileName) }

    private fun flavorName(f: Long): String = when (f) {
        0x00010000L -> "TrueType"
        0x4F54544FL -> "OpenType CFF"
        0x74727565L -> "TrueType ('true')"
        else -> Fg.hex(f, 8)
    }

    private fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val io = FgIo(source)
        val h = io.read(0, 44)
        if (h.size < 44 || !Bytes.startsWith(h, "wOFF")) return null
        val warnings = ArrayList<String>()
        val flavor = Bytes.u32be(h, 4)
        val length = Bytes.u32be(h, 8)
        val num = Bytes.u16be(h, 12)
        val totalSfnt = Bytes.u32be(h, 16)
        val major = Bytes.u16be(h, 20)
        val minor = Bytes.u16be(h, 22)
        val metaOff = Bytes.u32be(h, 24)
        val metaLen = Bytes.u32be(h, 28)
        val metaOrig = Bytes.u32be(h, 32)
        val privOff = Bytes.u32be(h, 36)
        val privLen = Bytes.u32be(h, 40)
        val facts = ArrayList<Fact>()
        facts.add(Fact("Flavor", flavorName(flavor), "Header"))
        facts.add(Fact("Table count", num.toString(), "Header"))
        facts.add(Fact("File length (declared)", Bytes.humanSize(length), "Header"))
        facts.add(Fact("Uncompressed font size", Bytes.humanSize(totalSfnt), "Header"))
        facts.add(Fact("Font version", "$major.$minor", "Header"))
        if (length != io.size) warnings.add("declared length $length differs from file size ${io.size}")
        if (totalSfnt > 0 && io.size > 0)
            facts.add(Fact("Compression ratio", "%.0f%% of original".format(java.util.Locale.ROOT, io.size * 100.0 / totalSfnt), "Header"))

        val use = minOf(num, MAX_TABLES)
        val dir = io.read(44, use * 20L)
        val tags = ArrayList<String>()
        val ent = HashMap<String, LongArray>()
        var bad = 0
        for (i in 0 until use) {
            val o = i * 20
            if (o + 20 > dir.size) { warnings.add("table directory is truncated"); break }
            val tag = Bytes.ascii(dir, o, 4)
            val off = Bytes.u32be(dir, o + 4)
            val comp = Bytes.u32be(dir, o + 8)
            val orig = Bytes.u32be(dir, o + 12)
            if (comp > orig) bad++
            if (!io.inFile(off, comp)) bad++
            if (tag !in ent) { tags.add(tag); ent[tag] = longArrayOf(off, comp, orig) }
        }
        if (num > MAX_TABLES) warnings.add("only the first $MAX_TABLES of $num tables were read")
        if (bad > 0) warnings.add("$bad table entries are inconsistent or extend past the end of the file")
        val view = FgSfntView(tags) { tag, max ->
            val e = ent[tag]
            if (e == null || e[1] > e[2]) null else {
                val raw = io.read(e[0], minOf(e[1], FgIo.MAX_CHUNK.toLong()))
                if (e[1] == e[2]) raw.copyOf(minOf(raw.size, max))
                else if (raw.size.toLong() < e[1]) null
                else {
                    val r = Fg.inflate(raw, 0, raw.size, minOf(e[2], max.toLong()).toInt(), false)
                    if (r.error && r.bytes.isEmpty()) null else r.bytes
                }
            }
        }
        val d = FgFont.describe(view, facts, warnings)

        var text: String? = null
        if (metaLen in 1..(1 shl 20) && metaOrig in 1..(1 shl 22) && io.inFile(metaOff, metaLen)) {
            val raw = io.read(metaOff, metaLen)
            if (raw.size.toLong() == metaLen) {
                val r = Fg.inflate(raw, 0, raw.size, minOf(metaOrig, FormatInspector.MAX_TEXT_CHARS.toLong()).toInt(), false)
                if (r.bytes.isNotEmpty()) {
                    val xml = Fg.utf8(r.bytes)
                    text = xml.take(FormatInspector.MAX_TEXT_CHARS)
                    Regex("<vendor[^>]*\\bname=\"([^\"]*)\"").find(xml)?.let {
                        facts.add(Fact("Metadata vendor", Fg.clean(it.groupValues[1]), "Metadata"))
                    }
                    Regex("<license[^>]*\\burl=\"([^\"]*)\"").find(xml)?.let {
                        facts.add(Fact("Metadata license URL", Fg.clean(it.groupValues[1]), "Metadata"))
                    }
                }
            }
        }
        if (metaLen > 0) facts.add(Fact("Metadata block", "${Bytes.humanSize(metaLen)} compressed, ${Bytes.humanSize(metaOrig)} original", "Metadata"))
        if (privLen > 0) facts.add(Fact("Private data", "${Bytes.humanSize(privLen)} at offset $privOff", "Metadata"))
        val summary = "WOFF web font (${flavorName(flavor)})" + (d.glyphs?.let { ", $it glyphs" } ?: "")
        return InspectionResult("woff", FgFont.titleOf(d, fileName, "WOFF font"), summary, facts, warnings, text)
    }
}

/** WOFF2 web fonts. Table data is Brotli-compressed, so only the header and table directory are read. */
object FontWoff2Inspector : FormatInspector {
    override val formatIds: Set<String> = setOf("woff2")
    private const val MAX_TABLES = 256

    private val KNOWN = arrayOf(
        "cmap", "head", "hhea", "hmtx", "maxp", "name", "OS/2", "post", "cvt ", "fpgm", "glyf", "loca", "prep",
        "CFF ", "VORG", "EBDT", "EBLC", "gasp", "hdmx", "kern", "LTSH", "PCLT", "VDMX", "vhea", "vmtx", "BASE",
        "GDEF", "GPOS", "GSUB", "EBSC", "JSTF", "MATH", "CBDT", "CBLC", "COLR", "CPAL", "SVG ", "sbix", "acnt",
        "avar", "bdat", "bloc", "bsln", "cvar", "fdsc", "feat", "fmtx", "fvar", "gvar", "hsty", "just", "lcar",
        "mort", "morx", "opbd", "prop", "trak", "Zapf", "Silf", "Glat", "Gloc", "Feat", "Sill",
    )

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = fgSafe { run(source, fileName) }

    private fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val io = FgIo(source)
        val h = io.read(0, 48)
        if (h.size < 48 || !Bytes.startsWith(h, "wOF2")) return null
        val warnings = ArrayList<String>()
        val flavor = Bytes.u32be(h, 4)
        val length = Bytes.u32be(h, 8)
        val num = Bytes.u16be(h, 12)
        val totalSfnt = Bytes.u32be(h, 16)
        val totalComp = Bytes.u32be(h, 20)
        val major = Bytes.u16be(h, 24)
        val minor = Bytes.u16be(h, 26)
        val metaLen = Bytes.u32be(h, 32)
        val metaOrig = Bytes.u32be(h, 36)
        val privLen = Bytes.u32be(h, 44)
        val facts = ArrayList<Fact>()
        val fl = when (flavor) {
            0x00010000L -> "TrueType"
            0x4F54544FL -> "OpenType CFF"
            0x74746366L -> "font collection (ttcf)"
            else -> Fg.hex(flavor, 8)
        }
        facts.add(Fact("Flavor", fl, "Header"))
        facts.add(Fact("Table count", num.toString(), "Header"))
        facts.add(Fact("File length (declared)", Bytes.humanSize(length), "Header"))
        facts.add(Fact("Uncompressed font size", Bytes.humanSize(totalSfnt), "Header"))
        facts.add(Fact("Compressed data size", Bytes.humanSize(totalComp), "Header"))
        facts.add(Fact("Font version", "$major.$minor", "Header"))
        if (length != io.size) warnings.add("declared length $length differs from file size ${io.size}")
        if (metaLen > 0) facts.add(Fact("Metadata block", "${Bytes.humanSize(metaLen)} compressed, ${Bytes.humanSize(metaOrig)} original", "Metadata"))
        if (privLen > 0) facts.add(Fact("Private data", Bytes.humanSize(privLen), "Metadata"))

        // Table directory: variable-length entries. Read a bounded window.
        val dir = io.read(48, minOf(num, MAX_TABLES) * 24L + 64)
        var p = 0
        val tags = ArrayList<String>()
        var glyfTransformed: Boolean? = null
        var sumOrig = 0L
        var ok = true
        fun base128(): Long {
            var v = 0L
            for (i in 0 until 5) {
                if (p >= dir.size) { ok = false; return 0 }
                val b = dir[p++].toInt() and 0xFF
                if (i == 0 && b == 0x80) { ok = false; return 0 }
                v = (v shl 7) or (b and 0x7F).toLong()
                if (b and 0x80 == 0) return v
            }
            ok = false
            return 0
        }
        for (i in 0 until minOf(num, MAX_TABLES)) {
            if (p >= dir.size) { ok = false; break }
            val fb = dir[p++].toInt() and 0xFF
            val idx = fb and 0x3F
            val tv = fb shr 6
            val tag = if (idx == 63) {
                if (p + 4 > dir.size) { ok = false; break }
                val t = Bytes.ascii(dir, p, 4); p += 4; t
            } else KNOWN[idx]
            val orig = base128()
            if (!ok) break
            val transformed = if (tag == "glyf" || tag == "loca") tv == 0 else tv != 0
            if (transformed) { base128(); if (!ok) break }
            if (tag == "glyf") glyfTransformed = transformed
            sumOrig += orig
            tags.add(tag)
        }
        if (!ok) warnings.add("table directory is truncated or malformed")
        if (tags.isNotEmpty()) facts.add(Fact("Tables", "${tags.size}: " + Fg.list(tags.map { it.trim() }, 40), "Tables"))
        if (ok && num > 0) facts.add(Fact("Sum of table sizes", Bytes.humanSize(sumOrig), "Tables"))
        glyfTransformed?.let { facts.add(Fact("glyf transform", if (it) "applied" else "none", "Tables")) }
        val outl = when {
            "glyf" in tags -> "TrueType (glyf)"
            "CFF " in tags -> "PostScript (CFF)"
            else -> null
        }
        outl?.let { facts.add(Fact("Outlines", it, "Outlines")) }
        if ("fvar" in tags) facts.add(Fact("Features", "variable", "Outlines"))
        facts.add(Fact("Names and metrics", "not read: table data is Brotli-compressed", "Header"))
        return InspectionResult("woff2", fileName ?: "WOFF2 font", "WOFF2 web font ($fl), $num tables", facts, warnings)
    }
}
