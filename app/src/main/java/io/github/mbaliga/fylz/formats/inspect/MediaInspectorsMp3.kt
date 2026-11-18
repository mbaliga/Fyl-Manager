package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*
import java.util.Locale

/** MP3: ID3v2 / ID3v1 tags, first MPEG audio frame, Xing/Info/VBRI header, duration. */
object MediaInspectorsMp3 : FormatInspector {
    override val formatIds: Set<String> = setOf("mp3")
    private val U = MediaInspectorsUtil

    private val GENRES = listOf(
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal",
        "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial",
        "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop",
        "Vocal", "Jazz+Funk", "Fusion", "Trance", "Classical", "Instrumental", "Acid", "House", "Game",
        "Sound Clip", "Gospel", "Noise", "AlternRock", "Bass", "Soul", "Punk", "Space", "Meditative",
        "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic", "Darkwave", "Techno-Industrial",
        "Electronic", "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta",
        "Top 40", "Christian Rap", "Pop/Funk", "Jungle", "Native American", "Cabaret", "New Wave",
        "Psychedelic", "Rave", "Showtunes", "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz",
        "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock",
    )

    private val V22_IDS = mapOf(
        "TT2" to "TIT2", "TP1" to "TPE1", "TAL" to "TALB", "TYE" to "TYER", "TRK" to "TRCK", "TCO" to "TCON",
        "TP2" to "TPE2", "TCM" to "TCOM", "TPA" to "TPOS", "TLE" to "TLEN", "TSS" to "TSSE", "PIC" to "APIC",
    )

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? =
        try { run(source) } catch (t: Throwable) { null }

    private class Frame(val version: Int, val layer: Int, val bitrate: Int, val rate: Int, val pad: Int,
                        val mode: Int, val crc: Boolean, val length: Int, val spf: Int)

    private val BR_V1 = arrayOf(
        intArrayOf(32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448),
        intArrayOf(32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384),
        intArrayOf(32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320),
    )
    private val BR_V2 = arrayOf(
        intArrayOf(32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256),
        intArrayOf(8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160),
        intArrayOf(8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160),
    )

    private fun frameAt(b: ByteArray, i: Int): Frame? {
        if (i < 0 || i + 4 > b.size) return null
        val b1 = Bytes.u8(b, i + 1); val b2 = Bytes.u8(b, i + 2); val b3 = Bytes.u8(b, i + 3)
        if (Bytes.u8(b, i) != 0xFF || (b1 and 0xE0) != 0xE0) return null
        val vbits = (b1 shr 3) and 3
        val lbits = (b1 shr 1) and 3
        if (vbits == 1 || lbits == 0) return null
        val version = when (vbits) { 3 -> 1; 2 -> 2; else -> 25 }
        val layer = 4 - lbits // 1..3
        val bi = (b2 shr 4) and 0xF
        val si = (b2 shr 2) and 3
        if (bi == 0 || bi == 15 || si == 3) return null
        val table = if (version == 1) BR_V1 else BR_V2
        val br = table[layer - 1][bi - 1]
        val rate = when (version) { 1 -> intArrayOf(44100, 48000, 32000)[si]; 2 -> intArrayOf(22050, 24000, 16000)[si]; else -> intArrayOf(11025, 12000, 8000)[si] }
        val pad = (b2 shr 1) and 1
        val spf = if (layer == 1) 384 else if (layer == 3 && version != 1) 576 else 1152
        val len = if (layer == 1) (12 * br * 1000 / rate + pad) * 4 else (spf / 8) * br * 1000 / rate + pad
        return Frame(version, layer, br, rate, pad, (b3 shr 6) and 3, (b1 and 1) == 0, len, spf)
    }

    private fun findFrame(w: ByteArray): Pair<Int, Frame>? {
        var i = 0
        while (i + 4 <= w.size && i < 128 * 1024) {
            val f = frameAt(w, i)
            if (f != null) {
                val next = i + f.length
                if (next + 4 > w.size) { if (next <= w.size) return Pair(i, f) } else {
                val g = frameAt(w, next)
                if (g != null && g.version == f.version && g.layer == f.layer && g.rate == f.rate) return Pair(i, f)
                }
            }
            i++
        }
        return null
    }

