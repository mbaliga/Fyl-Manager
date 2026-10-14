package io.github.mbaliga.fylz.ui.actions

import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import io.github.mbaliga.fylz.actions.GestureId
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Carries the dispatch callback from `FylzV1Workspace`/`FileBrowser` into the long-lived pointer-
 * input coroutine [pinchDetailGesture] installs. A plain lambda parameter would go stale: the
 * coroutine is keyed `Unit` (it must survive the whole gesture, not restart mid-pinch), so it
 * only ever sees the closure captured the FIRST time it ran, while `dispatcher`/`state`/`ctx` are
 * fresh objects every recomposition. [onGesture] is reassigned on every recomposition instead
 * (see the call site in `FylzV1App.kt`), so the coroutine always calls the latest one -- the same
 * fix, for the same reason, as Fotoz's own `GridChromeBridge`
 * (`com.fotoxplorr.app.gallery.GalleryContent`).
 */
@Stable
class PinchGestureBridge {
    internal var onGesture: (GestureId) -> Unit = {}
}

/** A pinch has to move the fingers at least this many px apart before its spread is trusted as a
 * zoom signal rather than as two fingers landing near-simultaneously at the same point. */
private const val MIN_PINCH_SPREAD_PX = 8f

/**
 * Two-finger pinch on the browse surface (grid or list), folded into [bridge] as discrete
 * [GestureId.PINCH_IN]/[GestureId.PINCH_OUT] events -- see `DetailLevelLadder.kt` for the
 * accumulator this feeds, and `BuiltInActions.kt` for what each event then does (Go Up, or step
 * the detail ladder, depending on the `PinchInBehavior` setting).
 *
 * Hand-rolled rather than Compose's `detectTransformGestures`, for the same reason Fotoz's own
 * `gridZoomGestures` is (`com.fotoxplorr.app.gallery.GalleryContent`): the stock detector
 * consumes every pointer's position change on every frame, ONE finger included, which would eat
 * the touch stream this grid/list's own vertical scroll and each card/row's own
 * tap/long-press/double-click `combinedClickable` both need. This reads the pointer count itself
 * and only ever calls `consume()` on a frame with two or more fingers down -- an unmodified
 * scroll, a one-finger drag, and every tap or long-press pass through completely untouched.
 */
fun pinchDetailGesture(modifier: Modifier, bridge: PinchGestureBridge): Modifier = modifier.pointerInput(Unit) {
    // The previous frame's average finger-to-centroid distance. Reset to null whenever fewer
    // than two fingers are down, so the frame that brings the SECOND finger down never reports a
    // "zoom" against a one-finger baseline that was never a spread measurement at all. residual
    // resets with it: a fresh pinch starts with no banked sub-threshold motion from the last one.
    var previousSpread: Float? = null
    var residual = 0f
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size < 2) {
                previousSpread = null
                residual = 0f
                continue
            }
            val centroidX = pressed.sumOf { it.position.x.toDouble() }.toFloat() / pressed.size
            val centroidY = pressed.sumOf { it.position.y.toDouble() }.toFloat() / pressed.size
            val spread = pressed.sumOf {
                hypot((it.position.x - centroidX).toDouble(), (it.position.y - centroidY).toDouble())
            }.toFloat() / pressed.size
            val previous = previousSpread
            if (previous != null && previous > MIN_PINCH_SPREAD_PX) {
                pressed.forEach { it.consume() }
                val step = stepPinch(residual, spread / previous)
                residual = step.residual
                repeat(abs(step.steps)) {
                    bridge.onGesture(if (step.steps > 0) GestureId.PINCH_OUT else GestureId.PINCH_IN)
                }
            }
            previousSpread = spread
        }
    }
}
