package io.github.mbaliga.fylz.archive

import java.util.concurrent.ConcurrentHashMap

/**
 * The manual per-archive charset override for legacy ZIP filenames (M3.7): remembered **for the
 * session only**, in an in-memory map keyed by the archive's own [ArchiveRef] -- never persisted
 * to disk, never part of an [ArchiveDocumentId], and consulted for nothing but a display string.
 * Re-decoding a name under a different charset changes nothing about the entry's own identity:
 * [ArchiveTreeEntry.path] (the lossy UTF-8 string the engine reported, what ids, ordinals and
 * `extract_entry_at` all key on) is untouched, and [displayNameFor] never feeds back into it.
 *
 * One instance lives on [io.github.mbaliga.fylz.FylzApplication], read by
 * `storage.ArchiveDocumentsProvider` (off the main thread, per that provider's own contract) and
 * written by the header-bar override control (`ui.actions.ArchiveEncodingControl`, on it) -- a
 * [ConcurrentHashMap] rather than a plain `HashMap` because of that cross-thread use, not because
 * either side needs more than a handful of entries at once.
 */
class ArchiveEncodingOverrides {
    private val overrides = ConcurrentHashMap<ArchiveRef, ArchiveNameEncoding>()

    /** The chosen encoding for [archive]; [ArchiveNameEncoding.AUTO] (the default) when none was set. */
    fun encodingFor(archive: ArchiveRef): ArchiveNameEncoding = overrides[archive] ?: ArchiveNameEncoding.AUTO

    /** Sets [archive]'s override; choosing [ArchiveNameEncoding.AUTO] clears it back to the default. */
    fun setEncoding(archive: ArchiveRef, encoding: ArchiveNameEncoding) {
        if (encoding == ArchiveNameEncoding.AUTO) overrides.remove(archive) else overrides[archive] = encoding
    }

    /**
     * [entry]'s display name, re-decoded under [archive]'s chosen encoding when [entry] is
     * lossy-named and carries its raw bytes ([ArchiveTreeEntry.rawPathBytes]); [ArchiveTreeEntry.name]
     * unchanged for every other entry (the overwhelming majority) or when the bytes are for some
     * reason absent. The raw bytes are the *whole* original pathname, not just its last segment
     * (a legacy Windows tool's own separator may be `\`), so the redecoded string is split on
     * either separator the same way [ArchiveTree.normalize] would.
     */
    fun displayNameFor(archive: ArchiveRef, entry: ArchiveTreeEntry): String {
        val raw = entry.rawPathBytes?.toByteArray() ?: return entry.name
        val decoded = LegacyZipCharsetDetector.decode(raw, encodingFor(archive))
        return decoded.substringAfterLast('/').substringAfterLast('\\')
    }
}
