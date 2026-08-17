package io.github.mbaliga.fylz.ui.deck

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import io.github.mbaliga.fylz.core.format.FileFormatRegistry
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.staging.LoopedCarousel
import io.github.mbaliga.fylz.staging.ShelfItem
import io.github.mbaliga.fylz.storage.toUri
import io.github.mbaliga.fylz.ui.components.EntryThumbnail
import io.github.mbaliga.fylz.ui.components.FileTypeIcons
import io.github.mbaliga.fylz.ui.components.ICON_SCALE
import io.github.mbaliga.fylz.ui.components.LocalIconStyle
import io.github.mbaliga.fylz.ui.components.LocalShowExtensions
import io.github.mbaliga.fylz.ui.components.displayName
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Which live source is backing an open deck: the current multi-selection, or the persistent Shelf. */
enum class DeckSource { SELECTION, SHELF }

/**
 * One card's worth of deck content, reduced from either a live [FileEntry] (the selection deck)
 * or a persisted [ShelfItem] (the Shelf deck) to the shape [FileDeckSurface] draws.
 *
 * @param sourceCrumb display-only provenance ("Downloads › invoices"); null when the deck has
 *   nothing to say about where an item came from.
 * @param entry live metadata for the thumbnail path; null falls back to the type-icon path --
 *   true for a Shelf item that has not been (re-)probed yet, or one whose probe came back empty.
 * @param missing a Shelf probe found nothing at the item's ref. The card still rides the deck --
 *   dropping it is a decision the user makes, not one the deck makes for them -- but it never
 *   claims a thumbnail or an un-struck name.
 */
data class DeckItem(
    val uri: Uri,
    val displayName: String,
    val kind: EntryKind,
    val isDirectory: Boolean,
    val sourceCrumb: String?,
    val sizeBytes: Long?,
    val entry: FileEntry?,
    val missing: Boolean = false,
)

/** The selection deck's adapter: a browsed [FileEntry] rides as-is, nothing to probe. */
fun FileEntry.toDeckItem(crumb: String? = null): DeckItem = DeckItem(
    uri = uri,
    displayName = name,
    kind = kind,
    isDirectory = isDirectory,
    sourceCrumb = crumb,
    sizeBytes = sizeBytes,
    entry = this,
)

/**
 * The Shelf deck's adapter. [probed] is the fresh metadata a repository probe of [ShelfItem.ref]
 * came back with; null means the probe found nothing, so the card falls back to the Shelf's own
 * last-known-good fields and reports [DeckItem.missing].
 */
fun ShelfItem.toDeckItem(probed: FileEntry?): DeckItem = DeckItem(
    uri = probed?.uri ?: ref.toUri(),
    displayName = probed?.name ?: displayName,
    kind = probed?.kind ?: kind,
    isDirectory = probed?.isDirectory ?: isDirectory,
    sourceCrumb = sourceCrumb,
    sizeBytes = probed?.sizeBytes ?: sizeBytes,
    entry = probed,
    missing = probed == null,
)

private val CARD_WIDTH = 232.dp
private val CARD_HEIGHT = 260.dp
private val CARD_ICON_SIZE = 96.dp
private val REMOVE_THRESHOLD = 72.dp

/**
 * The riffle-able card stack: a live selection or the Shelf, reviewed and pruned in one motion.
 *
 * Full-screen scrim over whatever room is behind it, the tray browser sheet's own idiom --
 * `zIndex(30f)`, a tap-away dismiss on the scrim, and a no-op [clickable] trap on the content
 * itself so a tap on the stack or the caption block does not fall through and dismiss the deck
 * it landed on.
 *
 * @param actions the Shelf's operation row (copy here / move here / compress / ...); the
 *   selection deck passes nothing and gets an empty row.
 */
@Composable
fun FileDeckSurface(
    items: List<DeckItem>,
    title: String,
    caption: String,
    onRemove: (DeckItem) -> Unit,
    onDismiss: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(30f)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clickable(onClick = {})
                .padding(24.dp),
        ) {
            DeckStack(
                items = items,
                onRemove = onRemove,
                modifier = Modifier.size(width = CARD_WIDTH + 48.dp, height = CARD_HEIGHT + 48.dp),
            )
            Spacer(Modifier.height(20.dp))
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        caption,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), content = actions)
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
            }
        }
    }
}

