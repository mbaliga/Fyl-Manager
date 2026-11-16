package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** FLV: header flags, onMetaData (AMF0), first audio/video tag codecs. */
object MediaInspectorsFlv : FormatInspector {
    override val formatIds: Set<String> = setOf("flv")
    private val U = MediaInspectorsUtil

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? =
        try { run(source) } catch (t: Throwable) { null }

    private val VIDEO = mapOf(1 to "JPEG", 2 to "Sorenson H.263", 3 to "Screen video", 4 to "On2 VP6", 5 to "On2 VP6 with alpha", 6 to "Screen video 2", 7 to "H.264 / AVC")
    private val AUDIO = mapOf(
        0 to "PCM (platform endian)", 1 to "ADPCM", 2 to "MP3", 3 to "PCM (little-endian)", 4 to "Nellymoser 16 kHz mono",
        5 to "Nellymoser 8 kHz mono", 6 to "Nellymoser", 7 to "G.711 A-law", 8 to "G.711 mu-law", 10 to "AAC",
        11 to "Speex", 14 to "MP3 8 kHz", 15 to "Device-specific",
    )

    private class Res(val v: Any?, val next: Int)

    private fun amf(b: ByteArray, p: Int, depth: Int): Res? {
        if (depth > 4 || p >= b.size) return null
        when (Bytes.u8(b, p)) {
            0 -> { if (p + 9 > b.size) return null; return Res(java.lang.Double.longBitsToDouble(Bytes.u64be(b, p + 1)), p + 9) }
            1 -> { if (p + 2 > b.size) return null; return Res(Bytes.u8(b, p + 1) != 0, p + 2) }
            2 -> {
                if (p + 3 > b.size) return null
                val l = Bytes.u16be(b, p + 1)
                if (p + 3 + l > b.size) return null
                return Res(U.clean(U.utf8(b, p + 3, l)), p + 3 + l)
            }
            12 -> {
                if (p + 5 > b.size) return null
                val l = Bytes.u32be(b, p + 1)
                if (l > b.size - p - 5) return null
                return Res(U.clean(U.utf8(b, p + 5, l.toInt())), p + 5 + l.toInt())
            }
            5, 6 -> return Res(null, p + 1)
            11 -> { if (p + 11 > b.size) return null; return Res(java.lang.Double.longBitsToDouble(Bytes.u64be(b, p + 1)), p + 11) }
            3, 8 -> {
                var q = p + 1
                if (Bytes.u8(b, p) == 8) q += 4
                val m = LinkedHashMap<String, Any?>()
                var n = 0
                while (n++ < 200) {
                    if (q + 3 > b.size) return Res(m, b.size)
                    val kl = Bytes.u16be(b, q)
                    if (kl == 0) { return Res(m, minOf(b.size, q + 3)) }
                    if (q + 2 + kl > b.size) return Res(m, b.size)
                    val key = U.clean(U.utf8(b, q + 2, kl), 100)
                    val r = amf(b, q + 2 + kl, depth + 1) ?: return Res(m, b.size)
                    m[key] = r.v
                    q = r.next
                }
                return Res(m, q)
            }
            10 -> {
                if (p + 5 > b.size) return null
                val c = minOf(Bytes.u32be(b, p + 1), 200L).toInt()
                var q = p + 5
                val l = ArrayList<Any?>()
                for (i in 0 until c) { val r = amf(b, q, depth + 1) ?: break; l.add(r.v); q = r.next }
                return Res(l, q)
            }
        }
        return null
    }

    private fun fmt(v: Any?): String? = when (v) {
        is Double -> if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else U.num(v, 3)
        is Boolean -> if (v) "yes" else "no"
        is String -> v.ifEmpty { null }
        else -> null
    }

