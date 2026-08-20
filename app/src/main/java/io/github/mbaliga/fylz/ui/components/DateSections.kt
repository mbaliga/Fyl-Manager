package io.github.mbaliga.fylz.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.model.FileEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One heading's worth of the frame-2 date-sectioned document grid: a day label ("Monday,
 * September 23"), an honest count line ("3 documents"), and the entries shown under it.
 */
data class DateSection(val label: String, val countLabel: String, val entries: List<FileEntry>)

private val DAY_LABEL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d")

/**
 * Groups [entries] by local calendar day, newest day first, with every entry
 * [FileEntry.lastModifiedMillis] has no answer for collected into one final "Undated" section --
 * never a fabricated date, the same honesty rule the rest of Fylz already holds itself to.
 *
 * Same `java.time` idiom [io.github.mbaliga.fylz.browse.groupedListing]'s own
 * `GroupAxis.DATE`/`monthBand` and `OverviewModels`'s date formatters already use
 * (`DateTimeFormatter.ofPattern`, zone- and locale-aware) -- entries sharing a day merge into one
 * section wherever they fall in [entries] (unlike `groupedListing`'s run-length grouping, which
 * assumes its input is already sorted by the grouping key; a folder's raw listing usually isn't
 * sorted by date, so this groups by actual day value first and orders the resulting sections after).
 *
 * Deliberately free of any Android framework dependency so [DateSectionsTest] calls it from a
 * plain JUnit test, no Robolectric context required: [undatedLabel] and [countLabel] default to
 * plain English literals -- the same "1 item"/"N items" idiom this file's neighbour
 * `FolderFace.kt`'s private `itemCountLabel` already uses for a pure, non-composable count string,
 * and the same choice `io.github.mbaliga.fylz.browse.GroupedListing.kindLabel` makes for its own
 * plural section labels. A composable call site that wants real localization overrides both, e.g.
 * `groupByDay(entries, now, undatedLabel = stringResource(R.string.browse_date_undated), countLabel = { n -> pluralStringResource(R.plurals.browse_date_document_count, n, n) })`
 * -- `strings_browse.xml` carries that production copy already, ready for whichever call site
 * assembles the real screen.
 */
fun groupByDay(
    entries: List<FileEntry>,
    nowMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
    undatedLabel: String = "Undated",
    countLabel: (Int) -> String = ::defaultDocumentCountLabel,
): List<DateSection> {
    // nowMillis is part of the contract (a "how long ago" relative label -- "Today"/"Yesterday" --
    // is the obvious next step here) but frame 2's own mock spells every heading out as a plain
    // weekday+date ("Monday, September 23"), so today's implementation doesn't consume it yet.
    // Keeping the parameter rather than dropping it keeps this function's signature stable for
    // that follow-up and for whatever caller already depends on the two-arg shape the shared
    // contract names.
    val dayFormat = DAY_LABEL_FORMAT.withLocale(locale)
    val byDay: Map<LocalDate?, List<FileEntry>> = entries.groupBy { entry ->
        entry.lastModifiedMillis?.let { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() }
    }

    val dated = byDay.keys.filterNotNull().sortedDescending().map { day ->
        val dayEntries = byDay.getValue(day)
        DateSection(dayFormat.format(day), countLabel(dayEntries.size), dayEntries)
    }
    val undated = byDay[null]
    return if (undated.isNullOrEmpty()) dated else dated + DateSection(undatedLabel, countLabel(undated.size), undated)
}

private fun defaultDocumentCountLabel(count: Int): String = if (count == 1) "1 document" else "$count documents"

/**
 * The frame-2 date-sectioned document grid's content: a heading per [DateSection] followed by its
 * entries, each drawn as a [DogEarPage]. An extension on [LazyGridScope] rather than a
 * `LazyVerticalGrid` of its own, so headers and thumbnails stay one lazy container -- the same
 * reason [io.github.mbaliga.fylz.browse.GroupedListing] interleaves its own headers into one row
 * list instead of running a second one alongside it.
 *
 * Column count and gutter are the *caller's* `LazyVerticalGrid` to set (`columns =
 * GridCells.Fixed(3)`, `horizontalArrangement = Arrangement.spacedBy(12.dp)`,
 * `verticalArrangement = Arrangement.spacedBy(12.dp)` for frame 2's "3-per-row, ~12dp gutters"),
 * since this extension only supplies items into an existing scope. That same grid's own
 * `contentPadding` should reserve frame 2's "~36dp from left" so headers and thumbnails clear
 * [LeftTimelineRail] -- every header here rides that padding rather than adding its own left
 * inset, so the rail's clearance and the grid's own margin never disagree.
 *
 * @param onEntryClick called with the tapped entry; each [DogEarPage] is wrapped in this click
 *   target itself, so [itemContent] only has to draw -- never wire the tap.
 * @param itemContent the real thumbnail for one entry, typically an [EntryThumbnail] call sized to
 *   at least fill the page frame.
 */
fun LazyGridScope.dateSections(
    sections: List<DateSection>,
    onEntryClick: (FileEntry) -> Unit,
    itemContent: @Composable (FileEntry) -> Unit,
) {
    sections.forEach { section ->
        item(key = "date-header-${section.label}", span = { GridItemSpan(maxLineSpan) }) {
            DateSectionHeader(section.label, section.countLabel)
        }
        items(section.entries, key = { it.uri.toString() }) { entry ->
            DogEarPage(modifier = Modifier.clickable { onEntryClick(entry) }) { itemContent(entry) }
        }
    }
}

/** frame 2's section rhythm: 28dp above the heading, 12dp between the count line and the grid below it. */
@Composable
private fun DateSectionHeader(label: String, countLabel: String) {
    Column(Modifier.padding(top = 28.dp, bottom = 12.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = countLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
