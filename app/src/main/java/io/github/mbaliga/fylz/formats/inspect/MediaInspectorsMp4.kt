package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*
import java.util.Locale

/** ISO base media (MP4 / M4A / MOV): ftyp, moov/mvhd, tracks, sample entries, iTunes tags. */
object MediaInspectorsMp4 : FormatInspector {
    override val formatIds: Set<String> = setOf("mp4", "m4a", "mov", "m4v", "m4b", "3gp")
    private val U = MediaInspectorsUtil

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? =
        try { run(source, fileName) } catch (t: Throwable) { null }

    private class Box(val type: String, val start: Int, val payload: Int, val end: Int)

    private fun children(b: ByteArray, from: Int, to: Int): List<Box> {
        val out = ArrayList<Box>()
        val limit = minOf(to, b.size)
        var p = from
        var n = 0
        while (p >= 0 && p + 8 <= limit && n++ < 5000) {
            var size = Bytes.u32be(b, p)
            val type = Bytes.ascii(b, p + 4, 4).let { t -> if (b[p + 4] == 0xA9.toByte()) "©" + t.substring(1) else t }
            var hdr = 8
            if (size == 1L) {
                if (p + 16 > limit) break
                size = Bytes.u64be(b, p + 8); hdr = 16
            } else if (size == 0L) size = (limit - p).toLong()
            if (size < hdr) break
            val remaining = (limit - p).toLong()
            val end = if (size < 0 || size > remaining) limit else (p + size).toInt()
            out.add(Box(type, p, p + hdr, end))
            p = end
        }
        return out
    }

    private class Track {
        var id = 0L; var type = ""; var handlerName = ""; var timescale = 0L; var duration = 0L
        var lang = ""; var tw = 0.0; var th = 0.0; var codec = ""; var sw = 0; var sh = 0
        var channels = 0; var bits = 0; var rate = 0L; var enabled = true
    }

    private val CODECS = mapOf(
        "avc1" to "H.264 / AVC", "avc3" to "H.264 / AVC", "hvc1" to "H.265 / HEVC", "hev1" to "H.265 / HEVC",
        "av01" to "AV1", "vp09" to "VP9", "vp08" to "VP8", "mp4v" to "MPEG-4 Visual", "jpeg" to "Motion JPEG",
        "mp4a" to "MPEG-4 Audio (AAC family)", "alac" to "Apple Lossless", "ac-3" to "Dolby Digital (AC-3)",
        "ec-3" to "Dolby Digital Plus (E-AC-3)", "Opus" to "Opus", "fLaC" to "FLAC", "samr" to "AMR-NB",
        "sawb" to "AMR-WB", "tx3g" to "3GPP timed text", "wvtt" to "WebVTT", "c608" to "CEA-608 captions",
        "text" to "QuickTime text", "tmcd" to "Timecode", "lpcm" to "Linear PCM", "sowt" to "PCM (little-endian)",
        "twos" to "PCM (big-endian)", "raw " to "Uncompressed",
    )

    private val HANDLERS = mapOf(
        "vide" to "video", "soun" to "audio", "text" to "text", "sbtl" to "subtitle", "subt" to "subtitle",
        "tmcd" to "timecode", "meta" to "metadata", "hint" to "hint", "clcp" to "closed captions",
    )

    private val ITUNES = linkedMapOf(
        "©nam" to "Title", "©ART" to "Artist", "aART" to "Album artist", "©alb" to "Album",
        "©day" to "Year", "©gen" to "Genre", "©wrt" to "Composer", "©too" to "Encoder",
        "©cmt" to "Comment", "desc" to "Description", "©grp" to "Grouping", "cprt" to "Copyright",
    )

    private fun dateFrom1904(s: Long): String? {
        if (s <= 0 || s > 20_000_000_000L) return null
        val unix = s - 2_082_844_800L
        return java.time.Instant.ofEpochSecond(unix).toString()
    }

