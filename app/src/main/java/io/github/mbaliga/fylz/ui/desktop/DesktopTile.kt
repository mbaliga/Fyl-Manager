package io.github.mbaliga.fylz.ui.desktop

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.mbaliga.fylz.canvas.TilePlacement
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.data.DocumentRepository
import io.github.mbaliga.fylz.desktop.DesktopItem
import io.github.mbaliga.fylz.desktop.DesktopItemSize
import io.github.mbaliga.fylz.desktop.DesktopPolicy
import io.github.mbaliga.fylz.desktop.DesktopWidgetType
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.storage.FileStorageProvider
import io.github.mbaliga.fylz.storage.StorageAccess
import io.github.mbaliga.fylz.storage.StorageRoot
import io.github.mbaliga.fylz.storage.StorageRootKind
import io.github.mbaliga.fylz.ui.components.EntryThumbnail
import io.github.mbaliga.fylz.ui.components.LocalShowExtensions
import io.github.mbaliga.fylz.ui.components.displayName
import io.github.mbaliga.fylz.ui.overview.OverviewCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private const val SHORTCUT_TILE_WIDTH_DP = 92
private const val NUDGE_FRACTION = 0.08f
private const val ARRANGE_SCALE = 1.06f
private const val ARRANGE_ELEVATION = 10f
private val WIDGET_UNIT_HEIGHT = 168.dp
private const val WIDGET_CORNER_DP = 20

/**
 * A widget's dp footprint for its own [DesktopItemSize] -- the mapping is a straightforward,
 * documented choice, not something derived from a widget's own content:
 *
 * - [DesktopItemSize.SMALL]: ~44% of the viewport's width, one unit tall.
 * - [DesktopItemSize.MEDIUM]: ~44% of the viewport's width, two units tall (a narrow, taller card).
 * - [DesktopItemSize.LARGE]: ~92% of the viewport's width, two units tall (the widest card).
 *
 * [WIDGET_UNIT_HEIGHT] is the one unit both taller sizes are multiples of.
 */
internal fun desktopWidgetSize(size: DesktopItemSize, viewportWidth: Dp): DpSize = when (size) {
    DesktopItemSize.SMALL -> DpSize(viewportWidth * 0.44f, WIDGET_UNIT_HEIGHT)
    DesktopItemSize.MEDIUM -> DpSize(viewportWidth * 0.44f, WIDGET_UNIT_HEIGHT * 2)
    DesktopItemSize.LARGE -> DpSize(viewportWidth * 0.92f, WIDGET_UNIT_HEIGHT * 2)
}

/** What [DocumentRepository.probe] found for a [DesktopItem.FolderShortcut]/[DesktopItem.FileShortcut]'s
 *  own target -- [Loading] and [Missing] are told apart deliberately, so a tile never dims itself
 *  during the brief window before the very first probe resolves. */
private sealed interface ShortcutProbe {
    object Loading : ShortcutProbe
    data class Found(val entry: FileEntry) : ShortcutProbe
    object Missing : ShortcutProbe
}

/**
 * One item on the desktop, gestures and all -- [CanvasTile][io.github.mbaliga.fylz.ui.canvas.CanvasTile]-
 * modelled long-press-lifts/drag-commits shape, adapted for a desktop that also hosts widgets:
 *
 * - **Shortcuts** ([DesktopItem.FolderShortcut]/[DesktopItem.FileShortcut]) render an
 *   [EntryThumbnail] plus (when [showLabels]) a label, exactly like a canvas tile. The target is
 *   probed on [DocumentRepository.probe] (already IO-dispatched) and dimmed, never removed, when
 *   the probe comes back empty -- see [ShortcutProbe]. A short tap opens the target, except while
 *   [editing] -- edit mode's own remove badge sits in the same corner a stray tap could otherwise
 *   also read as "open", so activation is suppressed for as long as editing is on.
 * - **Widgets** render inside a sized card ([desktopWidgetSize]) via [DesktopWidgetContent]. A
 *   short tap is left alone here -- Compose delivers it straight to whatever inside the widget's
 *   own content wants it (a button, a row) -- only a long press is intercepted, to lift the whole
 *   widget into a drag.
 *
 * Every drag has the same non-gesture twin [CanvasTile][io.github.mbaliga.fylz.ui.canvas.CanvasTile]
 * documents: [customActions] nudge the tile 8% of the viewport per activation and commit
 * immediately, available whether or not a drag is in flight.
 */
