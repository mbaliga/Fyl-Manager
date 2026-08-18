package io.github.mbaliga.fylz.ui.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut

/**
 * The app's one shared motion vocabulary — the owner's "desktop feel" ask was space, direct
 * manipulation, multi-pane *and* motion, and motion is the one of those four that a dozen ad-hoc
 * `tween()` calls scattered across the UI cannot deliver even if every one of them is well tuned:
 * a listing that eases one way and a card that eases another still reads as two apps stitched
 * together. Every surface reaches for a spec here first, so the thing that moves feels like it
 * was drawn by the same hand no matter which file it lives in.
 *
 * **Belongs here:** a spec or transition meant to be reused verbatim by more than one surface —
 * [settle] for anything easing into a resting position, [spring] for a release that should carry
 * a touch of physical give, [enter]/[exit] for the default appearance/dismissal of a floating or
 * overlay element. If a second, unrelated caller would plausibly want the exact same spec, it
 * belongs here.
 *
 * **Does not belong here:** [dev.aarso.cellshell.SpatialMotion]'s own settle spec — that object
 * is a fixed cross-app contract by its own KDoc, and [settle] below duplicates its numbers rather
 * than importing them, so a change here can never silently retune the shell out from under it;
 * `ClusterDrag`'s drag choreography, which is tuned to one specific gesture rather than a general
 * feel; and any effect that exists in exactly one place today with no second caller in sight —
 * adding it here before it is actually shared is how a shared vocabulary turns back into a junk
 * drawer of one-off tweens with better branding.
 */
object FylzMotion {

    private val STANDARD_EASE = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    private const val SETTLE_DURATION_MS = 320
    private const val ENTER_DURATION_MS = 200
    private const val EXIT_DURATION_MS = 160
    private const val SPRING_DAMPING_RATIO = 0.72f

    /**
     * The settle: eased cubic-bezier, 320ms, no overshoot. The same curve and duration
     * `SpatialMotion.settleSpec` uses for a room drag — a tab switching, a view mode changing, a
     * preview card resizing, all settle with the same hand as the shell's own room transitions.
     */
    val settle: FiniteAnimationSpec<Float> = tween(durationMillis = SETTLE_DURATION_MS, easing = STANDARD_EASE)

    /**
     * A touch of physical give for a release that should not feel mechanical — a drag letting go,
     * a card growing into its resting size. Generic over the value being animated (`Float`, `Dp`,
     * `IntOffset`, …) since a spring spec is; call as `FylzMotion.spring()` with the target type
     * inferred from where it's handed off.
     */
    fun <T> spring(): FiniteAnimationSpec<T> =
        androidx.compose.animation.core.spring(dampingRatio = SPRING_DAMPING_RATIO, stiffness = Spring.StiffnessMedium)

    /** The default "this appeared": fade plus a gentle grow from 95%, [settle]'s own easing. */
    val enter: EnterTransition =
        fadeIn(tween(ENTER_DURATION_MS, easing = STANDARD_EASE)) +
            scaleIn(initialScale = 0.95f, animationSpec = tween(ENTER_DURATION_MS, easing = STANDARD_EASE))

    /** The default "this left": the mirror of [enter], a touch quicker — leaving reads faster than arriving. */
    val exit: ExitTransition =
        fadeOut(tween(EXIT_DURATION_MS, easing = STANDARD_EASE)) +
            scaleOut(targetScale = 0.95f, animationSpec = tween(EXIT_DURATION_MS, easing = STANDARD_EASE))
}
