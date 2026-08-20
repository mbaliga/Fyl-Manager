package io.github.mbaliga.fylz.ui.overview

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.library.FavoriteLocation
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.storage.STORAGE_KIND_ORDER
import io.github.mbaliga.fylz.storage.StorageKind
import io.github.mbaliga.fylz.storage.StorageRoot
import io.github.mbaliga.fylz.storage.StorageUsageSnapshot
import io.github.mbaliga.fylz.storage.label
import io.github.mbaliga.fylz.util.formatBytes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One card on the landing overview, modelled after [io.github.mbaliga.fylz.ui.canvas.BentoMosaic]'s
 * spans-aligned-to-items grid mechanism, not its `FileEntry`-typed span *policy*: every card here
 * carries its own [span] (1 or 2 of the 2-column grid) and [height], the same way `BentoMosaic`
 * pairs [io.github.mbaliga.fylz.canvas.CanvasLayoutPolicy.bentoSpans] with its own fixed
 * `TALL_CELL_HEIGHT`/`SHORT_CELL_HEIGHT`, so [OverviewScreen]'s grid never has to know what kind
 * of card it is laying out to place it correctly.
 */
sealed interface OverviewCard {
    val id: String
    val span: Int
    val height: Dp

    /**
     * A quick-access standard folder (Downloads, and so on). [root] is null when full filesystem
     * access is not granted -- under scoped SAF access there is no folder to list without a
     * picker round trip -- and the card degrades to a permission CTA rather than a fabricated
     * empty state. [entryCount] is null only while the listing is still loading.
     */
    data class QuickAccess(
        val root: StorageRoot?,
        val hasFullAccess: Boolean,
        val entryCount: Int?,
        val thumbnails: List<FileEntry>,
    ) : OverviewCard {
        override val id get() = "quick-access:${root?.id ?: "none"}"
        override val span = 1
        override val height = 216.dp
    }

    /**
     * How many entries of [kind] live in [folderName] -- labelled honestly as "in [folderName]"
     * by the composable that renders this, never as a device-wide figure, because nothing in
     * this app aggregates a kind across the whole device (no MediaStore query exists anywhere).
     */
    data class KindFolder(
        val folderName: String,
        val kind: EntryKind,
        val count: Int,
        val lastModifiedMillis: Long?,
    ) : OverviewCard {
        override val id get() = "kind-folder:$folderName:$kind"
        override val span = 1
        override val height = 216.dp
    }

    /**
     * [usedBytes]/[totalBytes] come from [StorageRoot] and need no scan. [usage] is the last
     * completed [io.github.mbaliga.fylz.storage.StorageScanWorker] pass, or null when one has
     * never run -- the card must tell those two "no number" cases apart, never printing a zero
     * for either.
     */
    data class Storage(
        val hasFullAccess: Boolean,
        val usedBytes: Long?,
        val totalBytes: Long?,
        val usage: StorageUsageSnapshot?,
        val scanning: Boolean,
    ) : OverviewCard {
        override val id = "storage"
        override val span = 2
        override val height = 360.dp
    }

    data class DeletedFiles(
        val totalCount: Int,
        val recoverableBytes: Long,
        val notReportedCount: Int,
        val retentionDescription: String,
    ) : OverviewCard {
        override val id = "deleted-files"
        override val span = 2
        override val height = 196.dp
    }

    /** Favourites, finally with an icon and a tap target on a phone -- the workspace rail only
     *  ever rendered these as bare text at >=900dp, invisible on a handset. */
    data class Pinned(val favorites: List<FavoriteLocation>) : OverviewCard {
        override val id = "pinned"
        override val span = 2
        override val height = 208.dp
    }

    data class Tags(val topTags: List<Pair<String, Int>>) : OverviewCard {
        override val id = "tags"
        override val span = 2
        override val height = 152.dp
    }
}

