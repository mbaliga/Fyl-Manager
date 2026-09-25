package io.github.mbaliga.fylz.decoder

import android.os.Parcelable
import io.github.mbaliga.fylz.storage.VolumeInfo
import kotlinx.parcelize.Parcelize
import org.json.JSONException
import org.json.JSONObject

/**
 * The limits the archive engine enforces, mirroring `fylz-archive`'s `policy::Limits` field for
 * field with the same defaults (`docs/agent/DESIGN-M32-SEEKABLE-PFD.md` section 2.2): the seven
 * extraction-policy numbers the Kotlin `ArchiveExtractionLimits` carries today, plus
 * [maxListingEntries], which is not a policy rule but the decoder process's memory bound -- how
 * many entries one inspection may collect before it gives up with `LIMIT_EXCEEDED` (200,000
 * entries measured at about 27 MB of resident memory in the engine, M3.2a).
 *
 * This is the app's one limits type. Inspection and listing use the defaults ([forInspection]);
 * an extraction uses [forExtraction], destination-aware and consent-aware (M3.4,
 * `docs/agent/DESIGN-M34-SELECTIVE-EXTRACT.md` section 2.4). Crosses Binder as a Parcelable
 * (`ArchiveLimits.aidl`); [toRecord] turns it into the uniffi record the engine takes; [toJson]/
 * [fromJson] persist it in an extraction plan.
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
) : Parcelable {

    /** The plan's `limits_json` (M3.4): every field, so a re-run enforces what was consented to. */
    fun toJson(): String = JSONObject()
        .put("maxEntries", maxEntries)
        .put("maxArchiveBytes", maxArchiveBytes)
        .put("maxFileBytes", maxFileBytes)
        .put("maxTotalUncompressedBytes", maxTotalUncompressedBytes)
        .put("maxCompressionRatio", maxCompressionRatio)
        .put("maxPathDepth", maxPathDepth)
        .put("maxNameLength", maxNameLength)
        .put("maxListingEntries", maxListingEntries)
        .toString()

    companion object {
        /** The "not staged" input ceiling (M3.4 section 2.4): a seekable archive is never copied, so its
         * size is bounded only by what the engine can address; `ArchiveSource` keeps 2 GiB for staging. */
        const val HARD_CEILING_BYTES: Long = 256L * 1024L * 1024L * 1024L

        /** Above this expanded size, or above [CONSENT_ENTRIES_THRESHOLD] entries, the confirm sheet
         * needs an explicit tick before the caps below relax. */
        const val CONSENT_BYTES_THRESHOLD: Long = 4L * 1024L * 1024L * 1024L
        const val CONSENT_ENTRIES_THRESHOLD: Int = 10_000

        /** The entry cap with consent -- the listing bound, since nothing larger was ever listed. */
        const val CONSENT_MAX_ENTRIES: Int = 200_000

        /** The least free space an extraction leaves on the destination (with consent: `free - max(5 %,
         * this)`); without consent the old 4 GiB total cap stands whatever the volume holds. */
        const val LOW_STORAGE_THRESHOLD_BYTES: Long = 100L * 1024L * 1024L

        /** vfat's own 4 GiB - 1 file ceiling; the only filesystem-specific per-file rule. */
        const val VFAT_MAX_FILE_BYTES: Long = 4L * 1024L * 1024L * 1024L - 1L

        /** The limits every header-pass inspection and listing runs under: the engine defaults. */
        fun forInspection(): ArchiveLimits = ArchiveLimits()

        /**
         * The limits one extraction runs under (design section 2.4), destination-aware: no input
         * ceiling worth the name (the archive is not staged), the per-file cap only where the
         * filesystem has one (vfat), the total cap from the destination's free space -- with
         * [consent] up to the free-space margin, without it the old 4 GiB whichever is smaller, and
         * exactly 4 GiB when the free space is unknown -- and the entry cap 10,000 or, with consent,
         * 200,000. The ratio rule, depth and name length never change: no consent relaxes them.
         */
        fun forExtraction(volume: VolumeInfo?, consent: Boolean): ArchiveLimits = ArchiveLimits(
            maxEntries = if (consent) CONSENT_MAX_ENTRIES else CONSENT_ENTRIES_THRESHOLD,
            maxArchiveBytes = HARD_CEILING_BYTES,
            maxFileBytes = if (volume?.filesystemType == "vfat") VFAT_MAX_FILE_BYTES else HARD_CEILING_BYTES,
            maxTotalUncompressedBytes = totalCap(volume?.freeBytes, consent),
        )

        /** The total cap alone, recomputed at claim time against the destination's current free space. */
        fun totalCap(freeBytes: Long?, consent: Boolean): Long {
            if (freeBytes == null || freeBytes < 0L) return CONSENT_BYTES_THRESHOLD
            val margin = maxOf(freeBytes / 20L, LOW_STORAGE_THRESHOLD_BYTES)
            val withinFree = (freeBytes - margin).coerceAtLeast(0L)
            return if (consent) withinFree else minOf(CONSENT_BYTES_THRESHOLD, withinFree)
        }

        /** Whether [totalBytes]/[entryCount] need the consent tick before extraction. */
        fun needsConsent(totalBytes: Long, entryCount: Int): Boolean =
            totalBytes > CONSENT_BYTES_THRESHOLD || entryCount > CONSENT_ENTRIES_THRESHOLD

        @Throws(IllegalArgumentException::class)
        fun fromJson(json: String): ArchiveLimits = try {
            val root = JSONObject(json)
            ArchiveLimits(
                maxEntries = root.getInt("maxEntries"),
                maxArchiveBytes = root.getLong("maxArchiveBytes"),
                maxFileBytes = root.getLong("maxFileBytes"),
                maxTotalUncompressedBytes = root.getLong("maxTotalUncompressedBytes"),
                maxCompressionRatio = root.getDouble("maxCompressionRatio"),
                maxPathDepth = root.getInt("maxPathDepth"),
                maxNameLength = root.getInt("maxNameLength"),
                maxListingEntries = root.getInt("maxListingEntries"),
            )
        } catch (e: JSONException) {
            throw IllegalArgumentException("archive limits are not readable: ${e.message}", e)
        }
    }
}
