package io.github.mbaliga.fylz.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.browse.readableLabel
import io.github.mbaliga.fylz.core.format.FileFormatRegistry
import io.github.mbaliga.fylz.core.format.PreviewFamily
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.util.formatBytes

// Copied from PreviewPane's routing tables rather than shared: they are file-private there, and
// the two panes are allowed to drift (quick-look drops the editor path entirely).
private val QUICK_LOOK_SEMANTIC_ZIP_DOCUMENTS = setOf(
    "docx", "docm", "dotx", "pptx", "pptm", "ppsx", "xlsx", "xlsm",
    "odt", "ods", "odp", "odg", "epub",
)
private val QUICK_LOOK_ZIP_CONTAINER_EXTENSIONS = setOf(
    "zip", "zipx", "apk", "aab", "apks", "xapk", "apkm", "jar", "war", "ear",
    "cbz", "3mf", "kmz", "usdz", "vsdx", "nupkg", "whl",
)

/**
 * Everything [QuickLookContent] reads to pick and draw a preview, frozen together.
 *
 * `entry` alone used to be the only thing frozen for the exit animation; `textContent` and
 * `loading` kept coming straight from the live parameters, which are reset by a caller-side effect
 * the instant focus clears. Freezing only the entry let the `when` in [QuickLookContent] re-branch
 * mid-fade onto a *different* preview family than the one on screen.
 */
private data class QuickLookSnapshot(
    val entry: FileEntry,
    val textContent: String?,
    val textTruncated: Boolean,
    val loading: Boolean,
)

/**
 * The transient preview card: content edge-to-edge inside a notched silhouette, with its actions
 * living in the notches rather than on top of the picture.
 *
 * ### Why the notches
 *
 * The card's whole point is that the file fills it. Chrome laid over the content — a header strip,
 * a footer of buttons — spends the card's best pixels describing the card. Cutting two corners
 * away instead ([NotchedCardShape]) means the content genuinely stops there, and the actions sit
 * in space the card no longer occupies: a long rail top-left that grows a slot per action, and
 * close alone bottom-right, diagonally opposite so a reach for one is never a near-miss on the
 * other. The notch interiors are painted in the surface colour rather than cut through to the
 * scrim, so an icon never has to survive whatever image happens to be behind it.
 *
 * The card is resizable from the free top-right corner and remembers the size it was left at.
 *
 * [entry] going null plays the exit animation rather than yanking the card away: the last non-null
 * snapshot — entry AND the preview state that went with it — keeps drawing while it fades out.
 */
