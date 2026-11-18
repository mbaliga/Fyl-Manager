package io.github.mbaliga.fylz.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected as semanticsSelected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.browse.ItemGesture
import io.github.mbaliga.fylz.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.ui.components.EntryThumbnail
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Faded look for a file that has been cut and is waiting to be pasted. */
private const val CUT_ALPHA = 0.45f

/**
 * "12/11/26 · 118 KB" — the second line of a row and the caption under a grid icon. Folders have
 * no size to give, so they show the date alone.
 */
internal fun entryDetail(entry: FileEntry, zone: ZoneId = ZoneId.systemDefault()): String {
    val date = entry.lastModifiedMillis?.takeIf { it > 0L }?.let {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withZone(zone).format(Instant.ofEpochMilli(it))
    }
    val size = if (entry.isDirectory) null else entry.sizeBytes?.let(::formatStorageBytes)
    return listOfNotNull(date, size).joinToString(" · ")
}

/**
 * The selection circle. It exists only while selecting: an always-visible checkbox on every row
 * made a plain browse look like a form. Empty ring when off, filled with a tick when on.
 */
@Composable
internal fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (selected) scheme.primary else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(15.dp))
        } else {
            Canvas(Modifier.size(22.dp)) {
                drawCircle(
                    scheme.outline,
                    radius = size.minDimension / 2f - 1.dp.toPx(),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
    }
}

/**
 * One row of the list. A tap and a long press are reported, not interpreted: the browser decides
 * what they mean (see `TapPolicy`) because that depends on whether it is selecting and on a
 * setting. While selecting, a circle slides in on the left and a selected row is filled.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileListRow(
    entry: FileEntry,
    selecting: Boolean,
    selected: Boolean,
    onGesture: (ItemGesture) -> Unit,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    overline: String? = null,
    detail: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val label = if (entry.isDirectory) "Folder ${entry.name}" else entry.name
    Row(
        modifier = modifier
            .alpha(if (dimmed) CUT_ALPHA else 1f)
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .background(if (selected) scheme.primaryContainer else Color.Transparent)
            .combinedClickable(
                onClick = { onGesture(ItemGesture.TAP) },
                onLongClick = { onGesture(ItemGesture.LONG_PRESS) },
                role = Role.Button,
            )
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                semanticsSelected = selected
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            visible = selecting,
            enter = expandHorizontally() + fadeIn(),
            exit = shrinkHorizontally() + fadeOut(),
        ) {
            Row {
                SelectionMark(selected)
                Spacer(Modifier.width(12.dp))
            }
        }
        EntryThumbnail(entry, size = 44.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            if (overline != null) {
                Text(
                    overline,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                entry.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
            )
            Text(
                detail ?: entryDetail(entry),
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) scheme.onPrimaryContainer.copy(alpha = 0.8f) else scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One cell of the grid: a large icon, its name, and a caption. No card chrome. While selecting, a
 * ring sits on the icon's corner and a selected item gets a tick and its name in a filled pill —
 * the cue the reference shows, so a selection reads from across the screen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileGridCell(
    entry: FileEntry,
    selecting: Boolean,
    selected: Boolean,
    onGesture: (ItemGesture) -> Unit,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val label = if (entry.isDirectory) "Folder ${entry.name}" else entry.name
    Column(
        modifier
            .alpha(if (dimmed) CUT_ALPHA else 1f)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = { onGesture(ItemGesture.TAP) },
                onLongClick = { onGesture(ItemGesture.LONG_PRESS) },
                role = Role.Button,
            )
            .padding(vertical = 8.dp, horizontal = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                semanticsSelected = selected
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
            EntryThumbnail(entry, size = 76.dp)
            if (selecting) {
                SelectionMark(
                    selected,
                    Modifier.align(Alignment.BottomStart).background(scheme.surface.copy(alpha = 0.55f), CircleShape),
                )
            }
        }
        Spacer(Modifier.size(6.dp))
        Text(
            entry.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = if (selected) scheme.onPrimary else scheme.onSurface,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (selected) scheme.primary else Color.Transparent)
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
        val caption = entryDetail(entry)
        if (caption.isNotEmpty()) {
            Text(
                caption,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The title bar while selecting, after the reference: Select All on the left, how many are
 * selected in the middle, and a filled Done tick on the right.
 */
@Composable
internal fun SelectionTopBar(
    count: Int,
    allSelected: Boolean,
    onSelectAll: () -> Unit,
    onDone: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onSelectAll) { Text(if (allSelected) "Deselect All" else "Select All") }
            Text(
                when (count) {
                    0 -> "Select Items"
                    1 -> "1 Item"
                    else -> "$count Items"
                },
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            FilledIconButton(onClick = onDone, modifier = Modifier.semantics { contentDescription = "Done selecting" }) {
                Icon(Icons.Filled.Check, contentDescription = null)
            }
        }
    }
}

/**
 * The strip above a quick preview: open the file for real, add it to (or take it out of) the
 * selection, or close the card.
 */
@Composable
internal fun QuickPreviewBar(
    entry: FileEntry,
    selected: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                entry.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            if (!entry.isDirectory) TextButton(onClick = onOpen) { Text("Open") }
            TextButton(onClick = onToggleSelect) { Text(if (selected) "Remove from selection" else "Add to selection") }
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Close preview") }
        }
    }
}
