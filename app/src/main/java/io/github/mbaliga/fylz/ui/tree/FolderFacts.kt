package io.github.mbaliga.fylz.ui.tree

import io.github.mbaliga.fylz.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry

/**
 * What the top room says about the folder you are in — the counterpart of Fotoz's photo facts.
 *
 * Derived from the listing already on screen, so it costs no extra I/O and cannot disagree with
 * the file pane. It describes *this folder's own contents*, not everything beneath it: a
 * recursive size would mean walking the tree on a pull-down, and a number that took seconds to
 * appear would make the room feel broken. The room labels it accordingly.
 */
data class FolderFacts(
    val folderCount: Int,
    val fileCount: Int,
    /** Sum of the files whose size the provider reported. */
    val totalBytes: Long,
    /** Files whose size the provider did not report, so [totalBytes] is a lower bound. */
    val unknownSizeCount: Int,
    val newestModifiedMillis: Long?,
    val largestName: String?,
    val largestBytes: Long?,
    /** File kinds present, most common first. Folders are not a kind here. */
    val kinds: List<Pair<EntryKind, Int>>,
) {
    val isEmpty: Boolean get() = folderCount == 0 && fileCount == 0

    companion object {
        val Empty = FolderFacts(0, 0, 0L, 0, null, null, null, emptyList())

        fun from(entries: List<FileEntry>): FolderFacts {
            if (entries.isEmpty()) return Empty
            val files = entries.filterNot { it.isDirectory }
            val sized = files.filter { it.sizeBytes != null }
            val largest = sized.maxByOrNull { it.sizeBytes ?: 0L }
            return FolderFacts(
                folderCount = entries.count { it.isDirectory },
                fileCount = files.size,
                totalBytes = sized.sumOf { it.sizeBytes ?: 0L },
                unknownSizeCount = files.size - sized.size,
                newestModifiedMillis = entries.mapNotNull { it.lastModifiedMillis }.maxOrNull(),
                largestName = largest?.name,
                largestBytes = largest?.sizeBytes,
                kinds = files.groupingBy { it.kind }.eachCount()
                    .entries
                    // Most common first; the enum order breaks ties so the line never reshuffles.
                    .sortedWith(compareByDescending<Map.Entry<EntryKind, Int>> { it.value }.thenBy { it.key.ordinal })
                    .map { it.key to it.value },
            )
        }

        /** "12 files · 3 folders", leaving out a zero half. */
        fun countLine(facts: FolderFacts): String {
            if (facts.isEmpty) return "Empty"
            val parts = buildList {
                if (facts.fileCount > 0) add(plural(facts.fileCount, "file"))
                if (facts.folderCount > 0) add(plural(facts.folderCount, "folder"))
            }
            return parts.joinToString(" · ")
        }

        /** "8 images · 3 PDFs · 1 archive", capped so a mixed folder does not run on. */
        fun kindsLine(facts: FolderFacts, max: Int = 4): String =
            facts.kinds
                .filter { it.first != EntryKind.OTHER }
                .take(max)
                .joinToString(" · ") { (kind, count) -> kindNoun(kind, count) }

        private fun plural(count: Int, noun: String) = if (count == 1) "1 $noun" else "$count ${noun}s"

        private fun kindNoun(kind: EntryKind, count: Int): String {
            val noun = when (kind) {
                EntryKind.MARKDOWN -> "document"
                EntryKind.TEXT -> "text file"
                EntryKind.IMAGE -> "image"
                EntryKind.PDF -> "PDF"
                EntryKind.ARCHIVE -> "archive"
                EntryKind.AUDIO -> "audio file"
                EntryKind.VIDEO -> "video"
                EntryKind.DIRECTORY -> "folder"
                EntryKind.OTHER -> "other file"
            }
            return plural(count, noun)
        }
    }
}
