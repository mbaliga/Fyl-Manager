package io.github.mbaliga.fylz.ui.cluster

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One dragged card's motion, integrated rather than interpolated.
 *
 * The cluster used to be pinned to the finger: the cards *were* the touch point, offset by a
 * fixed fan. That reads as a cursor, not as objects — there is no weight in it, nothing lags,
 * nothing settles, and a flick of the wrist teleports the whole stack. Here each card is a mass
 * on a damped spring anchored to the finger, so the stack has to catch up, overshoots when the
 * finger stops, and swings wide through a fast turn.
 *
 * The spring is deliberately **under**damped (see [step]): critical damping for a given
 * stiffness is `2·√k`, and the defaults sit below it, which is what produces the settle-back
 * rather than a dead stop.
 */
internal class CardSpring {

    /** Where the card actually is. Read by composition every frame. */
    var position by mutableStateOf(Offset.Zero)
        private set

    /** Current velocity in px/second — the input to squash, stretch and bank. */
    var velocity by mutableStateOf(Offset.Zero)
        private set

    /** Teleport, killing momentum: the start of a drag, not a step within one. */
    fun snap(to: Offset) {
        position = to
        velocity = Offset.Zero
    }

    /**
     * Advance one frame toward [target].
     *
     * @param dtSeconds real elapsed time, clamped internally — a dropped frame must not be
     *   allowed to integrate a single huge step, which in a spring means it explodes rather
     *   than merely stutters.
     */
    fun step(target: Offset, dtSeconds: Float, stiffness: Float, damping: Float) {
        val dt = dtSeconds.coerceIn(0f, MAX_STEP_SECONDS)
        if (dt <= 0f) return
        val toTarget = target - position
        val acceleration = toTarget * stiffness - velocity * damping
        velocity += acceleration * dt
        position += velocity * dt
    }

    /** Speed in px/s, for the motion derivations that do not care about direction. */
    val speed: Float get() = sqrt(velocity.x * velocity.x + velocity.y * velocity.y)

    private companion object {
        /** ~30fps. Longer real gaps are integrated as if they were this long. */
        const val MAX_STEP_SECONDS = 1f / 30f
    }
}

/**
 * The motion constants for the cluster, in one place so the feel can be tuned as a whole.
 *
 * The trailing cards are softer than the leader ([stiffnessFor]) rather than merely delayed.
 * Delay alone gives a rigid conga line; a softer spring gives *overlapping action* — the stack
 * fans out under acceleration, streams behind a fast drag, and gathers back together as it
 * settles, which is the readable difference between "several things being carried" and "one
 * sprite with copies stamped behind it".
 */
internal object ClusterMotion {

    /** Lead card. Under 2·√380 ≈ 39, so it overshoots and settles. */
    const val LEAD_STIFFNESS = 380f
    const val LEAD_DAMPING = 26f

    /** How much softer each card behind the leader is. */
    private const val STIFFNESS_FALLOFF = 0.17f
    private const val DAMPING_FALLOFF = 0.09f

    fun stiffnessFor(index: Int): Float =
        LEAD_STIFFNESS * (1f - STIFFNESS_FALLOFF * index).coerceAtLeast(0.35f)

    fun dampingFor(index: Int): Float =
        LEAD_DAMPING * (1f - DAMPING_FALLOFF * index).coerceAtLeast(0.5f)

    /** Speed at which squash, stretch and bank reach full strength, px/s. */
    private const val REFERENCE_SPEED = 2600f

    /** Hard caps, so a violent flick deforms a card but never tears it apart. */
    private const val MAX_STRETCH = 0.26f
    private const val MAX_BANK_DEGREES = 13f

    /**
     * Squash and stretch from a velocity, as a `(scaleX, scaleY)` pair.
     *
     * The card lengthens along the axis it is travelling and narrows across it, roughly
     * preserving area — the classic treatment, and the one that makes a fast drag read as
     * momentum rather than as a frame-rate problem.
     */
    fun deform(velocity: Offset): Pair<Float, Float> {
        val vx = abs(velocity.x) / REFERENCE_SPEED
        val vy = abs(velocity.y) / REFERENCE_SPEED
        val alongX = (vx - vy).coerceIn(-1f, 1f) * MAX_STRETCH
        return (1f + alongX) to (1f - alongX)
    }

    /** Secondary action: the stack banks into a turn instead of sliding flat through it. */
    fun bankDegrees(velocity: Offset): Float =
        (velocity.x / REFERENCE_SPEED).coerceIn(-1f, 1f) * MAX_BANK_DEGREES
}
