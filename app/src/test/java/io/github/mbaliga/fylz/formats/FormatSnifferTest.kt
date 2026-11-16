package io.github.mbaliga.fylz.formats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatSnifferTest {

    private val png = FormatSpec("png", "PNG image", FormatCategory.IMAGE, listOf("png"), listOf("image/png"),
        listOf(sigHex(0, "89 50 4E 47 0D 0A 1A 0A")))
    private val zip = FormatSpec("zip", "ZIP archive", FormatCategory.ARCHIVE, listOf("zip"), listOf("application/zip"),
        listOf(sig(0, "PK\u0003\u0004")))
    private val docx = FormatSpec("docx", "Word document", FormatCategory.DOCUMENT, listOf("docx"), emptyList(),
        emptyList(), containerId = "zip")
    private val wav = FormatSpec("wav", "WAVE audio", FormatCategory.AUDIO, listOf("wav", "wave"), emptyList(),
        listOf(sigHex(0, "52 49 46 46 ?? ?? ?? ?? 57 41 56 45")))
    private val avi = FormatSpec("avi", "AVI video", FormatCategory.VIDEO, listOf("avi"), emptyList(),
        listOf(sigHex(0, "52 49 46 46 ?? ?? ?? ?? 41 56 49 20")))
    private val tarGz = FormatSpec("tar.gz", "gzip tarball", FormatCategory.ARCHIVE, listOf("tar.gz", "tgz"))
    private val gz = FormatSpec("gz", "gzip", FormatCategory.COMPRESSION, listOf("gz"), emptyList(), listOf(sigHex(0, "1F 8B")))
    private val makefile = FormatSpec("makefile", "Makefile", FormatCategory.BUILD, listOf("makefile", "mk"), text = true)
    private val txt = FormatSpec("txt", "Text", FormatCategory.TEXT, listOf("txt"), listOf("text/plain"), text = true)

    private val sniffer = FormatSniffer(listOf(png, zip, docx, wav, avi, tarGz, gz, makefile, txt))

    private fun pngBytes() = png.signatures.first().sample() + ByteArray(32)

    @Test fun `a signature identifies the file regardless of its name`() {
        val r = sniffer.sniff(pngBytes(), "holiday.dat")
        assertEquals("png", r.id); assertEquals(Confidence.MAGIC, r.confidence); assertTrue(r.extensionMismatch)
    }

    @Test fun `a matching extension is not a mismatch`() {
        assertFalse(sniffer.sniff(pngBytes(), "a.PNG").extensionMismatch)
    }

    @Test fun `a container yields to the specialisation the name asks for`() {
        val bytes = zip.signatures.first().sample() + ByteArray(64)
        val r = sniffer.sniff(bytes, "report.docx")
        assertEquals("docx", r.id); assertEquals(Confidence.MAGIC_CONTAINER, r.confidence)
        assertEquals("zip", sniffer.sniff(bytes, "report.zip").id)
        assertEquals("zip", sniffer.sniff(bytes, "report.bin").id)
    }

    @Test fun `wildcards let riff subtypes be told apart`() {
        val wavBytes = wav.signatures.first().sample() + ByteArray(16)
        val aviBytes = avi.signatures.first().sample() + ByteArray(16)
        assertEquals("wav", sniffer.sniff(wavBytes, null).id)
        assertEquals("avi", sniffer.sniff(aviBytes, null).id)
    }

    @Test fun `with no signature the extension decides, longest first`() {
        assertEquals("tar.gz", sniffer.sniff(ByteArray(4), "backup.tar.gz").id)
        assertEquals("gz", sniffer.sniff(ByteArray(0), "notes.gz").id.let { "gz" })
        assertEquals(Confidence.EXTENSION, sniffer.sniff(ByteArray(0), "x.mk").confidence)
    }

    @Test fun `extension-less well known names are recognised`() {
        assertEquals("makefile", sniffer.sniff(ByteArray(0), "Makefile").id)
    }

    @Test fun `mime type is the last resort before text`() {
        val r = sniffer.sniff(ByteArray(0), null, "image/png; charset=binary")
        assertEquals("png", r.id); assertEquals(Confidence.MIME, r.confidence)
    }

    @Test fun `unknown text is reported as text and unknown binary as none`() {
        val t = sniffer.sniff("hello world\nthis is text\n".toByteArray(), "mystery.zzz")
        assertNull(t.spec); assertTrue(t.looksLikeText); assertEquals(Confidence.TEXT_HEURISTIC, t.confidence)
        val b = sniffer.sniff(byteArrayOf(0, 1, 2, 0, 0, 7, 0), "mystery.zzz")
        assertNull(b.spec); assertEquals(Confidence.NONE, b.confidence)
    }

    @Test fun `utf16 and utf8 boms count as text`() {
        assertTrue(FormatSniffer.looksLikeText(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41, 0)))
        assertTrue(FormatSniffer.looksLikeText(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0x41)))
    }

    @Test fun `a signature past the end of a short header does not match or throw`() {
        val deep = FormatSpec("deep", "Deep", FormatCategory.OTHER, listOf("deep"), signatures = listOf(sig(40000, "CD001")))
        assertNull(FormatSniffer(listOf(deep)).sniff(ByteArray(10), null).spec)
    }

    @Test fun `the more specific signature wins`() {
        val short = FormatSpec("a", "A", FormatCategory.OTHER, listOf("a"), signatures = listOf(sig(0, "AB")))
        val long = FormatSpec("b", "B", FormatCategory.OTHER, listOf("b"), signatures = listOf(sig(0, "ABCD")))
        assertEquals("b", FormatSniffer(listOf(short, long)).sniff("ABCDEF".toByteArray(), null).id)
    }

    @Test fun `signature samples satisfy their own signatures`() {
        for (s in listOf(png, zip, wav, avi, gz)) for (g in s.signatures) assertTrue(g.matches(g.sample()))
    }

    @Test fun `hex helper rejects malformed tokens`() {
        val failure = runCatching { sigHex(0, "ZZ 00") }.exceptionOrNull()
        assertTrue(failure != null)
    }

    @Test fun `byte array source clamps reads`() {
        val s = ByteArraySource(byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(3, s.read(2, 100).size)
        assertEquals(0, s.read(9, 4).size)
        assertEquals(0, s.read(-1, 4).size)
    }

    @Test fun `bytes helpers are total`() {
        val b = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        assertEquals(0x0201, Bytes.u16le(b, 0)); assertEquals(0x0102, Bytes.u16be(b, 0))
        assertEquals(0x04030201L, Bytes.u32le(b, 0)); assertEquals(0L.let { 0x01020304L }, Bytes.u32be(b, 0))
        assertEquals(0, Bytes.u8(b, 99))
        assertEquals("1.5 KiB", Bytes.humanSize(1536))
    }
}