    private fun parseTrak(b: ByteArray, trak: Box): Track {
        val t = Track()
        for (c in children(b, trak.payload, trak.end)) {
            when (c.type) {
                "tkhd" -> {
                    val v = Bytes.u8(b, c.payload)
                    t.enabled = (Bytes.u8(b, c.payload + 3) and 1) != 0
                    t.id = if (v == 1) Bytes.u32be(b, c.payload + 20) else Bytes.u32be(b, c.payload + 12)
                    val wo = c.payload + (if (v == 1) 88 else 76)
                    if (wo + 8 <= c.end) {
                        t.tw = Bytes.u32be(b, wo) / 65536.0
                        t.th = Bytes.u32be(b, wo + 4) / 65536.0
                    }
                }
                "mdia" -> for (m in children(b, c.payload, c.end)) {
                    when (m.type) {
                        "mdhd" -> {
                            val v = Bytes.u8(b, m.payload)
                            if (v == 1) { t.timescale = Bytes.u32be(b, m.payload + 20); t.duration = Bytes.u64be(b, m.payload + 24) }
                            else { t.timescale = Bytes.u32be(b, m.payload + 12); t.duration = Bytes.u32be(b, m.payload + 16) }
                            val lo = m.payload + (if (v == 1) 32 else 20)
                            val l = Bytes.u16be(b, lo)
                            if (l >= 0x400 && l != 0x7FFF) {
                                val cs = charArrayOf(((l shr 10) and 31).plus(0x60).toChar(), ((l shr 5) and 31).plus(0x60).toChar(), (l and 31).plus(0x60).toChar())
                                t.lang = String(cs)
                            }
                        }
                        "hdlr" -> {
                            t.type = Bytes.ascii(b, m.payload + 8, 4)
                            if (m.end - (m.payload + 24) in 1..300) t.handlerName = U.clean(U.utf8(b, m.payload + 24, m.end - m.payload - 24).let { s -> if (s.isNotEmpty() && s[0].code == s.length - 1) s.substring(1) else s }, 100)
                        }
                        "minf" -> for (s in children(b, m.payload, m.end)) if (s.type == "stbl")
                            for (e in children(b, s.payload, s.end)) if (e.type == "stsd") parseStsd(b, e, t)
                    }
                }
            }
        }
        return t
    }

    private fun parseStsd(b: ByteArray, stsd: Box, t: Track) {
        val count = Bytes.u32be(b, stsd.payload + 4)
        if (count < 1) return
        val e = stsd.payload + 8
        if (e + 16 > stsd.end) return
        t.codec = Bytes.ascii(b, e + 4, 4)
        when (t.type) {
            "vide" -> if (e + 36 <= stsd.end) { t.sw = Bytes.u16be(b, e + 32); t.sh = Bytes.u16be(b, e + 34) }
            "soun" -> if (e + 36 <= stsd.end) {
                t.channels = Bytes.u16be(b, e + 24); t.bits = Bytes.u16be(b, e + 26); t.rate = Bytes.u32be(b, e + 32) ushr 16
            }
        }
    }

    private fun findIlst(b: ByteArray, meta: Box): Box? {
        val a = children(b, meta.payload + 4, meta.end)
        val cands = if (a.any { it.type == "hdlr" || it.type == "ilst" }) a else children(b, meta.payload, meta.end)
        return cands.firstOrNull { it.type == "ilst" }
    }

    private fun parseIlst(b: ByteArray, ilst: Box, facts: MutableList<Fact>, out: MutableMap<String, String>) {
        for (item in children(b, ilst.payload, ilst.end).take(100)) {
            val data = children(b, item.payload, item.end).firstOrNull { it.type == "data" } ?: continue
            val kind = Bytes.u32be(b, data.payload) and 0xFFFFFF
            val vs = data.payload + 8
            val ve = data.end
            if (vs > ve) continue
            val label = ITUNES[item.type]
            if (label != null && kind == 1L) {
                val v = U.clean(U.utf8(b, vs, minOf(ve - vs, 4000)))
                if (v.isNotEmpty()) { facts.add(Fact(label, v, "Tags")); out[label] = v }
            } else if (item.type == "trkn" || item.type == "disk") {
                if (ve - vs >= 6) {
                    val n = Bytes.u16be(b, vs + 2); val tot = Bytes.u16be(b, vs + 4)
                    val l = if (item.type == "trkn") "Track" else "Disc"
                    facts.add(Fact(l, if (tot > 0) "$n of $tot" else "$n", "Tags"))
                }
            } else if (item.type == "tmpo" && ve - vs >= 2) {
                facts.add(Fact("Tempo", "${Bytes.u16be(b, vs)} BPM", "Tags"))
            } else if (item.type == "covr") {
                val m = when (kind) { 13L -> "JPEG"; 14L -> "PNG"; 27L -> "BMP"; else -> "image" }
                facts.add(Fact("Cover art", "$m, ${ve - vs} bytes", "Tags"))
            } else if (item.type == "cpil" && ve - vs >= 1) {
                facts.add(Fact("Compilation", if (Bytes.u8(b, vs) != 0) "yes" else "no", "Tags"))
            }
        }
    }

