package io.github.mbaliga.fylz.widget

import io.github.mbaliga.fylz.operations.FileOperation
import io.github.mbaliga.fylz.operations.FileOperationType
import io.github.mbaliga.fylz.operations.OperationState

/** What a row in the Operations widget is doing. File operations keep their journal type. */
sealed interface ActivityKind {
    data class File(val type: FileOperationType) : ActivityKind
    data object Backup : ActivityKind
}

enum class ActivityPhase { WAITING, CHECKING, RUNNING, PAUSED }

/** One piece of work in progress, reduced to its kind and counts. No names, no URIs. */
data class ActivityRow(
    val kind: ActivityKind,
    val phase: ActivityPhase,
    /** Item count, or null when the work has no item list (a backup run). */
    val itemCount: Int?,
    val completedBytes: Long?,
    val totalBytes: Long?,
    val updatedAtMillis: Long,
) {
    /** Thousandths done, only when the total is known and positive. */
    val progressPermille: Int?
        get() {
            val total = totalBytes ?: return null
            val done = completedBytes ?: return null
            if (total <= 0L) return null
            return ((done.coerceIn(0L, total) * 1000L) / total).toInt()
        }

    val progressPercent: Int? get() = progressPermille?.let { it / 10 }
}

data class FinishedSummary(val type: FileOperationType, val state: OperationState, val atMillis: Long)

data class OperationsWidgetModel(
    val active: List<ActivityRow>,
    val lastFinished: FinishedSummary?,
) {
    val isIdle: Boolean get() = active.isEmpty()

    fun rowsFor(size: WidgetSize): List<ActivityRow> = active.take(capacity(size))

    fun hiddenAt(size: WidgetSize): Int = (active.size - capacity(size)).coerceAtLeast(0)

    /**
     * Times the widget should be redrawn even if nothing is written, so dead work is retired.
     *
     * A file operation whose process died stays RUNNING, CHECKING or PAUSED in the journal until a
     * new process builds an `OperationJournal`, whose once-per-process recovery marks it as needing
     * attention. A redraw [WATCHDOG_MILLIS] after the last write does exactly that when the process
     * is gone (and is a harmless redraw when it is not; live work writes, which pushes this time
     * back). A backup run stops counting as running when its lease lapses.
     */
    fun recheckAtMillis(nowMillis: Long): List<Long> = buildList {
        if (active.any { it.kind is ActivityKind.File && it.phase in RETIRED_BY_RECOVERY }) add(nowMillis + WATCHDOG_MILLIS)
        active.filter { it.kind == ActivityKind.Backup }
            .forEach { add(it.updatedAtMillis + BackupsWidgetModel.RUNNING_STALE_AFTER_MILLIS) }
    }

    companion object {
        /** How long after the last journal write the home screen looks again. */
        const val WATCHDOG_MILLIS: Long = 15L * 60L * 1000L

        /** Phases `OperationRecoveryPolicy.recoverAfterProcessDeath` turns into "needs attention". */
        private val RETIRED_BY_RECOVERY = setOf(ActivityPhase.CHECKING, ActivityPhase.RUNNING, ActivityPhase.PAUSED)

        /**
         * Rows per size. LARGE draws the same two as MEDIUM with a bytes line each; two 48dp
         * two-line rows are all that fit in 250x180dp.
         */
        fun capacity(size: WidgetSize): Int = when (size) {
            WidgetSize.COMPACT, WidgetSize.SMALL -> 1
            WidgetSize.MEDIUM, WidgetSize.LARGE -> 2
        }

        private fun phaseOf(state: OperationState): ActivityPhase? = when (state) {
            OperationState.QUEUED -> ActivityPhase.WAITING
            OperationState.PREFLIGHT -> ActivityPhase.CHECKING
            OperationState.RUNNING -> ActivityPhase.RUNNING
            OperationState.PAUSED -> ActivityPhase.PAUSED
            else -> null
        }

        /**
         * @param runningBackupStarts start times of backup runs the backup store records as running
         *   and that are still inside their lease (see [BackupsWidgetModel.outcomeOf]).
         */
        fun from(operations: List<FileOperation>, runningBackupStarts: List<Long>): OperationsWidgetModel {
            val fileRows = operations.mapNotNull { operation ->
                val phase = phaseOf(operation.state) ?: return@mapNotNull null
                ActivityRow(
                    kind = ActivityKind.File(operation.type),
                    phase = phase,
                    itemCount = operation.items.size,
                    completedBytes = operation.completedBytes,
                    totalBytes = operation.totalBytes,
                    updatedAtMillis = operation.updatedAtMillis,
                )
            }
            val backupRows = runningBackupStarts.map { started ->
                ActivityRow(
                    kind = ActivityKind.Backup,
                    phase = ActivityPhase.RUNNING,
                    itemCount = null,
                    completedBytes = null,
                    totalBytes = null,
                    updatedAtMillis = started,
                )
            }
            val finished = operations
                .filter { phaseOf(it.state) == null }
                .maxByOrNull(FileOperation::updatedAtMillis)
                ?.let { FinishedSummary(it.type, it.state, it.updatedAtMillis) }
            return OperationsWidgetModel(
                // Running work first, then the most recently touched.
                active = (fileRows + backupRows).sortedWith(
                    compareBy<ActivityRow> { if (it.phase == ActivityPhase.RUNNING) 0 else 1 }
                        .thenByDescending(ActivityRow::updatedAtMillis),
                ),
                lastFinished = finished,
            )
        }
    }
}
