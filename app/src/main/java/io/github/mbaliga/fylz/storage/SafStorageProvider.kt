package io.github.mbaliga.fylz.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import io.github.mbaliga.fylz.core.model.ItemCapability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Storage Access Framework source: Fylz's secondary storage backend.
 *
 * It is not merely a fallback. `MANAGE_EXTERNAL_STORAGE` covers shared local volumes and nothing
 * else, so SAF stays the only route to cloud, USB and third-party `DocumentsProvider` roots. It is
 * also what the app runs on entirely if the user declines "All files access".
 *
 * This provider itself holds no storage permission. It can only open subtrees the user has
 * explicitly granted, and everything else it offers is a *shortcut* that opens the system picker
 * pre-seeded with `DocumentsContract.EXTRA_INITIAL_URI`.
 */
class SafStorageProvider : StorageProvider {

    override val id: String = ID

    override val capabilities: Set<ItemCapability> = setOf(
        ItemCapability.LIST,
        ItemCapability.CREATE_FILE,
        ItemCapability.CREATE_DIRECTORY,
        ItemCapability.RENAME,
        ItemCapability.TRASH,
        ItemCapability.RESTORE_TRASH,
        ItemCapability.DELETE_PERMANENT,
        ItemCapability.CONTENT_SEARCH,
    )

    /** SAF needs no system grant; it is always able to render its home surface. */
    override fun isReady(context: Context): Boolean = true

    override fun readinessMessage(context: Context): String? = null

    override fun permissionIntent(context: Context): Intent? = null

    /**
     * SAF's contribution to the home surface.
     *
     * When the File backend is live, SAF contributes **only what File cannot reach**: cloud
     * and third-party document providers, and subtrees the user granted outside the shared
     * volume. Its "add a location" shortcuts and its granted-subtree list are suppressed,
     * because with full access those are the *same folders the File backend already lists as
     * directly openable* — the home screen was showing Internal storage, Downloads, DCIM,
     * Documents, Pictures, Movies and Music twice, the second time behind padlocks inviting a
     * grant the user had already given. Offering to unlock a door that is already open reads
     * as the app not knowing its own state.
     */
    override suspend fun rootGroups(context: Context): List<StorageRootGroup> =
        withContext(Dispatchers.IO) {
            val fileBackendLive = StorageAccess.hasFullAccess(context)
            buildList {
                // Granted subtrees are only worth showing when they add reach. Under full
                // access the File backend already serves everything on the shared volume, so
                // a granted subtree there is a duplicate row, not a second way in.
                grantedRoots(context)
                    .filterNot { fileBackendLive && it.isOnSharedVolume }
                    .takeIf { it.isNotEmpty() }
                    ?.let { add(StorageRootGroup(GROUP_GRANTED, it)) }

                // The grant shortcuts exist to escape the no-permission state. With the
                // permission held they are noise at best and misleading at worst.
                if (!fileBackendLive) {
                    add(StorageRootGroup(GROUP_SUGGESTED, suggestedShortcuts()))
                }

                removableShortcuts(context).takeIf { it.isNotEmpty() }?.let {
                    add(StorageRootGroup(GROUP_REMOVABLE, it))
                }
                // Always kept: MANAGE_EXTERNAL_STORAGE covers local shared storage and nothing
                // else, so SAF remains the only route to cloud, USB and third-party providers.
                providerRoots(context, fileBackendLive).takeIf { it.isNotEmpty() }?.let {
                    add(StorageRootGroup(GROUP_PROVIDERS, it))
                }
            }.let(::dropShadowedProviderRows)
        }

    /**
     * Subtrees the user has already granted. These are the only SAF locations that can be opened
     * without another picker round-trip.
     */
    private fun grantedRoots(context: Context): List<StorageRoot> =
        context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .mapNotNull { permission ->
                val treeUri = permission.uri
                val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
                    .getOrNull() ?: return@mapNotNull null
                val documentUri = runCatching {
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                }.getOrNull() ?: return@mapNotNull null
                StorageRoot(
                    id = "granted:$treeUri",
                    title = displayNameFor(context, documentUri) ?: documentId.substringAfterLast('/'),
                    subtitle = describeAuthority(treeUri.authority),
                    kind = StorageRootKind.PROVIDER_ROOT,
                    treeUri = treeUri,
                    documentUri = documentUri,
                    readOnly = !permission.isWritePermission,
                )
            }

