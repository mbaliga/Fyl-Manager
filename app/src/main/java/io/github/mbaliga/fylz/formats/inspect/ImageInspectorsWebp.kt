package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** WebP (RIFF container): VP8 / VP8L / VP8X, animation, alpha. */
internal object WebpInspector : SafeInspector() {
    override val formatIds = setOf("webp")
    private const val MAX_CHUNKS = 10_000

    override fun doInspect(source: ByteSource, fileName: String?): InspectionResult? {
        val h = source.read(0, 12)
        if (h.size < 12 || !Bytes.startsWith(h, "RIFF") || !Bytes.startsWith(h, "WEBP", 8)) return null
        val warnings = ArrayList<String>()
        val riffSize = Bytes.u32le(h, 4)
        val size = source.size
        if (riffSize + 8 > size) warnings.add("File is shorter than its RIFF header declares")
        var canvasW = 0L; var canvasH = 0L
        var vp8x = false; var flagAlpha = false; var flagAnim = false; var flagIcc = false; var flagExif = false; var flagXmp = false
        var codec: String? = null; var imgW = 0L; var imgH = 0L; var losslessAlpha = false; var alphaChunk = false
        var frames = 0L; var durationMs = 0L; var loop = -1; var bg: Long = -1
        var pos = 12L; var n = 0
        while (n++ < MAX_CHUNKS && pos + 8 <= size) {
            val ch = source.read(pos, 24)
            if (ch.size < 8) break
            val id = Bytes.ascii(ch, 0, 4); val len = Bytes.u32le(ch, 4)
            fun dat(n: Int): ByteArray = if (len >= n) ch.copyOfRange(8, minOf(ch.size, 8 + n)) else ByteArray(0)
            val dpos = pos + 8
            when (id) {
                "VP8X" -> {
                    val d = dat(10)
                    if (d.size >= 10 && !vp8x) {
                        vp8x = true
                        val fl = Bytes.u8(d, 0)
                        flagIcc = fl and 0x20 != 0; flagAlpha = fl and 0x10 != 0; flagExif = fl and 0x08 != 0
                        flagXmp = fl and 0x04 != 0; flagAnim = fl and 0x02 != 0
                        canvasW = (Bytes.u8(d, 4) or (Bytes.u8(d, 5) shl 8) or (Bytes.u8(d, 6) shl 16)) + 1L
                        canvasH = (Bytes.u8(d, 7) or (Bytes.u8(d, 8) shl 8) or (Bytes.u8(d, 9) shl 16)) + 1L
                    }
                }
                "VP8 " -> if (codec == null) {
                    val d = dat(10)
                    if (d.size >= 10 && Bytes.u8(d, 3) == 0x9D && Bytes.u8(d, 4) == 0x01 && Bytes.u8(d, 5) == 0x2A) {
                        codec = "Lossy (VP8)"
                        imgW = (Bytes.u16le(d, 6) and 0x3FFF).toLong(); imgH = (Bytes.u16le(d, 8) and 0x3FFF).toLong()
                    } else warnings.add("VP8 chunk has no valid key-frame header")
                }
                "VP8L" -> if (codec == null) {
                    val d = dat(5)
                    if (d.size >= 5 && Bytes.u8(d, 0) == 0x2F) {
                        codec = "Lossless (VP8L)"
                        val v = Bytes.u32le(d, 1)
                        imgW = (v and 0x3FFF) + 1; imgH = ((v shr 14) and 0x3FFF) + 1
                        losslessAlpha = (v shr 28) and 1L == 1L
                    } else warnings.add("VP8L chunk has no valid signature")
                }
                "ALPH" -> alphaChunk = true
                "ANIM" -> {
                    val d = dat(6)
                    if (d.size >= 6) { bg = Bytes.u32le(d, 0); loop = Bytes.u16le(d, 4) }
                }
                "ANMF" -> {
                    frames++
                    val d = dat(16)
                    if (d.size >= 15) durationMs += Bytes.u8(d, 12) or (Bytes.u8(d, 13) shl 8) or (Bytes.u8(d, 14) shl 16)
                }
            }
            pos += 8 + len + (len and 1L)
        }
        if (n > MAX_CHUNKS) warnings.add("Chunk scan stopped after $MAX_CHUNKS chunks; frame count is a lower bound")
        if (!vp8x && codec == null) warnings.add("No image data chunk found")

        val w = if (vp8x) canvasW else imgW
        val ht = if (vp8x) canvasH else imgH
        val animated = flagAnim || frames > 0
        val f = FactList()
        if (w > 0) f.add("Image", "Dimensions", Img.dims(w, ht))
        f.add("Image", "Container", if (vp8x) "Extended (VP8X)" else "Simple")
        f.add("Image", "Compression", codec ?: if (animated) "Per-frame (animation)" else null)
        val alpha = flagAlpha || losslessAlpha || alphaChunk
        f.add("Image", "Alpha channel", if (alpha) "yes" else "no")
        if (vp8x) {
            f.add("Image", "ICC profile", if (flagIcc) "present" else "no")
            f.add("Metadata", "EXIF", if (flagExif) "present" else "no")
            f.add("Metadata", "XMP", if (flagXmp) "present" else "no")
        }
        f.add("Animation", "Animated", if (animated) "yes" else "no")
        if (animated) {
            f.add("Animation", "Frames", "$frames")
            f.add("Animation", "Total duration", "%.2f s".format(java.util.Locale.ROOT, durationMs / 1000.0))
            if (loop >= 0) f.add("Animation", "Loop", if (loop == 0) "infinite" else "$loop times")
            if (bg >= 0) f.add("Animation", "Background colour (BGRA)", "0x%08X".format(bg))
        }
        val summary = if (w > 0) "WebP ${Img.dims(w, ht)}" + (if (animated) ", animated" else "") + (if (alpha) ", alpha" else "") else null
        return InspectionResult("webp", Img.title(fileName, "WebP image"), summary, f.list, warnings)
    }
}
