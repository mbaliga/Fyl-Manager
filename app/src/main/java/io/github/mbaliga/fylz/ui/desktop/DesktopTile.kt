package io.github.mbaliga.fylz.ui.desktop

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
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
import io.github.mbaliga.fylz.R
import io.github.mbaliga.fylz.canvas.TilePlacement
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.data.DocumentRepository
import io.github.mbaliga.fylz.desktop.DesktopItem
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
import io.github.mbaliga.fylz.ui.tactile.TactileButton
import io.github.mbaliga.fylz.ui.tactile.TactileButtonStyle
import io.github.mbaliga.fylz.ui.theme.FylzGeometry
import io.github.mbaliga.fylz.ui.theme.ShadowLevel
import io.github.mbaliga.fylz.ui.theme.hairline
import io.github.mbaliga.fylz.ui.theme.softShadow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private const val SHORTCUT_TILE_WIDTH_DP = 92
private const val SHORTCUT_THUMB_BOX_DP = 64
private const val NUDGE_FRACTION = 0.08f
private const val ARRANGE_SCALE = 1.06f
private const val ARRANGE_ELEVATION = 10f

/**
 * A widget's dp footprint: width comes from [DesktopPolicy.widgetWidthFraction] against the LIVE
 * [viewportWidthDp] (the same fraction [DesktopPolicy.clampWidget]/[DesktopPolicy.snapWidget] keep
 * on screen, so the three can never drift), height is [WidgetRegistry]'s own content-fit constant
 * for [item]'s TYPE -- not a blanket SMALL/MEDIUM/LARGE table, since two widgets of the same size
 * class can need very different room (a Storage card's legend versus a Deleted-files count chip).
 */
