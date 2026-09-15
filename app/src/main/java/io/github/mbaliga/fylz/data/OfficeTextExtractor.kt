package io.github.mbaliga.fylz.data

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.util.Locale

/**
 * A short text sample pulled from an OOXML document, for
 * [io.github.mbaliga.fylz.index.RuleField.TEXT_CONTENT] search matching -- not a full-fidelity
 * reader like [WorkbookReader]/[PresentationDeckReader], just enough of the document's own words
 * to make a "contains" search over it mean something. .xlsx/.pptx reuse those readers' own public
 * API directly; .docx has no existing reader in this app, so it gets a small handler here, built
 * on the same [ZipXmlParts] streaming access the other two use -- the safety properties (bounded
 * part size, bounded entry count, no external entities) come along for free rather than being
 * re-derived.
 *
 * Zero new Gradle dependency: an OOXML file is a ZIP of XML, decodable with `java.util.zip` and
 * the JVM's own SAX parser, per the hand-rolled-over-Apache-POI decision (no maintained Android
 * fork, real R8/minifyRelease risk).
 */
object OfficeTextExtractor {
    private const val MAX_SAMPLE_CHARS = 4_000
    private const val MAX_SHEETS_SAMPLED = 3

    fun supports(extension: String): Boolean =
        extension.lowercase(Locale.ROOT) in setOf("docx", "xlsx", "xlsm", "pptx", "ppsx")

    fun extract(open: () -> InputStream, extension: String): String? = runCatching {
        when (extension.lowercase(Locale.ROOT)) {
            "docx" -> extractDocx(open)
            "xlsx", "xlsm" -> extractXlsx(open)
            "pptx", "ppsx" -> extractPptx(open)
            else -> null
        }
    }.getOrNull()?.trim()?.take(MAX_SAMPLE_CHARS)?.ifBlank { null }

    private fun extractDocx(open: () -> InputStream): String? {
        val parts = ZipXmlParts(open)
        val handler = DocxBodyHandler()
        val found = parts.walk { name, stream ->
            if (name.equals("word/document.xml", ignoreCase = true)) {
                parts.parse(stream, handler)
                false
            } else {
                true
            }
        }
        return if (found) handler.text.toString() else null
    }

    /** Only as many sheets as it takes to fill [MAX_SAMPLE_CHARS] -- a search sample, not a copy
     * of the workbook, so there is no reason to walk every sheet of a 40-tab spreadsheet. */
    private fun extractXlsx(open: () -> InputStream): String? {
        val reader = WorkbookReader(open)
        val builder = StringBuilder()
        var sheetCount = 1
        var index = 0
        while (index < sheetCount && index < MAX_SHEETS_SAMPLED && builder.length < MAX_SAMPLE_CHARS) {
            val preview = runCatching { reader.readXlsx(index) }.getOrNull() ?: break
            sheetCount = preview.sheetNames.size
            preview.grid.rows.forEach { row -> row.forEach { cell -> if (cell.isNotBlank()) builder.append(cell).append(' ') } }
            index += 1
        }
        return builder.toString().ifBlank { null }
    }

    private fun extractPptx(open: () -> InputStream): String? {
        val deck = runCatching { PresentationDeckReader(open).readOoxml() }.getOrNull() ?: return null
        val builder = StringBuilder()
        for (slide in deck.slides) {
            slide.title?.let { builder.append(it).append(' ') }
            slide.body.forEach { builder.append(it).append(' ') }
            if (builder.length >= MAX_SAMPLE_CHARS) break
        }
        return builder.toString().ifBlank { null }
    }

    /** WordprocessingML's own text run is `<w:t>`, prefixed (unlike the spreadsheet/deck parts'
     * unprefixed elements) -- [ZipXmlParts] is namespace-unaware, so the qName really is "w:t". */
    private class DocxBodyHandler : DefaultHandler() {
        val text = StringBuilder()
        private var capturing = false

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            if (qName == "w:t") capturing = true
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (capturing) text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (qName) {
                "w:t" -> capturing = false
                "w:p" -> text.append('\n')
            }
            if (text.length >= MAX_SAMPLE_CHARS) throw XmlPartStop()
        }
    }
}
