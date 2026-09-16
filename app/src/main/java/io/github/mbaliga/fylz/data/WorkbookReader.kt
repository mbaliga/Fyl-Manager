package io.github.mbaliga.fylz.data

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.util.Locale

/**
 * A real value grid out of a spreadsheet container, parsed here rather than guessed at.
 *
 * Both formats this reads are ZIP containers of XML, and both are read with a streaming SAX pass
 * so a 200 MB workbook costs a bounded amount of memory instead of a DOM the size of the file.
 * Nothing is decompressed to disk and no entry outside the ones named below is even opened.
 *
 * What this deliberately does NOT do, and what the preview therefore says out loud:
 * cell formatting is not applied. A date stored as the serial number 45324 is shown as 45324,
 * because applying a format means reading `xl/styles.xml`'s number-format table, and a preview
 * that guessed at it would be inventing a value the file does not literally contain.
 *
 * The input is supplied as a factory rather than a stream because a ZIP container's parts arrive
 * in whatever order the writer chose: the shared-string table can follow the sheet that cites it,
 * so the container is walked twice -- once for the workbook's structure, once for one sheet.
 */
class WorkbookReader(open: () -> InputStream) {

    private val parts = ZipXmlParts(open)

    /** Reads one sheet of an OOXML workbook (.xlsx/.xlsm). */
    fun readXlsx(sheetIndex: Int, maxRows: Int = MAX_ROWS, maxColumns: Int = MAX_COLUMNS): WorkbookPreview {
        val structure = readXlsxStructure()
        require(structure.sheets.isNotEmpty()) { "This workbook declares no sheets." }
        val index = sheetIndex.coerceIn(0, structure.sheets.lastIndex)
        val sheet = structure.sheets[index]
        val handler = SheetHandler(structure.sharedStrings, maxRows, maxColumns)
        val found = parts.walk { name, stream ->
            if (name.equals(sheet.part, ignoreCase = true)) {
                parts.parse(stream, handler)
                false
            } else {
                true
            }
        }
        require(found) { "This workbook's sheet data is missing from the container." }
        return WorkbookPreview(
            formatLabel = "Excel workbook",
            sheetNames = structure.sheets.map { it.name },
            activeSheetIndex = index,
            grid = SheetGrid(
                name = sheet.name,
                rows = handler.rows,
                columnCount = handler.widest,
                rowsTruncated = handler.rowsTruncated,
                columnsTruncated = handler.columnsTruncated,
            ),
            note = buildString {
                append("Cell values as stored; number and date formats are not applied.")
                if (structure.sharedStringsTruncated) {
                    append(" Part of this workbook's shared text was beyond the preview limit.")
                }
            },
        )
    }

    /** Reads one sheet of an OpenDocument spreadsheet (.ods/.ots). */
    fun readOds(sheetIndex: Int, maxRows: Int = MAX_ROWS, maxColumns: Int = MAX_COLUMNS): WorkbookPreview {
        val handler = OpenDocumentSheetHandler(sheetIndex, maxRows, maxColumns)
        val found = parts.walk { name, stream ->
            if (name.equals("content.xml", ignoreCase = true)) {
                parts.parse(stream, handler)
                false
            } else {
                true
            }
        }
        require(found) { "This OpenDocument container has no content.xml." }
        require(handler.sheetNames.isNotEmpty()) { "This spreadsheet declares no sheets." }
        val index = sheetIndex.coerceIn(0, handler.sheetNames.lastIndex)
        return WorkbookPreview(
            formatLabel = "OpenDocument spreadsheet",
            sheetNames = handler.sheetNames,
            activeSheetIndex = index,
            grid = SheetGrid(
                name = handler.sheetNames[index],
                rows = handler.rows,
                columnCount = handler.widest,
                rowsTruncated = handler.rowsTruncated,
                columnsTruncated = handler.columnsTruncated,
            ),
            note = "Cell values as displayed in the document; formulas are not recalculated.",
        )
    }

    private class SheetRef(val name: String, val part: String)

    private class Structure(
        val sheets: List<SheetRef>,
        val sharedStrings: List<String>,
        val sharedStringsTruncated: Boolean,
    )

    private fun readXlsxStructure(): Structure {
        val shared = SharedStringHandler()
        val workbook = WorkbookHandler()
        val rels = RelationshipHandler()
        val worksheetParts = mutableListOf<String>()
        parts.walk { name, stream ->
            when {
                name.equals("xl/sharedStrings.xml", ignoreCase = true) -> parts.parse(stream, shared)
                name.equals("xl/workbook.xml", ignoreCase = true) -> parts.parse(stream, workbook)
                name.equals("xl/_rels/workbook.xml.rels", ignoreCase = true) -> parts.parse(stream, rels)
                name.startsWith("xl/worksheets/", ignoreCase = true) &&
                    name.endsWith(".xml", ignoreCase = true) -> worksheetParts += name
            }
            true
        }
        val sheets = workbook.sheets.mapNotNull { (label, relationshipId) ->
            val target = rels.targets[relationshipId] ?: return@mapNotNull null
            SheetRef(label, resolveOoxmlTarget("xl", target))
        }
        // A workbook with no usable relationship table still has its sheets on disk under
        // predictable names; fall back to those rather than reporting an empty workbook.
        val resolved = sheets.ifEmpty {
            worksheetParts.sortedBy { part -> part.filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }
                .mapIndexed { position, part -> SheetRef(workbook.sheets.getOrNull(position)?.first ?: "Sheet ${position + 1}", part) }
        }
        return Structure(resolved, shared.strings, shared.truncated)
    }

