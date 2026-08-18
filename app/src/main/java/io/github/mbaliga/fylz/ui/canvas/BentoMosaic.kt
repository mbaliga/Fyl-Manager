package io.github.mbaliga.fylz.ui.canvas

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.canvas.CanvasLayoutPolicy
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.data.DocumentRepository
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.FolderLocation
import io.github.mbaliga.fylz.ui.FolderPeek
import io.github.mbaliga.fylz.ui.components.EntryThumbnail
import io.github.mbaliga.fylz.ui.components.FolderFace
import io.github.mbaliga.fylz.ui.components.LocalShowExtensions
import io.github.mbaliga.fylz.ui.components.THUMBNAIL_PIXELS
import io.github.mbaliga.fylz.ui.components.displayName
import io.github.mbaliga.fylz.ui.landing.LandingSubject

private val TALL_CELL_HEIGHT = 176.dp
private val SHORT_CELL_HEIGHT = 84.dp

/**
 * The bento-grid home surface: [subject]'s children in a self-arranging four-column mosaic --
 * every directory and the two most recently modified media files get a 2x2 cell, everything else
 * a 1x1 one ([CanvasLayoutPolicy.bentoSpans]). No dragging: unlike [SubjectCanvas], arrangement
 * here is the grid's own job, not the user's.
 */
@Composable
fun BentoMosaic(
    subject: LandingSubject,
    repository: DocumentRepository,
    refreshKey: Int,
    onOpenFolder: (FolderLocation) -> Unit,
    onOpenFile: (FileEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    var failure by remember { mutableStateOf<String?>(null) }

    // Shared across every cell in this grid, keyed by subject+refresh the same way `children`
    // below is -- so a folder cell that scrolls off (LazyVerticalGrid disposes it) and back on
    // screen finds its peek already resolved instead of re-issuing `listChildren` for it, the
    // same cache-then-LaunchedEffect shape `FileCard`'s own `folderPeeks` already uses.
    val folderPeeks = remember(subject.folderUri, refreshKey) { mutableStateMapOf<Uri, FolderPeek>() }

    val children by produceState(initialValue = null as List<FileEntry>?, subject, refreshKey) {
        value = null
        failure = null
        value = runCatching { repository.listChildren(subject.treeUri, subject.folderUri) }
            .onFailure { failure = it.message ?: "Unable to read this location" }
            .getOrNull()
    }

    val listing = children
    when {
        listing == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        failure != null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(failure.orEmpty(), color = MaterialTheme.colorScheme.error)
        }

        listing.isEmpty() -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing here yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        else -> {
            val spans = remember(listing) { CanvasLayoutPolicy.bentoSpans(listing) }
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = modifier.fillMaxSize(),
            ) {
                itemsIndexed(
                    listing,
                    key = { _, entry -> entry.uri.toString() },
                    span = { index, _ -> GridItemSpan(spans[index]) },
                ) { index, entry ->
                    BentoCell(
                        entry = entry,
                        subject = subject,
                        repository = repository,
                        folderPeeks = folderPeeks,
                        tall = spans[index] == 2,
                        onOpen = {
                            if (entry.isDirectory) {
                                onOpenFolder(FolderLocation(entry.uri, entry.name))
                            } else {
                                onOpenFile(entry)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BentoCell(
    entry: FileEntry,
    subject: LandingSubject,
    repository: DocumentRepository,
    folderPeeks: MutableMap<Uri, FolderPeek>,
    tall: Boolean,
    onOpen: () -> Unit,
) {
    val height = if (tall) TALL_CELL_HEIGHT else SHORT_CELL_HEIGHT
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().height(height),
    ) {
        if (entry.isDirectory) {
            // Cache-first, `FileCard`'s own shape: a cell that already has an entry in
            // `folderPeeks` (e.g. it scrolled off-screen and back) skips `listChildren`
            // entirely instead of re-issuing it on every recomposition the grid throws away.
            LaunchedEffect(entry.uri) {
                if (folderPeeks.containsKey(entry.uri)) return@LaunchedEffect
                val peekChildren = runCatching { repository.listChildren(subject.treeUri, entry.uri) }
                    .getOrDefault(emptyList())
                val thumbs = peekChildren.filter { it.kind == EntryKind.IMAGE || it.kind == EntryKind.VIDEO }.take(3)
                val hasNonMedia = peekChildren.any { it.kind != EntryKind.IMAGE && it.kind != EntryKind.VIDEO }
                folderPeeks[entry.uri] = FolderPeek(peekChildren.size, thumbs, hasNonMedia)
            }
            // derivedStateOf so this cell only recomposes when its OWN key changes, not on every
            // other cell's peek arriving into the same SnapshotStateMap.
            val peek by remember(entry.uri, folderPeeks) {
                derivedStateOf { folderPeeks[entry.uri] ?: FolderPeek(0, emptyList(), false) }
            }
            FolderFace(entry, peek, Modifier.fillMaxSize().padding(8.dp))
        } else {
            BentoFileCell(entry, tall)
        }
    }
}

@Composable
private fun BentoFileCell(entry: FileEntry, tall: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        if (tall) {
            EntryThumbnail(entry, size = 120.dp, pixels = 384)
        } else {
            // A short cell's whole inner box is 68dp after padding; 40dp leaves the caption line
            // below it real headroom instead of the two nearly filling the cell between them.
            EntryThumbnail(entry, size = 40.dp, pixels = THUMBNAIL_PIXELS)
        }
        Text(
            displayName(entry.name, false, LocalShowExtensions.current),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
