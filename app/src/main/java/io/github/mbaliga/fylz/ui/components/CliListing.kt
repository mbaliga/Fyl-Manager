package io.github.mbaliga.fylz.ui.components

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.data.DocumentRepository
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.util.formatBytes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One drawn line of the CLI listing: a file or folder, how deep it sits in the tree of open
 * directories beneath the current folder, and where the tree's vertical guides still run past it.
 *
 * [guides] holds one entry per ancestor depth: true draws that column's "│", false leaves it
 * blank -- the standard `tree`-command bookkeeping for knowing whether an ancestor branch still
 * has siblings coming after it.
 */
internal data class CliRow(
    val entry: FileEntry,
    val depth: Int,
    val isLastChild: Boolean,
    val guides: List<Boolean>,
)

/**
 * Depth-first walk of [entries] and every directory open in [expanded], in the same folders-first,
 * name-ordered shape every other listing in the app uses. Pure and JVM-testable on purpose: the
 * box-drawing bookkeeping (which ancestor still owes a "│") is the part worth getting right in
 * isolation, away from a real [DocumentRepository] or a composition.
 *
 * Extends the shape of `FolderTreeRail`'s own flatten -- depth-first, cached children keyed by
 * `Uri` -- past that rail's folders-only filter: a CLI listing has to show files too, since they
 * are exactly what the extension column exists for.
 */
internal fun buildCliRows(
    entries: List<FileEntry>,
    expanded: Set<Uri>,
    children: Map<Uri, List<FileEntry>>,
): List<CliRow> {
    val out = mutableListOf<CliRow>()
    fun walk(list: List<FileEntry>, depth: Int, guides: List<Boolean>) {
        val ordered = list.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
        ordered.forEachIndexed { index, entry ->
            val isLast = index == ordered.lastIndex
            out += CliRow(entry, depth, isLast, guides)
            if (entry.isDirectory && entry.uri in expanded) {
                walk(children[entry.uri].orEmpty(), depth + 1, guides + !isLast)
            }
        }
    }
    walk(entries, 0, emptyList())
    return out
}

/** The connector this row draws before its name -- the same characters `tree` prints. */
internal fun CliRow.connector(): String = buildString {
    guides.forEach { append(if (it) "│  " else "   ") }
    append(if (isLastChild) "└─ " else "├─ ")
}

private val CLI_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/**
 * The trailing size/date columns -- blank for a directory, whose size nobody has paid to walk
 * and sum, filled in for a file. Sizes go through [formatBytes], the same "12.4 KiB" every other
 * screen in the app prints, so the CLI theme's numbers never disagree with the rest of it.
 *
 * [zoneId] defaults to the device zone, same as [io.github.mbaliga.fylz.browse.entryStops], and
 * exists as a parameter for the same reason that one takes it: a test can pin UTC instead of
 * chasing whatever zone happens to be the CI runner's default.
 */
internal fun CliRow.trailingColumns(zoneId: ZoneId = ZoneId.systemDefault()): String {
    if (entry.isDirectory) return ""
    val size = entry.sizeBytes?.let { formatBytes(it) } ?: "--"
    val date = entry.lastModifiedMillis
        ?.let { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate().format(CLI_DATE_FORMAT) }
        ?: "----------"
    return "  ${size.padStart(9)}  $date"
}

/** This row's full drawn line, extension and all -- CLI never hides one behind a display preference. */
internal fun CliRow.line(zoneId: ZoneId = ZoneId.systemDefault()): String = buildString {
    append(connector())
    append(entry.name)
    if (entry.isDirectory) append("/")
    append(trailingColumns(zoneId))
}

/**
 * The CLI theme's own listing: a monospace tree of the current folder and every directory the
 * user has opened in place, box-drawn so the structure the owner asked to see is the layout
 * itself rather than a filed-away detail. Folders expand and collapse in place -- opening one
 * never navigates away from the folder you were reading -- and files hand a tap to [onOpenFile].
 *
 * Owns its own expansion state and child cache, the same shape [FolderTreeRail] uses for the
 * folder-only nav rail: cheap to open on a deep hierarchy, since nothing is read until a row is
 * tapped, and re-collapsing costs nothing because the cache never forgets what it already read.
 *
 * @param entries the current folder's direct children -- the folder itself is never a row here,
 *   matching how the grid/list/details views only ever draw a folder's contents, not the folder.
 * @param selected marks a row with a `>` in the gutter column instead of a badge -- CLI's own
 *   answer to the rest of the app's selection mark, cheap enough to read at a glance in a
 *   text-dense listing.
 * @param listState hoisted rather than internal, the same reason LIST/DETAILS/GRID already
 *   hoist theirs -- a caller outside this composable (a scroll-position gate, an edge scrubber)
 *   needs to read or drive where this listing sits without this function growing a second
 *   signature for every such reader.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CliListing(
    treeUri: Uri,
    entries: List<FileEntry>,
    repository: DocumentRepository,
    selected: Set<Uri>,
    selectionActive: Boolean,
    showHidden: Boolean,
    onOpenFile: (FileEntry) -> Unit,
    onToggleSelect: (FileEntry) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    var expanded by remember(treeUri) { mutableStateOf(emptySet<Uri>()) }
    val children = remember(treeUri, showHidden) { mutableStateMapOf<Uri, List<FileEntry>>() }

    // Read exactly the folders that are open and not yet cached, same discipline as the tree rail:
    // collapsing costs nothing, and re-expanding is served from what is already in `children`.
    LaunchedEffect(expanded, treeUri, showHidden) {
        expanded.filterNot { children.containsKey(it) }.forEach { uri ->
            val listed = runCatching { repository.listChildren(treeUri, uri) }.getOrDefault(emptyList())
            children[uri] = if (showHidden) listed else listed.filterNot { it.name.startsWith(".") }
        }
    }

    val visible = if (showHidden) entries else entries.filterNot { it.name.startsWith(".") }
    val rows = remember(visible, expanded, children.toMap()) { buildCliRows(visible, expanded, children) }

    LazyColumn(modifier.fillMaxWidth(), state = listState) {
        items(rows, key = { it.entry.uri.toString() }) { row ->
            CliListingRow(
                row = row,
                checked = row.entry.uri in selected,
                selectionActive = selectionActive,
                onOpen = {
                    if (row.entry.isDirectory) {
                        expanded = if (row.entry.uri in expanded) expanded - row.entry.uri else expanded + row.entry.uri
                    } else {
                        onOpenFile(row.entry)
                    }
                },
                onToggleSelect = { onToggleSelect(row.entry) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CliListingRow(
    row: CliRow,
    checked: Boolean,
    selectionActive: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            // Same hard-swap discipline as every other listing surface's rows: once a selection
            // is live, a tap joins/leaves it instead of expanding a folder or opening a file --
            // building a multi-file selection here used to mean long-pressing every file one at
            // a time, the one theme where a plain tap silently did the wrong thing.
            .combinedClickable(
                onClick = { if (selectionActive) onToggleSelect() else onOpen() },
                onLongClick = onToggleSelect,
            )
            .background(if (checked) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 3.dp),
    ) {
        Text(
            if (checked) "> " else "  ",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        Text(
            row.line(),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
