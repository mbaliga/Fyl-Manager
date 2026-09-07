package io.github.mbaliga.fylz.browse

import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.util.formatBytes

/** One label/value line in the details room. */
data class DetailFact(val label: String, val value: String)

/**
 * What the top room says about whatever the user is looking at.
 *
 * Pure and framework-free — no `Uri`, no `Context`, no Compose — so the *wording* of the details
 * surface is unit-testable on the JVM. That matters more than it sounds: this is the one surface
 * whose entire job is to be accurate, and "it says 0 B when the provider said nothing" is a
 * correctness bug wearing a formatting bug's clothes.
 *
 * ### Unknown versus absent
 *
 * The two are not the same thing and the room must not blur them.
 *
 * A **directory** has no size and, on the current-folder path, no timestamp we ever asked for:
 * those rows are simply omitted, because a missing answer to a question nobody asked is not a
 * gap. A **file** always gets a Size and a Modified row, and when the provider declined to
 * report one it says so out loud ([NOT_REPORTED]) rather than printing a plausible zero. Null
 * and zero timestamps are routine from real `DocumentsProvider`s — see `entryStops`, which
 * buckets them separately for the same reason — so silently rendering them as `0 B` / `1 Jan
 * 1970` would be the app inventing facts about the user's files.
 */
object EntryDetails {

    /** What a file's row says when the provider returned nothing for it. */
    const val NOT_REPORTED = "Not reported"

    /**
     * The facts for a single entry — a file, or the folder the browser is currently in.
     *
     * Everything the caller does not know is left out, with the one exception described above:
     * a non-directory always carries Size and Modified.
     *
     * @param modified already formatted by the caller. Date formatting is locale-, zone- and
     *   clock-dependent, and pushing it out of here keeps this function's output pinnable in a
     *   test that must not change meaning in June.
     * @param childCount how many entries a directory holds; null when that is not known or the
     *   subject is a file.
     */
    fun facts(
        kind: EntryKind,
        location: String,
        tags: List<String> = emptyList(),
        sizeBytes: Long? = null,
        modified: String? = null,
        mimeType: String? = null,
        childCount: Int? = null,
    ): List<DetailFact> = buildList {
        add(DetailFact("Kind", kind.readableLabel()))
        if (childCount != null) add(DetailFact("Items", plural(childCount, "item")))
        if (kind == EntryKind.DIRECTORY) {
            modified?.let { add(DetailFact("Modified", it)) }
        } else {
            add(DetailFact("Size", sizeBytes?.let(::formatBytes) ?: NOT_REPORTED))
            add(DetailFact("Modified", modified ?: NOT_REPORTED))
        }
        mimeType?.takeIf { it.isNotBlank() }?.let { add(DetailFact("Type", it)) }
        add(DetailFact("Location", location))
        if (tags.isNotEmpty()) add(DetailFact("Tags", tags.joinToString(", ")))
    }

    /**
     * The facts for a multi-entry selection.
     *
     * The total is over the sizes that exist; entries the provider gave no size for are counted
     * separately rather than treated as zero, so "12.0 MiB" never quietly means "12 MiB of the
     * nine files we could measure".
     *
     * @param kinds one per selected entry, in any order.
     * @param sizes one per selected entry, aligned with [kinds] only in length.
     */
    fun selection(kinds: List<EntryKind>, sizes: List<Long?>): List<DetailFact> = buildList {
        add(DetailFact("Selected", plural(kinds.size, "item")))
        val measured = sizes.filterNotNull()
        val unmeasured = sizes.size - measured.size
        add(
            DetailFact(
                "Total size",
                buildString {
                    append(formatBytes(measured.sum()))
                    if (unmeasured > 0) append(" · $unmeasured not reported")
                },
            ),
        )
        val counts = EntryKind.entries.mapNotNull { kind ->
            kinds.count { it == kind }
                .takeIf { it > 0 }
                ?.let { plural(it, kind.readableLabel()) }
        }
        if (counts.isNotEmpty()) add(DetailFact("Kinds", counts.joinToString(" · ")))
    }

    private fun plural(count: Int, noun: String): String =
        if (count == 1) "$count $noun" else "$count ${noun}s"
}

/**
 * The name a kind goes by on screen.
 *
 * The browser used to derive this with `kind.name.lowercase().replaceFirstChar(uppercase)`, which
 * is how the app came to describe files as "Pdf" and folders as "Directory" — a mechanical
 * transform of an identifier, not a word anyone would choose. The two the transform got outright
 * wrong are the two that matter most in a file manager.
 */
fun EntryKind.readableLabel(): String = when (this) {
    EntryKind.DIRECTORY -> "Folder"
    EntryKind.MARKDOWN -> "Markdown"
    EntryKind.TEXT -> "Text"
    EntryKind.IMAGE -> "Image"
    EntryKind.PDF -> "PDF"
    EntryKind.ARCHIVE -> "Archive"
    EntryKind.AUDIO -> "Audio"
    EntryKind.VIDEO -> "Video"
    EntryKind.OTHER -> "File"
}
