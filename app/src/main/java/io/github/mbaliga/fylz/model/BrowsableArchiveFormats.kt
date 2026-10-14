package io.github.mbaliga.fylz.model

import io.github.mbaliga.fylz.preview.FileFormatRegistry

/**
 * Which files open as folders (`docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md` section 2.5): an
 * **explicit, tested set** matched on `FileFormatRegistry.compoundExtension`, with no `kind`
 * precondition -- many of these classify as `EntryKind.OTHER` today (`iso`, `zst`, `lz4`, `cab`,
 * `deb`, `rpm`) and would otherwise never open. It is what `openEntry`, the double-tap split and
 * the preview gate agree on -- and, since M3.4, what `fylz.extract`'s own `enabledWhen` accepts
 * too (`BuiltInActions`'s `CAN_EXTRACT`), once selective extraction stopped being ZIP-only. Only
 * `FileFormatRegistry.archives` (what gets an "Archive" label) stays a separate set with its own
 * job.
 *
 * Excluded, with the reason:
 * - `rar`, `cbr`: libarchive reads them, but the RAR fixture corpus is pending (logged).
 * - `arj`, `img`, `dmg`, `wim`: no libarchive reader in `support_format_all`.
 * - `xar`: needs libxml2 or expat, both OFF in `fylz-archive`'s `build.rs`.
 * - single-file compressed streams `gz`, `bz2`, `xz`, `zst`, `lz4` (a `notes.txt.gz`): libarchive's
 *   `raw` format is not in `support_format_all`, so they are not browsable and keep today's preview.
 */
object BrowsableArchiveFormats {
    val extensions: Set<String> = setOf(
        "zip", "zipx", "jar", "apk", "cbz",
        "7z", "cb7",
        "tar", "tgz", "tbz", "tbz2", "txz", "tzst",
        "tar.gz", "tar.bz2", "tar.xz", "tar.zst", "tar.lz4",
        "iso", "cpio", "ar", "deb", "rpm", "cab", "lha", "lzh", "warc",
    )

    fun matches(name: String): Boolean = FileFormatRegistry.compoundExtension(name) in extensions

    /**
     * M3.6: the extensions libarchive always reads as the ZIP format family
     * (`fylz_archive::ArchiveFormatFamily::ZIP`, `0x50000`) -- what the archive header bar's own
     * "edit in place" gate keys on (`actions/BuiltInActions.kt`'s `ARCHIVE_ENTRY_WRITABLE`), since
     * the browser only knows the archive's file *name* at this point, never its `formatCode`
     * (that lives in the catalog's summary, a suspend lookup this synchronous gate cannot make).
     * A name-based heuristic, same convention [matches] itself already uses; `7z`/`cb7`, every
     * `tar*` variant and everything else in [extensions] is a different format family and stays
     * read-only until a writer for it exists.
     */
    val zipFamilyExtensions: Set<String> = setOf("zip", "zipx", "jar", "apk", "cbz")

    fun isZipFamily(name: String): Boolean = FileFormatRegistry.compoundExtension(name) in zipFamilyExtensions
}

/** Whether tapping this entry pushes an archive location rather than opening a preview (never for a directory). */
val FileEntry.isBrowsableArchive: Boolean
    get() = !isDirectory && BrowsableArchiveFormats.matches(name)
