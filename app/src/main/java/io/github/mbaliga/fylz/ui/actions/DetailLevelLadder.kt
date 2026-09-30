package io.github.mbaliga.fylz.ui.actions

import io.github.mbaliga.fylz.model.DensityMode
import kotlin.math.ln

/**
 * Owner request (docs/agent/REVIEW_QUEUE.md): pinch-in/out on the browse surface can be
 * configured to step through a "detail level" ladder instead of navigating up. This is the
 * ladder itself -- four rungs, ordered from the densest/least-informative (index 0: small
 * icons, most items on screen, least said about each) to the sparsest/most-informative (index 3:
 * one column, fewest items, most said about each).
 *
 * Every rung reuses an existing model concept rather than inventing a parallel one:
 * [DensityMode] (COMPACT/COMFORTABLE/DETAILED) already existed in `model/Models.kt` with nothing
 * wiring it to anything -- the three grid rungs are exactly that. The fourth, sparsest rung is
 * the existing list view. `ViewMode.DETAILS` (also already declared, also unwired) is left for a
 * follow-up that gives it real column rendering -- see REVIEW_QUEUE.md for why this ladder stops
 * at four rungs, not five, for now.
 */
sealed interface DetailLevel {
    data class Grid(val density: DensityMode) : DetailLevel
    data object ListView : DetailLevel
}

/**
 * The ladder's shape, and the arithmetic that maps a [DetailLevel] to its rung and back. Mirrors
 * the shape of Fotoz's own `ZoomLadder` (`com.fotoxplorr.app.adaptive.GalleryZoomLadder`): grid
 * rungs first, ordered by density, then one sparser terminal state.
 */
object DetailLevelLadder {
    private val gridDensities = listOf(DensityMode.COMPACT, DensityMode.COMFORTABLE, DensityMode.DETAILED)

    /** Three grid densities, plus the list view. */
    val rungCount: Int get() = gridDensities.size + 1

    /** Where [level] sits on the ladder. A [DetailLevel.Grid] density this ladder has no rung
     * for (there is none today -- every [DensityMode] value is covered) lands on the ordinary
     * grid rung rather than throwing. */
    fun rungOf(level: DetailLevel): Int = when (level) {
        is DetailLevel.Grid -> gridDensities.indexOf(level.density).let { if (it >= 0) it else 1 }
        DetailLevel.ListView -> gridDensities.size
    }

    /** The level at [rung], clamped into range -- two closed ends, not a wrap. */
    fun levelAt(rung: Int): DetailLevel {
        val clamped = rung.coerceIn(0, rungCount - 1)
        return if (clamped < gridDensities.size) DetailLevel.Grid(gridDensities[clamped]) else DetailLevel.ListView
    }

    /** [current] moved [delta] rungs, clamped at either closed end -- what
     * `ActionContext.stepDetailLevel` calls for a single discrete pinch step. */
    fun stepped(current: DetailLevel, delta: Int): DetailLevel = levelAt(rungOf(current) + delta)
}

/** One gesture frame's result: how many whole steps this frame's motion crossed (signed --
 * positive is pinch-out/more detail, negative is pinch-in/less detail), and how much sub-
 * threshold motion is left over for the next frame. */
data class PinchAccumulation(val steps: Int, val residual: Float)

/**
 * How much accumulated pinch it takes to register one step, in natural-log-of-scale units. Same
 * value and same reasoning as Fotoz's `GalleryZoomLadder.PINCH_STEP_THRESHOLD`: a pinch that has
 * changed the on-screen finger spread by about 25% steps once. Chosen, not tuned from a device,
 * but it produces what the owner asked for ("deliberate, not twitchy") -- a percent or two of
 * jitter is far below it and changes nothing, a real "one more notch" pinch crosses it cleanly.
 */
const val PINCH_STEP_THRESHOLD = 0.22f

/**
 * Folds one gesture frame's multiplicative scale change into a signed step count, carrying the
 * leftover motion in [residual] for the next call. [scaleFactor] is this frame's finger-spread
 * ratio: greater than 1 for fingers spreading apart (pinch-OUT), less than 1 for pinching
 * together (pinch-IN), and very close to 1 on almost every real frame (frames arrive far faster
 * than fingers move). `ln` turns that multiplicative, noisy-near-1 signal into an additive one
 * `residual` can keep summing exactly across frames (`ln(a) + ln(b) == ln(a*b)`, where multiplying
 * the raw ratios directly would drift under float rounding over hundreds of frames).
 *
 * Deliberately mode- and ladder-agnostic, unlike Fotoz's `ZoomLadder.step` (which folds the
 * accumulator and the ladder-position lookup into one function, and specially clamps residual at
 * the ladder's two closed ends to stop a later small reversal from spending a large "banked"
 * charge at once -- see that file's own doc). This function never needs that special case: it
 * always consumes exactly one [threshold] worth of motion per step, win or lose, so residual can
 * never grow past [threshold] regardless of what the caller does with the returned [steps] --
 * including nothing, if the ladder was already at an end. See REVIEW_QUEUE.md for the fuller
 * comparison; docs/agent/REVIEW_QUEUE.md is where this divergence from the referenced pattern is
 * called out for a second look.
 */
fun stepPinch(residual: Float, scaleFactor: Float, threshold: Float = PINCH_STEP_THRESHOLD): PinchAccumulation {
    require(scaleFactor > 0f) { "scaleFactor must be positive, was $scaleFactor" }
    require(threshold > 0f) { "threshold must be positive, was $threshold" }

    var acc = residual + ln(scaleFactor)
    var steps = 0
    while (acc >= threshold) {
        steps += 1
        acc -= threshold
    }
    while (acc <= -threshold) {
        steps -= 1
        acc += threshold
    }
    return PinchAccumulation(steps, acc)
}
