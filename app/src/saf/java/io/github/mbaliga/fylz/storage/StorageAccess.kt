package io.github.mbaliga.fylz.storage

/**
 * `saf` flavor binding of the storage capability adapter.
 *
 * This build declares no storage permission and has no `java.io.File` backend compiled into it at
 * all -- `app/src/full` is simply not part of this variant's source set. It is the Play-safe
 * configuration described in README principle 2 and docs/ARCHITECTURE.md.
 */
object StorageAccess {

    const val HAS_FULL_ACCESS: Boolean = false

    const val FLAVOR_LABEL: String = "Storage Access Framework"

    val primary: StorageProvider by lazy { SafStorageProvider() }

    val secondary: List<StorageProvider> = emptyList()

    val all: List<StorageProvider> get() = listOf(primary)
}
