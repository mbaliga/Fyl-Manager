package io.github.mbaliga.fylz.ui.components

import android.net.Uri
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import io.github.mbaliga.fylz.ui.ClusterGestureHooks
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What a released-before-long-press tap resolves to, in isolation from the pointer plumbing
 * around it. Pure so the branch [entryGestures] takes is locked in by [EntryGesturesTest]
 * without a composition or a real pointer stream.
 *
 * [DOUBLE_TAP] outranks everything, including an active selection: a double-tap opens
 * regardless of selection mode, the same way [ClusterGestureHooks]'s three fixed call sites
 * ([io.github.mbaliga.fylz.ui.canvas.CanvasTile], `SubjectList`, `BentoMosaic`'s
 * `combinedClickable(onDoubleClick = ...)`) never gated their double-click on it either.
 */
internal enum class EntryTapOutcome { OPEN, TOGGLE_SELECTION, DOUBLE_TAP }

internal fun resolveEntryTap(secondTapArrived: Boolean, selectionActive: Boolean): EntryTapOutcome = when {
    secondTapArrived -> EntryTapOutcome.DOUBLE_TAP
    selectionActive -> EntryTapOutcome.TOGGLE_SELECTION
    else -> EntryTapOutcome.OPEN
}

/**
 * What a held-past-the-timeout press resolves to. Pure for the same reason as
 * [resolveEntryTap]: whether this finger starts the whole-selection cluster drag, opens the
 * row's own menu or just toggles membership depends on exactly four booleans, and getting that
 * branch wrong is how a selected row's drag and its toggle would both try to claim one finger.
 */
internal enum class EntryLongPressOutcome { CLUSTER_DRAG, TOGGLE_SELECTION, MENU }

/**
 * The long-press branch, in priority order.
 *
 * Both new parameters default to the pre-menu answer, so a row whose host offers no menu (every
 * file row, every canvas surface) resolves EXACTLY as it did before [MENU] existed: selected-
 * with-a-cluster drags, everything else toggles. The ordering IS the contract:
 * - a **selected** row with live cluster hooks still drags the selection, menu or no menu --
 *   that is the gesture the corner drop targets are built on and it is not up for negotiation;
 * - while a selection is **already live**, an unselected row still toggles, so long-pressing
 *   your way through a multi-select keeps working end to end -- only the FIRST item of a
 *   selection could ever reach [MENU], and the menu carries its own "Select" for exactly that;
 * - only a long press with **nothing selected at all** reaches [MENU], which is the state the
 *   owner's "long press a folder and open it in a new tab" ask is about.
 */
internal fun resolveEntryLongPress(
    selected: Boolean,
    clusterAvailable: Boolean,
    menuAvailable: Boolean = false,
    selectionActive: Boolean = false,
): EntryLongPressOutcome = when {
    selected && clusterAvailable -> EntryLongPressOutcome.CLUSTER_DRAG
    selectionActive -> EntryLongPressOutcome.TOGGLE_SELECTION
    menuAvailable -> EntryLongPressOutcome.MENU
    else -> EntryLongPressOutcome.TOGGLE_SELECTION
}

/**
 * Whether a held finger on THIS row may pick the row up on its own, without a selection behind
 * it -- [ClusterGestureHooks.onStartSolo]'s gate, pure for the same reason the two `resolve`
 * functions above are.
 *
 * Deliberately narrow. A solo drag is offered only from a standing start:
 * - [clusterAvailable], obviously -- there is nowhere for a drag to go otherwise;
 * - **not** [selected], because a selected row already drags the whole selection
 *   ([EntryLongPressOutcome.CLUSTER_DRAG]) and that is the older, louder gesture;
 * - **not** [selectionActive], because dragging one unselected row while three others sit
 *   selected would quietly act on the one and ignore the three. In that state the long press
 *   keeps its old meaning exactly -- toggle at the timeout, no drag, no waiting.
 *
 * So the only press that races movement against lift is the one that had nothing to lose: no
 * selection anywhere, this row not part of one.
 */
internal fun entryStartsSoloDrag(
    selected: Boolean,
    clusterAvailable: Boolean,
    selectionActive: Boolean,
): Boolean = clusterAvailable && !selected && !selectionActive

/**
 * One item of a row's long-press menu, and simultaneously one [CustomAccessibilityAction] on
 * that row. The two are deliberately the same list: a menu only a press-and-hold can reach is
 * not operable by switch access or TalkBack, and a list built twice is a list that drifts.
 */
data class EntryAction(val label: String, val onSelect: () -> Unit)

