package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** Windows icon / cursor containers. */
internal object IcoInspector : SafeInspector() {
    override val formatIds = setOf("ico", "cur")

    override fun doInspect(source: ByteSource, fileName: String?): InspectionResult? {
        val h = source.read(0, 6)
        if (h.size < 6 || Bytes.u16le(h, 0) != 0) return null
        val type = Bytes.u16le(h, 2)
        if (type != 1 && type != 2) return null
        val count = Bytes.u16le(h, 4)
        val isCur = type == 2
        val warnings = ArrayList<String>()
        if (count == 0) return null
        val shown = minOf(count, 256)
        if (count > shown) warnings.add("Only the first $shown of $count directory entries are listed")
        val dir = source.read(6, shown * 16)
        if (dir.size < 16 || Bytes.u8(dir, 3) != 0 || Bytes.u32le(dir, 12) < 6L + 16L * count) return null
        if (dir.size < shown * 16) warnings.add("Directory is truncated")
        val f = FactList()
        f.add("Container", "Type", if (isCur) "Cursor (.cur)" else "Icon (.ico)")
        f.add("Container", "Images", "$count")
        val sizes = ArrayList<String>()
        for (i in 0 until minOf(shown, dir.size / 16)) {
            val o = i * 16
            val w = Bytes.u8(dir, o).let { if (it == 0) 256 else it }
            val ht = Bytes.u8(dir, o + 1).let { if (it == 0) 256 else it }
            val bytes = Bytes.u32le(dir, o + 8); val off = Bytes.u32le(dir, o + 12)
            var bpp = if (isCur) 0 else Bytes.u16le(dir, o + 6)
            var fmt = "BMP"
            if (off + 8 <= source.size) {
                val d = source.read(off, 16)
                if (d.size >= 8 && Bytes.u8(d, 0) == 0x89 && Bytes.startsWith(d, "PNG", 1)) fmt = "PNG"
                else if (!isCur || true) {
                    if (d.size >= 16 && Bytes.u32le(d, 0) == 40L && bpp == 0) bpp = Bytes.u16le(d, 14)
                }
            } else warnings.add("Image ${i + 1} data lies beyond the end of the file")
            sizes.add(Img.dims(w.toLong(), ht.toLong()))
            if (i < 64) {
                val extra = if (isCur) ", hotspot (${Bytes.u16le(dir, o + 4)}, ${Bytes.u16le(dir, o + 6)})" else if (bpp > 0) ", $bpp-bit" else ""
                f.add("Images", "Image ${i + 1}", "${Img.dims(w.toLong(), ht.toLong())}$extra, $fmt, ${Bytes.humanSize(bytes)}")
            }
        }
        val summary = (if (isCur) "Cursor" else "Icon") + " with $count image" + (if (count == 1) "" else "s") +
            if (sizes.isNotEmpty()) " (" + sizes.distinct().take(8).joinToString(", ") + ")" else ""
        return InspectionResult(if (isCur) "cur" else "ico", Img.title(fileName, if (isCur) "Cursor" else "Icon"), summary, f.list, warnings)
    }
}

/** Adobe Photoshop PSD / PSB. */
internal object PsdInspector : SafeInspector() {
    override val formatIds = setOf("psd")

    private fun modeName(m: Int) = when (m) {
        0 -> "Bitmap"; 1 -> "Greyscale"; 2 -> "Indexed"; 3 -> "RGB"; 4 -> "CMYK"
        7 -> "Multichannel"; 8 -> "Duotone"; 9 -> "Lab"; else -> "Unknown ($m)"
    }

