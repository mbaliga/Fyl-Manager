package io.github.mbaliga.fylz.browse

import io.github.mbaliga.fylz.model.FileEntry

/** Which attribute the browser orders by. */
enum class SortField(val label: String) {
    NAME("Name"),
    SIZE("Size"),
    MODIFIED("Date modified"),
    TYPE("Type"),
}

enum class SortDirection(val label: String) {
    ASCENDING("Ascending"),
    DESCENDING("Descending"),
}

/**
 * A complete browser ordering.
 *
 * Replaces `DocumentRepository`'s hardcoded "directories first, then case-insensitive name",
 * which was applied unconditionally with no way for a user to change it.
 *
 * [foldersFirst] is kept independent of [direction] on purpose: reversing to Z-A should not
 * shuffle folders into the middle of the file list.
 */
data class SortSpec(
    val field: SortField = SortField.NAME,
    val direction: SortDirection = SortDirection.ASCENDING,
    val foldersFirst: Boolean = true,
) {
    val descending: Boolean get() = direction == SortDirection.DESCENDING

    fun withField(field: SortField): SortSpec =
        if (field == this.field) copy(direction = direction.flipped()) else copy(field = field)

    companion object {
        val Default: SortSpec = SortSpec()
    }
}

fun SortDirection.flipped(): SortDirection =
    if (this == SortDirection.ASCENDING) SortDirection.DESCENDING else SortDirection.ASCENDING

/**
 * Orders [entries] by [spec].
 *
 * Pure and framework-free so it can be unit tested without an emulator. Ties always fall back to
 * case-insensitive name so the order is total and stable -- a `LazyColumn` keyed by URI must not
 * see items swap places between recompositions for equal sort keys.
 */
fun sortEntries(entries: List<FileEntry>, spec: SortSpec): List<FileEntry> {
    val byName = Comparator<FileEntry> { a, b -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name) }

    val primary: Comparator<FileEntry> = when (spec.field) {
        SortField.NAME -> byName
        // Directories have no meaningful size; null sorts as -1 so folders group together at the
        // small end rather than being scattered by whatever the provider happened to report.
        SortField.SIZE -> compareBy { it.sizeBytes ?: -1L }
        SortField.MODIFIED -> compareBy { it.lastModifiedMillis ?: Long.MIN_VALUE }
        SortField.TYPE -> compareBy<FileEntry> { it.extension.lowercase() }.thenComparator(byName::compare)
    }

    val directed = if (spec.descending) primary.reversed() else primary
    val total = directed.thenComparator(byName::compare)

    return if (spec.foldersFirst) {
        entries.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenComparator(total::compare))
    } else {
        entries.sortedWith(total)
    }
}

/** Extension without the dot, or the empty string when the name has none. */
val FileEntry.extension: String
    get() = name.substringAfterLast('.', "").takeIf { it.length in 1..12 } ?: ""
