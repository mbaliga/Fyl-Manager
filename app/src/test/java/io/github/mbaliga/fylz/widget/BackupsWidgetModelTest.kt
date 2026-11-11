package io.github.mbaliga.fylz.widget

import io.github.mbaliga.fylz.backup.BackupMediaState
import io.github.mbaliga.fylz.backup.BackupPlan
import io.github.mbaliga.fylz.backup.BackupRunRecord
import io.github.mbaliga.fylz.backup.BackupRunStatus
import io.github.mbaliga.fylz.backup.BackupSchedule
import io.github.mbaliga.fylz.backup.BackupTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class BackupsWidgetModelTest {
    private val zone: ZoneId = ZoneId.of("UTC")
    private val now = LocalDateTime.of(2026, 11, 4, 12, 0).atZone(zone).toInstant().toEpochMilli()
    private val hour = 60L * 60L * 1000L

    private fun plan(
        id: String,
        name: String = "Plan $id",
        enabled: Boolean = true,
        daily: Pair<Int, Int>? = null,
        mediaThreshold: Int? = null,
    ) = BackupPlan(
        id = id,
        name = name,
        sourceTreeUri = "content://com.android.externalstorage.documents/tree/primary%3A$name",
        destinationTreeUri = "content://com.android.externalstorage.documents/tree/1234-ABCD%3ABackups",
        enabled = enabled,
        schedule = BackupSchedule(
            dailyEnabled = daily != null,
            dailyHour = daily?.first ?: 2,
            dailyMinute = daily?.second ?: 0,
            mediaCountEnabled = mediaThreshold != null,
            mediaThreshold = mediaThreshold ?: 25,
        ),
    )

    private fun run(planId: String, status: BackupRunStatus, hoursAgo: Long, message: String? = null) = BackupRunRecord(
        planId = planId,
        trigger = BackupTrigger.MANUAL,
        status = status,
        startedAtMillis = now - hoursAgo * hour,
        completedAtMillis = if (status == BackupRunStatus.RUNNING) null else now - hoursAgo * hour + 60_000L,
        message = message,
    )

    private fun model(
        plans: List<BackupPlan>,
        runs: List<BackupRunRecord> = emptyList(),
        media: Map<String, BackupMediaState> = emptyMap(),
    ) = BackupsWidgetModel.from(plans, runs, media, now, zone)

    @Test
    fun `no plans is its own first-run state`() {
        val model = model(emptyList())
        assertEquals(BackupsHealth.NO_PLANS, model.health)
        assertNull(model.lastRun)
        assertNull(model.nextWindowAtMillis)
    }

    @Test
    fun `plans that never ran say so`() {
        assertEquals(BackupsHealth.NEVER_RUN, model(listOf(plan("a", daily = 2 to 0))).health)
    }

    @Test
    fun `skipped runs are bookkeeping and never count as the last backup`() {
        val model = model(
            listOf(plan("a")),
            listOf(
                run("a", BackupRunStatus.SUCCEEDED, hoursAgo = 10),
                run("a", BackupRunStatus.SKIPPED_THRESHOLD, hoursAgo = 1),
                run("a", BackupRunStatus.SKIPPED_BUSY, hoursAgo = 2),
            ),
        )
        assertEquals(BackupsHealth.OK, model.health)
        assertEquals(RunOutcome.SUCCEEDED, model.lastRun!!.outcome)
        assertEquals(now - 10 * hour + 60_000L, model.lastRun!!.atMillis)
        assertEquals(model.lastRun!!.atMillis, model.lastSuccessAtMillis)
    }

    @Test
    fun `a failed latest run puts that plan in attention even after an older success`() {
        val model = model(
            listOf(plan("a"), plan("b")),
            listOf(
                run("a", BackupRunStatus.SUCCEEDED, hoursAgo = 30),
                run("a", BackupRunStatus.FAILED, hoursAgo = 5),
                run("b", BackupRunStatus.SUCCEEDED, hoursAgo = 1),
            ),
        )
        assertEquals(1, model.attentionCount)
        assertEquals(BackupsHealth.NEEDS_ATTENTION, model.health)
        assertEquals(RunOutcome.SUCCEEDED, model.lastRun!!.outcome)
        assertEquals(listOf(RunOutcome.FAILED, RunOutcome.SUCCEEDED), model.rows.map { it.outcome })
    }

    @Test
    fun `a run still marked running after its lease is reported as not finished`() {
        val fresh = model(listOf(plan("a")), listOf(run("a", BackupRunStatus.RUNNING, hoursAgo = 1)))
        assertEquals(BackupsHealth.RUNNING, fresh.health)
        assertEquals(1, fresh.runningCount)

        val stale = model(listOf(plan("a")), listOf(run("a", BackupRunStatus.RUNNING, hoursAgo = 7)))
        assertEquals(RunOutcome.DID_NOT_FINISH, stale.rows.single().outcome)
        assertEquals(BackupsHealth.NEEDS_ATTENTION, stale.health)
        assertEquals(0, stale.runningCount)
    }

    @Test
    fun `cancelled is not attention`() {
        val model = model(listOf(plan("a")), listOf(run("a", BackupRunStatus.CANCELLED, hoursAgo = 1)))
        assertFalse(RunOutcome.CANCELLED.needsAttention)
        assertEquals(BackupsHealth.OK, model.health)
    }

    @Test
    fun `runs of deleted plans are ignored`() {
        val model = model(listOf(plan("a")), listOf(run("gone", BackupRunStatus.FAILED, hoursAgo = 1)))
        assertEquals(0, model.attentionCount)
        assertNull(model.lastRun)
    }

    @Test
    fun `next window is the earliest among enabled daily plans`() {
        val model = model(
            listOf(
                plan("a", daily = 23 to 30),
                plan("b", daily = 13 to 15),
                plan("c", daily = 12 to 30, enabled = false),
                plan("d"),
            ),
        )
        assertEquals(now + hour + 15 * 60_000L, model.nextWindowAtMillis)
    }

    @Test
    fun `media progress follows the plan closest to its threshold`() {
        val model = model(
            listOf(plan("a", mediaThreshold = 100), plan("b", mediaThreshold = 10)),
            media = mapOf(
                "a" to BackupMediaState(initialized = true, pendingNewMedia = 40),
                "b" to BackupMediaState(initialized = true, pendingNewMedia = 7),
            ),
        )
        assertEquals(MediaProgress(pending = 7, threshold = 10), model.mediaProgress)
    }

    @Test
    fun `schedules are described by trigger`() {
        assertEquals(ScheduleDescriptor.Daily(2, 30, alsoAfterMedia = true), BackupsWidgetModel.describe(plan("a", daily = 2 to 30, mediaThreshold = 5), null))
        assertEquals(
            ScheduleDescriptor.AfterMedia(pending = 3, threshold = 5),
            BackupsWidgetModel.describe(plan("a", mediaThreshold = 5), BackupMediaState(pendingNewMedia = 3)),
        )
        assertEquals(ScheduleDescriptor.ManualOnly, BackupsWidgetModel.describe(plan("a"), null))
        assertEquals(ScheduleDescriptor.Off, BackupsWidgetModel.describe(plan("a", enabled = false, daily = 2 to 0), null))
    }

    @Test
    fun `plan rows appear only in the large layout`() {
        val model = model((1..5).map { plan("p$it", daily = 2 to 0) })
        assertTrue(model.rowsFor(WidgetSize.MEDIUM).isEmpty())
        assertEquals(1, BackupsWidgetModel.MAX_ROWS)
        assertEquals(BackupsWidgetModel.MAX_ROWS, model.rowsFor(WidgetSize.LARGE).size)
        assertEquals(5 - BackupsWidgetModel.MAX_ROWS, model.hiddenRowsAt(WidgetSize.LARGE))
    }

    @Test
    fun `the row that fits is the most urgent plan`() {
        val plans = listOf(plan("ok", daily = 2 to 0), plan("busy", daily = 3 to 0), plan("broken", daily = 4 to 0))
        val running = model(plans, listOf(run("ok", BackupRunStatus.SUCCEEDED, hoursAgo = 1), run("busy", BackupRunStatus.RUNNING, hoursAgo = 1)))
        assertEquals(RunOutcome.RUNNING, running.rowsFor(WidgetSize.LARGE).single().outcome)

        val failing = model(
            plans,
            listOf(
                run("ok", BackupRunStatus.SUCCEEDED, hoursAgo = 1),
                run("busy", BackupRunStatus.RUNNING, hoursAgo = 1),
                run("broken", BackupRunStatus.FAILED, hoursAgo = 2),
            ),
        )
        assertEquals(RunOutcome.FAILED, failing.rowsFor(WidgetSize.LARGE).single().outcome)
        // The model's own row list keeps plan order; only the drawn row is chosen by urgency.
        assertEquals(listOf(RunOutcome.SUCCEEDED, RunOutcome.RUNNING, RunOutcome.FAILED), failing.rows.map { it.outcome })
    }

    @Test
    fun `a running run schedules a redraw for when its lease lapses`() {
        val model = model(listOf(plan("a"), plan("b")), listOf(run("a", BackupRunStatus.RUNNING, hoursAgo = 2), run("b", BackupRunStatus.SUCCEEDED, hoursAgo = 1)))
        assertEquals(listOf(now - 2 * hour + BackupsWidgetModel.RUNNING_STALE_AFTER_MILLIS), model.runningStaleAtMillis)

        val lapsed = model(listOf(plan("a")), listOf(run("a", BackupRunStatus.RUNNING, hoursAgo = 7)))
        assertTrue("a run already past its lease needs no redraw for it", lapsed.runningStaleAtMillis.isEmpty())
    }

    @Test
    fun `nothing a user named reaches the widget model`() {
        val model = model(
            listOf(plan("a", name = "Wedding photos", daily = 2 to 0), plan("b", name = "Tax returns 2026")),
            listOf(
                run("a", BackupRunStatus.FAILED, hoursAgo = 2, message = "Source folder Wedding photos is unavailable."),
                run("b", BackupRunStatus.SUCCEEDED, hoursAgo = 3),
            ),
        )
        val shown = model.toString()
        listOf("Wedding", "Tax returns", "content://", "unavailable").forEach {
            assertFalse("model leaked '$it': $shown", shown.contains(it))
        }
    }
}
