package io.github.mbaliga.fylz.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Real round trips against hand-built ZIP-of-XML fixtures, the same shape a real .docx/.xlsx/.pptx
 * would have -- no Android type anywhere ([ZipXmlParts], [WorkbookReader] and
 * [PresentationDeckReader] are all plain `java.util.zip`/SAX, so this runs as a plain JUnit test.
 */
class OfficeTextExtractorTest {

    private fun zipOf(vararg entries: Pair<String, String>): () -> InputStream {
        val bytes = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zip ->
                entries.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        return { ByteArrayInputStream(bytes) }
    }

    @Test
    fun `supports only the OOXML extensions, case-insensitively`() {
        assertTrue(OfficeTextExtractor.supports("docx"))
        assertTrue(OfficeTextExtractor.supports("XLSX"))
        assertTrue(OfficeTextExtractor.supports("pptx"))
        assertTrue(OfficeTextExtractor.supports("ppsx"))
        assertTrue(OfficeTextExtractor.supports("xlsm"))
        assertFalse(OfficeTextExtractor.supports("pdf"))
        assertFalse(OfficeTextExtractor.supports("txt"))
    }

    @Test
    fun `extracts real text from a docx's own word-document xml body`() {
        val open = zipOf(
            "word/document.xml" to """
                <w:document><w:body>
                <w:p><w:r><w:t>Hello from a real document</w:t></w:r></w:p>
                <w:p><w:r><w:t>Second paragraph</w:t></w:r></w:p>
                </w:body></w:document>
            """.trimIndent(),
        )

        val extracted = OfficeTextExtractor.extract(open, "docx")

        requireNotNull(extracted)
        assertTrue(extracted.contains("Hello from a real document"))
        assertTrue(extracted.contains("Second paragraph"))
    }

    @Test
    fun `a docx with no word document xml part yields null`() {
        val open = zipOf("word/other.xml" to "<irrelevant/>")

        assertNull(OfficeTextExtractor.extract(open, "docx"))
    }

    @Test
    fun `extracts real cell text from an xlsx worksheet via the existing WorkbookReader`() {
        val open = zipOf(
            "xl/workbook.xml" to """<workbook><sheets><sheet name="Sheet1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to
                """<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to
                """<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>Hello Sheet</t></is></c></row></sheetData></worksheet>""",
        )

        val extracted = OfficeTextExtractor.extract(open, "xlsx")

        requireNotNull(extracted)
        assertTrue(extracted.contains("Hello Sheet"))
    }

    @Test
    fun `extracts real slide title and body text from a pptx via the existing PresentationDeckReader`() {
        val open = zipOf(
            "ppt/presentation.xml" to """<p:presentation><p:sldIdLst><p:sldId id="256" r:id="rId2"/></p:sldIdLst></p:presentation>""",
            "ppt/_rels/presentation.xml.rels" to
                """<Relationships><Relationship Id="rId2" Target="slides/slide1.xml"/></Relationships>""",
            "ppt/slides/slide1.xml" to """
                <p:sld>
                <p:sp><p:ph type="title"/><p:txBody><a:p><a:r><a:t>Slide Title</a:t></a:r></a:p></p:txBody></p:sp>
                <p:sp><p:txBody><a:p><a:r><a:t>Body paragraph text</a:t></a:r></a:p></p:txBody></p:sp>
                </p:sld>
            """.trimIndent(),
        )

        val extracted = OfficeTextExtractor.extract(open, "pptx")

        requireNotNull(extracted)
        assertTrue(extracted.contains("Slide Title"))
        assertTrue(extracted.contains("Body paragraph text"))
    }

    @Test
    fun `a corrupt container fails closed instead of throwing`() {
        val open = { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) }

        assertNull(OfficeTextExtractor.extract(open, "docx"))
    }
}
