package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** FLAC: STREAMINFO, Vorbis comments, pictures. */
object MediaInspectorsFlac : FormatInspector {
    override val formatIds: Set<String> = setOf("flac")
    private val U = MediaInspectorsUtil

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? =
        try { run(source) } catch (t: Throwable) { null }

    private fun blockName(t: Int) = when (t) {
        0 -> "STREAMINFO"; 1 -> "PADDING"; 2 -> "APPLICATION"; 3 -> "SEEKTABLE"
        4 -> "VORBIS_COMMENT"; 5 -> "CUESHEET"; 6 -> "PICTURE"; else -> "type $t"
    }

    private fun run(src: ByteSource): InspectionResult? {
        val size = src.size
        var pos = 0L
        val h = U.readRange(src, 0, 10)
        if (h.size == 10 && Bytes.startsWith(h, "ID3") && (6..9).all { Bytes.u8(h, it) < 0x80 }) {
            pos = 10L + (((Bytes.u8(h, 6) shl 21) or (Bytes.u8(h, 7) shl 14) or (Bytes.u8(h, 8) shl 7) or Bytes.u8(h, 9)).toLong())
        }
        val magic = U.readRange(src, pos, 4)
        if (!Bytes.startsWith(magic, "fLaC")) return null
        pos += 4
        val facts = ArrayList<Fact>()
        val warnings = ArrayList<String>()
        val blocks = ArrayList<String>()
        var rate = 0L; var channels = 0; var bits = 0; var total = 0L
        var gotInfo = false
        var title: String? = null
        var n = 0
        var last = false
        while (!last && n++ < 200 && pos + 4 <= size) {
            val bh = U.readRange(src, pos, 4)
            if (bh.size < 4) break
            last = (bh[0].toInt() and 0x80) != 0
            val type = bh[0].toInt() and 0x7F
            val len = (Bytes.u8(bh, 1) shl 16) or (Bytes.u8(bh, 2) shl 8) or Bytes.u8(bh, 3)
            val body = pos + 4
            if (body + len > size) { warnings.add("${blockName(type)} block runs past the end of the file"); break }
            blocks.add(blockName(type))
            when (type) {
                0 -> if (len >= 34) {
                    val b = U.readRange(src, body, 34)
                    val minBlock = Bytes.u16be(b, 0); val maxBlock = Bytes.u16be(b, 2)
                    val minFrame = (Bytes.u8(b, 4) shl 16) or (Bytes.u8(b, 5) shl 8) or Bytes.u8(b, 6)
                    val maxFrame = (Bytes.u8(b, 7) shl 16) or (Bytes.u8(b, 8) shl 8) or Bytes.u8(b, 9)
                    val packed = Bytes.u64be(b, 10)
                    rate = (packed ushr 44) and 0xFFFFF
                    channels = (((packed ushr 41) and 7) + 1).toInt()
                    bits = (((packed ushr 36) and 31) + 1).toInt()
                    total = packed and 0xFFFFFFFFFL
                    val md5 = b.copyOfRange(18, 34).joinToString("") { String.format("%02x", it) }
                    gotInfo = true
                    facts.add(Fact("Sample rate", "$rate Hz", "Audio"))
                    facts.add(Fact("Channels", channels.toString(), "Audio"))
                    facts.add(Fact("Bits per sample", bits.toString(), "Audio"))
                    if (total > 0) facts.add(Fact("Total samples", total.toString(), "Audio"))
                    if (rate > 0 && total > 0) facts.add(Fact("Duration", U.duration(total.toDouble() / rate), "Audio"))
                    facts.add(Fact("Block size", if (minBlock == maxBlock) "$minBlock (fixed)" else "$minBlock-$maxBlock (variable)", "Audio"))
                    if (minFrame != 0 || maxFrame != 0) facts.add(Fact("Frame size (bytes)", "$minFrame-$maxFrame", "Audio"))
                    if (md5.any { it != '0' }) facts.add(Fact("Audio MD5", md5, "Audio"))
                } else warnings.add("STREAMINFO block is too short")
                4 -> {
                    val b = U.readRange(src, body, minOf(len.toLong(), 1024L * 1024))
                    val vc = U.vorbisComments(b, 0, b.size)
                    if (vc != null) {
                        U.addVorbisFacts(facts, vc.first, vc.second, "Tags")
                        title = vc.second.firstOrNull { it.first == "TITLE" }?.second
                    } else warnings.add("Vorbis comment block is malformed")
                }
                6 -> {
                    val b = U.readRange(src, body, minOf(len.toLong(), 64L * 1024))
                    if (b.size >= 32) {
                        val ml = Bytes.u32be(b, 4)
                        if (ml <= 256 && 8 + ml + 4 <= b.size) {
                            val mime = U.clean(U.latin1(b, 8, ml.toInt()), 100)
                            val dl = Bytes.u32be(b, 8 + ml.toInt())
                            val o = 8 + ml.toInt() + 4
                            if (dl <= b.size - o - 16) {
                                val w = Bytes.u32be(b, o + dl.toInt()); val hh = Bytes.u32be(b, o + dl.toInt() + 4)
                                facts.add(Fact("Picture", "$mime ${w}x$hh (type ${Bytes.u32be(b, 0)})", "Tags"))
                            } else facts.add(Fact("Picture", mime, "Tags"))
                        }
                    }
                }
            }
            pos = body + len
        }
        if (!gotInfo) warnings.add("No STREAMINFO block found")
        if (!last && pos + 4 > size) warnings.add("Metadata blocks end before the audio frames")
        facts.add(Fact("Metadata blocks", blocks.joinToString(", "), "Container"))
        val summary = if (gotInfo) "FLAC, $channels ch, $rate Hz, $bits-bit" +
            (if (rate > 0 && total > 0) ", " + U.duration(total.toDouble() / rate) else "") else null
        return InspectionResult("flac", title ?: "FLAC audio", summary, facts, warnings)
    }
}
