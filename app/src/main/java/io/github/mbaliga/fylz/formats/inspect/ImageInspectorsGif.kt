package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** GIF87a / GIF89a: logical screen, palette, frame count, loop count, durations, comments. */
internal object GifInspector : SafeInspector() {
    override val formatIds = setOf("gif")
    private const val MAX_BLOCKS = 200_000

    override fun doInspect(source: ByteSource, fileName: String?): InspectionResult? {
        val h = source.read(0, 13)
        if (h.size < 13 || !(Bytes.startsWith(h, "GIF87a") || Bytes.startsWith(h, "GIF89a"))) return null
        val version = Bytes.ascii(h, 3, 3)
        val w = Bytes.u16le(h, 6); val ht = Bytes.u16le(h, 8)
        val packed = Bytes.u8(h, 10)
        val hasGct = packed and 0x80 != 0
        val colourRes = ((packed shr 4) and 7) + 1
        val gctSize = if (hasGct) 1 shl ((packed and 7) + 1) else 0
        val bg = Bytes.u8(h, 11); val aspect = Bytes.u8(h, 12)
        val win = Window(source, 127)
        val warnings = ArrayList<String>()
        var pos = 13L + gctSize * 3L
        var frames = 0L; var delayCs = 0L; var loop = -1; var transparent = false
        val comments = StringBuilder(); var commentCount = 0
        var trailer = false; var blocks = 0; var localPalettes = 0
        var interlaced = 0
        loop@ while (blocks++ < MAX_BLOCKS) {
            val b = win.u8(pos)
            if (b < 0) break
            when (b) {
                0x3B -> { trailer = true; break@loop }
                0x2C -> {
                    frames++
                    val pk = win.u8(pos + 9)
                    if (pk < 0) break@loop
                    pos += 10
                    if (pk and 0x80 != 0) { localPalettes++; pos += 3L * (1 shl ((pk and 7) + 1)) }
                    if (pk and 0x40 != 0) interlaced++
                    pos++ // LZW minimum code size
                    pos = skipSubBlocks(win, pos, null) ?: break@loop
                }
                0x21 -> {
                    val label = win.u8(pos + 1)
                    if (label < 0) break@loop
                    pos += 2
                    when (label) {
                        0xF9 -> {
                            val sz = win.u8(pos)
                            if (sz == 4) {
                                val flags = win.u8(pos + 1); val delay = win.u16le(pos + 2)
                                if (flags >= 0 && delay >= 0) { delayCs += delay; if (flags and 1 != 0) transparent = true }
                            }
                            pos = skipSubBlocks(win, pos, null) ?: break@loop
                        }
                        0xFF -> {
                            val sz = win.u8(pos)
                            if (sz == 11) {
                                val id = StringBuilder()
                                for (i in 1..11) id.append(win.u8(pos + i).coerceAtLeast(0).toChar())
                                val first = win.u8(pos + 12)
                                if ((id.toString() == "NETSCAPE2.0" || id.toString() == "ANIMEXTS1.0") && first == 3 && win.u8(pos + 13) == 1) {
                                    val l = win.u16le(pos + 14)
                                    if (l >= 0) loop = l
                                }
                            }
                            pos = skipSubBlocks(win, pos, null) ?: break@loop
                        }
                        0xFE -> {
                            val sb = java.io.ByteArrayOutputStream()
                            pos = skipSubBlocks(win, pos, sb) ?: break@loop
                            if (commentCount < 32 && comments.length < FormatInspector.MAX_TEXT_CHARS) {
                                val t = Img.clean(String(sb.toByteArray(), Charsets.ISO_8859_1)).trim()
                                if (t.isNotEmpty()) { commentCount++; comments.append(t).append('\n') }
                            }
                        }
                        else -> pos = skipSubBlocks(win, pos, null) ?: break@loop
                    }
                }
                else -> { warnings.add("Unexpected block byte 0x%02X at offset %d".format(b, pos)); break@loop }
            }
        }
        if (!trailer) {
            if (win.exhausted || blocks >= MAX_BLOCKS) warnings.add("Scan stopped early; frame count is a lower bound")
            else warnings.add("No GIF trailer; file may be truncated")
        }
        if (w == 0 || ht == 0) warnings.add("Logical screen has a zero dimension")
        if (frames == 0L) warnings.add("No image frames found")

        val f = FactList()
        f.add("Image", "Version", "GIF$version")
        f.add("Image", "Dimensions", Img.dims(w.toLong(), ht.toLong()))
        f.add("Image", "Global palette", if (hasGct) "$gctSize colours" else "none")
        f.add("Image", "Colour resolution", "$colourRes bits")
        if (hasGct) f.add("Image", "Background colour index", "$bg")
        if (aspect != 0) f.add("Image", "Pixel aspect ratio", "%.3f".format(java.util.Locale.ROOT, (aspect + 15) / 64.0))
        if (localPalettes > 0) f.add("Image", "Frames with local palette", "$localPalettes")
        if (interlaced > 0) f.add("Image", "Interlaced frames", "$interlaced")
        if (transparent) f.add("Image", "Transparency", "yes")
        f.add("Animation", "Frames", "$frames")
        if (frames > 1) {
            f.add("Animation", "Animated", "yes")
            f.add("Animation", "Total duration", "%.2f s".format(java.util.Locale.ROOT, delayCs / 100.0))
            f.add("Animation", "Loop", if (loop < 0) "not specified (plays once)" else if (loop == 0) "infinite" else "$loop times")
        } else f.add("Animation", "Animated", "no")
        if (commentCount > 0) f.add("Metadata", "Comments", "$commentCount")
        val summary = "GIF$version ${Img.dims(w.toLong(), ht.toLong())}, $frames frame" + (if (frames == 1L) "" else "s")
        return InspectionResult("gif", Img.title(fileName, "GIF image"), summary, f.list, warnings, comments.toString().ifEmpty { null })
    }

    /** Skips data sub-blocks starting at [pos]; returns the position after the terminator or null on EOF. */
    private fun skipSubBlocks(win: Window, start: Long, sink: java.io.ByteArrayOutputStream?): Long? {
        var pos = start
        var guard = 0
        while (guard++ < 10_000_000) {
            val n = win.u8(pos)
            if (n < 0) return null
            if (n == 0) return pos + 1
            if (sink != null && sink.size() < 65536) {
                for (i in 1..n) { val c = win.u8(pos + i); if (c < 0) return null; sink.write(c) }
            }
            pos += 1 + n
            if (pos >= win.size) return null
        }
        return null
    }
}
