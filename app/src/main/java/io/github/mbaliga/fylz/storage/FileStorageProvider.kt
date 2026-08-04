package io.github.mbaliga.fylz.storage

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * `java.io.File` + `StorageManager.getStorageVolumes()` launch surface: Fylz's primary storage
 * source whenever "All files access" is granted.
 *
 * Everything it returns opens *directly* -- no picker, no SAF grant -- because the URIs are served
 * by [FylzFilesDocumentsProvider], which this app owns. That is what makes a fresh launch show
 * internal storage, removable volumes and the standard folders straight away instead of
 * "Open a folder to begin".
 */
class FileStorageProvider : StorageProvider {

    override val id: String = ID

    override val capabilities: Set<StorageCapability> = setOf(
        StorageCapability.BROWSE_WITHOUT_PICKER,
        StorageCapability.WHOLE_VOLUME,
        StorageCapability.CREATE,
        StorageCapability.RENAME,
        StorageCapability.DELETE,
        StorageCapability.RECYCLE_BIN,
        StorageCapability.RECURSIVE_SEARCH,
        StorageCapability.CONTENT_SEARCH,
    )

    override fun isReady(context: Context): Boolean = FullAccessPermission.isGranted()

    override fun readinessMessage(context: Context): String? =
        if (isReady(context)) null else FullAccessPermission.RATIONALE

    override fun permissionIntent(context: Context): Intent? =
        if (isReady(context)) null else FullAccessPermission.intent(context)

    override suspend fun rootGroups(context: Context): List<StorageRootGroup> =
        withContext(Dispatchers.IO) {
            val volumes = FylzFilesDocumentsProvider.discoverVolumes(context)
            val primary = volumes.firstOrNull { it.primary }

            buildList {
                volumes.filter { it.primary }.map(::volumeRoot).takeIf { it.isNotEmpty() }?.let {
                    add(StorageRootGroup(GROUP_DEVICE, it))
                }
                volumes.filterNot { it.primary }.map(::volumeRoot).takeIf { it.isNotEmpty() }?.let {
                    add(StorageRootGroup(GROUP_REMOVABLE, it))
                }
                primary?.let { volume ->
                    standardDirectories(volume).takeIf { it.isNotEmpty() }?.let {
                        add(StorageRootGroup(GROUP_FOLDERS, it))
                    }
                }
            }
        }

    private fun volumeRoot(volume: VolumeDescriptor): StorageRoot = StorageRoot(
        id = "file:${volume.rootId}",
        title = volume.title,
        subtitle = volume.directory.absolutePath,
        kind = if (volume.removable) StorageRootKind.REMOVABLE else StorageRootKind.INTERNAL,
        treeUri = FylzFilesDocumentsProvider.treeUri(volume.rootId),
        documentUri = FylzFilesDocumentsProvider.documentUri(volume.rootId),
        availableBytes = runCatching { volume.directory.usableSpace }.getOrNull(),
        totalBytes = runCatching { volume.directory.totalSpace }.getOrNull(),
        readOnly = volume.readOnly,
    )

    /**
     * Standard shared folders on the primary volume. Only directories that actually exist are
     * listed, so a device without a Movies folder does not get a dead row.
     */
    private fun standardDirectories(volume: VolumeDescriptor): List<StorageRoot> =
        SafStorageProvider.STANDARD_DIRECTORIES.mapNotNull { (label, directory) ->
            val target = File(volume.directory, directory)
            if (!target.isDirectory) return@mapNotNull null
            StorageRoot(
                id = "file:${volume.rootId}:$directory",
                title = label,
                subtitle = target.absolutePath,
                kind = StorageRootKind.STANDARD_DIRECTORY,
                treeUri = FylzFilesDocumentsProvider.treeUri(volume.rootId, directory),
                documentUri = FylzFilesDocumentsProvider.documentUri(volume.rootId, directory),
                readOnly = volume.readOnly,
            )
        }

    companion object {
        const val ID: String = "file"

        const val GROUP_DEVICE: String = "This device"
        const val GROUP_REMOVABLE: String = "Removable storage"
        const val GROUP_FOLDERS: String = "Folders"
    }
}
