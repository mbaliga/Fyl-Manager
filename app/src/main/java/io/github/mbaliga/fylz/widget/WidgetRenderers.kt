package io.github.mbaliga.fylz.widget

import android.annotation.SuppressLint
import android.content.Context
import android.os.Environment
import android.widget.RemoteViews
import androidx.annotation.StringRes
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.mbaliga.fylz.R
import io.github.mbaliga.fylz.backup.BackupStore
import io.github.mbaliga.fylz.index.LocalIndexScheduler
import io.github.mbaliga.fylz.index.LocalIndexStore
import io.github.mbaliga.fylz.operations.FileOperationType
import io.github.mbaliga.fylz.operations.OperationJournal
import io.github.mbaliga.fylz.operations.OperationState
import io.github.mbaliga.fylz.operations.RecycleBinStore
import io.github.mbaliga.fylz.storage.FullAccessPermission
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import io.github.mbaliga.fylz.storage.SafStorageProvider
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * One reading of a widget's data, ready to draw at any [WidgetSize].
 *
 * A renderer reads the app's stores once (on a background thread) and then builds one
 * `RemoteViews` per size and per placed widget from that single snapshot, so the layouts the
 * launcher switches between on resize always agree with each other.
 */
internal interface WidgetRenderer {
    fun views(appWidgetId: Int, size: WidgetSize): RemoteViews

    /**
     * Upcoming wall-clock boundaries this rendering prints (a backup window), or null when it
     * prints no time at all and so needs no timed redraw. An empty list still means "redraw at
     * midnight", because day words and "as of" times roll over then.
     */
    val timeBoundaries: List<Long>?
}

/** Shared size dispatch for the panel-based widgets. */
internal abstract class PanelRenderer(
    protected val context: Context,
    protected val text: WidgetText,
) : WidgetRenderer {
    protected val pkg: String = context.packageName

    override val timeBoundaries: List<Long>? = emptyList()

    protected abstract fun compact(appWidgetId: Int): WidgetCompactSpec
    protected abstract fun panel(appWidgetId: Int, size: WidgetSize): WidgetPanelSpec

    override fun views(appWidgetId: Int, size: WidgetSize): RemoteViews = when (size) {
        WidgetSize.COMPACT -> WidgetTemplates.compact(pkg, compact(appWidgetId))
        WidgetSize.SMALL -> WidgetTemplates.stat(pkg, panel(appWidgetId, size))
        WidgetSize.MEDIUM -> WidgetTemplates.listMedium(pkg, panel(appWidgetId, size))
        WidgetSize.LARGE -> WidgetTemplates.listLarge(pkg, panel(appWidgetId, size))
    }

    protected fun glyph(@StringRes id: Int): String = text.s(id)
}

/** The honest error state: the widget could not read its data, and says so in words. */
internal class ErrorRenderer(
    context: Context,
    text: WidgetText,
    private val kind: WidgetKind,
) : PanelRenderer(context, text) {
    private val title get() = text.s(kind.label)
    private val message get() = text.s(kind.errorMessage)

    private fun open(appWidgetId: Int) = WidgetIntents.open(context, appWidgetId, WidgetIntents.SLOT_MAIN, WidgetDestination.Home)

    private val description get() = listOf(text.s(R.string.widget_open_app), text.join(title, message)).joinToString(". ")

    override fun compact(appWidgetId: Int) = WidgetCompactSpec(
        text = message,
        glyph = glyph(R.string.widget_glyph_failed),
        click = open(appWidgetId),
        description = description,
    )

    override fun panel(appWidgetId: Int, size: WidgetSize) = WidgetPanelSpec(
        title = title,
        click = open(appWidgetId),
        description = description,
        glyph = glyph(R.string.widget_glyph_failed),
        headline = message,
        rows = listOf(WidgetRowSpec(title = message, glyph = glyph(R.string.widget_glyph_failed))),
        footer = text.asOf(),
    ).let { if (size == WidgetSize.LARGE) it.copy(rows = emptyList()) else it }
}

// ---------------------------------------------------------------------------------------------
// Space
// ---------------------------------------------------------------------------------------------

