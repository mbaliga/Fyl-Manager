package io.github.mbaliga.fylz.data

import io.github.mbaliga.fylz.data.StructuredDocumentReaders.openerOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredDocumentReadersTest {

    private fun zipOf(vararg entries: Pair<String, String>): () -> ByteArrayInputStream {
        val bytes = ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                entries.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
            }
            out.toByteArray()
        }
        return { ByteArrayInputStream(bytes) }
    }

    // ── Word processing ──────────────────────────────────────────────────────────────

    @Test
    fun `docx text comes out of document xml with paragraph breaks`() {
        val docx = zipOf(
            "word/document.xml" to
                "<w:document><w:body><w:p><w:r><w:t>First paragraph.</w:t></w:r></w:p>" +
                "<w:p><w:r><w:t>Second, with &amp; and &lt;tags&gt;.</w:t></w:r></w:p></w:body></w:document>",
            "word/styles.xml" to "<ignored/>",
        )
        val text = StructuredDocumentReaders.readWordProcessing("docx", docx).text
        assertTrue(text.contains("First paragraph."))
        assertTrue(text.contains("Second, with & and <tags>."))
        assertTrue(
            "paragraphs must break",
            text.indexOf("First paragraph.") < text.indexOf("\n") &&
                text.contains("\n"),
        )
    }

    @Test
    fun `odt text comes out of content xml`() {
        val odt = zipOf(
            "content.xml" to "<office:body><text:p>Hallo Welt</text:p><text:p>Zweiter Absatz</text:p></office:body>",
        )
        val text = StructuredDocumentReaders.readWordProcessing("odt", odt).text
        assertTrue(text.contains("Hallo Welt"))
        assertTrue(text.contains("Zweiter Absatz"))
    }

    @Test
    fun `rtf strips control words and keeps line structure`() {
        val rtf = openerOf("""{\rtf1\ansi\deff0 {\fonttbl{\f0 Arial;}}\f0\fs24 Hello\par World \'e9 caf\'e9}""")
        val text = StructuredDocumentReaders.readWordProcessing("rtf", rtf).text
        assertTrue(text.contains("Hello"))
        assertTrue(text.contains("World"))
        assertFalse(text.contains("\\rtf1"))
        assertFalse(text.contains("fonttbl"))
    }

    @Test(expected = IllegalStateException::class)
    fun `a zip with no document part is refused with a reason`() {
        StructuredDocumentReaders.readWordProcessing("docx", zipOf("word/styles.xml" to "<x/>"))
    }

    // ── EML ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `plain single-part mail parses headers and body`() {
        val eml = openerOf(
            "From: Ada <ada@example.com>\r\n" +
                "To: Charles <charles@example.com>\r\n" +
                "Subject: Engine notes,\r\n folded across lines\r\n" +
                "Date: Sat, 22 Aug 2026 10:00:00 +0530\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "The analytical engine weaves algebraic patterns.\r\n",
        )
        val mail = StructuredDocumentReaders.readEml(eml)
        assertEquals("Ada <ada@example.com>", mail.from)
        assertEquals("Engine notes, folded across lines", mail.subject)
        assertTrue(mail.bodyText.contains("weaves algebraic patterns"))
        assertTrue(mail.attachmentNames.isEmpty())
    }

    @Test
    fun `multipart mail prefers the plain part and names attachments without opening them`() {
        val eml = openerOf(
            "From: a@example.com\n" +
                "Subject: Report\n" +
                "Content-Type: multipart/mixed; boundary=\"XYZ\"\n" +
                "\n" +
                "--XYZ\n" +
                "Content-Type: text/plain\n" +
                "\n" +
                "See attached.\n" +
                "--XYZ\n" +
                "Content-Type: application/pdf; name=\"q3.pdf\"\n" +
                "Content-Disposition: attachment; filename=\"q3.pdf\"\n" +
                "\n" +
                "%PDF-fake-bytes\n" +
                "--XYZ--\n",
        )
        val mail = StructuredDocumentReaders.readEml(eml)
        assertEquals("See attached.", mail.bodyText)
        assertEquals(listOf("q3.pdf"), mail.attachmentNames)
        assertFalse("attachment bytes must never reach the body", mail.bodyText.contains("%PDF"))
    }

    @Test
    fun `quoted printable bodies decode`() {
        val eml = openerOf(
            "Subject: QP\nContent-Type: text/plain\nContent-Transfer-Encoding: quoted-printable\n\ncaf=C3=A9 soft=\nwrap\n",
        )
        val body = StructuredDocumentReaders.readEml(eml).bodyText
        assertTrue(body.contains("softwrap"))
        assertFalse(body.contains("=C3"))
    }

    @Test
    fun `html-only mail falls back to stripped html`() {
        val eml = openerOf(
            "Subject: H\nContent-Type: text/html\n\n<html><body><p>Bold <b>claim</b>.</p></body></html>\n",
        )
        assertEquals("Bold claim.", StructuredDocumentReaders.readEml(eml).bodyText)
    }

    // ── EPUB ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `epub chapters concatenate in archive order with markup stripped`() {
        val epub = zipOf(
            "mimetype" to "application/epub+zip",
            "OEBPS/ch1.xhtml" to "<html><body><h1>Chapter One</h1><p>It begins.</p></body></html>",
            "OEBPS/ch2.xhtml" to "<html><body><p>It continues.</p><script>alert(1)</script></body></html>",
        )
        val text = StructuredDocumentReaders.readEpub(epub).text
        assertTrue(text.contains("Chapter One"))
        assertTrue(text.indexOf("It begins.") < text.indexOf("It continues."))
        assertFalse("scripts must be stripped, not printed", text.contains("alert"))
    }

    @Test(expected = IllegalStateException::class)
    fun `an epub with no chapters is refused with a reason`() {
        StructuredDocumentReaders.readEpub(zipOf("mimetype" to "application/epub+zip"))
    }

    // ── ICS / VCF ────────────────────────────────────────────────────────────────────

    @Test
    fun `calendar events become cards with readable times`() {
        val ics = openerOf(
            "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nSUMMARY:Design revie\r\n w\r\n" +
                "DTSTART;TZID=Asia/Kolkata:20260830T140000\r\nLOCATION:Studio\\, 4th floor\r\n" +
                "END:VEVENT\r\nEND:VCALENDAR\r\n",
        )
        val cards = StructuredDocumentReaders.readCalendar(ics)
        assertEquals(1, cards.size)
        assertEquals("Design review", cards[0].title)
        assertTrue(cards[0].fields.any { it.label == "Starts" && it.value == "2026-08-30 14:00" })
        assertTrue(cards[0].fields.any { it.label == "Where" && it.value == "Studio, 4th floor" })
    }

    @Test
    fun `contacts become cards and the overflow is counted not hidden`() {
        val vcf = openerOf(
            (1..10).joinToString("") { index ->
                "BEGIN:VCARD\nFN:Person $index\nTEL;TYPE=CELL:+91-$index\nEND:VCARD\n"
            },
        )
        val cards = StructuredDocumentReaders.readContacts(vcf, maxCards = 3)
        assertEquals(3, cards.size)
        assertEquals("Person 1", cards[0].title)
        assertEquals(7, cards[0].more)
        assertTrue(cards[0].fields.any { it.label == "Phone" })
    }

    // ── Bounds ───────────────────────────────────────────────────────────────────────

    @Test
    fun `oversized text is truncated and says so`() {
        val big = "A".repeat(StructuredDocumentReaders.MAX_TEXT_CHARS + 10_000)
        val rtf = openerOf("{\\rtf1 $big}")
        val extracted = StructuredDocumentReaders.readWordProcessing("rtf", rtf)
        assertTrue(extracted.truncated)
        assertEquals(StructuredDocumentReaders.MAX_TEXT_CHARS, extracted.text.length)
    }
}
