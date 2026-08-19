package io.github.mbaliga.fylz.operations

import android.content.Context

/**
 * How long a recycled item sits in the bin before an automatic purge is allowed to remove it for
 * good. [KEEP_UNTIL_EMPTIED] is the default and matches the promise
 * `docs/product/preview-and-recycle-bin-contract.md` made before this preference existed --
 * nothing leaves the bin except the user's own restore, permanent-delete, or Empty Recycle Bin
 * action. Choosing a numbered window is an opt-in relaxation of that, not the shipped behavior.
 */
enum class RecycleBinRetentionPeriod(val days: Int?) {
    KEEP_UNTIL_EMPTIED(null),
    SEVEN_DAYS(7),
    THIRTY_DAYS(30),
    SIXTY_DAYS(60),
}

/** The exact sentence a user picked -- the overview's Deleted Files card and every recycle-bin
 *  surface print this, never a paraphrase of it. */
fun RecycleBinRetentionPeriod.describe(): String = when (this) {
    RecycleBinRetentionPeriod.KEEP_UNTIL_EMPTIED ->
        "Kept until you empty the bin -- nothing is removed automatically."
    RecycleBinRetentionPeriod.SEVEN_DAYS -> "Deleted files are removed automatically after 7 days."
    RecycleBinRetentionPeriod.THIRTY_DAYS -> "Deleted files are removed automatically after 30 days."
    RecycleBinRetentionPeriod.SIXTY_DAYS -> "Deleted files are removed automatically after 60 days."
}

/**
 * Persists the user's retention choice, [io.github.mbaliga.fylz.settings.AppPreferencesStore]-
 * modelled: a single SharedPreferences file, a stored enum `name`, [KEEP_UNTIL_EMPTIED] whenever
 * nothing has been written yet or the stored value no longer parses.
 *
 * Deliberately its own file rather than folded into [io.github.mbaliga.fylz.settings
 * .AppPreferencesStore] or [RecycleBinStore] -- this is a schedule-affecting preference a
 * [RecycleBinRetentionScheduler] reads from a WorkManager worker, not a per-session display
 * setting or recycle-record data.
 */
class RecycleBinRetentionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun period(): RecycleBinRetentionPeriod {
        val raw = preferences.getString(PERIOD_KEY, null)
            ?: return RecycleBinRetentionPeriod.KEEP_UNTIL_EMPTIED
        return runCatching { RecycleBinRetentionPeriod.valueOf(raw) }
            .getOrDefault(RecycleBinRetentionPeriod.KEEP_UNTIL_EMPTIED)
    }

    @Synchronized
    fun setPeriod(period: RecycleBinRetentionPeriod) {
        preferences.edit().putString(PERIOD_KEY, period.name).commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "fylz_recycle_retention"
        const val PERIOD_KEY = "period"
    }
}

/**
 * Which [RecycleRecord]s a [RecycleBinRetentionPeriod] authorizes an automatic purge to remove,
 * given the current time. Pure and Context-free on purpose -- [RecycleBinRetentionWorker] is the
 * only caller that needs a Context, and keeping the threshold math separate from it makes the
 * math itself unit-testable without Robolectric or WorkManager.
 */
object RecycleBinRetentionPolicy {
    fun expired(
        records: List<RecycleRecord>,
        period: RecycleBinRetentionPeriod,
        nowMillis: Long,
    ): List<RecycleRecord> {
        val days = period.days ?: return emptyList()
        val cutoffMillis = nowMillis - days * MILLIS_PER_DAY
        return records.filter { it.recycledAtMillis <= cutoffMillis }
    }

    private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L
}
