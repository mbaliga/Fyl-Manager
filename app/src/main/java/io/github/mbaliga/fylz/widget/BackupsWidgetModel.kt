package io.github.mbaliga.fylz.widget

import io.github.mbaliga.fylz.backup.BackupMediaState
import io.github.mbaliga.fylz.backup.BackupPlan
import io.github.mbaliga.fylz.backup.BackupRunRecord
import io.github.mbaliga.fylz.backup.BackupRunStatus
import java.time.ZoneId

/** How one plan's most recent real run ended, in the widget's vocabulary. */
enum class RunOutcome {
    SUCCEEDED,
    FAILED,
    NEEDS_ATTENTION,
    CANCELLED,
    RUNNING,

    /** Recorded as running for longer than a backup lease lasts: the process died mid-run. */
    DID_NOT_FINISH,
    ;

    val needsAttention: Boolean
        get() = this == FAILED || this == NEEDS_ATTENTION || this == DID_NOT_FINISH
}

/** A plan described by its trigger, never by its name (plan names often name private folders). */
sealed interface ScheduleDescriptor {
    data class Daily(val hour: Int, val minute: Int, val alsoAfterMedia: Boolean) : ScheduleDescriptor
    data class AfterMedia(val pending: Int, val threshold: Int) : ScheduleDescriptor
    data object ManualOnly : ScheduleDescriptor
    data object Off : ScheduleDescriptor
}

data class BackupPlanRow(
    val schedule: ScheduleDescriptor,
    val outcome: RunOutcome?,
    val outcomeAtMillis: Long?,
)

data class LastRun(val outcome: RunOutcome, val atMillis: Long)

data class MediaProgress(val pending: Int, val threshold: Int)

/** Overall word for the Backups widget, most urgent first. */
enum class BackupsHealth { NO_PLANS, NEEDS_ATTENTION, RUNNING, NEVER_RUN, OK }

