package io.github.mbaliga.fylz.ui

import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.data.DocumentRepository
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.FolderLocation

/** One drawn line of the tree: a folder, how deep it sits, and whether it is open. */
internal data class TreeRail(
    val location: FolderLocation,
    val depth: Int,
    val expanded: Boolean,
    val loaded: Boolean,
    // Root-inclusive, ending at this rail's own location. Reported back whole on a tap so the
    // caller can replace a tab's stack rather than guess the path from one folder alone.
    val path: List<FolderLocation>,
)

/**
 * A fixed, theme-independent palette for the rail, same reasoning as the cluster bulges'
 * [io.github.mbaliga.fylz.ui.cluster.InkSurface] family: this panel is a primary navigation
 * surface with its own identity, not a card that should shift with the wallpaper-derived scheme.
 */
private val TreeGround = Color(0xFF000000)
private val TreeOnGround = Color(0xFFFFFFFF)
private val TreeOnGroundDim = Color(0x99FFFFFF)

/** A hairline, deliberately dimmer than [TreeOnGround] so the spine reads as structure, not text. */
private val TreeSpine = Color(0x38FFFFFF)
private val TreeSpineWidth = 1.dp

/** Deep indigo full-row fill and the bright violet edge accent that replace the old inset pill. */
private val TreeSelectionFill = Color(0xFF1A1633)
private val TreeSelectionAccent = Color(0xFF7C4DFF)
private val TreeSelectionAccentWidth = 6.dp

/**
 * One level's width, shared by the row's own indent padding and by the spine's x-offsets below --
 * the two have to agree on the same measure or a spine drawn for an ancestor's column would not
 * land under that ancestor's own marker/chevron.
 */
private val TreeIndentUnit = 24.dp

/**
 * The nested folder navigator that sits under the locations room's quick links.
 *
 * The room used to answer only "which of my open locations do I want", which is the shallowest
 * possible question — every level below the root still had to be reached by tapping into the
 * listing one folder at a time and backing out again. The tree answers the rest of it: the
 * structure under whatever is open, drilled into in place, without disturbing the listing until
 * a row is actually chosen.
 *
 * It is deliberately quiet — folders only, no files, small type, thin indent guides, chevrons
 * that turn rather than swap. A tree that renders every file is a second file browser inside the
 * navigation drawer, and the room already has one of those a swipe away.
 *
 * Children load lazily, per expansion, and are cached for as long as the room lives. That keeps
 * opening the room cheap on a deep hierarchy: nothing is read until a chevron is turned, and
 * turning one twice does not read twice.
 *
 * @param ancestors the open location's folder stack, root first. Seeds the expansion so the
 *   path you are standing on is already open when the room appears.
 * @param onOpenFolder the full root-inclusive ancestor chain for the tapped folder, not just the
 *   folder itself — the rail is the only side that knows the chain, since it built it walking
 *   down from [ancestors]`.first()`. A caller that appended only the tapped folder to whatever a
 *   tab's stack already held could strand the breadcrumb wherever that stack happened to be.
 * @param showHidden dotfile folders (`name.startsWith(".")`) are read but left out of the tree
 *   unless this is on, matching the listing and the in-app picker.
 */
