package io.github.mbaliga.fylz.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.aarso.cellshell.ScrubberStop
import kotlin.math.roundToInt

/**
 * Which stop a list position falls in: the last one that begins at or before [index]. Pure, so
 * the highlight that follows a scrub is asserted rather than eyeballed.
 */
internal fun stopIndexFor(stops: List<ScrubberStop>, index: Int): Int {
    if (stops.isEmpty()) return -1
    var low = 0
    var high = stops.lastIndex
    var found = 0
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (stops[mid].itemIndex <= index) { found = mid; low = mid + 1 } else high = mid - 1
    }
    return found
}

/**
 * Thins [stops] to at most [maxLabels], always keeping the one at [current], so the strip shown
 * while scrubbing fits the screen however many letters, months or sizes the listing has.
 */
internal fun thinStops(stops: List<ScrubberStop>, maxLabels: Int, current: Int): List<Int> {
    if (stops.isEmpty() || maxLabels <= 0) return emptyList()
    if (stops.size <= maxLabels) return stops.indices.toList()
    val step = stops.size.toDouble() / maxLabels
    val picked = sortedSetOf<Int>()
    for (i in 0 until maxLabels) picked += (i * step).toInt().coerceIn(0, stops.lastIndex)
    if (current in stops.indices) picked += current
    return picked.toList()
}

/**
 * A slim scrollbar down the right edge of a listing, and nothing else.
 *
 * It replaces the always-on letter strip, which ran the full height of the screen over the
 * header and swallowed taps meant for the menu. This sits only inside the list's own area. At
 * rest it is a quiet thumb; while the list scrolls it brightens; and while it is being dragged the
 * [stops] (letters, months, size bands — whatever the sort uses) unfold beside it with the
 * current one highlighted, then fold away on release.
 */
@Composable
internal fun BrowserScrollbar(
    stops: List<ScrubberStop>,
    itemCount: Int,
    firstVisibleIndex: Int,
    scrolling: Boolean,
    onScrubTo: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (itemCount < 2) return
    val scheme = MaterialTheme.colorScheme
    var dragging by remember { mutableStateOf(false) }
    var scrubIndex by remember { mutableIntStateOf(0) }
    val shownIndex = if (dragging) scrubIndex else firstVisibleIndex
    val awake by animateFloatAsState(
        targetValue = if (dragging || scrolling) 1f else 0.35f,
        animationSpec = tween(220),
        label = "scrollbar-awake",
    )
    val stripAlpha by animateFloatAsState(
        targetValue = if (dragging) 1f else 0f,
        animationSpec = tween(160),
        label = "scrollbar-strip",
    )

    BoxWithConstraints(modifier.fillMaxSize()) {
        val trackPx = with(LocalDensity.current) { maxHeight.toPx() }
        val thumbHeight = 48.dp
        val thumbPx = with(LocalDensity.current) { thumbHeight.toPx() }
        val travelPx = (trackPx - thumbPx).coerceAtLeast(1f)
        val fraction = shownIndex.coerceIn(0, itemCount - 1).toFloat() / (itemCount - 1)
        val thumbTop = (fraction * travelPx).roundToInt()

        // The touch zone: wide enough for a thumb, and only as tall as the list.
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(28.dp)
                .fillMaxHeight()
                .pointerInput(itemCount, trackPx) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            scrubIndex = (((offset.y - thumbPx / 2f) / travelPx).coerceIn(0f, 1f) * (itemCount - 1)).roundToInt()
                            onScrubTo(scrubIndex)
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        change.consume()
                        val next = (((change.position.y - thumbPx / 2f) / travelPx).coerceIn(0f, 1f) * (itemCount - 1)).roundToInt()
                        if (next != scrubIndex) {
                            scrubIndex = next
                            onScrubTo(next)
                        }
                    }
                },
        ) {
            Box(
                Modifier
                    .offset { IntOffset(0, thumbTop) }
                    .align(Alignment.TopEnd)
                    .padding(end = 3.dp)
                    .width(if (dragging) 8.dp else 4.dp)
                    .height(thumbHeight)
                    .alpha(awake)
                    .clip(RoundedCornerShape(50))
                    .background(if (dragging) scheme.primary else scheme.onSurfaceVariant),
            )
        }

        // The strip: only while a finger is on the thumb.
        if (stripAlpha > 0.01f && stops.isNotEmpty()) {
            val currentStop = stopIndexFor(stops, shownIndex)
            val labelHeight = 22.dp
            val maxLabels = (maxHeight / labelHeight).toInt().coerceAtLeast(3)
            val visible = thinStops(stops, maxLabels, currentStop)
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 34.dp)
                    .alpha(stripAlpha)
                    .clip(RoundedCornerShape(14.dp))
                    .background(scheme.surfaceContainerHighest.copy(alpha = 0.94f))
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                visible.forEach { stopIndex ->
                    val active = stopIndex == currentStop
                    Text(
                        stops[stopIndex].label,
                        style = if (active) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelSmall,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = if (active) scheme.onPrimary else scheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (active) scheme.primary else androidx.compose.ui.graphics.Color.Transparent)
                            .padding(horizontal = 8.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}
