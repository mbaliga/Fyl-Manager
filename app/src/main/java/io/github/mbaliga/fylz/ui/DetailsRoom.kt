package io.github.mbaliga.fylz.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.browse.DetailFact
import io.github.mbaliga.fylz.browse.EntryDetails
import io.github.mbaliga.fylz.browse.TreeChild
import io.github.mbaliga.fylz.browse.TreeRow
import io.github.mbaliga.fylz.browse.TreeTarget
import io.github.mbaliga.fylz.browse.locationTree
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.FolderLocation
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The top room: what you are looking at, described.
 *
 * The pull-down space was reserved from the day the shell landed and left empty — the pattern
 * requires the gesture to stay unclaimed, and nothing had earned the surface. Details earn it.
 * Everywhere else in the app, "what is this thing" is either a two-line row under a filename or
 * a preview pane that only exists at ≥900dp, so on a phone the answer to "how big is this, when
 * did it change, where exactly does it live" was nowhere.
 *
 * It is deliberately read-first. The only things that act here are the tree rows, and all they do
 * is move you to a folder you can already see — nothing in this room changes a file. That is the
 * bottom room's job, and the split is the point: **up is what, down is what to do about it.**
 *
 * @param ancestors the open location's folder stack, root first, current folder last.
 * @param children the current folder's entries in display order; the tree lists these under it.
 * @param folderItemCount how many entries the current folder actually holds. Deliberately not
 *   `children.size`: the tree draws what the browser is showing, filter and all, but "Items" is
 *   a fact about the folder and must not shrink because a search box has text in it.
 * @param focused the file the browser has focused, if any — the room describes it in preference
 *   to the folder, because a focused file is the more specific answer to "what am I looking at".
 * @param selection everything currently selected; more than one entry switches the room to a
 *   summary, since ten separate detail panes is not a detail pane.
 * @param tagsFor the library's tags for a URI, looked up against whatever the room settles on as
 *   its subject rather than against the folder the caller guessed at.
 */
@Composable
internal fun DetailsRoom(
    ancestors: List<FolderLocation>,
    children: List<FileEntry>,
    folderItemCount: Int,
    focused: FileEntry?,
    selection: List<FileEntry>,
    tagsFor: (Uri) -> List<String>,
    onOpenAncestor: (Int) -> Unit,
    onOpenChild: (FileEntry) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        RoomHeading("Details")

        if (ancestors.isEmpty()) {
            Text(
                "No location is open.",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                "Open one from the storage home surface or the locations room, and this is where " +
                    "its contents are described.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            return@Column
        }

        val multiple = selection.size > 1
        // What this room is about. A focused file wins over a lone selected folder because it is
        // the more specific answer; with several selected, the room summarises instead but still
        // marks the last one touched in the tree, so "which of these did I just tap" is visible.
        val subject = focused ?: selection.singleOrNull()

        // The tree lists folders — the structure — plus the one file being described, so a
        // selected file has a visible place in the hierarchy instead of only a path string.
        val treeEntries = remember(children, subject) {
            children.filter(FileEntry::isDirectory) +
                listOfNotNull(subject?.takeUnless(FileEntry::isDirectory))
        }
        val subjectIndex = subject
            ?.let { entry -> treeEntries.indexOfFirst { it.uri == entry.uri } }
            ?.takeIf { it >= 0 }
        val rows = remember(ancestors, treeEntries, subjectIndex) {
            locationTree(
                ancestors = ancestors.map(FolderLocation::name),
                children = treeEntries.map { TreeChild(it.name, it.isDirectory) },
                focusedChild = subjectIndex,
            )
        }

        Text(
            when {
                multiple -> "${selection.size} items selected"
                subject != null -> subject.name
                else -> ancestors.last().name
            },
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(20.dp))
        RoomHeading("Where it lives")
        rows.forEach { row ->
            LocationTreeRow(
                row = row,
                onClick = when (val target = row.target) {
                    is TreeTarget.Ancestor -> ({ onOpenAncestor(target.index) })
                    is TreeTarget.Child -> ({ onOpenChild(treeEntries[target.index]) })
                    is TreeTarget.Hidden -> null
                },
            )
        }

        Spacer(Modifier.height(24.dp))
        RoomHeading(if (multiple) "About this selection" else "About")
        val location = ancestors.joinToString(" / ", transform = FolderLocation::name)
        val facts = when {
            multiple -> EntryDetails.selection(selection.map { it.kind }, selection.map { it.sizeBytes })
            subject != null -> EntryDetails.facts(
                kind = subject.kind,
                location = location,
                tags = tagsFor(subject.uri),
                sizeBytes = subject.sizeBytes,
                modified = formatModified(subject.lastModifiedMillis),
                mimeType = subject.mimeType,
                childCount = null,
            )
            else -> EntryDetails.facts(
                kind = EntryKind.DIRECTORY,
                location = location,
                tags = tagsFor(ancestors.last().uri),
                childCount = folderItemCount,
            )
        }
        facts.forEach { FactRow(it) }
    }
}