@Composable
internal fun FolderTreeRail(
    treeUri: Uri?,
    ancestors: List<FolderLocation>,
    repository: DocumentRepository,
    showHidden: Boolean,
    onOpenFolder: (List<FolderLocation>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val root = ancestors.firstOrNull()
    if (treeUri == null || root == null) {
        Box(modifier.fillMaxWidth().background(TreeGround).padding(16.dp)) {
            Text(
                "Open a location to browse its folders here.",
                style = MaterialTheme.typography.bodySmall,
                color = TreeOnGroundDim,
            )
        }
        return
    }

    // Keyed on showHidden too: flipping the setting has to invalidate the cache, or a folder
    // read before the flip would keep showing (or hiding) entries the new setting disagrees with.
    val children = remember(treeUri, showHidden) { mutableStateMapOf<Uri, List<FileEntry>>() }
    var expanded by remember(treeUri) { mutableStateOf(ancestors.map { it.uri }.toSet()) }
    val current = ancestors.last().uri

    // Read exactly the folders that are open and not yet cached. Keyed on the expansion set, so
    // collapsing costs nothing and re-expanding is served from the cache.
    LaunchedEffect(expanded, treeUri, showHidden) {
        expanded.filterNot { children.containsKey(it) }.forEach { uri ->
            val listed = runCatching { repository.listChildren(treeUri, uri) }.getOrDefault(emptyList())
            children[uri] = if (showHidden) listed else listed.filterNot { it.name.startsWith(".") }
        }
    }

    val rails = remember(expanded, children.toMap(), root) {
        buildList { flattenInto(root, listOf(root), 0, expanded, children, this) }
    }

    LazyColumn(modifier.fillMaxWidth().background(TreeGround)) {
        items(rails, key = { it.location.uri.toString() }) { rail ->
            val folders = children[rail.location.uri]?.count { it.isDirectory } ?: 0
            // A leaf is only known to be a leaf once it has been read; until then every folder
            // gets a chevron, because hiding one on an unread folder tells the user a subtree
            // does not exist when nobody has looked.
            val expandable = !rail.loaded || folders > 0
            TreeRailRow(
                rail = rail,
                expandable = expandable,
                isCurrent = rail.location.uri == current,
                onToggle = {
                    expanded = if (rail.expanded) expanded - rail.location.uri else expanded + rail.location.uri
                },
                onOpen = { onOpenFolder(rail.path) },
            )
        }
    }
}

@Composable
private fun TreeRailRow(
    rail: TreeRail,
    expandable: Boolean,
    isCurrent: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    val turn by animateFloatAsState(if (rail.expanded) 90f else 0f, label = "chevron")
    val isRoot = rail.depth == 0
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (isCurrent) Modifier.background(TreeSelectionFill) else Modifier)
            // Both the ancestor spines and the selection accent are painted in this row's own
            // local space -- x = 0 at the panel's left edge -- so they have to run BEFORE the
            // indent padding below moves that origin, or every depth past the root would draw
            // its columns shifted by its own indent. `rail.depth` alone is enough to know which
            // columns this row owes a line: `flattenInto` only recurses into a subtree while
            // walking it, so every row between an ancestor's first child and its last descendant
            // sits at a depth strictly greater than that ancestor's -- the moment a row's depth
            // drops back to (or below) that ancestor's own depth, it is no longer under it, and
            // "draw levels 0 until depth" stops including that column on its own.
            .drawBehind {
                val unitPx = TreeIndentUnit.toPx()
                val strokePx = TreeSpineWidth.toPx()
                for (level in 0 until rail.depth) {
                    val x = unitPx * level + unitPx / 2f
                    drawLine(TreeSpine, Offset(x, 0f), Offset(x, size.height), strokePx)
                }
                if (isCurrent) {
                    drawRect(TreeSelectionAccent, size = Size(TreeSelectionAccentWidth.toPx(), size.height))
                }
            }
            .padding(start = TreeIndentUnit * rail.depth, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            // The root carries a static marker instead of a chevron -- it is always the tree's
            // entry point, not one more expandable level among its own descendants.
            isRoot -> Box(
                Modifier.size(TreeIndentUnit).clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(10.dp).background(TreeOnGround))
            }
            expandable -> Box(
                Modifier.size(TreeIndentUnit).clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = if (rail.expanded) "Collapse ${rail.location.name}" else "Expand ${rail.location.name}",
                    tint = TreeOnGround,
                    modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = turn },
                )
            }
            // A leaf owns no line of its own any more -- the ancestor spines already carry the
            // structure past it, so this is nothing but a reserved gutter width to keep the name
            // column aligned with every sibling and cousin at the same depth.
            else -> Box(Modifier.size(TreeIndentUnit))
        }
        Text(
            rail.location.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Bold,
            color = TreeOnGround,
            maxLines = 1,
            // Long names run under the panel's edge and clip softly rather than ellipsizing
            // early -- there is no "..." glyph competing with the tree's own hairlines for room.
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpen)
                .padding(vertical = 12.dp),
        )
    }
}

/**
 * Depth-first walk of the open branches, newest expansion state applied.
 *
 * Internal rather than private so the depth bookkeeping ([TreeRailRow]'s spine columns depend on
 * it being right) is exercised by a JVM test without a composition.
 */
internal fun flattenInto(
    location: FolderLocation,
    path: List<FolderLocation>,
    depth: Int,
    expanded: Set<Uri>,
    children: Map<Uri, List<FileEntry>>,
    out: MutableList<TreeRail>,
) {
    val isOpen = location.uri in expanded
    val loaded = children.containsKey(location.uri)
    out += TreeRail(location, depth, isOpen, loaded, path)
    if (!isOpen) return
    children[location.uri]
        ?.filter { it.isDirectory }
        ?.sortedBy { it.name.lowercase() }
        ?.forEach { entry ->
            val child = FolderLocation(entry.uri, entry.name)
            flattenInto(child, path + child, depth + 1, expanded, children, out)
        }
}
