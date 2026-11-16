package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** AIFF / AIFF-C: COMM chunk, text chunks, SSND size. */
object MediaInspectorsAiff : FormatInspector {
    override val formatIds: Set<String> = setOf("aiff", "aifc")
    private val U = MediaInspectorsUtil

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? =
        try { run(source) } catch (t: Throwable) { null }

    /** 80-bit IEEE extended, big-endian. */
    private fun extended(b: ByteArray, o: Int): Double {
        val se = Bytes.u16be(b, o)
        val sign = if ((se and 0x8000) != 0) -1.0 else 1.0
        val exp = se and 0x7FFF
        val mant = Bytes.u64be(b, o + 2)
        if (exp == 0 && mant == 0L) return 0.0
        if (exp == 0x7FFF) return Double.NaN
        val m = (mant ushr 1).toDouble() * 2.0 // avoids sign problems for the top bit
        return sign * m * Math.pow(2.0, (exp - 16383 - 63).toDouble())
    }

    private fun compName(t: String) = when (t) {
        "NONE" -> "none (PCM, big-endian)"; "sowt" -> "PCM (little-endian)"; "fl32" -> "32-bit float"; "fl64" -> "64-bit float"
        "alaw" -> "A-law"; "ulaw" -> "mu-law"; "ima4" -> "IMA 4:1 ADPCM"; else -> t
    }

    private fun run(src: ByteSource): InspectionResult? {
        val size = src.size
        val h = U.readRange(src, 0, 12)
        if (h.size < 12 || !Bytes.startsWith(h, "FORM")) return null
        val form = Bytes.ascii(h, 8, 4)
        if (form != "AIFF" && form != "AIFC") return null
        val facts = ArrayList<Fact>()
        val warnings = ArrayList<String>()
        facts.add(Fact("Variant", if (form == "AIFC") "AIFF-C (compressed-capable)" else "AIFF", "Container"))
        var pos = 12L
        var n = 0
        var channels = 0; var frames = 0L; var bits = 0; var rate = 0.0; var comp: String? = null
        var haveComm = false
        var ssnd = -1L
        val text = LinkedHashMap<String, String>()
        while (n++ < 500 && pos + 8 <= size) {
            val ch = U.readRange(src, pos, 8)
            if (ch.size < 8) break
            val id = Bytes.ascii(ch, 0, 4)
            val sz = Bytes.u32be(ch, 4)
            val body = pos + 8
            when (id) {
                "COMM" -> {
                    val c = U.readRange(src, body, minOf(sz, 256L))
                    if (c.size >= 18) {
                        haveComm = true
                        channels = Bytes.u16be(c, 0); frames = Bytes.u32be(c, 2); bits = Bytes.u16be(c, 6); rate = extended(c, 8)
                        if (form == "AIFC" && c.size >= 22) {
                            val t = Bytes.ascii(c, 18, 4)
                            val l = Bytes.u8(c, 22)
                            val nm = if (l > 0 && 23 + l <= c.size) U.clean(U.latin1(c, 23, l), 100) else ""
                            comp = compName(t) + (if (nm.isNotEmpty() && nm != t) " ($nm)" else "")
                        }
                    } else warnings.add("COMM chunk is too short")
                }
                "SSND" -> ssnd = (minOf(sz, size - body) - 8).coerceAtLeast(0) // minus offset+blockSize header
                "NAME", "AUTH", "(c) ", "ANNO" -> {
                    val label = when (id) { "NAME" -> "Title"; "AUTH" -> "Author"; "(c) " -> "Copyright"; else -> "Annotation" }
                    val v = U.clean(U.latin1(U.readRange(src, body, minOf(sz, 4096L)), 0, 4096))
                    if (v.isNotEmpty() && !text.containsKey(label)) text[label] = v
                }
            }
            val next = body + sz + (sz and 1L)
            if (next <= pos || next > size) break
            pos = next
        }
        if (!haveComm) warnings.add("No COMM chunk found")
        var seconds = -1.0
        if (haveComm) {
            facts.add(Fact("Channels", channels.toString(), "Audio"))
            facts.add(Fact("Sample rate", if (rate.isNaN()) "unknown" else U.num(rate, 0) + " Hz", "Audio"))
            facts.add(Fact("Bits per sample", bits.toString(), "Audio"))
            facts.add(Fact("Sample frames", frames.toString(), "Audio"))
            comp?.let { facts.add(Fact("Compression", it, "Audio")) }
            if (rate > 0 && !rate.isInfinite()) { seconds = frames / rate; facts.add(Fact("Duration", U.duration(seconds), "Audio")) }
        }
        if (ssnd >= 0) facts.add(Fact("Sound data", Bytes.humanSize(ssnd), "Container"))
        for ((k, v) in text) facts.add(Fact(k, v, "Tags"))
        val summary = if (haveComm) "$channels ch, ${if (rate.isNaN()) "?" else U.num(rate, 0)} Hz, $bits-bit" + (if (seconds >= 0) ", " + U.duration(seconds) else "") else null
        return InspectionResult(if (form == "AIFC") "aifc" else "aiff", text["Title"] ?: (if (form == "AIFC") "AIFF-C audio" else "AIFF audio"), summary, facts, warnings)
    }
}
