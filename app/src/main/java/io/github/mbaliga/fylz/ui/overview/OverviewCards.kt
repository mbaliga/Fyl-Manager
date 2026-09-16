package io.github.mbaliga.fylz.ui.overview

import android.net.Uri
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.R
import io.github.mbaliga.fylz.browse.readableLabel
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.history.RecentOpen
import io.github.mbaliga.fylz.library.FavoriteLocation
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.storage.LargeFileFact
import io.github.mbaliga.fylz.storage.StorageKind
import io.github.mbaliga.fylz.storage.StorageUsageSnapshot
import io.github.mbaliga.fylz.storage.label
import io.github.mbaliga.fylz.ui.components.EntryThumbnail
import io.github.mbaliga.fylz.ui.tactile.TactileButton
import io.github.mbaliga.fylz.ui.tactile.TactileButtonStyle
import io.github.mbaliga.fylz.ui.tactile.TactileIconKey
import io.github.mbaliga.fylz.ui.theme.FylzGeometry
import io.github.mbaliga.fylz.ui.theme.ShadowLevel
import io.github.mbaliga.fylz.ui.theme.hairline
import io.github.mbaliga.fylz.ui.theme.microLabel
import io.github.mbaliga.fylz.ui.theme.softShadow
import io.github.mbaliga.fylz.util.formatBytes

/** Shared card chrome: a Hyle radiusXl (16dp) plate, a [hairline] border, and a close
 *  [softShadow] -- hairline-plus-soft-shadow is the Hyle depth signature, replacing the old
 *  20dp-radius/no-op-tonalElevation/heavy 3dp shadowElevation combo (tonalElevation is a no-op on
 *  any Surface whose colour isn't exactly `colorScheme.surface`, which `surfaceContainerHigh`
 *  never is). Every card in the bento reads as one visual family regardless of what it shows. */
@Composable
internal fun OverviewCardSurface(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(FylzGeometry.RadiusXl)
    Surface(
        modifier = modifier.fillMaxWidth().softShadow(ShadowLevel.SM, shape),
        shape = shape,
        color = color,
        border = BorderStroke(1.dp, hairline()),
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
    onPickFolder: () -> Unit = {},
) {
    val root = card.root
    var menuOpen by remember { mutableStateOf(false) }
    OverviewCardSurface(
        modifier = modifier.let { if (root != null && card.entryCount != null) it.clickable(onClick = onOpen) else it },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                root?.title ?: "Quick access",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // The only affordance that reaches DesktopCallbacks.onPickQuickAccessFolder -- without
            // it a QUICK_ACCESS widget is permanently stuck on whatever config it was seeded with
            // (auto-resolved Downloads), since neither a tap (opens the target) nor a long-press
            // (drag-to-move) ever offers a way to point it at a different folder.
            Box {
                TactileIconKey(
                    icon = Icons.Outlined.MoreVert,
                    contentDescription = "More",
                    onClick = { menuOpen = true },
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Choose folder…") },
                        onClick = { menuOpen = false; onPickFolder() },
                    )
                }
            }
        }
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            card.thumbnails.take(3).forEach { entry ->
                                EntryThumbnail(entry, size = 48.dp, modifier = Modifier.clickable { onOpenFile(entry) })
                            }
                        }
                        Spacer(Modifier.height(8.dp))
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
            TactileButton(text = actionLabel, onClick = onAction, style = TactileButtonStyle.SECONDARY)
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
            // The onPrimaryContainer tint the old IconButton's Icon carried is lost here --
            // TactileIconKey's own recipe (plate/cap + onPlate/onCap ink) has no tint override
            // slot, so the glyph now reads in the tactile palette's own ink instead of matching
            // this card's primaryContainer background. Flag for the render pass: verify contrast
            // still holds against this card's own colour.
            Box {
                TactileIconKey(
                    icon = Icons.Outlined.MoreVert,
                    contentDescription = "More",
                    onClick = { menuOpen = true },
                )
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
                    TactileButton(text = "Scan now", onClick = onRefreshUsage, style = TactileButtonStyle.SECONDARY)
                }
            }
        } else {
            val entries = storageLegendEntries(usage)
            SegmentedUsageBar(entries, storageAccountedBytes(usage))
            Spacer(Modifier.height(12.dp))
            UsageLegend(visibleStorageLegendEntries(entries))
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "As of ${formatScannedAt(usage.scannedAtMillis)}",
                    style = microLabel(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (card.scanning) {
                    CircularProgressIndicator(Modifier.size(16.dp))
                } else {
                    TactileIconKey(icon = Icons.Outlined.Refresh, contentDescription = "Rescan", onClick = onRefreshUsage)
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
        // Stacked full-width, not squeezed side by side -- two SECONDARY caps sharing a row left
        // each one's label cramped at anything past a couple of words.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TactileButton(text = "Find large files", onClick = onFindLargeFiles, style = TactileButtonStyle.SECONDARY, fillWidth = true)
            TactileButton(text = "Clean up duplicates", onClick = onCleanUpDuplicates, style = TactileButtonStyle.SECONDARY, fillWidth = true)
        }
    }
}