/** Which axis a front-card drag has locked onto, decided by whichever way the finger moved first. */
private enum class Axis { HORIZONTAL, VERTICAL }

/**
 * Owns the deck's one shared motion: [frontProgress] is the single fractional [Animatable] that
 * [deckTransforms] turns into every rendered card's offset, rotation, scale and alpha, so a
 * riffle reads as one spring settling rather than N cards each animating on their own clock.
 *
 * The front card carries the only gesture: horizontal drag feeds [frontProgress] 1:1 (drags
 * track, the spring only takes over once the finger lifts -- the same house rule
 * `PullableTrayCard` uses for its pull), vertical drag feeds a separate [lift] that never rides
 * the shared progress at all, because lifting a card off the deck is not a position in the
 * riffle, it is leaving it entirely.
 */
@Composable
private fun DeckStack(
    items: List<DeckItem>,
    onRemove: (DeckItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var frontIndex by remember { mutableIntStateOf(LoopedCarousel.startIndex(items.size)) }
    // The real item frontIndex currently resolves to, tracked by identity rather than position --
    // this deck stays mounted across Shelf mutations that don't originate from its own gestures
    // (Remove missing, a partial Move, New-folder-with), which can shrink `items` out from under
    // it. A bare virtual index would then resolve to a different real item purely because the
    // list got shorter; re-anchoring on this uri below keeps the front card the same item.
    var frontUri by remember { mutableStateOf(items[LoopedCarousel.itemIndex(frontIndex, items.size)].uri) }
    val frontProgress = remember { Animatable(0f) }
    var lift by remember { mutableFloatStateOf(0f) }
    var axis by remember { mutableStateOf<Axis?>(null) }

    // Under two items there is nothing to shuffle to -- the front card still lifts off, it just
    // never takes a horizontal drag.
    val riffleEnabled = items.size >= 2
    val fanDepth = (items.size - 1).coerceIn(0, MAX_FAN_DEPTH)
    val cardWidthPx = with(density) { CARD_WIDTH.toPx() }
    val removeThresholdPx = with(density) { REMOVE_THRESHOLD.toPx() }
    val settleSpec = spring<Float>(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium)

    // Re-anchors frontIndex whenever the item list itself changes (a new List instance with
    // different content -- the riffle's own settle() below never triggers this, only an external
    // mutation does). Same real item if it's still present; the deck's own remove lands here too
    // and falls back to the new list's first item, since the removed item can no longer anchor
    // anything.
    LaunchedEffect(items) {
        val realIndex = items.indexOfFirst { it.uri == frontUri }
        frontIndex = if (realIndex >= 0) {
            LoopedCarousel.startIndex(items.size) + realIndex
        } else {
            LoopedCarousel.startIndex(items.size)
        }
        frontUri = items[LoopedCarousel.itemIndex(frontIndex, items.size)].uri
    }

    fun settle(target: Float, step: Int) {
        scope.launch {
            frontProgress.animateTo(target, animationSpec = settleSpec)
            frontIndex += step
            frontUri = items[LoopedCarousel.itemIndex(frontIndex, items.size)].uri
            frontProgress.snapTo(0f)
        }
    }

    fun cancelDrag() {
        scope.launch { frontProgress.animateTo(0f, animationSpec = settleSpec) }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        // Farthest first, front last -- draw order is z-order here, so the front card (drawn
        // last) sits on top at rest. deckTransforms' own zIndex-by-scale keeps that true mid
        // riffle too, when a card's live depth crosses another's.
        for (depth in fanDepth downTo 0) {
            val realIndex = LoopedCarousel.itemIndex(frontIndex + depth, items.size)
            val item = items[realIndex]
            val transform = deckTransforms(frontProgress.value, depth)

            if (depth == 0) {
                val shownName = displayName(item.displayName, item.isDirectory, LocalShowExtensions.current)
                val removeLabel = "Remove $shownName"
                // Same shape as PullableTrayCard's pull-alpha, mirrored: fully opaque at rest,
                // fading toward the release threshold so the outcome reads before it happens.
                val liftAlpha = 1f - (-lift / (removeThresholdPx * 1.6f)).coerceIn(0f, 0.55f)

                DeckCard(
                    item = item,
                    transform = transform,
                    extraTranslationY = lift,
                    extraAlpha = liftAlpha,
                    modifier = Modifier
                        .pointerInput(item.uri, riffleEnabled) {
                            detectDragGestures(
                                onDragStart = { axis = null },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val lockedAxis = axis ?: (
                                        if (riffleEnabled && abs(dragAmount.x) > abs(dragAmount.y)) {
                                            Axis.HORIZONTAL
                                        } else {
                                            Axis.VERTICAL
                                        }
                                        ).also { axis = it }
                                    when (lockedAxis) {
                                        Axis.HORIZONTAL -> if (riffleEnabled) {
                                            val next = (frontProgress.value - dragAmount.x / cardWidthPx).coerceIn(-1f, 1f)
                                            scope.launch { frontProgress.snapTo(next) }
                                        }
                                        // Only up takes the card off the deck; a stray downward
                                        // wobble just relaxes back rather than fighting the finger.
                                        Axis.VERTICAL -> lift = (lift + dragAmount.y).coerceAtMost(0f)
                                    }
                                },
                                onDragEnd = {
                                    when (axis) {
                                        Axis.HORIZONTAL -> when {
                                            frontProgress.value >= 0.5f -> settle(1f, 1)
                                            frontProgress.value <= -0.5f -> settle(-1f, -1)
                                            else -> cancelDrag()
                                        }
                                        Axis.VERTICAL -> {
                                            if (-lift >= removeThresholdPx) onRemove(item)
                                            lift = 0f
                                        }
                                        null -> Unit
                                    }
                                    axis = null
                                },
                                onDragCancel = {
                                    if (axis == Axis.HORIZONTAL) cancelDrag() else lift = 0f
                                    axis = null
                                },
                            )
                        }
                        .semantics {
                            contentDescription = "$shownName, item ${realIndex + 1} of ${items.size}"
                            // Gestures are never the only path: the drag above and this action
                            // reach the same outcome, mirroring PullableTrayCard's own pair.
                            customActions = listOf(
                                CustomAccessibilityAction(removeLabel) {
                                    onRemove(item)
                                    true
                                },
                            )
                        },
                )
            } else {
                DeckCard(item = item, transform = transform)
            }
        }
    }
}

