package io.github.mbaliga.fylz.data

import kotlin.math.max

data class ArchiveSpaceRequirements(
    val temporaryBytes: Long,
    val destinationBytes: Long,
)

data class ArchiveSpaceDecision(
    val allowed: Boolean,
    val requiredBytes: Long,
    val availableBytes: Long?,
    val reason: String? = null,
)

/** Pure, overflow-safe storage estimates used before ZIP extraction. */
object ArchiveSpacePolicy {
    private const val MIN_TEMPORARY_HEADROOM = 16L * 1024L * 1024L
    private const val MIN_DESTINATION_HEADROOM = 8L * 1024L * 1024L

    fun requirements(archiveBytes: Long, uncompressedBytes: Long): ArchiveSpaceRequirements? {
        if (archiveBytes < 0L || uncompressedBytes < 0L) return null
        val temporaryHeadroom = max(MIN_TEMPORARY_HEADROOM, uncompressedBytes / 10L)
        val destinationHeadroom = max(MIN_DESTINATION_HEADROOM, uncompressedBytes / 20L)
        val temporary = safeAdd(archiveBytes, uncompressedBytes, temporaryHeadroom) ?: return null
        val destination = safeAdd(uncompressedBytes, destinationHeadroom) ?: return null
        return ArchiveSpaceRequirements(temporary, destination)
    }

    fun evaluate(requiredBytes: Long, availableBytes: Long?, label: String): ArchiveSpaceDecision {
        if (requiredBytes < 0L) {
            return ArchiveSpaceDecision(false, requiredBytes, availableBytes, "Invalid $label requirement.")
        }
        if (availableBytes == null || availableBytes < 0L) {
            return ArchiveSpaceDecision(true, requiredBytes, null)
        }
        if (availableBytes < requiredBytes) {
            return ArchiveSpaceDecision(
                allowed = false,
                requiredBytes = requiredBytes,
                availableBytes = availableBytes,
                reason = "Not enough $label space. ${requiredBytes - availableBytes} more bytes are required.",
            )
        }
        return ArchiveSpaceDecision(true, requiredBytes, availableBytes)
    }

    private fun safeAdd(vararg values: Long): Long? {
        var total = 0L
        values.forEach { value ->
            if (value < 0L || Long.MAX_VALUE - total < value) return null
            total += value
        }
        return total
    }
}