data class BackupsWidgetModel(
    val planCount: Int,
    val lastRun: LastRun?,
    val lastSuccessAtMillis: Long?,
    val attentionCount: Int,
    val runningCount: Int,
    val nextWindowAtMillis: Long?,
    val mediaProgress: MediaProgress?,
    val rows: List<BackupPlanRow>,
    /**
     * When each run recorded as running stops counting as running (its start plus the backup
     * lease). Nothing is written at that moment, so the widget schedules a redraw for it; otherwise
     * a run whose process died would read "Backing up now" until some unrelated write.
     */
    val runningStaleAtMillis: List<Long> = emptyList(),
) {
    val health: BackupsHealth
        get() = when {
            planCount == 0 -> BackupsHealth.NO_PLANS
            attentionCount > 0 -> BackupsHealth.NEEDS_ATTENTION
            runningCount > 0 -> BackupsHealth.RUNNING
            lastRun == null -> BackupsHealth.NEVER_RUN
            else -> BackupsHealth.OK
        }

    /**
     * Plan rows for [size]: LARGE only, most urgent first (needing attention, then running, then
     * the rest in plan order), so the one row that fits is the one the headline is about.
     */
    fun rowsFor(size: WidgetSize): List<BackupPlanRow> =
        if (size == WidgetSize.LARGE) rows.sortedBy { urgency(it) }.take(MAX_ROWS) else emptyList()

    private fun urgency(row: BackupPlanRow): Int = when {
        row.outcome?.needsAttention == true -> 0
        row.outcome == RunOutcome.RUNNING -> 1
        else -> 2
    }

    fun hiddenRowsAt(size: WidgetSize): Int = if (size == WidgetSize.LARGE) (rows.size - MAX_ROWS).coerceAtLeast(0) else 0

    companion object {
        /**
         * Plan rows the LARGE layout holds. Under its headline and two-line caption there is room
         * for one 48dp row at the LARGE size (250x180dp); the rest are "and N more".
         */
        const val MAX_ROWS = 1

        /** Matches `BackupStore.LEASE_TIMEOUT_MILLIS`: a run older than its lease is not running. */
        const val RUNNING_STALE_AFTER_MILLIS: Long = 6L * 60L * 60L * 1000L

        private val SKIPS = setOf(BackupRunStatus.SKIPPED_BUSY, BackupRunStatus.SKIPPED_THRESHOLD)

        fun outcomeOf(run: BackupRunRecord, nowMillis: Long): RunOutcome? = when (run.status) {
            BackupRunStatus.SUCCEEDED -> RunOutcome.SUCCEEDED
            BackupRunStatus.FAILED -> RunOutcome.FAILED
            BackupRunStatus.NEEDS_ATTENTION -> RunOutcome.NEEDS_ATTENTION
            BackupRunStatus.CANCELLED -> RunOutcome.CANCELLED
            BackupRunStatus.QUEUED, BackupRunStatus.RUNNING ->
                if (nowMillis - run.startedAtMillis > RUNNING_STALE_AFTER_MILLIS) RunOutcome.DID_NOT_FINISH else RunOutcome.RUNNING
            BackupRunStatus.SKIPPED_BUSY, BackupRunStatus.SKIPPED_THRESHOLD -> null
        }

        private fun BackupRunRecord.atMillis(): Long = completedAtMillis ?: startedAtMillis

        fun from(
            plans: List<BackupPlan>,
            runs: List<BackupRunRecord>,
            mediaStates: Map<String, BackupMediaState>,
            nowMillis: Long,
            zone: ZoneId,
        ): BackupsWidgetModel {
            val planIds = plans.map(BackupPlan::id).toSet()
            // Skips are written every media-scan interval; they are bookkeeping, not backups.
            val realRuns = runs
                .filter { it.planId in planIds && it.status !in SKIPS }
                .sortedByDescending(BackupRunRecord::startedAtMillis)
            val latestByPlan = realRuns.groupBy(BackupRunRecord::planId).mapValues { it.value.first() }

            val rows = plans.map { plan ->
                val latest = latestByPlan[plan.id]
                BackupPlanRow(
                    schedule = describe(plan, mediaStates[plan.id]),
                    outcome = latest?.let { outcomeOf(it, nowMillis) },
                    outcomeAtMillis = latest?.atMillis(),
                )
            }

            val lastRun = realRuns.firstOrNull()?.let { run ->
                outcomeOf(run, nowMillis)?.let { LastRun(it, run.atMillis()) }
            }
            val lastSuccess = realRuns.firstOrNull { it.status == BackupRunStatus.SUCCEEDED }?.atMillis()

            val nextWindow = plans
                .filter { it.enabled && it.schedule.dailyEnabled }
                .minOfOrNull { WidgetTime.nextDailyWindow(it.schedule.dailyHour, it.schedule.dailyMinute, nowMillis, zone) }

            val media = plans
                .filter { it.enabled && it.schedule.mediaCountEnabled }
                .map { MediaProgress((mediaStates[it.id]?.pendingNewMedia ?: 0).coerceAtLeast(0), it.schedule.mediaThreshold) }
                .maxByOrNull { it.pending.toDouble() / it.threshold }

            return BackupsWidgetModel(
                planCount = plans.size,
                lastRun = lastRun,
                lastSuccessAtMillis = lastSuccess,
                attentionCount = rows.count { it.outcome?.needsAttention == true },
                runningCount = rows.count { it.outcome == RunOutcome.RUNNING },
                nextWindowAtMillis = nextWindow,
                mediaProgress = media,
                rows = rows,
                runningStaleAtMillis = realRuns
                    .filter { outcomeOf(it, nowMillis) == RunOutcome.RUNNING }
                    .map { it.startedAtMillis + RUNNING_STALE_AFTER_MILLIS },
            )
        }

        fun describe(plan: BackupPlan, media: BackupMediaState?): ScheduleDescriptor = when {
            !plan.enabled && plan.schedule.hasAutomaticTrigger -> ScheduleDescriptor.Off
            !plan.schedule.hasAutomaticTrigger -> ScheduleDescriptor.ManualOnly
            plan.schedule.dailyEnabled -> ScheduleDescriptor.Daily(
                hour = plan.schedule.dailyHour,
                minute = plan.schedule.dailyMinute,
                alsoAfterMedia = plan.schedule.mediaCountEnabled,
            )
            else -> ScheduleDescriptor.AfterMedia(
                pending = (media?.pendingNewMedia ?: 0).coerceAtLeast(0),
                threshold = plan.schedule.mediaThreshold,
            )
        }
    }
}