/** How many rows [Pinned]/[Tags] show before folding the rest into "+N more" -- fixed so the
 *  card's own fixed [OverviewCard.height] never has to grow with the user's own data. */
const val OVERVIEW_LIST_CARD_VISIBLE_ROWS = 4

/**
 * The truth as of this build: no retention/purge policy exists anywhere in the app (see
 * [io.github.mbaliga.fylz.operations.RecycleBinStore]), matching the promise the settings dialog
 * already makes. [OverviewScreen] takes this as a parameter rather than hard-coding it inline so
 * a real preference-backed retention setting, once one exists, has somewhere to plug in without
 * this card needing to change.
 */
const val DEFAULT_RETENTION_DESCRIPTION = "Kept until you empty the bin -- nothing is removed automatically."

/** "12" under [cap], "+99" over it -- the mockup's own treatment for a count nobody wants to see
 *  overflow its inset panel. */
fun cappedCountLabel(count: Int, cap: Int = 99): String = if (count > cap) "+$cap" else count.toString()

/** A sum over values that might not exist, plus how many did not -- the same "not reported versus
 *  zero" split [io.github.mbaliga.fylz.browse.EntryDetails.selection] pins for a multi-item
 *  selection, reused here for the recycle bin's own sizes. */
data class ByteTally(val totalBytes: Long, val unmeasuredCount: Int)

fun tallyBytes(sizes: List<Long?>): ByteTally {
    val measured = sizes.filterNotNull()
    return ByteTally(measured.sum(), sizes.size - measured.size)
}

/** "48.0 GiB" alone, or "48.0 GiB · 3 not reported" once [ByteTally.unmeasuredCount] is nonzero. */
fun ByteTally.describe(): String = buildString {
    append(formatBytes(totalBytes))
    if (unmeasuredCount > 0) append(" · $unmeasuredCount not reported")
}

/** [snapshot]'s bytes in the fixed legend order, one entry per [StorageKind] -- always all six,
 *  even the ones that came back zero, so [StorageKind.ARCHIVES] and [StorageKind.OTHER] are never
 *  the two the legend quietly drops. */
fun storageLegendEntries(snapshot: StorageUsageSnapshot): List<Pair<StorageKind, Long>> =
    STORAGE_KIND_ORDER.map { kind -> kind to snapshot.bytesFor(kind) }

/** What the scan actually accounted for, which is usually less than [OverviewCard.Storage.usedBytes]
 *  -- app data, thumbnails caches and anything below [StorageScanWorker]'s own depth/entry caps
 *  are real used bytes the segmented bar cannot honestly attribute to a kind. */
fun storageAccountedBytes(snapshot: StorageUsageSnapshot): Long = snapshot.kindBytes.values.sum()

// Built per call rather than held in a val, matching io.github.mbaliga.fylz.ui.DetailsRoom's own
// `formatModified`: a cached formatter freezes the locale that was current when this object
// loaded, and both of these are called from composables that can outlive a locale change.

/** Zero and negative timestamps are routine from real providers reporting "unknown", not a real
 *  1970 date -- treated as absent rather than printed. */
fun formatLastEdited(millis: Long?): String? = millis
    ?.takeIf { it > 0L }
    ?.let { DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()).format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) }

fun formatScannedAt(millis: Long): String =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault()).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

/**
 * "Just now" / "5m ago" / "3h ago" / "2d ago" for anything within the last week, falling back to
 * [formatLastEdited]'s plain date past that -- the desktop Recents widget's own row caption. Takes
 * [nowMillis] as a parameter, not `System.currentTimeMillis()` read internally, so a test can pin
 * both sides of the subtraction; a real caller passes the wall clock.
 *
 * Same "zero/negative is unknown, not a date" discipline as [formatLastEdited]: absent rather than
 * a fabricated "Just now" for a timestamp that never happened. A [millis] after [nowMillis] --
 * clock skew, not a real future open -- clamps to "Just now" rather than a negative duration.
 */
