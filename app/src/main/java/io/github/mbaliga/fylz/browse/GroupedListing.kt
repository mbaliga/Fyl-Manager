package io.github.mbaliga.fylz.browse

import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One row of a Stacks-grouped listing: a section title, or the entry itself. */
sealed interface ListingRow {
    data class Header(val label: String, val count: Int) : ListingRow
    data class Item(val entry: FileEntry) : ListingRow
}

/**
 * [rows] interleaved with section headers, plus the index translation the edge scrubber needs
 * to keep working once headers are in the mix.
 *
 * The scrubber maps a lazy-list index straight onto an entry index today -- the reason this
 * codebase has no `stickyHeader` anywhere (see the comment above `DetailsHeaderRow` in
 * `FylzV1App.kt`). The moment a [ListingRow.Header] is interleaved that 1:1 mapping breaks: row 5
 * of the `LazyColumn` might be entry 3. [entryIndexOf] and [lazyIndexOf] are that translation,
 * computed once alongside [rows] rather than re-derived by counting headers on every scrub frame.
 */
class GroupedListing internal constructor(
    val rows: List<ListingRow>,
    private val entryIndexByRow: List<Int?>,
    private val rowIndexByEntry: List<Int>,
) {
    /** The entry index shown at [lazyIndex], or `null` when that row is a [ListingRow.Header]. */
    fun entryIndexOf(lazyIndex: Int): Int? = entryIndexByRow.getOrNull(lazyIndex)

    /** The row index that shows entry [entryIndex] once headers are interleaved. */
    fun lazyIndexOf(entryIndex: Int): Int = rowIndexByEntry[entryIndex]
}

/**
 * Groups [entries] into Stacks sections per [SortSpec.groupBy], or passes them through untouched
 * -- one [ListingRow.Item] per entry, identity index translation -- when [SortSpec.groupBy] is
 * `null` or there is nothing to group.
 *
 * A section break is cut exactly where [entryStops] cuts a stop: wherever the group key changes
 * between two consecutive **displayed** entries. [GroupAxis.LETTER], [GroupAxis.SIZE] and
 * [GroupAxis.DATE] key off [initialOf], [sizeBand] and [monthBand] -- the same functions
 * `entryStops` uses for NAME/SIZE/MODIFIED -- so the strip and a Stacks header never disagree
 * about where one bucket ends and the next begins. [GroupAxis.KIND] has no [SortField]
 * counterpart (there is no "kind" sort), so it keys on [FileEntry.kind] directly.
 */
fun groupedListing(
    entries: List<FileEntry>,
    spec: SortSpec,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): GroupedListing {
    val axis = spec.groupBy
    if (axis == null || entries.isEmpty()) {
        val identity = entries.indices.toList()
        return GroupedListing(entries.map { ListingRow.Item(it) }, identity, identity)
    }

    val key = groupKeyFor(axis, zoneId, locale)
    val labels = entries.map(key)

    val rows = ArrayList<ListingRow>(entries.size + 8)
    val entryIndexByRow = ArrayList<Int?>(entries.size + 8)
    val rowIndexByEntry = IntArray(entries.size)

    var runStart = 0
    while (runStart < labels.size) {
        var runEnd = runStart
        while (runEnd < labels.size && labels[runEnd] == labels[runStart]) runEnd++

        rows += ListingRow.Header(labels[runStart], runEnd - runStart)
        entryIndexByRow += null

        for (entryIndex in runStart until runEnd) {
            rows += ListingRow.Item(entries[entryIndex])
            entryIndexByRow += entryIndex
            rowIndexByEntry[entryIndex] = rows.size - 1
        }
        runStart = runEnd
    }

    return GroupedListing(rows, entryIndexByRow, rowIndexByEntry.toList())
}

private fun groupKeyFor(axis: GroupAxis, zoneId: ZoneId, locale: Locale): (FileEntry) -> String {
    val month = DateTimeFormatter.ofPattern("MMM yy", locale).withZone(zoneId)
    return when (axis) {
        GroupAxis.LETTER -> { entry -> initialOf(entry.name, locale) }
        GroupAxis.DATE -> { entry -> monthBand(entry.lastModifiedMillis, month) }
        GroupAxis.SIZE -> { entry -> sizeBand(entry.sizeBytes) }
        GroupAxis.KIND -> { entry -> kindLabel(entry.kind) }
    }
}

/** The section title Stacks shows for [GroupAxis.KIND] -- plural and human, not the raw enum name. */
private fun kindLabel(kind: EntryKind): String = when (kind) {
    EntryKind.DIRECTORY -> "Folders"
    EntryKind.MARKDOWN -> "Markdown"
    EntryKind.TEXT -> "Text"
    EntryKind.IMAGE -> "Images"
    EntryKind.PDF -> "PDFs"
    EntryKind.ARCHIVE -> "Archives"
    EntryKind.AUDIO -> "Audio"
    EntryKind.VIDEO -> "Video"
    EntryKind.OTHER -> "Other"
}
