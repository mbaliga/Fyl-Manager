package io.github.mbaliga.fylz.ui

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aarso.cellshell.ScrubberStop
import io.github.mbaliga.fylz.browse.ItemGesture
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.search.SearchHit
import io.github.mbaliga.fylz.search.SearchProgress
import io.github.mbaliga.fylz.ui.places.CollectionGrouping
import io.github.mbaliga.fylz.ui.places.LauncherCategory
import io.github.mbaliga.fylz.ui.places.RecentFile
import kotlinx.coroutines.launch
import java.time.LocalDate

/** The singular noun a collection's count uses: "3 documents", "1 image". */
private fun LauncherCategory.noun(): String = when (this) {
    LauncherCategory.DOCUMENTS -> "document"
    LauncherCategory.IMAGES -> "image"
    LauncherCategory.VIDEOS -> "video"
    LauncherCategory.APKS -> "app"
}

/**
 * A collection, shown as itself: the files of one kind from across the device, grouped by the day
 * they changed, newest first ("Wednesday, September 23 — 3 documents"), each day a strip of real
 * thumbnails. There is no query, no search box and no scope chips: the search that gathers the
 * files runs underneath and nothing about it shows.
 *
 * Taps and long presses are reported, not interpreted (see `TapPolicy`), so a collection selects,
 * previews and opens exactly as a folder does.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CollectionScreen(
    category: LauncherCategory,
    hits: List<SearchHit>,
    progress: SearchProgress?,
    selecting: Boolean,
    selectedUris: Set<Uri>,
    cutUris: Set<Uri>,
    onItemGesture: (FileEntry, ItemGesture) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = remember(hits) { hits.map { it.entry } }
    val groups = remember(entries) { CollectionGrouping.byDay(entries, { it.lastModifiedMillis }, LocalDate.now()) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val scanning = progress == null || !progress.complete

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                CollectionGrouping.countLabel(entries.size, category.noun()),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (scanning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        if (progress?.limitReached == true) {
            Text(
                "Showing the newest ${entries.size}. Narrow it by opening a folder for the rest.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (groups.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (scanning) "Looking for ${category.noun()}s…" else "Nothing here yet",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(groups, key = { it.date.toString() }) { group ->
                        Column(Modifier.padding(top = 10.dp)) {
                            Text(
                                group.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            Text(
                                CollectionGrouping.countLabel(group.items.size, category.noun()),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(group.items, key = { it.uri.toString() }) { entry ->
                                    CollectionCard(
                                        entry = entry,
                                        category = category,
                                        selecting = selecting,
                                        selected = entry.uri in selectedUris,
                                        dimmed = entry.uri in cutUris,
                                        onGesture = { onItemGesture(entry, it) },
                                    )
                                }
                            }
                        }
                    }
                }
                // The date ruler: dragging this scrollbar unfolds the days, current one highlighted.
                BrowserScrollbar(
                    stops = groups.mapIndexed { index, group -> ScrubberStop(group.shortTitle, index) },
                    itemCount = groups.size,
                    firstVisibleIndex = listState.firstVisibleItemIndex,
                    scrolling = listState.isScrollInProgress,
                    onScrubTo = { index -> scope.launch { listState.scrollToItem(index) } },
                )
            }
        }
    }
}

/** One file in a day's strip: a page-sized thumbnail with its name under it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollectionCard(
    entry: FileEntry,
    category: LauncherCategory,
    selecting: Boolean,
    selected: Boolean,
    dimmed: Boolean,
    onGesture: (ItemGesture) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    val file = remember(entry.uri) { RecentFile(entry.uri, entry.name, entry.mimeType) }
    Column(
        Modifier
            .width(104.dp)
            .alpha(if (dimmed) 0.45f else 1f)
            .combinedClickable(
                onClick = { onGesture(ItemGesture.TAP) },
                onLongClick = { onGesture(ItemGesture.LONG_PRESS) },
                role = Role.Button,
            )
            .semantics(mergeDescendants = true) { contentDescription = entry.name },
    ) {
        Surface(
            shape = shape,
            color = scheme.surfaceContainerHigh,
            border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) scheme.primary else scheme.outlineVariant),
            modifier = Modifier.size(width = 104.dp, height = 136.dp),
        ) {
            Box {
                Box(Modifier.matchParentSize().clip(shape)) {
                    RecentFace(FolderCardContent.Recent(file, category.storage), 0.93.dp)
                }
                if (selecting) {
                    SelectionMark(
                        selected,
                        Modifier.align(Alignment.BottomStart).padding(6.dp).background(scheme.surface.copy(alpha = 0.55f), androidx.compose.foundation.shape.CircleShape),
                    )
                }
            }
        }
        Text(
            entry.name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
    }
}