    private fun run(src: ByteSource, fileName: String?): InspectionResult? {
        val size = src.size
        val facts = ArrayList<Fact>()
        val warnings = ArrayList<String>()
        var pos = 0L
        var n = 0
        var major = ""
        var moovBuf: ByteArray? = null
        var moovAt = -1
        var mdatAt = -1
        val order = ArrayList<String>()
        var fragmented = false
        var first = true
        while (n++ < 500 && pos + 8 <= size) {
            val h = U.readRange(src, pos, 16)
            if (h.size < 8) break
            var bs = Bytes.u32be(h, 0)
            val type = Bytes.ascii(h, 4, 4)
            var hdr = 8L
            if (first) {
                if (type !in setOf("ftyp", "moov", "mdat", "wide", "free", "skip", "pnot", "junk")) return null
                first = false
            }
            if (bs == 1L) { bs = Bytes.u64be(h, 8); hdr = 16 } else if (bs == 0L) bs = size - pos
            if (bs < hdr) { warnings.add("Invalid box size for '$type' at offset $pos"); break }
            val boxSize = if (bs < 0 || bs > size - pos) { if (type != "mdat") warnings.add("Box '$type' extends past the end of the file"); size - pos } else bs
            order.add(type)
            when (type) {
                "ftyp" -> {
                    val f = U.readRange(src, pos + hdr, minOf(boxSize - hdr, 4096L))
                    if (f.size >= 8) {
                        major = Bytes.ascii(f, 0, 4)
                        facts.add(Fact("Major brand", major.trim(), "Container"))
                        facts.add(Fact("Minor version", Bytes.u32be(f, 4).toString(), "Container"))
                        val brands = ArrayList<String>()
                        var q = 8
                        while (q + 4 <= f.size && brands.size < 50) { brands.add(Bytes.ascii(f, q, 4).trim()); q += 4 }
                        if (brands.isNotEmpty()) facts.add(Fact("Compatible brands", brands.joinToString(", "), "Container"))
                    }
                }
                "moov" -> if (moovBuf == null) {
                    val buf = U.readRange(src, pos + hdr, minOf(boxSize - hdr, 6L * 1024 * 1024))
                    if (boxSize - hdr > buf.size) warnings.add("moov box is large; only its first ${buf.size} bytes were parsed")
                    moovBuf = buf; moovAt = order.size - 1
                }
                "moof" -> fragmented = true
                "mdat" -> if (mdatAt < 0) mdatAt = order.size - 1
            }
            pos += boxSize
        }
        if (first) return null
        facts.add(Fact("Top-level boxes", order.take(20).joinToString(", ") + if (order.size > 20) ", ..." else "", "Container"))
        val b = moovBuf
        var summary: String? = null
        var title: String? = null
        if (b == null) {
            warnings.add("No moov box found")
        } else {
            if (mdatAt >= 0 && moovAt >= 0) facts.add(Fact("Fast start", if (moovAt < mdatAt) "yes (moov before mdat)" else "no (moov after mdat)", "Container"))
            var movieSeconds = -1.0
            val tracks = ArrayList<Track>()
            val tags = LinkedHashMap<String, String>()
            val tagFacts = ArrayList<Fact>()
            for (c in children(b, 0, b.size).take(200)) {
                when (c.type) {
                    "mvhd" -> {
                        val v = Bytes.u8(b, c.payload)
                        val ctime: Long; val ts: Long; val dur: Long
                        if (v == 1) { ctime = Bytes.u64be(b, c.payload + 4); ts = Bytes.u32be(b, c.payload + 20); dur = Bytes.u64be(b, c.payload + 24) }
                        else { ctime = Bytes.u32be(b, c.payload + 4); ts = Bytes.u32be(b, c.payload + 12); dur = Bytes.u32be(b, c.payload + 16) }
                        facts.add(Fact("Timescale", ts.toString(), "Movie"))
                        if (ts > 0 && dur >= 0 && dur != -1L) {
                            movieSeconds = dur.toDouble() / ts
                            facts.add(Fact("Duration", U.duration(movieSeconds), "Movie"))
                        }
                        dateFrom1904(ctime)?.let { facts.add(Fact("Created", it, "Movie")) }
                    }
                    "mvex" -> fragmented = true
                    "trak" -> if (tracks.size < 64) tracks.add(parseTrak(b, c))
                    "udta" -> for (m in children(b, c.payload, c.end)) if (m.type == "meta") findIlst(b, m)?.let { parseIlst(b, it, tagFacts, tags) }
                    "meta" -> findIlst(b, c)?.let { parseIlst(b, it, tagFacts, tags) }
                }
            }
            if (fragmented) facts.add(Fact("Fragmented", "yes (movie fragments)", "Container"))
            facts.add(Fact("Track count", tracks.size.toString(), "Movie"))
            var idx = 0
            for (t in tracks) {
                idx++
                val kind = HANDLERS[t.type] ?: (if (t.type.isEmpty()) "unknown" else t.type.trim())
                val g = "Track $idx ($kind)"
                if (t.id != 0L) facts.add(Fact("Track ID", t.id.toString(), g))
                facts.add(Fact("Handler", t.type.ifEmpty { "unknown" } + (if (t.handlerName.isNotEmpty()) " (${t.handlerName})" else ""), g))
                if (t.codec.isNotEmpty()) facts.add(Fact("Codec", (CODECS[t.codec]?.let { "$it [${t.codec.trim()}]" } ?: t.codec.trim()), g))
                if (t.type == "vide") {
                    if (t.tw > 0 && t.th > 0) facts.add(Fact("Display size", "${t.tw.toInt()}x${t.th.toInt()}", g))
                    if (t.sw > 0 && t.sh > 0) facts.add(Fact("Coded size", "${t.sw}x${t.sh}", g))
                }
                if (t.type == "soun") {
                    if (t.channels > 0) facts.add(Fact("Channels", t.channels.toString(), g))
                    if (t.rate > 0) facts.add(Fact("Sample rate", "${t.rate} Hz", g))
                    if (t.bits > 0) facts.add(Fact("Sample size", "${t.bits} bits", g))
                }
                if (t.timescale > 0 && t.duration > 0) facts.add(Fact("Duration", U.duration(t.duration.toDouble() / t.timescale), g))
                if (t.lang.isNotEmpty() && t.lang != "und") facts.add(Fact("Language", t.lang, g))
                if (!t.enabled) facts.add(Fact("Enabled", "no", g))
            }
            facts.addAll(tagFacts)
            title = tags["Title"]
            val vt = tracks.firstOrNull { it.type == "vide" }
            val at = tracks.firstOrNull { it.type == "soun" }
            val parts = ArrayList<String>()
            if (vt != null) parts.add(((CODECS[vt.codec] ?: vt.codec.trim()).ifEmpty { "video" }) + if (vt.tw > 0) " ${vt.tw.toInt()}x${vt.th.toInt()}" else "")
            if (at != null) parts.add((CODECS[at.codec] ?: at.codec.trim()).ifEmpty { "audio" })
            if (movieSeconds >= 0) parts.add(U.duration(movieSeconds))
            if (parts.isNotEmpty()) summary = parts.joinToString(", ")
        }
        val ids = formatIds
        val ext = fileName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) ?: ""
        val fid = if (ext in ids) ext else when (major.trim()) {
            "M4A", "M4B" -> "m4a"; "qt" -> "mov"; else -> "mp4"
        }
        return InspectionResult(fid, title ?: when (fid) { "m4a", "m4b" -> "MPEG-4 audio"; "mov" -> "QuickTime movie"; else -> "MPEG-4 media" }, summary, facts, warnings)
    }
}