    private fun run(src: ByteSource): InspectionResult? {
        val size = src.size
        val h = U.readRange(src, 0, 9)
        if (h.size < 9 || !Bytes.startsWith(h, "FLV") || Bytes.u8(h, 3) == 0) return null
        val facts = ArrayList<Fact>()
        val warnings = ArrayList<String>()
        val flags = Bytes.u8(h, 4)
        facts.add(Fact("Version", Bytes.u8(h, 3).toString(), "Container"))
        facts.add(Fact("Has audio", if ((flags and 4) != 0) "yes" else "no", "Container"))
        facts.add(Fact("Has video", if ((flags and 1) != 0) "yes" else "no", "Container"))
        var pos = Bytes.u32be(h, 5).coerceIn(9L, 4096L) + 4
        var meta: Map<String, Any?>? = null
        var vcodec = -1; var acodec = -1
        var n = 0
        while (n++ < 100 && pos + 11 <= size && (meta == null || vcodec < 0 && (flags and 1) != 0 || acodec < 0 && (flags and 4) != 0)) {
            val t = U.readRange(src, pos, 12)
            if (t.size < 11) break
            val type = Bytes.u8(t, 0)
            val dsz = ((Bytes.u8(t, 1) shl 16) or (Bytes.u8(t, 2) shl 8) or Bytes.u8(t, 3)).toLong()
            val body = pos + 11
            if (type == 18 && meta == null) {
                val d = U.readRange(src, body, minOf(dsz, 1024L * 1024))
                val name = amf(d, 0, 0)
                if (name != null && name.v == "onMetaData") {
                    val m = amf(d, name.next, 0)
                    @Suppress("UNCHECKED_CAST")
                    if (m != null && m.v is Map<*, *>) meta = m.v as Map<String, Any?>
                }
            } else if (type == 9 && vcodec < 0 && dsz >= 1) {
                vcodec = Bytes.u8(U.readRange(src, body, 1), 0) and 0x0F
            } else if (type == 8 && acodec < 0 && dsz >= 1) {
                acodec = (Bytes.u8(U.readRange(src, body, 1), 0) shr 4) and 0x0F
            }
            val next = body + dsz + 4
            if (next <= pos) break
            pos = next
        }
        val m = meta
        var seconds = -1.0
        if (m != null) {
            val g = "Metadata"
            val order = listOf(
                "duration" to "Duration (s)", "width" to "Width", "height" to "Height", "framerate" to "Frame rate",
                "videocodecid" to "Video codec id", "videodatarate" to "Video data rate (kbps)", "audiocodecid" to "Audio codec id",
                "audiodatarate" to "Audio data rate (kbps)", "audiosamplerate" to "Audio sample rate", "audiosamplesize" to "Audio sample size",
                "audiochannels" to "Audio channels", "stereo" to "Stereo", "filesize" to "File size (bytes)", "encoder" to "Encoder",
                "title" to "Title", "creationdate" to "Creation date",
            )
            for ((k, label) in order) fmt(m[k])?.let { facts.add(Fact(label, it, g)) }
            (m["duration"] as? Double)?.let { if (it >= 0 && it < 1e9) { seconds = it; facts.add(0, Fact("Duration", U.duration(it), g)) } }
            facts.add(Fact("Metadata keys", m.keys.take(40).joinToString(", "), g))
        } else warnings.add("No onMetaData script tag found in the first tags")
        if (vcodec >= 0) facts.add(Fact("Video codec (first tag)", VIDEO[vcodec] ?: "codec id $vcodec", "Streams"))
        if (acodec >= 0) facts.add(Fact("Audio codec (first tag)", AUDIO[acodec] ?: "codec id $acodec", "Streams"))
        if (seconds < 0 && size > 20) {
            val last = U.readRange(src, size - 4, 4)
            val ts = Bytes.u32be(last, 0)
            if (ts in 11..(size - 13)) {
                val th = U.readRange(src, size - 4 - ts, 11)
                val validTag = th.size == 11 && Bytes.u8(th, 0) in intArrayOf(8, 9, 18) &&
                    ((Bytes.u8(th, 1) shl 16) or (Bytes.u8(th, 2) shl 8) or Bytes.u8(th, 3)).toLong() == ts - 11
                if (validTag) {
                    val ms = (Bytes.u8(th, 4) shl 16) or (Bytes.u8(th, 5) shl 8) or Bytes.u8(th, 6) or (Bytes.u8(th, 7) shl 24)
                    if (ms >= 0) { seconds = ms / 1000.0; facts.add(0, Fact("Duration (estimate)", U.duration(seconds), "Streams")) }
                }
            }
        }
        val parts = ArrayList<String>()
        if (m != null) {
            val w = fmt(m["width"]); val hh = fmt(m["height"])
            if (w != null && hh != null) parts.add("${w}x$hh")
        }
        if (vcodec >= 0) parts.add(VIDEO[vcodec] ?: "video")
        if (acodec >= 0) parts.add(AUDIO[acodec] ?: "audio")
        if (seconds >= 0) parts.add(U.duration(seconds))
        return InspectionResult("flv", (m?.get("title") as? String)?.ifEmpty { null } ?: "FLV video", parts.joinToString(", ").ifEmpty { null }, facts, warnings)
    }
}