    override fun doInspect(source: ByteSource, fileName: String?): InspectionResult? {
        val h = source.read(0, 30)
        if (h.size < 26 || !Bytes.startsWith(h, "8BPS")) return null
        val ver = Bytes.u16be(h, 4)
        val ch = Bytes.u16be(h, 12); val ht = Bytes.u32be(h, 14); val w = Bytes.u32be(h, 18)
        val depth = Bytes.u16be(h, 22); val mode = Bytes.u16be(h, 24)
        val warnings = ArrayList<String>()
        if (ver != 1 && ver != 2) return null
        val maxDim = if (ver == 2) 300000L else 30000L
        if (w > maxDim || ht > maxDim || w == 0L || ht == 0L) warnings.add("Dimensions are outside the range allowed by version $ver")
        if (depth !in setOf(1, 8, 16, 32)) warnings.add("Unusual bit depth $depth")
        val f = FactList()
        f.add("Image", "Format", if (ver == 2) "PSB (large document)" else "PSD")
        f.add("Image", "Dimensions", Img.dims(w, ht))
        f.add("Image", "Channels", "$ch")
        f.add("Image", "Bit depth", "$depth")
        f.add("Image", "Colour mode", modeName(mode))
        // layer count: skip colour mode data + image resources, then read the layer-info header
        try {
            val cmd = Bytes.u32be(h, 26)
            var p = 30L + cmd
            val irLen = Bytes.u32be(source.read(p, 4), 0)
            p += 4 + irLen
            val wide = ver == 2
            val lmBytes = source.read(p, if (wide) 8 else 4)
            val lmLen = if (wide) Bytes.u64be(lmBytes, 0) else Bytes.u32be(lmBytes, 0)
            p += if (wide) 8 else 4
            if (lmLen > 0 && lmBytes.size == (if (wide) 8 else 4)) {
                val liBytes = source.read(p, if (wide) 10 else 6)
                val need = if (wide) 10 else 6
                if (liBytes.size == need) {
                    val liLen = if (wide) Bytes.u64be(liBytes, 0) else Bytes.u32be(liBytes, 0)
                    if (liLen > 0) {
                        val n = Bytes.u16be(liBytes, if (wide) 8 else 4).toShort().toInt()
                        f.add("Layers", "Layer count", Math.abs(n).toString())
                        if (n < 0) f.add("Layers", "First alpha channel", "holds merged-result transparency")
                    } else f.add("Layers", "Layer count", "0")
                }
            }
        } catch (_: Throwable) { }
        return InspectionResult("psd", Img.title(fileName, "Photoshop document"), "${if (ver == 2) "PSB" else "PSD"} ${Img.dims(w, ht)}, ${modeName(mode)}, $depth-bit", f.list, warnings)
    }
}

/** DirectDraw Surface textures. */
internal object DdsInspector : SafeInspector() {
    override val formatIds = setOf("dds")

    private fun dxgi(n: Long) = when (n) {
        28L -> "R8G8B8A8_UNORM"; 29L -> "R8G8B8A8_UNORM_SRGB"; 71L -> "BC1_UNORM"; 72L -> "BC1_UNORM_SRGB"
        74L -> "BC2_UNORM"; 75L -> "BC2_UNORM_SRGB"; 77L -> "BC3_UNORM"; 78L -> "BC3_UNORM_SRGB"
        80L -> "BC4_UNORM"; 83L -> "BC5_UNORM"; 87L -> "B8G8R8A8_UNORM"; 91L -> "B8G8R8A8_UNORM_SRGB"
        95L -> "BC6H_UF16"; 96L -> "BC6H_SF16"; 98L -> "BC7_UNORM"; 99L -> "BC7_UNORM_SRGB"
        else -> "DXGI format $n"
    }

