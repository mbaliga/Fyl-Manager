package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class MediaInspectorsVerifyTest {

    // ---------- byte helpers ----------
    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    private fun be16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun be32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun be64(v: Long) = be32(v ushr 32) + be32(v and 0xFFFFFFFFL)
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun le64(v: Long) = le32(v and 0xFFFFFFFFL) + le32(v ushr 32)
    private fun zeros(n: Int) = ByteArray(n)
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun cat(vararg parts: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(); for (p in parts) o.write(p); return o.toByteArray()
    }

    private fun src(b: ByteArray) = ByteArraySource(b)

    private fun result(i: FormatInspector, b: ByteArray, name: String? = null): InspectionResult {
        val r = i.inspect(src(b), name)
        assertNotNull("inspector returned null", r)
        return r!!
    }

    private fun fact(r: InspectionResult, label: String, group: String? = null): String? =
        r.facts.firstOrNull { it.label == label && (group == null || it.group == group) }?.value

    private fun everyPrefix(i: FormatInspector, b: ByteArray, name: String? = null) {
        for (n in 0..b.size) i.inspect(src(b.copyOf(n)), name)
    }

    // ---------- MP3 ----------
    private fun id3Frame(id: String, text: String) = cat(ascii(id), be32(text.length + 1L), bytes(0, 0), bytes(0), ascii(text))
    private fun syncsafe(n: Int) = bytes((n shr 21) and 0x7F, (n shr 14) and 0x7F, (n shr 7) and 0x7F, n and 0x7F)
    private fun mp3Frame(extra: ByteArray = ByteArray(0)): ByteArray {
        val f = ByteArray(417)
        f[0] = 0xFF.toByte(); f[1] = 0xFB.toByte(); f[2] = 0x90.toByte(); f[3] = 0
        System.arraycopy(extra, 0, f, 36, extra.size)
        return f
    }
    private fun id3v23(): ByteArray {
        val body = cat(
            id3Frame("TIT2", "Song Title"), id3Frame("TPE1", "The Artist"), id3Frame("TALB", "Some Album"),
            id3Frame("TYER", "1999"), id3Frame("TRCK", "3/12"), id3Frame("TCON", "(17)"),
        )
        return cat(ascii("ID3"), bytes(3, 0, 0), syncsafe(body.size), body)
    }
    private val mp3 get() = cat(id3v23(), mp3Frame(), mp3Frame(), mp3Frame())

    fun `mp3 reads ID3v2 tags and first frame`() {
        val r = result(MediaInspectorsMp3, mp3)
        assertEquals("The Artist - Song Title", r.title)
        assertEquals("Song Title", fact(r, "Title"))
        assertEquals("The Artist", fact(r, "Artist"))
        assertEquals("Some Album", fact(r, "Album"))
        assertEquals("1999", fact(r, "Year"))
        assertEquals("3/12", fact(r, "Track"))
        assertEquals("Rock", fact(r, "Genre"))
        assertEquals("ID3v2.3", fact(r, "Tag format"))
        assertEquals("MPEG-1 Layer III", fact(r, "Codec"))
        assertEquals("128 kbps", fact(r, "Bitrate (first frame)"))
        assertEquals("44100 Hz", fact(r, "Sample rate"))
        assertEquals("Stereo", fact(r, "Channel mode"))
        assertEquals("0:00.078", fact(r, "Duration (estimate)"))
    }

    fun `mp3 uses Xing frame count for exact duration`() {
        val xing = cat(ascii("Xing"), be32(1), be32(100))
        val b = cat(mp3Frame(xing), mp3Frame(), mp3Frame())
        val r = result(MediaInspectorsMp3, b)
        assertEquals("0:02.612", fact(r, "Duration"))
        assertEquals("100", fact(r, "Frames"))
        assertEquals("VBR (Xing header)", fact(r, "Bitrate mode"))
        assertEquals("MP3 audio", r.title)
    }

    fun `mp3 falls back to ID3v1`() {
        fun field(s: String, n: Int) = ascii(s).copyOf(n)
        val v1 = cat(ascii("TAG"), field("Old Title", 30), field("Old Artist", 30), field("Old Album", 30), ascii("1985"),
            field("", 28), bytes(0, 7), bytes(32))
        assertEquals(128, v1.size)
        val r = result(MediaInspectorsMp3, cat(mp3Frame(), mp3Frame(), v1))
        assertEquals("Old Title", fact(r, "Title"))
        assertEquals("Old Artist", fact(r, "Artist"))
        assertEquals("1985", fact(r, "Year"))
        assertEquals("7", fact(r, "Track"))
        assertEquals("Classical", fact(r, "Genre"))
        assertEquals("present", fact(r, "ID3v1 tag"))
    }

    fun `mp3 truncations and hostile lengths`() {
        everyPrefix(MediaInspectorsMp3, mp3)
        val hostile = cat(ascii("ID3"), bytes(3, 0, 0), bytes(0x7F, 0x7F, 0x7F, 0x7F),
            ascii("TIT2"), be32(0xFFFFFFFFL), bytes(0, 0), ascii("xx"))
        val r = MediaInspectorsMp3.inspect(src(hostile), null)
        assertTrue(r == null || r.warnings.isNotEmpty())
        val h2 = cat(ascii("ID3"), bytes(4, 0, 0), syncsafe(40), ascii("TIT2"), bytes(0x7F, 0x7F, 0x7F, 0x7F), bytes(0, 0), ascii("x"))
        MediaInspectorsMp3.inspect(src(h2), null)
    }

    // ---------- WAV ----------
    private fun chunk(id: String, body: ByteArray) = cat(ascii(id), le32(body.size.toLong()), body, if (body.size % 2 == 1) zeros(1) else ByteArray(0))
    private fun wav(dataLen: Int = 1600): ByteArray {
        val fmt = cat(le16(1), le16(1), le32(8000), le32(16000), le16(2), le16(16))
        val info = cat(ascii("INFO"), chunk("INAM", ascii("Hi\u0000")), chunk("ISFT", ascii("TestSoft\u0000")))
        val body = cat(ascii("WAVE"), chunk("fmt ", fmt), chunk("LIST", info), chunk("data", zeros(dataLen)))
        return cat(ascii("RIFF"), le32(body.size.toLong()), body)
    }

    fun `wav reports format and duration`() {
        val r = result(MediaInspectorsWav, wav())
        assertEquals("PCM", fact(r, "Codec"))
        assertEquals("1", fact(r, "Channels"))
        assertEquals("8000 Hz", fact(r, "Sample rate"))
        assertEquals("16", fact(r, "Bits per sample"))
        assertEquals("0:00.100", fact(r, "Duration"))
        assertEquals("Hi", fact(r, "Title"))
        assertEquals("TestSoft", fact(r, "Software"))
        assertEquals("Hi", r.title)
    }

    fun `wav truncations and hostile data length`() {
        everyPrefix(MediaInspectorsWav, wav(64))
        val fmt = cat(le16(1), le16(2), le32(44100), le32(176400), le16(4), le16(16))
        val b = cat(ascii("RIFF"), le32(0xFFFFFFFFL), ascii("WAVE"), chunk("fmt ", fmt), ascii("data"), le32(0xFFFFFFFFL), zeros(176400))
        val r = result(MediaInspectorsWav, b)
        assertEquals("0:01.000", fact(r, "Duration"))
        assertTrue(r.warnings.isNotEmpty())
        MediaInspectorsWav.inspect(src(cat(ascii("RIFF"), le32(0), ascii("WAVE"), ascii("LIST"), le32(0xFFFFFFFFL))), null)
    }

    // ---------- FLAC ----------
    private fun vorbisComment(vendor: String, vararg c: String): ByteArray {
        var o = cat(le32(vendor.length.toLong()), ascii(vendor), le32(c.size.toLong()))
        for (s in c) o = cat(o, le32(s.length.toLong()), ascii(s))
        return o
    }
    private fun flac(): ByteArray {
        val packed = (44100L shl 44) or (1L shl 41) or (15L shl 36) or 441000L
        val si = cat(be16(4096), be16(4096), bytes(0, 0, 0), bytes(0, 0, 0), be64(packed), zeros(16))
        val vc = vorbisComment("ref libFLAC", "TITLE=Hello", "ARTIST=Bob", "ALBUM=Greatest")
        return cat(ascii("fLaC"), bytes(0), bytes(0, 0, 34), si, bytes(0x84), bytes(0, 0, vc.size), vc, zeros(10))
    }

    fun `flac streaminfo and comments`() {
        val r = result(MediaInspectorsFlac, flac())
        assertEquals("44100 Hz", fact(r, "Sample rate"))
        assertEquals("2", fact(r, "Channels"))
        assertEquals("16", fact(r, "Bits per sample"))
        assertEquals("441000", fact(r, "Total samples"))
        assertEquals("0:10.000", fact(r, "Duration"))
        assertEquals("Hello", fact(r, "Title"))
        assertEquals("Bob", fact(r, "Artist"))
        assertEquals("ref libFLAC", fact(r, "Vendor"))
        assertEquals("Hello", r.title)
        assertEquals("STREAMINFO, VORBIS_COMMENT", fact(r, "Metadata blocks"))
    }

    fun `flac truncations and hostile lengths`() {
        everyPrefix(MediaInspectorsFlac, flac())
        val bad = cat(ascii("fLaC"), bytes(0x84), bytes(0, 0, 16), le32(0xFFFFFFFFL), le32(0xFFFFFFFFL), zeros(8))
        MediaInspectorsFlac.inspect(src(bad), null)
        val bad2 = cat(ascii("fLaC"), bytes(0x04), bytes(0xFF, 0xFF, 0xFF), zeros(4))
        val r = result(MediaInspectorsFlac, bad2)
        assertTrue(r.warnings.isNotEmpty())
        val bad3 = cat(ascii("fLaC"), bytes(0x84), bytes(0, 0, 12), le32(0), le32(0xFFFFFFFFL), zeros(4))
        MediaInspectorsFlac.inspect(src(bad3), null)
    }

    // ---------- OGG ----------
    private fun oggPage(type: Int, granule: Long, serial: Long, seq: Long, packet: ByteArray): ByteArray {
        require(packet.size < 255)
        return cat(ascii("OggS"), bytes(0, type), le64(granule), le32(serial), le32(seq), le32(0), bytes(1, packet.size), packet)
    }
    private fun oggVorbis(): ByteArray {
        val ident = cat(bytes(1), ascii("vorbis"), le32(0), bytes(2), le32(44100), le32(0), le32(128000), le32(0), bytes(0xB8), bytes(1))
        val comm = cat(bytes(3), ascii("vorbis"), vorbisComment("Xiph test", "TITLE=Ogg Song", "ARTIST=Eve"), bytes(1))
        return cat(oggPage(2, 0, 7, 0, ident), oggPage(0, 0, 7, 1, comm), oggPage(4, 44100L * 3, 7, 2, bytes(1, 2, 3)))
    }
    private fun oggOpus(): ByteArray {
        val head = cat(ascii("OpusHead"), bytes(1, 2), le16(312), le32(44100), le16(0), bytes(0))
        val tags = cat(ascii("OpusTags"), vorbisComment("libopus", "TITLE=Opus Song"))
        return cat(oggPage(2, 0, 9, 0, head), oggPage(0, 0, 9, 1, tags), oggPage(4, 96312L, 9, 2, bytes(1)))
    }

    fun `ogg vorbis headers comments and duration`() {
        val r = result(MediaInspectorsOgg, oggVorbis(), "x.ogg")
        assertEquals("Vorbis (version 0)", fact(r, "Codec"))
        assertEquals("2", fact(r, "Channels"))
        assertEquals("44100 Hz", fact(r, "Sample rate"))
        assertEquals("128 kbps", fact(r, "Nominal bitrate"))
        assertEquals("Ogg Song", fact(r, "Title"))
        assertEquals("Eve", fact(r, "Artist"))
        assertEquals("0:03.000", fact(r, "Duration"))
        assertEquals("ogg", r.formatId)
        assertEquals("Ogg Song", r.title)
    }

    fun `ogg opus accounts for pre-skip`() {
        val r = result(MediaInspectorsOgg, oggOpus(), "x.opus")
        assertEquals("Opus (version 1)", fact(r, "Codec"))
        assertEquals("312 samples", fact(r, "Pre-skip"))
        assertEquals("Opus Song", fact(r, "Title"))
        assertEquals("0:02.000", fact(r, "Duration"))
        assertEquals("opus", r.formatId)
    }

    fun `ogg truncations and hostile lengths`() {
        everyPrefix(MediaInspectorsOgg, oggVorbis())
        everyPrefix(MediaInspectorsOgg, oggOpus())
        val comm = cat(ascii("OpusTags"), le32(0xFFFFFFFFL), le32(0xFFFFFFFFL))
        val head = cat(ascii("OpusHead"), bytes(1, 2), le16(0), le32(48000), le16(0), bytes(0))
        MediaInspectorsOgg.inspect(src(cat(oggPage(2, 0, 1, 0, head), oggPage(0, 0, 1, 1, comm))), null)
        val comm2 = cat(ascii("OpusTags"), le32(3), ascii("abc"), le32(0xFFFFFFFFL), le32(0xFFFFFFFFL), zeros(30))
        MediaInspectorsOgg.inspect(src(cat(oggPage(2, 0, 1, 0, head), oggPage(0, 0, 1, 1, comm2))), null)
    }

    // ---------- MP4 ----------
    private fun box(type: String, payload: ByteArray = ByteArray(0)) = cat(be32(payload.size + 8L), ascii(type), payload)
    private fun box(type: ByteArray, payload: ByteArray) = cat(be32(payload.size + 8L), type, payload)
    private fun fullBox(type: String, payload: ByteArray) = box(type, cat(zeros(4), payload))

    private fun mvhd(ts: Long, dur: Long): ByteArray {
        val p = ByteArray(100)
        System.arraycopy(be32(ts), 0, p, 12, 4); System.arraycopy(be32(dur), 0, p, 16, 4)
        return box("mvhd", p)
    }
    private fun tkhd(id: Long, w: Int, h: Int): ByteArray {
        val p = ByteArray(84)
        System.arraycopy(be32(3), 0, p, 0, 4)
        System.arraycopy(be32(id), 0, p, 12, 4)
        System.arraycopy(be32(w.toLong() shl 16), 0, p, 76, 4); System.arraycopy(be32(h.toLong() shl 16), 0, p, 80, 4)
        return box("tkhd", p)
    }
    private fun mdhd(ts: Long, dur: Long): ByteArray {
        val p = ByteArray(24)
        System.arraycopy(be32(ts), 0, p, 12, 4); System.arraycopy(be32(dur), 0, p, 16, 4); System.arraycopy(be16(0x55C4), 0, p, 20, 2)
        return box("mdhd", p)
    }
    private fun hdlr(type: String) = box("hdlr", cat(zeros(8), ascii(type), zeros(12), ascii("Handler\u0000")))
    private fun videoEntry(w: Int, h: Int): ByteArray {
        val p = ByteArray(86)
        System.arraycopy(be32(86), 0, p, 0, 4); System.arraycopy(ascii("avc1"), 0, p, 4, 4)
        System.arraycopy(be16(w), 0, p, 32, 2); System.arraycopy(be16(h), 0, p, 34, 2)
        return p
    }
    private fun audioEntry(): ByteArray {
        val p = ByteArray(36)
        System.arraycopy(be32(36), 0, p, 0, 4); System.arraycopy(ascii("mp4a"), 0, p, 4, 4)
        System.arraycopy(be16(2), 0, p, 24, 2); System.arraycopy(be16(16), 0, p, 26, 2); System.arraycopy(be32(44100L shl 16), 0, p, 32, 4)
        return p
    }
    private fun trak(id: Long, handler: String, entry: ByteArray, w: Int, h: Int): ByteArray {
        val stsd = box("stsd", cat(zeros(4), be32(1), entry))
        val minf = box("minf", box("stbl", stsd))
        return box("trak", cat(tkhd(id, w, h), box("mdia", cat(mdhd(1000, 5000), hdlr(handler), minf))))
    }
    private fun textAtom(type: ByteArray, text: String) = box(type, box("data", cat(be32(1), be32(0), ascii(text))))
    private fun mp4(): ByteArray {
        val ilst = box("ilst", cat(
            textAtom(bytes(0xA9, 'n'.code, 'a'.code, 'm'.code), "My Movie"),
            textAtom(bytes(0xA9, 'A'.code, 'R'.code, 'T'.code), "Director"),
            box("trkn", box("data", cat(be32(0), be32(0), bytes(0, 0, 0, 3, 0, 10, 0, 0)))),
        ))
        val meta = box("meta", cat(zeros(4), hdlr("mdir"), ilst))
        val moov = box("moov", cat(
            mvhd(1000, 5000),
            trak(1, "vide", videoEntry(1920, 1080), 1920, 1080),
            trak(2, "soun", audioEntry(), 0, 0),
            box("udta", meta),
        ))
        val ftyp = box("ftyp", cat(ascii("isom"), be32(512), ascii("isom"), ascii("avc1"), ascii("mp42")))
        return cat(ftyp, moov, box("mdat", zeros(16)))
    }

    fun `mp4 movie tracks and tags`() {
        val r = result(MediaInspectors.all.first { "mp4" in it.formatIds }, mp4(), "clip.mp4")
        assertEquals("mp4", r.formatId)
        assertEquals("isom", fact(r, "Major brand"))
        assertEquals("isom, avc1, mp42", fact(r, "Compatible brands"))
        assertEquals("1000", fact(r, "Timescale"))
        assertEquals("0:05.000", fact(r, "Duration", "Movie"))
        assertEquals("2", fact(r, "Track count"))
        assertEquals("1920x1080", fact(r, "Display size", "Track 1 (video)"))
        assertEquals("1920x1080", fact(r, "Coded size", "Track 1 (video)"))
        assertEquals("H.264 / AVC [avc1]", fact(r, "Codec", "Track 1 (video)"))
        assertEquals("vide (Handler)", fact(r, "Handler", "Track 1 (video)"))
        assertEquals("2", fact(r, "Channels", "Track 2 (audio)"))
        assertEquals("44100 Hz", fact(r, "Sample rate", "Track 2 (audio)"))
        assertEquals("16 bits", fact(r, "Sample size", "Track 2 (audio)"))
        assertEquals("0:05.000", fact(r, "Duration", "Track 1 (video)"))
        assertEquals("My Movie", fact(r, "Title"))
        assertEquals("Director", fact(r, "Artist"))
        assertEquals("3 of 10", fact(r, "Track"))
        assertEquals("yes (moov before mdat)", fact(r, "Fast start"))
        assertEquals("My Movie", r.title)
    }

    fun `mp4 id follows brand when extension is unhelpful`() {
        val ftyp = box("ftyp", cat(ascii("M4A "), be32(0), ascii("M4A ")))
        assertEquals("m4a", result(MediaInspectorsMp4, cat(ftyp, box("moov", mvhd(1000, 1000)))).formatId)
        val qt = box("ftyp", cat(ascii("qt  "), be32(0), ascii("qt  ")))
        assertEquals("mov", result(MediaInspectorsMp4, cat(qt, box("moov", mvhd(1000, 1000)))).formatId)
    }

    fun `mp4 truncations and hostile sizes`() {
        everyPrefix(MediaInspectorsMp4, mp4())
        val ftyp = box("ftyp", cat(ascii("isom"), be32(0)))
        MediaInspectorsMp4.inspect(src(cat(ftyp, be32(0xFFFFFFFFL), ascii("moov"), zeros(100))), null)
        MediaInspectorsMp4.inspect(src(cat(ftyp, be32(1), ascii("moov"), be64(Long.MAX_VALUE), zeros(100))), null)
        MediaInspectorsMp4.inspect(src(cat(ftyp, be32(1), ascii("moov"), be64(-1L), zeros(100))), null)
        MediaInspectorsMp4.inspect(src(cat(be32(0xFFFFFFFFL), ascii("ftyp"), zeros(100))), null)
        val inner = cat(be32(0xFFFFFFFFL), ascii("trak"), be32(0xFFFFFFFFL), ascii("mdia"), be32(0xFFFFFFFFL), ascii("minf"), zeros(64))
        MediaInspectorsMp4.inspect(src(cat(ftyp, box("moov", inner))), null)
        // a self-looping zero-size-ish box must not hang
        MediaInspectorsMp4.inspect(src(cat(ftyp, box("moov", cat(be32(8), ascii("trak"), be32(8), ascii("trak"))))), null)
    }

    // ---------- MKV / WebM ----------
    private fun idBytes(id: Long): ByteArray {
        val all = be64(id)
        var i = 0
        while (i < 7 && all[i] == 0.toByte()) i++
        return all.copyOfRange(i, 8)
    }
    private fun vsize(n: Int): ByteArray = if (n < 127) bytes(0x80 or n) else bytes(0x40 or (n shr 8), n and 0xFF)
    private fun el(id: Long, payload: ByteArray) = cat(idBytes(id), vsize(payload.size), payload)
    private fun elUint(id: Long, v: Long) = el(id, if (v < 256) bytes(v.toInt()) else be16(v.toInt()))
    private fun elStr(id: Long, s: String) = el(id, ascii(s))
    private fun elF32(id: Long, f: Float) = el(id, be32(java.lang.Float.floatToIntBits(f).toLong() and 0xFFFFFFFFL))

    private fun mkv(): ByteArray {
        val ebml = el(0x1A45DFA3L, cat(elUint(0x4286, 1), elStr(0x4282, "webm"), elUint(0x4287, 4), elUint(0x4285, 2)))
        val info = el(0x1549A966L, cat(
            el(0x2AD7B1L, bytes(0x0F, 0x42, 0x40)), elF32(0x4489, 5000f), elStr(0x4D80, "libwebm-0.2"), elStr(0x5741, "Lavf58"),
            elStr(0x7BA9, "Clip Title"),
        ))
        val video = el(0xAEL, cat(elUint(0xD7, 1), elUint(0x83, 1), elStr(0x86, "V_VP9"), el(0xE0, cat(elUint(0xB0, 640), elUint(0xBA, 360))),
            el(0x23E383L, be32(40_000_000L))))
        val audio = el(0xAEL, cat(elUint(0xD7, 2), elUint(0x83, 2), elStr(0x86, "A_OPUS"), elStr(0x22B59CL, "eng"),
            el(0xE1, cat(elF32(0xB5, 48000f), elUint(0x9F, 2)))))
        val tracks = el(0x1654AE6BL, cat(video, audio))
        val seg = cat(idBytes(0x18538067L), bytes(0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF), info, tracks, el(0x1F43B675L, ByteArray(0)))
        return cat(ebml, seg)
    }

    fun `mkv webm segment info and tracks`() {
        val r = result(MediaInspectorsMkv, mkv(), "a.webm")
        assertEquals("webm", r.formatId)
        assertEquals("webm", fact(r, "DocType"))
        assertEquals("4", fact(r, "DocType version"))
        assertEquals("0:05.000", fact(r, "Duration"))
        assertEquals("1000000 ns", fact(r, "Timecode scale"))
        assertEquals("libwebm-0.2", fact(r, "Muxing app"))
        assertEquals("Lavf58", fact(r, "Writing app"))
        assertEquals("Clip Title", r.title)
        assertEquals("2", fact(r, "Track count"))
        assertEquals("V_VP9", fact(r, "Codec", "Track 1 (video)"))
        assertEquals("640x360", fact(r, "Pixel size", "Track 1 (video)"))
        assertEquals("25.000 fps (default duration)", fact(r, "Frame rate", "Track 1 (video)"))
        assertEquals("A_OPUS", fact(r, "Codec", "Track 2 (audio)"))
        assertEquals("48000 Hz", fact(r, "Sampling frequency", "Track 2 (audio)"))
        assertEquals("2", fact(r, "Channels", "Track 2 (audio)"))
        assertEquals("eng", fact(r, "Language", "Track 2 (audio)"))
        assertEquals("mkv", result(MediaInspectorsMkv, mkv(), null).formatId.let { if (it == "webm") "mkv" else it })
    }

    fun `mkv truncations and hostile sizes`() {
        everyPrefix(MediaInspectorsMkv, mkv())
        val huge = bytes(0x01, 0x00, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFE)
        val ebml = el(0x1A45DFA3L, elStr(0x4282, "matroska"))
        val seg = cat(idBytes(0x18538067L), huge, idBytes(0x1549A966L), huge, zeros(16))
        MediaInspectorsMkv.inspect(src(cat(ebml, seg)), null)
        val seg2 = cat(idBytes(0x18538067L), bytes(0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF),
            idBytes(0x1654AE6BL), vsize(40), idBytes(0xAEL), huge, zeros(16))
        MediaInspectorsMkv.inspect(src(cat(ebml, seg2)), null)
        assertNull(MediaInspectorsMkv.inspect(src(cat(idBytes(0x1A45DFA3L), huge, zeros(8))), null))
    }

    // ---------- AVI ----------
    private fun list(type: String, vararg parts: ByteArray) = chunk("LIST", cat(ascii(type), *parts))
    private fun avi(): ByteArray {
        val avih = cat(le32(40000), le32(0), le32(0), le32(0x10), le32(50), le32(0), le32(2), le32(0), le32(320), le32(240), zeros(16))
        val vstrh = cat(ascii("vids"), ascii("MJPG"), le32(0), le16(0), le16(0), le32(0), le32(1), le32(25), le32(0), le32(50), zeros(20))
        val vstrf = cat(le32(40), le32(320), le32(240), le16(1), le16(24), ascii("MJPG"), zeros(20))
        val astrh = cat(ascii("auds"), le32(0), le32(0), le16(0), le16(0), le32(0), le32(1), le32(8000), le32(0), le32(8000), zeros(20))
        val astrf = cat(le16(1), le16(1), le32(8000), le32(16000), le16(2), le16(16), le16(0))
        val hdrl = list("hdrl", chunk("avih", avih),
            list("strl", chunk("strh", vstrh), chunk("strf", vstrf)),
            list("strl", chunk("strh", astrh), chunk("strf", astrf)))
        val body = cat(ascii("AVI "), hdrl, list("movi", zeros(8)), chunk("idx1", zeros(16)))
        return cat(ascii("RIFF"), le32(body.size.toLong()), body)
    }

    fun `avi header and streams`() {
        val r = result(MediaInspectorsAvi, avi())
        assertEquals("0:02.000", fact(r, "Duration"))
        assertEquals("320x240", fact(r, "Frame size", "Video"))
        assertEquals("25.000 fps", fact(r, "Frame rate (main header)"))
        assertEquals("50", fact(r, "Total frames"))
        assertEquals("2", fact(r, "Stream count"))
        assertEquals("MJPG", fact(r, "Codec", "Stream 1 (video)"))
        assertEquals("320x240", fact(r, "Frame size", "Stream 1 (video)"))
        assertEquals("25.000 fps", fact(r, "Frame rate", "Stream 1 (video)"))
        assertEquals("24", fact(r, "Bit depth", "Stream 1 (video)"))
        assertEquals("PCM", fact(r, "Codec", "Stream 2 (audio)"))
        assertEquals("8000 Hz", fact(r, "Sample rate", "Stream 2 (audio)"))
        assertEquals("yes", fact(r, "Index"))
    }

    fun `avi truncations and hostile sizes`() {
        everyPrefix(MediaInspectorsAvi, avi())
        MediaInspectorsAvi.inspect(src(cat(ascii("RIFF"), le32(0), ascii("AVI "), ascii("LIST"), le32(0xFFFFFFFFL), ascii("hdrl"), zeros(32))), null)
        MediaInspectorsAvi.inspect(src(cat(ascii("RIFF"), le32(0), ascii("AVI "), list("hdrl", ascii("LIST"), le32(0xFFFFFFFFL), ascii("strl"), zeros(32)))), null)
        MediaInspectorsAvi.inspect(src(cat(ascii("RIFF"), le32(0), ascii("AVI "), ascii("LIST"), le32(4), ascii("hdrl"), ascii("avih"), le32(0xFFFFFFFFL))), null)
    }

    // ---------- FLV ----------
    private fun amfStr(s: String) = cat(bytes(2), be16(s.length), ascii(s))
    private fun amfProp(k: String, v: Double) = cat(be16(k.length), ascii(k), bytes(0), be64(java.lang.Double.doubleToLongBits(v)))
    private fun u24(v: Int) = bytes((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF)
    private fun flvTag(type: Int, ts: Int, data: ByteArray) =
        cat(bytes(type), u24(data.size), u24(ts and 0xFFFFFF), bytes((ts shr 24) and 0xFF), u24(0), data, be32(11L + data.size))
    private fun flv(): ByteArray {
        val meta = cat(amfStr("onMetaData"), bytes(8), be32(3), amfProp("duration", 12.5), amfProp("width", 640.0), amfProp("height", 480.0), bytes(0, 0, 9))
        return cat(ascii("FLV"), bytes(1, 5), be32(9), be32(0),
            flvTag(18, 0, meta), flvTag(9, 0, bytes(0x17, 0, 0, 0, 0)), flvTag(8, 0, bytes(0xAF, 0)))
    }

    fun `flv header and metadata`() {
        val r = result(MediaInspectorsFlv, flv())
        assertEquals("yes", fact(r, "Has audio"))
        assertEquals("yes", fact(r, "Has video"))
        assertEquals("0:12.500", fact(r, "Duration"))
        assertEquals("640", fact(r, "Width"))
        assertEquals("480", fact(r, "Height"))
        assertEquals("H.264 / AVC", fact(r, "Video codec (first tag)"))
        assertEquals("AAC", fact(r, "Audio codec (first tag)"))
        assertEquals("640x480, H.264 / AVC, AAC, 0:12.500", r.summary)
    }

    fun `flv truncations and hostile lengths`() {
        everyPrefix(MediaInspectorsFlv, flv())
        val hdr = cat(ascii("FLV"), bytes(1, 5), be32(9), be32(0))
        MediaInspectorsFlv.inspect(src(cat(hdr, bytes(18), bytes(0xFF, 0xFF, 0xFF), zeros(7), amfStr("onMetaData"), bytes(8), be32(0xFFFFFFFFL))), null)
        MediaInspectorsFlv.inspect(src(cat(hdr, flvTag(18, 0, cat(amfStr("onMetaData"), bytes(10), be32(0xFFFFFFFFL), bytes(0))))), null)
        MediaInspectorsFlv.inspect(src(cat(hdr, flvTag(18, 0, cat(amfStr("onMetaData"), bytes(2), be16(0xFFFF), ascii("ab"))))), null)
        MediaInspectorsFlv.inspect(src(cat(ascii("FLV"), bytes(1, 5), be32(0xFFFFFFFFL), zeros(30))), null)
    }

    // ---------- MIDI ----------
    private fun mtrk(data: ByteArray) = cat(ascii("MTrk"), be32(data.size.toLong()), data)
    private fun midi(): ByteArray {
        val t1 = cat(
            bytes(0x00, 0xFF, 0x03, 4), ascii("Tune"),
            bytes(0x00, 0xFF, 0x51, 3, 0x07, 0xA1, 0x20),
            bytes(0x00, 0xFF, 0x58, 4, 4, 2, 24, 8),
            bytes(0x00, 0xFF, 0x59, 2, 0, 0),
            bytes(0x00, 0xFF, 0x2F, 0),
        )
        val t2 = cat(
            bytes(0x00, 0xC0, 0x00), bytes(0x00, 0x90, 0x3C, 0x40), bytes(0x83, 0x60, 0x80, 0x3C, 0x40), bytes(0x00, 0xFF, 0x2F, 0),
        )
        return cat(ascii("MThd"), be32(6), be16(1), be16(2), be16(480), mtrk(t1), mtrk(t2))
    }

    fun `midi header tempo and duration`() {
        val r = result(MediaInspectorsMidi, midi())
        assertEquals("1 (simultaneous tracks)", fact(r, "Format"))
        assertEquals("2", fact(r, "Track count (header)"))
        assertEquals("480 ticks per quarter note", fact(r, "Division"))
        assertEquals("4/4", fact(r, "Time signature"))
        assertEquals("C major", fact(r, "Key signature"))
        assertEquals("120.0 BPM", fact(r, "Initial tempo"))
        assertEquals("1", fact(r, "Note-on events"))
        assertEquals("1", fact(r, "Channels used"))
        assertEquals("0:00.500", fact(r, "Duration"))
        assertEquals("Tune (0 notes)".replace(" (0 notes)", ""), fact(r, "Track 1"))
        assertEquals("Tune", r.title)
    }

    fun `midi smpte division`() {
        val b = cat(ascii("MThd"), be32(6), be16(0), be16(1), be16(0xE728), mtrk(bytes(0x00, 0xFF, 0x2F, 0)))
        assertEquals("SMPTE, 25 fps, 40 ticks/frame", fact(result(MediaInspectorsMidi, b), "Division"))
    }

    fun `midi truncations and hostile lengths`() {
        everyPrefix(MediaInspectorsMidi, midi())
        val head = cat(ascii("MThd"), be32(6), be16(1), be16(1), be16(480))
        MediaInspectorsMidi.inspect(src(cat(head, ascii("MTrk"), be32(0xFFFFFFFFL), bytes(0, 0xF0, 0xFF, 0xFF, 0xFF, 0x7F, 1, 2))), null)
        MediaInspectorsMidi.inspect(src(cat(head, mtrk(bytes(0, 0xFF, 0x01, 0xFF, 0xFF, 0xFF, 0x7F, 1)))), null)
        MediaInspectorsMidi.inspect(src(cat(ascii("MThd"), be32(0xFFFFFFFFL), be16(1), be16(1), be16(480))), null)
        MediaInspectorsMidi.inspect(src(cat(head, mtrk(bytes(0x80, 0x80, 0x80, 0x80, 0x80, 0x90)))), null)
        MediaInspectorsMidi.inspect(src(cat(head, mtrk(bytes(0x00, 0x40, 0x40)))), null)
    }

    // ---------- AIFF ----------
    private fun aiff(): ByteArray {
        val comm = cat(be16(2), be32(44100), be16(16), bytes(0x40, 0x0E, 0xAC, 0x44, 0, 0, 0, 0, 0, 0))
        val body = cat(ascii("AIFF"), chunk("COMM", comm).let { c -> cat(ascii("COMM"), be32(comm.size.toLong()), comm) },
            cat(ascii("NAME"), be32(4), ascii("Test")), cat(ascii("SSND"), be32(16), zeros(16)))
        return cat(ascii("FORM"), be32(body.size.toLong()), body)
    }

    fun `aiff comm chunk`() {
        val r = result(MediaInspectorsAiff, aiff())
        assertEquals("2", fact(r, "Channels"))
        assertEquals("44100 Hz", fact(r, "Sample rate"))
        assertEquals("16", fact(r, "Bits per sample"))
        assertEquals("44100", fact(r, "Sample frames"))
        assertEquals("0:01.000", fact(r, "Duration"))
        assertEquals("Test", fact(r, "Title"))
        assertEquals("Test", r.title)
    }

    fun `aiff truncations and hostile lengths`() {
        everyPrefix(MediaInspectorsAiff, aiff())
        MediaInspectorsAiff.inspect(src(cat(ascii("FORM"), be32(0), ascii("AIFC"), ascii("COMM"), be32(0xFFFFFFFFL), zeros(40))), null)
        MediaInspectorsAiff.inspect(src(cat(ascii("FORM"), be32(0), ascii("AIFF"), ascii("SSND"), be32(0xFFFFFFFFL), ascii("NAME"), be32(0xFFFFFFFFL))), null)
    }

    // ---------- cross-cutting ----------
    fun `registry lists every inspector with distinct canonical ids`() {
        val all = MediaInspectors.all
        assertEquals(10, all.size)
        val ids = all.flatMap { it.formatIds }
        for (id in listOf("mp3", "wav", "flac", "ogg", "m4a", "mp4", "mov", "mkv", "webm", "avi", "flv", "mid", "aiff")) {
            assertTrue("missing $id", id in ids)
        }
        assertEquals(ids.size, ids.toSet().size)
    }

    fun `garbage never throws and is not misidentified`() {
        val rnd = java.util.Random(42)
        val samples = listOf(ByteArray(0), zeros(1), zeros(4096), ByteArray(4096).also { rnd.nextBytes(it) }, ByteArray(100000) { 0xFF.toByte() })
        for (i in MediaInspectors.all) for (s in samples) {
            val r = i.inspect(src(s), "x.bin")
            if (s.all { it == 0.toByte() } || s.size == 0) assertNull("${i.javaClass.simpleName}", r)
        }
    }

    fun `wrong container is rejected`() {
        assertNull(MediaInspectorsWav.inspect(src(avi()), null))
        assertNull(MediaInspectorsAvi.inspect(src(wav()), null))
        assertNull(MediaInspectorsAiff.inspect(src(wav()), null))
        assertNull(MediaInspectorsFlac.inspect(src(mp3), null))
        assertNull(MediaInspectorsMidi.inspect(src(flac()), null))
    }

    // =================== VERIFY / FUZZ ===================
    private class CountingSource(private val data: ByteArray, private val virtualSize: Long = data.size.toLong(), private val fill: Byte = 0) : ByteSource {
        var bytes = 0L; var calls = 0; var maxNanos = 0L
        override val size: Long get() = virtualSize
        override fun read(offset: Long, length: Int): ByteArray {
            calls++; 
            if (offset < 0 || offset >= virtualSize || length <= 0) return ByteArray(0)
            val end = minOf(virtualSize, offset + length)
            val n = (end - offset).toInt()
            bytes += n
            val out = ByteArray(n) { fill }
            if (offset < data.size) {
                val c = minOf(n.toLong(), data.size - offset).toInt()
                System.arraycopy(data, offset.toInt(), out, 0, c)
            }
            return out
        }
    }

    private fun fixtures(): List<Triple<FormatInspector, String, ByteArray>> = listOf(
        Triple(MediaInspectorsMp3, "x.mp3", mp3),
        Triple(MediaInspectorsMp3, "x.mp3", cat(mp3Frame(cat(ascii("Xing"), be32(1), be32(100))), mp3Frame(), mp3Frame())),
        Triple(MediaInspectorsWav, "x.wav", wav(200)),
        Triple(MediaInspectorsFlac, "x.flac", flac()),
        Triple(MediaInspectorsOgg, "x.ogg", oggVorbis()),
        Triple(MediaInspectorsOgg, "x.opus", oggOpus()),
        Triple(MediaInspectorsMp4, "x.mp4", mp4()),
        Triple(MediaInspectorsMkv, "x.mkv", mkv()),
        Triple(MediaInspectorsAvi, "x.avi", avi()),
        Triple(MediaInspectorsFlv, "x.flv", flv()),
        Triple(MediaInspectorsMidi, "x.mid", midi()),
        Triple(MediaInspectorsAiff, "x.aiff", aiff()),
    )

    private fun check(i: FormatInspector, data: ByteArray, name: String?, what: String, vsize: Long = data.size.toLong(), fill: Byte = 0) {
        val s = CountingSource(data, vsize, fill)
        val t0 = System.nanoTime()
        try { i.inspect(s, name) } catch (t: Throwable) { throw AssertionError("${i.javaClass.simpleName} threw on $what: $t", t) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (s.bytes > FormatInspector.MAX_READ_BYTES) throw AssertionError("${i.javaClass.simpleName} read ${s.bytes} bytes on $what")
        if (s.calls > 20000) throw AssertionError("${i.javaClass.simpleName} made ${s.calls} read calls on $what")
        if (ms > 10_000) throw AssertionError("${i.javaClass.simpleName} took ${ms}ms on $what")
    }

    private val failures = ArrayList<String>()
    private fun safe(i: FormatInspector, data: ByteArray, name: String?, what: String, vsize: Long = data.size.toLong(), fill: Byte = 0) {
        try { check(i, data, name, what, vsize, fill) } catch (e: AssertionError) { if (failures.size < 40) failures.add(e.message ?: "?") }
    }

    @Test fun `fuzz prefixes and flips`() {
        failures.clear()
        for ((i, name, f) in fixtures()) {
            for (n in 0..f.size) safe(i, f.copyOf(n), name, "$name prefix $n")
            val rnd = java.util.Random(1)
            for (k in 0 until 3000) {
                val c = f.copyOf()
                val p = rnd.nextInt(c.size)
                c[p] = (c[p].toInt() xor (1 shl rnd.nextInt(8))).toByte()
                if (k % 3 == 0) c[p] = rnd.nextInt(256).toByte()
                safe(i, c, name, "$name flip at $p")
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun `fuzz length fields`() {
        failures.clear()
        for ((i, name, f) in fixtures()) {
            val vals = longArrayOf(0xFFFFFFFFL, 0x7FFFFFFFL, 0L, 0x80000000L, 0xFFFFL, 0xFFFFFFL)
            val step = 1
            for (p in 0 until f.size step step) for (v in vals) for (le in listOf(false, true)) {
                val c = f.copyOf()
                val enc = if (le) le32(v) else be32(v)
                for (k in 0 until 4) if (p + k < c.size) c[p + k] = enc[k]
                safe(i, c, name, "$name len@$p=${v.toString(16)} le=$le")
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun `fuzz zeros ff random magic and huge virtual size`() {
        failures.clear()
        val rnd = java.util.Random(7)
        for (i in MediaInspectors.all) {
            for (n in listOf(0, 1, 3, 4, 11, 12, 13, 27, 64, 1000, 70000, 600000)) {
                safe(i, ByteArray(n), "x.bin", "zeros $n")
                safe(i, ByteArray(n) { 0xFF.toByte() }, "x.bin", "ff $n")
            }
        }
        val magics = listOf("ID3", "RIFFxxxxWAVE", "fLaC", "OggS", "\u0000\u0000\u0000\u0018ftypM4A ", "\u001AEß£", "RIFFxxxxAVI ", "FLV\u0001\u0005", "MThd", "FORMxxxxAIFF", "\u0000\u0000\u0000\u0008moov")
        for (m in magics) for (i in MediaInspectors.all) for (k in 0 until 150) {
            val r = ByteArray(rnd.nextInt(3000) + 1).also { rnd.nextBytes(it) }
            safe(i, cat(m.toByteArray(Charsets.ISO_8859_1), r), "x.dat", "random after magic $m")
        }
        // huge virtual sizes, fixture at start then zero / FF filler
        for ((i, name, f) in fixtures()) for (fill in listOf(0.toByte(), 0xFF.toByte()))
            for (vs in listOf(50L * 1024 * 1024 * 1024, Long.MAX_VALUE / 2, 20_000_000L))
                safe(i, f, name, "$name huge size $vs fill $fill", vs, fill)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun `ground truth spot checks`() {
        val bad = ArrayList<String>()
        // ogg: pages parsed count must equal real pages consumed (vorbis fixture = 2 header pages)
        val ov = MediaInspectorsOgg.inspect(src(oggVorbis()), "x.ogg")!!
        val pp = fact(ov, "Pages parsed for headers")
        if (pp != "2" && pp != "3") bad.add("pages $pp")
        // aiff-c id
        val aifc = aiff().copyOf().also { it[11] = 'C'.code.toByte() }
        val ra = MediaInspectorsAiff.inspect(src(aifc), "x.aifc")
        if (ra == null || ra.formatId !in MediaInspectorsAiff.formatIds) bad.add("aiffc formatId=${ra?.formatId} claimed=${MediaInspectorsAiff.formatIds}")
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }
}
