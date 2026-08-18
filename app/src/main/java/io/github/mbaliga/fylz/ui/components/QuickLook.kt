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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Anchor
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.browse.readableLabel
import io.github.mbaliga.fylz.core.format.FileFormatRegistry
import io.github.mbaliga.fylz.core.format.PreviewFamily
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.ui.cluster.InkContent
import io.github.mbaliga.fylz.ui.cluster.InkSurface
import io.github.mbaliga.fylz.util.formatBytes
import kotlinx.coroutines.delay

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
 * How the preview card sits relative to the rest of the app. [EXPANDED] is Quick Look proper: a
 * scrim, tap-away dismissal, the card front and centre. [ANCHORED] drops the scrim so the browser
 * underneath stays fully interactive while the card stays put -- for comparing a preview against
 * the folder it came from. [DOCKED] shrinks the card to a corner-parked mini and keeps its content
 * alive (a playing video keeps playing) while the user goes back to browsing.
 */
enum class PreviewCardMode { EXPANDED, ANCHORED, DOCKED }

/** [PreviewCardMode.DOCKED]'s fixed mini-card width. */
private val QUICK_LOOK_DOCKED_WIDTH = 132.dp

/** [PreviewCardMode.DOCKED]'s height ceiling when content reports an aspect ratio. */
private val QUICK_LOOK_DOCKED_MAX_HEIGHT = 96.dp

/** [PreviewCardMode.DOCKED]'s height when content never reports one (free-aspect previews). */
private val QUICK_LOOK_DOCKED_DEFAULT_HEIGHT = 84.dp

/** How short an aspect-locked card is allowed to get before it stops honouring width instead. */
private val QUICK_LOOK_MIN_ASPECT_HEIGHT = 160.dp

/** How much of the viewport height an aspect-locked card may claim. */
private const val QUICK_LOOK_MAX_ASPECT_HEIGHT_FRACTION = 0.85f

/** Anchor, dock and close now fill the bottom-right notch, always, regardless of the rail's size. */
private const val QUICK_LOOK_CLOSE_SLOTS = 3

/** How much of the viewport width an aspect-locked card may claim before it gives up height instead. */
private const val QUICK_LOOK_MAX_ASPECT_WIDTH_FRACTION = 0.92f

/** How long the floating chrome stays up after the last tap before it fades itself away. */
private const val CHROME_IDLE_MILLIS = 2200L

