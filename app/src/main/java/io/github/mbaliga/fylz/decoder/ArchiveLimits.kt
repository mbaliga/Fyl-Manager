package io.github.mbaliga.fylz.decoder

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * The limits the archive engine enforces, mirroring `fylz-archive`'s `policy::Limits` field for
 * field with the same defaults (`docs/agent/DESIGN-M32-SEEKABLE-PFD.md` section 2.2): the seven
 * extraction-policy numbers the Kotlin `ArchiveExtractionLimits` carries today, plus
 * [maxListingEntries], which is not a policy rule but the decoder process's memory bound -- how
 * many entries one inspection may collect before it gives up with `LIMIT_EXCEEDED` (200,000
 * entries measured at about 27 MB of resident memory in the engine, M3.2a).
 *
 * This is the app's one limits type from M3.2 on. `data.ArchiveExtractionLimits` stays only for
 * `ArchiveService.extractZip` until M3.4 moves extraction onto the engine and deletes it.
 * Crosses Binder as a Parcelable (`ArchiveLimits.aidl`); [toRecord] turns it into the uniffi
 * record the engine takes.
 */
@Parcelize
data class ArchiveLimits(
    val maxEntries: Int = 10_000,
    val maxArchiveBytes: Long = 2L * 1024L * 1024L * 1024L,
    val maxFileBytes: Long = 1L * 1024L * 1024L * 1024L,
    val maxTotalUncompressedBytes: Long = 4L * 1024L * 1024L * 1024L,
    val maxCompressionRatio: Double = 200.0,
    val maxPathDepth: Int = 64,
    val maxNameLength: Int = 255,
    val maxListingEntries: Int = 200_000,
) : Parcelable
