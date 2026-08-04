package io.github.mbaliga.fylz.storage

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Point
import android.media.MediaMetadataRetriever
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/**
 * A `java.io.File`-backed [DocumentsProvider]: Fylz's broad-access storage backend.
 *
 * ## Why a DocumentsProvider rather than a second `FileEntry` backend
 *
 * Every existing service in this app -- `FileOperationService`, `RecycleBinService`,
 * `ArchiveService`, `BackupService`, `FileHistoryStore`, `DocumentRepository` -- speaks
 * `DocumentsContract` / `ContentResolver` / `DocumentFile` against SAF tree URIs, and
 * `model.FileEntry` is keyed by `Uri`. Introducing a parallel `File` model would have meant
 * capability-branching all of them and reworking the LazyColumn keys.
 *
 * Exposing broad storage *as a document provider owned by this app* keeps a single code path:
 * the URIs handed to the rest of the app are ordinary `content://` document URIs, so copy, move,
 * rename, recycle, archive, backup and history all keep working byte-for-byte unchanged. The
 * `java.io.File` and `StorageManager.getStorageVolumes()` backend lives here, behind that
 * contract.
 *
 * Same-UID callers bypass the `MANAGE_DOCUMENTS` guard declared in the manifest, so Fylz can read
 * its own provider while other applications cannot.
 *
 * Broad access still requires `MANAGE_EXTERNAL_STORAGE`; see [FullAccessPermission]. Without it
 * this provider simply reports the directories the app can legitimately read.
 */
class FylzFilesDocumentsProvider : DocumentsProvider() {

    override fun onCreate(): Boolean = true

