package io.github.mbaliga.fylz.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CSV reader is a state machine rather than a `split(",")` because every one of these cases
 * is a file a real export produces and a naive split gets wrong.
 */
class DelimitedTableParserTest {

    private fun parse(text: String, delimiter: Char = ',') =
        DelimitedTableParser.parse("test", text, delimiter)

    @Test
    fun `a quoted field keeps the delimiter inside it`() {
        val grid = parse("name,city\n\"Smith, John\",Leeds\n")
        assertEquals(listOf("name", "city"), grid.rows[0])
        assertEquals(listOf("Smith, John", "Leeds"), grid.rows[1])
    }

    @Test
    fun `a quoted field keeps an embedded newline in one cell`() {
        val grid = parse("id,address\n1,\"12 Mill Lane\nLeeds\"\n")
        assertEquals(2, grid.rowCount)
        assertEquals("12 Mill Lane\nLeeds", grid.cell(1, 1))
    }

    @Test
    fun `a doubled quote inside a quoted field is one literal quote`() {
        val grid = parse("phrase\n\"she said \"\"no\"\"\"\n")
        assertEquals("she said \"no\"", grid.cell(1, 0))
    }

    @Test
    fun `a quote in the middle of an unquoted field is literal, not a mode switch`() {
        val grid = parse("size\n5\" pipe,next\n")
        assertEquals(listOf("5\" pipe", "next"), grid.rows[1])
    }

    @Test
    fun `CRLF, LF and a bare CR all end a row`() {
        assertEquals(3, parse("a\r\nb\r\nc").rowCount)
        assertEquals(3, parse("a\nb\nc").rowCount)
        assertEquals(3, parse("a\rb\rc").rowCount)
    }

    @Test
    fun `a trailing newline does not invent a final empty row`() {
        val grid = parse("a,b\nc,d\n")
        assertEquals(2, grid.rowCount)
        assertEquals(listOf("c", "d"), grid.rows[1])
    }

    @Test
    fun `a blank line in the middle of a file is preserved as a row`() {
        val grid = parse("a\n\nb\n")
        assertEquals(3, grid.rowCount)
        assertEquals(listOf(""), grid.rows[1])
    }

    @Test
    fun `empty input produces no rows rather than one phantom row`() {
        val grid = parse("")
        assertEquals(0, grid.rowCount)
        assertEquals(0, grid.columnCount)
    }

    @Test
    fun `a ragged row is not padded with cells the file does not contain`() {
        val grid = parse("a,b,c\nd\n")
        assertEquals(3, grid.columnCount)
        assertEquals(listOf("d"), grid.rows[1])
        assertEquals("", grid.cell(1, 2))
    }

    @Test
    fun `the row limit is reported rather than silently applied`() {
        val text = (1..50).joinToString("\n") { "row$it" }
        val grid = DelimitedTableParser.parse("test", text, ',', maxRows = 10)
        assertEquals(10, grid.rowCount)
        assertTrue(grid.rowsTruncated)
    }

    @Test
    fun `a file exactly at the row limit is not reported as truncated`() {
        val text = (1..10).joinToString("\n") { "row$it" } + "\n"
        val grid = DelimitedTableParser.parse("test", text, ',', maxRows = 10)
        assertEquals(10, grid.rowCount)
        assertFalse(grid.rowsTruncated)
    }

    @Test
    fun `tabs win for a tsv without sniffing the content`() {
        assertEquals('\t', DelimitedTableParser.delimiterFor("tsv", "a,b,c"))
    }

    @Test
    fun `separators inside quotes do not decide the delimiter`() {
        // Semicolon-separated, with prose full of commas in every quoted cell: counting inside
        // the quotes would elect the comma and shred the file.
        val sample = "a;b;c\n\"one, two, three\";\"four, five\";\"six, seven\"\n"
        assertEquals(';', DelimitedTableParser.delimiterFor("csv", sample))
    }

    @Test
    fun `a file with no candidate separator falls back to a comma`() {
        assertEquals(',', DelimitedTableParser.delimiterFor("csv", "single\ncolumn\nvalues"))
    }

    @Test
    fun `column labels follow spreadsheet lettering`() {
        assertEquals("A", spreadsheetColumnLabel(0))
        assertEquals("Z", spreadsheetColumnLabel(25))
        assertEquals("AA", spreadsheetColumnLabel(26))
        assertEquals("AB", spreadsheetColumnLabel(27))
        assertEquals("BA", spreadsheetColumnLabel(52))
    }
}

/** Reference and relationship arithmetic shared by the OOXML readers. */
class OoxmlAddressingTest {

    @Test
    fun `a cell reference resolves to a zero-based column`() {
        assertEquals(0, WorkbookReader.columnIndexOf("A1"))
        assertEquals(2, WorkbookReader.columnIndexOf("C12"))
        assertEquals(26, WorkbookReader.columnIndexOf("AA7"))
        assertNull(WorkbookReader.columnIndexOf("12"))
    }

    @Test
    fun `a relationship target resolves against the part that declared it`() {
        assertEquals("xl/worksheets/sheet1.xml", resolveOoxmlTarget("xl", "worksheets/sheet1.xml"))
        assertEquals("ppt/slides/slide3.xml", resolveOoxmlTarget("ppt", "slides/slide3.xml"))
        assertEquals("ppt/slides/slide1.xml", resolveOoxmlTarget("ppt", "/ppt/slides/slide1.xml"))
        assertEquals("ppt/notesSlides/notesSlide1.xml", resolveOoxmlTarget("ppt/slides", "../notesSlides/notesSlide1.xml"))
    }

    @Test
    fun `only the spreadsheet and deck formats with a bundled reader are claimed`() {
        assertEquals(SpreadsheetKind.DELIMITED, WorkbookReader.spreadsheetKind("csv"))
        assertEquals(SpreadsheetKind.OOXML, WorkbookReader.spreadsheetKind("xlsx"))
        assertEquals(SpreadsheetKind.OPEN_DOCUMENT, WorkbookReader.spreadsheetKind("ods"))
        // Binary spreadsheet formats: no bundled decoder, so none of them may be advertised.
        assertNull(WorkbookReader.spreadsheetKind("xls"))
        assertNull(WorkbookReader.spreadsheetKind("xlsb"))
        assertNull(WorkbookReader.spreadsheetKind("numbers"))

        assertEquals(DeckKind.OOXML, PresentationDeckReader.deckKind("pptx"))
        assertEquals(DeckKind.OPEN_DOCUMENT, PresentationDeckReader.deckKind("odp"))
        assertNull(PresentationDeckReader.deckKind("ppt"))
        assertNull(PresentationDeckReader.deckKind("key"))
    }
}
