package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** JPEG: walks marker segments up to the first scan; reads SOF, JFIF, EXIF, ICC, Adobe, comments. */
internal object JpegInspector : SafeInspector() {
    override val formatIds = setOf("jpg")

    private fun sofName(m: Int) = when (m) {
        0xC0 -> "Baseline DCT"
        0xC1 -> "Extended sequential DCT"
        0xC2 -> "Progressive DCT"
        0xC3 -> "Lossless (sequential)"
        0xC5 -> "Differential sequential DCT"
        0xC6 -> "Differential progressive DCT"
        0xC7 -> "Differential lossless"
        0xC9 -> "Extended sequential DCT (arithmetic)"
        0xCA -> "Progressive DCT (arithmetic)"
        0xCB -> "Lossless (arithmetic)"
        0xCD -> "Differential sequential DCT (arithmetic)"
        0xCE -> "Differential progressive DCT (arithmetic)"
        0xCF -> "Differential lossless (arithmetic)"
        else -> "Unknown"
    }

    private fun isSof(m: Int) = m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC

    override fun doInspect(source: ByteSource, fileName: String?): InspectionResult? {
        val head = source.read(0, 3)
        if (head.size < 3 || Bytes.u8(head, 0) != 0xFF || Bytes.u8(head, 1) != 0xD8 || Bytes.u8(head, 2) != 0xFF) return null
        val size = source.size
        val warnings = ArrayList<String>()
        val image = FactList(); val jfif = FactList(); val meta = FactList(); val colour = FactList()
        var exif: ExifSummary? = null
        var width = 0; var height = 0; var precision = 0; var ncomp = 0; var sofMarker = -1
        var sampling: String? = null
        var comment: String? = null
        var sawScan = false; var sawEoi = false; var segs = 0
        var pos = 2L
        var xmp = false; var adobe = false; var iccDone = false; var exifTried = false; var commentTried = false
        loop@ while (segs++ < 2000) {
            if (pos + 2 > size) break
            val h = source.read(pos, 72)
            if (h.size < 2) break
            if (Bytes.u8(h, 0) != 0xFF) { warnings.add("Invalid marker at offset $pos"); break }
            val m = Bytes.u8(h, 1)
            when {
                m == 0xFF -> { pos++; continue@loop }
                m == 0xD9 -> { sawEoi = true; break@loop }
                m == 0x00 -> { warnings.add("Invalid marker at offset $pos"); break@loop }
                m == 0x01 || m in 0xD0..0xD8 -> { pos += 2; continue@loop }
            }
            if (h.size < 4) break
            val len = Bytes.u16be(h, 2)
            if (len < 2) { warnings.add("Invalid segment length at offset $pos"); break }
            if (m == 0xDA) { sawScan = true; break }
            val dlen = len - 2
            val dpos = pos + 4
            if (dpos + dlen > size) { warnings.add("File is truncated inside a segment"); break }
            val pre = h.copyOfRange(minOf(4, h.size), h.size)
            fun full(): ByteArray = if (pre.size >= dlen) pre.copyOf(dlen) else source.read(dpos, dlen)
            when {
                isSof(m) && sofMarker < 0 -> {
                    val d = full()
                    sofMarker = m
                    precision = Bytes.u8(d, 0); height = Bytes.u16be(d, 1); width = Bytes.u16be(d, 3); ncomp = Bytes.u8(d, 5)
                    if (ncomp == 3 && d.size >= 6 + 9) {
                        val s = IntArray(3) { Bytes.u8(d, 6 + it * 3 + 1) }
                        sampling = when {
                            s[1] == 0x11 && s[2] == 0x11 -> when (s[0]) {
                                0x11 -> "4:4:4"; 0x21 -> "4:2:2"; 0x22 -> "4:2:0"; 0x12 -> "4:4:0"; 0x41 -> "4:1:1"; else -> null
                            }
                            else -> null
                        }
                    }
                }
                m == 0xE0 && Bytes.startsWith(pre, "JFIF\u0000") && dlen >= 14 && pre.size >= 14 -> {
                    val d = pre
                    jfif.add("JFIF", "JFIF version", "${Bytes.u8(d, 5)}.${"%02d".format(Bytes.u8(d, 6))}")
                    val units = Bytes.u8(d, 7); val x = Bytes.u16be(d, 8); val y = Bytes.u16be(d, 10)
                    when (units) {
                        1 -> jfif.add("JFIF", "Density", "$x × $y dpi")
                        2 -> jfif.add("JFIF", "Density", "$x × $y dots/cm")
                        else -> jfif.add("JFIF", "Pixel aspect ratio", "$x:$y")
                    }
                }
                m == 0xE1 && Bytes.startsWith(pre, "Exif\u0000\u0000") && exif == null && !exifTried -> {
                    exifTried = true
                    exif = Exif.read(ByteArraySource(full()), 6)
                }
                m == 0xE1 && Bytes.startsWith(pre, "http://ns.adobe.com/xap/1.0/\u0000") -> xmp = true
                m == 0xE2 && Bytes.startsWith(pre, "ICC_PROFILE\u0000") && !iccDone -> {
                    iccDone = true
                    val d = pre.copyOf(minOf(pre.size, dlen))
                    var cs: String? = null
                    if (d.size >= 14 + 40 && Bytes.startsWith(d, "acsp", 14 + 36)) cs = Bytes.ascii(d, 14 + 16, 4).trim()
                    colour.add("Colour", "ICC profile", if (cs != null) "present ($cs colour space)" else "present")
                }
                m == 0xEE && Bytes.startsWith(pre, "Adobe") && dlen >= 12 && pre.size >= 12 && !adobe -> {
                    adobe = true
                    val d = pre
                    colour.add("Colour", "Adobe transform", when (val t = Bytes.u8(d, 11)) {
                        0 -> "None (RGB or CMYK)"; 1 -> "YCbCr"; 2 -> "YCCK"; else -> "Unknown ($t)"
                    })
                }
                m == 0xFE && comment == null && !commentTried -> {
                    commentTried = true
                    val c = Img.clean(String(full(), Charsets.ISO_8859_1)).trim()
                    if (c.isNotEmpty()) comment = c
                }
            }
            pos = dpos + dlen
        }
        if (sofMarker < 0) warnings.add("No start-of-frame segment found")
        if (sofMarker >= 0 && (width == 0 || height == 0)) warnings.add("Frame header reports a zero dimension (height may be defined by a DNL marker)")
        if (sofMarker >= 0 && !sawScan && !sawEoi) warnings.add("File ends before the first scan")

        var summary: String? = null
        if (sofMarker >= 0) {
            val progressive = sofMarker in setOf(0xC2, 0xC6, 0xCA, 0xCE)
            image.add("Image", "Dimensions", Img.dims(width.toLong(), height.toLong()))
            image.add("Image", "Encoding", sofName(sofMarker))
            image.add("Image", "Progressive", if (progressive) "yes" else "no")
            image.add("Image", "Sample precision", "$precision bits")
            image.add("Image", "Components", "$ncomp" + when (ncomp) { 1 -> " (greyscale)"; 3 -> " (colour)"; 4 -> " (CMYK/YCCK)"; else -> "" })
            image.add("Image", "Chroma subsampling", sampling)
            summary = "JPEG ${Img.dims(width.toLong(), height.toLong())}" + if (progressive) ", progressive" else ""
        }
        exif?.let { e ->
            meta.list.addAll(e.facts)
            if (e.make != null || e.model != null) summary = (summary ?: "JPEG") + ", " + listOfNotNull(e.make, e.model).joinToString(" ")
        }
        if (xmp) meta.add("Metadata", "XMP packet", "present")
        comment?.let { meta.add("Metadata", "Comment", Img.clip(it.replace('\n', ' '), 200)) }
        val facts = image.list + jfif.list + colour.list + meta.list
        return InspectionResult("jpg", Img.title(fileName, "JPEG image"), summary, facts, warnings, comment?.take(FormatInspector.MAX_TEXT_CHARS))
    }
}