@Composable
internal fun DesktopTile(
    item: DesktopItem,
    editing: Boolean,
    arranging: Boolean,
    snapEnabled: Boolean,
    showLabels: Boolean,
    viewportWidthDp: Dp,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    repository: DocumentRepository,
    refreshKey: Int,
    widgetData: DesktopWidgetData,
    callbacks: DesktopCallbacks,
    onEnterArrange: () -> Unit,
    onExitArrange: () -> Unit,
    onCommit: (TilePlacement) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val placement = item.placement
    val haptics = LocalHapticFeedback.current

    var dragOffsetPx by remember(item.id) { mutableStateOf(Offset.Zero) }
    val editingState = rememberUpdatedState(editing)

    fun nudged(dx: Float, dy: Float): TilePlacement {
        val next = placement.copy(x = placement.x + dx * NUDGE_FRACTION, y = placement.y + dy * NUDGE_FRACTION)
        return if (snapEnabled) DesktopPolicy.snap(next) else DesktopPolicy.clamp(next)
    }

    val onActivate: () -> Unit = {
        when (item) {
            is DesktopItem.FolderShortcut -> callbacks.onOpenFolderShortcut(item.treeUri, item.folderUri)
            is DesktopItem.FileShortcut -> callbacks.onOpenFileShortcut(item.uri)
            is DesktopItem.Widget -> Unit // Tap passes through to the widget's own content instead.
        }
    }

    val gestureModifier = Modifier.pointerInput(item.id) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val liftedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                waitForUpOrCancellation() != null
            }
            when (liftedEarly) {
                // A shortcut opens on tap (unless editing -- see this composable's own KDoc); a
                // widget always leaves the tap for its own content to handle.
                true -> if (item !is DesktopItem.Widget && !editingState.value) onActivate()
                false -> Unit
                null -> {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onEnterArrange()
                    val completed = drag(down.id) { change ->
                        change.consume()
                        dragOffsetPx += change.positionChange()
                    }
                    if (completed) {
                        val next = TilePlacement(
                            x = placement.x + dragOffsetPx.x / viewportWidthPx,
                            y = placement.y + dragOffsetPx.y / viewportHeightPx,
                            z = placement.z,
                        )
                        dragOffsetPx = Offset.Zero
                        onCommit(if (snapEnabled) DesktopPolicy.snap(next) else DesktopPolicy.clamp(next))
                    } else {
                        dragOffsetPx = Offset.Zero
                    }
                    onExitArrange()
                }
            }
        }
    }

    Box(
        modifier
            .offset {
                IntOffset(
                    x = (placement.x * viewportWidthPx + dragOffsetPx.x).roundToInt(),
                    y = (placement.y * viewportHeightPx + dragOffsetPx.y).roundToInt(),
                )
            }
            .zIndex(placement.z.toFloat())
            .graphicsLayer {
                scaleX = if (arranging) ARRANGE_SCALE else 1f
                scaleY = if (arranging) ARRANGE_SCALE else 1f
                shadowElevation = if (arranging) ARRANGE_ELEVATION else 0f
            }
            .then(gestureModifier)
            .semantics {
                contentDescription = tileContentDescription(item)
                onClick(label = "Open") { onActivate(); true }
                customActions = buildList {
                    add(CustomAccessibilityAction("Move left") { onCommit(nudged(-1f, 0f)); true })
                    add(CustomAccessibilityAction("Move right") { onCommit(nudged(1f, 0f)); true })
                    add(CustomAccessibilityAction("Move up") { onCommit(nudged(0f, -1f)); true })
                    add(CustomAccessibilityAction("Move down") { onCommit(nudged(0f, 1f)); true })
                    if (editingState.value) {
                        add(CustomAccessibilityAction("Remove from desktop") { onRemove(); true })
                    }
                }
            },
    ) {
        when (item) {
            is DesktopItem.FolderShortcut, is DesktopItem.FileShortcut -> ShortcutTileContent(
                item = item,
                showLabels = showLabels,
                repository = repository,
                refreshKey = refreshKey,
            )
            is DesktopItem.Widget -> WidgetTileContent(
                item = item,
                viewportWidthDp = viewportWidthDp,
                repository = repository,
                refreshKey = refreshKey,
                widgetData = widgetData,
                callbacks = callbacks,
            )
        }

        if (editing) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp),
            ) {
                IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Remove from desktop",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

private fun tileContentDescription(item: DesktopItem): String = when (item) {
    is DesktopItem.FolderShortcut -> "Folder ${item.displayName}"
    is DesktopItem.FileShortcut -> item.displayName
    is DesktopItem.Widget -> WidgetRegistry.of(item.type).let { "${it.type.name.lowercase().replace('_', ' ')} widget" }
}

@Composable
private fun ShortcutTileContent(
    item: DesktopItem,
    showLabels: Boolean,
    repository: DocumentRepository,
    refreshKey: Int,
) {
    val showExtensions = LocalShowExtensions.current
    val targetUri = when (item) {
        is DesktopItem.FolderShortcut -> item.folderUri
        is DesktopItem.FileShortcut -> item.uri
        else -> null
    }
    val storedDisplayName = when (item) {
        is DesktopItem.FolderShortcut -> item.displayName
        is DesktopItem.FileShortcut -> item.displayName
        else -> ""
    }
    val isFolder = item is DesktopItem.FolderShortcut

    val probe by produceState<ShortcutProbe>(ShortcutProbe.Loading, targetUri, refreshKey) {
        value = ShortcutProbe.Loading
        value = targetUri?.let { repository.probe(it) }?.let { ShortcutProbe.Found(it) } ?: ShortcutProbe.Missing
    }

    val reachable = probe !is ShortcutProbe.Missing
    val entry = (probe as? ShortcutProbe.Found)?.entry ?: FileEntry(
        uri = targetUri ?: Uri.EMPTY,
        name = storedDisplayName,
        mimeType = if (isFolder) "vnd.android.document/directory" else "application/octet-stream",
        sizeBytes = null,
        lastModifiedMillis = null,
        flags = 0,
        kind = if (isFolder) EntryKind.DIRECTORY else EntryKind.OTHER,
    )
    val shownName = displayName(storedDisplayName.ifBlank { entry.name }, isFolder, showExtensions)

    Column(
        Modifier.width(SHORTCUT_TILE_WIDTH_DP.dp).graphicsLayer { alpha = if (reachable) 1f else 0.45f },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            EntryThumbnail(entry, size = if (isFolder) 56.dp else 72.dp, pixels = if (isFolder) 192 else 384)
            if (!reachable) {
                Icon(
                    Icons.Outlined.BrokenImage,
                    contentDescription = "Can't find this anymore",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.BottomEnd).size(16.dp),
                )
            }
        }
        if (showLabels) {
            Text(
                shownName,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun WidgetTileContent(
    item: DesktopItem.Widget,
    viewportWidthDp: Dp,
    repository: DocumentRepository,
    refreshKey: Int,
    widgetData: DesktopWidgetData,
    callbacks: DesktopCallbacks,
) {
    val size = desktopWidgetSize(item.size, viewportWidthDp)
    val quickAccess = if (item.type == DesktopWidgetType.QUICK_ACCESS) {
        resolveQuickAccess(item, repository, refreshKey, callbacks)
    } else {
        null
    }

    Box(
        Modifier
            .size(size.width, size.height)
            .clip(RoundedCornerShape(WIDGET_CORNER_DP.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        DesktopWidgetContent(
            item = item,
            data = widgetData,
            quickAccess = quickAccess,
            callbacks = callbacks,
            modifier = Modifier.size(size.width, size.height),
        )
    }
}

/**
 * Resolves one QUICK_ACCESS widget's own `config` target -- `"downloads"` auto-resolved the way
 * [io.github.mbaliga.fylz.ui.overview.OverviewScreen] resolves its own Downloads root, or a picked
 * `"tree:<treeUri>|folder:<folderUri>"` pair -- into the plain [OverviewCard.QuickAccess] model
 * [io.github.mbaliga.fylz.ui.overview.QuickAccessCard] already knows how to draw. Per-widget,
 * because [DesktopWidgetData] (shared, device-wide facts) has no natural home for a value that
 * differs by which specific QUICK_ACCESS widget instance is asking -- see that class's own KDoc.
 */
@Composable
private fun resolveQuickAccess(
    item: DesktopItem.Widget,
    repository: DocumentRepository,
    refreshKey: Int,
    callbacks: DesktopCallbacks,
): QuickAccessResolution {
    val context = LocalContext.current
    val config = item.config
    val label = config["label"]
    val target = config["target"]

    val resolvedRoot by produceState<StorageRoot?>(null, target, refreshKey) {
        value = if (target == QUICK_ACCESS_TARGET_DOWNLOADS || target == null) {
            withContext(Dispatchers.IO) {
                StorageAccess.available(context).flatMap { it.rootGroups(context) }
                    .firstOrNull { it.title == FileStorageProvider.GROUP_FOLDERS }
                    ?.roots
                    ?.filter(StorageRoot::opensDirectly)
                    ?.let { roots -> roots.firstOrNull { it.title == "Downloads" } ?: roots.firstOrNull() }
            }
        } else {
            null
        }
    }

    val pickedTreeUri = target?.takeIf { it.startsWith(QUICK_ACCESS_TREE_PREFIX) }
        ?.removePrefix(QUICK_ACCESS_TREE_PREFIX)
        ?.substringBefore(QUICK_ACCESS_FOLDER_MARKER)
        ?.let { Uri.parse(it) }
    val pickedFolderUri = target?.takeIf { it.startsWith(QUICK_ACCESS_TREE_PREFIX) }
        ?.substringAfter(QUICK_ACCESS_FOLDER_MARKER, missingDelimiterValue = "")
        ?.takeIf { it.isNotEmpty() }
        ?.let { Uri.parse(it) }

    val pickedListing by produceState<List<FileEntry>?>(null, pickedFolderUri, refreshKey) {
        value = pickedTreeUri?.let { tree ->
            pickedFolderUri?.let { folder ->
                runCatching { repository.listChildren(tree, folder) }.getOrNull()
            }
        }
    }
    val downloadsListing by produceState<List<FileEntry>?>(null, resolvedRoot, refreshKey) {
        value = resolvedRoot?.let { root ->
            runCatching { repository.listChildren(requireNotNull(root.treeUri), requireNotNull(root.documentUri)) }.getOrNull()
        }
    }

    return if (pickedTreeUri != null && pickedFolderUri != null) {
        val listing = pickedListing
        val root = StorageRoot(
            id = "quick-access:${item.id}",
            title = label ?: "Folder",
            kind = StorageRootKind.PICKER_SHORTCUT,
            treeUri = pickedTreeUri,
            documentUri = pickedFolderUri,
        )
        QuickAccessResolution(
            card = OverviewCard.QuickAccess(
                root = root,
                hasFullAccess = true,
                entryCount = listing?.size,
                thumbnails = listing.orEmpty().filter { it.kind == EntryKind.IMAGE || it.kind == EntryKind.VIDEO }.take(3),
            ),
            onOpen = { callbacks.onOpenFolderShortcut(pickedTreeUri, pickedFolderUri) },
        )
    } else {
        val root = resolvedRoot?.let { if (label != null) it.copy(title = label) else it }
        val listing = downloadsListing
        QuickAccessResolution(
            card = OverviewCard.QuickAccess(
                root = root,
                hasFullAccess = root != null,
                entryCount = listing?.size,
                thumbnails = listing.orEmpty().filter { it.kind == EntryKind.IMAGE || it.kind == EntryKind.VIDEO }.take(3),
            ),
            onOpen = {
                val resolved = root
                if (resolved?.treeUri != null && resolved.documentUri != null) {
                    callbacks.onOpenFolderShortcut(resolved.treeUri, resolved.documentUri)
                }
            },
        )
    }
}

private const val QUICK_ACCESS_TARGET_DOWNLOADS = "downloads"
private const val QUICK_ACCESS_TREE_PREFIX = "tree:"
private const val QUICK_ACCESS_FOLDER_MARKER = "|folder:"
