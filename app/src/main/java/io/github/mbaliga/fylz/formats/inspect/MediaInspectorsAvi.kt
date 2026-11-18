package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** AVI (RIFF): main header, stream headers and formats. */
object MediaInspectorsAvi : FormatInspector {
    override val formatIds: Set<String> = setOf("avi")
    private val U = MediaInspectorsUtil

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? =
        try { run(source) } catch (t: Throwable) { null }

    private class Stream {
        var type = ""; var handler = ""; var scale = 0L; var rate = 0L; var length = 0L
        var w = 0L; var h = 0L; var bits = 0; var comp = ""; var name = ""
        var tag = -1; var ch = 0; var sr = 0L; var abits = 0
    }

    private fun parseStrl(b: ByteArray, from: Int, to: Int, out: Stream) {
        var p = from
        var n = 0
        while (p + 8 <= to && n++ < 50) {
            val id = Bytes.ascii(b, p, 4)
            val sz = Bytes.u32le(b, p + 4)
            val s = p + 8
            if (sz > to - s) break
            val e = s + sz.toInt()
            when (id) {
                "strh" -> if (sz >= 36) {
                    out.type = Bytes.ascii(b, s, 4); out.handler = Bytes.ascii(b, s + 4, 4)
                    out.scale = Bytes.u32le(b, s + 20); out.rate = Bytes.u32le(b, s + 24); out.length = Bytes.u32le(b, s + 32)
                }
                "strf" -> if (out.type == "vids" && sz >= 16) {
                    out.w = Bytes.u32le(b, s + 4); out.h = Math.abs(Bytes.u32le(b, s + 8).toInt()).toLong()
                    out.bits = Bytes.u16le(b, s + 14)
                    out.comp = if (sz >= 20) Bytes.ascii(b, s + 16, 4) else ""
                    if (sz >= 20 && Bytes.u32le(b, s + 16) == 0L) out.comp = "RGB"
                } else if (out.type == "auds" && sz >= 16) {
                    out.tag = Bytes.u16le(b, s); out.ch = Bytes.u16le(b, s + 2); out.sr = Bytes.u32le(b, s + 4); out.abits = Bytes.u16le(b, s + 14)
                }
                "strn" -> out.name = U.clean(U.latin1(b, s, minOf(sz.toInt(), 200)), 200)
            }
            p = e + (sz.toInt() and 1)
        }
    }