@Composable
fun QuickLook(
    entry: FileEntry?,
    textContent: String?,
    textTruncated: Boolean,
    loading: Boolean,
    rail: List<QuickAction>,
    widthFraction: Float,
    heightFraction: Float,
    onScaleChange: (Float, Float) -> Unit,
    onAction: (QuickAction, FileEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    var lastShown by remember { mutableStateOf<QuickLookSnapshot?>(null) }
    if (entry != null) {
        lastShown = QuickLookSnapshot(entry, textContent, textTruncated, loading)
    }
    var moreOpen by remember(entry?.uri) { mutableStateOf(false) }

    // Back closes the "more" list before it closes the card: the list is the thing most recently
    // opened, and dismissing the whole preview to put it away would lose the file too.
    BackHandler(enabled = entry != null) { if (moreOpen) moreOpen = false else onDismiss() }

    AnimatedVisibility(
        visible = entry != null,
        enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.95f, animationSpec = tween(180)),
        exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.95f, animationSpec = tween(140)),
    ) {
        val shown = lastShown ?: return@AnimatedVisibility
        val density = LocalDensity.current
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            val viewportW = maxWidth
            val viewportH = maxHeight
            val slots = quickLookSlots(rail.size + 1)
            // The card can never be dragged narrower than its own chrome. The shape refuses to cut
            // a notch wider than `width - slot`, so a card below slot*(slots+1) gets a notch
            // narrower than the icon row drawn on it and the actions spill onto the picture. The
            // floor is derived from the rail, so pinning a fourth action widens it along with the
            // notch rather than leaving a size that used to be legal and no longer is.
            val minWidth = (QuickLookSlot * (slots + 1) / viewportW).coerceIn(0.4f, 1f)
            var w by remember { mutableStateOf(widthFraction) }
            var h by remember { mutableStateOf(heightFraction) }
            val cardWidthFraction = w.coerceIn(minWidth, 1f)

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                QuickLookCard(
                    shown = shown,
                    rail = rail,
                    moreOpen = moreOpen,
                    width = viewportW * cardWidthFraction,
                    height = viewportH * h,
                    onResize = { dx, dy ->
                        // Dragging the top-right grip: right widens, up grows taller, which is the
                        // direction the corner itself moves.
                        //
                        // Doubled because the card is centred: growing it by d moves each edge by
                        // d/2, so feeding the finger's travel in raw would slide the grip at half
                        // the finger's speed and visibly leave it behind on a quick drag.
                        w = (w + CENTRED_DRAG * dx / with(density) { viewportW.toPx() })
                            .coerceIn(minWidth, 1f)
                        h = (h - CENTRED_DRAG * dy / with(density) { viewportH.toPx() })
                            .coerceIn(0.3f, 0.95f)
                    },
                    onResizeEnd = { onScaleChange(w.coerceIn(minWidth, 1f), h) },
                    onToggleMore = { moreOpen = !moreOpen },
                    onAction = { onAction(it, shown.entry) },
                    onDismiss = onDismiss,
                )
                // The overflow list unrolls beneath the card, the same way a room reveals; the
                // Column re-centres as it grows, so the card rides up to make room.
                AnimatedVisibility(
                    visible = moreOpen,
                    enter = fadeIn(tween(160)) + expandVertically(tween(200)),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
                ) {
                    QuickLookOverflow(
                        actions = QuickAction.overflowFor(rail),
                        maxWidth = viewportW * cardWidthFraction,
                        onAction = {
                            moreOpen = false
                            onAction(it, shown.entry)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickLookCard(
    shown: QuickLookSnapshot,
    rail: List<QuickAction>,
    moreOpen: Boolean,
    width: Dp,
    height: Dp,
    onResize: (Float, Float) -> Unit,
    onResizeEnd: () -> Unit,
    onToggleMore: () -> Unit,
    onAction: (QuickAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val slots = quickLookSlots(rail.size + 1)
    Box(Modifier.width(width).height(height)) {
        // The panel behind the cut. Everything the notches remove reveals this, which is what
        // makes the notch interiors read as solid surface rather than as holes.
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
            modifier = Modifier.fillMaxSize(),
        ) {}

        // The content, clipped to the notched silhouette so the picture stops at the cut.
        Surface(
            onClick = {},
            shape = NotchedCardShape(railSlots = slots),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxSize(),
        ) {
            QuickLookContent(shown.entry, shown.textContent, shown.textTruncated, shown.loading)
        }

        // ── The rail, in the top-left notch ───────────────────────────────────────────
        Row(Modifier.align(Alignment.TopStart).height(QuickLookSlot)) {
            rail.forEach { action ->
                QuickLookSlotButton(action.icon, action.label) { onAction(action) }
            }
            QuickLookSlotButton(
                icon = Icons.Outlined.MoreHoriz,
                label = if (moreOpen) "Fewer actions" else "More actions",
                onClick = onToggleMore,
            )
        }

        // ── Close, alone in the bottom-right notch ────────────────────────────────────
        Box(Modifier.align(Alignment.BottomEnd)) {
            QuickLookSlotButton(Icons.Outlined.Close, "Close preview", onDismiss)
        }

        // ── Resize, in the corner the notches leave free ──────────────────────────────
        ResizeGrip(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(QuickLookSlot)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = onResizeEnd,
                        onDrag = { change, drag ->
                            change.consume()
                            onResize(drag.x, drag.y)
                        },
                    )
                },
        )
    }
}

/** One 48dp action cell. Sized to the slot so the rail and the shape's notch cannot disagree. */
@Composable
private fun QuickLookSlotButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(QuickLookSlot)) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
    }
}