/** How long the corner resize hint stays lit the one time it shows. */
private const val RESIZE_HINT_MILLIS = 2000L

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
 * The transient preview card: content edge-to-edge, full stop. Its actions float over the
 * content as translucent pills rather than living in a shape carved out of it.
 *
 * ### Why floating, not carved
 *
 * A notch cut into the card ([NotchedCardShape], still used by the theme settings preview) reads
 * as clean geometry in isolation, but at card scale each notch is a fixed rectangle of surface
 * colour sitting beside the picture — exactly the kind of blank the card exists to avoid, just
 * moved from "around the content" to "cut into the content's own silhouette." Floating the rail
 * and the anchor/dock/close pills over the picture instead means the card's bounds and the
 * content's bounds are the same rectangle, always: [PreviewChrome]'s two pills read as a
 * translucent overlay, not as part of the card's shape, and they auto-hide entirely once the user
 * has looked for a couple of seconds ([CHROME_IDLE_MILLIS]).
 *
 * ### Why the card's own shape, not a fixed frame
 *
 * The card takes its aspect ratio from the content once the content can report one (image pixel
 * size, video frame size, a PDF's first page) — see [resolveFullCardSize]. A grey gutter either
 * side of a portrait photo is the card disagreeing with its own picture about what shape the
 * picture is; the fix is to let the picture win, clamped to sane bounds so a panorama doesn't fill
 * the screen with a sliver -- clamping width first and re-deriving height is what keeps that clamp
 * from asking for more width than the viewport actually has. Content with no intrinsic shape
 * (text, the universal inspector) never reports one, and the card falls back to the free two-axis
 * size the user last left it at.
 *
 * The card is resizable from an invisible target inside its own bottom-right corner and remembers
 * the size it was left at; see [ResizeEdgeTarget].
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
    mode: PreviewCardMode = PreviewCardMode.EXPANDED,
    onModeChange: (PreviewCardMode) -> Unit = {},
) {
    var lastShown by remember { mutableStateOf<QuickLookSnapshot?>(null) }
    if (entry != null) {
        lastShown = QuickLookSnapshot(entry, textContent, textTruncated, loading)
    }
    var moreOpen by remember(entry?.uri) { mutableStateOf(false) }

    // Dismissal always leaves the mode at EXPANDED for the next open -- an anchored or docked
    // card that reopened still anchored/docked next time would look like a bug, not a memory.
    val dismiss = {
        onModeChange(PreviewCardMode.EXPANDED)
        onDismiss()
    }

    // Back closes the "more" list before it closes the card: the list is the thing most recently
    // opened, and dismissing the whole preview to put it away would lose the file too.
    BackHandler(enabled = entry != null) { if (moreOpen) moreOpen = false else dismiss() }

    AnimatedVisibility(
        visible = entry != null,
        enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.95f, animationSpec = tween(180)),
        exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.95f, animationSpec = tween(140)),
    ) {
        val shown = lastShown ?: return@AnimatedVisibility
        val density = LocalDensity.current
        // Reset per file, not per composition: a new entry starts free-aspect (today's box) until
        // its own content reports a shape, rather than briefly inheriting the previous file's.
        var contentAspect by remember(shown.entry.uri) { mutableStateOf<Float?>(null) }

        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .then(
                    // Only EXPANDED scrims and eats taps: ANCHORED and DOCKED leave the browser
                    // underneath fully interactive, which is the entire point of either mode.
                    if (mode == PreviewCardMode.EXPANDED) {
                        Modifier
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = dismiss,
                            )
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            val viewportW = maxWidth
            val viewportH = maxHeight
            val slots = quickLookSlots(rail.size + 1)
            // The card can never be dragged narrower than its own floating chrome. Both pills sit
            // diagonally opposite, so the floor has to cover the rail pill, the anchor/dock/close
            // pill, AND one bare slot of card between them, or a narrow enough card would let the
            // two pills' corners touch. It's derived from the rail, so pinning a fourth quick
            // action widens the floor along with the pill rather than leaving a size that used to
            // be legal and no longer is.
            val minWidth = (QuickLookSlot * (slots + QUICK_LOOK_CLOSE_SLOTS + 1) / viewportW).coerceIn(0.4f, 1f)
            var w by remember { mutableStateOf(widthFraction) }
            var h by remember { mutableStateOf(heightFraction) }

            val cardSize = if (mode == PreviewCardMode.DOCKED) {
                resolveDockedSize(contentAspect)
            } else {
                resolveFullCardSize(w.coerceIn(minWidth, 1f), h, contentAspect, viewportW, viewportH)
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = if (mode == PreviewCardMode.DOCKED) {
                    // Clear of the pill, the trash bulge (bottom-right) and the clipboard bulge
                    // (top-left) -- BottomStart with this padding is the one corner none of those
                    // three claim.
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 12.dp, bottom = CommandPillReservedHeight + 8.dp)
                } else {
                    Modifier
                },
            ) {
                QuickLookCard(
                    shown = shown,
                    rail = rail,
                    moreOpen = moreOpen,
                    mode = mode,
                    width = cardSize.width,
                    height = cardSize.height,
                    onIntrinsicAspect = { contentAspect = it },
                    onResize = { dx, dy ->
                        // Dragging the bottom-right target: right widens, down grows taller, which
                        // is the direction that corner itself moves.
                        //
                        // Doubled because the card is centred: growing it by d moves each edge by
                        // d/2, so feeding the finger's travel in raw would slide the target at half
                        // the finger's speed and visibly leave it behind on a quick drag.
                        w = (w + CENTRED_DRAG * dx / with(density) { viewportW.toPx() })
                            .coerceIn(minWidth, 1f)
                        // Aspect-locked content follows width; only free-aspect content gets the
                        // second axis, matching the drag to what onResize's caller can actually see
                        // change (a locked card's height never moves independently of its width).
                        if (contentAspect == null) {
                            h = (h + CENTRED_DRAG * dy / with(density) { viewportH.toPx() })
                                .coerceIn(0.3f, 0.95f)
                        }
                    },
                    onResizeEnd = {
                        // Recomputed here rather than closing over `cardSize`: the drag gesture's
                        // coroutine is set up once and keeps calling this same lambda instance for
                        // the whole gesture, so reading `w`/`h`/`contentAspect` live (through their
                        // state delegates) rather than a frozen local is what makes the persisted
                        // scale reflect where the drag actually ended.
                        val finalWidthFraction = w.coerceIn(minWidth, 1f)
                        val size = resolveFullCardSize(finalWidthFraction, h, contentAspect, viewportW, viewportH)
                        onScaleChange(size.width.value / viewportW.value, size.height.value / viewportH.value)
                    },
                    onToggleMore = { moreOpen = !moreOpen },
                    onToggleAnchor = {
                        onModeChange(if (mode == PreviewCardMode.ANCHORED) PreviewCardMode.EXPANDED else PreviewCardMode.ANCHORED)
                    },
                    onDock = {
                        // Closed rather than left open-but-hidden: the overflow sheet has no rail
                        // to hang off of at mini size, and leaving it "open" would only mean it
                        // reappears mid-shrink at the mini card's width for one frame.
                        moreOpen = false
                        onModeChange(PreviewCardMode.DOCKED)
                    },
                    onExpand = { onModeChange(PreviewCardMode.EXPANDED) },
                    onAction = { onAction(it, shown.entry) },
                    onDismiss = dismiss,
                )
                // The overflow list unrolls beneath the card, the same way a room reveals; the
                // Column re-centres as it grows, so the card rides up to make room. Docked has no
                // rail to overflow from -- moreOpen simply never opens while the mode does.
                AnimatedVisibility(
                    visible = moreOpen && mode != PreviewCardMode.DOCKED,
                    enter = fadeIn(tween(160)) + expandVertically(tween(200)),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
                ) {
                    QuickLookOverflow(
                        actions = QuickAction.overflowFor(rail),
                        maxWidth = cardSize.width,
                        // Whatever the viewport leaves under the card, floored so a very tall
                        // card still shows a usably scrollable list rather than a sliver.
                        maxHeight = (viewportH - cardSize.height - 96.dp).coerceAtLeast(132.dp),
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

/**
 * The full (EXPANDED/ANCHORED) card's width and height once any aspect lock is applied.
 *
 * A free function rather than inline math so [QuickLook]'s `onResizeEnd` can recompute it against
 * whatever `w`/`h`/`contentAspect` hold at drag-end instead of a value captured when the drag
 * began — duplicating the formula here, rather than closing over a precomputed [DpSize], is what
 * lets that lambda read live state.
 */
internal fun resolveFullCardSize(
    widthFraction: Float,
    heightFraction: Float,
    aspect: Float?,
    viewportW: Dp,
    viewportH: Dp,
): DpSize {
    val rawWidth = viewportW * widthFraction
    if (aspect == null) return DpSize(rawWidth, viewportH * heightFraction)
    // Width is bounded to a fraction of the viewport BEFORE height enters the picture, and height
    // is derived from that already-legal width -- not the other way around. Clamping height first
    // and solving for width from it is what let a panorama (aspect > ~2.57 on a 411dp phone) ask
    // for a card wider than the viewport, which the caller's Modifier.width() then silently
    // coerced back down without touching the height, reintroducing exactly the letterbox this
    // clamp exists to remove.
    val maxWidth = viewportW * QUICK_LOOK_MAX_ASPECT_WIDTH_FRACTION
    val boundedWidth = rawWidth.coerceAtMost(maxWidth)
    val rawHeight = boundedWidth / aspect
    // The height floor can never demand more width than the clamp above already granted -- an
    // extreme-aspect panorama simply runs shorter than QUICK_LOOK_MIN_ASPECT_HEIGHT rather than
    // clawing the width clamp back open.
    val minHeight = QUICK_LOOK_MIN_ASPECT_HEIGHT.coerceAtMost(maxWidth / aspect)
    val clampedHeight = rawHeight.coerceIn(minHeight, viewportH * QUICK_LOOK_MAX_ASPECT_HEIGHT_FRACTION)
    // If the clamp bound the height, the width has to give up matching the drag exactly so the
    // card keeps the content's aspect rather than reintroducing the gutter the clamp was there to
    // avoid.
    val width = if (clampedHeight != rawHeight) clampedHeight * aspect else boundedWidth
    return DpSize(width, clampedHeight)
}

/**
 * The docked mini card's size: fit inside 132×96 preserving [aspect], not a pinned 132dp width
 * with height crushed to fit under it. Pinning the width let a portrait's docked mini keep the
 * full 132dp across and mash its height down to the 96dp ceiling, breaking its own aspect the same
 * way an unclamped full card once did; fitting inside the box on whichever axis binds first is
 * what lets a portrait dock tall-and-narrow instead.
 */
internal fun resolveDockedSize(aspect: Float?): DpSize {
    if (aspect == null) return DpSize(QUICK_LOOK_DOCKED_WIDTH, QUICK_LOOK_DOCKED_DEFAULT_HEIGHT)
    val widthAtMaxHeight = QUICK_LOOK_DOCKED_MAX_HEIGHT * aspect
    return if (widthAtMaxHeight <= QUICK_LOOK_DOCKED_WIDTH) {
        DpSize(widthAtMaxHeight, QUICK_LOOK_DOCKED_MAX_HEIGHT)
    } else {
        DpSize(QUICK_LOOK_DOCKED_WIDTH, QUICK_LOOK_DOCKED_WIDTH / aspect)
    }
}

@Composable
private fun QuickLookCard(
    shown: QuickLookSnapshot,
    rail: List<QuickAction>,
    moreOpen: Boolean,
    mode: PreviewCardMode,
    width: Dp,
    height: Dp,
    onIntrinsicAspect: (Float) -> Unit,
    onResize: (Float, Float) -> Unit,
    onResizeEnd: () -> Unit,
    onToggleMore: () -> Unit,
    onToggleAnchor: () -> Unit,
    onDock: () -> Unit,
    onExpand: () -> Unit,
    onAction: (QuickAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val docked = mode == PreviewCardMode.DOCKED
    // Visible on open and on every dock/undock, then hides itself once the user has stopped
    // touching the card -- keying on both `entry.uri` and `docked` is what makes a fresh file and
    // a return from the mini card both land with the controls on screen rather than inheriting
    // whatever the previous file's idle timer left behind.
    var chromeVisible by remember(shown.entry.uri, docked) { mutableStateOf(true) }
    // Keyed on `moreOpen` too, and guarded by it below: without this the countdown that started
    // when the card opened keeps ticking while the overflow list is open, fading the whole pill
    // -- including the only chrome affordance that can close that list -- out from under it.
    LaunchedEffect(chromeVisible, shown.entry.uri, docked, moreOpen) {
        if (chromeVisible && !moreOpen) {
            delay(CHROME_IDLE_MILLIS)
            chromeVisible = false
        }
    }
    // One call site for the content Surface regardless of mode: switching which composable calls
    // QuickLookContent (rather than which VALUES it's called with) is what would tear down and
    // rebuild whatever's inside -- an ExoPlayer mid-playback, a Coil request in flight -- every
    // time the card docks or undocks. Keeping the call site fixed and varying shape/size/onClick
    // as plain values is what lets a video keep playing across the transition.
    val onCardClick: () -> Unit = if (docked) {
        onExpand
    } else {
        { chromeVisible = !chromeVisible }
    }

    Box(Modifier.width(width).height(height)) {
        // The card IS the content: one transparent clip, not a surface-coloured panel with a
        // notch cut into it. Docked keeps its own opaque, elevated rounded rect -- it never had a
        // notch to begin with, and at mini size it has to read as a card sitting on the browser
        // rather than content floating loose over it.
        Surface(
            onClick = onCardClick,
            shape = RoundedCornerShape(if (docked) 16.dp else 20.dp),
            color = if (docked) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent,
            tonalElevation = if (docked) 6.dp else 0.dp,
            shadowElevation = if (docked) 10.dp else 0.dp,
            modifier = Modifier.fillMaxSize(),
        ) {
            QuickLookContent(
                entry = shown.entry,
                textContent = shown.textContent,
                textTruncated = shown.textTruncated,
                loading = shown.loading,
                docked = docked,
                chromeVisible = chromeVisible,
                onIntrinsicAspect = onIntrinsicAspect,
            )
        }

        if (docked) {
            // One tiny close, nothing else -- the rail and the anchor/dock/close trio only make
            // sense at a size where their own pills fit.
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).size(20.dp)) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Close preview",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(14.dp),
                )
            }
        } else {
            // Drawn before the chrome pills, not after: the two overlap by design in the very
            // corner (the pill floats 10dp in, the drag target starts flush at the edge), and a
            // sibling placed earlier in a Box loses the touch priority race to one placed later.
            // Putting the invisible drag zone first is what keeps a tap on the close button a
            // close, not a resize, while still leaving the target reachable everywhere the pill
            // itself doesn't cover -- which is everywhere, once the chrome has faded out.
            ResizeEdgeTarget(
                onResize = onResize,
                onResizeEnd = onResizeEnd,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
            PreviewChrome(
                visible = chromeVisible,
                rail = rail,
                moreOpen = moreOpen,
                mode = mode,
                onToggleMore = onToggleMore,
                onToggleAnchor = onToggleAnchor,
                onDock = onDock,
                onAction = onAction,
                onDismiss = onDismiss,
            )
        }
    }
}

/**
 * The rail and the anchor/dock/close trio, floated as translucent pills over the content instead
 * of carved into it. Both fade as one unit, tracking [QuickLookCard]'s own idle timer, so the card
 * never shows one half of its chrome without the other.
 */
@Composable
private fun BoxScope.PreviewChrome(
    visible: Boolean,
    rail: List<QuickAction>,
    moreOpen: Boolean,
    mode: PreviewCardMode,
    onToggleMore: () -> Unit,
    onToggleAnchor: () -> Unit,
    onDock: () -> Unit,
    onAction: (QuickAction) -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(160)),
        modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
    ) {
        PreviewPill {
            rail.forEach { action ->
                PreviewChromeButton(action.icon, action.label, onClick = { onAction(action) })
            }
            PreviewChromeButton(
                icon = Icons.Outlined.MoreHoriz,
                label = if (moreOpen) "Fewer actions" else "More actions",
                onClick = onToggleMore,
            )
        }
    }

    // Anchor innermost, close at the very corner: a Row laid out left-to-right and aligned to the
    // pill's own end edge puts its last child nearest the card's corner.
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(160)),
        modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
    ) {
        PreviewPill {
            PreviewChromeButton(
                icon = Icons.Outlined.Anchor,
                label = if (mode == PreviewCardMode.ANCHORED) "Release anchor" else "Anchor preview",
                active = mode == PreviewCardMode.ANCHORED,
                onClick = onToggleAnchor,
            )
            PreviewChromeButton(Icons.Outlined.PictureInPictureAlt, "Dock preview", onClick = onDock)
            PreviewChromeButton(Icons.Outlined.Close, "Close preview", onClick = onDismiss)
        }
    }
}

