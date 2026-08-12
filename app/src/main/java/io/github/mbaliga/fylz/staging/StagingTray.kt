package io.github.mbaliga.fylz.staging

import android.net.Uri
import io.github.mbaliga.fylz.core.model.EntryKind

/** One file riding a tray, reduced to what the bulge needs to draw and act on it. */
data class StagedItem(
    val uri: Uri,
    val displayName: String,
    val kind: EntryKind,
)

/**
 * Which tray a drop landed in. The two differ only in what "here" eventually does to the
 * source: a clipboard paste copies and keeps the tray, a move tray moves and empties itself —
 * a move that leaves its manifest behind would invite moving the same files twice.
 */
enum class TrayKind { CLIPBOARD, MOVE }

/**
 * A staging tray: the files visibly "attached" to the clipboard or move bulge.
 *
 * Immutable value semantics on purpose — the tray is UI state that composition watches, and
 * every mutation returning a new tray means the bulge recomposes exactly when the contents
 * change and never otherwise.
 *
 * Adding is idempotent per document: dropping a selection that overlaps the tray must not
 * produce duplicates, because "paste" would then copy one file twice and the looped browse
 * would show phantom copies. Order is preserved with new arrivals at the end, so the carousel
 * reads oldest-first and a fresh drop is one flick away.
 */
data class StagingTray(
    val kind: TrayKind,
    val items: List<StagedItem> = emptyList(),
) {
    val isEmpty: Boolean get() = items.isEmpty()
    val size: Int get() = items.size

    /** Stages [additions], skipping any document already aboard. */
    fun stage(additions: List<StagedItem>): StagingTray {
        val aboard = items.mapTo(HashSet()) { it.uri }
        val fresh = additions.filter { aboard.add(it.uri) }
        return if (fresh.isEmpty()) this else copy(items = items + fresh)
    }

    /** Removes one document; unknown URIs are a no-op rather than an error. */
    fun without(uri: Uri): StagingTray {
        val next = items.filterNot { it.uri == uri }
        return if (next.size == items.size) this else copy(items = next)
    }

    fun clear(): StagingTray = copy(items = emptyList())
}
