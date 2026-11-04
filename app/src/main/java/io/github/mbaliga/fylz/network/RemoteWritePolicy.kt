package io.github.mbaliga.fylz.network

/** Thrown when an upload or new folder would land on a name that already exists remotely. */
class RemoteConflictException(
    val name: String,
    val existingIsDirectory: Boolean,
) : IllegalStateException(
    if (existingIsDirectory) "A folder named “$name” already exists here." else "“$name” already exists here.",
)

/**
 * Backend-neutral rules every remote write passes before any network call is made.
 *
 * The backends disagree on safety: SFTP and SMB gate writes themselves, WebDAV and S3 do not, and
 * none of them refuses to replace an existing file. These rules are the single choke point that
 * makes the four behave the same from the user's point of view:
 *
 * - nothing is written unless the saved connection has "Allow writes" switched on;
 * - a new name must be a single, valid path segment on that backend;
 * - an upload never silently replaces an existing file, and never replaces a folder at all;
 * - the connection root itself can never be deleted;
 * - only empty folders are deleted (no backend-specific recursive delete).
 */
object RemoteWritePolicy {
    const val MAX_NAME_LENGTH = 255

    /** Fylz's own in-flight upload marker; refusing it keeps a user name from colliding with one. */
    const val TEMP_UPLOAD_MARKER = ".fylz-upload-"

    private val SMB_RESERVED = charArrayOf(':', '*', '?', '"', '<', '>', '|')

    fun requireWritable(connection: RemoteConnection) {
        require(connection.writesEnabled) {
            "Writes are off for ${connection.displayName}. Edit the connection and turn on “Allow writes”."
        }
    }

    /** Returns null when [name] is acceptable, otherwise a short user-facing reason. */
    fun nameProblem(kind: RemoteKind, name: String): String? = when {
        name.isBlank() -> "Enter a name."
        name != name.trim() -> "Names can’t start or end with a space."
        name == "." || name == ".." -> "That name is reserved."
        name.length > MAX_NAME_LENGTH -> "Names can be at most $MAX_NAME_LENGTH characters."
        name.any { it == '/' || it == '\\' } -> "Names can’t contain / or \\."
        name.any { it.code in 0..31 || it.code == 127 } -> "Names can’t contain control characters."
        TEMP_UPLOAD_MARKER in name -> "That name is reserved for Fylz’s own temporary uploads."
        kind == RemoteKind.SMB && name.any { it in SMB_RESERVED } ->
            "Windows shares don’t allow these characters: : * ? \" < > |"
        kind == RemoteKind.SMB && name.endsWith('.') -> "Windows shares don’t allow names ending in a dot."
        else -> null
    }

    fun requireValidName(kind: RemoteKind, name: String) {
        nameProblem(kind, name)?.let { throw IllegalArgumentException(it) }
    }

    /** The existing entry [name] would collide with, if any. SMB shares compare names case-insensitively. */
    fun conflict(kind: RemoteKind, existing: List<RemoteObject>, name: String): RemoteObject? =
        existing.firstOrNull { entry ->
            if (kind == RemoteKind.SMB) entry.name.equals(name, ignoreCase = true) else entry.name == name
        }

    /**
     * Decides whether an upload of [name] into a folder holding [existing] may go ahead.
     * Throws [RemoteConflictException] unless the user explicitly chose to replace a file.
     */
    fun checkUpload(kind: RemoteKind, existing: List<RemoteObject>, name: String, replaceExisting: Boolean) {
        val clash = conflict(kind, existing, name) ?: return
        if (clash.directory || !replaceExisting) throw RemoteConflictException(clash.name, clash.directory)
    }

    fun checkNewFolder(kind: RemoteKind, existing: List<RemoteObject>, name: String) {
        conflict(kind, existing, name)?.let { throw RemoteConflictException(it.name, it.directory) }
    }

    /** Path of [name] inside [directory], both relative to the connection root. */
    fun childPath(directory: String, name: String): String = RemotePathPolicy.child(directory, name)

    fun requireDeletablePath(path: String) {
        require(RemotePathPolicy.normalize(path).isNotBlank()) { "The connection’s top-level folder can’t be deleted." }
    }

    fun requireEmptyFolder(name: String, contents: List<RemoteObject>) {
        check(contents.isEmpty()) {
            "“$name” isn’t empty. Fylz only deletes empty remote folders; remove its contents first."
        }
    }
}