    private fun run(src: ByteSource): InspectionResult? {
        val size = src.size
        val h = U.readRange(src, 0, 12)
        if (h.size < 12 || !Bytes.startsWith(h, "RIFF") || !Bytes.startsWith(h, "AVI ", 8)) return null
        val facts = ArrayList<Fact>()
        val warnings = ArrayList<String>()
        var microPerFrame = 0L; var totalFrames = 0L; var width = 0L; var height = 0L; var flags = 0L; var avihSeen = false
        var odmlFrames = -1L
        val streams = ArrayList<Stream>()
        val info = LinkedHashMap<String, String>()
        var hasIdx = false
        var pos = 12L
        var n = 0
        while (n++ < 200 && pos + 8 <= size) {
            val ch = U.readRange(src, pos, 12)
            if (ch.size < 8) break
            val id = Bytes.ascii(ch, 0, 4)
            val sz = Bytes.u32le(ch, 4)
            val body = pos + 8
            if (id == "LIST" && ch.size >= 12) {
                val lt = Bytes.ascii(ch, 8, 4)
                if (lt == "hdrl") {
                    val b = U.readRange(src, body + 4, minOf(maxOf(sz - 4, 0L), 1024L * 1024))
                    var p = 0
                    var k = 0
                    while (p + 8 <= b.size && k++ < 100) {
                        val cid = Bytes.ascii(b, p, 4)
                        val cs = Bytes.u32le(b, p + 4)
                        if (cs > b.size - p - 8) break
                        val s = p + 8
                        if (cid == "avih" && cs >= 40) {
                            avihSeen = true
                            microPerFrame = Bytes.u32le(b, s); flags = Bytes.u32le(b, s + 12); totalFrames = Bytes.u32le(b, s + 16)
                            width = Bytes.u32le(b, s + 32); height = Bytes.u32le(b, s + 36)
                        } else if (cid == "LIST" && cs >= 4) {
                            val t = Bytes.ascii(b, s, 4)
                            if (t == "strl" && streams.size < 32) { val st = Stream(); parseStrl(b, s + 4, s + cs.toInt(), st); streams.add(st) }
                            else if (t == "odml") {
                                var q = s + 4
                                if (q + 8 <= s + cs.toInt() && Bytes.ascii(b, q, 4) == "dmlh") odmlFrames = Bytes.u32le(b, q + 8)
                            }
                        }
                        p = s + cs.toInt() + (cs.toInt() and 1)
                    }
                } else if (lt == "INFO") {
                    val b = U.readRange(src, body + 4, minOf(maxOf(sz - 4, 0L), 64L * 1024))
                    var p = 0; var k = 0
                    while (p + 8 <= b.size && k++ < 50) {
                        val cid = Bytes.ascii(b, p, 4); val cs = Bytes.u32le(b, p + 4)
                        if (cs > b.size - p - 8) break
                        val label = when (cid) { "INAM" -> "Title"; "IART" -> "Artist"; "ISFT" -> "Software"; "ICMT" -> "Comment"; "ICRD" -> "Created"; "IGNR" -> "Genre"; else -> null }
                        if (label != null) { val v = U.clean(U.latin1(b, p + 8, cs.toInt())); if (v.isNotEmpty()) info[label] = v }
                        p += 8 + cs.toInt() + (cs.toInt() and 1)
                    }
                }
            } else if (id == "idx1") hasIdx = true
            val next = body + sz + (sz and 1L)
            if (next <= pos || next > size) break
            pos = next
        }
        if (!avihSeen) warnings.add("No avih main header found")
        if (avihSeen) {
            facts.add(Fact("Frame size", "${width}x$height", "Video"))
            if (microPerFrame > 0) facts.add(Fact("Frame rate (main header)", U.num(1e6 / microPerFrame, 3) + " fps", "Video"))
            facts.add(Fact("Total frames", (if (odmlFrames > totalFrames) odmlFrames else totalFrames).toString(), "Container"))
            facts.add(Fact("Stream count", streams.size.toString(), "Container"))
            facts.add(Fact("Index", if (hasIdx || (flags and 0x10L) != 0L) "yes" else "no", "Container"))
        }
        var seconds = -1.0
        var i = 0
        var firstVideo: Stream? = null
        for (s in streams) {
            i++
            val kind = when (s.type) { "vids" -> "video"; "auds" -> "audio"; "txts" -> "text"; "mids" -> "midi"; else -> s.type.ifEmpty { "unknown" } }
            val g = "Stream $i ($kind)"
            if (s.type == "vids") {
                if (firstVideo == null) firstVideo = s
                facts.add(Fact("Codec", s.comp.ifEmpty { s.handler.trim() }.let { c -> if (s.handler.isNotBlank() && s.handler.trim() != c) "$c (handler ${s.handler.trim()})" else c }, g))
                facts.add(Fact("Frame size", "${s.w}x${s.h}", g))
                if (s.bits > 0) facts.add(Fact("Bit depth", s.bits.toString(), g))
                if (s.scale > 0 && s.rate > 0) {
                    facts.add(Fact("Frame rate", U.num(s.rate.toDouble() / s.scale, 3) + " fps", g))
                    if (s.length > 0 && seconds < 0) seconds = s.length.toDouble() * s.scale / s.rate
                }
                if (s.length > 0) facts.add(Fact("Length", "${s.length} frames", g))
            } else if (s.type == "auds") {
                if (s.tag >= 0) facts.add(Fact("Codec", U.wavFormatName(s.tag), g))
                facts.add(Fact("Channels", s.ch.toString(), g))
                facts.add(Fact("Sample rate", "${s.sr} Hz", g))
                if (s.abits > 0) facts.add(Fact("Bits per sample", s.abits.toString(), g))
            }
            if (s.name.isNotEmpty()) facts.add(Fact("Name", s.name, g))
        }
        if (seconds < 0 && microPerFrame > 0 && totalFrames > 0) seconds = totalFrames.toDouble() * microPerFrame / 1e6
        if (seconds >= 0) facts.add(0, Fact("Duration", U.duration(seconds), "Video"))
        for ((k, v) in info) facts.add(Fact(k, v, "Tags"))
        val parts = ArrayList<String>()
        if (avihSeen && width > 0) parts.add("${width}x$height")
        firstVideo?.let { if (it.comp.isNotEmpty()) parts.add(it.comp) }
        if (seconds >= 0) parts.add(U.duration(seconds))
        return InspectionResult("avi", info["Title"] ?: "AVI video", parts.joinToString(", ").ifEmpty { null }, facts, warnings)
    }
}
