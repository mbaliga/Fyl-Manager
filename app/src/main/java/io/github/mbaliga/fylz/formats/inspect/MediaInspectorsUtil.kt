package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*
import java.nio.charset.Charset
import java.util.Locale

/** Shared, total helpers for the media inspectors. Nothing here throws on hostile input. */
internal object MediaInspectorsUtil {
    private const val CHUNK = 64 * 1024

    /** Reads up to [length] bytes at [offset] in chunks, clamped to the file and to MAX_READ_BYTES. */
    fun readRange(src: ByteSource, offset: Long, length: Long): ByteArray {
        val size = src.size
        if (offset < 0 || length <= 0 || offset >= size) return ByteArray(0)
        val want = minOf(length, size - offset, FormatInspector.MAX_READ_BYTES.toLong()).toInt()
        val out = java.io.ByteArrayOutputStream(minOf(want, CHUNK))
        var pos = offset
        var left = want
        while (left > 0) {
            val part = src.read(pos, minOf(left, CHUNK))
            if (part.isEmpty()) break
            out.write(part)
            pos += part.size
            left -= part.size
        }
        return out.toByteArray()
    }

    fun str(b: ByteArray, o: Int, len: Int, cs: Charset): String {
        if (o < 0 || len <= 0 || o >= b.size) return ""
        val e = minOf(b.size.toLong(), o.toLong() + len).toInt()
        return String(b, o, e - o, cs)
    }

    fun latin1(b: ByteArray, o: Int, len: Int) = str(b, o, len, Charsets.ISO_8859_1)
    fun utf8(b: ByteArray, o: Int, len: Int) = str(b, o, len, Charsets.UTF_8)

    /** Text with trailing NULs/spaces removed and control characters dropped. */
    fun clean(s: String, max: Int = 2000): String {
        val sb = StringBuilder()
        for (c in s) {
            if (sb.length >= max) break
            if (c == '\n' || c == '\t' || c >= ' ' && c != '\u007f') sb.append(c)
        }
        return sb.toString().trimEnd('\u0000', ' ', '\n', '\t').trimStart()
    }

    fun num(d: Double, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", d)

    /** 83.5 -> "1:23.500"; 3725.25 -> "1:02:05.250". */
    fun duration(sec: Double): String {
        if (sec.isNaN() || sec.isInfinite() || sec < 0 || sec > 1e9) return "unknown"
        val totalMs = Math.round(sec * 1000.0)
        val ms = (totalMs % 1000).toInt()
        val totalS = totalMs / 1000
        val s = (totalS % 60).toInt()
        val m = ((totalS / 60) % 60).toInt()
        val h = totalS / 3600
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d.%03d", h, m, s, ms)
        else String.format(Locale.ROOT, "%d:%02d.%03d", totalS / 60, s, ms)
    }

    /** Picks the extension-named id when it is one of [ids], else [default]. */
    fun pickId(fileName: String?, ids: Set<String>, default: String): String {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) ?: ""
        return if (ext in ids) ext else default
    }

    fun wavFormatName(tag: Int): String = when (tag) {
        0x0001 -> "PCM"
        0x0002 -> "Microsoft ADPCM"
        0x0003 -> "IEEE float"
        0x0006 -> "A-law"
        0x0007 -> "mu-law"
        0x0011 -> "IMA ADPCM"
        0x0050 -> "MPEG Layer 1/2"
        0x0055 -> "MP3"
        0x00FF -> "AAC"
        0x0161 -> "Windows Media Audio 2"
        0xFFFE -> "Extensible"
        else -> String.format(Locale.ROOT, "format 0x%04X", tag)
    }

    fun printable(s: String) = s.isNotEmpty() && s.all { it.code in 0x20..0x7E }

    /** Vorbis-comment block (used by FLAC and Ogg). Returns vendor and KEY=VALUE pairs; bounded. */
    fun vorbisComments(b: ByteArray, start: Int, end: Int): Pair<String, List<Pair<String, String>>>? {
        val limit = minOf(end, b.size)
        var p = start
        if (p < 0 || p + 4 > limit) return null
        val vlen = Bytes.u32le(b, p); p += 4
        if (vlen > limit - p) return null
        val vendor = clean(utf8(b, p, vlen.toInt()))
        p += vlen.toInt()
        if (p + 4 > limit) return Pair(vendor, emptyList())
        val count = Bytes.u32le(b, p); p += 4
        val out = ArrayList<Pair<String, String>>()
        var n = 0L
        while (n < count && n < 2000 && p + 4 <= limit) {
            val l = Bytes.u32le(b, p); p += 4
            if (l > limit - p) break
            val s = utf8(b, p, l.toInt())
            p += l.toInt()
            n++
            val eq = s.indexOf('=')
            if (eq > 0) out.add(Pair(clean(s.substring(0, eq), 64).uppercase(Locale.ROOT), clean(s.substring(eq + 1), 1000)))
        }
        return Pair(vendor, out)
    }

    val VORBIS_LABELS = linkedMapOf(
        "TITLE" to "Title", "ARTIST" to "Artist", "ALBUM" to "Album", "ALBUMARTIST" to "Album artist",
        "DATE" to "Date", "TRACKNUMBER" to "Track", "DISCNUMBER" to "Disc", "GENRE" to "Genre",
        "COMPOSER" to "Composer", "COMMENT" to "Comment", "DESCRIPTION" to "Description",
        "PERFORMER" to "Performer", "ORGANIZATION" to "Organization", "ENCODER" to "Encoder",
    )

    fun addVorbisFacts(f: MutableList<Fact>, vendor: String, tags: List<Pair<String, String>>, group: String) {
        if (vendor.isNotEmpty()) f.add(Fact("Vendor", vendor, group))
        for ((k, label) in VORBIS_LABELS) {
            val vals = tags.filter { it.first == k }.map { it.second }.filter { it.isNotEmpty() }
            if (vals.isNotEmpty()) f.add(Fact(label, vals.joinToString(" / "), group))
        }
        val known = VORBIS_LABELS.keys
        val others = tags.map { it.first }.filter { it !in known }.distinct()
        if (others.isNotEmpty()) f.add(Fact("Other tags", others.take(30).joinToString(", "), group))
        f.add(Fact("Comment count", tags.size.toString(), group))
    }
}

/** 9 printable ASCII characters at [o] starting with a letter (a LAME-style encoder tag), else null. */
internal fun MediaInspectorsUtil.ascii9(b: ByteArray, o: Int): String? {
    if (o < 0 || o + 9 > b.size) return null
    val s = String(b, o, 9, Charsets.ISO_8859_1)
    return if (s[0].isLetter() && printable(s)) s.trim() else null
}
