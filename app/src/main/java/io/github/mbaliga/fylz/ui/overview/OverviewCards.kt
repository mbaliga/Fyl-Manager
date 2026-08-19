package io.github.mbaliga.fylz.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.browse.readableLabel
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.library.FavoriteLocation
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.storage.StorageKind
import io.github.mbaliga.fylz.storage.label
import io.github.mbaliga.fylz.ui.components.EntryThumbnail
import io.github.mbaliga.fylz.util.formatBytes

/** Shared card chrome: 20dp corners and a soft raised surface, per the owner's export -- every
 *  card in the bento reads as one visual family regardless of what it shows. */
@Composable
internal fun OverviewCardSurface(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = color,
        tonalElevation = 1.dp,
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), content = content)
    }
}

@Composable
internal fun QuickAccessCard(
    card: OverviewCard.QuickAccess,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit = {},
    onOpenFile: (FileEntry) -> Unit = {},
    onGrantFullAccess: () -> Unit = {},
) {
    val root = card.root
    OverviewCardSurface(
        modifier = modifier.let { if (root != null && card.entryCount != null) it.clickable(onClick = onOpen) else it },
    ) {
        Text(
            root?.title ?: "Quick access",
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                !card.hasFullAccess || root == null -> EmptyFolderState(
                    icon = Icons.Outlined.Lock,
                    title = "No quick access yet",
                    body = "Grant full access to see Downloads and the other standard folders here.",
                    actionLabel = "Grant access",
                    onAction = onGrantFullAccess,
                )
                card.entryCount == null -> CircularProgressIndicator(Modifier.size(28.dp))
                card.entryCount == 0 -> EmptyFolderState(
                    icon = Icons.Outlined.FolderOpen,
                    title = "No ${root.title}!",
                    body = "Nothing here yet.",
                )
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (card.thumbnails.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            card.thumbnails.take(3).forEach { entry ->
                                EntryThumbnail(entry, size = 52.dp, modifier = Modifier.clickable { onOpenFile(entry) })
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    Text(
                        if (card.entryCount == 1) "1 item" else "${card.entryCount} items",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyFolderState(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
internal fun KindFolderCard(
    card: OverviewCard.KindFolder,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    OverviewCardSurface(
        modifier = modifier.clickable(onClick = onOpen),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                card.folderName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "More", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Open folder") }, onClick = { menuOpen = false; onOpen() })
                }
            }
        }
        Spacer(Modifier.weight(1f))
        // Honest, not device-wide: this is a count inside one folder, and says so.
        Text(
            "${card.count} ${kindNounFor(card.kind, card.count)} in ${card.folderName}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        formatLastEdited(card.lastModifiedMillis)?.let {
            Text(
                "Last edited $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
            )
        }
    }
}

/**
 * The card's own kind, as a countable noun. [readableLabel] is the display name for one entry and
 * is capitalised for a label; here the word sits mid-sentence after a number, so it is lowered and
 * pluralised -- except PDF, which is an initialism and stays upper.
 */
private fun kindNounFor(kind: EntryKind, count: Int): String {
    val one = when (kind) {
        EntryKind.PDF -> "PDF"
        EntryKind.OTHER -> "file"
        else -> kind.readableLabel().lowercase()
    }
    return if (count == 1) one else one + "s"
}

@Composable
internal fun StorageCard(
    card: OverviewCard.Storage,
    modifier: Modifier = Modifier,
    onRefreshUsage: () -> Unit = {},
    onGrantFullAccess: () -> Unit = {},
    onFindLargeFiles: () -> Unit = {},
    onCleanUpDuplicates: () -> Unit = {},
) {
    OverviewCardSurface(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Storage", style = MaterialTheme.typography.titleMedium)
            if (card.hasFullAccess && card.usedBytes != null && card.totalBytes != null) {
                Text(
                    "${formatBytes(card.usedBytes)} / ${formatBytes(card.totalBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        if (!card.hasFullAccess) {
            EmptyFolderState(
                icon = Icons.Outlined.Lock,
                title = "No storage figures without access",
                body = "Under folder-by-folder access Fylz cannot see how much space is used or by what.",
                actionLabel = "Grant access",
                onAction = onGrantFullAccess,
            )
            return@OverviewCardSurface
        }

        val usage = card.usage
        if (usage == null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (card.scanning) "Scanning..." else "Run a scan to see what's using your space.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (card.scanning) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                } else {
                    TextButton(onClick = onRefreshUsage) { Text("Scan now") }
                }
            }
        } else {
            val entries = storageLegendEntries(usage)
            SegmentedUsageBar(entries, storageAccountedBytes(usage))
            Spacer(Modifier.height(12.dp))
            UsageLegend(entries)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "As of ${formatScannedAt(usage.scannedAtMillis)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (card.scanning) {
                    CircularProgressIndicator(Modifier.size(16.dp))
                } else {
                    IconButton(onClick = onRefreshUsage, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Rescan")
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "Shown in GiB (1024-based) -- a little smaller than the decimal GB on the box.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onFindLargeFiles, modifier = Modifier.weight(1f)) { Text("Find large files") }
            OutlinedButton(onClick = onCleanUpDuplicates, modifier = Modifier.weight(1f)) { Text("Clean up duplicates") }
        }
    }
}

@Composable
private fun SegmentedUsageBar(entries: List<Pair<StorageKind, Long>>, totalBytes: Long, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (totalBytes > 0) {
            entries.forEach { (kind, bytes) ->
                val weight = bytes.toFloat() / totalBytes.toFloat()
                if (weight > 0f) {
                    Box(Modifier.weight(weight).fillMaxHeight().background(colorFor(kind)))
                }
            }
        }
    }
}

@Composable
private fun UsageLegend(entries: List<Pair<StorageKind, Long>>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        entries.forEach { (kind, bytes) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(colorFor(kind)))
                Spacer(Modifier.width(8.dp))
                Text(kind.label(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(formatBytes(bytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Fixed swatches, independent of theme -- decorative only, since [UsageLegend] always pairs each
 *  one with a name and a size (colour alone must never carry the meaning, per DESIGN.md). */
private fun colorFor(kind: StorageKind): Color = when (kind) {
    StorageKind.PHOTOS -> Color(0xFF4F8EF7)
    StorageKind.DOCUMENTS -> Color(0xFFB0479A)
    StorageKind.VIDEOS -> Color(0xFFE0524B)
    StorageKind.SOUNDS -> Color(0xFF7C5CFC)
    StorageKind.ARCHIVES -> Color(0xFFE8A33D)
    StorageKind.OTHER -> Color(0xFF8A8F98)
}

@Composable
internal fun DeletedFilesCard(
    card: OverviewCard.DeletedFiles,
    modifier: Modifier = Modifier,
    onSeeFiles: () -> Unit = {},
) {
    OverviewCardSurface(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Deleted Files", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onSeeFiles) { Text("See Files") }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.padding(end = 14.dp),
            ) {
                Text(
                    cappedCountLabel(card.totalCount),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
            Column {
                Text(
                    ByteTally(card.recoverableBytes, card.notReportedCount).describe() + " recoverable",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    card.retentionDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun PinnedCard(
    card: OverviewCard.Pinned,
    modifier: Modifier = Modifier,
    onOpenFavorite: (FavoriteLocation) -> Unit = {},
) {
    OverviewCardSurface(modifier) {
        Text("Pinned", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (card.favorites.isEmpty()) {
            Text(
                "Favourite a folder to see it here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            card.favorites.take(OVERVIEW_LIST_CARD_VISIBLE_ROWS).forEach { favorite ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenFavorite(favorite) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(favorite.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val extra = card.favorites.size - OVERVIEW_LIST_CARD_VISIBLE_ROWS
            if (extra > 0) {
                Text("+$extra more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun TagsCard(
    card: OverviewCard.Tags,
    modifier: Modifier = Modifier,
    onOpenTag: (String) -> Unit = {},
) {
    OverviewCardSurface(modifier) {
        Text("Tags", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (card.topTags.isEmpty()) {
            Text(
                "Tag a file to see it here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                card.topTags.take(OVERVIEW_LIST_CARD_VISIBLE_ROWS * 2).forEach { (tag, count) ->
                    AssistChip(
                        onClick = { onOpenTag(tag) },
                        label = { Text("$tag · $count") },
                        leadingIcon = { Icon(Icons.Outlined.Label, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    )
                }
            }
        }
    }
}