/**
 * The one gesture contract every selectable entry row in the app shares: open, toggle-when-a-
 * selection-is-active, cluster-drag-when-selected, double-tap, a long-press menu, an explicit
 * long-press haptic, and the full a11y story (a semantic click, a "Toggle selection" custom
 * action and one custom action per menu item) -- all from one [Modifier], attached once, for the
 * row's whole lifetime.
 *
 * ### Why one [pointerInput], not [androidx.compose.foundation.combinedClickable] plus a second,
 * stacked one for the drag
 * A `combinedClickable` and a raw `pointerInput(detectDragGesturesAfterLongPress)` placed ahead
 * of it are two independently-suspended gesture consumers racing the same up event: a long press
 * held past the timeout and released without moving can fire BOTH a selection toggle (from the
 * click detector) AND a cancelled cluster-drag start (from the raw one) off a single finger. That
 * was the bug in the three sites this modifier replaces (`FileRowV1`, `FileCard`, `DetailsRow` in
 * `FylzV1App.kt`). [CanvasTile][io.github.mbaliga.fylz.ui.canvas.CanvasTile], `SubjectList` and
 * `BentoMosaic` already fixed it locally by racing long-press-vs-lift exactly once inside a
 * single `awaitEachGesture`; this is that same contract, extracted, with the one thing those
 * three don't need: a double-tap branch.
 *
 * ### The double-tap branch
 * Those three references treat a released-before-long-press tap as the click, full stop. Adding
 * a double-tap on top of that shape means the FIRST tap's release can no longer act immediately
 * -- it has to wait out [androidx.compose.ui.platform.ViewConfiguration.doubleTapTimeoutMillis]
 * to see whether a second down arrives, because firing [onOpen] (or the toggle) on the first tap
 * and then [onDoubleTap] on the second would run a folder's open twice off one double-tap. So the
 * sequence per gesture is: down, race long-press-timeout vs. lift (unchanged) -- and only once
 * the first tap has actually released early does it race the double-tap window against a second
 * down. [resolveEntryTap] is the decision made once that second race resolves.
 *
 * ### The menu branch, and why it costs the old long press nothing
 * [resolveEntryLongPress] only reaches [EntryLongPressOutcome.MENU] when NOTHING is selected and
 * the host supplied both [menuActions] and [onOpenMenu] -- see its own KDoc for the ordering.
 * Today that is folder rows only (a folder has a second destination worth offering: another
 * tab); a file row passes no menu and long-pressing it still ends in a selection toggle.
 *
 * ### The solo drag, and the one thing it does change
 * Neither the menu nor the toggle acts at the timeout any more when [entryStartsSoloDrag] says
 * this press could be a drag -- both wait to see whether the finger MOVES. Movement past touch
 * slop lifts this row alone through [ClusterGestureHooks.onStartSolo]; a lift without movement
 * does what the branch always did. That is the missing half of "I wasn't able to drag a file to
 * one of the actions": before this, a drag could only start on an ALREADY-SELECTED row, so the
 * first thing a fresh long press did was select instead of lift, and reaching a drop target took
 * two separate long presses.
 *
 * The haptic still fires at the timeout, so the press still *confirms* itself on time; what
 * moves is only the commit, from the timeout to the lift. And it moves only for the press that
 * had nothing to lose -- with a selection live, or on a selected row, [entryStartsSoloDrag] is
 * false and the branch commits at the timeout exactly as it always has.
 *
 * The one real cost: a long press the finger WOBBLES past touch slop is now a drag, and a drag
 * released over nothing springs home having done nothing at all -- where before it would have
 * selected. That is deliberate. A cancelled action with a side effect is worse than one without,
 * the spring home says plainly that nothing happened, and pressing again without moving still
 * selects. It is also the only shape in which one held finger can reach a drop target at all.
 *
 * ### Contract, precisely (there is no `androidTest` source set in this repo to encode it in)
 * - **Down, held past [androidx.compose.ui.platform.ViewConfiguration.longPressTimeoutMillis]:**
 *   [HapticFeedbackType.LongPress] fires once, then [resolveEntryLongPress] decides --
 *   [selected] with a non-null [cluster] hands the rest of the touch to
 *   [ClusterGestureHooks.onStart]/`onDrag`/`onEnd`/`onCancel`, reporting every position in root
 *   coordinates (added to this row's own origin, tracked by the trailing
 *   [onGloballyPositioned]); [EntryLongPressOutcome.MENU] calls [onOpenMenu]; anything else calls
 *   [onToggleSelection]. Those last two fire at the timeout when this press cannot become a drag
 *   ([entryStartsSoloDrag] false), and on the lift when it can -- in which case a move past touch
 *   slop instead runs [ClusterGestureHooks.onStartSolo] for this row alone and neither fires.
 * - **Down, released before the timeout, no second down inside the double-tap window:**
 *   [onToggleSelection] if [selectionActive], else [onOpen].
 * - **Down, released before the timeout, a second down arrives inside the window:** waits that
 *   second touch out, then calls [onDoubleTap] (or [onOpen] if the caller left it null) --
 *   regardless of [selectionActive].
 * - **Semantics:** `selected` mirrors [selected]; the merged `onClick` action reproduces the
 *   tap branch above (open or toggle, per [selectionActive]) for switch access and TalkBack's
 *   double-tap-to-activate; a "Toggle selection" [CustomAccessibilityAction] is present whenever
 *   [onToggleSelection] is non-null, so a selection can be built and cleared without ever
 *   double-tapping into a folder by accident; and every [menuActions] entry is published as its
 *   own custom action, so nothing behind the long-press menu needs a long press to reach.
 * - [cluster]'s own [ClusterGestureHooks.onPositioned] is reported from the same
 *   [onGloballyPositioned] callback while [selected] -- an unselected row has nothing for a live
 *   cluster drag to gather -- and once more, for this row alone, the moment a solo drag starts.
 *
 * @param key identifies the row -- almost always `entry.uri`. Keys [pointerInput] (so the
 *   long-lived gesture coroutine restarts on a genuinely different row, never on a value this
 *   same gesture might itself change) and is the `Uri` reported to [ClusterGestureHooks.onPositioned].
 * @param onDoubleTap defaults to [onOpen] when left null -- a caller with no distinct
 *   double-tap behaviour (e.g. "open externally" for files) still gets a double-tap that opens.
 * @param contentDescription left unset by default, the same as every current call site: with
 *   nothing here, TalkBack falls back to the row's own merged child text.
 * @param menuActions this row's long-press menu, also published one-for-one as custom
 *   accessibility actions. Empty (the default) means this row has no menu and its long press
 *   behaves exactly as it did before menus existed.
 * @param onOpenMenu shows [menuActions] -- the host owns the menu surface itself, since a
 *   [Modifier] has no business emitting one. Supplying it is what arms the menu branch; a null
 *   [onOpenMenu] and an empty [menuActions] are equivalent, and either one alone still leaves
 *   the old long press untouched (the actions then survive only as a11y actions).
 */
