package io.github.mbaliga.fylz.storage

import android.net.Uri

/**
 * What a concrete [StorageProvider] can actually do.
 *
 * Downstream services (file operations, recycle bin, archives, backup, history) are all written
 * against SAF tree URIs. Rather than teach each of them a second backend, every provider in this
 * app hands back *document* URIs that a `ContentResolver` can service; the capability set here
 * tells the UI what to offer, not how to talk to the backend.
 */
enum class StorageCapability {
    /** Roots can be listed and walked without any user gesture. */
    BROWSE_WITHOUT_PICKER,

    /** Provider exposes whole storage volumes rather than individually granted subtrees. */
    WHOLE_VOLUME,

    /** New files/folders can be created under a root. */
    CREATE,

    /** Items can be renamed in place. */
    RENAME,

    /** Items can be deleted. */
    DELETE,

    /** An app-managed `.fylz-trash` recycle location can be created inside the root. */
    RECYCLE_BIN,

    /** Children can be enumerated recursively for search. */
    RECURSIVE_SEARCH,

    /** File bytes can be opened for content search. */
    CONTENT_SEARCH,
}

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
}

/** A titled group of roots, so the home surface can render sections in a stable order. */
data class StorageRootGroup(
    val title: String,
    val roots: List<StorageRoot>,
)
