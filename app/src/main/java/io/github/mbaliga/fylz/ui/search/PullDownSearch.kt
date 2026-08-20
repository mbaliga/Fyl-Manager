package io.github.mbaliga.fylz.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.ui.motion.FylzMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The pure decision behind one pull: how far a downward drag has pulled the search field into
 * view, and whether that pull went far enough to stay open on release instead of springing shut.
 * Kept apart from Compose entirely — no `pointerInput`, no [NestedScrollConnection], nothing that
 * needs a real gesture or a composition to exercise — so the accumulate/threshold/latch math is
 * unit-testable on its own, the same split [dev.aarso.cellshell.ShakePeakTrain] makes for a shake.
 *
 * Three zones, in terms of [pulledPx] against [revealThresholdPx]:
 * - `0` to [revealThresholdPx]: [revealFraction] tracks the finger 1:1, `0f` to `1f`.
 * - past [revealThresholdPx]: the field is already fully shown; further pull is [overpull], damped
 *   so it visibly resists rather than continuing to track the finger, the classic rubber band.
 * - past [revealThresholdPx] **plus** a further [latchExcessFraction] of it: releasing now latches
 *   the field open at exactly its rest height rather than springing it shut. Short of that excess,
 *   release always springs shut — there is no partial-credit middle state.
 */
internal class PullDownGesture(
    val revealThresholdPx: Float,
    private val latchExcessFraction: Float = 0.5f,
    private val rubberBandFactor: Float = 0.55f,
) {
    init {
        require(revealThresholdPx > 0f) { "revealThresholdPx must be positive, was $revealThresholdPx" }
    }

    /** The raw excess, in px, a release must clear to latch rather than spring shut. */
    private val latchExcessPx: Float get() = revealThresholdPx * latchExcessFraction

    /** Accumulated finger travel, clamped so a retracting drag never runs the count negative. */
    var pulledPx: Float = 0f
        private set

    /** Set once a release clears [latchExcessPx]; only [collapse] clears it back. */
    var isLatched: Boolean = false
        private set

    /**
     * True for exactly the window in which this gesture owns the pointer: a live, unresolved
     * pull. `false` before the first pixel of a new pull (nothing to retract yet) and `false`
     * once latched (a latched field takes no further drag input at all, by design — see
     * [onDrag]). A caller's own gesture-claiming logic (its `onPreScroll`) should keep tracking
     * this exact window rather than re-deriving it from [pulledPx] or [isLatched] separately.
     */
    val isDragging: Boolean get() = !isLatched && pulledPx > 0f

    /** `0f` at rest, `1f` once the field's natural height is fully exposed. Never falls outside
     *  that range even mid-rubber-band, so a caller never has to clamp it before using it to size
     *  a layout — [overpullFor] is where the extra stretch past `1f` shows up instead. */
    fun revealFractionFor(pulledPx: Float): Float = (pulledPx / revealThresholdPx).coerceIn(0f, 1f)

    /** The damped stretch past full reveal, in px — `0` at or under [revealThresholdPx], rising
     *  but bounded well under [latchExcessPx] as the raw excess grows unbounded. Bounded rather
     *  than merely damped: an overpull that could still grow forever would eventually dwarf the
     *  field it is stretching, however slowly. */
    fun overpullFor(pulledPx: Float): Float {
        val excess = pulledPx - revealThresholdPx
        if (excess <= 0f) return 0f
        return latchExcessPx * rubberBandFactor * (excess / (excess + latchExcessPx))
    }

    /** This gesture's own current reveal fraction — [revealFractionFor] applied to [pulledPx]. */
    val revealFraction: Float get() = revealFractionFor(pulledPx)

    /** This gesture's own current overpull — [overpullFor] applied to [pulledPx]. */
    val overpull: Float get() = overpullFor(pulledPx)

    /**
     * Feed one downward-positive delta, in px — a negative value retracts. No-op once [isLatched]:
     * a field that latched open on a *previous* release does not re-enter a live pull just because
     * a finger touched the listing again: it takes a fresh, separate pull starting from `0`, or an
     * explicit [collapse], never a stray drag it happens to still be able to see.
     */
    fun onDrag(deltaPx: Float) {
        if (isLatched) return
        pulledPx = (pulledPx + deltaPx).coerceAtLeast(0f)
    }

    /**
     * The finger lifted (or a fling began before the pull resolved). Past [latchExcessPx] of raw
     * excess beyond full reveal, the field latches open at exactly [revealThresholdPx]; short of
     * that, it resets to `0` — sprung shut. A no-op at rest ([pulledPx] already `0`) or once
     * already [isLatched], so calling it defensively from more than one release path is safe.
     */
    fun onRelease() {
        if (isLatched || pulledPx == 0f) return
        val excess = pulledPx - revealThresholdPx
        pulledPx = if (excess >= latchExcessPx) {
            isLatched = true
            revealThresholdPx
        } else {
            0f
        }
    }

    /** Programmatic close — what a revealed field's own handle or dismiss action calls. The only
     *  way a latched field ever returns to `0`, since [onDrag] refuses it input while latched. */
    fun collapse() {
        isLatched = false
        pulledPx = 0f
    }

    /**
     * Programmatic open — the counterpart to [collapse], for a caller with no drag to replay (a
     * command that should land on an already-revealed field, e.g. Workstream W's FocusSearch
     * command handling in `FylzV1App.kt`). Latches straight at [revealThresholdPx], the same rest
     * height a real drag-and-release settles at once it clears the latch excess. A no-op while
     * already [isLatched].
     */
    fun reveal() {
        if (isLatched) return
        isLatched = true
        pulledPx = revealThresholdPx
    }
}

