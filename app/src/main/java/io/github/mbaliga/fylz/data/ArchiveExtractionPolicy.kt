package io.github.mbaliga.fylz.data

data class ArchiveEntryMetadata(
    val name: String,
    val directory: Boolean,
    val compressedBytes: Long,
    val uncompressedBytes: Long,
)

data class ArchiveExtractionLimits(
    val maxEntries: Int = 10_000,
    val maxArchiveBytes: Long = 2L * 1024L * 1024L * 1024L,
    val maxFileBytes: Long = 1L * 1024L * 1024L * 1024L,
    val maxTotalUncompressedBytes: Long = 4L * 1024L * 1024L * 1024L,
    val maxCompressionRatio: Double = 200.0,
    val maxPathDepth: Int = 64,
    val maxNameLength: Int = 255,
)

data class ArchiveExtractionDecision(
    val allowed: Boolean,
    val reason: String? = null,
)

/** Pure preflight rules for untrusted ZIP metadata. */
object ArchiveExtractionPolicy {
    fun evaluate(
        archiveBytes: Long,
        entries: List<ArchiveEntryMetadata>,
        limits: ArchiveExtractionLimits = ArchiveExtractionLimits(),
    ): ArchiveExtractionDecision {
        if (archiveBytes < 0L || archiveBytes > limits.maxArchiveBytes) {
            return ArchiveExtractionDecision(false, "Archive exceeds the allowed input size.")
        }
        if (entries.size > limits.maxEntries) {
            return ArchiveExtractionDecision(false, "Archive contains too many entries.")
        }

        var totalUncompressed = 0L
        for (entry in entries) {
            val pathReason = validatePath(entry.name, limits)
            if (pathReason != null) return ArchiveExtractionDecision(false, pathReason)
            if (entry.compressedBytes < 0L || entry.uncompressedBytes < 0L) {
                return ArchiveExtractionDecision(false, "Archive contains an entry with unknown size.")
            }
            if (!entry.directory && entry.uncompressedBytes > limits.maxFileBytes) {
                return ArchiveExtractionDecision(false, "Archive contains a file larger than the extraction limit.")
            }
            if (Long.MAX_VALUE - totalUncompressed < entry.uncompressedBytes) {
                return ArchiveExtractionDecision(false, "Archive size metadata overflowed.")
            }
            totalUncompressed += entry.uncompressedBytes
            if (totalUncompressed > limits.maxTotalUncompressedBytes) {
                return ArchiveExtractionDecision(false, "Archive expands beyond the total extraction limit.")
            }

            if (!entry.directory && entry.uncompressedBytes > 0L) {
                if (entry.compressedBytes == 0L) {
                    return ArchiveExtractionDecision(false, "Archive contains an implausibly compressed entry.")
                }
                val ratio = entry.uncompressedBytes.toDouble() / entry.compressedBytes.toDouble()
                if (ratio > limits.maxCompressionRatio) {
                    return ArchiveExtractionDecision(false, "Archive contains a suspicious compression ratio.")
                }
            }
        }
        return ArchiveExtractionDecision(true)
    }

    private fun validatePath(name: String, limits: ArchiveExtractionLimits): String? {
        if (name.isBlank() || name.length > limits.maxNameLength * limits.maxPathDepth) {
            return "Archive contains an invalid path."
        }
        if ('\u0000' in name || name.startsWith('/') || name.startsWith('\\')) {
            return "Archive contains an absolute or invalid path."
        }
        val normalized = name.replace('\\', '/')
        val segments = normalized.split('/').filter(String::isNotEmpty)
        if (segments.size > limits.maxPathDepth) return "Archive path nesting is too deep."
        if (segments.any { it == "." || it == ".." || it.length > limits.maxNameLength }) {
            return "Archive contains an unsafe path segment."
        }
        if (segments.firstOrNull()?.matches(Regex("^[A-Za-z]:$")) == true) {
            return "Archive contains a drive-qualified path."
        }
        return null
    }
}
