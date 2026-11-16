package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*
import java.util.Locale

/** Matroska / WebM (EBML): doctype, segment info, tracks. */
object MediaInspectorsMkv : FormatInspector {
    override val formatIds: Set<String> = setOf("mkv", "webm", "mka")
    private val U = MediaInspectorsUtil

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? =
        try { run(source, fileName) } catch (t: Throwable) { null }

    private const val ID_EBML = 0x1A45DFA3L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_INFO = 0x1549A966L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_CLUSTER = 0x1F43B675L

    private class El(val id: Long, val size: Long, val hdr: Int) // size -1 = unknown

    private fun element(b: ByteArray, p: Int): El? {
        if (p < 0 || p >= b.size) return null
        val first = Bytes.u8(b, p)
        if (first == 0) return null
        val idLen = Integer.numberOfLeadingZeros(first) - 24 + 1
        if (idLen > 4 || p + idLen > b.size) return null
        var id = 0L
        for (i in 0 until idLen) id = (id shl 8) or Bytes.u8(b, p + i).toLong()
        val sp = p + idLen
        if (sp >= b.size) return null
        val f = Bytes.u8(b, sp)
        if (f == 0) return null
        val sLen = Integer.numberOfLeadingZeros(f) - 24 + 1
        if (sp + sLen > b.size) return null
        var v = (f and (0xFF shr sLen)).toLong()
        var allOnes = v == (0xFF shr sLen).toLong()
        for (i in 1 until sLen) {
            val x = Bytes.u8(b, sp + i)
            v = (v shl 8) or x.toLong()
            if (x != 0xFF) allOnes = false
        }
        return El(id, if (allOnes) -1L else v, idLen + sLen)
    }

    private fun uint(b: ByteArray, s: Int, len: Int): Long {
        var v = 0L
        for (i in 0 until minOf(len, 8)) v = (v shl 8) or Bytes.u8(b, s + i).toLong()
        return v
    }

    private fun flt(b: ByteArray, s: Int, len: Int): Double? = when (len) {
        4 -> java.lang.Float.intBitsToFloat(uint(b, s, 4).toInt()).toDouble()
        8 -> java.lang.Double.longBitsToDouble(uint(b, s, 8))
        else -> null
    }

    private fun text(b: ByteArray, s: Int, len: Int) = U.clean(U.utf8(b, s, len), 500)

    private fun kids(b: ByteArray, from: Int, to: Int, cb: (El, Int, Int) -> Unit) {
        var p = from
        var n = 0
        val limit = minOf(to, b.size)
        while (p < limit && n++ < 5000) {
            val e = element(b, p) ?: break
            val bs = p + e.hdr
            if (e.size < 0 || e.size > limit - bs) { cb(e, bs, limit); break }
            cb(e, bs, bs + e.size.toInt())
            p = bs + e.size.toInt()
        }
    }

    private val TRACK_TYPES = mapOf(1L to "video", 2L to "audio", 3L to "complex", 0x10L to "logo", 0x11L to "subtitle", 0x12L to "buttons", 0x20L to "control")

    private class Trk {
        var num = 0L; var type = 0L; var codec = ""; var name = ""; var lang = ""
        var w = 0L; var h = 0L; var dw = 0L; var dh = 0L; var defDur = 0L
        var freq = 0.0; var ch = 0L; var depth = 0L; var enabled = true
    }

    private fun parseTrack(b: ByteArray, s: Int, e: Int): Trk {
        val t = Trk()
        kids(b, s, e) { el, bs, be ->
            val len = be - bs
            when (el.id) {
                0xD7L -> t.num = uint(b, bs, len)
                0x83L -> t.type = uint(b, bs, len)
                0x86L -> t.codec = text(b, bs, len)
                0x536EL -> t.name = text(b, bs, len)
                0x22B59CL -> t.lang = text(b, bs, len)
                0x23E383L -> t.defDur = uint(b, bs, len)
                0xB9L -> t.enabled = uint(b, bs, len) != 0L
                0xE0L -> kids(b, bs, be) { v, vs, ve ->
                    when (v.id) {
                        0xB0L -> t.w = uint(b, vs, ve - vs); 0xBAL -> t.h = uint(b, vs, ve - vs)
                        0x54B0L -> t.dw = uint(b, vs, ve - vs); 0x54BAL -> t.dh = uint(b, vs, ve - vs)
                    }
                }
                0xE1L -> kids(b, bs, be) { a, vs, ve ->
                    when (a.id) {
                        0xB5L -> t.freq = flt(b, vs, ve - vs) ?: 0.0
                        0x9FL -> t.ch = uint(b, vs, ve - vs)
                        0x6264L -> t.depth = uint(b, vs, ve - vs)
                    }
                }
            }
        }
        return t
    }

