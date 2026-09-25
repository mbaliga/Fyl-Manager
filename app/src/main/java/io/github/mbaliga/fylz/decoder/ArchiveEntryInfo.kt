package io.github.mbaliga.fylz.decoder

import android.os.Parcelable
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

/**
 * One row of an archive listing as [ArchiveInspection] carries it out of the decoder process:
 * the engine's `EntryMetadata` (`docs/agent/DESIGN-M32-SEEKABLE-PFD.md` section 2.2) with its
 * `Option`s flattened into sentinels a Parcel can hold -- [UNKNOWN_SIZE] for a size the header
 * does not declare, [UNKNOWN_MTIME] for a missing timestamp, `null` for no link target.
 *
 * [kind] is one of the `KIND_*` codes rather than an enum so the AIDL-facing shape stays plain
 * ints and strings; [isDirectory] is the one question every caller asks.
 */
@Parcelize
data class ArchiveEntryInfo(
    val path: String,
    val kind: Int,
    val linkTarget: String?,
    val uncompressedBytes: Long,
    val mtimeEpochSeconds: Long,
    val mode: Int,
    val encryptedData: Boolean,
    val encryptedMetadata: Boolean,
    /** The stored name was not UTF-8 and [path] is its lossy decoding (legacy CP437/GBK ZIPs;
     * M3.7 adds charset detection). */
    val nameLossy: Boolean,
) : Parcelable {

    @IgnoredOnParcel
    val isDirectory: Boolean = kind == KIND_DIRECTORY

    @IgnoredOnParcel
    val isLink: Boolean = kind == KIND_SYMLINK || kind == KIND_HARDLINK

    companion object {
        const val KIND_FILE = 0
        const val KIND_DIRECTORY = 1
        const val KIND_SYMLINK = 2
        const val KIND_HARDLINK = 3
        /** Device nodes, fifos, sockets: listed, never extracted. */
        const val KIND_OTHER = 4

        const val UNKNOWN_SIZE = -1L
        const val UNKNOWN_MTIME = Long.MIN_VALUE
    }
}
