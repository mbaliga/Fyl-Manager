package io.github.mbaliga.fylz.decoder

import io.github.mbaliga.fylz.core.ArchiveEngineException
import io.github.mbaliga.fylz.core.ArchiveEntryKindRecord
import io.github.mbaliga.fylz.core.ArchiveEntryRecord
import io.github.mbaliga.fylz.core.ArchiveExtractOutcomeRecord
import io.github.mbaliga.fylz.core.ArchiveExtractRecord
import io.github.mbaliga.fylz.core.ArchiveInspectionRecord
import io.github.mbaliga.fylz.core.ArchiveLimitsRecord

/**
 * The two-way copy between the uniffi records `fylz-ffi-android` generates into
 * `io.github.mbaliga.fylz.core` (`*Record`, unsigned fields, `Option`s as nullables) and the
 * `decoder.*` Parcelables that cross Binder. Pure functions with no native dependency -- the
 * generated record classes are plain Kotlin -- so `DecoderServiceMappingTest` proves every field
 * on the JVM without loading `libfylz_ffi_android.so`. Every `when` is exhaustive over a sealed
 * or enum type, so a new engine variant fails to compile here rather than mapping silently.
 */

internal fun ArchiveLimits.toRecord(): ArchiveLimitsRecord = ArchiveLimitsRecord(
    maxEntries = maxEntries.toUnsigned(),
    maxArchiveBytes = maxArchiveBytes.toUnsigned(),
    maxFileBytes = maxFileBytes.toUnsigned(),
    maxTotalUncompressedBytes = maxTotalUncompressedBytes.toUnsigned(),
    maxCompressionRatio = maxCompressionRatio,
    maxPathDepth = maxPathDepth.toUnsigned(),
    maxNameLength = maxNameLength.toUnsigned(),
    maxListingEntries = maxListingEntries.toUnsigned(),
)

internal fun ArchiveInspectionRecord.toInspection(): ArchiveInspection = ArchiveInspection(
    outcome = ArchiveInspection.OUTCOME_OK,
    message = null,
    formatCode = formatCode.toClampedInt(),
    formatName = formatName,
    filters = filters,
    archiveBytes = archiveBytes.toClampedLong(),
    entryCount = entryCount.toClampedInt(),
    fileCount = fileCount.toClampedInt(),
    directoryCount = directoryCount.toClampedInt(),
    linkCount = linkCount.toClampedInt(),
    totalUncompressedBytes = totalUncompressed?.toClampedLong() ?: ArchiveInspection.UNKNOWN_SIZE,
    hasEncryptedEntries = hasEncryptedEntries,
    hasEncryptedMetadata = hasEncryptedMetadata,
    hasLossyNames = hasLossyNames,
    policyAllowed = policyAllowed,
    policyReason = policyReason,
    rows = rows.map(ArchiveEntryRecord::toEntryInfo),
    rowsTruncated = rowsTruncated,
    partial = partial,
    partialMessage = partialMessage,
    structuralRefusal = structuralRefusal,
)

internal fun ArchiveEntryRecord.toEntryInfo(): ArchiveEntryInfo = ArchiveEntryInfo(
    path = path,
    ordinal = ordinal.toClampedInt(),
    kind = when (kind) {
        ArchiveEntryKindRecord.FILE -> ArchiveEntryInfo.KIND_FILE
        ArchiveEntryKindRecord.DIRECTORY -> ArchiveEntryInfo.KIND_DIRECTORY
        ArchiveEntryKindRecord.SYMLINK -> ArchiveEntryInfo.KIND_SYMLINK
        ArchiveEntryKindRecord.HARDLINK -> ArchiveEntryInfo.KIND_HARDLINK
        ArchiveEntryKindRecord.OTHER -> ArchiveEntryInfo.KIND_OTHER
    },
    linkTarget = linkTarget,
    uncompressedBytes = uncompressed?.toClampedLong() ?: ArchiveEntryInfo.UNKNOWN_SIZE,
    mtimeEpochSeconds = mtime ?: ArchiveEntryInfo.UNKNOWN_MTIME,
    mode = mode.toClampedInt(),
    encryptedData = encryptedData,
    encryptedMetadata = encryptedMetadata,
    nameLossy = nameLossy,
)

/**
 * The outcome table of `DESIGN-M32-SEEKABLE-PFD.md` section 2.4: each engine exception to its
 * outcome with the engine's own text, and anything else in the decoder process -- a Rust panic
 * surfacing as uniffi's `InternalException`, an `OutOfMemoryError`, a bug -- to
 * [ArchiveInspection.OUTCOME_INTERNAL] with the exception's class name and never a stack trace.
 */
