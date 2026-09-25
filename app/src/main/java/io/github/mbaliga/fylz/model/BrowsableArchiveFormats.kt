package io.github.mbaliga.fylz.model

import io.github.mbaliga.fylz.preview.FileFormatRegistry

/**
 * Which files open as folders (`docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md` section 2.5): an
 * **explicit, tested set** matched on `FileFormatRegistry.compoundExtension`, with no `kind`
 * precondition -- many of these classify as `EntryKind.OTHER` today (`iso`, `zst`, `lz4`, `cab`,
 * `deb`, `rpm`) and would otherwise never open. It is what `openEntry`, the double-tap split and
 * the preview gate agree on; `FileFormatRegistry.archives` (what gets an "Archive" label) and
 * `isZipFamilyArchive` (what the zip4j Extract action accepts until M3.4) stay separate sets with
 * separate jobs.
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
}

/** Whether tapping this entry pushes an archive location rather than opening a preview (never for a directory). */
val FileEntry.isBrowsableArchive: Boolean
    get() = !isDirectory && BrowsableArchiveFormats.matches(name)
