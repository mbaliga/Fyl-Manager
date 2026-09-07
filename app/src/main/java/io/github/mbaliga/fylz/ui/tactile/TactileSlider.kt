package io.github.mbaliga.fylz.ui.tactile

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.R
import io.github.mbaliga.fylz.ui.theme.FylzGeometry
import io.github.mbaliga.fylz.ui.theme.LocalThemeStyle
import io.github.mbaliga.fylz.ui.theme.ThemeStyle
import kotlin.math.roundToInt

private val TrackHeight = 10.dp
/** The thumb is a KEYCAP, not a disc -- same vocabulary as every other control in this kit, where
 *  a round thumb on a flat bar is exactly the stock-Material read the kit exists to replace. */
private val ThumbWidth = 30.dp
private val ThumbHeight = 22.dp
private val TouchTargetBand = 48.dp

/**
 * A recessed channel groove (reusing [tactileFieldGroove] -- the same RECESSED GROOVE recipe as a
 * [TactileField]'s body, just pill-shaped and un-slanted) with an accent fill up to the current
 * value and a small raised-cap thumb (glint omitted at this size; a dimple stands in for it).
 * Every touch inside the 48dp band jumps the value to that x position, and dragging continues to
 * track it live -- a slider is scrubbed anywhere along its length, not just on the thumb itself.
 */
@Composable
fun TactileSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
) {
    if (LocalThemeStyle.current == ThemeStyle.CLI) {
        CliSlider(value, onValueChange, modifier, valueRange, enabled)
        return
    }

    val palette = tactilePalette()
    val density = LocalDensity.current
    val thumbRadiusPx = with(density) { (ThumbWidth / 2).toPx() }
    var trackWidthPx by remember { mutableFloatStateOf(0f) }
    var dragXPx by remember { mutableFloatStateOf(0f) }
    val fraction = tactileSliderFraction(value, valueRange)
    val focusInteraction = remember { MutableInteractionSource() }
    val focused by focusInteraction.collectIsFocusedAsState()
    val percent = (fraction * 100f).roundToInt()
    val percentLabel = stringResource(R.string.tactile_slider_value_percent, percent)

    fun jumpTo(xPx: Float) {
        if (trackWidthPx <= 0f) return
        val newFraction = tactileFractionAtOffsetX(xPx, trackWidthPx, thumbRadiusPx)
        onValueChange(tactileSliderValueAt(newFraction, valueRange))
    }

    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = TouchTargetBand)
            .alpha(if (enabled) 1f else 0.38f)
            .onSizeChanged { trackWidthPx = it.width.toFloat() }
            .tactileFocusRing(focused, palette.accent, cornerRadius = TrackHeight / 2)
            .focusable(enabled = enabled, interactionSource = focusInteraction)
            .progressSemantics(value, valueRange)
            .semantics {
                stateDescription = percentLabel
                if (enabled) {
                    setProgress { target ->
                        onValueChange(target.coerceIn(valueRange.start, valueRange.endInclusive))
                        true
                    }
                }
                if (!enabled) disabled()
            }
            .pointerInput(enabled) {
                if (enabled) detectTapGestures(onTap = { offset -> jumpTo(offset.x) })
            }
            .draggable(
                state = rememberDraggableState { delta ->
                    if (enabled) {
                        dragXPx += delta
                        jumpTo(dragXPx)
                    }
                },
                orientation = Orientation.Horizontal,
                enabled = enabled,
                onDragStarted = { offset -> dragXPx = offset.x },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(TrackHeight)
                .align(Alignment.Center)
                .tactileTrackGroove(palette, RoundedCornerShape(50)),
        )
        if (trackWidthPx > 0f) {
            val thumbCenterXPx = tactileThumbCenterX(fraction, trackWidthPx, thumbRadiusPx)
            val fillWidthDp = with(density) { thumbCenterXPx.toDp() }
            // Inset by 1dp top and bottom so the groove's own dark lip still frames the fill --
            // a fill flush to the channel's edges just reads as a flat painted bar.
            Box(
                Modifier
                    .width(fillWidthDp)
                    .height(TrackHeight - 2.dp)
                    .align(Alignment.CenterStart)
                    .padding(horizontal = 1.dp)
                    .clip(RoundedCornerShape(50))
                    .background(palette.accent),
            )
            val thumbXDp = with(density) { (thumbCenterXPx - thumbRadiusPx).toDp() }
            Box(
                Modifier
                    .offset(x = thumbXDp)
                    .align(Alignment.CenterStart)
                    .size(width = ThumbWidth, height = ThumbHeight)
                    .tactileCap(palette, RoundedCornerShape(FylzGeometry.RadiusMd)) { 0f },
            )
        }
    }
}

/** CLI degrade: a `----|----`-style dash track, never a fake thumb. */
@Composable
private fun CliSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
) {
    val fraction = tactileSliderFraction(value, valueRange)
    val trackLength = 20
    val markerIndex = (fraction * (trackLength - 1)).roundToInt().coerceIn(0, trackLength - 1)
    val track = buildString { repeat(trackLength) { i -> append(if (i == markerIndex) '|' else '-') } }
    val percent = (fraction * 100f).roundToInt()
    val percentLabel = stringResource(R.string.tactile_slider_value_percent, percent)
    var widthPx by remember { mutableFloatStateOf(0f) }

    Text(
        track,
        style = LocalTextStyle.current,
        modifier = modifier
            .height(TouchTargetBand)
            .wrapContentHeight(Alignment.CenterVertically)
            .alpha(if (enabled) 1f else 0.38f)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .progressSemantics(value, valueRange)
            .semantics {
                stateDescription = percentLabel
                if (enabled) {
                    setProgress { target ->
                        onValueChange(target.coerceIn(valueRange.start, valueRange.endInclusive))
                        true
                    }
                }
                if (!enabled) disabled()
            }
            .pointerInput(enabled) {
                if (enabled) {
                    detectTapGestures(onTap = { offset ->
                        val f = (offset.x / widthPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
                        onValueChange(tactileSliderValueAt(f, valueRange))
                    })
                }
            }
            .padding(horizontal = 8.dp),
    )
}