/** The track (empty [entries]/[totalBytes]) is the surfaceVariant background itself, always drawn
 *  under everything -- so a scan with nothing accounted for still shows an honest flat bar rather
 *  than an empty gap. Segments sit [SEGMENT_GAP_DP] apart, letting that track colour show through
 *  as a hairline seam between kinds; only the bar's own outer ends are rounded (its own [clip]),
 *  not each individual segment. */
@Composable
private fun SegmentedUsageBar(entries: List<Pair<StorageKind, Long>>, totalBytes: Long, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(FylzGeometry.RadiusMd))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        horizontalArrangement = Arrangement.spacedBy(SEGMENT_GAP_DP),
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

private val SEGMENT_GAP_DP = 2.dp

/** [entries] is [visibleStorageLegendEntries]' already-filtered list when called from the
 *  graphical card -- every zero-byte kind dropped, per the reference frames, which show only the
 *  kinds actually present. [io.github.mbaliga.fylz.ui.overview.overviewCliLines] intentionally
 *  keeps calling [storageLegendEntries] (the unfiltered six) instead, so CLI mode stays honest
 *  about every kind the scan considered, zero or not. */
@Composable
private fun UsageLegend(entries: List<Pair<StorageKind, Long>>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            TactileButton(text = "See Files", onClick = onSeeFiles, style = TactileButtonStyle.SECONDARY)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(FylzGeometry.RadiusLg),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.padding(end = 16.dp),
            ) {
                Text(
                    cappedCountLabel(card.totalCount),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
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
            // 48dp rows on a 2dp gap, not 44dp rows on an 8dp one. These cards are laid out
            // on the desktop against a height budget WidgetRegistry records to the dp (PINNED
            // and RECENTS both have nothing spare at four rows), so the four missing dp per row
            // are taken out of the gap instead of added to the card: four rows now stand 198dp
            // tall where they stood 200, and every one of them is a real 48dp target.
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                card.favorites.take(OVERVIEW_LIST_CARD_VISIBLE_ROWS).forEach { favorite ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onOpenFavorite(favorite) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(favorite.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            val extra = card.favorites.size - OVERVIEW_LIST_CARD_VISIBLE_ROWS
            if (extra > 0) {
                Text(
                    "+$extra more",
                    style = microLabel(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
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
            // Kept stock: every chip here carries a leading Label icon alongside its text
            // ("$tag · $count"), and TactileButton's cap has no leading-icon slot to carry that
            // second glyph -- converting would silently drop the icon that reads "this is a tag"
            // at a glance. Per the mission's own escape hatch for this exact row.
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

// ── Desktop-only widget bodies ────────────────────────────────────────────────────────────────
//
// The five below have no [OverviewCard] grid membership (no [OverviewCard.id]/span/height) --
// nothing on [OverviewScreen] shows a Shelf, Recents, Search, Quick actions or Large files card
// today -- but they follow the exact same shape as every card above: a plain model (or plain
// parameters, for the ones with nothing worth wrapping) plus callbacks, framed in
// [OverviewCardSurface]. [io.github.mbaliga.fylz.ui.desktop.WidgetRenderers] is what actually
// reuses them, one per [io.github.mbaliga.fylz.desktop.DesktopWidgetType] that has no counterpart
// above.

/** The Shelf widget's own compact card: how many items are staged, up to three of their names,
 *  and a way in. [previewNames] is already the caller's chosen slice and order -- this composable
 *  only ever takes its own first three of whatever it is handed, never re-orders it. */
@Composable
internal fun ShelfCard(
    count: Int,
    previewNames: List<String>,
    modifier: Modifier = Modifier,
    onOpenShelf: () -> Unit = {},
) {
    OverviewCardSurface(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.desktop_widget_shelf), style = MaterialTheme.typography.titleMedium)
            Icon(Icons.Outlined.Inventory2, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        if (count == 0) {
            Text(
                stringResource(R.string.desktop_shelf_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                if (count == 1) "1 item" else "$count items",
                style = MaterialTheme.typography.bodyMedium,
            )
            previewNames.take(3).forEach { name ->
                Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.weight(1f))
        TactileButton(
            text = stringResource(R.string.desktop_open_shelf),
            onClick = onOpenShelf,
            style = TactileButtonStyle.SECONDARY,
            modifier = Modifier.align(Alignment.End),
        )
    }
}

/**
 * The Recents widget: up to four rows of what was opened lately, newest first. Row tap opens the
 * item; [nowMillis] is threaded in (rather than read internally) purely so [formatRelativeTime]'s
 * own caption stays testable against a pinned clock, per that function's own KDoc.
 */
@Composable
internal fun RecentsCard(
    items: List<RecentOpen>,
    nowMillis: Long,
    modifier: Modifier = Modifier,
    onOpenFile: (Uri) -> Unit = {},
) {
    OverviewCardSurface(modifier) {
        Text(stringResource(R.string.desktop_widget_recents), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (items.isEmpty()) {
            Text(
                stringResource(R.string.desktop_recents_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // 48dp rows on a 2dp gap, not 44dp rows on an 8dp one. These cards are laid out
            // on the desktop against a height budget WidgetRegistry records to the dp (PINNED
            // and RECENTS both have nothing spare at four rows), so the four missing dp per row
            // are taken out of the gap instead of added to the card: four rows now stand 198dp
            // tall where they stood 200, and every one of them is a real 48dp target.
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items.take(4).forEach { item ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onOpenFile(item.uri) },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            item.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        formatRelativeTime(nowMillis, item.openedAtMillis)?.let {
                            Text(it, style = microLabel(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** The Search widget: a single pill that hands focus to the app's own search entry point rather
 *  than duplicating a text field here -- [onFocusSearch] is what actually opens/focuses it. A real
 *  stadium pill at its own natural height (see [io.github.mbaliga.fylz.ui.desktop.WidgetRegistry]'s
 *  SEARCH entry) -- hairline plus a close shadow, not the old no-op `tonalElevation` (a no-op on
 *  any Surface whose colour isn't exactly `colorScheme.surface`). */
@Composable
internal fun SearchPill(modifier: Modifier = Modifier, onFocusSearch: () -> Unit = {}) {
    val shape = CircleShape
    Surface(
        modifier = modifier.fillMaxWidth().softShadow(ShadowLevel.SM, shape).clickable(onClick = onFocusSearch),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, hairline()),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.desktop_search_pill), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Four one-tap shortcuts to the actions that would otherwise need a folder open first: scan,
 *  search, the Shelf, the recycle bin. */
@Composable
internal fun QuickActionsCard(
    modifier: Modifier = Modifier,
    onScan: () -> Unit = {},
    onFocusSearch: () -> Unit = {},
    onOpenShelf: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
) {
    OverviewCardSurface(modifier) {
        Text(stringResource(R.string.desktop_widget_quick_actions), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        // Four equal weighted columns rather than SpaceEvenly: the disc stays 40dp (four discs
        // at their old 46dp implicit footprint overflowed a compact card's own content width,
        // and that is still true of four 48dp ones), but the TAP TARGET is now the whole column
        // -- disc, gap and label -- edge to edge with its neighbours. SpaceEvenly spent a third
        // of this row on gaps that looked tappable and were not; weight(1f) gives every pixel of
        // the row to one of the four actions, and the target grows from a 40dp disc to a full
        // quarter-of-the-card column about 60dp tall. See [QuickActionButton].
        Row(Modifier.fillMaxWidth()) {
            QuickActionButton(Icons.Outlined.Refresh, stringResource(R.string.desktop_quick_action_scan), onScan, Modifier.weight(1f))
            QuickActionButton(Icons.Outlined.Search, stringResource(R.string.desktop_quick_action_search), onFocusSearch, Modifier.weight(1f))
            QuickActionButton(Icons.Outlined.Inventory2, stringResource(R.string.desktop_quick_action_shelf), onOpenShelf, Modifier.weight(1f))
            QuickActionButton(Icons.Outlined.DeleteSweep, stringResource(R.string.desktop_quick_action_trash), onOpenTrash, Modifier.weight(1f))
        }
    }
}

private val QUICK_ACTION_DISC_DP = 40.dp

/**
 * One quick action: a 40dp disc over its own name.
 *
 * The click lives on the COLUMN, not on the disc. A 40dp disc is a 40dp touch target, and the
 * name printed under it -- which every user reads as part of the same control -- was dead. The
 * column is ~60dp tall (disc, 4dp gap, label) and, given a `weight(1f)` by its caller, a full
 * quarter of the card wide, so the target clears the floor on the axis it can and takes every
 * pixel available on the axis it cannot: a compact desktop widget is not wide enough for four
 * 48dp columns, and widening the disc instead would only push the card past the height its
 * seeded neighbours are laid out against.
 *
 * The [Icon] keeps [label] as its own [contentDescription] and the click carries it again as an
 * `onClickLabel`, so the action is named whether a reader merges the column or reads the glyph.
 */
@Composable
private fun QuickActionButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        // No padding of its own: this card's height is measured against a seeded desktop layout
        // (see WidgetRegistry's own table -- QUICK_ACTIONS has 4dp spare) and the column already
        // stands ~60dp tall without spending any of it.
        modifier = modifier.clickable(onClick = onClick, onClickLabel = label, role = Role.Button),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(QUICK_ACTION_DISC_DP),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * The Large files widget: the top five of [usage]'s own [StorageUsageSnapshot.largestFiles],
 * largest first, name plus size, with the same "no scan yet" / "scanning" absent-state split the
 * Storage card itself makes -- never a fabricated empty list standing in for "unknown".
 */
@Composable
internal fun LargeFilesCard(
    usage: StorageUsageSnapshot?,
    scanning: Boolean,
    modifier: Modifier = Modifier,
    onSeeAll: () -> Unit = {},
) {
    OverviewCardSurface(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.desktop_widget_large_files), style = MaterialTheme.typography.titleMedium)
            if (!scanning) {
                TactileButton(text = stringResource(R.string.desktop_see_all), onClick = onSeeAll, style = TactileButtonStyle.SECONDARY)
            }
        }
        Spacer(Modifier.height(8.dp))
        val files: List<LargeFileFact>? = usage?.largestFiles
        when {
            files == null -> Text(
                if (scanning) stringResource(R.string.desktop_large_files_empty_scanning) else stringResource(R.string.desktop_large_files_empty_unscanned),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            files.isEmpty() -> Text(
                stringResource(R.string.desktop_large_files_empty_unscanned),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    files.take(5).forEach { fact ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                fact.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                formatBytes(fact.sizeBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "As of ${formatScannedAt(usage.scannedAtMillis)}",
                    style = microLabel(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
