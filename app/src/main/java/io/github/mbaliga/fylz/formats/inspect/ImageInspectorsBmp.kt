package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** Windows / OS/2 bitmap. */
internal object BmpInspector : SafeInspector() {
    override val formatIds = setOf("bmp")

    private fun headerName(n: Long) = when (n) {
        12L -> "BITMAPCOREHEADER (OS/2 1.x)"
        40L -> "BITMAPINFOHEADER"
        52L -> "BITMAPV2INFOHEADER"
        56L -> "BITMAPV3INFOHEADER"
        64L -> "OS22XBITMAPHEADER"
        108L -> "BITMAPV4HEADER"
        124L -> "BITMAPV5HEADER"
        else -> "Unknown ($n bytes)"
    }

    private fun compName(c: Long) = when (c) {
        0L -> "BI_RGB (none)"; 1L -> "BI_RLE8"; 2L -> "BI_RLE4"; 3L -> "BI_BITFIELDS"
        4L -> "BI_JPEG"; 5L -> "BI_PNG"; 6L -> "BI_ALPHABITFIELDS"
        11L -> "BI_CMYK"; 12L -> "BI_CMYKRLE8"; 13L -> "BI_CMYKRLE4"
        else -> "Unknown ($c)"
    }

    override fun doInspect(source: ByteSource, fileName: String?): InspectionResult? {
        val h = source.read(0, 54)
        if (h.size < 18 || !Bytes.startsWith(h, "BM")) return null
        val warnings = ArrayList<String>()
        val declared = Bytes.u32le(h, 2)
        val dataOffset = Bytes.u32le(h, 10)
        val hdr = Bytes.u32le(h, 14)
        if (hdr !in setOf(12L, 40L, 52L, 56L, 64L, 108L, 124L)) return null
        val f = FactList()
        var w = 0L; var ht = 0L; var bpp = 0; var comp = 0L; var topDown = false
        var colours = 0L; var xppm = 0L; var yppm = 0L; var planes = 1
        if (hdr == 12L) {
            if (h.size < 26) return null
            w = Bytes.u16le(h, 18).toLong(); ht = Bytes.u16le(h, 20).toLong(); planes = Bytes.u16le(h, 22); bpp = Bytes.u16le(h, 24)
        } else if (hdr >= 40L) {
            if (h.size < 54) return null
            w = Bytes.u32le(h, 18).toInt().toLong(); val rawH = Bytes.u32le(h, 22).toInt().toLong()
            topDown = rawH < 0; ht = Math.abs(rawH)
            planes = Bytes.u16le(h, 26); bpp = Bytes.u16le(h, 28); comp = Bytes.u32le(h, 30)
            xppm = Bytes.u32le(h, 38).toInt().toLong(); yppm = Bytes.u32le(h, 42).toInt().toLong(); colours = Bytes.u32le(h, 46)
        } else return null
        if (w < 0) { w = Math.abs(w); warnings.add("Negative width") }
        if (w == 0L || ht == 0L) warnings.add("Zero dimension")
        if (bpp !in setOf(1, 2, 4, 8, 16, 24, 32, 64)) warnings.add("Unusual bits per pixel: $bpp")
        if (declared > source.size) warnings.add("File is shorter than its header declares")
        if (dataOffset > source.size) warnings.add("Pixel data offset is beyond the end of the file")
        f.add("Image", "Dimensions", Img.dims(w, ht))
        f.add("Image", "Bits per pixel", "$bpp")
        f.add("Image", "Compression", compName(comp))
        f.add("Image", "Row order", if (topDown) "top-down" else "bottom-up")
        f.add("Image", "Colour planes", "$planes")
        if (hdr != 12L && colours > 0 && bpp <= 8) f.add("Image", "Palette colours", "$colours")
        else if (hdr != 12L && bpp in 1..8) f.add("Image", "Palette colours", "${1L shl bpp} (default)")
        if (xppm > 0 && yppm > 0) f.add("Image", "Resolution", "${Math.round(xppm * 0.0254)} × ${Math.round(yppm * 0.0254)} dpi")
        f.add("Header", "Header", headerName(hdr))
        f.add("Header", "Pixel data offset", "$dataOffset")
        f.add("Header", "Declared file size", "$declared bytes")
        return InspectionResult("bmp", Img.title(fileName, "BMP image"), "BMP ${Img.dims(w, ht)}, $bpp-bit, ${compName(comp)}", f.list, warnings)
    }
}