internal fun desktopWidgetSize(item: DesktopItem.Widget, viewportWidthDp: Dp): DpSize {
    val height = WidgetRegistry.of(item.type).height
    val widthFraction = DesktopPolicy.widgetWidthFraction(item.size, viewportWidthDp.value)
    return DpSize(viewportWidthDp * widthFraction, height)
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
 * - **Shortcuts** ([DesktopItem.FolderShortcut]/[DesktopItem.FileShortcut]) render as a chip
 *   ([ShortcutTileContent]): an [EntryThumbnail] in a uniform 64dp box plus (when [showLabels]) a
 *   label. The target is probed once, HERE (not inside [ShortcutTileContent]), on
 *   [DocumentRepository.probe] (already IO-dispatched) -- this composable's own tap gesture needs
 *   to know whether the shortcut is reachable *before* deciding what a tap does (open it, or offer
 *   [BrokenShortcutDialog]), so the probe can no longer live purely inside the content composable
 *   the way it used to. A short tap opens the target when reachable, or offers the dialog when it
 *   is not -- except while [editing], where the remove badge sits in the same corner a stray tap
 *   could otherwise also read as "open", so activation is suppressed for as long as editing is on.
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
    worldHeightDp: Dp,
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

    // Resolved here, in a real @Composable context -- the semantics{} block a few lines down is a
    // plain SemanticsPropertyReceiver lambda, not @Composable, so stringResource() cannot be called
    // from inside it directly.
    val openActionLabel = stringResource(R.string.desktop_action_open)
    val moveLeftLabel = stringResource(R.string.desktop_move_left)
    val moveRightLabel = stringResource(R.string.desktop_move_right)
    val moveUpLabel = stringResource(R.string.desktop_move_up)
    val moveDownLabel = stringResource(R.string.desktop_move_down)
    val removeLabel = stringResource(R.string.desktop_remove_item)

    var dragOffsetPx by remember(item.id) { mutableStateOf(Offset.Zero) }
    val editingState = rememberUpdatedState(editing)

    // Widgets settle through clampWidget/snapWidget (width- AND viewport-aware, keeps the whole
    // card on the real screen); shortcut tiles keep the canvas safe band their 92dp footprint was
    // tuned for.
    fun settled(next: TilePlacement): TilePlacement = if (item is DesktopItem.Widget) {
        if (snapEnabled) {
            DesktopPolicy.snapWidget(next, item.size, viewportWidthDp.value, worldHeightDp.value)
        } else {
            DesktopPolicy.clampWidget(next, item.size, viewportWidthDp.value, worldHeightDp.value)
        }
    } else {
        if (snapEnabled) DesktopPolicy.snap(next) else DesktopPolicy.clamp(next)
    }

    fun nudged(dx: Float, dy: Float): TilePlacement =
        settled(placement.copy(x = placement.x + dx * NUDGE_FRACTION, y = placement.y + dy * NUDGE_FRACTION))

    // Probed once here (not inside ShortcutTileContent -- see this composable's own KDoc) so both
    // the tap gesture below and the rendered content agree on reachability.
    val shortcutTarget = shortcutTargetUri(item)
    val probe by produceState<ShortcutProbe>(ShortcutProbe.Loading, shortcutTarget, refreshKey) {
        value = ShortcutProbe.Loading
        value = shortcutTarget?.let { repository.probe(it) }?.let { ShortcutProbe.Found(it) } ?: ShortcutProbe.Missing
    }
    val shortcutReachable = probe !is ShortcutProbe.Missing
    var showBrokenDialog by remember(item.id) { mutableStateOf(false) }

    val onActivate: () -> Unit = {
        when (item) {
            is DesktopItem.FolderShortcut ->
                if (shortcutReachable) callbacks.onOpenFolderShortcut(item.treeUri, item.folderUri) else showBrokenDialog = true
            is DesktopItem.FileShortcut ->
                if (shortcutReachable) callbacks.onOpenFileShortcut(item.uri) else showBrokenDialog = true
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
                // A shortcut opens (or, when broken, offers the dialog) on tap unless editing --
                // see this composable's own KDoc; a widget always leaves the tap for its own
                // content to handle.
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
                        onCommit(settled(next))
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
                shape = RoundedCornerShape(if (item is DesktopItem.Widget) FylzGeometry.RadiusXl else FylzGeometry.RadiusLg)
                clip = false
            }
            .then(gestureModifier)
            .semantics {
                contentDescription = tileContentDescription(item)
                onClick(label = openActionLabel) { onActivate(); true }
                customActions = buildList {
                    add(CustomAccessibilityAction(moveLeftLabel) { onCommit(nudged(-1f, 0f)); true })
                    add(CustomAccessibilityAction(moveRightLabel) { onCommit(nudged(1f, 0f)); true })
                    add(CustomAccessibilityAction(moveUpLabel) { onCommit(nudged(0f, -1f)); true })
                    add(CustomAccessibilityAction(moveDownLabel) { onCommit(nudged(0f, 1f)); true })
                    if (editingState.value) {
                        add(CustomAccessibilityAction(removeLabel) { onRemove(); true })
                    }
                }
            },
    ) {
        when (item) {
            is DesktopItem.FolderShortcut, is DesktopItem.FileShortcut -> ShortcutTileContent(
                item = item,
                showLabels = showLabels,
                reachable = shortcutReachable,
                probe = probe,
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
            RemoveBadge(onRemove = onRemove, label = removeLabel, modifier = Modifier.align(Alignment.TopEnd))
        }
    }

    if (showBrokenDialog) {
        BrokenShortcutDialog(
            onRemove = { showBrokenDialog = false; onRemove() },
            onKeep = { showBrokenDialog = false },
        )
    }
}

private fun shortcutTargetUri(item: DesktopItem): Uri? = when (item) {
    is DesktopItem.FolderShortcut -> item.folderUri
    is DesktopItem.FileShortcut -> item.uri
    is DesktopItem.Widget -> null
}

private fun tileContentDescription(item: DesktopItem): String = when (item) {
    is DesktopItem.FolderShortcut -> "Folder ${item.displayName}"
    is DesktopItem.FileShortcut -> item.displayName
    is DesktopItem.Widget -> WidgetRegistry.of(item.type).let { "${it.type.name.lowercase().replace('_', ' ')} widget" }
}

/**
 * The remove-from-desktop badge: a 48dp touch target (this composable's own KDoc point 9) around a
 * visually smaller 28dp disc, so the tap area meets the hard 48dp gate without the mark itself
 * growing to match -- an [IconButton] (48dp by default) is layered directly over a decorative
 * [Surface] disc, both centred in the same [Box].
 *
 * **Kept stock (Build 11.5 wave-2 tactile sweep, deliberately not
 * [io.github.mbaliga.fylz.ui.tactile.TactileIconKey]):** the badge's whole identity is a small,
 * unambiguously-red disc riding the tile's corner -- errorContainer colour, 28dp, no bevel -- and
 * [TactileIconKey]'s contract has no tint/colour override, only `latched: Boolean` swapping between
 * its own two fixed RAISED CAP / plate looks (neither is red). Even ignoring colour, its own visual
 * footprint (48x44dp, [io.github.mbaliga.fylz.ui.tactile.TactileIconKey]'s own `IconKeyVisualWidth`/
 * `Height`) would roughly double this corner mark's size against a 92dp-wide [ShortcutTileContent]
 * chip or a compact widget card, very plausibly swallowing the thumbnail/label it sits over. The
 * "simplest honest" call from the wave-2 brief: keep this disc, its colour, and its small scale --
 * they are the badge's whole reason for existing -- and take the press behaviour ([IconButton]'s
 * ripple + 48dp target) as already matching the kit's own PRESS recipe intent well enough that
 * forcing the RAISED CAP skin on top would only cost legibility for no behavioural gain.
 */
@Composable
private fun RemoveBadge(onRemove: () -> Unit, label: String, modifier: Modifier = Modifier) {
    Box(modifier.offset(x = 10.dp, y = (-10).dp).size(48.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.size(28.dp).softShadow(ShadowLevel.SM, CircleShape),
        ) {}
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Offered instead of the (formerly dead) open callback when a shortcut's own target can no longer
 * be probed -- Remove takes it off the desktop the same way [RemoveBadge] does; Keep just dismisses,
 * for a target that is only temporarily unavailable (an unmounted SD card, a cloud-backed
 * document not currently synced).
 */
@Composable
private fun BrokenShortcutDialog(onRemove: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(stringResource(R.string.desktop_broken_dialog_title)) },
        text = { Text(stringResource(R.string.desktop_broken_dialog_body)) },
        // Remove just drops the desktop shortcut, not the (already-missing) target itself, so this
        // reads as an ordinary confirm rather than a DESTRUCTIVE one -- PRIMARY per the wave-2
        // brief's own default for a dialog's confirm side.
        confirmButton = {
            TactileButton(
                text = stringResource(R.string.desktop_broken_dialog_remove),
                onClick = onRemove,
                style = TactileButtonStyle.PRIMARY,
            )
        },
        dismissButton = {
            TactileButton(
                text = stringResource(R.string.desktop_broken_dialog_keep),
                onClick = onKeep,
                style = TactileButtonStyle.SECONDARY,
            )
        },
    )
}

/**
 * A shortcut's own chip -- the same treatment [CanvasTile][io.github.mbaliga.fylz.ui.canvas.CanvasTile]
 * gives a canvas tile, adapted for sitting directly on wallpaper: a translucent
 * `surfaceContainerHigh` fill (so it reads as a chip against art of any colour, never a flat block)
 * plus a [hairline] border and a close [softShadow], never the plain unstyled thumbnail-plus-label
 * the desktop used to draw straight onto the wallpaper.
 *
 * The chip itself is always FULL opacity, reachable or not -- only the thumbnail inside dims when
 * [reachable] is false, so a broken shortcut still reads as a real, tappable object (this
 * composable's own KDoc point 8) rather than fading into the wallpaper the way the whole-tile dim
 * used to.
 */
@Composable
private fun ShortcutTileContent(
    item: DesktopItem,
    showLabels: Boolean,
    reachable: Boolean,
    probe: ShortcutProbe,
) {
    val showExtensions = LocalShowExtensions.current
    val storedDisplayName = when (item) {
        is DesktopItem.FolderShortcut -> item.displayName
        is DesktopItem.FileShortcut -> item.displayName
        else -> ""
    }
    val isFolder = item is DesktopItem.FolderShortcut
    val targetUri = shortcutTargetUri(item)

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

    val chipShape = RoundedCornerShape(FylzGeometry.RadiusLg)
    Column(
        Modifier
            .width(SHORTCUT_TILE_WIDTH_DP.dp)
            .softShadow(ShadowLevel.SM, chipShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f), chipShape)
            .border(1.dp, hairline(), chipShape)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(SHORTCUT_THUMB_BOX_DP.dp), contentAlignment = Alignment.Center) {
            EntryThumbnail(
                entry,
                size = SHORTCUT_THUMB_BOX_DP.dp,
                pixels = if (isFolder) 256 else 384,
                modifier = Modifier.graphicsLayer { alpha = if (reachable) 1f else 0.5f },
            )
            if (!reachable) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceBright,
                    border = BorderStroke(1.dp, hairline()),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 4.dp, y = 4.dp)
                        .size(24.dp)
                        .softShadow(ShadowLevel.SM, CircleShape),
                ) {
                    Box(Modifier.padding(2.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.ErrorOutline,
                            contentDescription = stringResource(R.string.desktop_broken_shortcut),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
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
            if (!reachable) {
                Text(
                    stringResource(R.string.desktop_shortcut_missing_caption),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
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
    val size = desktopWidgetSize(item, viewportWidthDp)
    val quickAccess = if (item.type == DesktopWidgetType.QUICK_ACCESS) {
        resolveQuickAccess(item, repository, refreshKey, callbacks)
    } else {
        null
    }

    // No backing plate here any more -- every widget's own content (OverviewCardSurface, or
    // SearchPill's own stadium Surface) already draws the one and only surface a card needs; a
    // second, identically-coloured surface underneath it was pure Z-fighting (this composable's
    // own KDoc point 1).
    Box(Modifier.size(size.width, size.height)) {
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
