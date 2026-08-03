package io.github.mbaliga.fylz.data

data class ArchiveEntryFacts(
    val name: String,
    val directory: Boolean,
    val compressedBytes: Long,
    val uncompressedBytes: Long,
)

data class ArchiveExtractionLimits(
    val maxEntries: Int = 10_000,
    val maxSingleFileBytes: Long = 512L * 1024L * 1024L,
    val maxTotalUncompressedBytes: Long = 2L * 1024L * 1024L * 1024L,
    val maxCompressionRatio: Double = 200.0,
    val maxPathDepth: Int = 64,
    val maxNameLength: Int = 255,
)

/** Pure preflight checks that run before an archive is extracted to disk. */
object ArchiveSafetyPolicy {
    fun validate(
        entries: List<ArchiveEntryFacts>,
        limits: ArchiveExtractionLimits = ArchiveExtractionLimits(),
    ) {
        require(entries.size <= limits.maxEntries) {
            "Archive contains too many entries (${entries.size}; limit ${limits.maxEntries})."
        }

        var total = 0L
        entries.forEach { entry ->
            require(entry.name.isNotBlank()) { "Archive contains an unnamed entry." }
            require(entry.name.length <= limits.maxNameLength) {
                "Archive entry name is too long: ${entry.name.take(80)}"
            }
            val depth = entry.name.replace('\\', '/').split('/').count { it.isNotEmpty() }
            require(depth <= limits.maxPathDepth) {
                "Archive entry is nested too deeply: ${entry.name.take(80)}"
            }
            require(entry.compressedBytes >= 0L && entry.uncompressedBytes >= 0L) {
                "Archive entry has invalid size metadata: ${entry.name.take(80)}"
            }
            if (!entry.directory) {
                require(entry.uncompressedBytes <= limits.maxSingleFileBytes) {
                    "Archive entry is too large: ${entry.name.take(80)}"
                }
                total = Math.addExact(total, entry.uncompressedBytes)
                require(total <= limits.maxTotalUncompressedBytes) {
                    "Archive expands beyond the ${limits.maxTotalUncompressedBytes}-byte safety limit."
                }
                if (entry.uncompressedBytes > 0L) {
                    require(entry.compressedBytes > 0L) {
                        "Archive entry has an unsafe compression ratio: ${entry.name.take(80)}"
                    }
                    val ratio = entry.uncompressedBytes.toDouble() / entry.compressedBytes.toDouble()
                    require(ratio <= limits.maxCompressionRatio) {
                        "Archive entry exceeds the compression-ratio limit: ${entry.name.take(80)}"
                    }
                }
            }
        }
    }
}
