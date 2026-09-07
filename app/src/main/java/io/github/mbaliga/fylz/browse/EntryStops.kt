package io.github.mbaliga.fylz.browse

import dev.aarso.cellshell.ScrubberStop
import io.github.mbaliga.fylz.model.FileEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The labelled stops for the edge scrubber over a folder listing.
 *
 * Unlike a photo timeline, Fylz's list has no single natural key: the same folder is ordered by
 * name, size, date or type depending on what the user picked, and the scrubber has to agree
 * with whatever that is. A strip showing months down a list sorted A-Z would be worse than no
 * strip at all — it would be a map of a different place.
 *
 * So the key follows [SortSpec.field]. Stops are cut wherever the key changes between two
 * consecutive entries **in the order they are displayed**, which means this needs no knowledge
 * of the sort direction: a descending list simply produces the same boundaries in reverse.
 *
 * `foldersFirst` needs no special handling either. Folders bunch at the top and share whatever
 * key their names or dates give them, so they produce ordinary stops; the run of directories is
 * visible on the strip as the first stretch of ticks.
 */
fun entryStops(
    entries: List<FileEntry>,
    spec: SortSpec,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): List<ScrubberStop> {
    if (entries.isEmpty()) return emptyList()
    val key = keyFor(spec.field, zoneId, locale)
    val stops = ArrayList<ScrubberStop>()
    var last: String? = null
    entries.forEachIndexed { index, entry ->
        val label = key(entry)
        if (label == last) return@forEachIndexed
        stops += ScrubberStop(label, index)
        last = label
    }
    return stops
}

/**
 * The label a single entry contributes under [field].
 *
 * Built entirely from the bucket functions below ([initialOf], [monthBand], [sizeBand],
 * [extensionBand]) rather than inlining the logic here, because `GroupedListing`'s Stacks
 * grouper keys off the same buckets. One definition each means the strip and a Stacks header can
 * never disagree about where a bucket starts.
 */
private fun keyFor(field: SortField, zoneId: ZoneId, locale: Locale): (FileEntry) -> String {
    val month = DateTimeFormatter.ofPattern("MMM yy", locale).withZone(zoneId)
    return when (field) {
        SortField.NAME -> { entry -> initialOf(entry.name, locale) }
        SortField.MODIFIED -> { entry -> monthBand(entry.lastModifiedMillis, month) }
        SortField.SIZE -> { entry -> sizeBand(entry.sizeBytes) }
        SortField.TYPE -> { entry -> extensionBand(entry, locale) }
    }
}

/**
 * The letter a name files under.
 *
 * Anything that is not a letter collapses to a single `#` bucket rather than getting a stop of
 * its own. A folder of `2024-01-03.log … 2024-12-30.log` would otherwise produce one stop per
 * file — a strip with a thousand identical labels on it, which is not a map of anything.
 */
internal fun initialOf(name: String, locale: Locale = Locale.getDefault()): String {
    val first = name.firstOrNull { !it.isWhitespace() } ?: return NON_ALPHA
    return if (first.isLetter()) first.uppercase(locale) else NON_ALPHA
}

/**
 * The month [lastModifiedMillis] falls in per [formatter], or [UNDATED] when there is no
 * timestamp to trust.
 *
 * [formatter] is built once by the caller rather than inside this function: it runs once per
 * entry in a folder that may hold thousands, and re-parsing the `"MMM yy"` pattern that often
 * would be wasted work every caller can avoid by building it once up front.
 */
internal fun monthBand(lastModifiedMillis: Long?, formatter: DateTimeFormatter): String =
    lastModifiedMillis
        ?.takeIf { it > 0L }
        ?.let { formatter.format(Instant.ofEpochMilli(it)) }
        ?: UNDATED

/**
 * Which order-of-magnitude band a size falls in.
 *
 * Bands, not exact sizes: consecutive files differ by bytes, so an exact-size key would cut a
 * stop at every single row. Powers of 1024 are what a size sort is actually *about* — "where do
 * the big ones start" — and they give a strip with a handful of stops on it whatever the folder
 * holds.
 */
internal fun sizeBand(sizeBytes: Long?): String = when {
    // Directories report no size. They are their own band so a size sort's folder block is one
    // stop rather than being merged into the empty-file band beneath it.
    sizeBytes == null -> FOLDERS
    sizeBytes <= 0L -> "0"
    sizeBytes < 1_024L -> "B"
    sizeBytes < 1_024L * 1_024 -> "KB"
    sizeBytes < 1_024L * 1_024 * 1_024 -> "MB"
    else -> "GB"
}

/** The extension bucket [entry] falls under, or [NO_EXTENSION] for a name that has none. */
internal fun extensionBand(entry: FileEntry, locale: Locale): String =
    entry.extension.uppercase(locale).ifEmpty { NO_EXTENSION }

private const val NON_ALPHA = "#"
private const val UNDATED = "—"
private const val NO_EXTENSION = "·"
private const val FOLDERS = "dir"
