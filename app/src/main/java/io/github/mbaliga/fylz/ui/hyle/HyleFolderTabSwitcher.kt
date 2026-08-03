package io.github.mbaliga.fylz.ui.hyle

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.aarso.hyle.Pulse
import dev.aarso.hyle.tokens.HyleTokens
import io.github.mbaliga.fylz.model.FolderTab

/**
 * Hyle's `easing.standard` motion token (kit/tactile-kit.html: `--ease: cubic-bezier(.4,0,.2,1)`).
 * Used for every colour/position cross-fade below; the physical "settle" motion instead uses a
 * [spring], matching the tactile-kit's own split between eased fades and springy physical snaps.
 */
val HyleStandardEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

private val TabAccent = Color(HyleTokens.Color.colorPaletteAccentViolet)
private val TabAccentInk = Color(HyleTokens.Color.colorTextInverse)
private val TabSurface = Color(HyleTokens.Color.controlSurface)
private val TabSurfaceRaised = Color(HyleTokens.Color.controlSurfaceRaised)
private val TabEdge = Color(HyleTokens.Color.controlEdge)
private val TabInk = Color(HyleTokens.Color.colorTextPrimary)
private val TabInkDim = Color(HyleTokens.Color.colorTextSecondary)

private val TabWidth = 132.dp
private val TabOverlap = (-16).dp
private val TabHeight = 40.dp
private val TabActiveLift = 10.dp
private val ScrubStepDp = 56.dp

/**
 * The fast switcher between open folder tabs, ported from Hyle's tactile-kit "Folders"
 * cascading tab drawer (kit/tactile-kit.html, `.flip`/`.ftab`): numbered, overlapping,
 * rounded-top tabs fanned out like a manila-folder index, with the open tab centred forward,
 * lifted, and lit in the Hyle accent. A drop-in replacement for the previous conventional
 * tab-row strip -- same `tabs` / `activeTabId` / callback shape as before.
 *
 * Tap a tab to select it directly. Long-press anywhere on the strip and drag horizontally to
 * scrub through tabs one at a time (mirroring the tactile-kit's own drag-to-focus stack
 * interaction); each tab crossed fires a real haptic tick ([HyleTabHaptics.tick]) and UI sound
 * ([HyleTabSoundCues.tick]), and releasing commits the scrubbed-to tab with a firmer settle
 * pulse ([HyleTabHaptics.settle] / [HyleTabSoundCues.settle]).
 */