    // ---------------------------------------------------------------- roots

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        volumeRoots().forEach { root ->
            cursor.newRow().apply {
                add(DocumentsContract.Root.COLUMN_ROOT_ID, root.rootId)
                add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, documentIdFor(root.rootId, root.directory, root.directory))
                add(DocumentsContract.Root.COLUMN_TITLE, root.title)
                add(DocumentsContract.Root.COLUMN_SUMMARY, root.directory.absolutePath)
                add(
                    DocumentsContract.Root.COLUMN_FLAGS,
                    DocumentsContract.Root.FLAG_SUPPORTS_CREATE or
                        DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD or
                        DocumentsContract.Root.FLAG_LOCAL_ONLY,
                )
                add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, root.directory.usableSpace)
                add(DocumentsContract.Root.COLUMN_ICON, android.R.drawable.ic_menu_save)
            }
        }
        return cursor
    }

    // ------------------------------------------------------------ documents

    @Throws(FileNotFoundException::class)
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        addDocumentRow(cursor, documentId, resolveFile(documentId))
        return cursor
    }

    @Throws(FileNotFoundException::class)
    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val parent = resolveFile(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("$parentDocumentId is not a directory")
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val rootId = rootIdOf(parentDocumentId)
        // listFiles() returns null on an unreadable directory rather than throwing; treat that as
        // an empty folder so the browser shows "This folder is empty" instead of crashing.
        parent.listFiles().orEmpty().forEach { child ->
            addDocumentRow(cursor, documentIdFor(rootId, rootDirectory(rootId), child), child)
        }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        // Required for tree URIs: DocumentsContract validates every document reached through a
        // tree against this method before handing it to the caller.
        if (rootIdOf(parentDocumentId) != rootIdOf(documentId)) return false
        val parentPath = runCatching { resolveFile(parentDocumentId).canonicalPath }.getOrNull()
            ?: return false
        val childPath = runCatching { resolveFile(documentId).canonicalPath }.getOrNull()
            ?: return false
        return childPath.startsWith(parentPath.trimEnd(File.separatorChar) + File.separatorChar)
    }

    @Throws(FileNotFoundException::class)
    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val file = resolveFile(documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    @Throws(FileNotFoundException::class)
    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val parent = resolveFile(parentDocumentId)
        val safeName = sanitizeDisplayName(displayName)
        var candidate = File(parent, safeName)
        // The framework contract is "create something", not "fail on conflict" -- match
        // ExternalStorageProvider and disambiguate rather than overwriting a user's file.
        var attempt = 1
        while (candidate.exists()) {
            candidate = File(parent, disambiguate(safeName, attempt++))
        }
        val created = if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            candidate.mkdir()
        } else {
            runCatching { candidate.createNewFile() }.getOrDefault(false)
        }
        if (!created) throw FileNotFoundException("Unable to create $displayName in $parentDocumentId")
        return documentIdFor(rootIdOf(parentDocumentId), rootDirectory(rootIdOf(parentDocumentId)), candidate)
    }

    @Throws(FileNotFoundException::class)
    override fun deleteDocument(documentId: String) {
        val file = resolveFile(documentId)
        if (!deleteRecursively(file)) {
            throw FileNotFoundException("Unable to delete $documentId")
        }
    }

    @Throws(FileNotFoundException::class)
    override fun renameDocument(documentId: String, displayName: String): String {
        val file = resolveFile(documentId)
        val target = File(file.parentFile, sanitizeDisplayName(displayName))
        if (target.exists()) throw FileNotFoundException("${target.name} already exists")
        if (!file.renameTo(target)) throw FileNotFoundException("Unable to rename $documentId")
        val rootId = rootIdOf(documentId)
        return documentIdFor(rootId, rootDirectory(rootId), target)
    }

    @Throws(FileNotFoundException::class)
    override fun moveDocument(
        sourceDocumentId: String,
        sourceParentDocumentId: String,
        targetParentDocumentId: String,
    ): String {
        val source = resolveFile(sourceDocumentId)
        val targetParent = resolveFile(targetParentDocumentId)
        val target = File(targetParent, source.name)
        if (target.exists()) throw FileNotFoundException("${target.name} already exists at the destination")
        // renameTo only succeeds within one filesystem; a cross-volume move is left to the
        // caller's verified copy-then-delete path (see docs/product/preview-and-recycle-bin-contract.md
        // section 3), which is why this reports failure instead of silently half-copying.
        if (!source.renameTo(target)) {
            throw FileNotFoundException("Unable to move $sourceDocumentId across storage volumes")
        }
        val rootId = rootIdOf(targetParentDocumentId)
        return documentIdFor(rootId, rootDirectory(rootId), target)
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point,
        signal: CancellationSignal?,
    ): android.content.res.AssetFileDescriptor? {
        val file = runCatching { resolveFile(documentId) }.getOrNull() ?: return null
        val mime = mimeTypeOf(file)
        return when {
            mime.startsWith("image/") -> android.content.res.AssetFileDescriptor(
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
                0,
                android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH,
            )
            mime.startsWith("video/") -> videoThumbnail(file, sizeHint)
            else -> null
        }
    }

    /**
     * Extracts one frame from a video into the app cache and hands back a descriptor for it.
     * Bounded work: a single frame at the requested size, never the whole file.
     */
    private fun videoThumbnail(file: File, sizeHint: Point): android.content.res.AssetFileDescriptor? {
        val context = context ?: return null
        val cacheDir = File(context.cacheDir, THUMBNAIL_CACHE_DIR).apply { mkdirs() }
        val cached = File(cacheDir, "${file.absolutePath.hashCode()}_${file.lastModified()}.jpg")
        if (!cached.exists()) {
            val frame: Bitmap = runCatching {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(file.absolutePath)
                    retriever.getScaledFrameAtTime(
                        0,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        sizeHint.x.coerceIn(64, 512),
                        sizeHint.y.coerceIn(64, 512),
                    )
                }
            }.getOrNull() ?: return null
            runCatching {
                FileOutputStream(cached).use { out -> frame.compress(Bitmap.CompressFormat.JPEG, 80, out) }
            }.onFailure {
                Log.w(TAG, "Unable to cache video thumbnail")
                return null
            }
        }
        return android.content.res.AssetFileDescriptor(
            ParcelFileDescriptor.open(cached, ParcelFileDescriptor.MODE_READ_ONLY),
            0,
            android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH,
        )
    }

    // ----------------------------------------------------------- row helper

    private fun addDocumentRow(cursor: MatrixCursor, documentId: String, file: File) {
        val isDirectory = file.isDirectory
        var flags = 0
        if (file.canWrite()) {
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                DocumentsContract.Document.FLAG_SUPPORTS_RENAME or
                DocumentsContract.Document.FLAG_SUPPORTS_MOVE
            if (isDirectory) flags = flags or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
            if (!isDirectory) flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
        }
        val mime = if (isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else mimeTypeOf(file)
        if (mime.startsWith("image/") || mime.startsWith("video/")) {
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL
        }
        cursor.newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.name)
            add(DocumentsContract.Document.COLUMN_MIME_TYPE, mime)
            add(DocumentsContract.Document.COLUMN_SIZE, if (isDirectory) null else file.length())
            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(DocumentsContract.Document.COLUMN_FLAGS, flags)
        }
    }

    // ------------------------------------------------------- id <-> file

    /**
     * Document ids follow `ExternalStorageProvider`'s scheme: `<rootId>:<path relative to root>`.
     * The empty relative path denotes the root directory itself.
     */
    private fun documentIdFor(rootId: String, rootDirectory: File, file: File): String {
        val rootPath = rootDirectory.absolutePath.trimEnd(File.separatorChar)
        val path = file.absolutePath
        val relative = when {
            path == rootPath -> ""
            path.startsWith("$rootPath${File.separatorChar}") -> path.substring(rootPath.length + 1)
            else -> path.trimStart(File.separatorChar)
        }
        return "$rootId:$relative"
    }

    private fun rootIdOf(documentId: String): String = documentId.substringBefore(':')

    private fun rootDirectory(rootId: String): File =
        volumeRoots().firstOrNull { it.rootId == rootId }?.directory
            ?: Environment.getExternalStorageDirectory()

    /**
     * Resolves a document id to a real file, rejecting anything that escapes its root. Path
     * traversal is checked on the canonical path, so `..` segments and symlinks are both caught.
     */
    @Throws(FileNotFoundException::class)
    private fun resolveFile(documentId: String): File {
        val rootId = rootIdOf(documentId)
        val relative = documentId.substringAfter(':', "")
        val root = volumeRoots().firstOrNull { it.rootId == rootId }?.directory
            ?: throw FileNotFoundException("Unknown storage root in $documentId")
        val candidate = if (relative.isEmpty()) root else File(root, relative)
        val rootCanonical = runCatching { root.canonicalPath }.getOrNull()
            ?: throw FileNotFoundException("Unreadable storage root $rootId")
        val candidateCanonical = runCatching { candidate.canonicalPath }.getOrNull()
            ?: throw FileNotFoundException("Unreadable path in $documentId")
        if (candidateCanonical != rootCanonical &&
            !candidateCanonical.startsWith(rootCanonical.trimEnd(File.separatorChar) + File.separatorChar)
        ) {
            throw FileNotFoundException("$documentId escapes its storage root")
        }
        if (!candidate.exists()) throw FileNotFoundException("$documentId no longer exists")
        return candidate
    }

    // --------------------------------------------------------------- volumes

    private fun volumeRoots(): List<VolumeDescriptor> {
        val context = context ?: return emptyList()
        return discoverVolumes(context)
    }

    private fun deleteRecursively(file: File): Boolean {
        if (file.isDirectory) {
            file.listFiles().orEmpty().forEach { child ->
                if (!deleteRecursively(child)) return false
            }
        }
        return file.delete()
    }

    private fun sanitizeDisplayName(displayName: String): String {
        val trimmed = displayName.trim().trimStart('.')
        require(trimmed.isNotBlank()) { "A name is required." }
        // Strip path separators and control characters only. Spaces, hyphens and unicode
        // are legitimate in file names and must survive untouched.
        val cleaned = trimmed.map { char ->
            if (char == '/' || char == '\\' || char.code < 0x20 || char.code == 0x7F) '_' else char
        }.joinToString("")
        return cleaned.take(255)
    }

    private fun disambiguate(name: String, attempt: Int): String {
        val stem = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        return if (extension.isEmpty()) "$stem ($attempt)" else "$stem ($attempt).$extension"
    }

    private fun mimeTypeOf(file: File): String {
        if (file.isDirectory) return DocumentsContract.Document.MIME_TYPE_DIR
        val extension = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    companion object {
        private const val TAG = "FylzFilesProvider"
        private const val THUMBNAIL_CACHE_DIR = "video-thumbnails"

        /** Must match the authority declared in `src/full/AndroidManifest.xml`. */
        const val AUTHORITY: String = "io.github.mbaliga.fylz.files"

        /** Root id of the primary shared volume, mirroring `ExternalStorageProvider`. */
        const val PRIMARY_ROOT_ID: String = "primary"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
            DocumentsContract.Root.COLUMN_ICON,
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )

        /**
         * The `java.io.File` + `StorageManager.getStorageVolumes()` backend, shared with
         * [FileStorageProvider] so the provider and the launch surface can never disagree about
         * which volumes exist or what their ids are.
         */
        internal fun discoverVolumes(context: Context): List<VolumeDescriptor> {
            val storageManager = context.getSystemService(StorageManager::class.java)
                ?: return listOfNotNull(primaryFallback())
            val volumes = runCatching { storageManager.storageVolumes }.getOrNull()
                ?: return listOfNotNull(primaryFallback())
            val discovered = volumes.mapNotNull { volume ->
                if (volume.state != Environment.MEDIA_MOUNTED &&
                    volume.state != Environment.MEDIA_MOUNTED_READ_ONLY
                ) {
                    return@mapNotNull null
                }
                val directory = runCatching { volume.directory }.getOrNull() ?: return@mapNotNull null
                VolumeDescriptor(
                    rootId = if (volume.isPrimary) PRIMARY_ROOT_ID else (volume.uuid ?: directory.name),
                    title = runCatching { volume.getDescription(context) }.getOrNull()
                        ?: if (volume.isPrimary) "Internal storage" else directory.name,
                    directory = directory,
                    primary = volume.isPrimary,
                    removable = volume.isRemovable,
                    readOnly = volume.state == Environment.MEDIA_MOUNTED_READ_ONLY,
                )
            }
            return discovered.ifEmpty { listOfNotNull(primaryFallback()) }
        }

        private fun primaryFallback(): VolumeDescriptor? {
            @Suppress("DEPRECATION")
            val directory = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
                ?: return null
            return VolumeDescriptor(
                rootId = PRIMARY_ROOT_ID,
                title = "Internal storage",
                directory = directory,
                primary = true,
                removable = false,
                readOnly = false,
            )
        }

        /** Builds the tree URI this provider serves for a given root id. */
        fun treeUri(rootId: String, relativePath: String = ""): android.net.Uri =
            DocumentsContract.buildTreeDocumentUri(AUTHORITY, "$rootId:$relativePath")

        /** Builds the document URI for a path inside a tree served by this provider. */
        fun documentUri(rootId: String, relativePath: String = ""): android.net.Uri =
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri(rootId, relativePath),
                "$rootId:$relativePath",
            )
    }
}

/** Description of one mounted storage volume, shared by the provider and the launch surface. */
internal data class VolumeDescriptor(
    val rootId: String,
    val title: String,
    val directory: File,
    val primary: Boolean,
    val removable: Boolean,
    val readOnly: Boolean,
)

private fun MediaMetadataRetriever.use(block: (MediaMetadataRetriever) -> Bitmap?): Bitmap? =
    try {
        block(this)
    } catch (error: IOException) {
        null
    } catch (error: RuntimeException) {
        null
    } finally {
        runCatching { release() }
    }