    private fun syncsafe(b: ByteArray, o: Int): Int =
        ((Bytes.u8(b, o) and 0x7F) shl 21) or ((Bytes.u8(b, o + 1) and 0x7F) shl 14) or
            ((Bytes.u8(b, o + 2) and 0x7F) shl 7) or (Bytes.u8(b, o + 3) and 0x7F)

    private fun deUnsync(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if (b[i] == 0xFF.toByte() && i + 1 < b.size && b[i + 1] == 0.toByte()) i++
            i++
        }
        return out.toByteArray()
    }

    private fun decodeText(b: ByteArray, s: Int, e: Int): String {
        if (e <= s) return ""
        val enc = Bytes.u8(b, s)
        val cs = when (enc) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        val raw = U.str(b, s + 1, e - s - 1, cs)
        return raw.split('\u0000').map { U.clean(it) }.filter { it.isNotEmpty() }.joinToString(" / ")
    }

    private fun genreName(v: String): String {
        val m = Regex("^\\((\\d+)\\)(.*)$").find(v)
        val idx = m?.groupValues?.get(1) ?: if (v.all { it.isDigit() } && v.isNotEmpty()) v else null
        if (idx != null) {
            val rest = m?.groupValues?.get(2) ?: ""
            if (rest.isNotEmpty()) return rest
            val n = idx.toIntOrNull()
            return if (n != null && n in GENRES.indices) GENRES[n] else v
        }
        return v
    }

    private fun parseId3(body: ByteArray, ver: Int, flags: Int, out: MutableMap<String, String>, warn: MutableList<String>) {
        val b = if (ver < 4 && (flags and 0x80) != 0) deUnsync(body) else body
        var p = 0
        if ((flags and 0x40) != 0 && ver >= 3) {
            val ext = if (ver == 4) syncsafe(b, 0) else (Bytes.u32be(b, 0) + 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (ext in 4..b.size) p = ext else return
        }
        val idLen = if (ver == 2) 3 else 4
        val hdr = if (ver == 2) 6 else 10
        var n = 0
        while (p + hdr <= b.size && n++ < 2000) {
            if (b[p] == 0.toByte()) break
            val rawId = Bytes.ascii(b, p, idLen)
            if (!rawId.all { it in 'A'..'Z' || it in '0'..'9' }) break
            val size: Long = when (ver) {
                2 -> ((Bytes.u8(b, p + 3) shl 16) or (Bytes.u8(b, p + 4) shl 8) or Bytes.u8(b, p + 5)).toLong()
                3 -> Bytes.u32be(b, p + 4)
                else -> syncsafe(b, p + 4).toLong()
            }
            val fl0 = if (ver >= 3) Bytes.u8(b, p + 8) else 0
            val fl1 = if (ver >= 3) Bytes.u8(b, p + 9) else 0
            val ds = p + hdr
            if (size < 0 || size > b.size - ds) { warn.add("ID3v2 frame $rawId extends beyond the tag"); break }
            var s = ds
            var e = ds + size.toInt()
            var skip = false
            if (ver == 3) { if ((fl0 and 0xC0) != 0) skip = true; if ((fl0 and 0x20) != 0) s += 1 }
            if (ver == 4) {
                if ((fl1 and 0x0C) != 0) skip = true
                if ((fl1 and 0x40) != 0) s += 1
                if ((fl1 and 0x01) != 0) s += 4
            }
            val id = if (ver == 2) (V22_IDS[rawId] ?: rawId) else rawId
            if (!skip && s < e) {
                var data = b
                if (ver == 4 && (fl1 and 0x02) != 0) { data = deUnsync(b.copyOfRange(s, e)); e = data.size; s = 0 }
                if (id == "APIC") {
                    if (!out.containsKey("APIC")) {
                        val enc = Bytes.u8(data, s)
                        val mime = if (ver == 2) "image/" + U.latin1(data, s + 1, 3).lowercase(Locale.ROOT).replace("jpg", "jpeg")
                        else { var q = s + 1; while (q < e && data[q] != 0.toByte()) q++; U.latin1(data, s + 1, minOf(q, e) - s - 1) }
                        out["APIC"] = U.clean(mime, 100).ifEmpty { "image (enc $enc)" }
                    }
                } else if (id[0] == 'T' && id != "TXXX" && !out.containsKey(id)) {
                    val t = decodeText(data, s, e)
                    if (t.isNotEmpty()) out[id] = t
                }
            }
            p = ds + size.toInt()
        }
    }

    private fun run(src: ByteSource): InspectionResult? {
        val size = src.size
        if (size < 4) return null
        val warnings = ArrayList<String>()
        val facts = ArrayList<Fact>()
        var audioStart = 0L
        val v2 = LinkedHashMap<String, String>()
        var v2Version = 0
        val h = U.readRange(src, 0, 10)
        if (h.size == 10 && Bytes.startsWith(h, "ID3") && h[3].toInt() in 2..4 &&
            (6..9).all { Bytes.u8(h, it) < 0x80 }) {
            v2Version = h[3].toInt()
            val flags = Bytes.u8(h, 5)
            val tagSize = syncsafe(h, 6).toLong()
            val footer = v2Version == 4 && (flags and 0x10) != 0
            audioStart = 10 + tagSize + (if (footer) 10 else 0)
            val body = U.readRange(src, 10, minOf(tagSize, 4L * 1024 * 1024))
            parseId3(body, v2Version, flags, v2, warnings)
            if (audioStart > size) warnings.add("ID3v2 tag size runs past the end of the file")
        }
        // ID3v1
        var v1: Map<String, String>? = null
        var v1Present = false
        if (size >= 128) {
            val t = U.readRange(src, size - 128, 128)
            if (t.size == 128 && Bytes.startsWith(t, "TAG")) {
                v1Present = true
                val m = LinkedHashMap<String, String>()
                fun f(k: String, o: Int, l: Int) { val v = U.clean(U.latin1(t, o, l)); if (v.isNotEmpty()) m[k] = v }
                f("TIT2", 3, 30); f("TPE1", 33, 30); f("TALB", 63, 30); f("TYER", 93, 4)
                if (t[125] == 0.toByte() && t[126] != 0.toByte()) m["TRCK"] = Bytes.u8(t, 126).toString()
                val g = Bytes.u8(t, 127)
                if (g < GENRES.size) m["TCON"] = GENRES[g]
                v1 = m
            }
        }
        // First frame
        val window = if (audioStart < size) U.readRange(src, audioStart, 256L * 1024) else ByteArray(0)
        val found = findFrame(window)

        if (found == null && v2Version == 0 && v1 == null) return null

        val tags: Map<String, String> = if (v2Version != 0) v2 else (v1 ?: emptyMap())
        val tagGroup = "Tags"
        fun tag(label: String, key: String) { tags[key]?.let { facts.add(Fact(label, if (key == "TCON") genreName(it) else it, tagGroup)) } }
        tag("Title", "TIT2"); tag("Artist", "TPE1"); tag("Album", "TALB"); tag("Album artist", "TPE2")
        val year = tags["TYER"] ?: tags["TDRC"]?.take(4)?.takeIf { y -> y.length == 4 && y.all { it.isDigit() } }
        if (year != null) facts.add(Fact("Year", year, tagGroup))
        tags["TDRC"]?.takeIf { it.length > 4 }?.let { facts.add(Fact("Recording date", it, tagGroup)) }
        tag("Track", "TRCK"); tag("Disc", "TPOS"); tag("Genre", "TCON"); tag("Composer", "TCOM")
        tags["TLEN"]?.toLongOrNull()?.let { facts.add(Fact("Tagged length", U.duration(it / 1000.0), tagGroup)) }
        tag("Tag encoder", "TSSE")
        tags["APIC"]?.let { facts.add(Fact("Cover art", it, tagGroup)) }
        if (v2Version != 0) facts.add(Fact("Tag format", "ID3v2.$v2Version", "Container"))
        if (v1Present) facts.add(Fact("ID3v1 tag", if (v2Version != 0) "present (ignored, ID3v2 used)" else "present", "Container"))

        var summary: String? = null
        if (found != null) {
            val (pos, fr) = found
            val verName = when (fr.version) { 1 -> "MPEG-1"; 2 -> "MPEG-2"; else -> "MPEG-2.5" }
            val layerName = "Layer " + "I".repeat(if (fr.layer == 3) 0 else fr.layer).ifEmpty { "III" }
            val mode = when (fr.mode) { 0 -> "Stereo"; 1 -> "Joint stereo"; 2 -> "Dual channel"; else -> "Mono" }
            val g = "Audio"
            facts.add(Fact("Codec", "$verName $layerName", g))
            facts.add(Fact("Bitrate (first frame)", "${fr.bitrate} kbps", g))
            facts.add(Fact("Sample rate", "${fr.rate} Hz", g))
            facts.add(Fact("Channel mode", mode, g))
            facts.add(Fact("Channels", if (fr.mode == 3) "1" else "2", g))
            facts.add(Fact("CRC protected", if (fr.crc) "yes" else "no", g))
            // Xing / Info / VBRI
            val side = if (fr.version == 1) (if (fr.mode == 3) 17 else 32) else (if (fr.mode == 3) 9 else 17)
            var vbrKind: String? = null
            var xFrames = -1L
            var encoder: String? = null
            for (extra in intArrayOf(0, 2)) {
                val xo = pos + 4 + side + extra
                val tagId = Bytes.ascii(window, xo, 4)
                if (tagId == "Xing" || tagId == "Info") {
                    vbrKind = tagId
                    val fl = Bytes.u32be(window, xo + 4)
                    var q = xo + 8
                    if ((fl and 1L) != 0L) { xFrames = Bytes.u32be(window, q); q += 4 }
                    if ((fl and 2L) != 0L) q += 4
                    if ((fl and 4L) != 0L) q += 100
                    if ((fl and 8L) != 0L) q += 4
                    val enc = U.ascii9(window, q)
                    if (enc != null) encoder = enc
                    break
                }
            }
            if (vbrKind == null && Bytes.ascii(window, pos + 36, 4) == "VBRI") {
                vbrKind = "VBRI"
                xFrames = Bytes.u32be(window, pos + 50)
            }
            when (vbrKind) {
                "Info" -> facts.add(Fact("Bitrate mode", "CBR (Info header)", g))
                "Xing", "VBRI" -> facts.add(Fact("Bitrate mode", "VBR ($vbrKind header)", g))
            }
            encoder?.let { facts.add(Fact("Encoder", it, g)) }
            var seconds = -1.0
            if (xFrames > 0) {
                seconds = xFrames.toDouble() * fr.spf / fr.rate
                facts.add(Fact("Frames", xFrames.toString(), g))
                facts.add(Fact("Duration", U.duration(seconds), g))
            } else if (vbrKind == null) {
                val bytes = size - (audioStart + pos) - (if (v1Present) 128 else 0)
                if (bytes > 0) {
                    seconds = bytes * 8.0 / (fr.bitrate * 1000.0)
                    facts.add(Fact("Duration (estimate)", U.duration(seconds), g))
                    facts.add(Fact("Estimate basis", "constant bitrate assumed", g))
                }
            }
            if (audioStart + pos > 0) facts.add(Fact("Audio starts at", "${audioStart + pos} bytes", "Container"))
            summary = "$verName $layerName, ${fr.bitrate} kbps, ${fr.rate} Hz, $mode" +
                (if (seconds >= 0) ", " + U.duration(seconds) else "")
        } else {
            warnings.add("No valid MPEG audio frame found after the tags")
        }
        val title = tags["TIT2"]?.let { t -> tags["TPE1"]?.let { "$it - $t" } ?: t } ?: "MP3 audio"
        return InspectionResult("mp3", title, summary, facts, warnings)
    }
}