    override fun doInspect(source: ByteSource, fileName: String?): InspectionResult? {
        val h = source.read(0, 148)
        if (h.size < 128 || !Bytes.startsWith(h, "DDS ")) return null
        val warnings = ArrayList<String>()
        if (Bytes.u32le(h, 4) != 124L || Bytes.u32le(h, 76) != 32L) return null
        val flags = Bytes.u32le(h, 8)
        val ht = Bytes.u32le(h, 12); val w = Bytes.u32le(h, 16)
        val depth = Bytes.u32le(h, 24); val mips = Bytes.u32le(h, 28)
        val pfFlags = Bytes.u32le(h, 80)
        val four = Bytes.ascii(h, 84, 4)
        val bits = Bytes.u32le(h, 88)
        val caps2 = Bytes.u32le(h, 112)
        val f = FactList()
        f.add("Image", "Dimensions", Img.dims(w, ht))
        if (flags and 0x800000L != 0L && depth > 1) f.add("Image", "Depth (volume texture)", "$depth")
        val mipCount = if (flags and 0x20000L != 0L) maxOf(mips, 1L) else 1L
        f.add("Image", "Mipmap levels", "$mipCount")
        if (caps2 and 0x200L != 0L) f.add("Image", "Cubemap", "yes")
        var fmt: String
        if (pfFlags and 0x4L != 0L) {
            fmt = four
            if (four == "DX10") {
                if (h.size >= 148) {
                    val dx = Bytes.u32le(h, 128); val arr = Bytes.u32le(h, 140)
                    fmt = "DX10: ${dxgi(dx)}"
                    f.add("Image", "Array size", "$arr")
                } else warnings.add("DX10 extended header is truncated")
            }
            f.add("Pixel format", "FourCC", four)
        } else {
            fmt = "Uncompressed, $bits bits per pixel" + (if (pfFlags and 0x1L != 0L) ", alpha" else "")
        }
        f.add("Pixel format", "Format", fmt)
        return InspectionResult("dds", Img.title(fileName, "DDS texture"), "DDS ${Img.dims(w, ht)}, $fmt, $mipCount mip level" + (if (mipCount == 1L) "" else "s"), f.list, warnings)
    }
}

/** Truevision TGA (no magic number: validated by header plausibility). */
internal object TgaInspector : SafeInspector() {
    override val formatIds = setOf("tga")

    private fun typeName(t: Int) = when (t) {
        1 -> "Colour-mapped"; 2 -> "True-colour"; 3 -> "Greyscale"
        9 -> "Colour-mapped (RLE)"; 10 -> "True-colour (RLE)"; 11 -> "Greyscale (RLE)"; else -> "Unknown"
    }

    override fun doInspect(source: ByteSource, fileName: String?): InspectionResult? {
        val h = source.read(0, 18)
        if (h.size < 18) return null
        val idLen = Bytes.u8(h, 0); val cmType = Bytes.u8(h, 1); val type = Bytes.u8(h, 2)
        val w = Bytes.u16le(h, 12); val ht = Bytes.u16le(h, 14); val bpp = Bytes.u8(h, 16); val desc = Bytes.u8(h, 17)
        if (cmType > 1 || type !in setOf(1, 2, 3, 9, 10, 11) || w == 0 || ht == 0) return null
        if (bpp !in setOf(8, 15, 16, 24, 32)) return null
        if ((type == 1 || type == 9) && cmType != 1) return null
        val warnings = ArrayList<String>()
        val rle = type >= 9
        val f = FactList()
        f.add("Image", "Dimensions", Img.dims(w.toLong(), ht.toLong()))
        f.add("Image", "Bits per pixel", "$bpp")
        f.add("Image", "Image type", typeName(type))
        f.add("Image", "RLE compressed", if (rle) "yes" else "no")
        f.add("Image", "Alpha bits", "${desc and 0x0F}")
        f.add("Image", "Origin", (if (desc and 0x20 != 0) "top" else "bottom") + "-" + (if (desc and 0x10 != 0) "right" else "left"))
        if (cmType == 1) f.add("Image", "Colour map entries", "${Bytes.u16le(h, 5)} (${Bytes.u8(h, 7)} bits each)")
        if (idLen > 0) {
            val idb = source.read(18, idLen)
            val id = Img.clean(String(CharArray(idLen) { i -> val c = Bytes.u8(idb, i); if (c in 0x20..0x7E) c.toChar() else ' ' })).trim()
            if (id.isNotEmpty()) f.add("Metadata", "Image ID", id)
        }
        val size = source.size
        var v2 = false
        if (size >= 26) {
            val foot = source.read(size - 26, 26)
            if (foot.size == 26 && Bytes.startsWith(foot, "TRUEVISION-XFILE.", 8) && Bytes.u8(foot, 25) == 0) {
                v2 = true
                val ext = Bytes.u32le(foot, 0)
                if (ext > 0 && ext + 495 <= size - 26) {
                    val e = source.read(ext, 495)
                    if (e.size == 495 && Bytes.u16le(e, 0) == 495) {
                        fun field(o: Int, n: Int): String? = Img.latin1(e, o, n).substringBefore('\u0000').trim().ifEmpty { null }
                        f.add("Metadata", "Author", field(2, 41))
                        f.add("Metadata", "Software", field(426, 41))
                        f.add("Metadata", "Job name", field(379, 41))
                    }
                }
            }
        }
        f.add("Header", "TGA version", if (v2) "2.0 (has footer)" else "1.0 (no footer)")
        return InspectionResult("tga", Img.title(fileName, "TGA image"), "TGA ${Img.dims(w.toLong(), ht.toLong())}, $bpp-bit ${typeName(type)}", f.list, warnings)
    }
}

