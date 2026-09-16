package io.github.mbaliga.fylz.data

/**
 * One sheet's worth of cells, already bounded to what a preview will draw.
 *
 * [rows] is ragged on purpose -- a spreadsheet row that stops at column C is stored as three
 * cells, not padded to the sheet's width -- so the renderer pads for layout and nothing here
 * invents a cell the file does not contain. [columnCount] is the widest row actually seen.
 */
data class SheetGrid(
    val name: String,
    val rows: List<List<String>>,
    val columnCount: Int,
    val rowsTruncated: Boolean,
    val columnsTruncated: Boolean,
) {
    val rowCount: Int get() = rows.size

    fun cell(row: Int, column: Int): String = rows.getOrNull(row)?.getOrNull(column).orEmpty()
}

/**
 * A parsed spreadsheet as the preview needs it: the sheet on screen plus the names of the others.
 *
 * [note] is where a reader states what it did NOT do -- unapplied number formats, an unsupported
 * feature -- so the grid is never mistaken for a full-fidelity rendering.
 */
data class WorkbookPreview(
    val formatLabel: String,
    val sheetNames: List<String>,
    val activeSheetIndex: Int,
    val grid: SheetGrid,
    val note: String? = null,
)

/** Spreadsheet column labels: A, B, ... Z, AA, AB, ... */
fun spreadsheetColumnLabel(index: Int): String {
    require(index >= 0) { "Column index must not be negative." }
    var remaining = index
    val label = StringBuilder()
    while (true) {
        label.append('A' + (remaining % 26))
        remaining = remaining / 26 - 1
        if (remaining < 0) break
    }
    return label.reverse().toString()
}

/**
 * A delimiter-separated table parser that follows RFC 4180 rather than splitting on commas.
 *
 * Splitting on the delimiter is the bug this exists to avoid: it tears `"Smith, John"` into two
 * cells and turns a quoted address with a line break into two broken rows. This is a character
 * state machine instead -- a quoted field runs until its closing quote whatever it contains,
 * `""` inside a quoted field is one literal quote, and CRLF, LF and a bare CR all end a row.
 *
 * Everything is bounded, because a preview of a 400 MB export must still open: rows, columns and
 * the length of any one cell all have ceilings, and the result records which of them it hit.
 */
object DelimitedTableParser {
    const val MAX_ROWS = 5_000
    const val MAX_COLUMNS = 256
    const val MAX_CELL_CHARS = 4_000

    private const val SNIFF_CHARS = 64 * 1024

    /** Candidate separators, in the order a tie is broken. */
    private val CANDIDATES = listOf(',', '\t', ';', '|')

    /**
     * The separator to parse [sample] with.
     *
     * A .tsv is tab-separated by definition. For everything else the winner is whichever candidate
     * occurs most often *outside* quoted fields -- counting inside quotes is what makes a file of
     * quoted prose containing commas look comma-separated when it is really semicolon-separated.
     */
    fun delimiterFor(extension: String, sample: String): Char {
        if (extension.equals("tsv", ignoreCase = true) || extension.equals("tab", ignoreCase = true)) return '\t'
        val counts = HashMap<Char, Int>()
        var quoted = false
        var index = 0
        val limit = minOf(sample.length, SNIFF_CHARS)
        while (index < limit) {
            val character = sample[index]
            when {
                character == '"' -> quoted = !quoted
                !quoted && character in CANDIDATES -> counts[character] = (counts[character] ?: 0) + 1
            }
            index += 1
        }
        val best = CANDIDATES.maxByOrNull { counts[it] ?: 0 } ?: ','
        return if ((counts[best] ?: 0) > 0) best else ','
    }

    /** Parses [text] into a bounded grid named [name]. */
    fun parse(
        name: String,
        text: String,
        delimiter: Char,
        maxRows: Int = MAX_ROWS,
        maxColumns: Int = MAX_COLUMNS,
    ): SheetGrid {
        require(maxRows > 0 && maxColumns > 0) { "Invalid table preview limits." }
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var cellStarted = false
        var columnsTruncated = false
        var rowsTruncated = false
        var widest = 0
        var index = 0

        fun endCell() {
            if (row.size < maxColumns) {
                row.add(cell.toString())
            } else {
                columnsTruncated = true
            }
            cell.setLength(0)
            cellStarted = false
        }

        fun endRow(): Boolean {
            endCell()
            // A file ending in a newline must not produce a phantom final row of one empty cell.
            if (!(row.size == 1 && row[0].isEmpty() && index >= text.length)) {
                widest = maxOf(widest, row.size)
                rows.add(row)
            }
            row = mutableListOf()
            if (rows.size >= maxRows) {
                rowsTruncated = index < text.length
                return false
            }
            return true
        }

        while (index < text.length) {
            val character = text[index]
            if (quoted) {
                if (character == '"') {
                    if (index + 1 < text.length && text[index + 1] == '"') {
                        cell.appendBounded('"')
                        index += 2
                        continue
                    }
                    quoted = false
                    index += 1
                    continue
                }
                cell.appendBounded(character)
                index += 1
                continue
            }
            when (character) {
                // A quote only opens a quoted field at the start of one; a stray quote in the
                // middle of an unquoted field is a literal character, not a mode switch.
                '"' -> if (!cellStarted) { quoted = true; cellStarted = true } else cell.appendBounded(character)
                delimiter -> endCell()
                '\r' -> {
                    if (index + 1 < text.length && text[index + 1] == '\n') index += 1
                    index += 1
                    if (!endRow()) return SheetGrid(name, rows, widest, rowsTruncated, columnsTruncated)
                    continue
                }
                '\n' -> {
                    index += 1
                    if (!endRow()) return SheetGrid(name, rows, widest, rowsTruncated, columnsTruncated)
                    continue
                }
                else -> {
                    cellStarted = true
                    cell.appendBounded(character)
                }
            }
            index += 1
        }
        if (cell.isNotEmpty() || row.isNotEmpty() || rows.isEmpty()) {
            endCell()
            if (row.isNotEmpty() && !(row.size == 1 && row[0].isEmpty())) {
                widest = maxOf(widest, row.size)
                rows.add(row)
            }
        }
        return SheetGrid(name, rows, widest, rowsTruncated, columnsTruncated)
    }

    private fun StringBuilder.appendBounded(character: Char) {
        if (length < MAX_CELL_CHARS) append(character)
    }
}
