package io.github.mbaliga.fylz.storage

import android.net.Uri

/** Broad shape of a launch-surface entry, used for grouping and iconography. */
enum class StorageRootKind {
    /** Primary/internal shared storage. */
    INTERNAL,

    /** SD card, USB OTG or any other removable volume. */
    REMOVABLE,

    /** A well-known directory such as Downloads or DCIM. */
    STANDARD_DIRECTORY,

    /** A document provider root reported by the platform (SAF). */
    PROVIDER_ROOT,

    /** A one-tap `ACTION_OPEN_DOCUMENT_TREE` shortcut, not yet a real location. */
    PICKER_SHORTCUT,

    /** A configured network location (WebDAV/SFTP/SMB/S3). */
    REMOTE,
}

/**
 * One entry on the launch surface.
 *
 * [treeUri] and [documentUri] are both `content://` URIs that the existing SAF-shaped code paths
 * accept. For picker shortcuts both are null and [initialUri] carries the
 * `DocumentsContract.EXTRA_INITIAL_URI` hint instead.
 */
data class StorageRoot(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val kind: StorageRootKind,
    val treeUri: Uri? = null,
    val documentUri: Uri? = null,
    val initialUri: Uri? = null,
    val availableBytes: Long? = null,
    val totalBytes: Long? = null,
    val readOnly: Boolean = false,
) {
    /** True when tapping this entry can open a tab directly, with no picker round-trip. */
    val opensDirectly: Boolean get() = treeUri != null && documentUri != null

    /**
     * True when this root lives on the device's primary shared volume — the region
     * `MANAGE_EXTERNAL_STORAGE` covers.
     *
     * Used to suppress duplicate home-surface rows. With full access the File backend already
     * serves everything here, so a SAF entry for the same place is a second row for one folder
     * rather than a second way to reach it. Roots that are NOT on the shared volume (cloud
     * providers, USB, third-party document providers) always stay, because SAF is the only
     * route to those however broad the file permission is.
     *
     * Derived from the tree URI rather than a stored flag, so it stays correct for roots built
     * by any provider.
     */
    val isOnSharedVolume: Boolean
        get() {
            val uri = treeUri ?: return false
            if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return false
            // ExternalStorageProvider document ids look like "primary:Pictures". "primary" is
            // the shared volume; anything else (a UUID) is a removable card.
            val documentId = uri.lastPathSegment ?: return false
            return documentId.substringBefore(':') == PRIMARY_VOLUME_ID
        }

    private companion object {
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        const val PRIMARY_VOLUME_ID = "primary"
    }
}

/** A titled group of roots, so the home surface can render sections in a stable order. */
data class StorageRootGroup(
    val title: String,
    val roots: List<StorageRoot>,
)