/** Where the search field settles once fully revealed, absent a caller measuring its own. Matches
 *  the browser's existing search surface height so a caller that has not built a bespoke field yet
 *  still gets a sane default. */
val PullDownSearchDefaultHeight: Dp = 56.dp

/**
 * The Compose-facing half of [PullDownGesture]: the same accumulate/threshold/latch decision,
 * driven by real drag deltas from a [NestedScrollConnection] and rendered through an [Animatable]
 * so a resolved release (spring open or spring shut) animates on [FylzMotion.settle] instead of
 * snapping. Live drag updates go through the same [Animatable] via [Animatable.snapTo] rather than
 * a separate un-animated field — one number renders the field at every point in the gesture,
 * mid-drag or settling, so nothing has to reconcile two sources of truth about where it is.
 */
@Stable
class PullDownSearchState internal constructor(
    internal val gesture: PullDownGesture,
    private val pull: Animatable<Float, AnimationVector1D>,
    private val scope: CoroutineScope,
) {
    /** `0f` at rest, `1f` once the field's natural height is fully exposed. Drives the reveal
     *  container's height; see [PullDownGesture.revealFractionFor]. */
    val revealFraction: Float get() = gesture.revealFractionFor(pull.value)

    /** The rubber-banded stretch past full reveal, in px, for a caller drawing the overscroll or
     *  sizing the handle's approach; see [PullDownGesture.overpullFor]. */
    val overpull: Float get() = gesture.overpullFor(pull.value)

    /** True once a pull has latched the field open — the handle only ever shows for this, never
     *  for a field merely mid-drag toward it. */
    val isRevealed: Boolean get() = gesture.isLatched

    /** Called from inside a live drag; not part of the public gesture surface a search field's own
     *  UI should reach for. */
    internal fun drag(deltaPx: Float) {
        gesture.onDrag(deltaPx)
        scope.launch { pull.snapTo(gesture.pulledPx) }
    }

    /** Called once the finger lifts or a fling begins, from a suspend nested-scroll callback —
     *  awaited there so the settle animation is the thing that actually resolves the gesture. */
    internal suspend fun release() {
        gesture.onRelease()
        pull.animateTo(gesture.pulledPx, animationSpec = FylzMotion.settle)
    }

    /** Closes a latched field on demand — the revealed handle's tap target, and the only route
     *  back to `0` once [isRevealed] is true (see [PullDownGesture.collapse]). */
    fun collapse() {
        gesture.collapse()
        scope.launch { pull.animateTo(0f, animationSpec = FylzMotion.settle) }
    }

    /**
     * Opens the field on demand, without a drag to replay — the counterpart to [collapse]. Added
     * for Workstream W's FocusSearch command handling (`FylzV1App.kt`): nothing equivalent existed
     * on this state before, so this is a small, additive public method, not a redefinition of
     * [collapse]'s own contract. Settles at the same rest height a real reveal-and-latch drag
     * would, via the same [FylzMotion.settle] animation.
     */
    fun reveal() {
        gesture.reveal()
        scope.launch { pull.animateTo(gesture.pulledPx, animationSpec = FylzMotion.settle) }
    }
}