/** SVG: light text scan of the root element, title and element counts. */
internal object SvgInspector : SafeInspector() {
    override val formatIds = setOf("svg")
    /** Index of a root `<svg` element after any BOM-less prolog (declaration, comments, DOCTYPE, PIs); -1 if the root is something else. */
    private fun rootStart(s: String): Int {
        var i = 0; var guard = 0
        while (guard++ < 1000) {
            while (i < s.length && s[i].isWhitespace()) i++
            if (s.startsWith("<?", i)) { val e = s.indexOf("?>", i + 2); if (e < 0) return -1; i = e + 2 }
            else if (s.startsWith("<!--", i)) { val e = s.indexOf("-->", i + 4); if (e < 0) return -1; i = e + 3 }
            else if (s.startsWith("<!", i)) {
                var j = i + 2; var depth = 0
                while (j < s.length && !(s[j] == '>' && depth == 0)) { if (s[j] == '[') depth++ else if (s[j] == ']' && depth > 0) depth--; j++ }
                if (j >= s.length) return -1
                i = j + 1
            } else break
        }
        if (!s.startsWith("<svg", i)) return -1
        val after = s.getOrNull(i + 4)
        if (after != null && !(after.isWhitespace() || after == '>' || after == '/')) return -1
        return i
    }

    /** Linear attribute scanner (no regex backtracking). */
    private fun parseAttrs(tag: String, out: MutableMap<String, String>) {
        fun startCh(c: Char) = c.isLetter() || c == '_' || c == ':'
        fun nameCh(c: Char) = c.isLetterOrDigit() || c == '_' || c == ':' || c == '-' || c == '.'
        var i = 4; val n = tag.length
        while (i < n && out.size < 64) {
            if (!startCh(tag[i])) { i++; continue }
            val a = i
            while (i < n && nameCh(tag[i])) i++
            val name = tag.substring(a, i)
            while (i < n && tag[i].isWhitespace()) i++
            if (i >= n || tag[i] != '=') continue
            i++
            while (i < n && tag[i].isWhitespace()) i++
            if (i >= n || (tag[i] != '"' && tag[i] != '\'')) continue
            val q = tag[i]
            val e = tag.indexOf(q, i + 1)
            if (e < 0) break
            out.putIfAbsent(name, tag.substring(i + 1, e))
            i = e + 1
        }
    }
    private val COUNTED = listOf("path", "rect", "circle", "ellipse", "line", "polyline", "polygon", "text", "image", "g", "use")

