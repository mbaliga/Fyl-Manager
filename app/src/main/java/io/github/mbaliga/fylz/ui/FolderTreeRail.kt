package io.github.mbaliga.fylz.ui

import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.data.DocumentRepository
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.FolderLocation

/** One drawn line of the tree: a folder, how deep it sits, and whether it is open. */
private data class TreeRail(
    val location: FolderLocation,
    val depth: Int,
    val expanded: Boolean,
    val loaded: Boolean,
    // Root-inclusive, ending at this rail's own location. Reported back whole on a tap so the
    // caller can replace a tab's stack rather than guess the path from one folder alone.
    val path: List<FolderLocation>,
)

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
        Box(modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Text(
                "Open a location to browse its folders here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

    LazyColumn(modifier.fillMaxWidth()) {
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
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(start = (rail.depth * 14).dp, end = 4.dp)
            .then(
                if (isCurrent) {
                    Modifier.background(
                        MaterialTheme.colorScheme.secondaryContainer,
                        RoundedCornerShape(8.dp),
                    )
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (expandable) {
            Box(
                Modifier.size(32.dp).clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = if (rail.expanded) "Collapse ${rail.location.name}" else "Expand ${rail.location.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = turn },
                )
            }
        } else {
            // The indent guide stands in for the missing chevron, so leaf rows still line up
            // with their siblings instead of sliding left by a control's width.
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                Spacer(
                    Modifier
                        .width(1.dp)
                        .heightIn(min = 16.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
        Text(
            rail.location.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isCurrent) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpen)
                .padding(vertical = 8.dp),
        )
    }
}

/** Depth-first walk of the open branches, newest expansion state applied. */
private fun flattenInto(
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
