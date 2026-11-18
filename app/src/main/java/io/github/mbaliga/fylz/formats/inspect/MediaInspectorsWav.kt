package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** WAV (RIFF/WAVE): fmt chunk, data size, duration, LIST/INFO tags. */
object MediaInspectorsWav : FormatInspector {
    override val formatIds: Set<String> = setOf("wav")
    private val U = MediaInspectorsUtil

    private val INFO = mapOf(
        "INAM" to "Title", "IART" to "Artist", "IPRD" to "Album/product", "ICMT" to "Comment",
        "ICRD" to "Created", "ISFT" to "Software", "IGNR" to "Genre", "ICOP" to "Copyright", "ITRK" to "Track",
    )

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? =
        try { run(source) } catch (t: Throwable) { null }

    private fun run(src: ByteSource): InspectionResult? {
        val size = src.size
        val h = U.readRange(src, 0, 12)
        if (h.size < 12 || !Bytes.startsWith(h, "RIFF") || !Bytes.startsWith(h, "WAVE", 8)) return null
        val warnings = ArrayList<String>()
        val facts = ArrayList<Fact>()
        var tag = -1; var channels = 0; var rate = 0L; var byteRate = 0L; var align = 0; var bits = 0; var validBits = 0
        var dataSize = -1L
        var factSamples = -1L
        val info = LinkedHashMap<String, String>()
        var pos = 12L
        var n = 0
        while (n++ < 500 && pos + 8 <= size) {
            val ch = U.readRange(src, pos, 8)
            if (ch.size < 8) break
            val id = Bytes.ascii(ch, 0, 4)
            val sz = Bytes.u32le(ch, 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    val f = U.readRange(src, body, minOf(sz, 256L))
                    if (f.size >= 16) {
                        tag = Bytes.u16le(f, 0); channels = Bytes.u16le(f, 2); rate = Bytes.u32le(f, 4)
                        byteRate = Bytes.u32le(f, 8); align = Bytes.u16le(f, 12); bits = Bytes.u16le(f, 14)
                        if (tag == 0xFFFE && f.size >= 26) {
                            validBits = Bytes.u16le(f, 18)
                            tag = Bytes.u16le(f, 24)
                            facts.add(Fact("Container format", "WAVE_FORMAT_EXTENSIBLE", "Audio"))
                        }
                    } else warnings.add("fmt chunk is too short")
                }
                "fact" -> { val f = U.readRange(src, body, 4); if (f.size == 4) factSamples = Bytes.u32le(f, 0) }
                "data" -> {
                    if (sz > size - body) {
                        warnings.add("data chunk size exceeds the file; using the remaining bytes")
                        dataSize = maxOf(0L, size - body)
                    } else dataSize = sz
                }
                "LIST" -> {
                    val l = U.readRange(src, body, minOf(sz, 64L * 1024))
                    if (l.size >= 4 && Bytes.startsWith(l, "INFO")) {
                        var p = 4; var k = 0
                        while (p + 8 <= l.size && k++ < 100) {
                            val cid = Bytes.ascii(l, p, 4)
                            val cs = Bytes.u32le(l, p + 4)
                            if (cs > l.size - p - 8) break
                            INFO[cid]?.let { label -> val v = U.clean(U.latin1(l, p + 8, cs.toInt())); if (v.isNotEmpty()) info[label] = v }
                            p += 8 + cs.toInt() + (cs.toInt() and 1)
                        }
                    }
                }
            }
            if (id == "data" && sz > size - body) break
            val next = body + sz + (sz and 1L)
            if (next <= pos) break
            pos = next
        }
        if (tag < 0) warnings.add("No fmt chunk found")
        if (dataSize < 0) warnings.add("No data chunk found")
        val g = "Audio"
        if (tag >= 0) {
            facts.add(0, Fact("Codec", U.wavFormatName(tag), g))
            facts.add(Fact("Channels", channels.toString(), g))
            facts.add(Fact("Sample rate", "$rate Hz", g))
            facts.add(Fact("Bits per sample", bits.toString(), g))
            if (validBits != 0 && validBits != bits) facts.add(Fact("Valid bits", validBits.toString(), g))
            facts.add(Fact("Byte rate", "$byteRate B/s", g))
            facts.add(Fact("Block align", align.toString(), g))
        }
        var seconds = -1.0
        if (dataSize >= 0) {
            facts.add(Fact("Data size", Bytes.humanSize(dataSize) + " ($dataSize bytes)", "Container"))
            if (byteRate > 0) seconds = dataSize.toDouble() / byteRate
            else if (tag == 1 && align > 0 && rate > 0) seconds = (dataSize / align).toDouble() / rate
            if (seconds >= 0) facts.add(Fact("Duration", U.duration(seconds), g))
        }
        if (factSamples >= 0) facts.add(Fact("Sample frames (fact)", factSamples.toString(), g))
        for ((k, v) in info) facts.add(Fact(k, v, "Tags"))
        val summary = if (tag >= 0) "${U.wavFormatName(tag)}, $channels ch, $rate Hz, $bits-bit" +
            (if (seconds >= 0) ", " + U.duration(seconds) else "") else null
        return InspectionResult("wav", info["Title"] ?: "WAV audio", summary, facts, warnings)
    }
}
