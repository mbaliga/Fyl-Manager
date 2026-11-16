package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentInspectorsVerifyTest {

    // ---------------------------------------------------------------- helpers

    private fun InspectionResult.f(label: String): String? = facts.firstOrNull { it.label == label }?.value

    private fun run(i: FormatInspector, b: ByteArray, name: String? = null): InspectionResult? =
        i.inspect(ByteArraySource(b), name)

    private fun prefixes(i: FormatInspector, b: ByteArray, name: String? = null, step: Int = 1) {
        var n = 0
        while (n <= b.size) {
            i.inspect(ByteArraySource(b.copyOf(n)), name)
            n += step
        }
    }

    private fun zip(entries: List<Triple<String, ByteArray, Boolean>>): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z ->
            for ((name, data, stored) in entries) {
                val e = ZipEntry(name)
                if (stored) {
                    e.method = ZipEntry.STORED
                    e.size = data.size.toLong()
                    e.compressedSize = data.size.toLong()
                    val c = CRC32(); c.update(data); e.crc = c.value
                }
                z.putNextEntry(e)
                z.write(data)
                z.closeEntry()
            }
        }
        return bo.toByteArray()
    }

    private fun e(name: String, text: String, stored: Boolean = false) = Triple(name, text.toByteArray(Charsets.UTF_8), stored)

    // ---------------------------------------------------------------- registry

    fun registryListsEveryInspector() {
        val ids = DocumentInspectors.all.flatMap { it.formatIds }.toSet()
        assertTrue(ids.containsAll(setOf("pdf", "docx", "xlsx", "pptx", "odt", "ods", "odp", "epub", "rtf", "doc", "xls", "ppt", "mobi", "azw")))
        assertEquals(DocumentInspectors.all.size, DocumentInspectors.all.toSet().size)
    }

    fun garbageIsRejectedByAll() {
        val junk = ByteArray(2000) { (it * 31 + 7).toByte() }
        for (i in DocumentInspectors.all) {
            assertNull(i.toString(), run(i, junk))
            assertNull(i.toString(), run(i, ByteArray(0)))
        }
    }

    // ---------------------------------------------------------------- PDF

    private fun pdfBytes(extraTrailer: String = "", extraObjects: String = ""): ByteArray {
        val s = "%PDF-1.7\n%âãÏÓ\n" +
            "1 0 obj\n<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [] >> >>\nendobj\n" +
            "2 0 obj\n<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>\nendobj\n" +
            "3 0 obj\n<< /Type /Page /Parent 2 0 R >>\nendobj\n" +
            "4 0 obj\n<< /Type /Page /Parent 2 0 R >>\nendobj\n" +
            "5 0 obj\n<< /Title <FEFF00480065006C006C006F00200057006F0072006C0064> /Author (Jane \\(Q\\) Doe) " +
            "/Creator (Writer) /Producer (Fylz Test) /CreationDate (D:20210304050607+01'00') " +
            "/ModDate (D:20220101120000Z) /Subject (caf\\351) /Keywords (a, b) >>\nendobj\n" + extraObjects +
            "trailer\n<< /Size 6 /Root 1 0 R /Info 5 0 R $extraTrailer >>\nstartxref\n0\n%%EOF\n"
        return s.toByteArray(Charsets.ISO_8859_1)
    }

    fun pdfBasicFacts() {
        val r = run(DocumentInspectorsPdf, pdfBytes(), "x.pdf")!!
        assertEquals("pdf", r.formatId)
        assertEquals("Hello World", r.title)
        assertEquals("1.7", r.f("PDF version"))
        assertEquals("2", r.f("Pages"))
        assertEquals("page tree /Count", r.f("Page count source"))
        assertEquals("Jane (Q) Doe", r.f("Author"))
        assertEquals("café", r.f("Subject"))
        assertEquals("a, b", r.f("Keywords"))
        assertEquals("Writer", r.f("Creator"))
        assertEquals("Fylz Test", r.f("Producer"))
        assertEquals("2021-03-04T05:06:07+01:00", r.f("Created"))
        assertEquals("2022-01-01T12:00:00Z", r.f("Modified"))
        assertEquals("no", r.f("Encrypted"))
        assertEquals("no", r.f("Linearized"))
        assertEquals("interactive form", r.f("Contains (in uncompressed objects)"))
        assertTrue(r.warnings.isEmpty())
    }

    fun pdfEncrypted() {
        val r = run(DocumentInspectorsPdf, pdfBytes("/Encrypt 6 0 R", "6 0 obj\n<< /Filter /Standard /V 2 /R 3 /Length 128 /P -4 >>\nendobj\n"))!!
        assertEquals("yes", r.f("Encrypted"))
        assertEquals("Standard", r.f("Encryption handler"))
        assertEquals("2", r.f("Encryption algorithm version (V)"))
        assertEquals("3", r.f("Encryption revision (R)"))
        assertEquals("RC4, variable key length", r.f("Encryption method"))
        assertTrue(r.summary!!.contains("encrypted"))
    }

    fun pdfLinearized() {
        fun build(l: String): ByteArray = ("%PDF-1.4\n1 0 obj\n<< /Linearized 1 /L $l /H [ 100 200 ] /O 5 /E 600 /N 3 /T 900 >>\nendobj\n" +
            "xref\n0 1\n0000000000 65535 f \ntrailer\n<< /Size 9 /Root 2 0 R >>\nstartxref\n0\n%%EOF\n").toByteArray()
        val len = build("000000").size
        val b = build("%06d".format(len))
        val r = run(DocumentInspectorsPdf, b)!!
        assertEquals("yes (fast web view)", r.f("Linearized"))
        assertEquals("3", r.f("Pages"))
        assertEquals("linearization hint /N", r.f("Page count source"))
        assertEquals(len.toString(), r.f("Linearization file length"))
        assertTrue(r.warnings.none { it.contains("differs") })
        val r2 = run(DocumentInspectorsPdf, b + "junk".toByteArray())!!
        assertTrue(r2.warnings.any { it.contains("differs") })
    }

    fun pdfDecodesPdfDocEncoding() {
        assertEquals("•€é", DocumentInspectorsPdf.decodeText(byteArrayOf(0x80.toByte(), 0xA0.toByte(), 0xE9.toByte())))
        assertEquals("—", DocumentInspectorsPdf.decodeText(byteArrayOf(0x84.toByte())))
        assertEquals("A", DocumentInspectorsPdf.decodeText(byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0, 0x41)))
        assertEquals("2021-03", DocumentInspectorsPdf.formatDate("D:202103"))
        assertEquals("garbage", DocumentInspectorsPdf.formatDate("garbage"))
    }

    fun pdfWithoutEofWarns() {
        val b = pdfBytes()
        val cut = b.copyOf(b.size - 20)
        val r = run(DocumentInspectorsPdf, cut)!!
        assertTrue(r.warnings.any { it.contains("%%EOF") })
    }

    fun pdfTruncation() = prefixes(DocumentInspectorsPdf, pdfBytes("/Encrypt 6 0 R"))

    fun pdfHostileLengths() {
        val s = "%PDF-1.5\n1 0 obj\n<< /Type /Pages /Count 4294967295 /Kids [] >>\nendobj\n" +
            "2 0 obj\n<< /Title (unterminated \\" + "9".repeat(5000) + "\n" +
            "trailer\n<< /Info 2 0 R /Encrypt << /V 4294967295 /R 18446744073709551615 >> >>\n"
        val r = run(DocumentInspectorsPdf, s.toByteArray(Charsets.ISO_8859_1))!!
        assertEquals("4294967295", r.f("Pages"))
        // a very large mostly-empty file must still be bounded
        val big = object : ByteSource {
            override val size: Long = 50L * 1024 * 1024 * 1024
            override fun read(offset: Long, length: Int): ByteArray =
                if (offset == 0L) "%PDF-1.4\n".toByteArray().copyOf(minOf(length, 4 * 1024 * 1024)) else ByteArray(minOf(length, 4 * 1024 * 1024))
        }
        val r2 = DocumentInspectorsPdf.inspect(big, null)
        assertNotNull(r2)
        assertTrue(r2!!.warnings.any { it.contains("scanned") })
    }

    // ---------------------------------------------------------------- OOXML

    private val CT = "http://schemas.openxmlformats.org/package/2006/content-types"

    private fun docxBytes(): ByteArray = zip(listOf(
        e("[Content_Types].xml", "<?xml version=\"1.0\"?><Types xmlns=\"$CT\"><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>"),
        e("docProps/core.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><cp:coreProperties xmlns:cp=\"a\" xmlns:dc=\"b\" xmlns:dcterms=\"c\" xmlns:xsi=\"d\">" +
            "<dc:title>Quarterly &amp; Annual Report</dc:title><dc:subject>Finance</dc:subject><dc:creator>Ada Lovelace</dc:creator><cp:keywords>a, b</cp:keywords>" +
            "<cp:lastModifiedBy>Charles</cp:lastModifiedBy><cp:revision>3</cp:revision>" +
            "<dcterms:created xsi:type=\"dcterms:W3CDTF\">2020-01-02T03:04:05Z</dcterms:created><dcterms:modified xsi:type=\"dcterms:W3CDTF\">2021-02-03T04:05:06Z</dcterms:modified></cp:coreProperties>"),
        e("docProps/app.xml", "<Properties><Application>Microsoft Office Word</Application><AppVersion>16.0000</AppVersion><Pages>12</Pages><Words>3402</Words><Company>ACME</Company></Properties>"),
        e("word/document.xml", "<w:document><w:body><w:p><w:r><w:t>Hello</w:t></w:r><w:r><w:t xml:space=\"preserve\"> world</w:t></w:r></w:p><w:p><w:r><w:t>Second</w:t></w:r></w:p></w:body></w:document>"),
        e("word/media/image1.png", "x"),
    ))

    fun docxFacts() {
        val r = run(DocumentInspectorsOoxml, docxBytes(), "r.docx")!!
        assertEquals("docx", r.formatId)
        assertEquals("Quarterly & Annual Report", r.title)
        assertEquals("Document", r.f("Variant"))
        assertEquals("Ada Lovelace", r.f("Author"))
        assertEquals("Charles", r.f("Last modified by"))
        assertEquals("2020-01-02T03:04:05Z", r.f("Created"))
        assertEquals("2021-02-03T04:05:06Z", r.f("Modified"))
        assertEquals("12", r.f("Pages"))
        assertEquals("3402", r.f("Words"))
        assertEquals("ACME", r.f("Company"))
        assertEquals("Microsoft Office Word", r.f("Application"))
        assertEquals("1", r.f("Embedded media files"))
        assertEquals("Hello world\nSecond", r.text)
    }

    fun xlsxFacts() {
        val b = zip(listOf(
            e("[Content_Types].xml", "<Types xmlns=\"$CT\"><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.ms-excel.sheet.macroEnabled.main+xml\"/></Types>"),
            e("docProps/core.xml", "<cp:coreProperties xmlns:cp=\"a\" xmlns:dc=\"b\"><dc:creator>Bob</dc:creator></cp:coreProperties>"),
            e("xl/workbook.xml", "<workbook><workbookPr date1904=\"1\"/><sheets><sheet name=\"Data\" sheetId=\"1\" r:id=\"rId1\"/><sheet name=\"Hidden &amp; More\" sheetId=\"2\" state=\"hidden\" r:id=\"rId2\"/></sheets></workbook>"),
            e("xl/sharedStrings.xml", "<sst count=\"2\" uniqueCount=\"2\"><si><t>alpha</t></si><si><r><t>be</t></r><r><t>ta</t></r></si></sst>"),
            e("xl/vbaProject.bin", "bin"),
        ))
        val r = run(DocumentInspectorsOoxml, b, "b.xlsm")!!
        assertEquals("xlsx", r.formatId)
        assertEquals("Macro-enabled workbook (.xlsm)", r.f("Variant"))
        assertEquals("2", r.f("Sheet count"))
        assertEquals("Data", r.f("Sheet 1"))
        assertEquals("Hidden & More (hidden)", r.f("Sheet 2"))
        assertEquals("1904", r.f("Date system"))
        assertEquals("yes", r.f("Contains VBA macros"))
        assertEquals("Bob", r.f("Author"))
        assertEquals("alpha\nbeta", r.text)
        assertTrue(r.warnings.any { it.contains("VBA") })
    }

    fun pptxFacts() {
        val b = zip(listOf(
            e("[Content_Types].xml", "<Types xmlns=\"$CT\"><Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/></Types>"),
            e("docProps/app.xml", "<Properties><Slides>2</Slides><Notes>1</Notes><HiddenSlides>0</HiddenSlides></Properties>"),
            e("ppt/presentation.xml", "<p:presentation><p:sldSz cx=\"9144000\" cy=\"6858000\"/></p:presentation>"),
            e("ppt/slides/slide2.xml", "<p:sld><a:p><a:r><a:t>Second slide</a:t></a:r></a:p></p:sld>"),
            e("ppt/slides/slide1.xml", "<p:sld><a:p><a:r><a:t>Title</a:t></a:r></a:p><a:p><a:r><a:t>Body</a:t></a:r></a:p></p:sld>"),
        ))
        val r = run(DocumentInspectorsOoxml, b, "s.pptx")!!
        assertEquals("pptx", r.formatId)
        assertEquals("Presentation", r.f("Variant"))
        assertEquals("2", r.f("Slides"))
        assertEquals("2", r.f("Slide parts"))
        assertEquals("10.00 x 7.50", r.f("Slide size (inches)"))
        assertEquals("Slide 1:\nTitle\nBody\n\nSlide 2:\nSecond slide", r.text)
    }

    fun ooxmlWithPrependedJunkAndZip64Style() {
        val b = ByteArray(1000) { 7 } + docxBytes()
        // central-directory offsets are now off by 1000; the reader must cope
        val r = run(DocumentInspectorsOoxml, b, "r.docx")
        assertNotNull(r)
        assertEquals("12", r!!.f("Pages"))
    }

    fun ooxmlTruncationAndHostile() {
        val b = docxBytes()
        prefixes(DocumentInspectorsOoxml, b, "r.docx")
        // central directory sizes 0xFFFFFFFF
        val p = b.copyOf()
        var i = 0
        while (i + 4 < p.size) {
            if (p[i] == 0x50.toByte() && p[i + 1] == 0x4B.toByte() && p[i + 2] == 1.toByte() && p[i + 3] == 2.toByte()) {
                for (k in 20..27) p[i + k] = 0xFF.toByte()
            }
            i++
        }
        run(DocumentInspectorsOoxml, p, "r.docx")
        // EOCD with all-ones fields
        val eocd = ByteArray(22)
        eocd[0] = 0x50; eocd[1] = 0x4B; eocd[2] = 5; eocd[3] = 6
        for (k in 10..19) eocd[k] = 0xFF.toByte()
        assertNull(run(DocumentInspectorsOoxml, eocd))
        // decompression bomb in a metadata part is capped
        val bomb = zip(listOf(
            e("[Content_Types].xml", "<Types xmlns=\"$CT\"/>"),
            Triple("word/document.xml", ByteArray(30 * 1024 * 1024), false),
            Triple("docProps/core.xml", ByteArray(30 * 1024 * 1024), false),
        ))
        assertTrue(bomb.size < 200_000)
        val t0 = System.nanoTime()
        assertNotNull(run(DocumentInspectorsOoxml, bomb, "bomb.docx"))
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 20_000)
    }

    fun encryptedOoxmlInOle() {
        val b = cfb(listOf("EncryptionInfo" to ByteArray(64) { 1 }, "EncryptedPackage" to ByteArray(5000) { 2 }))
        val r = run(DocumentInspectorsOoxml, b, "secret.xlsx")!!
        assertEquals("xlsx", r.formatId)
        assertEquals("yes", r.f("Encrypted"))
    }

    // ---------------------------------------------------------------- ODF

    private fun odf(mime: String, content: String, meta: String? = null, extra: List<Triple<String, ByteArray, Boolean>> = emptyList()): ByteArray {
        val l = ArrayList<Triple<String, ByteArray, Boolean>>()
        l.add(e("mimetype", mime, true))
        l.add(e("META-INF/manifest.xml", "<manifest:manifest xmlns:manifest=\"m\"/>"))
        if (meta != null) l.add(e("meta.xml", meta))
        l.add(e("content.xml", content))
        l.addAll(extra)
        return zip(l)
    }

    private val odtMeta = "<office:document-meta><office:meta><meta:generator>LibreOffice/7.5</meta:generator><dc:title>My Doc</dc:title>" +
        "<meta:initial-creator>Grace</meta:initial-creator><dc:creator>Hopper</dc:creator><meta:creation-date>2020-05-06T07:08:09</meta:creation-date>" +
        "<dc:date>2021-06-07T08:09:10</dc:date><meta:keyword>x</meta:keyword><meta:keyword>y</meta:keyword>" +
        "<meta:document-statistic meta:page-count=\"3\" meta:word-count=\"250\" meta:character-count=\"1400\"/></office:meta></office:document-meta>"

    fun odtFacts() {
        val b = odf("application/vnd.oasis.opendocument.text",
            "<office:document-content><office:body><office:text><text:h>Heading</text:h><text:p>Hello<text:s text:c=\"2\"/>world</text:p></office:text></office:body></office:document-content>",
            odtMeta)
        val r = run(DocumentInspectorsOdf, b, "a.odt")!!
        assertEquals("odt", r.formatId)
        assertEquals("My Doc", r.title)
        assertEquals("Grace", r.f("Author (initial creator)"))
        assertEquals("Hopper", r.f("Last modified by"))
        assertEquals("2020-05-06T07:08:09", r.f("Created"))
        assertEquals("2021-06-07T08:09:10", r.f("Modified"))
        assertEquals("x, y", r.f("Keywords"))
        assertEquals("3", r.f("Pages"))
        assertEquals("250", r.f("Words"))
        assertEquals("LibreOffice/7.5", r.f("Generator"))
        assertEquals("application/vnd.oasis.opendocument.text", r.f("MIME type"))
        assertEquals("Heading\nHello  world", r.text)
    }

    fun odsAndOdp() {
        val ods = odf("application/vnd.oasis.opendocument.spreadsheet",
            "<office:document-content><office:body><office:spreadsheet><table:table table:name=\"Budget\"><table:table-row/></table:table><table:table table:name=\"Q&amp;A\"/></office:spreadsheet></office:body></office:document-content>", odtMeta)
        val r = run(DocumentInspectorsOdf, ods, "a.ods")!!
        assertEquals("ods", r.formatId)
        assertEquals("2", r.f("Sheet count"))
        assertEquals("Budget", r.f("Sheet 1"))
        assertEquals("Q&A", r.f("Sheet 2"))
        val odp = odf("application/vnd.oasis.opendocument.presentation",
            "<office:document-content><office:body><office:presentation><draw:page draw:name=\"page1\"><draw:frame/></draw:page><draw:page draw:name=\"page2\"/><draw:page draw:name=\"page3\"/></office:presentation></office:body></office:document-content>")
        val p = run(DocumentInspectorsOdf, odp, "a.odp")!!
        assertEquals("odp", p.formatId)
        assertEquals("3", p.f("Slides"))
        assertEquals("page2", p.f("Slide 2"))
        assertTrue(p.warnings.any { it.contains("meta.xml") })
    }

    fun odfTruncationAndHostile() {
        val b = odf("application/vnd.oasis.opendocument.text", "<office:body><text:p>x</text:p></office:body>", odtMeta)
        prefixes(DocumentInspectorsOdf, b, "a.odt")
        val p = b.copyOf()
        var i = 0
        while (i + 4 < p.size) {
            if (p[i] == 0x50.toByte() && p[i + 1] == 0x4B.toByte() && p[i + 2] == 1.toByte() && p[i + 3] == 2.toByte()) for (k in 20..27) p[i + k] = 0xFF.toByte()
            i++
        }
        run(DocumentInspectorsOdf, p, "a.odt")
        val big = odf("application/vnd.oasis.opendocument.spreadsheet", "<office:body>" + "<table:table table:name=\"s\"/>".repeat(100_000) + "</office:body>")
        assertNotNull(run(DocumentInspectorsOdf, big, "a.ods"))
    }

    // ---------------------------------------------------------------- EPUB

    private fun epubBytes(): ByteArray = zip(listOf(
        e("mimetype", "application/epub+zip", true),
        e("META-INF/container.xml", "<container><rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>"),
        e("OEBPS/content.opf", "<package version=\"3.0\" xmlns=\"http://www.idpf.org/2007/opf\" unique-identifier=\"id\"><metadata xmlns:dc=\"d\">" +
            "<dc:title>Moby</dc:title><dc:creator>Melville</dc:creator><dc:creator>Second</dc:creator><dc:language>en</dc:language>" +
            "<dc:identifier id=\"id\">urn:uuid:1234</dc:identifier><dc:publisher>Harper</dc:publisher><meta property=\"dcterms:modified\">2022-02-02T00:00:00Z</meta></metadata>" +
            "<manifest><item id=\"a\" href=\"a.xhtml\" media-type=\"application/xhtml+xml\"/><item id=\"b\" href=\"b.xhtml\" media-type=\"application/xhtml+xml\"/></manifest>" +
            "<spine><itemref idref=\"a\"/><itemref idref=\"b\"/></spine></package>"),
        e("OEBPS/a.xhtml", "<html/>"),
        e("OEBPS/b.xhtml", "<html/>"),
        e("OEBPS/cover.jpg", "jpg"),
    ))

    fun epubFacts() {
        val r = run(DocumentInspectorsEpub, epubBytes(), "m.epub")!!
        assertEquals("epub", r.formatId)
        assertEquals("Moby", r.title)
        assertEquals("EPUB 3.0", r.summary)
        assertEquals("Melville; Second", r.f("Author"))
        assertEquals("en", r.f("Language"))
        assertEquals("urn:uuid:1234", r.f("Identifier"))
        assertEquals("Harper", r.f("Publisher"))
        assertEquals("2022-02-02T00:00:00Z", r.f("Modified"))
        assertEquals("2", r.f("Manifest items"))
        assertEquals("2", r.f("Spine items (reading order)"))
        assertEquals("OEBPS/content.opf", r.f("Package document"))
        assertEquals("2", r.f("Content documents (xhtml/html)"))
        assertEquals("1", r.f("Images"))
    }

    fun epubTruncationAndHostile() {
        val b = epubBytes()
        prefixes(DocumentInspectorsEpub, b, "m.epub")
        val p = b.copyOf()
        var i = 0
        while (i + 4 < p.size) {
            if (p[i] == 0x50.toByte() && p[i + 1] == 0x4B.toByte() && p[i + 2] == 1.toByte() && p[i + 3] == 2.toByte()) for (k in 20..27) p[i + k] = 0xFF.toByte()
            i++
        }
        run(DocumentInspectorsEpub, p, "m.epub")
        val many = zip(listOf(e("mimetype", "application/epub+zip", true), e("META-INF/container.xml", "<rootfile full-path=\"../../etc/passwd\"/>")))
        assertNotNull(run(DocumentInspectorsEpub, many))
    }

    // ---------------------------------------------------------------- RTF

    private val rtf = "{\\rtf1\\ansi\\ansicpg1252\\deff0{\\fonttbl{\\f0 Arial;}}{\\*\\generator Riched20 10.0.19041;}" +
        "{\\info{\\title Caf\\'e9 Menu}{\\author John Smith}{\\company ACME}{\\creatim\\yr2020\\mo5\\dy17\\hr10\\min30}{\\nofpages2}{\\nofwords120}}" +
        "\\pard Hello \\b world\\b0\\par Second line\\u8364?\\par}"

    fun rtfFacts() {
        val r = run(DocumentInspectorsRtf, rtf.toByteArray(Charsets.ISO_8859_1), "a.rtf")!!
        assertEquals("rtf", r.formatId)
        assertEquals("Café Menu", r.title)
        assertEquals("1", r.f("RTF version"))
        assertEquals("ANSI", r.f("Character set"))
        assertEquals("1252", r.f("ANSI code page"))
        assertEquals("Riched20 10.0.19041", r.f("Generator"))
        assertEquals("John Smith", r.f("Author"))
        assertEquals("ACME", r.f("Company"))
        assertEquals("2020-05-17T10:30", r.f("Created"))
        assertEquals("2", r.f("Pages"))
        assertEquals("120", r.f("Words"))
        assertEquals("Hello world\nSecond line€", r.text)
    }

    fun rtfTruncationAndHostile() {
        val b = rtf.toByteArray(Charsets.ISO_8859_1)
        prefixes(DocumentInspectorsRtf, b, "a.rtf")
        val s = "{\\rtf1" + "{".repeat(300_000) + "\\bin4294967295 \\u99999999999 \\'" + "{\\info" + "{\\title ".repeat(10000)
        assertNotNull(run(DocumentInspectorsRtf, s.toByteArray(Charsets.ISO_8859_1)))
        assertNull(run(DocumentInspectorsRtf, "plain text".toByteArray()))
    }

    // ---------------------------------------------------------------- OLE2

    private fun le32(o: ByteArrayOutputStream, v: Long) { for (i in 0 until 4) o.write(((v shr (8 * i)) and 0xFF).toInt()) }
    private fun le16(o: ByteArrayOutputStream, v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF) }

    private fun cfb(streams: List<Pair<String, ByteArray>>, clsid: ByteArray = ByteArray(16)): ByteArray {
        val small = streams.filter { it.second.size < 4096 }
        val big = streams.filter { it.second.size >= 4096 }
        val mini = ByteArrayOutputStream()
        val miniStart = HashMap<String, Int>()
        for ((n, d) in small) {
            miniStart[n] = mini.size() / 64
            mini.write(d)
            while (mini.size() % 64 != 0) mini.write(0)
        }
        val miniCount = mini.size() / 64
        val miniFat = IntArray(miniCount) { -1 }
        for ((n, d) in small) {
            val c = (d.size + 63) / 64
            val st = miniStart[n]!!
            for (k in 0 until c) miniFat[st + k] = if (k == c - 1) -2 else st + k + 1
        }
        val miniFatSectors = (miniCount * 4 + 511) / 512
        val miniStreamSectors = (mini.size() + 511) / 512
        val dirSectors = ((1 + streams.size) * 128 + 511) / 512
        val bigSectors = big.map { (it.second.size + 511) / 512 }
        val rest = dirSectors + miniFatSectors + miniStreamSectors + bigSectors.sum()
        var f = 1
        while (f * 128 < rest + f) f++
        val total = f + rest
        val fat = IntArray(f * 128) { -1 }
        for (i in 0 until f) fat[i] = -3
        fun chain(start: Int, n: Int) { for (k in 0 until n) fat[start + k] = if (k == n - 1) -2 else start + k + 1 }
        val dirStart = f
        chain(dirStart, dirSectors)
        val miniFatStart = dirStart + dirSectors
        chain(miniFatStart, miniFatSectors)
        val miniStreamStart = miniFatStart + miniFatSectors
        chain(miniStreamStart, miniStreamSectors)
        val bigStart = HashMap<String, Int>()
        var cur = miniStreamStart + miniStreamSectors
        for ((idx, s) in big.withIndex()) { bigStart[s.first] = cur; chain(cur, bigSectors[idx]); cur += bigSectors[idx] }

        val out = ByteArrayOutputStream()
        // header
        out.write(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()))
        out.write(ByteArray(16))
        le16(out, 0x3E); le16(out, 3); le16(out, 0xFFFE); le16(out, 9); le16(out, 6)
        out.write(ByteArray(6))
        le32(out, 0) // dir sectors (v3)
        le32(out, f.toLong())
        le32(out, dirStart.toLong())
        le32(out, 0)
        le32(out, 4096)
        le32(out, if (miniFatSectors > 0) miniFatStart.toLong() else 0xFFFFFFFEL)
        le32(out, miniFatSectors.toLong())
        le32(out, 0xFFFFFFFEL)
        le32(out, 0)
        for (i in 0 until 109) le32(out, if (i < f) i.toLong() else 0xFFFFFFFFL)
        check(out.size() == 512)
        // FAT
        for (v in fat) le32(out, v.toLong() and 0xFFFFFFFFL)
        // directory
        fun entry(name: String, type: Int, left: Long, right: Long, child: Long, cl: ByteArray, start: Long, size: Long): ByteArray {
            val o = ByteArrayOutputStream()
            val nb = name.toByteArray(Charsets.UTF_16LE)
            o.write(nb); o.write(ByteArray(64 - nb.size))
            le16(o, nb.size + 2)
            o.write(type); o.write(1)
            le32(o, left); le32(o, right); le32(o, child)
            o.write(cl)
            o.write(ByteArray(4 + 16))
            le32(o, start)
            le32(o, size); le32(o, 0)
            check(o.size() == 128)
            return o.toByteArray()
        }
        val dir = ByteArrayOutputStream()
        dir.write(entry("Root Entry", 5, 0xFFFFFFFFL, 0xFFFFFFFFL, if (streams.isEmpty()) 0xFFFFFFFFL else 1, clsid,
            if (miniStreamSectors > 0) miniStreamStart.toLong() else 0xFFFFFFFEL, mini.size().toLong()))
        for ((i, s) in streams.withIndex()) {
            val isSmall = s.second.size < 4096
            dir.write(entry(s.first, 2, 0xFFFFFFFFL, if (i == streams.size - 1) 0xFFFFFFFFL else (i + 2).toLong(), 0xFFFFFFFFL, ByteArray(16),
                (if (isSmall) miniStart[s.first]!! else bigStart[s.first]!!).toLong(), s.second.size.toLong()))
        }
        while (dir.size() % 512 != 0) dir.write(0)
        out.write(dir.toByteArray())
        // mini FAT
        val mf = ByteArrayOutputStream()
        for (v in miniFat) le32(mf, v.toLong() and 0xFFFFFFFFL)
        while (mf.size() % 512 != 0) mf.write(0xFF)
        if (miniFatSectors > 0) out.write(mf.toByteArray())
        val ms = mini.toByteArray().copyOf(miniStreamSectors * 512)
        out.write(ms)
        for ((idx, s) in big.withIndex()) out.write(s.second.copyOf(bigSectors[idx] * 512))
        check(out.size() == 512 + total * 512) { "${out.size()} vs ${512 + total * 512}" }
        return out.toByteArray()
    }

    private fun vI2(x: Int) = ByteArrayOutputStream().also { le16(it, 2); le16(it, 0); le16(it, x); le16(it, 0) }.toByteArray()
    private fun vI4(x: Long) = ByteArrayOutputStream().also { le16(it, 3); le16(it, 0); le32(it, x) }.toByteArray()
    private fun vStr(s: String): ByteArray {
        val b = s.toByteArray(Charsets.ISO_8859_1)
        val o = ByteArrayOutputStream()
        le16(o, 0x1E); le16(o, 0); le32(o, b.size + 1L); o.write(b); o.write(0)
        while (o.size() % 4 != 0) o.write(0)
        return o.toByteArray()
    }
    private fun vFt(ticks: Long) = ByteArrayOutputStream().also { le16(it, 0x40); le16(it, 0); le32(it, ticks and 0xFFFFFFFFL); le32(it, ticks ushr 32) }.toByteArray()

    private fun propSet(props: List<Pair<Int, ByteArray>>): ByteArray {
        val o = ByteArrayOutputStream()
        le16(o, 0xFFFE); le16(o, 0); le32(o, 0); o.write(ByteArray(16)); le32(o, 1)
        o.write(ByteArray(16)); le32(o, 48)
        val values = ByteArrayOutputStream()
        val tableLen = 8 + 8 * props.size
        val sec = ByteArrayOutputStream()
        val table = ByteArrayOutputStream()
        for ((id, v) in props) { le32(table, id.toLong()); le32(table, (tableLen + values.size()).toLong()); values.write(v) }
        le32(sec, (tableLen + values.size()).toLong()); le32(sec, props.size.toLong())
        sec.write(table.toByteArray()); sec.write(values.toByteArray())
        o.write(sec.toByteArray())
        return o.toByteArray()
    }

    private fun ticksOf(iso: String) = (java.time.Instant.parse(iso).epochSecond + 11_644_473_600L) * 10_000_000L

    private val summary = propSet(listOf(
        1 to vI2(1252), 2 to vStr("Report Title"), 4 to vStr("Ada"), 8 to vStr("Bob"),
        12 to vFt(ticksOf("2020-01-02T03:04:05Z")), 14 to vI4(7), 15 to vI4(1500), 18 to vStr("Microsoft Word 11"),
    ))
    private val docSummary = propSet(listOf(1 to vI2(1252), 2 to vStr("Cat"), 7 to vI4(5), 14 to vStr("Boss"), 15 to vStr("ACME")))

    private fun fib(flags: Int): ByteArray = ByteArray(4200).also {
        it[0] = 0xEC.toByte(); it[1] = 0xA5.toByte(); it[2] = 0xC1.toByte(); it[3] = 0
        it[10] = (flags and 0xFF).toByte(); it[11] = (flags shr 8).toByte()
    }

    private fun wordCfb(flags: Int = 0x0200): ByteArray = cfb(listOf(
        "WordDocument" to fib(flags), "\u0005SummaryInformation" to summary, "\u0005DocumentSummaryInformation" to docSummary,
    ), byteArrayOf(0x06, 0x09, 0x02, 0, 0, 0, 0, 0, 0xC0.toByte(), 0, 0, 0, 0, 0, 0, 0x46))

    fun legacyDoc() {
        val r = run(DocumentInspectorsOle, wordCfb(), "a.doc")!!
        assertEquals("doc", r.formatId)
        assertEquals("Report Title", r.title)
        assertEquals("Ada", r.f("Author"))
        assertEquals("Bob", r.f("Last saved by"))
        assertEquals("2020-01-02T03:04:05Z", r.f("Created"))
        assertEquals("7", r.f("Pages"))
        assertEquals("1500", r.f("Words"))
        assertEquals("Microsoft Word 11", r.f("Created with"))
        assertEquals("ACME", r.f("Company"))
        assertEquals("Boss", r.f("Manager"))
        assertEquals("Cat", r.f("Category"))
        assertEquals("0x00C1 (Word 97)", r.f("Word FIB version (nFib)"))
        assertEquals("1Table", r.f("Table stream"))
        assertEquals("3", r.f("Stream count"))
        assertEquals("4200 bytes", r.f("WordDocument"))
        assertEquals("512 bytes", r.f("Sector size"))
        assertEquals("00020906-0000-0000-C000-000000000046 (Microsoft Word 97-2003 document)", r.f("Root CLSID"))
        assertNull(r.f("Encrypted"))
    }

    fun legacyDocEncryptedAndMismatch() {
        val r = run(DocumentInspectorsOle, wordCfb(0x0300), "a.xls")!!
        assertEquals("yes (FIB fEncrypted)", r.f("Encrypted"))
        assertTrue(r.warnings.any { it.contains("does not match") })
    }

    fun legacyXls() {
        fun rec(id: Int, body: ByteArray) = ByteArrayOutputStream().also { le16(it, id); le16(it, body.size); it.write(body) }.toByteArray()
        fun bound(name: String, state: Int, wide: Boolean): ByteArray {
            val o = ByteArrayOutputStream()
            le32(o, 100); o.write(state); o.write(0); o.write(name.length); o.write(if (wide) 1 else 0)
            o.write(name.toByteArray(if (wide) Charsets.UTF_16LE else Charsets.ISO_8859_1))
            return rec(0x0085, o.toByteArray())
        }
        val bof = ByteArrayOutputStream().also { le16(it, 0x0600); le16(it, 5); le16(it, 0x0DBB); le16(it, 0x07CC); le32(it, 0); le32(it, 6) }.toByteArray()
        val wb = ByteArrayOutputStream()
        wb.write(rec(0x0809, bof)); wb.write(bound("Sheet1", 0, false)); wb.write(bound("Hidden", 1, false)); wb.write(bound("Données", 0, true))
        wb.write(rec(0x000A, ByteArray(0)))
        wb.write(ByteArray(5000))
        val b = cfb(listOf("Workbook" to wb.toByteArray(), "\u0005SummaryInformation" to summary))
        val r = run(DocumentInspectorsOle, b, "a.xls")!!
        assertEquals("xls", r.formatId)
        assertEquals("BIFF8 (Excel 97-2003)", r.f("BIFF version"))
        assertEquals("3", r.f("Sheet count"))
        assertEquals("Sheet1", r.f("Sheet 1"))
        assertEquals("Hidden (hidden)", r.f("Sheet 2"))
        assertEquals("Données", r.f("Sheet 3"))
        assertEquals("Report Title", r.title)
    }

    fun legacyPpt() {
        val b = cfb(listOf("PowerPoint Document" to ByteArray(300), "Current User" to ByteArray(40), "\u0005DocumentSummaryInformation" to docSummary))
        val r = run(DocumentInspectorsOle, b, "a.ppt")!!
        assertEquals("ppt", r.formatId)
        assertEquals("5", r.f("Slides"))
        assertEquals("ACME", r.f("Company"))
    }

    fun oleTruncationAndHostile() {
        val b = wordCfb()
        prefixes(DocumentInspectorsOle, b, "a.doc", 3)
        for (n in listOf(0, 1, 511, 512, 513, 1023, 1024, 1025, b.size - 1, b.size)) run(DocumentInspectorsOle, b.copyOf(n), "a.doc")
        // hostile header counts
        val h = b.copyOf()
        for (k in 0x2C..0x2F) h[k] = 0xFF.toByte()   // numFat
        for (k in 0x44..0x4B) h[k] = 0xFF.toByte()   // firstDifat / numDifat
        run(DocumentInspectorsOle, h, "a.doc")
        // hostile directory: giant sizes and self-referential tree
        val d = b.copyOf()
        var o = 512 + 512 + 128
        while (o + 128 <= d.size && o < 512 + 512 + 128 * 4) {
            for (k in 120..127) d[o + k] = 0xFF.toByte()
            for (k in 68..79) d[o + k] = 1
            o += 128
        }
        run(DocumentInspectorsOle, d, "a.doc")
        // FAT whose chain loops on itself
        val loop = b.copyOf()
        for (k in 0x30..0x33) loop[k] = 0
        run(DocumentInspectorsOle, loop, "a.doc")
        // 4 GiB claimed stream in a tiny file
        val g = wordCfb()
        for (k in 120..127) g[512 + 512 + 128 + k] = 0xFF.toByte()
        assertNotNull(run(DocumentInspectorsOle, g, "a.doc"))
    }

    // ---------------------------------------------------------------- MOBI

    private fun be32(o: ByteArrayOutputStream, v: Long) { for (i in 3 downTo 0) o.write(((v shr (8 * i)) and 0xFF).toInt()) }
    private fun be16(o: ByteArrayOutputStream, v: Int) { o.write((v shr 8) and 0xFF); o.write(v and 0xFF) }

    private fun mobiBytes(enc: Int = 0): ByteArray {
        val title = "Moby Dick".toByteArray()
        fun exth(): ByteArray {
            val recs = ByteArrayOutputStream()
            fun r(t: Int, v: String) { val b = v.toByteArray(); be32(recs, t.toLong()); be32(recs, 8L + b.size); recs.write(b) }
            r(100, "Herman Melville"); r(101, "Harper"); r(524, "en")
            val o = ByteArrayOutputStream()
            o.write("EXTH".toByteArray()); be32(o, 12L + recs.size()); be32(o, 3); o.write(recs.toByteArray())
            while (o.size() % 4 != 0) o.write(0)
            return o.toByteArray()
        }
        val ex = exth()
        val rec0 = ByteArray(16 + 232 + ex.size + 4 + title.size + 8)
        val bb = ByteBuffer.wrap(rec0).order(ByteOrder.BIG_ENDIAN)
        bb.putShort(0, 2); bb.putInt(4, 12345); bb.putShort(8, 3); bb.putShort(10, 4096); bb.putShort(12, enc.toShort())
        System.arraycopy("MOBI".toByteArray(), 0, rec0, 16, 4)
        bb.putInt(20, 232); bb.putInt(24, 2); bb.putInt(28, 65001); bb.putInt(36, 6)
        val nameOff = 16 + 232 + ex.size
        bb.putInt(84, nameOff); bb.putInt(88, title.size); bb.putInt(104, 6); bb.putInt(128, 0x40)
        System.arraycopy(ex, 0, rec0, 248, ex.size)
        System.arraycopy(title, 0, rec0, nameOff, title.size)
        val o = ByteArrayOutputStream()
        val name = "My_Book".toByteArray()
        o.write(name); o.write(ByteArray(32 - name.size))
        le16(o, 0); le16(o, 0); o.write(ByteArray(4 * 5)) // attrs, version, 5 dates/ids -> up to 60
        while (o.size() < 60) o.write(0)
        o.write("BOOK".toByteArray()); o.write("MOBI".toByteArray())
        be32(o, 0); be32(o, 0)
        be16(o, 2)
        val rec0Off = 78 + 16 + 2
        be32(o, rec0Off.toLong()); o.write(0); o.write(byteArrayOf(0, 0, 0))
        be32(o, (rec0Off + rec0.size).toLong()); o.write(0); o.write(byteArrayOf(0, 0, 1))
        be16(o, 0)
        check(o.size() == rec0Off) { "${o.size()} != $rec0Off" }
        o.write(rec0)
        o.write(ByteArray(10))
        return o.toByteArray()
    }

    fun mobiFacts() {
        val r = run(DocumentInspectorsMobi, mobiBytes(), "moby.mobi")!!
        assertEquals("mobi", r.formatId)
        assertEquals("Moby Dick", r.title)
        assertEquals("My_Book", r.f("Database name"))
        assertEquals("BOOK / MOBI", r.f("Type / creator"))
        assertEquals("PalmDOC (LZ77)", r.f("Compression"))
        assertEquals("12345", r.f("Uncompressed text length"))
        assertEquals("none", r.f("Encryption"))
        assertEquals("UTF-8", r.f("Text encoding"))
        assertEquals("6", r.f("File version"))
        assertEquals("Mobipocket book", r.f("MOBI type"))
        assertEquals("Herman Melville", r.f("Author"))
        assertEquals("Harper", r.f("Publisher"))
        assertEquals("en", r.f("Language"))
        assertTrue(r.warnings.isEmpty())
    }

    fun mobiEncryptedWarns() {
        val r = run(DocumentInspectorsMobi, mobiBytes(2), "moby.azw")!!
        assertEquals("azw", r.formatId)
        assertEquals("Mobipocket encryption", r.f("Encryption"))
        assertTrue(r.warnings.any { it.contains("encrypted") })
    }

    fun mobiTruncationAndHostile() {
        val b = mobiBytes()
        prefixes(DocumentInspectorsMobi, b, "m.mobi")
        val a = b.copyOf(); for (k in 78..81) a[k] = 0xFF.toByte()
        run(DocumentInspectorsMobi, a)
        val c = b.copyOf(); for (k in (96 + 88)..(96 + 91)) c[k] = 0xFF.toByte()
        run(DocumentInspectorsMobi, c)
        val d = b.copyOf(); for (k in (96 + 248 + 8)..(96 + 248 + 11)) d[k] = 0xFF.toByte()
        assertNotNull(run(DocumentInspectorsMobi, d))
        val e2 = b.copyOf(); for (k in (96 + 248 + 4)..(96 + 248 + 7)) e2[k] = 0xFF.toByte()
        run(DocumentInspectorsMobi, e2)
        val f2 = b.copyOf(); for (k in (96 + 20)..(96 + 23)) f2[k] = 0xFF.toByte()
        run(DocumentInspectorsMobi, f2)
    }

    // ======================================================== VERIFY FUZZ

    private class Counting(private val d: ByteArray) : ByteSource {
        var requested = 0L; var returned = 0L; var calls = 0
        override val size: Long get() = d.size.toLong()
        override fun read(offset: Long, length: Int): ByteArray {
            calls++
            if (length > 0) requested += length
            if (offset < 0 || offset >= d.size || length <= 0) return ByteArray(0)
            val end = minOf(d.size.toLong(), offset + length).toInt()
            val r = d.copyOfRange(offset.toInt(), end)
            returned += r.size
            return r
        }
    }

    private val failures = ArrayList<String>()
    private var runs = 0
    private var worstMs = 0L

    private fun check1(label: String, i: FormatInspector, data: ByteArray, name: String?) {
        val c = Counting(data)
        val t0 = System.nanoTime()
        try { i.inspect(c, name) } catch (t: Throwable) {
            if (failures.size < 60) failures.add("$label: THROWS $t"); return
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        runs++
        if (ms > worstMs) worstMs = ms
        if (ms > 10_000 && failures.size < 60) failures.add("$label: SLOW ${ms}ms (size ${data.size})")
        if (c.requested > FormatInspector.MAX_READ_BYTES && failures.size < 60) failures.add("$label: requested ${c.requested} > MAX")
        if (c.returned > FormatInspector.MAX_READ_BYTES && failures.size < 60) failures.add("$label: returned ${c.returned} > MAX")
        if (c.calls > 20000 && failures.size < 60) failures.add("$label: ${c.calls} read calls")
    }

    private fun fixtures(): List<Triple<FormatInspector, ByteArray, String>> = listOf(
        Triple(DocumentInspectorsPdf, pdfBytes("/Encrypt 6 0 R", "6 0 obj\n<< /Filter /Standard /V 2 /R 3 >>\nendobj\n"), "a.pdf"),
        Triple(DocumentInspectorsOoxml, docxBytes(), "a.docx"),
        Triple(DocumentInspectorsOoxml, zip(listOf(
            e("[Content_Types].xml", "<Types xmlns=\"$CT\"><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.ms-excel.sheet.macroEnabled.main+xml\"/></Types>"),
            e("xl/workbook.xml", "<workbook><sheets><sheet name=\"A\" sheetId=\"1\"/><sheet name=\"B\" state=\"hidden\"/></sheets></workbook>"),
            e("xl/sharedStrings.xml", "<sst><si><t>alpha</t></si></sst>"), e("xl/vbaProject.bin", "bin"))), "a.xlsx"),
        Triple(DocumentInspectorsOoxml, zip(listOf(
            e("ppt/presentation.xml", "<p:presentation><p:sldSz cx=\"9144000\" cy=\"6858000\"/></p:presentation>"),
            e("ppt/slides/slide1.xml", "<p:sld><a:p><a:r><a:t>Title</a:t></a:r></a:p></p:sld>"))), "a.pptx"),
        Triple(DocumentInspectorsOoxml, cfb(listOf("EncryptionInfo" to ByteArray(64) { 1 }, "EncryptedPackage" to ByteArray(5000) { 2 })), "a.xlsx"),
        Triple(DocumentInspectorsOdf, odf("application/vnd.oasis.opendocument.text", "<office:body><text:p>x</text:p></office:body>", odtMeta), "a.odt"),
        Triple(DocumentInspectorsOdf, odf("application/vnd.oasis.opendocument.spreadsheet", "<office:body><table:table table:name=\"s\"/></office:body>", odtMeta), "a.ods"),
        Triple(DocumentInspectorsOdf, odf("application/vnd.oasis.opendocument.presentation", "<office:body><draw:page draw:name=\"p\"/></office:body>"), "a.odp"),
        Triple(DocumentInspectorsEpub, epubBytes(), "a.epub"),
        Triple(DocumentInspectorsRtf, rtf.toByteArray(Charsets.ISO_8859_1), "a.rtf"),
        Triple(DocumentInspectorsOle, wordCfb(), "a.doc"),
        Triple(DocumentInspectorsOle, cfb(listOf("PowerPoint Document" to ByteArray(300), "\u0005DocumentSummaryInformation" to docSummary)), "a.ppt"),
        Triple(DocumentInspectorsOle, xlsFixture(), "a.xls"),
        Triple(DocumentInspectorsMobi, mobiBytes(), "a.mobi"),
        Triple(DocumentInspectorsMobi, mobiBytes(2), "a.azw3"),
    )

    private fun xlsFixture(): ByteArray {
        fun rec(id: Int, body: ByteArray) = ByteArrayOutputStream().also { le16(it, id); le16(it, body.size); it.write(body) }.toByteArray()
        fun bound(name: String, state: Int, wide: Boolean): ByteArray {
            val o = ByteArrayOutputStream()
            le32(o, 100); o.write(state); o.write(0); o.write(name.length); o.write(if (wide) 1 else 0)
            o.write(name.toByteArray(if (wide) Charsets.UTF_16LE else Charsets.ISO_8859_1))
            return rec(0x0085, o.toByteArray())
        }
        val bof = ByteArrayOutputStream().also { le16(it, 0x0600); le16(it, 5); le16(it, 0x0DBB); le16(it, 0x07CC); le32(it, 0); le32(it, 6) }.toByteArray()
        val wb = ByteArrayOutputStream()
        wb.write(rec(0x0809, bof)); wb.write(bound("Sheet1", 0, false)); wb.write(bound("Hidden", 1, false)); wb.write(bound("Don", 0, true))
        wb.write(rec(0x002F, ByteArray(4))); wb.write(rec(0x000A, ByteArray(0))); wb.write(ByteArray(5000))
        return cfb(listOf("Workbook" to wb.toByteArray(), "\u0005SummaryInformation" to summary))
    }

    private fun report() {
        println("VERIFY runs=$runs worstMs=$worstMs failures=${failures.size}")
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test fun verifyPrefixes() {
        for ((i, b, n) in fixtures()) {
            var k = 0
            while (k <= b.size) { check1("prefix $n@$k", i, b.copyOf(k), n); k += if (k < 1500) 1 else 5 }
        }
        report()
    }

    @Test fun verifyByteFlips() {
        val rnd = java.util.Random(42)
        for ((i, b, n) in fixtures()) {
            val step = maxOf(1, b.size / 1500)
            var k = 0
            while (k < b.size) {
                for (v in intArrayOf(0x00, 0xFF, 0x80, rnd.nextInt(256))) {
                    val c = b.copyOf(); c[k] = v.toByte(); check1("flip $n@$k=$v", i, c, n)
                }
                k += step
            }
            for (t in 0 until 300) {
                val c = b.copyOf()
                for (q in 0 until 1 + rnd.nextInt(8)) c[rnd.nextInt(c.size)] = rnd.nextInt(256).toByte()
                check1("multiflip $n#$t", i, c, n)
            }
        }
        report()
    }

    @Test fun verifyBlanksAndRandom() {
        val rnd = java.util.Random(7)
        val magics = listOf("%PDF-1.4\n".toByteArray(), byteArrayOf(0x50, 0x4B, 3, 4), "{\\rtf1".toByteArray(),
            byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()), ByteArray(0))
        for (i in DocumentInspectors.all) for (n in listOf("a.pdf", "a.docx", "a.odt", "a.epub", "a.rtf", "a.doc", "a.mobi", null)) {
            for (size in intArrayOf(0, 1, 7, 8, 22, 100, 511, 512, 513, 1024, 4096, 70000, 1_000_000, 9_000_000)) {
                if (size > 100000 && n != "a.pdf" && n != "a.rtf" && n != "a.docx" && n != "a.doc") continue
                check1("zeros $i $n $size", i, ByteArray(size), n)
                check1("ff $i $n $size", i, ByteArray(size) { -1 }, n)
            }
            for (m in magics) for (t in 0 until 12) {
                val len = 1 + rnd.nextInt(if (t < 8) 3000 else 200000)
                val r = ByteArray(len); rnd.nextBytes(r)
                val d = m + r
                check1("rand $i $n magic${m.size} #$t", i, d, n)
            }
        }
        report()
    }

    @Test fun verifyFieldOverwrites() {
        val vals = listOf(
            byteArrayOf(-1, -1, -1, -1), byteArrayOf(-1, -1, -1, 0x7F), byteArrayOf(0, 0, 0, 0),
            byteArrayOf(0x7F, -1, -1, -1), byteArrayOf(-1, -1, 0, 0), byteArrayOf(0, 0, 0, 0x80.toByte()),
        )
        for ((i, b, n) in fixtures()) {
            val step = if (b.size < 2500) 1 else 3
            var k = 0
            while (k + 4 <= b.size) {
                for (v in vals) {
                    val c = b.copyOf(); System.arraycopy(v, 0, c, k, 4); check1("field $n@$k=${v.joinToString { (it.toInt() and 0xFF).toString(16) }}", i, c, n)
                }
                k += step
            }
        }
        report()
    }

    @Test fun verifyPdfHostileScans() {
        val sizes = listOf(300_000)
        for (sz in sizes) {
            val pats = listOf("/Type/Pages ", "/Type /Page ", "/Info 1 0 R ", "/Parent ", "/Count 1 ", "1 0 obj ", "/Encrypt ", "/JS ", "/Type/ObjStm ", "/Linearized ", "<</Type/Pages/Count 5>> ")
            for (p in pats) {
                val pb = p.toByteArray()
                val d = ByteArray(sz); System.arraycopy("%PDF-1.4\n".toByteArray(), 0, d, 0, 9)
                var o = 9; while (o + pb.size < sz) { System.arraycopy(pb, 0, d, o, pb.size); o += pb.size }
                check1("pdfhostile '$p' $sz", DocumentInspectorsPdf, d, "a.pdf")
            }
        }
        // 50GB size claimed
        for (i in DocumentInspectors.all) {
            val big = object : ByteSource {
                var req = 0L
                override val size: Long = 50L * 1024 * 1024 * 1024
                override fun read(offset: Long, length: Int): ByteArray { req += length; return ByteArray(minOf(length, 1 shl 22)) { 0x25 } }
            }
            val t0 = System.nanoTime()
            try { i.inspect(big, "x.pdf") } catch (t: Throwable) { failures.add("big $i THROWS $t") }
            if (big.req > FormatInspector.MAX_READ_BYTES) failures.add("big $i requested ${big.req}")
            if ((System.nanoTime() - t0) / 1_000_000 > 10_000) failures.add("big $i slow")
        }
        report()
    }

    @Test fun verifyRtfHostile() {
        val pats = listOf("{\\info{\\title ", "\\u9999 ", "{\\*\\x ", "\\'4", "\\bin5 ", "{\\creatim\\yr", "\\generator ", "{\\info")
        for (p in pats) {
            val sb = StringBuilder("{\\rtf1\\ansi ")
            while (sb.length < 300_000) sb.append(p)
            check1("rtf '$p'", DocumentInspectorsRtf, sb.toString().toByteArray(Charsets.ISO_8859_1), "a.rtf")
        }
        report()
    }

    @Test fun verifyXmlHostile() {
        val pats = listOf("<a ", "<w:t>", "<![CDATA[", "&#", "<sheet ", "<text:p>", "<item ", "<si>", "</w:p>")
        for (p in pats) {
            val sb = StringBuilder()
            while (sb.length < 900_000) sb.append(p)
            val x = sb.toString()
            check1("docx $p", DocumentInspectorsOoxml, zip(listOf(e("word/document.xml", x), e("docProps/core.xml", x), e("[Content_Types].xml", x), e("docProps/app.xml", x))), "a.docx")
            check1("xlsx $p", DocumentInspectorsOoxml, zip(listOf(e("xl/workbook.xml", x), e("xl/sharedStrings.xml", x), e("[Content_Types].xml", x))), "a.xlsx")
            check1("pptx $p", DocumentInspectorsOoxml, zip(listOf(e("ppt/presentation.xml", x), e("ppt/slides/slide1.xml", x))), "a.pptx")
            for (m in listOf("text", "spreadsheet", "presentation"))
                check1("odf $m $p", DocumentInspectorsOdf, zip(listOf(e("mimetype", "application/vnd.oasis.opendocument.$m", true), e("meta.xml", x), e("content.xml", x), e("META-INF/manifest.xml", x))), "a.od")
            check1("epub $p", DocumentInspectorsEpub, zip(listOf(e("mimetype", "application/epub+zip", true), e("META-INF/container.xml", x), e("OEBPS/content.opf", x))), "a.epub")
            check1("epub2 $p", DocumentInspectorsEpub, zip(listOf(e("mimetype", "application/epub+zip", true), e("META-INF/container.xml", "<rootfile full-path=\"c.opf\"/>"), e("c.opf", x))), "a.epub")
        }
        report()
    }

    @Test fun verifyNonMatching() {
        val txt = "hello world, just some text\n".repeat(50).toByteArray()
        val plainZip = zip(listOf(e("a.txt", "hi"), e("b/c.bin", "zz")))
        for (i in DocumentInspectors.all) {
            assertNull("$i text", i.inspect(ByteArraySource(txt), "a.txt"))
            assertNull("$i empty", i.inspect(ByteArraySource(ByteArray(0)), null))
            if (i !== DocumentInspectorsOoxml) assertNull("$i plainzip", i.inspect(ByteArraySource(plainZip), "a.zip"))
            if (i === DocumentInspectorsOoxml) assertNull("ooxml plainzip noext", i.inspect(ByteArraySource(plainZip), "a.zip"))
        }
        // generic zip named .docx must not be reported as a docx
        val g = DocumentInspectorsOoxml.inspect(ByteArraySource(plainZip), "renamed.docx")
        assertNull("generic zip with .docx extension should not be claimed as docx", g)
        val g2 = DocumentInspectorsOdf.inspect(ByteArraySource(zip(listOf(e("mimetype", "application/vnd.oasis.opendocument.graphics", true)))), "x.odt")
        assertNull("odg named .odt claimed as odt", g2)
        // ole that is not office
        val o = DocumentInspectorsOle.inspect(ByteArraySource(cfb(listOf("Foo" to ByteArray(100)))), "x.msi")
        if (o != null) assertTrue("generic OLE reported as ${o.formatId}", o.warnings.any { it.contains("generic OLE2") } && o.formatId != "doc")
        // PDF header only
        val pd = DocumentInspectorsPdf.inspect(ByteArraySource("%PDF-1.4\n".toByteArray()), "a.pdf")
        if (pd != null) assertTrue("Encrypted fact fabricated", pd.facts.none { it.label == "Encrypted" && it.value == "yes" })
    }

    @Test fun verifyIdContract() {
        val ids = DocumentInspectors.all.flatMap { it.formatIds.toList() }
        assertEquals("duplicate ids", ids.size, ids.toSet().size)
        for (i in ids) assertTrue("id $i not lowercase", i == i.lowercase())
        assertEquals(7, DocumentInspectors.all.size)
    }
}