fun formatRelativeTime(nowMillis: Long, millis: Long): String? {
    if (millis <= 0L) return null
    val elapsed = (nowMillis - millis).coerceAtLeast(0L)
    return when {
        elapsed < MINUTE_MILLIS -> "Just now"
        elapsed < HOUR_MILLIS -> "${elapsed / MINUTE_MILLIS}m ago"
        elapsed < DAY_MILLIS -> "${elapsed / HOUR_MILLIS}h ago"
        elapsed < WEEK_MILLIS -> "${elapsed / DAY_MILLIS}d ago"
        else -> formatLastEdited(millis)
    }
}

private const val MINUTE_MILLIS = 60_000L
private const val HOUR_MILLIS = 60 * MINUTE_MILLIS
private const val DAY_MILLIS = 24 * HOUR_MILLIS
private const val WEEK_MILLIS = 7 * DAY_MILLIS

/**
 * The same facts the graphical cards show, as monospace text lines -- [OverviewScreen] renders
 * these instead of the card grid under [io.github.mbaliga.fylz.ui.theme.ThemeStyle.CLI], so the
 * two surfaces can never quietly disagree about what a card says.
 */
fun overviewCliHeading(card: OverviewCard): String = when (card) {
    is OverviewCard.QuickAccess -> (card.root?.title ?: "QUICK ACCESS").uppercase()
    is OverviewCard.KindFolder -> "${card.kind.name} IN ${card.folderName.uppercase()}"
    is OverviewCard.Storage -> "STORAGE"
    is OverviewCard.DeletedFiles -> "DELETED FILES"
    is OverviewCard.Pinned -> "PINNED"
    is OverviewCard.Tags -> "TAGS"
}

fun overviewCliLines(card: OverviewCard): List<String> = when (card) {
    is OverviewCard.QuickAccess -> when {
        !card.hasFullAccess || card.root == null -> listOf("no access granted")
        card.entryCount == null -> listOf("loading...")
        card.entryCount == 0 -> listOf("empty")
        else -> listOf("${card.entryCount} items")
    }
    is OverviewCard.KindFolder -> buildList {
        add("${card.count} items")
        formatLastEdited(card.lastModifiedMillis)?.let { add("edited $it") }
    }
    is OverviewCard.Storage -> buildList {
        if (!card.hasFullAccess) {
            add("no access granted")
        } else {
            if (card.usedBytes != null && card.totalBytes != null) {
                add("${formatBytes(card.usedBytes)} / ${formatBytes(card.totalBytes)} used")
            }
            val usage = card.usage
            if (usage == null) {
                add(if (card.scanning) "scanning..." else "not scanned yet")
            } else {
                storageLegendEntries(usage).forEach { (kind, bytes) -> add("  ${kind.label()} ${formatBytes(bytes)}") }
                add("as of ${formatScannedAt(usage.scannedAtMillis)}")
            }
        }
    }
    is OverviewCard.DeletedFiles -> listOf(
        "${cappedCountLabel(card.totalCount)} items",
        ByteTally(card.recoverableBytes, card.notReportedCount).describe(),
        card.retentionDescription,
    )
    is OverviewCard.Pinned -> if (card.favorites.isEmpty()) {
        listOf("none yet")
    } else {
        card.favorites.take(OVERVIEW_LIST_CARD_VISIBLE_ROWS).map { "  * ${it.name}" } +
            overflowLine(card.favorites.size)
    }
    is OverviewCard.Tags -> if (card.topTags.isEmpty()) {
        listOf("none yet")
    } else {
        card.topTags.take(OVERVIEW_LIST_CARD_VISIBLE_ROWS).map { (tag, count) -> "  #$tag ($count)" } +
            overflowLine(card.topTags.size)
    }
}

private fun overflowLine(total: Int): List<String> {
    val extra = total - OVERVIEW_LIST_CARD_VISIBLE_ROWS
    return if (extra > 0) listOf("  +$extra more") else emptyList()
}
