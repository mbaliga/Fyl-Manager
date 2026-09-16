package io.github.mbaliga.fylz.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * End-to-end coverage for the two office readers, over containers built here rather than over a
 * checked-in binary: the point is that a real ZIP of real part XML goes in and the right cells and
 * slides come out, including the cases that quietly break a naive reader.
 *
 * These run on the JVM with no device: both readers are `java.util.zip` plus SAX by design.
 */
class OfficeContainerReaderTest {

    private fun container(parts: List<Pair<String, String>>): () -> InputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            parts.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        val snapshot = bytes.toByteArray()
        return { ByteArrayInputStream(snapshot) }
    }

    /**
     * The shared-string table is written AFTER the sheet that cites it, which is legal and which
     * a single-pass reader gets wrong: it would resolve every `t="s"` cell against an empty table
     * and render a sheet of blanks.
     */
    @Test
    fun `an xlsx resolves shared strings written after the sheet that cites them`() {
        val open = container(
            listOf(
                "xl/workbook.xml" to """
                    <workbook><sheets>
                      <sheet name="Data" sheetId="1" r:id="rId1"/>
                      <sheet name="Notes" sheetId="2" r:id="rId2"/>
                    </sheets></workbook>
                """.trimIndent(),
                "xl/worksheets/sheet1.xml" to """
                    <worksheet><sheetData>
                      <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
                      <row r="3">
                        <c r="A3" t="s"><v>2</v></c>
                        <c r="C3"><v>42</v></c>
                        <c r="D3" t="b"><v>1</v></c>
                      </row>
                    </sheetData></worksheet>
                """.trimIndent(),
                "xl/worksheets/sheet2.xml" to
                    """<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>Inline</t></is></c></row></sheetData></worksheet>""",
                "xl/_rels/workbook.xml.rels" to """
                    <Relationships>
                      <Relationship Id="rId1" Target="worksheets/sheet1.xml"/>
                      <Relationship Id="rId2" Target="worksheets/sheet2.xml"/>
                    </Relationships>
                """.trimIndent(),
                "xl/sharedStrings.xml" to
                    """<sst><si><t>Name</t></si><si><t>City</t></si><si><t>Smith, John</t></si></sst>""",
            ),
        )

        val workbook = WorkbookReader(open).readXlsx(0)

        assertEquals(listOf("Data", "Notes"), workbook.sheetNames)
        assertEquals("Data", workbook.grid.name)
        assertEquals(listOf("Name", "City"), workbook.grid.rows[0])
        // r="3" skips a row, and the gap is preserved rather than closed up.
        assertEquals(emptyList<String>(), workbook.grid.rows[1])
        assertEquals(listOf("Smith, John", "", "42", "TRUE"), workbook.grid.rows[2])
        assertEquals(4, workbook.grid.columnCount)
        // The preview must say that stored values are not formatted values.
        assertTrue(workbook.note!!.contains("not applied"))
    }

    @Test
    fun `an xlsx inline string is read from its own cell rather than the shared table`() {
        val open = container(
            listOf(
                "xl/workbook.xml" to """<workbook><sheets><sheet name="Only" sheetId="1" r:id="rId1"/></sheets></workbook>""",
                "xl/_rels/workbook.xml.rels" to
                    """<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>""",
                "xl/worksheets/sheet1.xml" to
                    """<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>Inline</t></is></c></row></sheetData></worksheet>""",
            ),
        )

        val workbook = WorkbookReader(open).readXlsx(0)

        assertEquals(listOf("Inline"), workbook.grid.rows[0])
    }

    @Test
    fun `an ods expands repeated cells and rows without expanding them without bound`() {
        val open = container(
            listOf(
                "content.xml" to """
                    <office:document-content><office:body><office:spreadsheet>
                      <table:table table:name="First">
                        <table:table-row>
                          <table:table-cell table:number-columns-repeated="2"><text:p>x</text:p></table:table-cell>
                          <table:table-cell><text:p>y</text:p></table:table-cell>
                        </table:table-row>
                        <table:table-row table:number-rows-repeated="2">
                          <table:table-cell><text:p>z</text:p></table:table-cell>
                        </table:table-row>
                      </table:table>
                      <table:table table:name="Second"/>
                    </office:spreadsheet></office:body></office:document-content>
                """.trimIndent(),
            ),
        )

        val workbook = WorkbookReader(open).readOds(0)

        // Both sheets are named even though only the first was read: the tab strip must not
        // under-report how many sheets the document has.
        assertEquals(listOf("First", "Second"), workbook.sheetNames)
        assertEquals(listOf("x", "x", "y"), workbook.grid.rows[0])
        assertEquals(listOf("z"), workbook.grid.rows[1])
        assertEquals(listOf("z"), workbook.grid.rows[2])
    }

    /**
     * The slide list is deliberately in the opposite order to the part names. A reader that sorts
     * `slide1.xml`, `slide2.xml` silently reorders every deck whose slides have been moved, which
     * is the single most common thing an author does to a deck.
     */
    @Test
    fun `a pptx is read in presentation order, not in part-name order`() {
        val open = container(
            listOf(
                "ppt/presentation.xml" to """
                    <p:presentation><p:sldIdLst>
                      <p:sldId id="256" r:id="rId2"/>
                      <p:sldId id="257" r:id="rId1"/>
                    </p:sldIdLst></p:presentation>
                """.trimIndent(),
                "ppt/_rels/presentation.xml.rels" to """
                    <Relationships>
                      <Relationship Id="rId1" Target="slides/slide1.xml"/>
                      <Relationship Id="rId2" Target="slides/slide2.xml"/>
                    </Relationships>
                """.trimIndent(),
                "ppt/slides/slide1.xml" to slideXml("Agenda", listOf("Point one", "Point two")),
                "ppt/slides/slide2.xml" to slideXml("Welcome", listOf("Opening")),
            ),
        )

        val deck = PresentationDeckReader(open).readOoxml()

        assertEquals(2, deck.slides.size)
        assertEquals("Welcome", deck.slides[0].title)
        assertEquals(listOf("Opening"), deck.slides[0].body)
        assertEquals("Agenda", deck.slides[1].title)
        assertEquals(listOf("Point one", "Point two"), deck.slides[1].body)
        assertEquals(1, deck.slides[0].number)
        assertEquals(2, deck.slides[1].number)
        // The reader must never let its outline be mistaken for a rendered slide.
        assertTrue(deck.note.contains("does not render"))
    }

    @Test
    fun `an odp keeps speaker notes out of the slide body`() {
        val open = container(
            listOf(
                "content.xml" to """
                    <office:document-content><office:body><office:presentation>
                      <draw:page draw:name="page1">
                        <draw:frame presentation:class="title"><draw:text-box><text:p>Kickoff</text:p></draw:text-box></draw:frame>
                        <draw:frame presentation:class="outline"><draw:text-box><text:p>Why now</text:p></draw:text-box></draw:frame>
                        <presentation:notes><draw:frame><draw:text-box><text:p>Remember to breathe</text:p></draw:text-box></draw:frame></presentation:notes>
                      </draw:page>
                    </office:presentation></office:body></office:document-content>
                """.trimIndent(),
            ),
        )

        val deck = PresentationDeckReader(open).readOpenDocument()

        assertEquals(1, deck.slides.size)
        assertEquals("Kickoff", deck.slides[0].title)
        assertEquals(listOf("Why now"), deck.slides[0].body)
    }

    private fun slideXml(title: String, bullets: List<String>): String = buildString {
        append("<p:sld><p:cSld><p:spTree>")
        append("<p:sp><p:nvSpPr><p:nvPr><p:ph type=\"title\"/></p:nvPr></p:nvSpPr>")
        append("<p:txBody><a:p><a:r><a:t>").append(title).append("</a:t></a:r></a:p></p:txBody></p:sp>")
        append("<p:sp><p:txBody>")
        bullets.forEach { append("<a:p><a:r><a:t>").append(it).append("</a:t></a:r></a:p>") }
        append("</p:txBody></p:sp>")
        append("</p:spTree></p:cSld></p:sld>")
    }
}
