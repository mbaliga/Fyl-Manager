package io.github.mbaliga.fylz.ui.places

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * The card-stack a collection (Downloads, Videos, Photos, Documents...) is drawn as, from the
 * design handoff's `collections.html`. A collection is a virtual grouping, not a folder, so it gets
 * a stack of its newest files instead of a folder icon: the active card in front, neighbours fanned
 * out to either side.
 *
 * All pure: the pose of a card at any fractional distance from the active one, and how a swipe
 * settles. Units are the handoff's CSS pixels for a 112 x 150 card; the composable scales them.
 */
object CollectionStackSpec {

    const val CARD_W = 112f
    const val CARD_H = 150f
    const val CARD_RADIUS = 13f
    const val BORDER = 3f

    /** The stack's natural width: the active card plus two neighbours' reach either side. */
    const val STACK_W = 280f

    /** Top inset of the cards inside the stack's box, and room left below for the drop shadow. */
    const val TOP = 14f
    const val STACK_H = TOP + CARD_H + 8f

    /** One card step in a swipe, in the handoff's pixels: position = active - dx / STEP. */
    const val SWIPE_STEP = 70f

    /** Cards further than this from the active one are not drawn at all. */
    const val VISIBLE_DISTANCE = 3f

    data class Pose(val x: Float, val y: Float, val rotationDeg: Float, val scale: Float, val alpha: Float, val z: Int)

    /** The pose of a card [d] steps from the active one (negative = to its left). */
    fun pose(d: Float): Pose {
        val a = abs(d)
        val s = sign(d)
        val x = s * (min(a, 1f) * 58f + (min(a, 2f) - 1f).coerceAtLeast(0f) * 26f + (a - 2f).coerceAtLeast(0f) * 10f)
        val rotation = d.coerceIn(-2f, 2f) * 6.5f
        val scale = 1f - min(a, 3f) * 0.1f
        val y = min(a, 2f) * 4f
        val alpha = when {
            a <= 1f -> 1f
            a <= 2f -> 1f - (a - 1f) * 0.5f
            a <= 3f -> (0.5f - (a - 2f) * 0.5f).coerceAtLeast(0f)
            else -> 0f
        }
        return Pose(x, y, rotation, scale, alpha, 100 - (a * 10f).roundToInt())
    }

    /**
     * Where a swipe ends up. The cards follow the finger; on release the stack moves one card if the
     * drag passed 35% of a step or the flick was faster than 0.5 px/ms, otherwise it snaps back.
     * [dragPx] is the finger's travel (negative = leftwards, which advances), [velocityPxPerMs] its
     * velocity at release.
     */
    fun settle(startIndex: Int, dragPx: Float, velocityPxPerMs: Float, itemCount: Int): Int {
        if (itemCount <= 0) return 0
        val direction = when {
            abs(dragPx) > 0.35f * SWIPE_STEP -> -sign(dragPx)
            abs(velocityPxPerMs) > 0.5f -> -sign(velocityPxPerMs)
            else -> 0f
        }
        return (startIndex + direction.toInt()).coerceIn(0, itemCount - 1)
    }
}

/** "Updated 3h ago" and friends, for the line under a collection. */
object RelativeTime {

    fun updated(nowMillis: Long, thenMillis: Long?): String? {
        if (thenMillis == null || thenMillis <= 0L) return null
        val seconds = ((nowMillis - thenMillis) / 1000L).coerceAtLeast(0L)
        val text = when {
            seconds < 60L -> "just now"
            seconds < 3_600L -> "${seconds / 60L}m ago"
            seconds < 86_400L -> "${seconds / 3_600L}h ago"
            seconds < 30L * 86_400L -> "${seconds / 86_400L}d ago"
            seconds < 365L * 86_400L -> "${seconds / (30L * 86_400L)}mo ago"
            else -> "${seconds / (365L * 86_400L)}y ago"
        }
        return if (text == "just now") "Updated just now" else "Updated $text"
    }
}

/** One day's worth of files in a collection. */
data class DayGroup<T>(val date: LocalDate, val title: String, val shortTitle: String, val items: List<T>)

/**
 * Groups a collection's files by the day they were modified, newest day first and newest file first
 * within it, which is how the reference lays a collection out ("Monday, September 23 — 3
 * documents"). Files with no date go in a last "Undated" group rather than being dropped.
 */
object CollectionGrouping {

    fun <T> byDay(
        items: List<T>,
        modifiedMillis: (T) -> Long?,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): List<DayGroup<T>> {
        val sameYear = DateTimeFormatter.ofPattern("EEEE, MMMM d", locale)
        val otherYear = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", locale)
        val short = DateTimeFormatter.ofPattern("MMM d", locale)
        val dated = items.filter { (modifiedMillis(it) ?: 0L) > 0L }
        val undated = items.filter { (modifiedMillis(it) ?: 0L) <= 0L }
        val groups = dated
            .sortedByDescending { modifiedMillis(it) }
            .groupBy { Instant.ofEpochMilli(modifiedMillis(it)!!).atZone(zone).toLocalDate() }
            .map { (date, list) ->
                DayGroup(
                    date = date,
                    title = (if (date.year == today.year) sameYear else otherYear).format(date),
                    shortTitle = short.format(date),
                    items = list,
                )
            }
        return if (undated.isEmpty()) groups else groups + DayGroup(LocalDate.MIN, "Undated", "—", undated)
    }

    /** "1 document", "3 documents". */
    fun countLabel(count: Int, singular: String): String = if (count == 1) "1 $singular" else "$count ${singular}s"
}
