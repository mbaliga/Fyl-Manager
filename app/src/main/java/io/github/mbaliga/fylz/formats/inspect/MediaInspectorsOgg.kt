package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** Ogg: Vorbis / Opus / FLAC / Speex / Theora identification, comments, duration from last granule. */
object MediaInspectorsOgg : FormatInspector {
    override val formatIds: Set<String> = setOf("ogg", "oga", "opus")
    private val U = MediaInspectorsUtil

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? =
        try { run(source, fileName) } catch (t: Throwable) { null }

    private fun run(src: ByteSource, fileName: String?): InspectionResult? {
        val size = src.size
        val head = U.readRange(src, 0, 512L * 1024)
        if (head.size < 27 || !Bytes.startsWith(head, "OggS")) return null
        // Reassemble the first logical stream's first packets from the leading pages.
        val firstSerial = Bytes.u32le(head, 14)
        val packets = ArrayList<ByteArray>()
        var cur = java.io.ByteArrayOutputStream()
        var p = 0
        var pages = 0
        while (p + 27 <= head.size && pages < 64 && packets.size < 3) {
            if (!Bytes.startsWith(head, "OggS", p)) break
            val nseg = Bytes.u8(head, p + 26)
            if (p + 27 + nseg > head.size) break
            pages++
            val serial = Bytes.u32le(head, p + 14)
            var q = p + 27 + nseg
            var total = 0
            for (i in 0 until nseg) total += Bytes.u8(head, p + 27 + i)
            if (serial == firstSerial) {
                var off = q
                for (i in 0 until nseg) {
                    val l = Bytes.u8(head, p + 27 + i)
                    val e = minOf(head.size, off + l)
                    if (off < e) cur.write(head, off, e - off)
                    off += l
                    if (l < 255) { packets.add(cur.toByteArray()); cur = java.io.ByteArrayOutputStream() }
                }
            }
            q += total
            p = q
        }
        if (packets.isEmpty()) return null
        val id = packets[0]
        val facts = ArrayList<Fact>()
        val warnings = ArrayList<String>()
        var codec: String? = null
        var rate = 0L; var channels = 0; var preskip = 0; var granuleRate = 0L
        var commentOff = -1; var commentPacket: ByteArray? = null
        var summaryExtra = ""
        if (id.size >= 30 && id[0] == 1.toByte() && Bytes.startsWith(id, "vorbis", 1)) {
            codec = "Vorbis"
            channels = Bytes.u8(id, 11); rate = Bytes.u32le(id, 12); granuleRate = rate
            val nom = Bytes.u32le(id, 20).toInt()
            facts.add(Fact("Codec", "Vorbis (version ${Bytes.u32le(id, 7)})", "Audio"))
            facts.add(Fact("Channels", channels.toString(), "Audio"))
            facts.add(Fact("Sample rate", "$rate Hz", "Audio"))
            if (nom > 0) facts.add(Fact("Nominal bitrate", "${nom / 1000} kbps", "Audio"))
            if (packets.size > 1 && packets[1].size > 7 && packets[1][0] == 3.toByte() && Bytes.startsWith(packets[1], "vorbis", 1)) {
                commentPacket = packets[1]; commentOff = 7
            }
        } else if (id.size >= 19 && Bytes.startsWith(id, "OpusHead")) {
            codec = "Opus"
            channels = Bytes.u8(id, 9); preskip = Bytes.u16le(id, 10); rate = Bytes.u32le(id, 12); granuleRate = 48000
            facts.add(Fact("Codec", "Opus (version ${Bytes.u8(id, 8)})", "Audio"))
            facts.add(Fact("Channels", channels.toString(), "Audio"))
            facts.add(Fact("Original input rate", "$rate Hz", "Audio"))
            facts.add(Fact("Pre-skip", "$preskip samples", "Audio"))
            if (packets.size > 1 && Bytes.startsWith(packets[1], "OpusTags")) { commentPacket = packets[1]; commentOff = 8 }
        } else if (id.size >= 9 && id[0] == 0x7F.toByte() && Bytes.startsWith(id, "FLAC", 1)) {
            codec = "FLAC"
            facts.add(Fact("Codec", "FLAC in Ogg", "Audio"))
            if (id.size >= 17 + 34) {
                val packed = Bytes.u64be(id, 17 + 10)
                rate = (packed ushr 44) and 0xFFFFF; channels = (((packed ushr 41) and 7) + 1).toInt(); granuleRate = rate
                facts.add(Fact("Channels", channels.toString(), "Audio")); facts.add(Fact("Sample rate", "$rate Hz", "Audio"))
            }
            if (packets.size > 1 && packets[1].size > 4 && (packets[1][0].toInt() and 0x7F) == 4) { commentPacket = packets[1]; commentOff = 4 }
        } else if (id.size >= 8 && Bytes.startsWith(id, "Speex   ")) {
            codec = "Speex"; facts.add(Fact("Codec", "Speex", "Audio"))
            if (id.size >= 40) { rate = Bytes.u32le(id, 36); granuleRate = rate; facts.add(Fact("Sample rate", "$rate Hz", "Audio")) }
            if (packets.size > 1) { commentPacket = packets[1]; commentOff = 0 }
        } else if (id.size >= 7 && id[0] == 0x80.toByte() && Bytes.startsWith(id, "theora", 1)) {
            codec = "Theora"; facts.add(Fact("Codec", "Theora (video)", "Video"))
            if (id.size >= 21) facts.add(Fact("Picture size", "${(Bytes.u16be(id, 14) shl 8) or Bytes.u8(id, 16)}x${(Bytes.u16be(id, 17) shl 8) or Bytes.u8(id, 19)}", "Video"))
        } else {
            return null
        }
        var title: String? = null
        if (commentPacket != null) {
            val vc = U.vorbisComments(commentPacket, commentOff, commentPacket.size)
            if (vc != null) {
                U.addVorbisFacts(facts, vc.first, vc.second, "Tags")
                title = vc.second.firstOrNull { it.first == "TITLE" }?.second
            }
        }
        // Duration from the last page's granule position
        var seconds = -1.0
        if (granuleRate > 0) {
            val tailLen = minOf(size, 128L * 1024)
            val tail = U.readRange(src, size - tailLen, tailLen)
            var i = tail.size - 27
            while (i >= 0) {
                if (Bytes.startsWith(tail, "OggS", i) && Bytes.u8(tail, i + 4) == 0 && Bytes.u32le(tail, i + 14) == firstSerial) {
                    val gran = Bytes.u64le(tail, i + 6)
                    if (gran != -1L && gran >= 0) {
                        val samples = if (codec == "Opus") gran - preskip else gran
                        if (samples >= 0) seconds = samples.toDouble() / granuleRate
                        break
                    }
                }
                i--
            }
            if (seconds >= 0) facts.add(Fact("Duration", U.duration(seconds), "Audio"))
            else warnings.add("Could not find a final page with a granule position")
        }
        facts.add(Fact("Pages parsed for headers", pages.toString(), "Container"))
        val summary = codec + (if (channels > 0) ", $channels ch" else "") + (if (rate > 0 && codec != "Opus") ", $rate Hz" else "") +
            (if (seconds >= 0) ", " + U.duration(seconds) else "") + summaryExtra
        val ext = fileName?.substringAfterLast('.', "")?.lowercase()
        val fid = if (ext != null && ext in formatIds) ext else "ogg"
        return InspectionResult(fid, title ?: "Ogg $codec", summary, facts, warnings)
    }
}
