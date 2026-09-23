package io.github.mbaliga.fylz.storage

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import java.io.File

/**
 * What P1.5's `PreflightPolicy` needs to know about a transfer's destination volume, resolved
 * once per transfer (every item in a batch lands on the same destination).
 *
 * @param filesystemType e.g. `"vfat"`, `"exfat"`, `"ext4"` -- null when it could not be determined
 *   (any destination this app has no real on-disk [File] for -- a third-party SAF provider, or the
 *   system `ExternalStorageProvider`, whose files this app never touches directly). Every
 *   filesystem-specific rule (the vfat 4 GiB file limit, vfat/exfat's illegal-character and
 *   trailing-space-or-dot rules) simply does not apply when this is null, rather than guessing.
 * @param freeBytes null when it could not be determined at all (neither a real path nor a
 *   provider root query succeeded) -- the free-space check is then skipped, not treated as zero.
 * @param caseInsensitive whether two names differing only in case collide at this destination --
 *   true for vfat/exfat, and also true anywhere under Android's own shared storage
 *   (`/storage/emulated`), which is itself case-insensitive independent of the backing filesystem.
 */
data class VolumeInfo(
    val filesystemType: String?,
    val freeBytes: Long?,
    val caseInsensitive: Boolean,
)

/** Resolves [VolumeInfo] for a real transfer destination. */
object VolumeInfoResolver {

    /**
     * @param destinationTreeUri the transfer's destination tree, for the [queryRootAvailableBytes]
     *   fallback when [destinationPath] is null.
     * @param destinationPath the destination's real on-disk path, when this app has one --
     *   [FylzFilesDocumentsProvider.fileFor] for its own provider, null for anything else (this
     *   app never resolves a `File` for the system `ExternalStorageProvider` or a third-party
     *   provider, so [filesystemType][VolumeInfo.filesystemType] is always null there).
     */
    fun resolve(context: Context, destinationTreeUri: Uri, destinationPath: File?): VolumeInfo {
        val freeBytes = destinationPath?.let(::statFsAvailableBytes)
            ?: queryRootAvailableBytes(context, destinationTreeUri)
        val filesystemType = destinationPath?.let(::mountFilesystemType)
        val caseInsensitive = (filesystemType != null && filesystemType in CASE_INSENSITIVE_FILESYSTEMS) ||
            (destinationPath != null && isUnderSharedStorage(destinationPath))
        return VolumeInfo(filesystemType, freeBytes, caseInsensitive)
    }

    private fun statFsAvailableBytes(path: File): Long? =
        runCatching { StatFs(path.absolutePath).availableBytes }.getOrNull()

    internal fun isUnderSharedStorage(path: File): Boolean {
        val canonical = runCatching { path.canonicalPath }.getOrNull() ?: return false
        return canonical == SHARED_STORAGE_PREFIX || canonical.startsWith("$SHARED_STORAGE_PREFIX${File.separatorChar}")
    }

    internal fun mountFilesystemType(path: File): String? {
        val canonical = runCatching { path.canonicalPath }.getOrNull() ?: return null
        val mountsText = runCatching { File(MOUNTS_FILE).readText() }.getOrNull() ?: return null
        return filesystemTypeForPath(canonical, mountsText)
    }

    private const val MOUNTS_FILE = "/proc/self/mounts"
    private const val SHARED_STORAGE_PREFIX = "/storage/emulated"
    private val CASE_INSENSITIVE_FILESYSTEMS = setOf("vfat", "exfat")
}

/** One `/proc/self/mounts` line's mount point and filesystem type. */
internal data class MountEntry(val mountPoint: String, val filesystemType: String)

/**
 * Parses `/proc/self/mounts`-shaped text: `device mountPoint filesystemType options dump pass`,
 * space-separated, one mount per line. A malformed line (fewer than 3 fields) is skipped rather
 * than failing the whole parse -- this reads real, unversioned kernel output, not a file this app
 * controls the shape of.
 */
internal fun parseMounts(mountsText: String): List<MountEntry> =
    mountsText.lineSequence().mapNotNull { line ->
        val fields = line.split(' ')
        if (fields.size < 3) return@mapNotNull null
        MountEntry(mountPoint = unescapeMountField(fields[1]), filesystemType = fields[2])
    }.toList()

/** `/proc/mounts` escapes space, tab, newline and backslash inside a field as octal
 * `\040`/`\011`/`\012`/`\134`, the same convention `/etc/fstab` and `/proc/mounts` have always
 * used -- a mount point containing a space would otherwise be unparsable by field-splitting. */
private fun unescapeMountField(field: String): String =
    field.replace("\\040", " ").replace("\\011", "\t").replace("\\012", "\n").replace("\\134", "\\")

/**
 * The filesystem type of whichever entry in [mountsText] actually contains [canonicalPath] --
 * the longest matching mount point, exactly how the kernel itself resolves one mount nested
 * inside another (a removable card mounted under `/storage/XXXX-XXXX` sits inside, and takes
 * precedence over, the root mount `/`). Null if nothing in [mountsText] contains [canonicalPath]
 * at all.
 */
internal fun filesystemTypeForPath(canonicalPath: String, mountsText: String): String? =
    parseMounts(mountsText)
        .filter { entry ->
            canonicalPath == entry.mountPoint ||
                canonicalPath.startsWith(entry.mountPoint.trimEnd('/') + "/")
        }
        .maxByOrNull { it.mountPoint.length }
        ?.filesystemType

/**
 * [DocumentsContract.Root.COLUMN_AVAILABLE_BYTES] for the root [treeUri] belongs to, queried over
 * [android.content.ContentResolver] -- works for any provider, including one this app has no
 * local [File] access to, which is exactly the case [VolumeInfoResolver.resolve] falls back to
 * this for. Shared with [io.github.mbaliga.fylz.data.ArchiveService], which needs the identical
 * "how much room is left at this destination" answer before extracting a zip.
 */
fun queryRootAvailableBytes(context: Context, treeUri: Uri): Long? = runCatching {
    val authority = treeUri.authority ?: return@runCatching null
    val documentId = DocumentsContract.getTreeDocumentId(treeUri)
    val expectedRootId = documentId.substringBefore(':')
    val rootsUri = DocumentsContract.buildRootsUri(authority)
    val projection = arrayOf(
        DocumentsContract.Root.COLUMN_ROOT_ID,
        DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
    )
    context.contentResolver.query(rootsUri, projection, null, null, null)?.use { cursor ->
        val rootIndex = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_ROOT_ID)
        val bytesIndex = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES)
        while (cursor.moveToNext()) {
            if (rootIndex < 0 || bytesIndex < 0 || cursor.isNull(bytesIndex)) continue
            if (cursor.getString(rootIndex) == expectedRootId) {
                return@use cursor.getLong(bytesIndex).takeIf { it >= 0L }
            }
        }
        null
    }
}.getOrNull()