/** Builds and remembers a [PullDownSearchState] sized to [revealHeight]. Recreated if [revealHeight]
 *  or the display density changes, which loses an in-flight pull — an acceptable trade for a change
 *  that only happens on rotation or a font-scale/density shift, neither of which fires mid-gesture. */
@Composable
fun rememberPullDownSearchState(revealHeight: Dp = PullDownSearchDefaultHeight): PullDownSearchState {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val revealThresholdPx = with(density) { revealHeight.toPx() }
    val gesture = remember(revealThresholdPx) { PullDownGesture(revealThresholdPx) }
    val pull = remember(gesture) { Animatable(0f) }
    return remember(gesture, pull, scope) { PullDownSearchState(gesture, pull, scope) }
}

/**
 * A [NestedScrollConnection] that claims a downward drag only once the wrapped listing has
 * nothing left of its own to consume — i.e. it is already at its absolute top, per [listAtTop] —
 * and only for [NestedScrollSource.Drag], never [NestedScrollSource.Fling]. Read as three cases:
 *
 * - **A fresh pull's first pixel** arrives in [NestedScrollConnection.onPostScroll] as unconsumed
 *   `available`: the listing itself already declined it (there is nowhere further up to scroll),
 *   which is what [listAtTop] double-checks rather than trusts blindly. Every pixel after the
 *   first, this connection is already dragging, so [NestedScrollConnection.onPreScroll] claims
 *   it ahead of the listing instead — that is what lets a reversing drag retract the pull before
 *   the listing gets a chance to scroll into it.
 * - **Once latched**, both scroll callbacks step aside entirely: a revealed field stays pinned at
 *   its rest height while the listing scrolls freely underneath it, exactly like a normal listing
 *   with a header. Only [PullDownSearchState.collapse] closes it from here.
 * - **The release**, in [NestedScrollConnection.onPreFling]: whatever residual velocity the finger
 *   leaves behind is swallowed here rather than handed to the listing as a fling, since a live
 *   pull's release always means "resolve the reveal," never "keep scrolling."
 */
@Composable
internal fun rememberPullDownSearchConnection(
    state: PullDownSearchState,
    listAtTop: () -> Boolean,
): NestedScrollConnection {
    val currentListAtTop = rememberUpdatedState(listAtTop)
    return remember(state) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.Drag || !state.gesture.isDragging) return Offset.Zero
                state.drag(available.y)
                return Offset(0f, available.y)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.Drag) return Offset.Zero
                if (available.y <= 0f || state.gesture.isLatched) return Offset.Zero
                if (!currentListAtTop.value()) return Offset.Zero
                state.drag(available.y)
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!state.gesture.isDragging) return Velocity.Zero
                state.release()
                return available
            }
        }
    }
}