@Composable
fun HyleFolderTabSwitcher(
    tabs: List<FolderTab>,
    activeTabId: String?,
    onSelect: (FolderTab) -> Unit,
    onClose: (FolderTab) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHyleTabHaptics()
    val sounds = rememberHyleTabSoundCues()
    val density = LocalDensity.current

    var scrubbing by remember { mutableStateOf(false) }
    var scrubIndex by remember { mutableIntStateOf(-1) }
    var dragAccumulatorPx by remember { mutableFloatStateOf(0f) }
    val stepPx = with(density) { ScrubStepDp.toPx() }

    val highlightedId = if (scrubbing && scrubIndex in tabs.indices) tabs[scrubIndex].id else activeTabId

    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(TabOverlap),
        modifier = modifier
            .fillMaxWidth()
            .height(TabHeight + TabActiveLift + 6.dp)
            .horizontalScroll(rememberScrollState())
            .padding(start = 10.dp, end = 4.dp)
            .pointerInput(tabs) {
                if (tabs.isEmpty()) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        scrubbing = true
                        scrubIndex = tabs.indexOfFirst { it.id == activeTabId }.let { found -> if (found < 0) 0 else found }
                        dragAccumulatorPx = 0f
                        haptics.tick()
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragAccumulatorPx += dragAmount.x
                        while (dragAccumulatorPx <= -stepPx) {
                            if (scrubIndex < tabs.lastIndex) {
                                scrubIndex += 1
                                haptics.tick()
                                sounds.tick()
                            }
                            dragAccumulatorPx += stepPx
                        }
                        while (dragAccumulatorPx >= stepPx) {
                            if (scrubIndex > 0) {
                                scrubIndex -= 1
                                haptics.tick()
                                sounds.tick()
                            }
                            dragAccumulatorPx -= stepPx
                        }
                    },
                    onDragEnd = {
                        scrubbing = false
                        val target = tabs.getOrNull(scrubIndex)
                        if (target != null && target.id != activeTabId) {
                            haptics.settle()
                            sounds.settle()
                            onSelect(target)
                        }
                    },
                    onDragCancel = { scrubbing = false },
                )
            },
    ) {
        tabs.forEachIndexed { index, tab ->
            HyleFolderTab(
                index = index,
                tab = tab,
                active = tab.id == highlightedId,
                onSelect = {
                    if (tab.id != activeTabId) {
                        haptics.settle()
                        sounds.settle()
                        onSelect(tab)
                    }
                },
                onClose = {
                    haptics.dismiss()
                    onClose(tab)
                },
            )
        }
        Box(
            modifier = Modifier
                .padding(start = 10.dp, bottom = 4.dp)
                .size(32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(TabSurfaceRaised)
                .border(1.dp, TabEdge, RoundedCornerShape(16.dp))
                .selectable(selected = false, onClick = onAdd),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Add, contentDescription = "Open another root", tint = TabInkDim, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun HyleFolderTab(
    index: Int,
    tab: FolderTab,
    active: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    // The cascade: inactive tabs settle to one of three staggered heights (a shallow fan, like
    // folder tabs of slightly different height peeking out of a drawer); the active tab lifts
    // clear of the fan entirely. Position and scale spring into place -- a physical settle, not
    // a linear glide -- while colour cross-fades on the standard eased timing.
    val stagger = when (index % 3) {
        0 -> 0.dp
        1 -> 5.dp
        else -> 9.dp
    }
    val liftAnimated by animateDpAsState(
        targetValue = if (active) TabActiveLift else -stagger,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "hyleTabLift",
    )
    val scaleAnimated by animateFloatAsState(
        targetValue = if (active) 1f else 0.94f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "hyleTabScale",
    )
    val background by animateColorAsState(
        targetValue = if (active) TabAccent else TabSurface,
        animationSpec = tween(HyleTokens.Duration.durationCalm, easing = HyleStandardEasing),
        label = "hyleTabBackground",
    )
    val edge by animateColorAsState(
        targetValue = if (active) TabAccent else TabEdge,
        animationSpec = tween(HyleTokens.Duration.durationCalm, easing = HyleStandardEasing),
        label = "hyleTabEdge",
    )
    val ink by animateColorAsState(
        targetValue = if (active) TabAccentInk else TabInk,
        animationSpec = tween(HyleTokens.Duration.durationCalm, easing = HyleStandardEasing),
        label = "hyleTabInk",
    )
    val numberInk by animateColorAsState(
        targetValue = if (active) TabAccentInk.copy(alpha = 0.72f) else TabInkDim,
        animationSpec = tween(HyleTokens.Duration.durationCalm, easing = HyleStandardEasing),
        label = "hyleTabNumberInk",
    )

    // Hyle's Provenance/Radiant idiom: "heartbeat, not weather" (Pulse.WATCHED). The active tab
    // breathes a very soft accent glow at Hyle's own cadence rather than sitting perfectly
    // still, so the open tab reads as *live* the same way a watched/connected surface does
    // elsewhere in the constellation -- material behaviour, not a label.
    val pulseTransition = rememberInfiniteTransition(label = "hyleTabPulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = Pulse.WATCHED.minAlphaPct / 100f,
        targetValue = Pulse.WATCHED.maxAlphaPct / 100f,
        animationSpec = infiniteRepeatable(
            animation = tween(Pulse.WATCHED.periodMs, easing = HyleStandardEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "hyleTabPulseAlpha",
    )

    Box(
        modifier = Modifier
            .zIndex(if (active) 100f else index.toFloat())
            .graphicsLayer {
                translationY = -liftAnimated.toPx()
                scaleX = scaleAnimated
                scaleY = scaleAnimated
                transformOrigin = TransformOrigin(0.5f, 1f)
            }
            .width(TabWidth)
            .height(TabHeight)
            .then(
                if (active) {
                    Modifier.background(
                        TabAccent.copy(alpha = pulseAlpha * 0.35f),
                        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 6.dp, bottomEnd = 6.dp),
                    )
                } else {
                    Modifier
                },
            )
            .padding(if (active) 3.dp else 0.dp)
            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 4.dp, bottomEnd = 4.dp))
            .background(background)
            .border(1.dp, edge, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 4.dp, bottomEnd = 4.dp))
            .selectable(selected = active, onClick = onSelect)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "%02d".format(index + 1),
                color = numberInk,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = tab.title,
                color = ink,
                fontSize = 12.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (active) {
                IconButton(onClick = onClose, modifier = Modifier.size(18.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close ${tab.title}", tint = ink, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}
