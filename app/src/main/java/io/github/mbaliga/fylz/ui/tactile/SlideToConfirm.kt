package io.github.mbaliga.fylz.ui.tactile

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The deliberate-actions confirm control (docs/product/deliberate-ux.md): a thumb dragged the
 * full width of a track, standing in for the actuation force a mechanical button used to
 * demand. A resting finger cannot fire it; neither can a stray palm edge, because commitment
 * is *distance held under way*, not contact.
 *
 * Contract, matching the constellation's motion doctrine (`docs/fonebrew-navigation.md`):
 * the thumb tracks the finger 1:1, release decides — past [COMMIT_FRACTION] commits exactly
 * once, anything less settles back in ~320ms eased, **no spring, no overshoot**.
 *
 * Accessibility is not the slide: the node carries an ordinary confirm click action, so
 * TalkBack and switch access fire [onConfirm] directly. The deliberation this control adds is
 * aimed at accidental *touches*; an assistive-tech activation is already deliberate.
 */
@Composable
fun SlideToConfirm(
    text: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val currentOnConfirm by rememberUpdatedState(onConfirm)
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var trackWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val thumbWidthPx = with(density) { THUMB_WIDTH.toPx() }
    val insetPx = with(density) { TRACK_INSET.toPx() }
    val travel = (trackWidthPx - thumbWidthPx - 2 * insetPx).coerceAtLeast(1f)

    val trackColor = MaterialTheme.colorScheme.errorContainer
    val thumbColor = MaterialTheme.colorScheme.error

    Box(
        modifier
            .fillMaxWidth()
            .height(TRACK_HEIGHT)
            .clip(RoundedCornerShape(TRACK_HEIGHT / 2))
            .background(trackColor)
            .onSizeChanged { trackWidthPx = it.width }
            .alpha(if (enabled) 1f else 0.5f)
            .semantics {
                role = Role.Button
                contentDescription = text
                onClick(label = text) {
                    if (enabled) currentOnConfirm()
                    enabled
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            // The label fades as the thumb closes on it: progress feedback with no extra parts.
            modifier = Modifier.alpha(1f - (offset.value / travel) * 0.8f),
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = TRACK_INSET)
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .fillMaxHeight()
                .padding(vertical = TRACK_INSET)
                .clip(RoundedCornerShape((TRACK_HEIGHT - TRACK_INSET * 2) / 2))
                .background(thumbColor)
                .width(THUMB_WIDTH)
                .draggable(
                    orientation = Orientation.Horizontal,
                    enabled = enabled,
                    state = rememberDraggableState { delta ->
                        scope.launch { offset.snapTo((offset.value + delta).coerceIn(0f, travel)) }
                    },
                    onDragStopped = {
                        if (offset.value >= travel * COMMIT_FRACTION) {
                            currentOnConfirm()
                            // Do NOT animate back on commit: the surface behind this control
                            // decides what happens next (usually dismissal or a running state);
                            // a thumb sliding home under a "working…" label reads as a refusal.
                        } else {
                            offset.animateTo(0f, SETTLE)
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "»",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onError,
            )
        }
    }
}

private val TRACK_HEIGHT = 48.dp
private val THUMB_WIDTH = 64.dp
private val TRACK_INSET = 4.dp

/** How far along the finger must be AT RELEASE for the action to commit. */
private const val COMMIT_FRACTION = 0.85f

/** The house settle: ~320ms eased, no spring (fonebrew-navigation contract). */
private val SETTLE = tween<Float>(durationMillis = 320, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
