package io.github.mbaliga.fylz.ui.components

import android.net.Uri
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
 * [resolveEntryTap]: whether this finger starts the whole-selection cluster drag or just toggles
 * membership depends on exactly two booleans, and getting that branch wrong is how a selected
 * row's drag and its toggle would both try to claim one finger.
 */
internal enum class EntryLongPressOutcome { CLUSTER_DRAG, TOGGLE_SELECTION }

internal fun resolveEntryLongPress(selected: Boolean, clusterAvailable: Boolean): EntryLongPressOutcome =
    if (selected && clusterAvailable) EntryLongPressOutcome.CLUSTER_DRAG else EntryLongPressOutcome.TOGGLE_SELECTION

/**
 * The one gesture contract every selectable entry row in the app shares: open, toggle-when-a-
 * selection-is-active, cluster-drag-when-selected, double-tap, an explicit long-press haptic, and
 * the full a11y story (a semantic click plus a "Toggle selection" custom action) -- all from one
 * [Modifier], attached once, for the row's whole lifetime.
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
 * ### Contract, precisely (there is no `androidTest` source set in this repo to encode it in)
 * - **Down, held past [androidx.compose.ui.platform.ViewConfiguration.longPressTimeoutMillis]:**
 *   [HapticFeedbackType.LongPress] fires once, then [resolveEntryLongPress] decides --
 *   [selected] with a non-null [cluster] hands the rest of the touch to
 *   [ClusterGestureHooks.onStart]/`onDrag`/`onEnd`/`onCancel`, reporting every position in root
 *   coordinates (added to this row's own origin, tracked by the trailing
 *   [onGloballyPositioned]); anything else calls [onToggleSelection] and waits out the remaining
 *   touch without starting a drag.
 * - **Down, released before the timeout, no second down inside the double-tap window:**
 *   [onToggleSelection] if [selectionActive], else [onOpen].
 * - **Down, released before the timeout, a second down arrives inside the window:** waits that
 *   second touch out, then calls [onDoubleTap] (or [onOpen] if the caller left it null) --
 *   regardless of [selectionActive].
 * - **Semantics:** `selected` mirrors [selected]; the merged `onClick` action reproduces the
 *   tap branch above (open or toggle, per [selectionActive]) for switch access and TalkBack's
 *   double-tap-to-activate; a "Toggle selection" [CustomAccessibilityAction] is present whenever
 *   [onToggleSelection] is non-null, so a selection can be built and cleared without ever
 *   double-tapping into a folder by accident.
 * - [cluster]'s own [ClusterGestureHooks.onPositioned] is reported from the same
 *   [onGloballyPositioned] callback, but only while [selected] -- an unselected row has nothing
 *   for a live cluster drag to gather.
 *
 * @param key identifies the row -- almost always `entry.uri`. Keys [pointerInput] (so the
 *   long-lived gesture coroutine restarts on a genuinely different row, never on a value this
 *   same gesture might itself change) and is the `Uri` reported to [ClusterGestureHooks.onPositioned].
 * @param onDoubleTap defaults to [onOpen] when left null -- a caller with no distinct
 *   double-tap behaviour (e.g. "open externally" for files) still gets a double-tap that opens.
 * @param contentDescription left unset by default, the same as every current call site: with
 *   nothing here, TalkBack falls back to the row's own merged child text.
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
): Modifier {
    val haptics = LocalHapticFeedback.current
    var originInRoot by remember(key) { mutableStateOf(Offset.Zero) }

    // Read fresh inside the long-lived pointerInput coroutine below -- see CanvasTile's own
    // KDoc for why: plain parameters would freeze at whatever they were the one time this key
    // launched the coroutine, and `key` is the only thing that key is allowed to change on.
    val selectedState = rememberUpdatedState(selected)
    val selectionActiveState = rememberUpdatedState(selectionActive)
    val onOpenState = rememberUpdatedState(onOpen)
    val onDoubleTapState = rememberUpdatedState(onDoubleTap ?: onOpen)
    val toggleSelectionState = rememberUpdatedState(onToggleSelection)
    val clusterState = rememberUpdatedState(cluster)
    // A bare `selected` inside the semantics block below would resolve back to this function's
    // own parameter rather than `SemanticsPropertyReceiver.selected` -- every fixed row carries
    // this identical rename for the identical shadowing.
    val entrySelected = selected

    return this
        .onGloballyPositioned { coordinates ->
            originInRoot = coordinates.positionInRoot()
            if (selected) cluster?.onPositioned(key, coordinates.boundsInRoot().center)
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
                        val outcome = resolveEntryLongPress(selectedState.value, liveCluster != null)
                        // The null check is repeated (rather than trusting `outcome` alone) so
                        // the compiler smart-casts `liveCluster` here -- `resolveEntryLongPress`
                        // already guarantees the two agree.
                        if (outcome == EntryLongPressOutcome.CLUSTER_DRAG && liveCluster != null) {
                            liveCluster.onStart(originInRoot + down.position)
                            val completed = drag(down.id) { change ->
                                change.consume()
                                liveCluster.onDrag(originInRoot + change.position)
                            }
                            if (completed) liveCluster.onEnd() else liveCluster.onCancel()
                        } else {
                            toggleSelectionState.value?.invoke()
                            waitForUpOrCancellation()
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
            onToggleSelection?.let { toggle ->
                customActions = listOf(CustomAccessibilityAction("Toggle selection") { toggle(); true })
            }
        }
}