/** The pill shell shared by both floating chrome groups: the Ink tokens are theme-independent on
 * purpose, since the pill has to stay legible sitting on top of an arbitrary photo. */
@Composable
private fun PreviewPill(content: @Composable RowScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = InkSurface.copy(alpha = 0.72f),
    ) {
        Row(content = content)
    }
}

/** One 48dp action cell inside a floating pill. Sized to the touch minimum, not to the pill. */
@Composable
private fun PreviewChromeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(QuickLookSlot)) {
        if (active) {
            Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
            }
        } else {
            Icon(icon, contentDescription = label, tint = InkContent, modifier = Modifier.size(22.dp))
        }
    }
}

/** Set once a session shows the hint, never reset -- it only has to teach the gesture once per
 * run of the app, not once per file or once ever. */
private var quickLookResizeHintSeen = false

/**
 * The resize handle: an invisible 28dp drag zone inside the card's own bottom-right corner rather
 * than a glyph sitting on the picture. The corner is the conventional place to grab a rectangle by
 * its corner to resize it, so nothing has to be drawn there to make it findable on a second visit
 * -- [showHint] draws a small chevron the very first time instead, then never again.
 */
@Composable
private fun ResizeEdgeTarget(
    onResize: (Float, Float) -> Unit,
    onResizeEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showHint by remember { mutableStateOf(!quickLookResizeHintSeen) }
    if (showHint) {
        LaunchedEffect(Unit) {
            quickLookResizeHintSeen = true
            delay(RESIZE_HINT_MILLIS)
            showHint = false
        }
    }
    Box(
        modifier
            .size(28.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = onResizeEnd,
                    onDrag = { change, drag ->
                        change.consume()
                        onResize(drag.x, drag.y)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = showHint,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(400)),
        ) {
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = "Drag this corner to resize",
                tint = InkContent,
                modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = 45f },
            )
        }
    }
}

