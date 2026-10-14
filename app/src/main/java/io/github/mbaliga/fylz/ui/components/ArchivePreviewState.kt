package io.github.mbaliga.fylz.ui.components

import io.github.mbaliga.fylz.archive.ArchiveCatalog
import io.github.mbaliga.fylz.archive.ArchiveDocumentId
import io.github.mbaliga.fylz.archive.ArchiveTreeEntry
import io.github.mbaliga.fylz.decoder.ArchiveInspection
import io.github.mbaliga.fylz.model.FileEntry

/**
 * What the archive preview shows (`docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md` section 2.7): the
 * catalog's summary and tree for the archive an entry names, or the one sentence for why not.
 * Reading through the catalog rather than `ArchiveInspector` means a preview focus lists the
 * archive once and the listing is already on disk when the user then opens it as a folder.
 */
sealed class ArchivePreviewState {
    data class Ready(
        val summary: ArchiveInspection,
        /** The archive had to be copied to cache to be read at all (a provider that only streams). */
        val staged: Boolean,
        /** Entries hidden for unsafe paths (section 2.3). */
        val quarantined: Int,
        /** `null` unless the header pass stopped on damage after some entries. */
        val partialMessage: String?,
        /** The root's children, at most [MAX_ROWS] of them, in archive order. */
        val rows: List<ArchiveTreeEntry>,
        val rowsTruncated: Boolean,
        /** Every entry in the tree, implicit directories included. */
        val entryCount: Int,
    ) : ArchivePreviewState()

    data class Failed(val message: String) : ArchivePreviewState()

    companion object {
        const val MAX_ROWS = 500

        /**
         * Lists the archive [entry] names -- a plain file (a top-level archive), or an entry inside
         * an archive (a nested one) -- through [catalog]. Blocking; run it off the main thread.
         */
        suspend fun load(catalog: ArchiveCatalog, entry: FileEntry): ArchivePreviewState {
            val id = try {
                if (ArchiveDocumentId.isArchiveUri(entry.uri)) ArchiveDocumentId.parse(entry.uri).nestedRoot() else ArchiveDocumentId.root(entry.uri)
            } catch (e: IllegalArgumentException) {
                return Failed(e.message ?: "This archive cannot be browsed.")
            }
            return try {
                val handle = catalog.open(id)
                val children = handle.tree.children("")
                Ready(
                    summary = handle.summary,
                    staged = handle.staged,
                    quarantined = handle.quarantined,
                    partialMessage = if (handle.partial) handle.summary.partialMessage ?: "damaged" else null,
                    rows = children.take(MAX_ROWS),
                    rowsTruncated = children.size > MAX_ROWS,
                    entryCount = handle.tree.size,
                )
            } catch (failure: ArchiveCatalog.Failure) {
                Failed(failure.message ?: "This archive could not be inspected.")
            }
        }
    }
}
