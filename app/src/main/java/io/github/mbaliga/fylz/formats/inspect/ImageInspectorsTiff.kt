package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** TIFF and TIFF-based raw formats (DNG, CR2, NEF, ARW): IFD0 plus SubIFDs for the largest image. */
internal object TiffInspector : SafeInspector() {
    override val formatIds = setOf("tif", "dng", "cr2", "nef", "arw")

    private fun compName(c: Long) = when (c) {
        1L -> "None"; 2L -> "CCITT modified Huffman RLE"; 3L -> "CCITT Group 3"; 4L -> "CCITT Group 4"
        5L -> "LZW"; 6L -> "JPEG (old-style)"; 7L -> "JPEG"; 8L -> "Deflate (Adobe)"
        32773L -> "PackBits"; 32946L -> "Deflate"; 34892L -> "Lossy JPEG (DNG)"
        else -> "Unknown ($c)"
    }

    private fun photoName(p: Long) = when (p) {
        0L -> "WhiteIsZero"; 1L -> "BlackIsZero"; 2L -> "RGB"; 3L -> "Palette"; 4L -> "Transparency mask"
        5L -> "CMYK"; 6L -> "YCbCr"; 8L -> "CIELab"; 32803L -> "Colour filter array (raw)"; 34892L -> "LinearRaw"
        else -> "Unknown ($p)"
    }

    private class Img1(val w: Long, val h: Long, val comp: Long?, val photo: Long?, val bits: List<Long>, val spp: Long?, val level: String)

    override fun doInspect(source: ByteSource, fileName: String?): InspectionResult? {
        val t = TiffReader(source, 0)
        if (!t.valid) return null
        val warnings = ArrayList<String>()
        if (t.bigTiff) {
            val f = FactList()
            f.add("Image", "Variant", "BigTIFF (64-bit offsets)")
            warnings.add("BigTIFF structure is not parsed; only the variant is reported")
            return InspectionResult(idFor(fileName, false, false), Img.title(fileName, "TIFF image"), "BigTIFF image", f.list, warnings)
        }
        val ifd0 = t.readIfd(t.ifd0)
        if (ifd0 == null) {
            warnings.add("IFD0 could not be read")
            return InspectionResult(idFor(fileName, false, false), Img.title(fileName, "TIFF image"), null, emptyList(), warnings)
        }
        val e0 = ifd0.first
        val magic = source.read(8, 4)
        val cr2 = Bytes.startsWith(magic, "CR") && Bytes.u8(magic, 2) == 2
        val dng = e0.containsKey(0xC612)
        val images = ArrayList<Img1>()
        fun describe(e: Map<Int, TiffReader.Entry>, level: String): Img1? {
            val w = e[0x0100]?.let { t.long(it) } ?: return null
            val h = e[0x0101]?.let { t.long(it) } ?: return null
            return Img1(w, h, e[0x0103]?.let { t.long(it) }, e[0x0106]?.let { t.long(it) }, e[0x0102]?.let { t.longs(it, 8) } ?: emptyList(), e[0x0115]?.let { t.long(it) }, level)
        }
        describe(e0, "IFD0")?.let { images.add(it) }
        // SubIFDs (tag 0x014A): raw files keep full-size data here
        e0[0x014A]?.let { sub ->
            for ((i, off) in t.longs(sub, 8).withIndex()) {
                val s = t.readIfd(off) ?: continue
                describe(s.first, "SubIFD ${i + 1}")?.let { images.add(it) }
            }
        }
        // count top-level IFDs (pages)
        var pages = 1; var next = ifd0.second; var guard = 0
        val seen = HashSet<Long>(); seen.add(t.ifd0)
        var cr2Raw: Img1? = null
        while (next > 0 && guard++ < 256 && seen.add(next)) {
            val n = t.readIfd(next) ?: break
            pages++
            if (cr2 && pages <= 4) describe(n.first, "IFD${pages - 1}")?.let { images.add(it); if (pages == 4) cr2Raw = it }
            next = n.second
        }
        if (pages >= 256) warnings.add("Page count capped at 256")
        val main = images.maxByOrNull { it.w * it.h }
                val make = e0[0x010F]?.let { t.str(it) }
        val id = idFor(fileName, dng, cr2, make)
        val nefOk = id == "nef"; val arwOk = id == "arw"
        val f = FactList()
        val model = e0[0x0110]?.let { t.str(it) }
        if (main != null) {
            f.add("Image", "Dimensions", Img.dims(main.w, main.h))
            if (images.size > 1) f.add("Image", "Dimensions taken from", main.level + " (largest of ${images.size} images found)")
            main.comp?.let { f.add("Image", "Compression", compName(it)) }
            main.photo?.let { f.add("Image", "Photometric interpretation", photoName(it)) }
            if (main.bits.isNotEmpty()) f.add("Image", "Bits per sample", main.bits.joinToString(", "))
            main.spp?.let { f.add("Image", "Samples per pixel", it.toString()) }
        } else warnings.add("IFD0 has no ImageWidth/ImageLength")
        f.add("Image", "Byte order", if (t.le) "little-endian (II)" else "big-endian (MM)")
        f.add("Image", "Pages / IFDs", "$pages")
        if (id != "tif") f.add("Image", "Raw format", id.uppercase())
        if (dng) e0[0xC612]?.let { e -> f.add("Image", "DNG version", t.valueBytes(e, 4).joinToString(".") { (it.toInt() and 0xFF).toString() }) }
        f.add("Camera", "Make", make)
        f.add("Camera", "Model", model)
        f.add("Camera", "Software", e0[0x0131]?.let { t.str(it) })
        f.add("Camera", "Date/time", e0[0x0132]?.let { t.str(it) })
        e0[0x0112]?.let { e -> t.long(e)?.toInt()?.let { o -> f.add("Camera", "Orientation", Exif.orientationName(o) ?: "Unknown ($o)") } }
        var hasGps = e0.containsKey(0x8825)
        e0[0x8769]?.let { p ->
            t.long(p)?.let { off -> t.readIfd(off)?.first }?.let { x ->
                f.add("Camera", "Date taken", x[0x9003]?.let { t.str(it) })
                x[0x8827]?.let { e -> t.long(e)?.let { f.add("Camera", "ISO", it.toString()) } }
            }
        }
        if (hasGps) f.add("Camera", "GPS data", "present")
        val label = when (id) { "dng" -> "DNG"; "cr2" -> "Canon CR2"; "nef" -> "Nikon NEF"; "arw" -> "Sony ARW"; else -> "TIFF" }
        val summary = (main?.let { "$label ${Img.dims(it.w, it.h)}" } ?: label) + (if (pages > 1) ", $pages pages" else "") +
            (listOfNotNull(make, model).takeIf { it.isNotEmpty() }?.let { ", " + it.joinToString(" ") } ?: "")
        if (cr2Raw != null && cr2) { /* dims already considered via maxBy */ }
        if ((nefOk || arwOk) && !dng && images.size == 1) warnings.add("Raw sensor dimensions were not found (no SubIFDs); size is from IFD0 only")
        return InspectionResult(id, Img.title(fileName, "$label image"), summary, f.list, warnings)
    }

    private fun idFor(fileName: String?, dng: Boolean, cr2: Boolean, make: String? = null): String {
        if (dng) return "dng"
        if (cr2) return "cr2"
        val e = Img.ext(fileName)
        if (e == "nef" && make?.uppercase()?.startsWith("NIKON") == true) return e
        if (e == "arw" && make?.uppercase()?.startsWith("SONY") == true) return e
        return "tif"
    }
}