internal fun Throwable.toFailedInspection(): ArchiveInspection {
    val (outcome, message) = toOutcome()
    return ArchiveInspection.failed(outcome, message)
}

/**
 * `archive_extract_ranges`'s outcome record (M3.4a) to the Parcelable: the outcome enum to the same
 * codes `extractEntry`'s exceptions map to, the counts clamped, `stop_ordinal` to
 * [ArchiveExtractResult.NO_STOP_ORDINAL] when absent. Exhaustive over the enum.
 */
internal fun ArchiveExtractRecord.toResult(): ArchiveExtractResult = ArchiveExtractResult(
    outcome = when (outcome) {
        ArchiveExtractOutcomeRecord.OK -> ArchiveExtractResult.OUTCOME_OK
        ArchiveExtractOutcomeRecord.REFUSED -> ArchiveExtractResult.OUTCOME_REFUSED
        ArchiveExtractOutcomeRecord.NOT_SEEKABLE -> ArchiveExtractResult.OUTCOME_NOT_SEEKABLE
        ArchiveExtractOutcomeRecord.UNSUPPORTED -> ArchiveExtractResult.OUTCOME_UNSUPPORTED
        ArchiveExtractOutcomeRecord.CORRUPT -> ArchiveExtractResult.OUTCOME_CORRUPT
        ArchiveExtractOutcomeRecord.LIMIT_EXCEEDED -> ArchiveExtractResult.OUTCOME_LIMIT_EXCEEDED
        ArchiveExtractOutcomeRecord.CANCELLED -> ArchiveExtractResult.OUTCOME_CANCELLED
        ArchiveExtractOutcomeRecord.INTERNAL -> ArchiveExtractResult.OUTCOME_INTERNAL
    },
    message = message,
    bytesWritten = bytesWritten.toClampedLong(),
    entriesWritten = entriesWritten.toClampedInt(),
    entriesFailed = entriesFailed.toClampedInt(),
    stopOrdinal = stopOrdinal?.toClampedInt() ?: ArchiveExtractResult.NO_STOP_ORDINAL,
)

/** The same table for `extractEntry` (M3.3), plus `NotFound` -> [ArchiveExtractResult.OUTCOME_NOT_FOUND]. */
internal fun Throwable.toFailedExtraction(): ArchiveExtractResult {
    val (outcome, message) = toOutcome()
    return ArchiveExtractResult.failed(outcome, message)
}

/**
 * Engine exception -> (outcome, message). `NotFound` maps to [ArchiveExtractResult.OUTCOME_NOT_FOUND],
 * a code only `extractEntry` can produce; the `when` is exhaustive over the sealed class.
 */
private fun Throwable.toOutcome(): Pair<Int, String?> = when (this) {
    is ArchiveEngineException -> when (this) {
        is ArchiveEngineException.NotSeekable -> ArchiveInspection.OUTCOME_NOT_SEEKABLE to detail
        is ArchiveEngineException.Unsupported -> ArchiveInspection.OUTCOME_UNSUPPORTED to detail
        is ArchiveEngineException.Corrupt -> ArchiveInspection.OUTCOME_CORRUPT to detail
        is ArchiveEngineException.LimitExceeded -> ArchiveInspection.OUTCOME_LIMIT_EXCEEDED to "limit exceeded ($rule) at entry $entry"
        is ArchiveEngineException.Internal -> ArchiveInspection.OUTCOME_INTERNAL to detail
        is ArchiveEngineException.NotFound -> ArchiveExtractResult.OUTCOME_NOT_FOUND to "no entry \"$path\" at header $ordinal"
        // M3.4a: one entry's data or header wrong while the archive stays readable -- for the
        // single-entry `extractEntry` that entry is what the caller wanted, so it reads as damage.
        is ArchiveEngineException.Failed -> ArchiveInspection.OUTCOME_CORRUPT to detail
        is ArchiveEngineException.Cancelled -> ArchiveExtractResult.OUTCOME_CANCELLED to "cancelled"
    }
    else -> ArchiveInspection.OUTCOME_INTERNAL to javaClass.simpleName
}

/** A negative limit is meaningless; the engine gets zero, which refuses everything, rather than
 * a wrapped-around huge value that would refuse nothing. */
private fun Int.toUnsigned(): UInt = coerceAtLeast(0).toUInt()

private fun Long.toUnsigned(): ULong = coerceAtLeast(0L).toULong()

/** Engine counts and codes are far below `Int.MAX_VALUE`; clamp rather than wrap if one ever is not. */
private fun UInt.toClampedInt(): Int = if (this > Int.MAX_VALUE.toUInt()) Int.MAX_VALUE else toInt()

private fun ULong.toClampedLong(): Long = if (this > Long.MAX_VALUE.toULong()) Long.MAX_VALUE else toLong()