/**
 * Wraps a scrollable listing with the pull-down search reveal: [content] is offered a [Modifier]
 * already carrying the [NestedScrollConnection] and must attach it to its own scrolling container
 * (the `LazyColumn`/`LazyVerticalGrid` itself, not an ancestor `Box`) for the gesture to see real
 * scroll deltas. [searchField] draws inside the revealing band above [content]; it receives the
 * live [PullDownSearchState] so it can key its own entrance on [PullDownSearchState.revealFraction]
 * and call [PullDownSearchState.collapse] from its own close affordance, if it has one.
 *
 * This is the first `nestedScroll` connection in the repo — there is no `PullToRefresh` or
 * `overscroll` precedent here to follow, so the accumulate/threshold/latch split behind it
 * ([PullDownGesture]) is the pattern going forward, not a one-off.
 *
 * **Why this gesture was banned, and why the ban is now narrower rather than gone.**
 * [dev.aarso.cellshell.SpatialShell]'s top-room reveal claims a downward drag starting within its
 * `EDGE_DP` (56dp) band on `PointerEventPass.Initial` — before any child, including a listing
 * wrapped here, ever sees the pointer. That claim is unconditional and this function does nothing
 * to weaken it: a pull starting inside a room's top 56dp still opens the details room every time,
 * exactly as `docs/fonebrew-navigation.md` says. What changes is everything *below* that band: a
 * pull starting there, on a listing already scrolled to its own top, used to be refused outright
 * (refresh moved to a shake specifically so nothing would ever contest that space). It no longer
 * is, for search only — pull-to-refresh and pull-to-backup stay banned. The two places that
 * documented the old absolute rule needed rewriting, not just this new file: `docs/fonebrew-
 * navigation.md` now says so directly, and the comment above `ShakeToRefresh` at
 * `FylzV1App.kt:1690-1694` (that file's own, not this one's, so it is not edited here) still
 * describes the old absolute claim and should read:
 *
 * > Refresh is a shake, everywhere in the constellation. The pull-down space in a room's top
 * > 56dp belongs to the top-room reveal and no other gesture may claim it there — that part is
 * > unchanged. Below that band, pulling down on a listing already at rest reveals search
 * > ([PullDownSearchHost]) instead of nothing: a deliberate narrowing of the old "pull-down is
 * > reserved, full stop" rule, scoped so the shell's own top-edge claim still wins outright and
 * > is never contested. Refresh itself stays off the touch plane regardless — a deliberate shake
 * > needs no affordance, no instructional copy, and competes with no scroll. The toolbar button
 * > stays for anyone who would rather tap than shake.
 *
 * **What this does not cover.** CANVAS ([io.github.mbaliga.fylz.ui.canvas.SubjectCanvas]) has no
 * scroll container to attach a [NestedScrollConnection] to — pulling down on a canvas just pans
 * it. Search has to stay reachable from that mode's actions bar instead; nothing here builds that
 * route.
 *
 * @param listAtTop whether [content]'s listing is scrolled to its own absolute top right now —
 *   read every frame through a [rememberUpdatedState] internally, so passing a value that changes
 *   on every scroll tick is the intended, cheap use, not something to guard behind `remember`.
 *   Deliberately a plain `Boolean` rather than a `LazyListState` this file would have to know how
 *   to read: GRID's `LazyGridState` and CliListing's `LazyListState` compute "at top" differently,
 *   and this host has no business caring which.
 */
@Composable
fun PullDownSearchHost(
    listAtTop: Boolean,
    modifier: Modifier = Modifier,
    revealHeight: Dp = PullDownSearchDefaultHeight,
    state: PullDownSearchState = rememberPullDownSearchState(revealHeight),
    searchField: @Composable (PullDownSearchState) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val currentListAtTop = rememberUpdatedState(listAtTop)
    val connection = rememberPullDownSearchConnection(state) { currentListAtTop.value }
    val density = LocalDensity.current

    Column(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(revealHeight * state.revealFraction + with(density) { state.overpull.toDp() })
                .clipToBounds(),
        ) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(revealHeight)) {
                searchField(state)
            }
        }
        AnimatedVisibility(visible = state.isRevealed, enter = FylzMotion.enter, exit = FylzMotion.exit) {
            PullDownSearchHandle(onCollapse = state::collapse)
        }
        content(Modifier.weight(1f).nestedScroll(connection))
    }
}

/** The affordance a latched-open field shows so closing it is a tap, never another gesture the
 *  listing beneath might also want. Sized to the 48dp touch minimum even though the bar it draws
 *  reads much thinner — the same `heightIn`-before-the-visual split the browser's own title target
 *  uses (`FylzV1App.kt`'s folder-title-opens-details row). */
@Composable
private fun PullDownSearchHandle(onCollapse: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button) { onCollapse() }
            .semantics { contentDescription = "Hide search" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 32.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
        )
    }
}