    /**
     * One-tap `ACTION_OPEN_DOCUMENT_TREE` shortcuts. The `EXTRA_INITIAL_URI` values are built
     * from `ExternalStorageProvider`'s documented `primary:<relative path>` document-id scheme, so
     * the picker opens *at* the folder instead of at an arbitrary default.
     */
    private fun suggestedShortcuts(): List<StorageRoot> = buildList {
        add(
            pickerShortcut(
                id = "saf:primary",
                title = "Internal storage",
                subtitle = "Grant Fylz access to the whole shared volume",
                documentId = EXTERNAL_STORAGE_PRIMARY,
                kind = StorageRootKind.INTERNAL,
            ),
        )
        STANDARD_DIRECTORIES.forEach { (label, directory) ->
            add(
                pickerShortcut(
                    id = "saf:$directory",
                    title = label,
                    subtitle = "Grant access to this folder only",
                    documentId = "$EXTERNAL_STORAGE_PRIMARY$directory",
                    kind = StorageRootKind.STANDARD_DIRECTORY,
                ),
            )
        }
    }

    private fun pickerShortcut(
        id: String,
        title: String,
        subtitle: String,
        documentId: String,
        kind: StorageRootKind,
    ) = StorageRoot(
        id = id,
        title = title,
        subtitle = subtitle,
        kind = kind,
        initialUri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, documentId),
    )

    /**
     * Removable volumes. `StorageManager.getStorageVolumes()` is plain metadata and needs no
     * permission; `StorageVolume.createOpenDocumentTreeIntent()` yields a picker seeded to that
     * exact volume, which is the SAF-legal way to reach an SD card.
     */
    private fun removableShortcuts(context: Context): List<StorageRoot> {
        val storageManager = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        return runCatching {
            storageManager.storageVolumes
                .filter { !it.isPrimary && it.state == Environment.MEDIA_MOUNTED }
                .mapNotNull { volume ->
                    // The typed getParcelableExtra overload is API 33; minSdk here is 31, so the
                    // deprecated one-argument form is the only version available across the
                    // supported range.
                    @Suppress("DEPRECATION")
                    val initial = runCatching {
                        volume.createOpenDocumentTreeIntent()
                            .getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI)
                    }.getOrNull()
                    StorageRoot(
                        id = "saf:volume:${volume.uuid ?: volume.hashCode()}",
                        title = volume.getDescription(context) ?: "Removable storage",
                        subtitle = if (volume.isRemovable) "Removable volume" else "Secondary volume",
                        kind = StorageRootKind.REMOVABLE,
                        initialUri = initial,
                    )
                }
        }.getOrDefault(emptyList())
    }

    /**
     * Document providers installed on the device (Drive, Dropbox, USB, network providers...).
     *
     * `queryRoots` on a provider is normally guarded by the signature-level `MANAGE_DOCUMENTS`
     * permission, so a normal app usually cannot read root rows. We therefore *try* the query and
     * fall back to a bare picker shortcut per authority, which always works.
     */
    private fun providerRoots(context: Context, fileBackendLive: Boolean): List<StorageRoot> {
        val intent = Intent(DOCUMENTS_PROVIDER_ACTION)
        val providers = runCatching {
            context.packageManager.queryIntentContentProviders(intent, 0)
        }.getOrDefault(emptyList())

        return providers.mapNotNull { info ->
            val authority = info.providerInfo?.authority ?: return@mapNotNull null
            if (isNoiseAuthority(authority, context.packageName, fileBackendLive)) return@mapNotNull null
            val label = runCatching {
                info.providerInfo.loadLabel(context.packageManager).toString()
            }.getOrNull() ?: authority
            StorageRoot(
                id = "saf:authority:$authority",
                title = label,
                subtitle = "Browse via the system picker",
                kind = StorageRootKind.PROVIDER_ROOT,
                initialUri = runCatching {
                    DocumentsContract.buildRootsUri(authority)
                }.getOrNull(),
            )
        }.distinctBy { it.id }
    }

    private fun displayNameFor(context: Context, documentUri: Uri): String? = runCatching {
        context.contentResolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    private fun describeAuthority(authority: String?): String = when (authority) {
        EXTERNAL_STORAGE_AUTHORITY -> "Device storage"
        DOWNLOADS_AUTHORITY -> "Downloads"
        "com.android.providers.media.documents" -> "Media"
        null -> "Document provider"
        else -> authority
    }

    /**
     * Policy for what "Other providers" is for: authorities that add no reach beyond what the
     * home surface already offers directly. Fylz's own provider is already surfaced as the app
     * itself, not a document source to browse into; MTP host and shell never expose anything a
     * user meaningfully browses; Downloads is a picker-only echo of the folder the File backend
     * already lists directly once full access is live.
     */
    private fun isNoiseAuthority(authority: String, packageName: String, fileBackendLive: Boolean): Boolean =
        authority == EXTERNAL_STORAGE_AUTHORITY ||
            authority.startsWith(packageName) ||
            authority in NOISY_PROVIDER_AUTHORITIES ||
            (fileBackendLive && authority == DOWNLOADS_AUTHORITY)

    /** Titles already reachable with no picker round-trip, across every group built so far. */
    private fun directOpenTitles(groups: List<StorageRootGroup>): Set<String> =
        groups.asSequence()
            .flatMap { it.roots }
            .filter { it.opensDirectly }
            .mapTo(mutableSetOf()) { it.title.lowercase() }

    /**
     * Drops "Other providers" rows that just repeat a title already reachable directly --
     * vendor-shipped "Files"/"Local storage" shims, and a provider "Downloads" row shadowing a
     * folder the user already granted straight access to.
     */
    private fun dropShadowedProviderRows(groups: List<StorageRootGroup>): List<StorageRootGroup> {
        val directTitles = directOpenTitles(groups)
        if (directTitles.isEmpty()) return groups
        return groups.mapNotNull { group ->
            if (group.title != GROUP_PROVIDERS) return@mapNotNull group
            group.roots.filterNot { it.title.lowercase() in directTitles }
                .takeIf { it.isNotEmpty() }
                ?.let { StorageRootGroup(group.title, it) }
        }
    }

    companion object {
        const val ID: String = "saf"

        const val GROUP_GRANTED: String = "Granted folders"
        const val GROUP_SUGGESTED: String = "Add a location"
        const val GROUP_REMOVABLE: String = "Removable storage"
        const val GROUP_PROVIDERS: String = "Other providers"

        const val EXTERNAL_STORAGE_AUTHORITY: String = "com.android.externalstorage.documents"

        /** `ExternalStorageProvider`'s document id for the primary shared volume. */
        const val EXTERNAL_STORAGE_PRIMARY: String = "primary:"

        private const val DOCUMENTS_PROVIDER_ACTION = "android.content.action.DOCUMENTS_PROVIDER"

        private const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"

        /** Never a useful "Other providers" row: neither exposes anything a user can browse. */
        private val NOISY_PROVIDER_AUTHORITIES = setOf(
            "com.android.mtp.documents",
            "com.android.shell.documents",
        )

        /**
         * Well-known shared directories, in the order the home surface shows them. Kept in one
         * place so the SAF and File-backed providers agree on naming.
         */
        val STANDARD_DIRECTORIES: List<Pair<String, String>> = listOf(
            "Downloads" to Environment.DIRECTORY_DOWNLOADS,
            "Camera (DCIM)" to Environment.DIRECTORY_DCIM,
            "Documents" to Environment.DIRECTORY_DOCUMENTS,
            "Pictures" to Environment.DIRECTORY_PICTURES,
            "Movies" to Environment.DIRECTORY_MOVIES,
            "Music" to Environment.DIRECTORY_MUSIC,
        )
    }
}