    private class SharedStringHandler : DefaultHandler() {
        val strings = mutableListOf<String>()
        var truncated = false
        private val entry = StringBuilder()
        private val text = StringBuilder()
        private var capturing = false
        private var phonetic = 0
        private var totalChars = 0L

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            when (qName) {
                "si" -> entry.setLength(0)
                // Phonetic guides carry their own <t> runs; they are pronunciation hints, not the
                // cell's text, and concatenating them would corrupt every Japanese workbook.
                "rPh" -> phonetic += 1
                "t" -> if (phonetic == 0) { capturing = true; text.setLength(0) }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (capturing) text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (qName) {
                "t" -> if (capturing) { entry.append(text); capturing = false }
                "rPh" -> if (phonetic > 0) phonetic -= 1
                "si" -> {
                    if (strings.size >= MAX_SHARED_STRINGS || totalChars >= MAX_SHARED_CHARS) {
                        truncated = true
                        throw XmlPartStop()
                    }
                    val value = entry.toString().take(DelimitedTableParser.MAX_CELL_CHARS)
                    totalChars += value.length
                    strings += value
                }
            }
        }
    }

    private class WorkbookHandler : DefaultHandler() {
        val sheets = mutableListOf<Pair<String, String>>()

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            if (qName != "sheet") return
            if (sheets.size >= MAX_SHEETS) return
            val name = attributes.getValue("name") ?: "Sheet ${sheets.size + 1}"
            val relationshipId = attributes.getValue("r:id") ?: attributes.getValue("id") ?: return
            sheets += name to relationshipId
        }
    }

    private class SheetHandler(
        private val shared: List<String>,
        private val maxRows: Int,
        private val maxColumns: Int,
    ) : DefaultHandler() {
        val rows = mutableListOf<List<String>>()
        var widest = 0
        var rowsTruncated = false
        var columnsTruncated = false

        private var cells = HashMap<Int, String>()
        private var highestColumn = -1
        private var nextColumn = 0
        private var declaredRow: Int? = null
        private var cellType: String? = null
        private var column = 0
        private val cellText = StringBuilder()
        private val text = StringBuilder()
        private var capturing = false

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            when (qName) {
                "row" -> {
                    cells = HashMap()
                    highestColumn = -1
                    nextColumn = 0
                    declaredRow = attributes.getValue("r")?.toIntOrNull()
                }
                "c" -> {
                    cellType = attributes.getValue("t")
                    column = attributes.getValue("r")?.let { columnIndexOf(it) } ?: nextColumn
                    nextColumn = column + 1
                    cellText.setLength(0)
                }
                "v", "t" -> { capturing = true; text.setLength(0) }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (capturing) text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (qName) {
                "v" -> {
                    capturing = false
                    cellText.append(resolveValue(text.toString()))
                }
                // Inline strings: <c t="inlineStr"><is><t>text</t></is></c>. Runs concatenate.
                "t" -> { capturing = false; cellText.append(text) }
                "c" -> {
                    val value = cellText.toString()
                    if (value.isNotEmpty() && column >= 0) {
                        if (column < maxColumns) {
                            cells[column] = value.take(DelimitedTableParser.MAX_CELL_CHARS)
                            highestColumn = maxOf(highestColumn, column)
                        } else {
                            columnsTruncated = true
                        }
                    }
                }
                "row" -> finishRow()
            }
        }

        private fun resolveValue(raw: String): String = when (cellType) {
            "s" -> raw.trim().toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
            "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
            else -> raw
        }

        private fun finishRow() {
            // Honour the declared row number so blank rows keep their place, but only ever pad by
            // a bounded amount -- a sheet whose first row declares r="1048576" must not allocate.
            val target = declaredRow?.let { it - 1 }
            if (target != null && target > rows.size) {
                val padding = minOf(target - rows.size, maxRows - rows.size)
                repeat(padding.coerceAtLeast(0)) { rows += emptyList<String>() }
            }
            if (rows.size >= maxRows) {
                rowsTruncated = true
                throw XmlPartStop()
            }
            val width = highestColumn + 1
            rows += if (width <= 0) emptyList<String>() else (0 until width).map { cells[it].orEmpty() }
            widest = maxOf(widest, width)
        }
    }

    /**
     * ODF stores repetition rather than repeated cells: one `<table:table-cell>` with
     * `table:number-columns-repeated="500"` stands for five hundred cells. Expanding that blindly
     * is how an ODS preview turns a 3 KB file into a million-cell allocation, so repeats are
     * expanded only up to the preview's own column and row ceilings.
     */
    private class OpenDocumentSheetHandler(
        private val wantedSheet: Int,
        private val maxRows: Int,
        private val maxColumns: Int,
    ) : DefaultHandler() {
        val sheetNames = mutableListOf<String>()
        val rows = mutableListOf<List<String>>()
        var widest = 0
        var rowsTruncated = false
        var columnsTruncated = false

        private var sheetIndex = -1
        private var collecting = false
        private var row = mutableListOf<String>()
        private var rowRepeat = 1
        private var cellRepeat = 1
        private val cellText = StringBuilder()
        private val text = StringBuilder()
        private var capturing = false

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            when (qName) {
                "table:table" -> {
                    sheetIndex += 1
                    if (sheetNames.size < MAX_SHEETS) {
                        sheetNames += attributes.getValue("table:name") ?: "Sheet ${sheetIndex + 1}"
                    }
                    collecting = sheetIndex == wantedSheet.coerceAtLeast(0)
                }
                "table:table-row" -> if (collecting) {
                    row = mutableListOf()
                    rowRepeat = attributes.getValue("table:number-rows-repeated")?.toIntOrNull()?.coerceIn(1, maxRows) ?: 1
                }
                "table:table-cell", "table:covered-table-cell" -> if (collecting) {
                    cellText.setLength(0)
                    cellRepeat = attributes.getValue("table:number-columns-repeated")
                        ?.toIntOrNull()?.coerceIn(1, maxColumns) ?: 1
                }
                "text:p" -> if (collecting) { capturing = true; text.setLength(0) }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (capturing) text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (qName) {
                "text:p" -> if (capturing) {
                    capturing = false
                    if (cellText.isNotEmpty()) cellText.append('\n')
                    cellText.append(text)
                }
                "table:table-cell", "table:covered-table-cell" -> if (collecting) {
                    val value = cellText.toString().take(DelimitedTableParser.MAX_CELL_CHARS)
                    repeat(cellRepeat) {
                        if (row.size < maxColumns) row.add(value) else columnsTruncated = true
                    }
                }
                "table:table-row" -> if (collecting) {
                    // Trailing empties are ODF's padding to the sheet's declared width, not data.
                    while (row.isNotEmpty() && row.last().isEmpty()) row.removeAt(row.lastIndex)
                    repeat(rowRepeat) {
                        if (rows.size >= maxRows) {
                            rowsTruncated = true
                            return@repeat
                        }
                        rows += row.toList()
                        widest = maxOf(widest, row.size)
                    }
                }
                // Deliberately not an early stop: the sheet tab strip has to name every sheet
                // in the document, and a parse that quit at the end of the requested table would
                // report a two-sheet workbook as having however many it happened to reach.
                "table:table" -> collecting = false
            }
        }
    }

    companion object {
        const val MAX_ROWS = 2_000
        const val MAX_COLUMNS = 128
        const val MAX_SHEETS = 256

        private const val MAX_SHARED_STRINGS = 200_000
        private const val MAX_SHARED_CHARS = 4L * 1024L * 1024L

        /** "C" -> 2, "AB12" -> 27, or null when the reference has no usable column part. */
        fun columnIndexOf(reference: String): Int? {
            var index = 0
            var seen = false
            for (character in reference) {
                val offset = when (character) {
                    in 'A'..'Z' -> character - 'A' + 1
                    in 'a'..'z' -> character - 'a' + 1
                    else -> break
                }
                index = index * 26 + offset
                seen = true
                if (index > MAX_REFERENCE_COLUMN) return null
            }
            return if (seen) index - 1 else null
        }

        private const val MAX_REFERENCE_COLUMN = 1 shl 20

        /** The reader for a spreadsheet extension, or null when Fylz has none for it. */
        fun spreadsheetKind(extension: String): SpreadsheetKind? = when (extension.lowercase(Locale.ROOT)) {
            "csv", "tsv", "tab" -> SpreadsheetKind.DELIMITED
            "xlsx", "xlsm" -> SpreadsheetKind.OOXML
            "ods", "ots" -> SpreadsheetKind.OPEN_DOCUMENT
            else -> null
        }
    }
}

/**
 * Spreadsheet containers Fylz has a real reader for.
 *
 * `.xls` (the pre-2007 binary BIFF format), `.xlsb` (the binary OOXML variant) and Apple's
 * `.numbers` are deliberately absent: nothing on the classpath decodes them, so they keep the
 * bounded universal inspector instead of a grid Fylz cannot fill.
 */
enum class SpreadsheetKind { DELIMITED, OOXML, OPEN_DOCUMENT }
