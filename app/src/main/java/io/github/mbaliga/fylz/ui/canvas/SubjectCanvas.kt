package io.github.mbaliga.fylz.ui.canvas

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.canvas.CanvasLayoutPolicy
import io.github.mbaliga.fylz.canvas.CanvasLayoutStore
import io.github.mbaliga.fylz.data.DocumentRepository
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.FolderLocation
import io.github.mbaliga.fylz.ui.ClusterGestureHooks
import io.github.mbaliga.fylz.ui.landing.LandingSubject

/**
 * The freeform-canvas home surface: [subject]'s children scattered at their own remembered
 * (or, the first time, cascaded) spots, dragged one at a time.
 *
 * Listing follows the leaf idiom [io.github.mbaliga.fylz.ui.picker.FylzPicker] uses for a folder
 * outside the tab machinery -- `produceState` keyed on [subject] and [refreshKey], null while
 * loading, an empty list rendered rather than spun on forever, a caught failure shown in place.
 * This is deliberately not the workspace tab's own `loading` flag: home has no tab open yet.
 *
 * Selection and the cluster drag now reach the canvas too -- [selectedUris], [selectionActive],
 * [onToggleSelection] and [cluster] are all optional and default to "no selection", so a caller
 * that hasn't wired them yet gets exactly today's behaviour. [CanvasTile] documents the hard-swap
 * gesture table that keeps arrange and selection from ever claiming the same finger.
 */
@Composable
fun SubjectCanvas(
    subject: LandingSubject,
    repository: DocumentRepository,
    refreshKey: Int,
    onOpenFolder: (FolderLocation) -> Unit,
    onOpenFile: (FileEntry) -> Unit,
    modifier: Modifier = Modifier,
    selectedUris: Set<Uri> = emptySet(),
    selectionActive: Boolean = false,
    onToggleSelection: ((FileEntry) -> Unit)? = null,
    cluster: ClusterGestureHooks? = null,
) {
    val context = LocalContext.current
    val store = remember { CanvasLayoutStore(context) }
    var failure by remember { mutableStateOf<String?>(null) }

    val children by produceState(initialValue = null as List<FileEntry>?, subject, refreshKey) {
        value = null
        failure = null
        value = runCatching { repository.listChildren(subject.treeUri, subject.folderUri) }
            .onFailure { failure = it.message ?: "Unable to read this location" }
            .getOrNull()
    }

    var placements by remember(subject.folderUri) {
        mutableStateOf(store.placements(subject.folderUri))
    }
    var arrangingUri by remember(subject.folderUri) { mutableStateOf<Uri?>(null) }

    LaunchedEffect(children, subject.folderUri) {
        val list = children ?: return@LaunchedEffect
        val present = list.mapTo(mutableSetOf()) { it.uri }
        store.prune(subject.folderUri, present)
        placements = placements.filterKeys { it in present }
    }

    BackHandler(enabled = arrangingUri != null) { arrangingUri = null }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewportWidthPx = constraints.maxWidth.toFloat()
        val viewportHeightPx = constraints.maxHeight.toFloat()
        val listing = children

        when {
            listing == null -> Row(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(Modifier.padding(4.dp))
            }

            failure != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(failure.orEmpty(), color = MaterialTheme.colorScheme.error)
            }

            listing.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing here yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            else -> {
                val visible = remember(listing) { CanvasLayoutPolicy.visibleSubset(listing) }
                // Every still-unplaced tile in `visible` renders at its own index as z (see
                // CanvasLayoutPolicy.defaultPlacement), never entering `placements` at all -- on a
                // freshly opened subject `placements` is empty, so leaving those z's out of this
                // max would let a grabbed unplaced tile "raise" to a z still under most of its
                // still-unplaced neighbors.
                val maxZ = maxOf(placements.values.maxOfOrNull { it.z } ?: 0, (visible.size - 1).coerceAtLeast(0))

                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(enabled = arrangingUri != null) { arrangingUri = null },
                ) {
                    if (listing.size > visible.size) {
                        Text(
                            "Showing ${visible.size} of ${listing.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                        )
                    }
                    visible.forEachIndexed { index, entry ->
                        // Keyed by uri, not just positioned by loop order: entries drop out on a
                        // prune or a refresh, and without an explicit key Compose can rebind one
                        // uri's remembered drag/default state onto a different uri that lands in
                        // the same slot.
                        key(entry.uri) {
                            val default = remember(entry.uri) {
                                CanvasLayoutPolicy.defaultPlacement(index, placements.values)
                            }
                            val placement = placements[entry.uri] ?: default
                            CanvasTile(
                                entry = entry,
                                placement = placement,
                                defaultPlacement = default,
                                arranging = arrangingUri == entry.uri,
                                viewportWidthPx = viewportWidthPx,
                                viewportHeightPx = viewportHeightPx,
                                onOpen = {
                                    if (entry.isDirectory) {
                                        onOpenFolder(FolderLocation(entry.uri, entry.name))
                                    } else {
                                        onOpenFile(entry)
                                    }
                                },
                                onEnterArrange = {
                                    placements = placements + (entry.uri to CanvasLayoutPolicy.raise(placement, maxZ))
                                    arrangingUri = entry.uri
                                },
                                onExitArrange = { if (arrangingUri == entry.uri) arrangingUri = null },
                                onCommit = { updated ->
                                    placements = placements + (entry.uri to updated)
                                    store.place(subject.folderUri, entry.uri, updated)
                                },
                                selected = entry.uri in selectedUris,
                                selectionActive = selectionActive,
                                onToggleSelection = onToggleSelection?.let { toggle -> { toggle(entry) } },
                                cluster = cluster,
                            )
                        }
                    }
                }
            }
        }
    }
}