@Composable
private fun DeckCard(
    item: DeckItem,
    transform: DeckTransform,
    extraTranslationY: Float = 0f,
    extraAlpha: Float = 1f,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = 4.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .size(width = CARD_WIDTH, height = CARD_HEIGHT)
            .then(modifier)
            .offset {
                IntOffset(
                    x = transform.translationX.dp.roundToPx(),
                    y = transform.translationY.dp.roundToPx() + extraTranslationY.roundToInt(),
                )
            }
            .graphicsLayer {
                rotationZ = transform.rotationZ
                scaleX = transform.scale
                scaleY = transform.scale
                alpha = transform.alpha * extraAlpha
            }
            // Nearer cards draw over farther ones even mid-riffle, when two cards' live depths
            // cross -- scale already falls monotonically with depth, so it doubles as z-order
            // for free.
            .zIndex(transform.scale),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(16.dp),
        ) {
            Box(Modifier.size(CARD_ICON_SIZE), contentAlignment = Alignment.Center) {
                when {
                    item.missing -> Icon(
                        Icons.Outlined.BrokenImage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(CARD_ICON_SIZE * ICON_SCALE),
                    )
                    item.entry != null -> EntryThumbnail(item.entry, size = CARD_ICON_SIZE)
                    else -> DeckTypeIcon(item, size = CARD_ICON_SIZE)
                }
            }
            Spacer(Modifier.height(10.dp))
            val shownName = displayName(item.displayName, item.isDirectory, LocalShowExtensions.current)
            Text(
                shownName,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                textDecoration = if (item.missing) TextDecoration.LineThrough else TextDecoration.None,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (item.sourceCrumb != null) {
                Text(
                    item.sourceCrumb,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.missing) {
                Spacer(Modifier.height(4.dp))
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                    Text(
                        "Missing",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/** The un-probed / un-thumbnailable fallback: same resolution order as [EntryThumbnail]'s own. */
@Composable
private fun DeckTypeIcon(item: DeckItem, size: Dp) {
    val style = LocalIconStyle.current
    val asset = remember(item.displayName, item.kind, style) {
        val descriptor = FileFormatRegistry.describe(item.displayName, "", item.kind)
        "file:///android_asset/" + FileTypeIcons.assetPath(descriptor.extension, descriptor.family, style)
    }
    AsyncImage(
        model = asset,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(size * ICON_SCALE),
    )
}

/** How many cards ever fan out behind the front one, whatever the deck's real size. */
internal const val MAX_FAN_DEPTH = 4

private const val BASE_OFFSET_DP = 12f
private const val MAX_OFFSET_DP = 20f
private const val Y_STEP_DP = 8f
private const val ROTATION_STEP_DEG = 2.5f
private const val SCALE_BASE = 0.95f
private const val ALPHA_STEP = 0.16f
private const val ALPHA_MIN = 0.25f

/** One card's rendered transform: a flat, Density-free value so [deckTransforms] is JVM-pure. */
internal data class DeckTransform(
    val translationX: Float,
    val translationY: Float,
    val rotationZ: Float,
    val scale: Float,
    val alpha: Float,
)

/**
 * The deck's one piece of pure math: what a card at fan slot [depth] (0 = front, 1..[MAX_FAN_DEPTH]
 * fanned behind it) looks like while the shared riffle spring sits at [frontProgress].
 *
 * At rest ([frontProgress] == 0) this is just the fan geometry -- offset, rotation and scale each
 * grow with depth, sign alternating left/right/left/... so the stack reads as a loose hand of
 * cards rather than a single pile.
 *
 * Off rest, [frontProgress] in (0, 1] is a leftward riffle in progress and (-1, 0) a rightward
 * one; every slot's card interpolates smoothly toward the next slot's rest position (`depth - 1`
 * advancing, `depth + 1` retreating) -- linear interpolation between two rest transforms, so
 * opposite-sided neighbours cross cleanly through zero offset partway through, which is exactly
 * what two cards trading places should look like.
 *
 * Slot 0 and slot [MAX_FAN_DEPTH] are the two ends of that chain and get the special case: slot 0
 * advancing has nowhere to hand off to except the very back (it IS the outgoing card riffling
 * under the stack), and slot [MAX_FAN_DEPTH] retreating is the one card arriving from beyond the
 * visible fan to become the new front. Both interpolate the whole span in one step rather than
 * one slot, which is what makes the riffle read as a single card cycling rather than N-1 cards
 * shuffling up while one simply vanishes.
 */
internal fun deckTransforms(frontProgress: Float, depth: Int): DeckTransform {
    val progress = frontProgress.coerceIn(-1f, 1f)
    return when {
        depth == 0 && progress > 0f -> lerpTransform(restTransform(0), restTransform(MAX_FAN_DEPTH), progress)
        depth == MAX_FAN_DEPTH && progress < 0f -> lerpTransform(restTransform(MAX_FAN_DEPTH), restTransform(0), -progress)
        progress > 0f -> lerpTransform(restTransform(depth), restTransform(depth - 1), progress)
        progress < 0f -> lerpTransform(restTransform(depth), restTransform(depth + 1), -progress)
        else -> restTransform(depth)
    }
}

/** The fan's rest geometry at an exact integer depth -- everything [deckTransforms] interpolates between. */
private fun restTransform(depth: Int): DeckTransform {
    if (depth <= 0) return DeckTransform(0f, 0f, 0f, 1f, 1f)
    val side = if (depth % 2 == 1) 1f else -1f
    val magnitude = if (depth == 1) {
        BASE_OFFSET_DP
    } else {
        BASE_OFFSET_DP + (depth - 1) * (MAX_OFFSET_DP - BASE_OFFSET_DP) / (MAX_FAN_DEPTH - 1)
    }
    return DeckTransform(
        translationX = magnitude * side,
        translationY = -Y_STEP_DP * depth,
        rotationZ = ROTATION_STEP_DEG * depth * side,
        scale = SCALE_BASE.pow(depth),
        alpha = (1f - ALPHA_STEP * depth).coerceAtLeast(ALPHA_MIN),
    )
}

private fun lerpTransform(from: DeckTransform, to: DeckTransform, fraction: Float): DeckTransform = DeckTransform(
    translationX = from.translationX + (to.translationX - from.translationX) * fraction,
    translationY = from.translationY + (to.translationY - from.translationY) * fraction,
    rotationZ = from.rotationZ + (to.rotationZ - from.rotationZ) * fraction,
    scale = from.scale + (to.scale - from.scale) * fraction,
    alpha = from.alpha + (to.alpha - from.alpha) * fraction,
)