    private fun run(src: ByteSource, fileName: String?): InspectionResult? {
        val size = src.size
        val head = U.readRange(src, 0, 64)
        val top = element(head, 0)
        if (top == null || top.id != ID_EBML || top.size < 0 || top.size > 4096) return null
        val facts = ArrayList<Fact>()
        val warnings = ArrayList<String>()
        val eb = U.readRange(src, top.hdr.toLong(), top.size)
        var docType = ""
        kids(eb, 0, eb.size) { el, bs, be ->
            val len = be - bs
            when (el.id) {
                0x4282L -> docType = text(eb, bs, len)
                0x4287L -> facts.add(Fact("DocType version", uint(eb, bs, len).toString(), "Container"))
                0x4285L -> facts.add(Fact("DocType read version", uint(eb, bs, len).toString(), "Container"))
                0x4286L -> facts.add(Fact("EBML version", uint(eb, bs, len).toString(), "Container"))
            }
        }
        facts.add(0, Fact("DocType", docType.ifEmpty { "unknown" }, "Container"))
        var pos = top.hdr + top.size
        var title: String? = null
        var seconds = -1.0
        val tracks = ArrayList<Trk>()
        var haveSegment = false
        var sawInfo = false
        val seg = U.readRange(src, pos, 16)
        val segEl = element(seg, 0)
        if (segEl != null && segEl.id == ID_SEGMENT) {
            haveSegment = true
            var p = pos + segEl.hdr
            val segEnd = if (segEl.size < 0) size else minOf(size, p + segEl.size)
            var n = 0
            while (n++ < 300 && p < segEnd) {
                val eh = element(U.readRange(src, p, 16), 0) ?: break
                val bs = p + eh.hdr
                if (eh.id == ID_CLUSTER) break
                if (eh.size < 0) break
                if (eh.id == ID_INFO || eh.id == ID_TRACKS) {
                    val body = U.readRange(src, bs, minOf(eh.size, 2L * 1024 * 1024))
                    if (eh.size > body.size) warnings.add("Segment element is large; parsed only its first ${body.size} bytes")
                    if (eh.id == ID_INFO) {
                        sawInfo = true
                        var scale = 1_000_000L
                        var dur: Double? = null
                        kids(body, 0, body.size) { el, s, e ->
                            val len = e - s
                            when (el.id) {
                                0x2AD7B1L -> scale = uint(body, s, len)
                                0x4489L -> dur = flt(body, s, len)
                                0x4D80L -> facts.add(Fact("Muxing app", text(body, s, len), "Segment"))
                                0x5741L -> facts.add(Fact("Writing app", text(body, s, len), "Segment"))
                                0x7BA9L -> { title = text(body, s, len); facts.add(Fact("Title", title ?: "", "Segment")) }
                                0x4461L -> if (len == 8) {
                                    val ns = uint(body, s, 8)
                                    val epoch = 978_307_200L + Math.floorDiv(ns, 1_000_000_000L)
                                    if (epoch in 0..4_102_444_800L) facts.add(Fact("Date", java.time.Instant.ofEpochSecond(epoch).toString(), "Segment"))
                                }
                            }
                        }
                        facts.add(Fact("Timecode scale", "$scale ns", "Segment"))
                        dur?.let { d ->
                            if (d >= 0 && scale > 0) {
                                seconds = d * scale / 1e9
                                facts.add(Fact("Duration", U.duration(seconds), "Segment"))
                            }
                        }
                    } else {
                        kids(body, 0, body.size) { el, s, e -> if (el.id == 0xAEL && tracks.size < 64) tracks.add(parseTrack(body, s, e)) }
                    }
                }
                p = bs + eh.size
            }
        } else warnings.add("No Segment element follows the EBML header")
        if (haveSegment && !sawInfo) warnings.add("No Segment Info found before the first cluster; duration unknown")
        facts.add(Fact("Track count", tracks.size.toString(), "Tracks"))
        var i = 0
        for (t in tracks) {
            i++
            val kind = TRACK_TYPES[t.type] ?: "type ${t.type}"
            val g = "Track $i ($kind)"
            if (t.num != 0L) facts.add(Fact("Track number", t.num.toString(), g))
            if (t.codec.isNotEmpty()) facts.add(Fact("Codec", t.codec, g))
            if (t.name.isNotEmpty()) facts.add(Fact("Name", t.name, g))
            if (t.lang.isNotEmpty()) facts.add(Fact("Language", t.lang, g))
            if (t.type == 1L) {
                if (t.w > 0 || t.h > 0) facts.add(Fact("Pixel size", "${t.w}x${t.h}", g))
                if (t.dw > 0 && (t.dw != t.w || t.dh != t.h)) facts.add(Fact("Display size", "${t.dw}x${t.dh}", g))
                if (t.defDur > 0) facts.add(Fact("Frame rate", U.num(1e9 / t.defDur, 3) + " fps (default duration)", g))
            }
            if (t.type == 2L) {
                if (t.freq > 0) facts.add(Fact("Sampling frequency", U.num(t.freq, 0).let { "$it Hz" }, g))
                if (t.ch > 0) facts.add(Fact("Channels", t.ch.toString(), g))
                if (t.depth > 0) facts.add(Fact("Bit depth", t.depth.toString(), g))
            }
            if (!t.enabled) facts.add(Fact("Enabled", "no", g))
        }
        val vt = tracks.firstOrNull { it.type == 1L }
        val parts = ArrayList<String>()
        if (vt != null) parts.add(vt.codec.ifEmpty { "video" } + if (vt.w > 0) " ${vt.w}x${vt.h}" else "")
        tracks.firstOrNull { it.type == 2L }?.let { parts.add(it.codec.ifEmpty { "audio" }) }
        if (seconds >= 0) parts.add(U.duration(seconds))
        val ext = fileName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) ?: ""
        val fid = if (ext in formatIds) ext else if (docType == "webm") "webm" else "mkv"
        val label = if (docType == "webm") "WebM media" else "Matroska media"
        return InspectionResult(fid, title ?: label, parts.joinToString(", ").ifEmpty { null }, facts, warnings)
    }
}
