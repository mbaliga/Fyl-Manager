package io.github.mbaliga.fylz.operations

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What accent colour and glyph an [ActivityProgress] card draws with -- never the operation's own
 * identity, just enough to tell two concurrent activities apart at a glance the way the owner's
 * own reference (a green save-shaped bar, a blue one, an orange one with a warning glyph) does.
 */
enum class ActivityKind { TRANSFER, ARCHIVE, DOCUMENT }

/**
 * One in-flight, trackable operation: a label ("Moving", "Copying"), where it is in its own item
 * count, and which [kind] it is. Immutable -- [ActivityTracker] replaces it wholesale on every
 * progress tick rather than mutating a shared instance in place, so a Compose collector's own
 * equality check (skip a recomposition when nothing about THIS activity changed) keeps working.
 */
data class ActivityProgress(
    val id: String,
    val label: String,
    val itemIndex: Int,
    val itemCount: Int,
    val kind: ActivityKind,
)

/**
 * The live registry of every in-flight file operation Fylz tracks as an on-screen Activity --
 * `io.github.mbaliga.fylz.ui.activity.ActivityOverlay` is its one reader. [FileOperationService]
 * owns one tracker and drives it transparently from inside its own `transfer()`, so a move or copy
 * shows up here without any caller of [FileOperationService.move]/[FileOperationService.copy]
 * having to call [start]/[update]/[finish] by hand.
 *
 * A plain in-memory [MutableStateFlow], not persisted: an Activity is what is running RIGHT NOW,
 * and a process death already ends whatever it was tracking -- the operation journal
 * ([OperationJournal]) is the durable record of what happened, this is not a second one of those.
 */
class ActivityTracker {
    private val _activities = MutableStateFlow<List<ActivityProgress>>(emptyList())
    val activities: StateFlow<List<ActivityProgress>> = _activities.asStateFlow()

    /** No-op if [id] is already tracked -- a caller that races a duplicate start (there is none
     *  today, but nothing stops a future one) does not get two cards for one operation. */
    fun start(id: String, label: String, itemCount: Int, kind: ActivityKind) {
        _activities.update { current ->
            if (current.any { it.id == id }) current else current + ActivityProgress(id, label, 0, itemCount, kind)
        }
    }

    /** A no-op for an [id] this tracker never started, or already finished -- ordinary once an
     *  operation's own coroutine is cancelled mid-flight and a queued progress tick still lands. */
    fun update(id: String, itemIndex: Int, itemCount: Int) {
        _activities.update { current ->
            current.map { if (it.id == id) it.copy(itemIndex = itemIndex, itemCount = itemCount) else it }
        }
    }

    /** Removes [id] regardless of how the operation ended -- success, failure or cancellation all
     *  mean it is no longer IN FLIGHT, which is the only thing this tracker has an opinion about. */
    fun finish(id: String) {
        _activities.update { current -> current.filterNot { it.id == id } }
    }
}
