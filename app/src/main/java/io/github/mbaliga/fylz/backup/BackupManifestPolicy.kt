package io.github.mbaliga.fylz.backup

import java.util.Locale

object BackupManifestPolicy {
    const val MANIFEST_FILE = ".fylz-backup-manifest.json"
    const val MAX_DEPTH = 128
    const val MAX_ENTRIES = 1_000_000

    private val reservedManifestKey = MANIFEST_FILE.lowercase(Locale.ROOT)

    data class Validation(
        val allowed: Boolean,
        val reason: String? = null,
    )

    fun validateSourceSegment(value: String): Validation {
        if (value.isBlank()) return Validation(false, "Backup entries must have a name.")
        if (value == "." || value == "..") return Validation(false, "Backup entry names cannot be dot paths.")
        if ('/' in value || '\\' in value) {
            return Validation(false, "Backup entry names cannot contain path separators.")
        }
        if (value.length > 255) return Validation(false, "Backup entry names cannot exceed 255 characters.")
        if (value.any { it.code in 0..31 || it.code == 127 }) {
            return Validation(false, "Backup entry names cannot contain control characters.")
        }
        if (value.lowercase(Locale.ROOT) == reservedManifestKey) {
            return Validation(false, "Backup source conflicts with the reserved manifest name.")
        }
        return Validation(true)
    }

    fun validate(manifest: BackupManifest): Validation {
        if (manifest.schemaVersion != 1) return Validation(false, "Unsupported backup manifest version.")
        if (manifest.backupId.isBlank() || manifest.planId.isBlank()) {
            return Validation(false, "Backup manifest identity is missing.")
        }
        if (manifest.entries.size > MAX_ENTRIES) return Validation(false, "Backup contains too many entries.")

        val normalized = linkedMapOf<String, BackupManifestEntry>()
        for (entry in manifest.entries) {
            val path = validatePath(entry.relativePath)
            if (!path.allowed) return path
            if (entry.directory) {
                if (entry.sizeBytes != 0L || entry.sha256 != null) {
                    return Validation(false, "Directory metadata is invalid for ${entry.relativePath}.")
                }
            } else {
                if (entry.sizeBytes < 0L || entry.sha256?.matches(SHA_256) != true) {
                    return Validation(false, "File metadata is invalid for ${entry.relativePath}.")
                }
            }
            val key = normalizedKey(entry.relativePath)
            if (normalized.put(key, entry) != null) {
                return Validation(false, "Backup contains duplicate or case-colliding paths.")
            }
        }

        normalized.forEach { (path, _) ->
            var ancestor = path.substringBeforeLast('/', "")
            while (ancestor.isNotBlank()) {
                val parent = normalized[ancestor]
                if (parent != null && !parent.directory) {
                    return Validation(false, "A file conflicts with a child path in the backup.")
                }
                ancestor = ancestor.substringBeforeLast('/', "")
            }
        }
        return Validation(true)
    }

    fun normalizedKey(path: String): String = path.lowercase(Locale.ROOT)

    private fun validatePath(path: String): Validation {
        if (path.isBlank() || path.startsWith('/') || '\\' in path) {
            return Validation(false, "Backup contains an invalid relative path.")
        }
        val segments = path.split('/')
        if (segments.size > MAX_DEPTH) return Validation(false, "Backup path nesting exceeds the safety limit.")
        segments.forEach { segment ->
            val validation = validateSourceSegment(segment)
            if (!validation.allowed) return validation
        }
        return Validation(true)
    }

    private val SHA_256 = Regex("[0-9a-f]{64}")
}