/** The actions that did not fit the rail, revealed beneath the card. */
@Composable
private fun QuickLookOverflow(
    actions: List<QuickAction>,
    maxWidth: Dp,
    maxHeight: Dp,
    onAction: (QuickAction) -> Unit,
) {
    if (actions.isEmpty()) return
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
        modifier = Modifier.padding(top = 10.dp).width(maxWidth),
    ) {
        // Scrolls within the room the viewport leaves it: the list grew past a handful of rows
        // once the rail became user-configurable, and a tall card on a short screen would
        // otherwise push the last actions somewhere no gesture can reach.
        Column(
            Modifier
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
        ) {
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
    docked: Boolean,
    chromeVisible: Boolean,
    onIntrinsicAspect: (Float) -> Unit,
) {
    val descriptor = remember(entry.name, entry.mimeType, entry.kind) {
        FileFormatRegistry.describe(entry.name, entry.mimeType, entry.kind)
    }
    val autoAnimate = LocalAutoAnimate.current
    // A backdrop lives here, not on the card root -- the root went transparent so an image, a
    // video frame or a rendered PDF page (each now sized to fill the card exactly) shows as pure
    // content with nothing behind it. Text, the zip listings, font specimens and the universal
    // inspector don't paint a page of their own, though, and without this they'd show whatever is
    // behind the card -- the scrim in EXPANDED, the browser in ANCHORED -- through their own
    // unfilled margins. Invisible wherever real content already covers the card; a real backdrop
    // everywhere it doesn't.
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
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
            descriptor.family == PreviewFamily.IMAGE ->
                RichImagePreview(entry, Modifier.fillMaxSize(), onIntrinsicAspect = onIntrinsicAspect)
            descriptor.family == PreviewFamily.PDF ->
                PdfPagerPreview(entry, descriptor, Modifier.fillMaxSize(), onIntrinsicAspect = onIntrinsicAspect)
            // Autoplay is a video/GIF motion rule (HIG section 5): muted playback previews motion,
            // and audio has no silent motion to preview -- autoplaying it muted would just start
            // inaudible playback with no way to tell it apart from not having started at all.
            descriptor.family == PreviewFamily.VIDEO ->
                MediaFilePreview(
                    entry, descriptor, Modifier.fillMaxSize(),
                    autoPlay = autoAnimate, useController = !docked, onVideoSize = onIntrinsicAspect,
                )
            descriptor.family == PreviewFamily.AUDIO ->
                MediaFilePreview(
                    entry, descriptor, Modifier.fillMaxSize(),
                    useController = !docked, onVideoSize = onIntrinsicAspect,
                )
            descriptor.family == PreviewFamily.FONT -> FontFilePreview(entry, descriptor, Modifier.fillMaxSize())
            descriptor.extension in QUICK_LOOK_SEMANTIC_ZIP_DOCUMENTS ->
                ZipDocumentPreview(entry, descriptor, Modifier.fillMaxSize())
            descriptor.extension in QUICK_LOOK_ZIP_CONTAINER_EXTENSIONS ->
                ZipArchivePreview(entry, descriptor, Modifier.fillMaxSize())
            descriptor.rendererId == "mesh-wireframe" || descriptor.rendererId == "dxf" ->
                GeometryFilePreview(entry, descriptor, Modifier.fillMaxSize())
            else -> UniversalInspectorPreview(entry, descriptor, Modifier.fillMaxSize())
        }

        // The caption rides the same idle timer as the pills -- rather than sitting fixed while
        // everything around it fades, which would leave a name-and-size label as the one piece of
        // chrome nobody asked to keep on screen.
        if (!docked) {
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(160)),
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
            ) {
                QuickLookCaption(entry = entry)
            }
        }
    }
}

@Composable
private fun QuickLookCaption(entry: FileEntry, modifier: Modifier = Modifier) {
    // The name rides a gradient scrim along the foot rather than a divider-and-header band: it
    // has to be legible over an arbitrary image without stealing a strip of the content. Ink
    // tokens rather than scheme ones, same as the chrome pills -- the scrim has to read against
    // whatever the photo underneath happens to be, not against whichever theme is active. The end
    // inset roughly covers where the anchor/dock/close pill floats, so a long name ellipsizes
    // there rather than running under it.
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, InkSurface.copy(alpha = 0.75f))))
            .padding(start = 18.dp, top = 28.dp, bottom = 12.dp, end = QuickLookSlot * QUICK_LOOK_CLOSE_SLOTS),
    ) {
        Text(
            displayName(entry.name, entry.isDirectory, LocalShowExtensions.current),
            style = MaterialTheme.typography.titleSmall,
            color = InkContent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOfNotNull(entry.kind.readableLabel(), entry.sizeBytes?.let(::formatBytes)).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = InkContent.copy(alpha = 0.75f),
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