/**
 * One line of the location tree.
 *
 * The shape is the reference the owner supplied: a chevron, a folder glyph, the name, indented
 * by depth with a hairline guide running down each level it sits under, and the current row
 * marked by a bar in the accent along its leading edge.
 *
 * The bar is a shape, not just a colour, and the current row is also bolder and announces itself
 * to a screen reader — `docs/DESIGN.md` forbids colour carrying state on its own, and a tinted
 * band alone is exactly that.
 */
@Composable
private fun LocationTreeRow(row: TreeRow, onClick: (() -> Unit)?) {
    val accent = MaterialTheme.colorScheme.primary
    val guide = MaterialTheme.colorScheme.onSurface.copy(alpha = GUIDE_ALPHA)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TREE_ROW_HEIGHT)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(if (row.current) accent.copy(alpha = CURRENT_TINT_ALPHA) else Color.Transparent)
            .semantics {
                contentDescription = buildString {
                    append(if (row.directory) "Folder " else "")
                    append(row.label)
                    if (row.current) append(", current")
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(if (row.current) accent else Color.Transparent),
        )
        repeat(row.depth) {
            Box(Modifier.width(TREE_INDENT).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(guide))
            }
        }
        Box(Modifier.size(TREE_INDENT), contentAlignment = Alignment.Center) {
            if (row.directory) {
                Icon(
                    if (row.expanded) Icons.Outlined.KeyboardArrowDown else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (row.target is TreeTarget.Hidden) {
            Text(
                row.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
            return@Row
        }
        Icon(
            if (row.directory) Icons.Outlined.Folder else Icons.AutoMirrored.Outlined.InsertDriveFile,
            contentDescription = null,
            tint = if (row.current) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            row.label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (row.current) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (row.current) 1f else 0.75f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun FactRow(fact: DetailFact) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            fact.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            fact.value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * A timestamp as a person would read it, or null when the provider had nothing to say.
 *
 * Zero is treated as nothing, not as 1970: `DocumentsProvider`s routinely report `0` for
 * "unknown", and the details room's whole value is that it does not make things up.
 *
 * The formatter is built per call rather than held in a `val`. A cached one captures the locale
 * and zone that were current when the class loaded, so the room would keep printing the old
 * language after a locale change; this runs at most once per composition of a surface that only
 * composes while it is open.
 */
private fun formatModified(millis: Long?): String? = millis
    ?.takeIf { it > 0L }
    ?.let {
        DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(it))
    }

/** 48dp so a tree row clears `docs/DESIGN.md`'s minimum touch target; it is a real destination. */
private val TREE_ROW_HEIGHT = 48.dp

/** One indent step, and the width of the chevron column, so guides line up under chevrons. */
private val TREE_INDENT = 24.dp

private const val GUIDE_ALPHA = 0.18f
private const val CURRENT_TINT_ALPHA = 0.16f
