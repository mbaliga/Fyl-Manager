package io.github.mbaliga.fylz.ui.components.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.core.format.FileFormatDescriptor
import io.github.mbaliga.fylz.data.DelimitedTableParser
import io.github.mbaliga.fylz.data.SheetGrid
import io.github.mbaliga.fylz.data.SpreadsheetKind
import io.github.mbaliga.fylz.data.WorkbookPreview
import io.github.mbaliga.fylz.data.WorkbookReader
import io.github.mbaliga.fylz.data.spreadsheetColumnLabel
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.ui.components.UniversalInspectorPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * A spreadsheet as a grid of its actual cells.
 *
 * CSV/TSV are parsed by [DelimitedTableParser] (a real RFC 4180 reader, so a quoted field with a
 * comma or a line break in it stays one cell); .xlsx/.xlsm and .ods are read by [WorkbookReader]
 * straight out of their ZIP containers. Formats with no bundled reader -- .xls, .xlsb, .numbers --
 * never reach here; they keep the universal inspector.
 *
 * The header states exactly what was parsed, and the footer states what was left out. Neither
 * ever quotes a row or column count for the file as a whole once a limit has been hit, because at
 * that point the only number this code can substantiate is how much of it was read.
 */
@Composable
fun SpreadsheetPreview(entry: FileEntry, descriptor: FileFormatDescriptor, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val kind = remember(descriptor.extension) { WorkbookReader.spreadsheetKind(descriptor.extension) }
    if (kind == null) {
        UniversalInspectorPreview(entry, descriptor, modifier, "Fylz has no bundled reader for this spreadsheet format.")
        return
    }
    var sheetIndex by remember(entry.uri) { mutableIntStateOf(0) }
    val parsed by produceState<Result<WorkbookPreview>?>(null, entry.uri, kind, sheetIndex) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val opener = {
                    context.contentResolver.openInputStream(entry.uri) ?: error("Unable to read this spreadsheet.")
                }
                when (kind) {
                    SpreadsheetKind.DELIMITED -> readDelimited(entry.name, descriptor.extension, opener)
                    SpreadsheetKind.OOXML -> WorkbookReader(opener).readXlsx(sheetIndex)
                    SpreadsheetKind.OPEN_DOCUMENT -> WorkbookReader(opener).readOds(sheetIndex)
                }
            }
        }
    }
    when (val result = parsed) {
        null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> result.fold(
            onSuccess = { workbook ->
                SpreadsheetContent(workbook, modifier) { requested -> sheetIndex = requested }
            },
            onFailure = { failure ->
                UniversalInspectorPreview(
                    entry,
                    descriptor,
                    modifier,
                    failure.message ?: "This spreadsheet could not be parsed safely.",
                )
            },
        )
    }
}

