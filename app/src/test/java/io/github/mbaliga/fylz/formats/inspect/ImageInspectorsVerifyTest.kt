package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

class ImageInspectorsVerifyTest {
    // ---------- byte builders ----------
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun cat(vararg parts: ByteArray): ByteArray { val o = ByteArrayOutputStream(); parts.forEach { o.write(it) }; return o.toByteArray() }
    private fun asc(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    private fun be16(v: Int) = bytes(v shr 8, v)
    private fun be32(v: Long) = bytes((v shr 24).toInt(), (v shr 16).toInt(), (v shr 8).toInt(), v.toInt())
    private fun le16(v: Int) = bytes(v, v shr 8)
    private fun le32(v: Long) = bytes(v.toInt(), (v shr 8).toInt(), (v shr 16).toInt(), (v shr 24).toInt())
    private fun u16(le: Boolean, v: Int) = if (le) le16(v) else be16(v)
    private fun u32(le: Boolean, v: Long) = if (le) le32(v) else be32(v)

    private fun run(i: FormatInspector, b: ByteArray, name: String? = null) = i.inspect(ByteArraySource(b), name)
    private fun InspectionResult.fact(label: String) = facts.firstOrNull { it.label == label }?.value
    private fun InspectionResult.has(label: String, value: String) = assertEquals("fact $label in $facts", value, fact(label))

    private fun assertNoThrowOnPrefixes(i: FormatInspector, b: ByteArray, name: String? = null) {
        for (n in 0..b.size) {
            try { run(i, b.copyOf(n), name) } catch (t: Throwable) { fail("threw on prefix $n of ${b.size}: $t") }
        }
    }

    private class ZeroSource(override val size: Long, private val prefix: ByteArray) : ByteSource {
        override fun read(offset: Long, length: Int): ByteArray {
            if (offset < 0 || offset >= size || length <= 0) return ByteArray(0)
            val n = minOf(length.toLong(), size - offset).toInt()
            val out = ByteArray(n)
            for (k in 0 until n) { val p = offset + k; if (p < prefix.size) out[k] = prefix[p.toInt()] }
            return out
        }
    }

    private fun assertHugeSourceOk(i: FormatInspector, prefix: ByteArray) {
        val start = System.currentTimeMillis()
        i.inspect(ZeroSource(50L * 1024 * 1024 * 1024, prefix), null)
        assertTrue("took too long", System.currentTimeMillis() - start < 15000)
    }

    // ---------- PNG ----------
    private fun chunk(type: String, data: ByteArray) = cat(be32(data.size.toLong()), asc(type), data, be32(0))
    private fun deflate(b: ByteArray): ByteArray {
        val d = Deflater(); d.setInput(b); d.finish()
        val buf = ByteArray(1024); val n = d.deflate(buf); d.end(); return buf.copyOf(n)
    }
    private val PNG_SIG = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private fun png(): ByteArray = cat(
        PNG_SIG,
        chunk("IHDR", cat(be32(640), be32(480), bytes(8, 6, 0, 0, 0))),
        chunk("gAMA", be32(45455)),
        chunk("pHYs", cat(be32(2835), be32(2835), bytes(1))),
        chunk("tIME", cat(be16(2024), bytes(5, 17, 13, 45, 9))),
        chunk("tEXt", asc("Author\u0000Jane Doe")),
        chunk("zTXt", cat(asc("Comment\u0000"), bytes(0), deflate(asc("compressed words")))),
        chunk("iTXt", cat(asc("Title\u0000"), bytes(0, 0), asc("en\u0000"), asc("Titre\u0000"), "Héllo".toByteArray(Charsets.UTF_8))),
        chunk("acTL", cat(be32(3), be32(0))),
        chunk("IDAT", ByteArray(20)),
        chunk("IEND", ByteArray(0)),
    )

    fun pngFacts() {
        val r = run(PngInspector, png(), "a.png")!!
        assertEquals("png", r.formatId)
        r.has("Dimensions", "640×480")
        r.has("Bit depth", "8")
        r.has("Colour type", "Truecolour + alpha (RGBA)")
        r.has("Bits per pixel", "32")
        r.has("Interlace", "None")
        r.has("Gamma", "0.45455")
        r.has("Pixel density", "72 × 72 dpi")
        r.has("Last modified", "2024-05-17 13:45:09 UTC")
        r.has("Author", "Jane Doe")
        r.has("Comment", "compressed words")
        r.has("Title", "Héllo")
        r.has("Frames", "3")
        r.has("Plays", "infinite")
        assertTrue(r.warnings.toString(), r.warnings.isEmpty())
        assertTrue(r.text!!.contains("Author: Jane Doe"))
        assertEquals("PNG 640×480, 8-bit Truecolour + alpha (RGBA), 3 frames", r.summary)
    }

    fun pngNotPng() = assertNull(run(PngInspector, asc("hello world, definitely not a png")))

    fun pngBadDepthWarns() {
        val b = cat(PNG_SIG, chunk("IHDR", cat(be32(1), be32(1), bytes(3, 2, 0, 0, 1))), chunk("IEND", ByteArray(0)))
        val r = run(PngInspector, b)!!
        assertTrue(r.warnings.any { it.contains("Bit depth") })
        r.has("Interlace", "Adam7")
    }

    fun pngTruncation() = assertNoThrowOnPrefixes(PngInspector, png())

    fun pngHostileLength() {
        val b = cat(PNG_SIG, chunk("IHDR", cat(be32(1), be32(1), bytes(8, 2, 0, 0, 0))), cat(be32(0xFFFFFFFFL), asc("tEXt"), asc("Key\u0000val")))
        val r = run(PngInspector, b)!!
        assertTrue(r.warnings.isNotEmpty())
        val b2 = cat(PNG_SIG, chunk("IHDR", cat(be32(1), be32(1), bytes(8, 2, 0, 0, 0))), cat(be32(0x7FFFFFFFL), asc("zTXt"), asc("Key\u0000\u0000xx")))
        assertNotNull(run(PngInspector, b2))
        assertHugeSourceOk(PngInspector, cat(PNG_SIG, chunk("IHDR", cat(be32(1), be32(1), bytes(8, 2, 0, 0, 0)))))
    }

    fun pngZipBombIsBounded() {
        val big = deflate(ByteArray(8 * 1024 * 1024))
        val b = cat(PNG_SIG, chunk("IHDR", cat(be32(1), be32(1), bytes(8, 2, 0, 0, 0))), chunk("zTXt", cat(asc("Bomb\u0000"), bytes(0), big)), chunk("IEND", ByteArray(0)))
        val r = run(PngInspector, b)!!
        assertTrue(r.text!!.length <= FormatInspector.MAX_TEXT_CHARS + 16)
    }

    // ---------- TIFF / EXIF builder ----------
    private class E(val tag: Int, val type: Int, val count: Int, val value: ByteArray)

    private fun ifdBlock(le: Boolean, at: Int, entries: List<E>, next: Int): ByteArray {
        val head = ByteArrayOutputStream(); val data = ByteArrayOutputStream()
        val dataStart = at + 2 + 12 * entries.size + 4
        head.write(u16(le, entries.size))
        for (e in entries) {
            head.write(u16(le, e.tag)); head.write(u16(le, e.type)); head.write(u32(le, e.count.toLong()))
            if (e.value.size <= 4) { head.write(e.value); head.write(ByteArray(4 - e.value.size)) }
            else { head.write(u32(le, (dataStart + data.size()).toLong())); data.write(e.value); if (e.value.size % 2 == 1) data.write(0) }
        }
        head.write(u32(le, next.toLong()))
        return cat(head.toByteArray(), data.toByteArray())
    }
    private fun ascii(le: Boolean, s: String) = E(0, 2, s.length + 1, asc(s + "\u0000"))
    private fun E.tagged(t: Int) = E(t, type, count, value)
    private fun short(le: Boolean, tag: Int, v: Int) = E(tag, 3, 1, u16(le, v))
    private fun long(le: Boolean, tag: Int, v: Long) = E(tag, 4, 1, u32(le, v))
    private fun str(le: Boolean, tag: Int, s: String) = ascii(le, s).tagged(tag)
    private fun rat(le: Boolean, tag: Int, n: Long, d: Long) = E(tag, 5, 1, cat(u32(le, n), u32(le, d)))

    private fun header(le: Boolean) = cat(asc(if (le) "II" else "MM"), u16(le, 42), u32(le, 8))

    private fun tiff(le: Boolean): ByteArray {
        val bps = E(0x0102, 3, 3, cat(u16(le, 8), u16(le, 8), u16(le, 8)))
        val entries = listOf(
            long(le, 0x0100, 4000), short(le, 0x0101, 3000), bps, short(le, 0x0103, 5), short(le, 0x0106, 2),
            str(le, 0x010F, "Canon"), str(le, 0x0110, "EOS R5"), short(le, 0x0115, 3),
        )
        val ifd0 = ifdBlock(le, 8, entries, 0)
        return cat(header(le), ifd0)
    }

    fun tiffLittleAndBigEndian() {
        for (le in listOf(true, false)) {
            val r = run(TiffInspector, tiff(le), "scan.tif")!!
            assertEquals("tif", r.formatId)
            r.has("Dimensions", "4000×3000")
            r.has("Compression", "LZW")
            r.has("Photometric interpretation", "RGB")
            r.has("Bits per sample", "8, 8, 8")
            r.has("Make", "Canon")
            r.has("Model", "EOS R5")
            r.has("Pages / IFDs", "1")
            r.has("Byte order", if (le) "little-endian (II)" else "big-endian (MM)")
        }
    }

    fun tiffPagesAndDng() {
        val le = true
        val e0 = listOf(short(le, 0x0100, 256), short(le, 0x0101, 171), E(0xC612, 1, 4, bytes(1, 4, 0, 0)), E(0x014A, 4, 1, u32(le, 0)))
        // IFD0 placeholder to compute size, then SubIFD after it
        val size0 = ifdBlock(le, 8, e0, 0).size
        val subAt = 8 + size0
        val e0b = listOf(short(le, 0x0100, 256), short(le, 0x0101, 171), E(0xC612, 1, 4, bytes(1, 4, 0, 0)), E(0x014A, 4, 1, u32(le, subAt.toLong())))
        val sub = ifdBlock(le, subAt, listOf(long(le, 0x00FE, 0), short(le, 0x0100, 6000), short(le, 0x0101, 4000), short(le, 0x0103, 7), short(le, 0x0106, 32803)), 0)
        val b = cat(header(le), ifdBlock(le, 8, e0b, 0), sub)
        val r = run(TiffInspector, b, "IMG_0001.DNG")!!
        assertEquals("dng", r.formatId)
        r.has("Dimensions", "6000×4000")
        r.has("DNG version", "1.4.0.0")
        r.has("Compression", "JPEG")
        r.has("Photometric interpretation", "Colour filter array (raw)")
        assertTrue(r.fact("Dimensions taken from")!!.startsWith("SubIFD 1"))
        // sniffed as DNG even without a name
        assertEquals("dng", run(TiffInspector, b, null)!!.formatId)
    }

    fun tiffMultiPage() {
        val le = true
        val e = listOf(short(le, 0x0100, 10), short(le, 0x0101, 10))
        val sz = ifdBlock(le, 8, e, 0).size
        val b = cat(header(le), ifdBlock(le, 8, e, 8 + sz), ifdBlock(le, 8 + sz, e, 0))
        assertEquals("2", run(TiffInspector, b, "x.tiff")!!.fact("Pages / IFDs"))
    }

    fun tiffLoopingIfdChainTerminates() {
        val le = true
        val e = listOf(short(le, 0x0100, 10), short(le, 0x0101, 10))
        val b = cat(header(le), ifdBlock(le, 8, e, 8)) // next points back to itself
        assertEquals("1", run(TiffInspector, b)!!.fact("Pages / IFDs"))
    }

    fun tiffBigTiffAndHostile() {
        val big = cat(asc("II"), le16(43), le16(8), le16(0), le32(16), le32(0))
        val r = run(TiffInspector, big, "x.tif")!!
        assertTrue(r.warnings.isNotEmpty())
        // hostile IFD0 offset and huge ASCII count
        val hostile = cat(asc("II"), le16(42), le32(0xFFFFFFFFL))
        assertNotNull(run(TiffInspector, hostile))
        val le = true
        val bad = ifdBlock(le, 8, listOf(E(0x010F, 2, 0x7FFFFFFF, u32(le, 12)), short(le, 0x0100, 1), short(le, 0x0101, 1)), 0)
        assertNotNull(run(TiffInspector, cat(header(le), bad)))
        assertHugeSourceOk(TiffInspector, cat(header(le), ifdBlock(le, 8, listOf(short(le, 0x0100, 1)), 0)))
    }

    fun tiffTruncation() { assertNoThrowOnPrefixes(TiffInspector, tiff(true)); assertNoThrowOnPrefixes(TiffInspector, tiff(false)) }

    // ---------- JPEG ----------
    private fun seg(marker: Int, data: ByteArray) = cat(bytes(0xFF, marker), be16(data.size + 2), data)

    private fun exifPayload(): ByteArray {
        val le = false
        val e0a = listOf(str(le, 0x010F, "NIKON"), str(le, 0x0110, "Z 6"), short(le, 0x0112, 6), long(le, 0x8769, 0), long(le, 0x8825, 0))
        val s0 = ifdBlock(le, 8, e0a, 0).size
        val exifAt = 8 + s0
        val exifEntries = listOf(str(le, 0x9003, "2023:07:04 10:20:30"), rat(le, 0x829A, 1, 250), rat(le, 0x829D, 28, 10), short(le, 0x8827, 400), rat(le, 0x920A, 500, 10))
        val s1 = ifdBlock(le, exifAt, exifEntries, 0).size
        val gpsAt = exifAt + s1
        val gps = ifdBlock(le, gpsAt, listOf(E(1, 2, 2, asc("N\u0000"))), 0)
        val e0 = listOf(str(le, 0x010F, "NIKON"), str(le, 0x0110, "Z 6"), short(le, 0x0112, 6), long(le, 0x8769, exifAt.toLong()), long(le, 0x8825, gpsAt.toLong()))
        return cat(asc("Exif\u0000\u0000"), header(le), ifdBlock(le, 8, e0, 0), ifdBlock(le, exifAt, exifEntries, 0), gps)
    }

    private fun jpeg(progressive: Boolean = true): ByteArray = cat(
        bytes(0xFF, 0xD8),
        seg(0xE0, cat(asc("JFIF\u0000"), bytes(1, 2, 1), be16(300), be16(300), bytes(0, 0))),
        seg(0xE1, exifPayload()),
        seg(0xE1, asc("http://ns.adobe.com/xap/1.0/\u0000<x/>")),
        seg(0xE2, cat(asc("ICC_PROFILE\u0000"), bytes(1, 1), ByteArray(12), asc("mntr"), asc("RGB "), ByteArray(16), asc("acsp"), ByteArray(60))),
        seg(0xFE, asc("made by tests")),
        seg(if (progressive) 0xC2 else 0xC0, cat(bytes(8), be16(1080), be16(1920), bytes(3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1))),
        seg(0xDA, bytes(3, 1, 0, 2, 0x11, 3, 0x11, 0, 63, 0)),
        bytes(1, 2, 3, 0xFF, 0xD9),
    )

    fun jpegFacts() {
        val r = run(JpegInspector, jpeg(), "p.jpg")!!
        assertEquals("jpg", r.formatId)
        r.has("Dimensions", "1920×1080")
        r.has("Encoding", "Progressive DCT")
        r.has("Progressive", "yes")
        r.has("Sample precision", "8 bits")
        r.has("Components", "3 (colour)")
        r.has("Chroma subsampling", "4:2:0")
        r.has("JFIF version", "1.02")
        r.has("Density", "300 × 300 dpi")
        r.has("Camera make", "NIKON")
        r.has("Camera model", "Z 6")
        r.has("Orientation", "Rotated 90° clockwise")
        r.has("Date taken", "2023:07:04 10:20:30")
        r.has("Exposure time", "1/250 s")
        r.has("Aperture", "f/2.8")
        r.has("ISO", "400")
        r.has("Focal length", "50.0 mm")
        r.has("GPS data", "present")
        r.has("ICC profile", "present (RGB colour space)")
        r.has("XMP packet", "present")
        r.has("Comment", "made by tests")
        assertTrue(r.warnings.toString(), r.warnings.isEmpty())
        assertEquals("JPEG 1920×1080, progressive, NIKON Z 6", r.summary)
    }

    fun jpegBaseline() {
        val r = run(JpegInspector, jpeg(false))!!
        r.has("Encoding", "Baseline DCT")
        r.has("Progressive", "no")
    }

    fun jpegNotJpeg() { assertNull(run(JpegInspector, asc("GIF89a....."))); assertNull(run(JpegInspector, bytes(0xFF, 0xD8))) }

    fun jpegTruncation() = assertNoThrowOnPrefixes(JpegInspector, jpeg())

    fun jpegHostile() {
        val b = cat(bytes(0xFF, 0xD8), bytes(0xFF, 0xE1, 0xFF, 0xFF), asc("Exif\u0000\u0000"), ByteArray(50))
        val r = run(JpegInspector, b)!!
        assertTrue(r.warnings.isNotEmpty())
        // exif with huge entry count and offsets
        val le = true
        val tiffBad = cat(asc("Exif\u0000\u0000"), header(le), u16(le, 0xFFFF), ByteArray(24), u32(le, 0xFFFFFFFFL))
        assertNotNull(run(JpegInspector, cat(bytes(0xFF, 0xD8), seg(0xE1, tiffBad), seg(0xC0, cat(bytes(8), be16(1), be16(1), bytes(1, 1, 0x11, 0))))))
        assertHugeSourceOk(JpegInspector, bytes(0xFF, 0xD8, 0xFF, 0xFF, 0xFF))
        // endless fill bytes
        assertHugeSourceOk(JpegInspector, cat(bytes(0xFF, 0xD8), ByteArray(0)).let { it + ByteArray(3) { 0xFF.toByte() } })
    }

    // ---------- GIF ----------
    private fun gifFrame(delay: Int) = cat(
        bytes(0x21, 0xF9, 4, 1), le16(delay), bytes(0, 0),
        bytes(0x2C), le16(0), le16(0), le16(10), le16(20), bytes(0),
        bytes(2, 2, 0x44, 0x01, 0),
    )

    private fun gif(): ByteArray = cat(
        asc("GIF89a"), le16(10), le16(20), bytes(0xF1, 0, 0), ByteArray(12),
        bytes(0x21, 0xFF, 11), asc("NETSCAPE2.0"), bytes(3, 1), le16(0), bytes(0),
        bytes(0x21, 0xFE, 5), asc("hello"), bytes(0),
        gifFrame(10), gifFrame(10), bytes(0x3B),
    )

    fun gifFacts() {
        val r = run(GifInspector, gif(), "a.gif")!!
        assertEquals("gif", r.formatId)
        r.has("Version", "GIF89a")
        r.has("Dimensions", "10×20")
        r.has("Global palette", "4 colours")
        r.has("Colour resolution", "8 bits")
        r.has("Frames", "2")
        r.has("Animated", "yes")
        r.has("Total duration", "0.20 s")
        r.has("Loop", "infinite")
        r.has("Transparency", "yes")
        r.has("Comments", "1")
        assertTrue(r.text!!.contains("hello"))
        assertTrue(r.warnings.toString(), r.warnings.isEmpty())
    }

    fun gifStillAndFiniteLoop() {
        val still = cat(asc("GIF87a"), le16(3), le16(3), bytes(0x00, 0, 0), gifFrame(0).copyOfRange(8, gifFrame(0).size), bytes(0x3B))
        val r = run(GifInspector, still)!!
        r.has("Animated", "no"); r.has("Frames", "1")
        val twice = gif().let { g -> run(GifInspector, g.copyOf().also { it[13 + 12 + 16] = 3 })!! }
        r.has("Version", "GIF87a")
        assertNotNull(twice)
    }

    fun gifTruncationAndHostile() {
        assertNoThrowOnPrefixes(GifInspector, gif())
        val cut = run(GifInspector, gif().copyOf(gif().size - 1))!!
        assertTrue(cut.warnings.any { it.contains("trailer") })
        // sub-block chain that never terminates in the zero-source
        assertHugeSourceOk(GifInspector, cat(asc("GIF89a"), le16(1), le16(1), bytes(0, 0, 0), bytes(0x2C), ByteArray(9), bytes(2), ByteArray(0)))
        val ff = ByteArray(65536) { 1 }
        val big = cat(asc("GIF89a"), le16(1), le16(1), bytes(0, 0, 0), bytes(0x21, 0xFE), ff)
        assertNotNull(run(GifInspector, big))
    }

    // ---------- WebP ----------
    private fun riffWebp(vararg chunks: ByteArray): ByteArray {
        val body = cat(asc("WEBP"), *chunks)
        return cat(asc("RIFF"), le32(body.size.toLong()), body)
    }
    private fun rchunk(id: String, d: ByteArray) = cat(asc(id), le32(d.size.toLong()), d, if (d.size % 2 == 1) ByteArray(1) else ByteArray(0))
    private fun le24(v: Int) = bytes(v, v shr 8, v shr 16)

    fun webpLossless() {
        val v = (99L) or (49L shl 14) or (1L shl 28)
        val r = run(WebpInspector, riffWebp(rchunk("VP8L", cat(bytes(0x2F), le32(v), ByteArray(8)))), "a.webp")!!
        r.has("Dimensions", "100×50"); r.has("Compression", "Lossless (VP8L)"); r.has("Alpha channel", "yes"); r.has("Animated", "no")
        assertTrue(r.warnings.toString(), r.warnings.isEmpty())
    }

    fun webpLossy() {
        val r = run(WebpInspector, riffWebp(rchunk("VP8 ", cat(bytes(0x10, 0, 0), bytes(0x9D, 0x01, 0x2A), le16(320), le16(240), ByteArray(4)))))!!
        r.has("Dimensions", "320×240"); r.has("Compression", "Lossy (VP8)"); r.has("Alpha channel", "no"); r.has("Container", "Simple")
    }

    private fun webpAnim(): ByteArray {
        fun anmf(ms: Int) = rchunk("ANMF", cat(le24(0), le24(0), le24(63), le24(31), le24(ms), bytes(0), ByteArray(4)))
        return riffWebp(
            rchunk("VP8X", cat(bytes(0x12, 0, 0, 0), le24(63), le24(31))),
            rchunk("ANIM", cat(bytes(0xFF, 0xFF, 0xFF, 0xFF), le16(3))),
            anmf(100), anmf(200),
        )
    }

    fun webpAnimated() {
        val r = run(WebpInspector, webpAnim())!!
        r.has("Dimensions", "64×32"); r.has("Animated", "yes"); r.has("Frames", "2"); r.has("Total duration", "0.30 s")
        r.has("Loop", "3 times"); r.has("Alpha channel", "yes"); r.has("Container", "Extended (VP8X)")
    }

    fun webpTruncationAndHostile() {
        assertNoThrowOnPrefixes(WebpInspector, webpAnim())
        val b = cat(asc("RIFF"), le32(0xFFFFFFFFL), asc("WEBP"), asc("VP8X"), le32(0xFFFFFFFFL))
        assertNotNull(run(WebpInspector, b))
        assertHugeSourceOk(WebpInspector, cat(asc("RIFF"), le32(8), asc("WEBP")))
        assertNull(run(WebpInspector, asc("RIFF0000WAVE")))
    }

    // ---------- BMP ----------
    private fun bmp(h: Int = -8): ByteArray = cat(
        asc("BM"), le32(54 + 16 * 8 * 3L), le32(0), le32(54),
        le32(40), le32(16), le32(h.toLong() and 0xFFFFFFFFL), le16(1), le16(24), le32(0), le32(0), le32(2835), le32(2835), le32(0), le32(0),
        ByteArray(16 * 8 * 3),
    )

    fun bmpFacts() {
        val r = run(BmpInspector, bmp(), "a.bmp")!!
        r.has("Dimensions", "16×8"); r.has("Bits per pixel", "24"); r.has("Compression", "BI_RGB (none)")
        r.has("Row order", "top-down"); r.has("Header", "BITMAPINFOHEADER"); r.has("Resolution", "72 × 72 dpi"); r.has("Pixel data offset", "54")
        assertTrue(r.warnings.toString(), r.warnings.isEmpty())
        run(BmpInspector, bmp(8))!!.has("Row order", "bottom-up")
    }

    fun bmpOs2Core() {
        val b = cat(asc("BM"), le32(30), le32(0), le32(26), le32(12), le16(5), le16(7), le16(1), le16(8), ByteArray(4))
        val r = run(BmpInspector, b)!!
        r.has("Dimensions", "5×7"); r.has("Header", "BITMAPCOREHEADER (OS/2 1.x)")
    }

    fun bmpTruncationAndHostile() {
        assertNoThrowOnPrefixes(BmpInspector, bmp())
        val b = cat(asc("BM"), le32(0xFFFFFFFFL), le32(0), le32(0xFFFFFFFFL), le32(40), le32(0xFFFFFFFFL), le32(0x80000000L), ByteArray(30))
        assertNotNull(run(BmpInspector, b))
        assertHugeSourceOk(BmpInspector, asc("BM"))
    }

    // ---------- ICO / CUR ----------
    private fun ico(type: Int = 1): ByteArray {
        val d = 6 + 32
        val png = cat(PNG_SIG, ByteArray(20))
        val bmpImg = cat(le32(40), ByteArray(36))
        return cat(le16(0), le16(type), le16(2),
            bytes(16, 16, 0, 0), le16(1), le16(32), le32(bmpImg.size.toLong()), le32(d.toLong()),
            bytes(0, 0, 0, 0), le16(1), le16(32), le32(png.size.toLong()), le32((d + bmpImg.size).toLong()),
            bmpImg, png)
    }

    fun icoFacts() {
        val r = run(IcoInspector, ico(), "favicon.ico")!!
        assertEquals("ico", r.formatId)
        r.has("Images", "2")
        r.has("Image 1", "16×16, 32-bit, BMP, 40 B")
        assertTrue(r.fact("Image 2")!!.startsWith("256×256, 32-bit, PNG"))
        assertEquals("Icon with 2 images (16×16, 256×256)", r.summary)
    }

    fun curFacts() {
        val r = run(IcoInspector, ico(2), "c.cur")!!
        assertEquals("cur", r.formatId)
        assertTrue(r.fact("Image 1")!!.contains("hotspot (1, 32)"))
    }

    fun icoTruncationAndHostile() {
        assertNoThrowOnPrefixes(IcoInspector, ico())
        val b = cat(le16(0), le16(1), le16(0xFFFF), bytes(1, 1, 0, 0), le16(1), le16(8), le32(0xFFFFFFFFL), le32(0xFFFFFFFFL))
        val r = run(IcoInspector, b)!!
        assertTrue(r.warnings.isNotEmpty())
        assertHugeSourceOk(IcoInspector, cat(le16(0), le16(1), le16(0xFFFF)))
        assertNull(run(IcoInspector, asc("not an icon")))
    }

    // ---------- PSD ----------
    private fun psd(): ByteArray = cat(
        asc("8BPS"), be16(1), ByteArray(6), be16(4), be32(100), be32(200), be16(8), be16(3),
        be32(0), be32(0), be32(10), be32(8), be16(3), ByteArray(4),
    )

    fun psdFacts() {
        val r = run(PsdInspector, psd(), "a.psd")!!
        r.has("Dimensions", "200×100"); r.has("Channels", "4"); r.has("Bit depth", "8"); r.has("Colour mode", "RGB"); r.has("Layer count", "3")
        r.has("Format", "PSD")
        assertTrue(r.warnings.toString(), r.warnings.isEmpty())
    }

    fun psdTruncationAndHostile() {
        assertNoThrowOnPrefixes(PsdInspector, psd())
        val b = cat(asc("8BPS"), be16(2), ByteArray(6), be16(0xFFFF), be32(0xFFFFFFFFL), be32(0xFFFFFFFFL), be16(77), be16(99), be32(0xFFFFFFFFL), be32(0xFFFFFFFFL), be32(0xFFFFFFFFL))
        val r = run(PsdInspector, b)!!
        assertTrue(r.warnings.isNotEmpty())
        assertHugeSourceOk(PsdInspector, cat(asc("8BPS"), be16(1), ByteArray(6), be16(3), be32(10), be32(10), be16(8), be16(3), be32(0xFFFFFFFFL)))
    }

    // ---------- DDS ----------
    private fun dds(four: String = "DXT5", dx10: Boolean = false): ByteArray {
        val pf = cat(le32(32), le32(4), asc(four), le32(0), le32(0), le32(0), le32(0), le32(0))
        val hdr = cat(asc("DDS "), le32(124), le32(0x1007 or 0x20000), le32(64), le32(128), le32(0), le32(0), le32(7), ByteArray(44), pf, le32(0x401008), le32(0), le32(0), le32(0), le32(0))
        return if (dx10) cat(hdr, le32(98), le32(3), le32(0), le32(1), le32(0)) else hdr
    }

    fun ddsFacts() {
        val b = dds()
        assertEquals(128, b.size)
        val r = run(DdsInspector, b, "t.dds")!!
        r.has("Dimensions", "128×64"); r.has("Mipmap levels", "7"); r.has("FourCC", "DXT5"); r.has("Format", "DXT5")
        assertTrue(r.warnings.toString(), r.warnings.isEmpty())
        val r2 = run(DdsInspector, dds("DX10", true))!!
        r2.has("Format", "DX10: BC7_UNORM"); r2.has("Array size", "1")
    }

    fun ddsTruncationAndHostile() {
        assertNoThrowOnPrefixes(DdsInspector, dds("DX10", true))
        val b = dds().also { for (i in 8 until 32) it[i] = 0xFF.toByte() }
        assertNotNull(run(DdsInspector, b))
        assertHugeSourceOk(DdsInspector, dds())
    }

    // ---------- TGA ----------
    private fun tga(): ByteArray = cat(
        bytes(4, 0, 10), ByteArray(5), le16(0), le16(0), le16(32), le16(16), bytes(32, 0x28), asc("test"),
        ByteArray(30),
        le32(0), le32(0), asc("TRUEVISION-XFILE."), bytes(0),
    )

    fun tgaFacts() {
        val r = run(TgaInspector, tga(), "a.tga")!!
        r.has("Dimensions", "32×16"); r.has("Bits per pixel", "32"); r.has("RLE compressed", "yes"); r.has("Image type", "True-colour (RLE)")
        r.has("Alpha bits", "8"); r.has("Origin", "top-left"); r.has("Image ID", "test"); r.has("TGA version", "2.0 (has footer)")
    }

    fun tgaExtensionArea() {
        val head = cat(bytes(0, 0, 2), ByteArray(5), le16(0), le16(0), le16(2), le16(2), bytes(24, 0), ByteArray(12))
        val ext = ByteArray(495)
        ext[0] = (495 and 0xFF).toByte(); ext[1] = (495 shr 8).toByte()
        asc("Ada").copyInto(ext, 2); asc("Gimp").copyInto(ext, 426)
        val b = cat(head, ext, le32(head.size.toLong()), le32(0), asc("TRUEVISION-XFILE."), bytes(0))
        val r = run(TgaInspector, b)!!
        r.has("Author", "Ada"); r.has("Software", "Gimp"); r.has("Alpha bits", "0")
    }

    fun tgaRejectsGarbageAndTruncation() {
        assertNull(run(TgaInspector, ByteArray(40) { 0x41 }))
        assertNull(run(TgaInspector, ByteArray(100)))
        assertNoThrowOnPrefixes(TgaInspector, tga())
        val hostile = cat(bytes(0xFF, 0, 2), ByteArray(5), le16(0), le16(0), le16(0xFFFF), le16(0xFFFF), bytes(24, 0))
        assertNotNull(run(TgaInspector, hostile))
        assertHugeSourceOk(TgaInspector, hostile)
    }

    // ---------- SVG ----------
    private val svgText = """<?xml version="1.0" encoding="UTF-8"?>
<!-- c -->
<svg xmlns="http://www.w3.org/2000/svg" width="120" height="80" viewBox="0 0 24 16" version="1.1">
  <title>My Logo</title><desc>A <b>test</b></desc>
  <g><path d="M0 0"/><path d="M1 1"/><rect x="0" y="0" width="1" height="1"/><circle r="2"/></g>
</svg>"""

    fun svgFacts() {
        val r = run(SvgInspector, svgText.toByteArray(), "logo.svg")!!
        r.has("Width", "120"); r.has("Height", "80"); r.has("viewBox", "0 0 24 16"); r.has("viewBox size", "24×16")
        r.has("SVG version", "1.1"); r.has("Title", "My Logo"); r.has("Elements", "path×2, rect×1, circle×1, g×1")
        assertEquals("SVG 120×80 – My Logo", r.summary)
        assertEquals("My Logo", r.text)
    }

    fun svgRejectsNonSvgAndSurvivesTruncation() {
        assertNull(run(SvgInspector, asc("<html><body>hi</body></html>")))
        assertNull(run(SvgInspector, asc("<svgx>")))
        assertNoThrowOnPrefixes(SvgInspector, svgText.toByteArray())
        val hostile = ("<svg " + "a=\"".repeat(1) + "x".repeat(300000)).toByteArray()
        assertNotNull(run(SvgInspector, hostile))
        assertHugeSourceOk(SvgInspector, "<svg width='1'>".toByteArray())
    }

    // ---------- HEIC / AVIF ----------
    private fun box(type: String, body: ByteArray) = cat(be32(8L + body.size), asc(type), body)
    private fun fullbox(type: String, ver: Int, flags: Int, body: ByteArray) = box(type, cat(bytes(ver, flags shr 16, flags shr 8, flags), body))

    private fun heif(brand: String, codecProp: ByteArray?): ByteArray {
        val ftyp = box("ftyp", cat(asc(brand), be32(0), asc("mif1"), asc(brand)))
        val infe1 = fullbox("infe", 2, 0, cat(be16(1), be16(0), asc(if (brand == "avif") "av01" else "hvc1"), bytes(0)))
        val infe2 = fullbox("infe", 2, 0, cat(be16(2), be16(0), asc(if (brand == "avif") "av01" else "hvc1"), bytes(0)))
        val infe3 = fullbox("infe", 2, 0, cat(be16(3), be16(0), asc("Exif"), bytes(0)))
        val iinf = fullbox("iinf", 0, 0, cat(be16(3), infe1, infe2, infe3))
        val ispeBig = fullbox("ispe", 0, 0, cat(be32(4032), be32(3024)))
        val ispeSmall = fullbox("ispe", 0, 0, cat(be32(320), be32(240)))
        val pixi = fullbox("pixi", 0, 0, bytes(3, 8, 8, 8))
        val irot = box("irot", bytes(1))
        val colr = box("colr", cat(asc("nclx"), be16(1), be16(13), be16(6), bytes(0x80)))
        val props = mutableListOf(ispeBig, ispeSmall, pixi, irot, colr)
        if (codecProp != null) props.add(codecProp)
        val ipco = box("ipco", cat(*props.toTypedArray()))
        val assoc1 = mutableListOf(1, 3, 4, 5)
        if (codecProp != null) assoc1.add(6)
        val ipma = fullbox("ipma", 0, 0, cat(be32(2), be16(1), bytes(assoc1.size), bytes(*assoc1.toIntArray()), be16(2), bytes(1), bytes(2)))
        val iprp = box("iprp", cat(ipco, ipma))
        val pitm = fullbox("pitm", 0, 0, be16(1))
        val hdlr = fullbox("hdlr", 0, 0, cat(be32(0), asc("pict"), ByteArray(12), bytes(0)))
        val meta = fullbox("meta", 0, 0, cat(hdlr, pitm, iinf, iprp))
        return cat(ftyp, meta, box("mdat", ByteArray(8)))
    }

    fun heicFacts() {
        val b = heif("heic", box("hvcC", bytes(1, 1, 0x60, 0, 0, 0)))
        val r = run(IsobmffImageInspector, b, "IMG_1.HEIC")!!
        assertEquals("heic", r.formatId)
        r.has("Major brand", "HEIC (HEVC still image)")
        r.has("Dimensions", "4032×3024")
        r.has("Channels / bit depth", "3 channels, 8/8/8 bits")
        r.has("Rotation", "90° counter-clockwise")
        r.has("Items", "3"); r.has("Image items", "2"); r.has("Item types", "Exif×1, hvc1×2")
        r.has("Primary item ID", "1")
        r.has("Codec", "HEVC (profile idc 1)")
        assertTrue(r.fact("Colour info")!!.startsWith("nclx"))
        assertTrue(r.warnings.toString(), r.warnings.isEmpty())
    }

    fun avifFacts() {
        val b = heif("avif", box("av1C", bytes(0x81, 0x00, 0x40, 0)))
        val r = run(IsobmffImageInspector, b, "x.avif")!!
        assertEquals("avif", r.formatId)
        r.has("Major brand", "AVIF still image")
        r.has("Dimensions", "4032×3024")
        r.has("Codec", "AV1 (profile 0, level index 0, 10-bit)")
        assertEquals("avif", run(IsobmffImageInspector, b, null)!!.formatId)
    }

    fun isobmffRejectsVideoAndHostile() {
        val mp4 = box("ftyp", cat(asc("isom"), be32(0), asc("isom"), asc("mp41")))
        assertNull(run(IsobmffImageInspector, mp4))
        val b = heif("heic", null)
        assertNoThrowOnPrefixes(IsobmffImageInspector, b)
        val ftyp = box("ftyp", cat(asc("heic"), be32(0), asc("mif1")))
        val hostile = cat(ftyp, cat(be32(1), asc("meta"), be32(0xFFFFFFFFL), be32(0xFFFFFFFFL)), cat(be32(0xFFFFFFFFL), asc("iprp")))
        assertNotNull(run(IsobmffImageInspector, hostile))
        val hostileMeta = cat(ftyp, box("meta", cat(bytes(0, 0, 0, 0), box("iinf", cat(bytes(0, 0, 0, 0), be16(0xFFFF))), box("iprp", box("ipma", cat(bytes(0, 0, 0, 0), be32(0xFFFFFFFFL), ByteArray(40)))))))
        assertNotNull(run(IsobmffImageInspector, hostileMeta))
        assertHugeSourceOk(IsobmffImageInspector, cat(ftyp, be32(0), asc("meta")))
    }

    // =====================================================================
    // VERIFY (adversarial) section
    // =====================================================================
    private class CountingSource(private val inner: ByteSource) : ByteSource {
        override val size: Long get() = inner.size
        var bytes = 0L; var calls = 0
        override fun read(offset: Long, length: Int): ByteArray {
            calls++; if (length > 0) bytes += length
            return inner.read(offset, length)
        }
    }

    /** Virtual source: byte at position p is gen(p). */
    private class GenSource(override val size: Long, private val gen: (Long) -> Int) : ByteSource {
        override fun read(offset: Long, length: Int): ByteArray {
            if (offset < 0 || offset >= size || length <= 0) return ByteArray(0)
            val n = minOf(length.toLong(), size - offset).toInt()
            return ByteArray(n) { gen(offset + it).toByte() }
        }
    }

    private val failures = ArrayList<String>()
    private val seen = HashSet<String>()

    private fun name(i: FormatInspector) = i::class.simpleName ?: "?"

    private fun check(i: FormatInspector, src: ByteSource, fileName: String?, label: String): InspectionResult? {
        val c = CountingSource(src)
        val t0 = System.nanoTime()
        var r: InspectionResult? = null
        try { r = i.inspect(c, fileName) } catch (t: Throwable) {
            val k = "${name(i)}|throw"; if (seen.add(k)) failures.add("${name(i)} THREW on $label: $t")
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (c.bytes > FormatInspector.MAX_READ_BYTES) { val k = "${name(i)}|bytes"; if (seen.add(k)) failures.add("${name(i)} requested ${c.bytes} bytes (> MAX_READ_BYTES) on $label") }
        if (c.calls > 20000) { val k = "${name(i)}|calls"; if (seen.add(k)) failures.add("${name(i)} made ${c.calls} read calls (> 20000) on $label") }
        if (ms > 1500) { val k = "${name(i)}|time"; if (seen.add(k)) failures.add("${name(i)} took $ms ms on $label") }
        return r
    }

    private fun check(i: FormatInspector, b: ByteArray, fileName: String?, label: String) = check(i, ByteArraySource(b), fileName, label)

    private fun fixtures(): List<Triple<String, ByteArray, String?>> {
        val l = ArrayList<Triple<String, ByteArray, String?>>()
        l += Triple("png", png(), "a.png")
        for (le in listOf(true, false)) l += Triple("tiff$le", tiff(le), "a.tif")
        l += Triple("jpeg", jpeg(), "a.jpg"); l += Triple("jpegBase", jpeg(false), "a.jpg")
        l += Triple("gif", gif(), "a.gif")
        l += Triple("webpAnim", webpAnim(), "a.webp")
        l += Triple("webpL", riffWebp(rchunk("VP8L", cat(bytes(0x2F), le32((99L) or (49L shl 14) or (1L shl 28)), ByteArray(8)))), "a.webp")
        l += Triple("webpLossy", riffWebp(rchunk("VP8 ", cat(bytes(0x10, 0, 0), bytes(0x9D, 0x01, 0x2A), le16(320), le16(240), ByteArray(4)))), "a.webp")
        l += Triple("bmp", bmp(), "a.bmp"); l += Triple("bmpOs2", cat(asc("BM"), le32(30), le32(0), le32(26), le32(12), le16(5), le16(7), le16(1), le16(8), ByteArray(4)), "a.bmp")
        l += Triple("ico", ico(), "a.ico"); l += Triple("cur", ico(2), "a.cur")
        l += Triple("psd", psd(), "a.psd")
        l += Triple("dds", dds(), "a.dds"); l += Triple("dds10", dds("DX10", true), "a.dds")
        l += Triple("tga", tga(), "a.tga")
        l += Triple("svg", svgText.toByteArray(), "a.svg")
        l += Triple("heic", heif("heic", box("hvcC", bytes(1, 1, 0x60, 0, 0, 0))), "a.heic")
        l += Triple("avif", heif("avif", box("av1C", bytes(0x81, 0x00, 0x40, 0))), "a.avif")
        // DNG with SubIFD
        val le = true
        val e0 = listOf(short(le, 0x0100, 256), short(le, 0x0101, 171), E(0xC612, 1, 4, bytes(1, 4, 0, 0)), E(0x014A, 4, 1, u32(le, 0)))
        val subAt = 8 + ifdBlock(le, 8, e0, 0).size
        val e0b = listOf(short(le, 0x0100, 256), short(le, 0x0101, 171), E(0xC612, 1, 4, bytes(1, 4, 0, 0)), E(0x014A, 4, 1, u32(le, subAt.toLong())))
        val sub = ifdBlock(le, subAt, listOf(long(le, 0x00FE, 0), short(le, 0x0100, 6000), short(le, 0x0101, 4000), short(le, 0x0103, 7), short(le, 0x0106, 32803)), 0)
        l += Triple("dng", cat(header(le), ifdBlock(le, 8, e0b, 0), sub), "a.dng")
        return l
    }

    private fun mutations(b: ByteArray): Sequence<Pair<String, ByteArray>> = sequence {
        val step = if (b.size > 700) 3 else 1
        var p = 0
        while (p < b.size) {
            yield("flip@$p" to b.copyOf().also { it[p] = (it[p].toInt() xor 0xFF).toByte() })
            yield("zero@$p" to b.copyOf().also { it[p] = 0 })
            yield("7f@$p" to b.copyOf().also { it[p] = 0x7F })
            for (w in listOf(2, 4, 8)) if (p + w <= b.size) {
                yield("ff$w@$p" to b.copyOf().also { for (k in 0 until w) it[p + k] = 0xFF.toByte() })
                yield("zero$w@$p" to b.copyOf().also { for (k in 0 until w) it[p + k] = 0 })
            }
            if (p + 4 <= b.size) {
                yield("7fffffffBE@$p" to b.copyOf().also { it[p] = 0x7F; it[p + 1] = -1; it[p + 2] = -1; it[p + 3] = -1 })
                yield("7fffffffLE@$p" to b.copyOf().also { it[p] = -1; it[p + 1] = -1; it[p + 2] = -1; it[p + 3] = 0x7F })
                yield("80000000BE@$p" to b.copyOf().also { it[p] = 0x80.toByte(); it[p + 1] = 0; it[p + 2] = 0; it[p + 3] = 0 })
            }
            p += step
        }
    }

    private fun report(title: String) {
        if (failures.isNotEmpty()) fail("$title: ${failures.size} failures:\n" + failures.take(40).joinToString("\n"))
    }

    @Test fun vPrefixesAllInspectors() {
        failures.clear(); seen.clear()
        for ((fn, b, nm) in fixtures()) for (i in ImageInspectors.all) {
            for (n in 0..b.size) check(i, b.copyOf(n), nm, "prefix $n of $fn")
        }
        report("prefixes")
    }

    @Test fun vMutationsAllInspectors() {
        failures.clear(); seen.clear()
        for ((fn, b, nm) in fixtures()) for (i in ImageInspectors.all) {
            for ((ml, mb) in mutations(b)) check(i, mb, nm, "$fn $ml")
        }
        report("mutations")
    }

    @Test fun vFilledBuffers() {
        failures.clear(); seen.clear()
        val sizes = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 11, 12, 13, 16, 17, 18, 19, 26, 27, 29, 30, 31, 54, 64, 100, 127, 128, 129, 147, 148, 149, 255, 256, 1000, 4096, 70000, 300000)
        val nonNull = ArrayList<String>()
        for (i in ImageInspectors.all) for (s in sizes) for (v in listOf(0, 0xFF)) {
            val b = ByteArray(s) { v.toByte() }
            val r = check(i, b, null, "filled 0x${v.toString(16)} x $s")
            if (r != null) nonNull.add("${name(i)} returned non-null for ${s} bytes of 0x${v.toString(16)}")
        }
        report("filled")
        assertTrue("non-null on filled buffers: " + nonNull.take(10), nonNull.isEmpty())
    }

    @Test fun vRandomWithMagic() {
        failures.clear(); seen.clear()
        val rnd = java.util.Random(7)
        for ((fn, b, nm) in fixtures()) for (i in ImageInspectors.all) {
            for (round in 0 until 150) {
                val k = intArrayOf(2, 3, 4, 6, 8, 12, 16, 18, 26, 30)[rnd.nextInt(10)].coerceAtMost(b.size)
                val tail = ByteArray(rnd.nextInt(400)); rnd.nextBytes(tail)
                check(i, cat(b.copyOf(k), tail), nm, "magic-random $fn k=$k round $round")
            }
        }
        report("random-magic")
    }

    @Test fun vCrossNonMatchReturnsNull() {
        val msgs = ArrayList<String>()
        for ((fn, b, _) in fixtures()) for (i in ImageInspectors.all) {
            val r = i.inspect(ByteArraySource(b), null)
            if (r != null) msgs.add("$fn -> ${name(i)} (${r.formatId}): ${r.summary}")
        }
        println("CROSS CLAIMS:\n" + msgs.joinToString("\n"))
    }

    // ---- hostile large virtual sources ----
    private fun period(sizeBytes: Long, head: ByteArray, unit: ByteArray): GenSource =
        GenSource(sizeBytes) { p -> if (p < head.size) head[p.toInt()].toInt() else unit[((p - head.size) % unit.size).toInt()].toInt() }

    @Test fun vHostileManyTextChunksPng() {
        failures.clear(); seen.clear()
        val head = cat(PNG_SIG, chunk("IHDR", cat(be32(1), be32(1), bytes(8, 2, 0, 0, 0))))
        val unit = cat(be32(65536), asc("tEXt"), ByteArray(65536) { 'A'.code.toByte() }, be32(0))
        check(PngInspector, period(50L shl 30, head, unit), "x.png", "50GB of 64K tEXt chunks lacking NUL")
        report("png many text")
    }

    @Test fun vHostileManyTinyChunksPng() {
        failures.clear(); seen.clear()
        val head = cat(PNG_SIG, chunk("IHDR", cat(be32(1), be32(1), bytes(8, 2, 0, 0, 0))))
        val unit = chunk("gAMA", be32(45455))
        check(PngInspector, period(50L shl 30, head, unit), "x.png", "50GB of gAMA chunks")
        report("png tiny chunks")
    }

    @Test fun vHostileJpegManyBigSegments() {
        failures.clear(); seen.clear()
        val head = bytes(0xFF, 0xD8)
        val unit = cat(bytes(0xFF, 0xEF, 0xFF, 0xFF), ByteArray(65533))
        check(JpegInspector, period(50L shl 30, head, unit), "x.jpg", "50GB of 64K APP15 segments")
        report("jpeg big segments")
    }

    @Test fun vHostileWebpManyChunks() {
        failures.clear(); seen.clear()
        val head = cat(asc("RIFF"), le32(0xFFFFFFF0L), asc("WEBP"), rchunk("VP8X", cat(bytes(0x12, 0, 0, 0), le24(63), le24(31))))
        val unit = rchunk("ANMF", cat(le24(0), le24(0), le24(63), le24(31), le24(10), bytes(0), ByteArray(4)))
        check(WebpInspector, period(50L shl 30, head, unit), "x.webp", "50GB of ANMF chunks")
        report("webp many chunks")
    }

    @Test fun vHostileGifLongBlocks() {
        failures.clear(); seen.clear()
        val head = cat(asc("GIF89a"), le16(1), le16(1), bytes(0, 0, 0))
        val unit = cat(bytes(0x21, 0xFE, 255), ByteArray(255) { 'A'.code.toByte() }, bytes(0))
        check(GifInspector, period(50L shl 30, head, unit), "x.gif", "50GB of comment extensions")
        val unit2 = cat(bytes(0x2C), ByteArray(9), bytes(2), bytes(255), ByteArray(255) { 1 }, bytes(0))
        check(GifInspector, period(50L shl 30, head, unit2), "x.gif", "50GB of frames")
        report("gif long blocks")
    }

    @Test fun vHostileTiffChain() {
        failures.clear(); seen.clear()
        // 300 IFDs each 512 entries, chained
        val le = true
        val entries = (0 until 512).map { short(le, 0x0100 + it, 1) }
        val one = ifdBlock(le, 8, entries, 0).size
        val out = ByteArrayOutputStream(); out.write(header(le))
        for (k in 0 until 300) out.write(ifdBlock(le, 8 + k * one, entries, 8 + (k + 1) * one))
        check(TiffInspector, out.toByteArray(), "x.tif", "300 chained 512-entry IFDs")
        report("tiff chain")
    }

    @Test fun vHostileSvgRegex() {
        failures.clear(); seen.clear()
        val cases = listOf(
            "<svg " + "a".repeat(20000),
            "<svg " + "a=".repeat(8000),
            "<svg " + "a b ".repeat(5000),
            "<svg " + "a    ".repeat(4000) + "=",
            "<svg " + "a=\"b\" ".repeat(3000),
            "<svg width=\"1\">" + "<g>".repeat(80000),
            "<svg>" + "<title>".repeat(30000),
            "<svg viewBox=\"" + "1 ".repeat(8000) + "\">",
            "<svg>" + " ".repeat(250000) + "<title>x",
        )
        for ((n, c) in cases.withIndex()) check(SvgInspector, c.toByteArray(), "x.svg", "svg case $n")
        report("svg regex")
    }

    @Test fun vFalsePositives() {
        val msgs = ArrayList<String>()
        fun probe(label: String, i: FormatInspector, b: ByteArray) { val r = i.inspect(ByteArraySource(b), null); if (r != null) msgs.add("$label -> ${name(i)}: ${r.summary} ${r.facts.take(4)}") }
        val text = "BMW service report 2024: engine inspected and everything is fine, thank you very much.".toByteArray()
        probe("text starting BM", BmpInspector, text)
        probe("html with inline svg", SvgInspector, "<!DOCTYPE html><html><body><p>Hello</p><svg width=\"10\" height=\"10\"><rect/></svg></body></html>".toByteArray())
        probe("markdown mentioning svg", SvgInspector, "Use the <svg> tag like this: <svg width=\"3\"> in HTML".toByteArray())
        probe("text starting DDS ", DdsInspector, ("DDS file notes: " + "x".repeat(200)).toByteArray())
        probe("ico magic only", IcoInspector, bytes(0, 0, 1, 0, 0, 0))
        probe("ico magic count 0 + junk", IcoInspector, cat(bytes(0, 0, 1, 0, 0, 0), ByteArray(100) { 0x41 }))
        probe("8BPS only", PsdInspector, cat(asc("8BPS"), ByteArray(22)))
        probe("ftyp heif brand mif1 w/o meta", IsobmffImageInspector, box("ftyp", cat(asc("mif1"), be32(0), asc("mif1"))))
        println("FALSE POSITIVE PROBES:\n" + msgs.joinToString("\n"))
        assertTrue("false positives: $msgs", msgs.isEmpty())
    }

    @Test fun vFactChecks() {
        // value checks for fields the builder did not assert
        val bmpR = run(BmpInspector, bmp())!!
        println("BMP facts " + bmpR.facts)
        val tg = run(TgaInspector, cat(bytes(11, 0, 2), ByteArray(5), le16(0), le16(0), le16(4), le16(4), bytes(24, 0), asc("my.file.tga"), ByteArray(48)), "a.tga")!!
        println("TGA ID fact: " + tg.fact("Image ID"))
        assertEquals("my.file.tga", tg.fact("Image ID"))
    }
}
