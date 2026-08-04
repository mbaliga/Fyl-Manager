package io.github.mbaliga.fylz.storage

/**
 * `full` flavor binding of the storage capability adapter.
 *
 * The File-backed provider is primary -- it is what makes a fresh launch show real content --
 * and the SAF provider stays available so cloud and third-party document providers are still
 * reachable.
 */
object StorageAccess {

    /** Whether this build has a broad-access backend at all. Mirrors `BuildConfig.FULL_STORAGE_ACCESS`. */
    const val HAS_FULL_ACCESS: Boolean = true

    /** Human-readable name of this build's access model, for the About/home footer. */
    const val FLAVOR_LABEL: String = "Full access"

    /** The provider the home surface leads with. */
    val primary: StorageProvider by lazy { FileStorageProvider() }

    /** Additional providers offered alongside [primary]; may be empty. */
    val secondary: List<StorageProvider> by lazy { listOf(SafStorageProvider()) }

    /** Every provider this build can use, primary first. */
    val all: List<StorageProvider> get() = listOf(primary) + secondary
}