@Composable
private fun SpreadsheetContent(
    workbook: WorkbookPreview,
    modifier: Modifier,
    onSelectSheet: (Int) -> Unit,
) {
    val grid = workbook.grid
    val columns = minOf(grid.columnCount, MAX_DISPLAY_COLUMNS)
    // One scroll state shared by the column headings and every body row: separate states would
    // let the headings drift out of alignment with the cells they name.
    val horizontal = rememberScrollState()

    Column(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(workbook.formatLabel, style = MaterialTheme.typography.titleSmall)
            Text(
                gridSummary(grid),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (workbook.sheetNames.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                workbook.sheetNames.forEachIndexed { index, name ->
                    SheetTab(name, index == workbook.activeSheetIndex) { onSelectSheet(index) }
                }
            }
        }
        HorizontalDivider()
        if (grid.rowCount == 0 || columns == 0) {
            SpreadsheetNote("This sheet has no cells.")
            return@Column
        }
        Row(Modifier.fillMaxWidth()) {
            HeaderCell("", CORNER_WIDTH)
            Row(Modifier.horizontalScroll(horizontal)) {
                for (column in 0 until columns) HeaderCell(spreadsheetColumnLabel(column), CELL_WIDTH)
            }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(grid.rows) { index, row ->
                Row(Modifier.fillMaxWidth()) {
                    HeaderCell("${index + 1}", CORNER_WIDTH)
                    Row(Modifier.horizontalScroll(horizontal)) {
                        for (column in 0 until columns) ValueCell(row.getOrNull(column).orEmpty())
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item {
                Column {
                    workbook.note?.let { SpreadsheetNote(it) }
                    if (grid.rowsTruncated) {
                        SpreadsheetNote("Only the first ${grid.rowCount} rows of this sheet were read.")
                    }
                    if (grid.columnsTruncated || grid.columnCount > MAX_DISPLAY_COLUMNS) {
                        SpreadsheetNote("Only the first $MAX_DISPLAY_COLUMNS columns are shown.")
                    }
                }
            }
        }
    }
}

/**
 * Counts are stated as what was read, never as what the file contains: once a limit is hit, the
 * total is something this code has not seen and therefore cannot claim.
 */
private fun gridSummary(grid: SheetGrid): String = buildString {
    append(grid.name)
    append(" · ")
    if (grid.rowsTruncated) append("first ")
    append(grid.rowCount)
    append(if (grid.rowCount == 1) " row" else " rows")
    append(" × ")
    if (grid.columnsTruncated) append("first ")
    append(grid.columnCount)
    append(if (grid.columnCount == 1) " column" else " columns")
}

@Composable
private fun SheetTab(name: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            // A sheet named "Q1" must not become a 24dp target: the touch box keeps its 48dp in
            // both directions however short the label is.
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clickable(onClickLabel = "Show sheet $name", role = Role.Tab, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HeaderCell(label: String, width: Dp) {
    Box(
        Modifier
            .width(width)
            .heightIn(min = ROW_HEIGHT)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun ValueCell(value: String) {
    Box(
        Modifier
            .width(CELL_WIDTH)
            .heightIn(min = ROW_HEIGHT)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            // A cell holding an embedded newline is one cell; showing it on one line keeps the
            // grid's rows aligned, and the value itself is not altered.
            value.replace('\n', ' '),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SpreadsheetNote(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun readDelimited(name: String, extension: String, open: () -> InputStream): WorkbookPreview {
    // The byte cap has to be REPORTED, not just applied. A 4 MiB ceiling silently cut the tail off
    // a large csv and then drew the surviving rows as if they were the whole file -- a preview
    // quietly answering a different question than the one asked. The grid's own rowsTruncated flag
    // covers a row-count cap, not a byte cap, so the two are combined into the note below.
    val bounded = open().use { input -> readBoundedUtf8(input, MAX_DELIMITED_BYTES) }
    val text = bounded.text
    val delimiter = DelimitedTableParser.delimiterFor(extension, text)
    val grid = DelimitedTableParser.parse(name, text, delimiter)
    return WorkbookPreview(
        formatLabel = when (delimiter) {
            '\t' -> "Tab-separated values"
            ';' -> "Semicolon-separated values"
            '|' -> "Pipe-separated values"
            else -> "Comma-separated values"
        },
        sheetNames = listOf(name),
        activeSheetIndex = 0,
        grid = grid,
        note = if (bounded.truncated) {
            "Showing the first ${MAX_DELIMITED_BYTES / (1024 * 1024)} MiB of this file."
        } else {
            null
        },
    )
}

/** [readBoundedUtf8]'s answer: the text it actually read, and whether the file had more to give. */
private data class BoundedDelimitedText(val text: String, val truncated: Boolean)

private fun readBoundedUtf8(input: InputStream, maxBytes: Int): BoundedDelimitedText {
    val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (total < maxBytes) {
        val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - total))
        if (count < 0) break
        output.write(buffer, 0, count)
        total += count
    }
    // One more read decides truncation without keeping the byte: reaching the cap is not itself
    // proof there was more, and claiming truncation on a file that happens to be exactly the cap
    // would be its own small lie.
    val truncated = total >= maxBytes && input.read() >= 0
    return BoundedDelimitedText(
        text = String(output.toByteArray(), Charsets.UTF_8).removePrefix(BYTE_ORDER_MARK),
        truncated = truncated,
    )
}

/** The UTF-8 byte-order mark, which is a file-encoding marker and not a cell value. */
private const val BYTE_ORDER_MARK = "\uFEFF"

/** Columns drawn before the grid says it is showing only part of the sheet's width. */
private const val MAX_DISPLAY_COLUMNS = 40

/** Ceiling on the text pulled out of a delimited file for one preview. */
private const val MAX_DELIMITED_BYTES = 4 * 1024 * 1024

private val CELL_WIDTH = 132.dp
private val CORNER_WIDTH = 52.dp
private val ROW_HEIGHT = 34.dp