internal class SpaceRenderer(
    context: Context,
    text: WidgetText,
    private val model: SpaceWidgetModel,
) : PanelRenderer(context, text) {

    private fun label(row: VolumeRow): String = when {
        row.kind == VolumeKind.INTERNAL -> text.s(R.string.widget_space_internal)
        row.ordinal > 0 -> text.s(R.string.widget_space_removable_n, row.ordinal)
        else -> text.s(R.string.widget_space_removable)
    }

    private fun free(row: VolumeRow): String =
        if (row.sizeKnown) text.s(R.string.widget_space_free, text.bytes(row.freeBytes!!)) else text.s(R.string.widget_space_unknown)

    /** "of 118.0 GB · 65% used", plus "Read-only" when it is. */
    private fun detail(row: VolumeRow): String? {
        val size = if (row.sizeKnown) text.s(R.string.widget_space_of_used, text.bytes(row.totalBytes!!), row.usedPercent!!) else null
        val readOnly = if (row.readOnly) text.s(R.string.widget_space_read_only) else null
        return listOfNotNull(size, readOnly).reduceOrNull { a, b -> text.join(a, b) }
    }

    private fun spoken(row: VolumeRow): String {
        val amount = if (row.sizeKnown) {
            text.join(
                text.s(R.string.widget_space_free_of, text.bytes(row.freeBytes!!), text.bytes(row.totalBytes!!)),
                text.s(R.string.widget_space_used, row.usedPercent!!),
            )
        } else {
            text.s(R.string.widget_space_unknown)
        }
        return text.join(label(row), if (row.readOnly) text.join(amount, text.s(R.string.widget_space_read_only)) else amount)
    }

    private fun home(appWidgetId: Int) = WidgetIntents.open(context, appWidgetId, WidgetIntents.SLOT_MAIN, WidgetDestination.Home)

    private val emptyDescription: String
        get() = listOf(text.s(R.string.widget_open_app), text.s(R.string.widget_space_empty)).joinToString(". ")

    override fun compact(appWidgetId: Int): WidgetCompactSpec {
        val first = model.rows.firstOrNull()
            ?: return WidgetCompactSpec(text.s(R.string.widget_space_empty), home(appWidgetId), emptyDescription)
        return WidgetCompactSpec(
            text = free(first),
            click = home(appWidgetId),
            description = text.join(text.s(R.string.widget_open_app), spoken(first)),
            progressPermille = first.usedPermille,
        )
    }

    override fun panel(appWidgetId: Int, size: WidgetSize): WidgetPanelSpec {
        val title = text.s(R.string.widget_space_label)
        val first = model.rows.firstOrNull()
            ?: return WidgetPanelSpec(
                title = title,
                click = home(appWidgetId),
                description = emptyDescription,
                headline = text.s(R.string.widget_space_empty),
                rows = listOf(WidgetRowSpec(title = text.s(R.string.widget_space_empty))),
                footer = text.asOf(),
            )
        if (size == WidgetSize.SMALL) {
            return WidgetPanelSpec(
                title = label(first),
                click = home(appWidgetId),
                description = text.join(text.s(R.string.widget_open_app), spoken(first)),
                headline = free(first),
                caption = detail(first),
                progressPermille = first.usedPermille,
                footer = text.footer(model.hiddenAt(size)),
            )
        }
        // A volume row opens that volume only at LARGE, where rows are 48dp targets; at MEDIUM the
        // whole widget opens the storage home, which lists every volume.
        val rows = model.rowsFor(size).mapIndexed { index, row ->
            WidgetRowSpec(
                title = label(row),
                trailing = free(row),
                meta = detail(row),
                progressPermille = row.usedPermille,
                click = if (size == WidgetSize.LARGE) {
                    WidgetIntents.open(context, appWidgetId, WidgetIntents.SLOT_ITEM_BASE + index, WidgetDestination.Volume(row.rootId))
                } else {
                    null
                },
                description = text.join(text.s(R.string.widget_space_open, label(row)), spoken(row)),
            )
        }
        return WidgetPanelSpec(
            title = title,
            click = home(appWidgetId),
            description = (listOf(text.s(R.string.widget_open_app)) + model.rows.map(::spoken)).joinToString(". "),
            trailing = text.plural(R.plurals.widget_space_volumes, model.rows.size, model.rows.size),
            rows = rows,
            footer = text.footer(model.hiddenAt(size)),
        )
    }

    companion object {
        // usableSpace/totalSpace, not getAllocatableBytes: the widget must print the same numbers
        // as the storage home (FileStorageProvider), which uses these.
        @SuppressLint("UsableSpace")
        fun load(context: Context, text: WidgetText): SpaceRenderer {
            val readings = FylzFilesDocumentsProvider.discoverVolumes(context).map { volume ->
                VolumeReading(
                    rootId = volume.rootId,
                    primary = volume.primary,
                    readOnly = volume.readOnly,
                    // File reports 0 rather than throwing when it cannot stat; VolumeRow treats a
                    // zero total as "size not reported".
                    freeBytes = runCatching { volume.directory.usableSpace }.getOrNull(),
                    totalBytes = runCatching { volume.directory.totalSpace }.getOrNull(),
                )
            }
            return SpaceRenderer(context, text, SpaceWidgetModel.from(readings))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Backups
// ---------------------------------------------------------------------------------------------

internal class BackupsRenderer(
    context: Context,
    text: WidgetText,
    private val model: BackupsWidgetModel,
) : PanelRenderer(context, text) {

    /** The next daily window, and the moment each running run's lease lapses (it then reads "Did not finish"). */
    override val timeBoundaries: List<Long> = listOfNotNull(model.nextWindowAtMillis) + model.runningStaleAtMillis

    private class Status(val glyph: String?, val word: String, val atMillis: Long?)

    private fun word(outcome: RunOutcome): String = text.s(
        when (outcome) {
            RunOutcome.SUCCEEDED -> R.string.widget_run_succeeded
            RunOutcome.FAILED -> R.string.widget_run_failed
            RunOutcome.NEEDS_ATTENTION -> R.string.widget_run_needs_attention
            RunOutcome.CANCELLED -> R.string.widget_run_cancelled
            RunOutcome.RUNNING -> R.string.widget_run_running
            RunOutcome.DID_NOT_FINISH -> R.string.widget_run_did_not_finish
        },
    )

    private fun glyphOf(outcome: RunOutcome): String = glyph(
        when (outcome) {
            RunOutcome.SUCCEEDED -> R.string.widget_glyph_ok
            RunOutcome.FAILED -> R.string.widget_glyph_failed
            RunOutcome.NEEDS_ATTENTION, RunOutcome.DID_NOT_FINISH -> R.string.widget_glyph_attention
            RunOutcome.CANCELLED -> R.string.widget_glyph_waiting
            RunOutcome.RUNNING -> R.string.widget_glyph_running
        },
    )

    private val status: Status = when (model.health) {
        BackupsHealth.NO_PLANS -> Status(null, text.s(R.string.widget_backups_no_plans), null)
        BackupsHealth.NEEDS_ATTENTION -> Status(
            glyph(R.string.widget_glyph_attention),
            text.plural(R.plurals.widget_backups_attention, model.attentionCount, model.attentionCount),
            model.lastRun?.atMillis,
        )
        BackupsHealth.RUNNING -> Status(glyph(R.string.widget_glyph_running), text.s(R.string.widget_run_running), model.lastRun?.atMillis)
        BackupsHealth.NEVER_RUN -> Status(glyph(R.string.widget_glyph_waiting), text.s(R.string.widget_backups_never_run), null)
        BackupsHealth.OK -> model.lastRun!!.let { Status(glyphOf(it.outcome), word(it.outcome), it.atMillis) }
    }

    private val statusLine: String = status.atMillis?.let { text.join(status.word, text.moment(it)) } ?: status.word

    /** The forward-looking line: the next daily window, else the media count, else nothing. */
    private val nextLine: String? = when {
        model.planCount == 0 -> text.s(R.string.widget_backups_no_plans_hint)
        model.nextWindowAtMillis != null -> text.s(R.string.widget_backups_next_window, text.moment(model.nextWindowAtMillis))
        model.mediaProgress != null -> text.plural(
            R.plurals.widget_backups_media_progress,
            model.mediaProgress.threshold,
            model.mediaProgress.pending,
            model.mediaProgress.threshold,
        )
        else -> null
    }

    /** When the headline is not itself a success, the last success is worth its own words. */
    private val lastSuccessLine: String? = model.lastSuccessAtMillis
        ?.takeIf { model.health != BackupsHealth.OK || model.lastRun?.outcome != RunOutcome.SUCCEEDED }
        ?.let { text.s(R.string.widget_backups_last_success, text.moment(it)) }

    private fun schedule(row: BackupPlanRow): String = when (val schedule = row.schedule) {
        is ScheduleDescriptor.Daily -> text.s(
            if (schedule.alsoAfterMedia) R.string.widget_schedule_daily_media else R.string.widget_schedule_daily,
            text.timeOfDay(schedule.hour, schedule.minute),
        )
        // Counts are printed as they are (a run may be due and not yet started); only bars cap.
        is ScheduleDescriptor.AfterMedia ->
            text.plural(R.plurals.widget_schedule_media, schedule.threshold, schedule.pending, schedule.threshold)
        ScheduleDescriptor.ManualOnly -> text.s(R.string.widget_schedule_manual)
        ScheduleDescriptor.Off -> text.s(R.string.widget_schedule_off)
    }

    private fun open(appWidgetId: Int) = WidgetIntents.open(context, appWidgetId, WidgetIntents.SLOT_MAIN, WidgetDestination.Recovery)

    private val description: String
        get() = listOfNotNull(text.s(R.string.widget_backups_open), statusLine, nextLine).joinToString(". ")

    override fun compact(appWidgetId: Int) = WidgetCompactSpec(
        text = statusLine,
        glyph = status.glyph,
        click = open(appWidgetId),
        description = description,
    )

    override fun panel(appWidgetId: Int, size: WidgetSize): WidgetPanelSpec {
        val title = text.s(R.string.widget_backups_label)
        val plans = if (model.planCount > 0) text.plural(R.plurals.widget_backups_plans, model.planCount, model.planCount) else null
        val base = WidgetPanelSpec(title = title, click = open(appWidgetId), description = description, footer = text.asOf())
        return when (size) {
            WidgetSize.COMPACT, WidgetSize.SMALL -> base.copy(
                glyph = status.glyph,
                headline = status.word,
                caption = listOfNotNull(status.atMillis?.let(text::moment), nextLine).joinToString("\n"),
            )
            WidgetSize.MEDIUM -> base.copy(
                trailing = plans,
                rows = listOf(
                    WidgetRowSpec(title = status.word, glyph = status.glyph, trailing = status.atMillis?.let(text::moment)),
                    nextRow(),
                ),
            )
            WidgetSize.LARGE -> base.copy(
                trailing = plans,
                glyph = status.glyph,
                headline = status.word,
                caption = listOfNotNull(status.atMillis?.let(text::moment), lastSuccessLine, nextLine).joinToString(" · "),
                rows = model.rowsFor(size).map { row ->
                    WidgetRowSpec(
                        title = row.outcome?.let(::word) ?: text.s(R.string.widget_backups_never_run_row),
                        glyph = row.outcome?.let(::glyphOf) ?: glyph(R.string.widget_glyph_waiting),
                        trailing = row.outcomeAtMillis?.let(text::moment),
                        meta = schedule(row),
                        progressPermille = (row.schedule as? ScheduleDescriptor.AfterMedia)?.let {
                            ((it.pending.coerceAtMost(it.threshold) * 1000L) / it.threshold).toInt()
                        },
                    )
                },
                footer = text.footer(model.hiddenRowsAt(size)),
            )
        }
    }

    private fun nextRow(): WidgetRowSpec {
        val media = model.mediaProgress
        return when {
            model.planCount == 0 -> WidgetRowSpec(title = text.s(R.string.widget_backups_no_plans_hint))
            model.nextWindowAtMillis != null -> WidgetRowSpec(
                title = text.s(R.string.widget_backups_next_title),
                glyph = glyph(R.string.widget_glyph_waiting),
                trailing = text.moment(model.nextWindowAtMillis),
            )
            media != null -> WidgetRowSpec(
                title = text.plural(R.plurals.widget_backups_media_progress, media.threshold, media.pending, media.threshold),
                glyph = glyph(R.string.widget_glyph_waiting),
                progressPermille = ((media.pending.coerceAtMost(media.threshold) * 1000L) / media.threshold).toInt(),
            )
            else -> WidgetRowSpec(title = text.s(R.string.widget_backups_no_window))
        }
    }

    companion object {
        fun load(context: Context, text: WidgetText): BackupsRenderer {
            val store = BackupStore(context)
            val plans = store.plans()
            val model = BackupsWidgetModel.from(
                plans = plans,
                runs = store.runs(),
                mediaStates = plans.associate { it.id to store.mediaState(it.id) },
                nowMillis = text.nowMillis,
                zone = text.zone,
            )
            return BackupsRenderer(context, text, model)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Recovery
// ---------------------------------------------------------------------------------------------

internal fun WidgetText.operationNoun(type: FileOperationType): String = s(
    when (type) {
        FileOperationType.COPY -> R.string.widget_op_copy
        FileOperationType.MOVE -> R.string.widget_op_move
        FileOperationType.RECYCLE -> R.string.widget_op_recycle
        FileOperationType.RESTORE -> R.string.widget_op_restore
        FileOperationType.PERMANENT_DELETE -> R.string.widget_op_permanent_delete
        FileOperationType.RENAME -> R.string.widget_op_rename
        FileOperationType.CREATE_DIRECTORY -> R.string.widget_op_create_directory
        FileOperationType.CREATE_FILE -> R.string.widget_op_create_file
        FileOperationType.ARCHIVE -> R.string.widget_op_archive
        FileOperationType.EXTRACT -> R.string.widget_op_extract
    },
)

internal class RecoveryRenderer(
    context: Context,
    text: WidgetText,
    private val model: RecoveryWidgetModel,
) : PanelRenderer(context, text) {

    /** The bin's size, worded as a floor when some recycled item has no recorded size. */
    private val binSize: String? = if (model.binCount == 0) {
        null
    } else {
        text.bytes(model.binBytes).let { if (model.binSizeComplete) it else text.s(R.string.widget_recovery_bin_size_floor, it) }
    }

    private val binLine: String =
        binSize?.let { text.join(text.items(model.binCount), it) } ?: text.s(R.string.widget_recovery_bin_empty)

    /** "Recycle Bin · 12 items · 1.4 GB", or "Recycle Bin is empty". */
    private val binSummary: String =
        if (model.binCount == 0) binLine else text.join(text.s(R.string.widget_recovery_bin_title), binLine)

    private val attentionGlyph: String =
        glyph(if (model.attentionCount > 0) R.string.widget_glyph_attention else R.string.widget_glyph_ok)

    private val attentionLine: String = if (model.attentionCount == 0) {
        text.s(R.string.widget_recovery_all_clear)
    } else {
        text.plural(R.plurals.widget_recovery_attention, model.attentionCount, model.attentionCount)
    }

    /** Begins with what the tap opens, then says what the widget shows. */
    private fun description(@StringRes opens: Int): String =
        listOf(text.s(opens), binSummary, attentionLine).joinToString(". ")

    private fun recovery(appWidgetId: Int) = WidgetIntents.open(context, appWidgetId, WidgetIntents.SLOT_MAIN, WidgetDestination.Recovery)
    private fun bin(appWidgetId: Int) = WidgetIntents.open(context, appWidgetId, WidgetIntents.SLOT_ITEM_BASE, WidgetDestination.RecycleBin)
    private fun activity(appWidgetId: Int) = WidgetIntents.operationHistory(context, appWidgetId, WidgetIntents.SLOT_SECONDARY)

    private fun reason(reason: AttentionReason): String = text.s(
        when (reason) {
            AttentionReason.INTERRUPTED -> R.string.widget_reason_interrupted
            AttentionReason.MOVE_CLEANUP -> R.string.widget_reason_move_cleanup
            AttentionReason.REVIEW -> R.string.widget_reason_review
        },
    )

    override fun compact(appWidgetId: Int): WidgetCompactSpec = when {
        model.attentionCount > 0 ->
            WidgetCompactSpec(attentionLine, activity(appWidgetId), description(R.string.widget_recovery_open_activity), glyph = attentionGlyph)
        model.binCount > 0 -> WidgetCompactSpec(
            text.join(text.s(R.string.widget_recovery_bin_title), text.items(model.binCount)),
            bin(appWidgetId),
            description(R.string.widget_recovery_open_bin),
        )
        else -> WidgetCompactSpec(attentionLine, recovery(appWidgetId), description(R.string.widget_recovery_open_room), glyph = attentionGlyph)
    }

    /**
     * The Recycle Bin row and the attention row. Each opens its own screen at LARGE only, where
     * rows are 48dp targets; at MEDIUM the template ignores the clicks and the whole widget opens
     * the Recovery room.
     */
    private fun rows(appWidgetId: Int, size: WidgetSize): List<WidgetRowSpec> {
        val tappable = size == WidgetSize.LARGE
        val latest = model.latestAttention
        return listOf(
            WidgetRowSpec(
                title = text.s(R.string.widget_recovery_bin_title),
                trailing = if (model.binCount == 0) text.s(R.string.widget_recovery_empty) else binLine,
                meta = model.oldestRecycledAtMillis?.let { text.s(R.string.widget_recovery_bin_oldest, text.moment(it)) },
                click = if (tappable) bin(appWidgetId) else null,
                description = text.join(text.s(R.string.widget_recovery_open_bin), binLine),
            ),
            WidgetRowSpec(
                title = attentionLine,
                glyph = attentionGlyph,
                trailing = latest?.let { text.moment(it.atMillis) },
                // The newest operation awaiting the user, by kind, count and reason; never by name.
                meta = latest?.let { text.join(text.join(text.operationNoun(it.type), text.items(it.itemCount)), reason(it.reason)) },
                click = if (tappable) activity(appWidgetId) else null,
                description = text.join(text.s(R.string.widget_recovery_open_activity), attentionLine),
            ),
        )
    }

    override fun panel(appWidgetId: Int, size: WidgetSize): WidgetPanelSpec {
        val title = text.s(R.string.widget_recovery_label)
        return when (size) {
            // One value and one tap: the headline names what the tap opens.
            WidgetSize.COMPACT, WidgetSize.SMALL -> if (model.attentionCount > 0) {
                WidgetPanelSpec(
                    title = text.s(R.string.widget_recovery_attention_title),
                    click = activity(appWidgetId),
                    description = description(R.string.widget_recovery_open_activity),
                    glyph = attentionGlyph,
                    headline = text.plural(R.plurals.widget_recovery_operations, model.attentionCount, model.attentionCount),
                    caption = binSummary,
                    footer = text.asOf(),
                )
            } else {
                WidgetPanelSpec(
                    title = text.s(R.string.widget_recovery_bin_title),
                    click = bin(appWidgetId),
                    description = description(R.string.widget_recovery_open_bin),
                    headline = text.items(model.binCount),
                    // The SMALL template's glyph slot belongs to the headline, which here is a plain
                    // count, so the all-clear glyph goes inline beside its own word.
                    caption = listOfNotNull(binSize, "$attentionGlyph $attentionLine").joinToString("\n"),
                    footer = text.asOf(),
                )
            }
            WidgetSize.MEDIUM, WidgetSize.LARGE -> WidgetPanelSpec(
                title = title,
                trailing = model.interruptedCount.takeIf { it > 0 && size == WidgetSize.LARGE }
                    ?.let { text.plural(R.plurals.widget_recovery_interrupted, it, it) },
                click = recovery(appWidgetId),
                description = description(R.string.widget_recovery_open_room),
                rows = rows(appWidgetId, size),
                footer = text.asOf(),
            )
        }
    }

    companion object {
        fun load(context: Context, text: WidgetText): RecoveryRenderer =
            RecoveryRenderer(
                context,
                text,
                RecoveryWidgetModel.from(RecycleBinStore(context).list(), OperationJournal(context).list()),
            )
    }
}

// ---------------------------------------------------------------------------------------------
// Operations in progress
// ---------------------------------------------------------------------------------------------

internal class OperationsRenderer(
    context: Context,
    text: WidgetText,
    private val model: OperationsWidgetModel,
) : PanelRenderer(context, text) {

    private fun verb(kind: ActivityKind): String = text.s(
        when (kind) {
            ActivityKind.Backup -> R.string.widget_doing_backup
            is ActivityKind.File -> when (kind.type) {
                FileOperationType.COPY -> R.string.widget_doing_copy
                FileOperationType.MOVE -> R.string.widget_doing_move
                FileOperationType.RECYCLE -> R.string.widget_doing_recycle
                FileOperationType.RESTORE -> R.string.widget_doing_restore
                FileOperationType.PERMANENT_DELETE -> R.string.widget_doing_permanent_delete
                FileOperationType.RENAME -> R.string.widget_doing_rename
                FileOperationType.CREATE_DIRECTORY -> R.string.widget_doing_create_directory
                FileOperationType.CREATE_FILE -> R.string.widget_doing_create_file
                FileOperationType.ARCHIVE -> R.string.widget_doing_archive
                FileOperationType.EXTRACT -> R.string.widget_doing_extract
            }
        },
    )

    private fun glyphOf(row: ActivityRow): String = glyph(
        when (row.phase) {
            ActivityPhase.RUNNING -> R.string.widget_glyph_running
            ActivityPhase.WAITING, ActivityPhase.CHECKING -> R.string.widget_glyph_waiting
            ActivityPhase.PAUSED -> R.string.widget_glyph_paused
        },
    )

    private fun title(row: ActivityRow): String =
        row.itemCount?.takeIf { it > 0 }?.let { text.join(verb(row.kind), text.items(it)) } ?: verb(row.kind)

    /** A phase word when not running; the percentage when running with a known total; else nothing. */
    private fun trailing(row: ActivityRow): String? = when (row.phase) {
        ActivityPhase.WAITING -> text.s(R.string.widget_phase_waiting)
        ActivityPhase.CHECKING -> text.s(R.string.widget_phase_checking)
        ActivityPhase.PAUSED -> text.s(R.string.widget_phase_paused)
        ActivityPhase.RUNNING -> row.progressPercent?.let { text.s(R.string.widget_operations_percent, it) }
    }

    private fun bytes(row: ActivityRow): String? {
        val total = row.totalBytes?.takeIf { it > 0L } ?: return null
        return text.s(R.string.widget_operations_bytes, text.bytes(row.completedBytes ?: 0L), text.bytes(total))
    }

    private fun spoken(row: ActivityRow): String = listOfNotNull(title(row), trailing(row), bytes(row)).joinToString(", ")

    private val lastFinished: String? = model.lastFinished?.let {
        val state = text.s(
            when (it.state) {
                OperationState.SUCCEEDED -> R.string.widget_finished_succeeded
                OperationState.FAILED -> R.string.widget_finished_failed
                OperationState.CANCELLED -> R.string.widget_finished_cancelled
                else -> R.string.widget_finished_needs_attention
            },
        )
        text.s(R.string.widget_operations_last_finished, text.operationNoun(it.type), state, text.moment(it.atMillis))
    }

    private val description: String
        get() = listOf(text.s(R.string.widget_operations_open)).plus(
            if (model.isIdle) listOfNotNull(text.s(R.string.widget_operations_idle), lastFinished) else model.active.map(::spoken),
        ).joinToString(". ")

    private fun open(appWidgetId: Int) = WidgetIntents.operationHistory(context, appWidgetId, WidgetIntents.SLOT_MAIN)

    /** The watchdog and backup-lease redraws (see [OperationsWidgetModel.recheckAtMillis]), plus midnight. */
    override val timeBoundaries: List<Long> = model.recheckAtMillis(text.nowMillis)

    override fun compact(appWidgetId: Int): WidgetCompactSpec {
        val first = model.active.firstOrNull()
            ?: return WidgetCompactSpec(text.s(R.string.widget_operations_idle), open(appWidgetId), description)
        // "Copying · 42% · as of 14:02": the time says how old the percentage is, even at 2x1 where
        // there is no footer. The item count gives way to it.
        val doing = trailing(first)?.let { text.join(verb(first.kind), it) } ?: verb(first.kind)
        return WidgetCompactSpec(
            text = text.join(doing, text.asOf()),
            click = open(appWidgetId),
            description = description,
            glyph = glyphOf(first),
            progressPermille = first.progressPermille,
        )
    }

    override fun panel(appWidgetId: Int, size: WidgetSize): WidgetPanelSpec {
        val title = text.s(R.string.widget_operations_label)
        val base = WidgetPanelSpec(
            title = title,
            click = open(appWidgetId),
            description = description,
            trailing = model.active.size.takeIf { it > 0 }?.let { text.plural(R.plurals.widget_operations_active, it, it) },
            footer = text.footer(model.hiddenAt(size)),
        )
        val first = model.active.firstOrNull()
        if (first == null) {
            return when (size) {
                WidgetSize.COMPACT, WidgetSize.SMALL -> base.copy(
                    headline = text.s(R.string.widget_operations_idle_short),
                    caption = lastFinished ?: text.s(R.string.widget_operations_idle),
                )
                else -> base.copy(
                    rows = listOfNotNull(
                        WidgetRowSpec(title = text.s(R.string.widget_operations_idle)),
                        lastFinished?.let { WidgetRowSpec(title = it) },
                    ),
                )
            }
        }
        return when (size) {
            WidgetSize.COMPACT, WidgetSize.SMALL -> base.copy(
                glyph = glyphOf(first),
                headline = trailing(first) ?: verb(first.kind),
                caption = title(first),
                progressPermille = first.progressPermille,
            )
            else -> base.copy(
                rows = model.rowsFor(size).map { row ->
                    WidgetRowSpec(
                        title = title(row),
                        glyph = glyphOf(row),
                        trailing = trailing(row),
                        meta = bytes(row),
                        progressPermille = row.progressPermille,
                        description = spoken(row),
                    )
                },
            )
        }
    }

    companion object {
        fun load(context: Context, text: WidgetText): OperationsRenderer {
            val runningBackups = BackupStore(context).runs()
                .filter { BackupsWidgetModel.outcomeOf(it, text.nowMillis) == RunOutcome.RUNNING }
                .map { it.startedAtMillis }
            return OperationsRenderer(context, text, OperationsWidgetModel.from(OperationJournal(context).list(), runningBackups))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Quick folders
// ---------------------------------------------------------------------------------------------

internal class FoldersRenderer(
    private val context: Context,
    private val text: WidgetText,
    private val model: FoldersWidgetModel,
) : WidgetRenderer {

    /**
     * Prints no time. Still redrawn at midnight, so an All files access revoked in Settings, or a
     * standard folder that came or went, shows within a day even if Fylz is never opened.
     */
    override val timeBoundaries: List<Long> = emptyList()

    private fun label(directory: String): String = FOLDER_LABELS[directory]?.let(text::s) ?: directory

    private fun pad(appWidgetId: Int, index: Int, pad: FolderPad): WidgetPadSpec {
        val label = label(pad.directory)
        return WidgetPadSpec(
            label = label,
            click = WidgetIntents.open(context, appWidgetId, WidgetIntents.SLOT_ITEM_BASE + index, WidgetDestination.Folder(pad.directory)),
            description = text.s(R.string.widget_folders_open, label),
        )
    }

    override fun views(appWidgetId: Int, size: WidgetSize): RemoteViews {
        val home = WidgetIntents.open(context, appWidgetId, WidgetIntents.SLOT_MAIN, WidgetDestination.Home)
        val title = text.s(R.string.widget_folders_label)
        val pkg = context.packageName
        if (model.needsAccess) {
            // Not connected: no pad could open its folder, so say so at every size, and open the
            // storage home, where the All files access card is.
            val needs = text.s(R.string.widget_folders_needs_access)
            val description = listOf(text.s(R.string.widget_open_storage_home), title, needs).joinToString(". ")
            return if (size == WidgetSize.COMPACT) {
                WidgetTemplates.compact(pkg, WidgetCompactSpec(text = needs, click = home, description = description, folderIcon = true))
            } else {
                WidgetTemplates.stat(
                    pkg,
                    WidgetPanelSpec(
                        title = title,
                        click = home,
                        description = description,
                        headline = text.s(R.string.widget_folders_needs_access_short),
                        caption = text.s(R.string.widget_folders_needs_access_hint),
                    ),
                )
            }
        }
        if (model.pads.isEmpty()) {
            val empty = text.s(R.string.widget_folders_empty)
            val description = listOf(text.s(R.string.widget_open_app), empty).joinToString(". ")
            return if (size == WidgetSize.COMPACT) {
                WidgetTemplates.compact(pkg, WidgetCompactSpec(empty, home, description))
            } else {
                WidgetTemplates.stat(pkg, WidgetPanelSpec(title = title, click = home, description = description, headline = empty))
            }
        }
        val pads = model.padsFor(size).mapIndexed { index, pad -> pad(appWidgetId, index, pad) }
        return if (size == WidgetSize.COMPACT) {
            val only = pads.first()
            WidgetTemplates.compact(
                pkg,
                WidgetCompactSpec(text = only.label, click = only.click, description = only.description, folderIcon = true),
            )
        } else {
            WidgetTemplates.pads(pkg, size, pads, home, listOf(text.s(R.string.widget_open_app), title).joinToString(". "))
        }
    }

    companion object {
        private val FOLDER_LABELS: Map<String, Int> = mapOf(
            Environment.DIRECTORY_DOWNLOADS to R.string.widget_folder_download,
            Environment.DIRECTORY_DCIM to R.string.widget_folder_dcim,
            Environment.DIRECTORY_DOCUMENTS to R.string.widget_folder_documents,
            Environment.DIRECTORY_PICTURES to R.string.widget_folder_pictures,
            Environment.DIRECTORY_MOVIES to R.string.widget_folder_movies,
            Environment.DIRECTORY_MUSIC to R.string.widget_folder_music,
        )

        /** The storage home's standard folders, in its order. */
        fun standardFolders(): List<String> = SafStorageProvider.STANDARD_DIRECTORIES.map { it.second }

        fun load(context: Context, text: WidgetText): FoldersRenderer {
            val standard = standardFolders()
            val access = FullAccessPermission.isGranted()
            val primary = FylzFilesDocumentsProvider.discoverVolumes(context).firstOrNull { it.primary }?.directory
            // Only whether each folder exists is read; nothing inside is listed or counted.
            val existing = if (access && primary != null) standard.filter { File(primary, it).isDirectory }.toSet() else emptySet()
            return FoldersRenderer(context, text, FoldersWidgetModel.from(standard, existing, opensDirectly = access))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Index
// ---------------------------------------------------------------------------------------------

internal class IndexRenderer(
    context: Context,
    text: WidgetText,
    private val model: IndexWidgetModel,
) : PanelRenderer(context, text) {

    private val statusWord: String = text.s(
        when (model.status) {
            IndexStatus.NOT_SET_UP -> R.string.widget_index_not_set_up
            IndexStatus.PAUSED -> R.string.widget_index_paused
            IndexStatus.REBUILDING -> R.string.widget_index_rebuilding
            IndexStatus.QUEUED -> R.string.widget_index_queued
            IndexStatus.FAILED -> R.string.widget_index_failed
            IndexStatus.NOT_BUILT -> R.string.widget_index_not_built
            IndexStatus.BUILT -> R.string.widget_index_built
        },
    )

    private val statusGlyph: String? = when (model.status) {
        IndexStatus.NOT_SET_UP -> null
        IndexStatus.PAUSED -> glyph(R.string.widget_glyph_paused)
        IndexStatus.REBUILDING -> glyph(R.string.widget_glyph_running)
        IndexStatus.QUEUED, IndexStatus.NOT_BUILT -> glyph(R.string.widget_glyph_waiting)
        IndexStatus.FAILED -> glyph(R.string.widget_glyph_failed)
        IndexStatus.BUILT -> glyph(R.string.widget_glyph_ok)
    }

    /** When a completed index was built; only the BUILT state has a time of its own to show. */
    private val builtAt: String? = model.lastCompletedAtMillis?.takeIf { model.status == IndexStatus.BUILT }?.let(text::moment)

    /** "Built · Today 02:10": the build time carries the meaning, since nothing follows the folders afterwards. */
    private val statusLine: String = builtAt?.let { text.join(statusWord, it) } ?: statusWord

    private val items: String = text.plural(R.plurals.widget_index_files, model.indexedFiles, text.count(model.indexedFiles))

    private val scope: String = text.join(
        text.plural(R.plurals.widget_index_folders, model.folderCount, model.folderCount),
        text.plural(R.plurals.widget_index_collections, model.collectionCount, model.collectionCount),
    )

    private val truncated: String? = if (model.truncated) text.s(R.string.widget_index_truncated) else null

    private val notSetUp: Boolean get() = model.status == IndexStatus.NOT_SET_UP

    private val description: String
        get() = listOfNotNull(text.s(R.string.widget_index_open), statusLine, items.takeUnless { notSetUp }, truncated)
            .joinToString(". ")

    private fun open(appWidgetId: Int) = WidgetIntents.indexManager(context, appWidgetId, WidgetIntents.SLOT_MAIN)

    override fun compact(appWidgetId: Int) = WidgetCompactSpec(
        text = if (notSetUp) text.join(text.s(R.string.widget_index_label), statusWord) else text.join(statusWord, items),
        click = open(appWidgetId),
        description = description,
        glyph = statusGlyph,
    )

    override fun panel(appWidgetId: Int, size: WidgetSize): WidgetPanelSpec {
        val base = WidgetPanelSpec(title = text.s(R.string.widget_index_label), click = open(appWidgetId), description = description)
        if (notSetUp) {
            return base.copy(
                headline = statusWord,
                caption = text.s(R.string.widget_index_not_set_up_hint),
                rows = listOf(WidgetRowSpec(title = statusWord), WidgetRowSpec(title = text.s(R.string.widget_index_not_set_up_hint))),
            ).let { if (size == WidgetSize.LARGE) it.copy(rows = emptyList()) else it }
        }
        return when (size) {
            // The stat template: the count is the value, "Indexed items" its label.
            WidgetSize.COMPACT, WidgetSize.SMALL -> base.copy(
                title = text.s(R.string.widget_index_items_title),
                glyph = statusGlyph,
                headline = text.count(model.indexedFiles),
                caption = statusLine,
                footer = text.asOf(),
            )
            WidgetSize.MEDIUM -> base.copy(
                trailing = items,
                rows = listOf(
                    WidgetRowSpec(title = statusWord, glyph = statusGlyph, trailing = builtAt),
                    WidgetRowSpec(title = truncated ?: scope),
                ),
                footer = text.asOf(),
            )
            WidgetSize.LARGE -> base.copy(
                glyph = statusGlyph,
                headline = items,
                caption = statusLine,
                rows = listOf(WidgetRowSpec(title = scope, meta = truncated)),
                action = if (model.canRebuild) {
                    WidgetActionSpec(
                        label = text.s(R.string.widget_index_rebuild),
                        description = text.s(R.string.widget_index_rebuild_description),
                        click = WidgetIntents.rebuildIndex(context, appWidgetId),
                    )
                } else {
                    null
                },
                footer = text.asOf(),
            )
        }
    }

    companion object {
        fun load(context: Context, text: WidgetText): IndexRenderer {
            val store = LocalIndexStore(context)
            val work = runCatching {
                WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWork(LocalIndexScheduler.WORK_NAME)
                    .get(WORK_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .let { infos ->
                        when {
                            infos.any { it.state == WorkInfo.State.RUNNING } -> RebuildWork.RUNNING
                            infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED } -> RebuildWork.WAITING
                            else -> RebuildWork.NONE
                        }
                    }
            }.getOrDefault(RebuildWork.NONE)
            val model = IndexWidgetModel.from(
                state = store.state(),
                enabledFolders = store.scopes().count { it.enabled },
                collections = store.collections().size,
                work = work,
            )
            return IndexRenderer(context, text, model)
        }

        private const val WORK_QUERY_TIMEOUT_SECONDS = 3L
    }
}
