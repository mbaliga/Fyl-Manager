package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** PNG / APNG: walks the chunk list without reading pixel data. */
internal object PngInspector : SafeInspector() {
    override val formatIds = setOf("png")
    private val SIG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private const val MAX_CHUNKS = 10000
    private const val MAX_TEXTS = 64

    private fun colourName(t: Int) = when (t) {
        0 -> "Greyscale"; 2 -> "Truecolour (RGB)"; 3 -> "Indexed (palette)"
        4 -> "Greyscale + alpha"; 6 -> "Truecolour + alpha (RGBA)"; else -> "Unknown ($t)"
    }

    private fun channels(t: Int) = when (t) { 0 -> 1; 2 -> 3; 3 -> 1; 4 -> 2; 6 -> 4; else -> 0 }

    private fun validDepth(t: Int, d: Int) = when (t) {
        0 -> d in setOf(1, 2, 4, 8, 16)
        3 -> d in setOf(1, 2, 4, 8)
        2, 4, 6 -> d == 8 || d == 16
        else -> false
    }

    override fun doInspect(source: ByteSource, fileName: String?): InspectionResult? {
        val head = source.read(0, 8)
        if (head.size < 8 || !head.contentEquals(SIG)) return null
        val warnings = ArrayList<String>()
        val image = FactList(); val anim = FactList(); val colour = FactList(); val phys = FactList(); val texts = FactList()
        val textBlob = StringBuilder()
        var width = 0L; var height = 0L; var depth = 0; var ctype = -1; var interlace = -1
        var sawIhdr = false; var sawIend = false; var chunkCount = 0
        var frames = -1L; var plays = -1L; var paletteEntries = -1L; var textCount = 0; var textTries = 0; var iccDone = false
        var hasAlphaTrns = false; var firstChunk = true
        var pos = 8L
        val size = source.size
        var truncated = false
        while (chunkCount < MAX_CHUNKS) {
            if (pos + 12 > size) { if (pos < size) truncated = true; break }
            val h = source.read(pos, 24)
            if (h.size < 8) break
            fun dat(n: Int): ByteArray = h.copyOfRange(8, minOf(h.size, 8 + n))
            val len = Bytes.u32be(h, 0)
            val type = Bytes.ascii(h, 4, 4)
            if (len > 0x7FFFFFFFL) { warnings.add("Chunk $type has an invalid length"); break }
            chunkCount++
            val dpos = pos + 8
            if (dpos + len + 4 > size) truncated = true
            when (type) {
                "IHDR" -> {
                    if (!firstChunk) warnings.add("IHDR is not the first chunk")
                    if (len == 13L) {
                        val d = dat(13)
                        width = Bytes.u32be(d, 0); height = Bytes.u32be(d, 4)
                        depth = Bytes.u8(d, 8); ctype = Bytes.u8(d, 9); interlace = Bytes.u8(d, 12)
                        sawIhdr = true
                    } else warnings.add("IHDR has length $len instead of 13")
                }
                "PLTE" -> paletteEntries = len / 3
                "tRNS" -> hasAlphaTrns = true
                "gAMA" -> if (len == 4L) {
                    val g = Bytes.u32be(dat(4), 0)
                    if (g > 0) colour.add("Colour", "Gamma", "%.5f".format(java.util.Locale.ROOT, g / 100000.0))
                }
                "sRGB" -> if (len == 1L) {
                    val r = Bytes.u8(dat(1), 0)
                    colour.add("Colour", "sRGB rendering intent", when (r) {
                        0 -> "Perceptual"; 1 -> "Relative colorimetric"; 2 -> "Saturation"; 3 -> "Absolute colorimetric"; else -> "Unknown ($r)"
                    })
                }
                "iCCP" -> if (!iccDone) {
                    iccDone = true
                    val d = source.read(dpos, minOf(len, 80L).toInt())
                    val z = d.indexOfFirst { it.toInt() == 0 }
                    colour.add("Colour", "ICC profile", if (z in 1..79) Img.latin1(d, 0, z) else "present")
                }
                "cHRM" -> colour.add("Colour", "Chromaticities", "present")
                "pHYs" -> if (len == 9L) {
                    val d = dat(9)
                    val x = Bytes.u32be(d, 0); val y = Bytes.u32be(d, 4); val unit = Bytes.u8(d, 8)
                    if (unit == 1) {
                        phys.add("Physical", "Pixel density", "${Math.round(x * 0.0254)} × ${Math.round(y * 0.0254)} dpi")
                    } else phys.add("Physical", "Pixel aspect ratio", "$x:$y")
                }
                "tIME" -> if (len == 7L) {
                    val d = dat(7)
                    phys.add("Physical", "Last modified", "%04d-%02d-%02d %02d:%02d:%02d UTC".format(
                        Bytes.u16be(d, 0), Bytes.u8(d, 2), Bytes.u8(d, 3), Bytes.u8(d, 4), Bytes.u8(d, 5), Bytes.u8(d, 6)))
                }
                "acTL" -> if (len == 8L) {
                    val d = dat(8)
                    frames = Bytes.u32be(d, 0); plays = Bytes.u32be(d, 4)
                }
                "tEXt", "zTXt", "iTXt" -> if (textTries < MAX_TEXTS) {
                    textTries++
                    val d = source.read(dpos, minOf(len, 65536L).toInt())
                    val kv = parseText(type, d)
                    if (kv != null) {
                        textCount++
                        texts.add("Text", kv.first, Img.clip(kv.second.replace('\n', ' '), 200))
                        if (textBlob.length < FormatInspector.MAX_TEXT_CHARS) textBlob.append(kv.first).append(": ").append(kv.second).append('\n')
                    }
                }
                "IEND" -> sawIend = true
            }
            firstChunk = false
            if (sawIend) break
            pos += 12 + len
        }
        if (chunkCount >= MAX_CHUNKS) warnings.add("Chunk scan stopped after $MAX_CHUNKS chunks")
        if (!sawIhdr) warnings.add("No valid IHDR chunk found")
        if (truncated) warnings.add("File is truncated")
        else if (!sawIend && chunkCount < MAX_CHUNKS) warnings.add("No IEND chunk")

        var summary: String? = null
        if (sawIhdr) {
            if (width == 0L || height == 0L) warnings.add("Image has a zero dimension")
            if (!validDepth(ctype, depth)) warnings.add("Bit depth $depth is not valid for colour type $ctype")
            if (interlace !in 0..1) warnings.add("Unknown interlace method $interlace")
            image.add("Image", "Dimensions", Img.dims(width, height))
            image.add("Image", "Bit depth", "$depth")
            image.add("Image", "Colour type", colourName(ctype))
            if (channels(ctype) > 0) image.add("Image", "Bits per pixel", "${channels(ctype) * depth}")
            image.add("Image", "Interlace", when (interlace) { 0 -> "None"; 1 -> "Adam7"; else -> "Unknown ($interlace)" })
            if (paletteEntries >= 0) image.add("Image", "Palette entries", "$paletteEntries")
            if (hasAlphaTrns) image.add("Image", "Transparency chunk (tRNS)", "present")
            summary = "PNG ${Img.dims(width, height)}, $depth-bit ${colourName(ctype)}"
        }
        if (frames >= 0) {
            anim.add("Animation", "Animated", "yes (APNG)")
            anim.add("Animation", "Frames", "$frames")
            anim.add("Animation", "Plays", if (plays == 0L) "infinite" else "$plays")
            if (summary != null) summary += ", $frames frames"
        }
        val facts = image.list + anim.list + colour.list + phys.list + texts.list
        return InspectionResult(
            "png", Img.title(fileName, "PNG image"), summary, facts, warnings,
            textBlob.toString().take(FormatInspector.MAX_TEXT_CHARS).ifEmpty { null },
        )
    }