    override fun doInspect(source: ByteSource, fileName: String?): InspectionResult? {
        val raw = source.read(0, 256 * 1024)
        if (raw.isEmpty()) return null
        var off = 0
        if (raw.size >= 3 && Bytes.u8(raw, 0) == 0xEF && Bytes.u8(raw, 1) == 0xBB && Bytes.u8(raw, 2) == 0xBF) off = 3
        val s = String(raw, off, raw.size - off, Charsets.UTF_8)
        val start = rootStart(s)
        if (start < 0) return null
        val after = s.getOrNull(start + 4)
        if (after != null && !(after.isWhitespace() || after == '>' || after == '/')) return null
        // end of the start tag, respecting quotes
        var i = start + 4; var q = 0.toChar(); val limit = minOf(s.length, start + 16384)
        while (i < limit) {
            val c = s[i]
            if (q != 0.toChar()) { if (c == q) q = 0.toChar() } else if (c == '"' || c == '\'') q = c else if (c == '>') break
            i++
        }
        val tag = s.substring(start, minOf(i, s.length))
        val attrs = LinkedHashMap<String, String>()
        parseAttrs(tag, attrs)
        val warnings = ArrayList<String>()
        if (raw.size >= 256 * 1024 && source.size > raw.size) warnings.add("Only the first 256 KiB was scanned; element counts are lower bounds")
        val f = FactList()
        attrs["width"]?.let { f.add("Image", "Width", Img.clip(it, 64)) }
        attrs["height"]?.let { f.add("Image", "Height", Img.clip(it, 64)) }
        val vb = attrs["viewBox"]
        if (vb != null) {
            f.add("Image", "viewBox", Img.clip(vb, 64))
            val n = vb.trim().split(Regex("[\\s,]+")).mapNotNull { it.toDoubleOrNull() }
            if (n.size == 4) f.add("Image", "viewBox size", "${fmt(n[2])}$TIMES${fmt(n[3])}")
        }
        attrs["version"]?.let { f.add("Image", "SVG version", Img.clip(it, 16)) }
        if (attrs.containsKey("xmlns")) f.add("Image", "Namespace", Img.clip(attrs["xmlns"]!!, 80))
        if (attrs["width"] == null && vb == null) warnings.add("No width, height or viewBox: intrinsic size is undefined")
        val body = s.substring(minOf(i, s.length))
        val title = textOf(body, "title")
        val desc = textOf(body, "desc")
        f.add("Metadata", "Title", title?.let { Img.clip(it, 200) })
        f.add("Metadata", "Description", desc?.let { Img.clip(it, 200) })
        val counts = ArrayList<String>()
        for (name in COUNTED) {
            var c = 0; var p = 0
            val needle = "<$name"
            while (c < 100000) {
                p = body.indexOf(needle, p)
                if (p < 0) break
                val ch = body.getOrNull(p + needle.length)
                if (ch != null && (ch.isWhitespace() || ch == '>' || ch == '/')) c++
                p += needle.length
            }
            if (c > 0) counts.add("$name×$c")
        }
        if (counts.isNotEmpty()) f.add("Content", "Elements", counts.joinToString(", "))
        if (body.contains("<script", ignoreCase = true)) f.add("Content", "Scripts", "contains <script> element")
        val size = if (attrs["width"] != null && attrs["height"] != null) "${attrs["width"]}$TIMES${attrs["height"]}" else vb?.let { "viewBox $it" }
        val summary = "SVG" + (size?.let { " $it" } ?: "") + (title?.let { " – " + Img.clip(it, 60) } ?: "")
        return InspectionResult("svg", Img.title(fileName, "SVG image"), summary, f.list, warnings, title)
    }

    private fun fmt(d: Double) = if (d == Math.floor(d) && Math.abs(d) < 1e12) d.toLong().toString() else d.toString()

    private fun textOf(body: String, tag: String): String? {
        val a = body.indexOf("<$tag")
        if (a < 0) return null
        val gt = body.indexOf('>', a)
        if (gt < 0 || body[gt - 1] == '/') return null
        val e = body.indexOf("</$tag>", gt)
        if (e < 0) return null
        val t = body.substring(gt + 1, minOf(e, gt + 1 + 2000)).trim()
        return t.replace(Regex("\\s+"), " ").ifEmpty { null }
    }
}

object ImageInspectors {
    val all: List<FormatInspector> = listOf(
        PngInspector, JpegInspector, GifInspector, WebpInspector, BmpInspector, TiffInspector,
        IsobmffImageInspector, IcoInspector, PsdInspector, DdsInspector, TgaInspector, SvgInspector,
    )
}