/**
 * The resize affordance: two short arcs struck concentrically about the corner.
 *
 * Drawn rather than iconified because it has to read as *this corner is draggable* at a glance
 * without occupying a slot — arcs parallel to the corner say that; a glyph in a button would look
 * like a fourth action.
 */
@Composable
private fun ResizeGrip(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        val inset = size.minDimension * 0.30f
        val stroke = size.minDimension * 0.055f
        listOf(0.34f, 0.52f).forEach { fraction ->
            val r = size.minDimension * fraction
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(size.width - inset - r, inset - r),
                size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/** The actions that did not fit the rail, revealed beneath the card. */
@Composable
private fun QuickLookOverflow(
    actions: List<QuickAction>,
    maxWidth: Dp,
    onAction: (QuickAction) -> Unit,
) {
    if (actions.isEmpty()) return
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
        modifier = Modifier.padding(top = 10.dp).width(maxWidth),
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            actions.forEach { action ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onAction(action) }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(action.icon, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(action.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun QuickLookContent(
    entry: FileEntry,
    textContent: String?,
    textTruncated: Boolean,
    loading: Boolean,
) {
    val descriptor = remember(entry.name, entry.mimeType, entry.kind) {
        FileFormatRegistry.describe(entry.name, entry.mimeType, entry.kind)
    }
    Box(Modifier.fillMaxSize()) {
        when {
            loading -> Box(
                Modifier.fillMaxWidth().aspectRatio(4f / 3f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            entry.kind == EntryKind.MARKDOWN && textContent != null -> Column(Modifier.fillMaxSize()) {
                if (textTruncated) QuickLookTruncationNotice()
                MarkdownPreview(textContent, Modifier.fillMaxSize())
            }
            entry.kind == EntryKind.TEXT && textContent != null -> Column(Modifier.fillMaxSize()) {
                if (textTruncated) QuickLookTruncationNotice()
                // Read-only here even though the docked pane can edit: a transient card that
                // vanishes on the next tap elsewhere is the wrong place to hold unsaved text.
                MonospaceTextPreview(textContent, Modifier.fillMaxSize())
            }
            descriptor.family == PreviewFamily.IMAGE -> RichImagePreview(entry, Modifier.fillMaxSize())
            descriptor.family == PreviewFamily.PDF -> PdfPagerPreview(entry, descriptor, Modifier.fillMaxSize())
            descriptor.family == PreviewFamily.AUDIO || descriptor.family == PreviewFamily.VIDEO ->
                MediaFilePreview(entry, descriptor, Modifier.fillMaxSize())
            descriptor.family == PreviewFamily.FONT -> FontFilePreview(entry, descriptor, Modifier.fillMaxSize())
            descriptor.extension in QUICK_LOOK_SEMANTIC_ZIP_DOCUMENTS ->
                ZipDocumentPreview(entry, descriptor, Modifier.fillMaxSize())
            descriptor.extension in QUICK_LOOK_ZIP_CONTAINER_EXTENSIONS ->
                ZipArchivePreview(entry, descriptor, Modifier.fillMaxSize())
            descriptor.rendererId == "mesh-wireframe" || descriptor.rendererId == "dxf" ->
                GeometryFilePreview(entry, descriptor, Modifier.fillMaxSize())
            else -> UniversalInspectorPreview(entry, descriptor, Modifier.fillMaxSize())
        }

        // The name rides a gradient scrim along the foot rather than a divider-and-header band:
        // it has to be legible over an arbitrary image without stealing a strip of the content.
        QuickLookCaption(
            entry = entry,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 18.dp, bottom = 12.dp, end = QuickLookSlot),
        )
    }
}

@Composable
private fun QuickLookCaption(entry: FileEntry, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            entry.name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOfNotNull(entry.kind.readableLabel(), entry.sizeBytes?.let(::formatBytes)).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun QuickLookTruncationNotice() {
    Text(
        text = "Large file preview is limited to 512 KiB.",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth().padding(8.dp),
    )
}

/** A centred box moves each edge by half of any size change; the grip has to cover both halves. */
private const val CENTRED_DRAG = 2f