    private fun parseText(type: String, d: ByteArray): Pair<String, String>? {
        val z = d.indexOfFirst { it.toInt() == 0 }
        if (z < 1 || z > 79) return null
        val key = Img.latin1(d, 0, z)
        return when (type) {
            "tEXt" -> Pair(key, Img.clean(Img.latin1(d, z + 1, d.size)))
            "zTXt" -> {
                if (z + 2 > d.size || Bytes.u8(d, z + 1) != 0) return null
                val out = Img.inflateBounded(d.copyOfRange(z + 2, d.size), 65536) ?: return null
                Pair(key, Img.clean(String(out, Charsets.ISO_8859_1)))
            }
            else -> { // iTXt: keyword\0 flag method lang\0 translated\0 text
                if (z + 3 > d.size) return null
                val compressed = Bytes.u8(d, z + 1) == 1
                var p = z + 3
                val l = d.indexOfFirstFrom(p) { it.toInt() == 0 }; if (l < 0) return null
                p = l + 1
                val t = d.indexOfFirstFrom(p) { it.toInt() == 0 }; if (t < 0) return null
                p = t + 1
                val raw = d.copyOfRange(minOf(p, d.size), d.size)
                val bytes = if (compressed) Img.inflateBounded(raw, 65536) ?: return null else raw
                Pair(key, Img.clean(String(bytes, Charsets.UTF_8)))
            }
        }
    }

    private inline fun ByteArray.indexOfFirstFrom(start: Int, pred: (Byte) -> Boolean): Int {
        for (i in start until size) if (pred(this[i])) return i
        return -1
    }
}