@Composable
fun Modifier.entryGestures(
    key: Uri,
    selected: Boolean,
    selectionActive: Boolean,
    onOpen: () -> Unit,
    onToggleSelection: (() -> Unit)?,
    cluster: ClusterGestureHooks? = null,
    onDoubleTap: (() -> Unit)? = null,
    contentDescription: String? = null,
    menuActions: List<EntryAction> = emptyList(),
    onOpenMenu: (() -> Unit)? = null,
): Modifier {
    val haptics = LocalHapticFeedback.current
    var originInRoot by remember(key) { mutableStateOf(Offset.Zero) }
    // This row's own centre, in the same root space -- the seed a solo drag hands the cluster,
    // and the one thing a never-selected row has never had a reason to report until now.
    var centreInRoot by remember(key) { mutableStateOf(Offset.Zero) }

    // Read fresh inside the long-lived pointerInput coroutine below -- see CanvasTile's own
    // KDoc for why: plain parameters would freeze at whatever they were the one time this key
    // launched the coroutine, and `key` is the only thing that key is allowed to change on.
    val selectedState = rememberUpdatedState(selected)
    val selectionActiveState = rememberUpdatedState(selectionActive)
    val onOpenState = rememberUpdatedState(onOpen)
    val onDoubleTapState = rememberUpdatedState(onDoubleTap ?: onOpen)
    val toggleSelectionState = rememberUpdatedState(onToggleSelection)
    val clusterState = rememberUpdatedState(cluster)
    // Both halves or neither: a menu route with nothing in it would swallow the long press and
    // show an empty sheet, and actions with no route to open them stay a11y actions and nothing
    // more. Collapsed to one nullable here so the pointer branch has a single thing to test.
    val menuState = rememberUpdatedState(onOpenMenu?.takeIf { menuActions.isNotEmpty() })
    // A bare `selected` inside the semantics block below would resolve back to this function's
    // own parameter rather than `SemanticsPropertyReceiver.selected` -- every fixed row carries
    // this identical rename for the identical shadowing.
    val entrySelected = selected

    return this
        .onGloballyPositioned { coordinates ->
            originInRoot = coordinates.positionInRoot()
            centreInRoot = coordinates.boundsInRoot().center
            if (selected) cluster?.onPositioned(key, centreInRoot)
        }
        .pointerInput(key) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // `true` = up arrived first (a tap), `false` = the wait was cancelled some other
                // way (e.g. consumed elsewhere), `null` = the timeout won while still down.
                val liftedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    waitForUpOrCancellation() != null
                }
                when (liftedEarly) {
                    true -> {
                        // Do not act yet: a second down inside the double-tap window turns this
                        // into a double-tap instead, and firing here first would open a
                        // double-tapped folder twice, once per tap.
                        val secondDown = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                            awaitFirstDown(requireUnconsumed = false)
                        }
                        if (secondDown != null) waitForUpOrCancellation()
                        when (resolveEntryTap(secondDown != null, selectionActiveState.value)) {
                            EntryTapOutcome.DOUBLE_TAP -> onDoubleTapState.value.invoke()
                            EntryTapOutcome.TOGGLE_SELECTION -> toggleSelectionState.value?.invoke()
                            EntryTapOutcome.OPEN -> onOpenState.value.invoke()
                        }
                    }
                    false -> Unit
                    null -> {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        val liveCluster = clusterState.value
                        val openMenu = menuState.value
                        val outcome = resolveEntryLongPress(
                            selected = selectedState.value,
                            clusterAvailable = liveCluster != null,
                            menuAvailable = openMenu != null,
                            selectionActive = selectionActiveState.value,
                        )
                        // The null checks below are repeated (rather than trusting `outcome`
                        // alone) so the compiler smart-casts -- `resolveEntryLongPress` already
                        // guarantees each branch and its nullable agree.
                        if (outcome == EntryLongPressOutcome.CLUSTER_DRAG && liveCluster != null) {
                            liveCluster.onStart(originInRoot + down.position)
                            runClusterDrag(down.id, liveCluster, originInRoot)
                        } else {
                            // MENU or TOGGLE_SELECTION -- and which of the two it is is not yet
                            // the question. What this finger DID is: move, and it was reaching
                            // for a drop target; lift, and it meant [outcome]. Exactly one of
                            // those fires, from here, so a drag start and a toggle can never both
                            // claim one touch -- this modifier's whole reason for existing.
                            val onLift = if (outcome == EntryLongPressOutcome.MENU) {
                                openMenu
                            } else {
                                toggleSelectionState.value
                            }
                            val solo = liveCluster?.takeIf {
                                entryStartsSoloDrag(
                                    selected = selectedState.value,
                                    clusterAvailable = true,
                                    selectionActive = selectionActiveState.value,
                                )
                            }
                            if (solo == null) {
                                // Nothing to drag: act at the timeout and wait the touch out,
                                // bit-for-bit the behaviour every row had before solo drags.
                                onLift?.invoke()
                                waitForUpOrCancellation()
                            } else {
                                val moved = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                                    // Consumed here, on the Main pass, ahead of the enclosing
                                    // lazy list's own scroll detector -- a child sees the event
                                    // first, which is what lets a row be lifted out of a list
                                    // that would otherwise have scrolled under the finger.
                                    change.consume()
                                }
                                if (moved == null) {
                                    onLift?.invoke()
                                } else {
                                    // Seed the gather from where this row actually sits: the
                                    // controller reads origins per uri, and an unselected row has
                                    // never reported one (the onGloballyPositioned above only
                                    // speaks while selected), so without this the card
                                    // materialises under the finger instead of leaving its row.
                                    solo.onPositioned(key, centreInRoot)
                                    solo.onStartSolo(key, originInRoot + moved.position)
                                    runClusterDrag(down.id, solo, originInRoot)
                                }
                            }
                        }
                    }
                }
            }
        }
        .semantics {
            contentDescription?.let { this.contentDescription = it }
            this.selected = entrySelected
            onClick(label = if (selectionActive) "Toggle selection" else "Open") {
                if (selectionActive) onToggleSelection?.invoke() else onOpen()
                true
            }
            // One list, toggle first: TalkBack and switch access read these in order, and
            // "Toggle selection" is the one every row has had since this modifier existed.
            val actions = listOfNotNull(
                onToggleSelection?.let { toggle ->
                    CustomAccessibilityAction("Toggle selection") { toggle(); true }
                },
            ) + menuActions.map { action ->
                CustomAccessibilityAction(action.label) { action.onSelect(); true }
            }
            if (actions.isNotEmpty()) customActions = actions
        }
}

/**
 * The tail every drag this modifier starts shares: follow [pointerId] to the end, reporting each
 * position in ROOT coordinates ([originInRoot] plus the change's own row-local position), then
 * tell [cluster] whether the touch completed or was cancelled.
 *
 * Shared by the selected-row cluster drag and the solo drag rather than written twice, because
 * the host hit-tests drop targets against these numbers: two copies is how one of them ends up
 * reporting row-local coordinates and every target quietly stops accepting.
 */
private suspend fun AwaitPointerEventScope.runClusterDrag(
    pointerId: PointerId,
    cluster: ClusterGestureHooks,
    originInRoot: Offset,
) {
    val completed = drag(pointerId) { change ->
        change.consume()
        cluster.onDrag(originInRoot + change.position)
    }
    if (completed) cluster.onEnd() else cluster.onCancel()
}
