package io.github.mbaliga.fylz.ui.tactile

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.ui.chrome.FolderTabSlant
import io.github.mbaliga.fylz.ui.motion.FylzMotion
import io.github.mbaliga.fylz.ui.theme.FylzGeometry
import io.github.mbaliga.fylz.ui.theme.LocalThemeStyle
import io.github.mbaliga.fylz.ui.theme.ThemeStyle
import kotlin.math.roundToInt

/** One segment of a [TactileToggle]. Exactly one of [label]/[icon] is expected to carry the
 *  visible content in practice, but [contentDescription] is required either way -- an icon-only
 *  segment has no other text for a screen reader to read. */
data class TactileToggleOption(
    val label: String? = null,
    val icon: ImageVector? = null,
    val contentDescription: String,
)

private val ToggleHeight = 48.dp
private val SegmentMinWidth = 56.dp
private val CapGap = 3.dp

/**
 * The owner's reference segmented control: a plate with a sliding keycap under whichever segment
 * is selected ("The tactile control language", Build 11.5 fidelity spec). The cap's inner edge
 * slants toward whichever side is now inactive and always carries the glint top-right; the slide
 * settles over [io.github.mbaliga.fylz.ui.motion.FylzMotion.settle] (320ms, no spring).
 */
@Composable
fun TactileToggle(
    options: List<TactileToggleOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (options.isEmpty()) return
    val clampedSelected = tactileClampSegmentIndex(selectedIndex, options.size)

    if (LocalThemeStyle.current == ThemeStyle.CLI) {
        CliToggle(options, clampedSelected, onSelect, modifier, enabled)
        return
    }

    val palette = tactilePalette()
    // Each segment below is already its own real focus target (via `.selectable()`); a second,
    // separately-sourced `.focusable()` on this outer plate would be exactly the "competing focus
    // node that never actually receives the real focus events" bug TactileButton's own note warns
    // against (see that file) -- here at the toggle level, since there are N segment focus targets
    // rather than one. `focusGroup()` makes this plate a real (but never directly-focusable) node
    // in the focus tree that goes ActiveParent -- and so `hasFocus` -- whenever any child segment
    // actually holds focus, which `onFocusChanged` below reads to drive the ring.
    var focused by remember { mutableStateOf(false) }
    val capPressInteraction = remember { MutableInteractionSource() }
    val capPress = rememberTactilePressState(capPressInteraction)
    // The cap slides to the MEASURED rect of the selected segment rather than an index fraction:
    // segments wrap their own content (a long label widens only its own segment), the plate wraps
    // the row, and the control's natural width is its content -- the Build-11.5 render pass caught
    // the previous fraction-times-segment-width math parking the cap mid-plate while an inner
    // fillMaxSize row stretched the plate across whatever width the caller had available.
    val segmentRects = remember(options.size) { mutableStateListOf(*Array(options.size) { 0f to 0f }) }
    val capGapPx = with(LocalDensity.current) { CapGap.toPx() }
    val reduced = tactileReducedMotion()
    val capX by animateFloatAsState(
        targetValue = segmentRects.getOrElse(clampedSelected) { 0f to 0f }.first + capGapPx,
        animationSpec = if (reduced) snap() else FylzMotion.settle,
        label = "tactile-toggle-x",
    )
    val capWidth by animateFloatAsState(
        targetValue = (segmentRects.getOrElse(clampedSelected) { 0f to 0f }.second - capGapPx * 2).coerceAtLeast(0f),
        animationSpec = if (reduced) snap() else FylzMotion.settle,
        label = "tactile-toggle-w",
    )

    Box(
        modifier
            .height(ToggleHeight)
            .alpha(if (enabled) 1f else 0.38f)
            .onFocusChanged { focused = it.hasFocus }
            .focusGroup()
            .tactileFocusRing(focused, palette.accent)
            .tactilePlate(palette)
            .semantics { if (!enabled) disabled() },
    ) {
        // Only a 2-side call to make: the leftmost segment's only inactive neighbour is to its
        // right (slant the cap's own right/TRAILING edge); the rightmost segment's only inactive
        // neighbour is to its left (slant LEADING). A middle segment in a 3+ option toggle has
        // inactive content on both sides -- TRAILING is an arbitrary but deterministic default
        // for that case, same as any other segment that isn't an end.
        val capSide = if (clampedSelected == options.lastIndex) TactileSlantSide.LEADING else TactileSlantSide.TRAILING
        val capShape = remember(capSide) { TactileSlantShape(capSide, FolderTabSlant, FylzGeometry.RadiusLg) }

        if (capWidth > 0f) {
            Box(
                Modifier
                    .offset { IntOffset(capX.roundToInt(), 0) }
                    .width(with(LocalDensity.current) { capWidth.toDp() })
                    .fillMaxHeight()
                    .padding(vertical = CapGap)
                    .tactilePressOffset(capPress)
                    .tactileCap(palette, capShape) { capPress.shadow },
            )
        }

        Row(Modifier.selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
            options.forEachIndexed { index, option ->
                val isActive = index == clampedSelected
                val segmentModifier = if (isActive) {
                    Modifier.selectable(
                        selected = true,
                        interactionSource = capPressInteraction,
                        indication = null,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(index) },
                    )
                } else {
                    Modifier.selectable(
                        selected = false,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(index) },
                    )
                }
                Box(
                    Modifier
                        .widthIn(min = SegmentMinWidth)
                        .fillMaxHeight()
                        .onPlaced { segmentRects[index] = it.positionInParent().x to it.size.width.toFloat() }
                        .then(segmentModifier)
                        .semantics { contentDescription = option.contentDescription },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.padding(horizontal = 14.dp)) {
                        SegmentContent(option, color = if (isActive) palette.onCap else palette.onPlate)
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentContent(option: TactileToggleOption, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        option.icon?.let { Icon(it, contentDescription = null, tint = color) }
        option.label?.let { Text(it, color = color, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
    }
}

/** CLI degrade: bracketed monospace text, never a fake keycap -- `[Selected]`, ` Not selected `. */
@Composable
private fun CliToggle(
    options: List<TactileToggleOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
) {
    Row(
        modifier
            .height(ToggleHeight)
            .alpha(if (enabled) 1f else 0.38f)
            .selectableGroup(),
    ) {
        options.forEachIndexed { index, option ->
            val isActive = index == selectedIndex
            val text = option.label ?: option.contentDescription
            Text(
                if (isActive) "[$text]" else " $text ",
                style = LocalTextStyle.current,
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = 8.dp)
                    .selectable(
                        selected = isActive,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(index) },
                    )
                    .semantics { contentDescription = option.contentDescription },
            )
        }
    }
}
