package io.github.mbaliga.fylz.storage

import android.content.Context

/**
 * Runtime selection of the storage backend.
 *
 * Fylz takes full filesystem access as its default posture: when the user has granted
 * "All files access", the `java.io.File` + `StorageManager.getStorageVolumes()` backend
 * ([FileStorageProvider]) drives the home surface and every storage volume opens directly, with no
 * picker in the happy path.
 *
 * The Storage Access Framework path ([SafStorageProvider]) is kept as a **secondary** source, not
 * a fallback of last resort:
 *
 * - it is the only way to reach cloud, USB and third-party `DocumentsProvider` roots, which
 *   `MANAGE_EXTERNAL_STORAGE` does not cover at all; and
 * - it is what the app runs on if the user declines the permission, so declining degrades the
 *   experience instead of breaking it.
 *
 * This is a *runtime* decision, re-evaluated on every call, because the permission can be granted
 * or revoked from Settings while the app is alive. It is deliberately not a compile-time split.
 */
object StorageAccess {

    /** The provider that should lead the home surface right now. */
    fun primary(context: Context): StorageProvider {
        val file = fileProvider
        return if (file.isReady(context)) file else safProvider
    }

    /**
     * Every provider that can contribute rows to the home surface right now, primary first.
     *
     * When broad access is granted this is File-then-SAF, so a user sees their volumes *and* their
     * cloud providers on one screen. When it is not, it is SAF alone.
     */
    fun available(context: Context): List<StorageProvider> =
        if (fileProvider.isReady(context)) {
            listOf(fileProvider, safProvider, remoteProvider)
        } else {
            listOf(safProvider, remoteProvider)
        }

    /** True when the broad-access backend is usable. */
    fun hasFullAccess(context: Context): Boolean = fileProvider.isReady(context)

    /** Short description of the access model currently in force, for the home footer. */
    fun accessLabel(context: Context): String =
        if (hasFullAccess(context)) "Full filesystem access" else "Storage Access Framework only"

    /** The broad-access backend, whether or not it is currently permitted. */
    val fileProvider: StorageProvider by lazy { FileStorageProvider() }

    /** The SAF backend. Always usable; needs no system grant. */
    val safProvider: StorageProvider by lazy { SafStorageProvider() }

    /** Saved remote connections, surfaced for visibility only. Always usable; needs no system grant. */
    val remoteProvider: StorageProvider by lazy { RemoteStorageProvider() }
}
