package io.github.mbaliga.fylz.ui.places

import android.net.Uri
import io.github.mbaliga.fylz.storage.StorageRoot
import io.github.mbaliga.fylz.storage.StorageRootGroup
import io.github.mbaliga.fylz.storage.StorageRootKind

/**
 * What the landing screen shows and in what order — the launcher's content, apart from how it is
 * drawn, so the choices are asserted in a test.
 *
 * The principle is a phone's home screen, not a directory listing: the few things people reach
 * for, big and close, and the long tail out of the way. Storage volumes first (they answer
 * "how much room do I have"), then the standard folders as tiles, then what was opened lately,
 * then everything else.
 */
data class LauncherModel(
    /** Internal and removable volumes, drawn as the large cards. */
    val storages: List<StorageRoot>,
    /** The standard folders, in the order people reach for them. */
    val shortcuts: List<StorageRoot>,
    /** Cloud providers, network roots and picker shortcuts: the long tail. */
    val more: List<StorageRoot>,
) {
    companion object {
        /** Display order for the standard folders; anything unlisted follows alphabetically. */
        private val SHORTCUT_ORDER = listOf("Downloads", "Documents", "Pictures", "Camera (DCIM)", "Music", "Movies")

        fun from(groups: List<StorageRootGroup>): LauncherModel {
            val roots = groups.flatMap { it.roots }
            val storages = roots.filter { it.kind == StorageRootKind.INTERNAL || it.kind == StorageRootKind.REMOVABLE }
                // Internal first, so the card people care about leads.
                .sortedBy { if (it.kind == StorageRootKind.INTERNAL) 0 else 1 }
            val shortcuts = roots.filter { it.kind == StorageRootKind.STANDARD_DIRECTORY }
                .distinctBy { it.title }
                .sortedWith(
                    compareBy<StorageRoot> { SHORTCUT_ORDER.indexOf(it.title).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                        .thenBy { it.title },
                )
            val more = roots.filter {
                it.kind == StorageRootKind.PROVIDER_ROOT ||
                    it.kind == StorageRootKind.REMOTE ||
                    it.kind == StorageRootKind.PICKER_SHORTCUT
            }
            return LauncherModel(storages, shortcuts, more)
        }

        /**
         * The volume a folder lives on, for the "free of" line in the top room: the volume's own
         * root if the tab is one, or the device's internal storage for the standard folders on it.
         * Null for anything else (a cloud provider's tree, say) rather than a figure that would
         * describe the wrong disk.
         */
        fun volumeFor(treeUri: Uri?, groups: List<StorageRootGroup>): StorageRoot? {
            if (treeUri == null) return null
            val roots = groups.flatMap { it.roots }
            val own = roots.firstOrNull { it.treeUri == treeUri }
            if (own != null && own.totalBytes != null) return own
            if (own?.kind == StorageRootKind.STANDARD_DIRECTORY) {
                return roots.firstOrNull { it.kind == StorageRootKind.INTERNAL && it.totalBytes != null }
            }
            return null
        }

        /** "Camera (DCIM)" is a path's name, not a word anyone says; the tile says "Camera". */
        fun tileLabel(root: StorageRoot): String = root.title.substringBefore(" (").ifBlank { root.title }

        /** Fraction of a volume in use, 0..1, or null when the volume did not report its size. */
        fun usedFraction(root: StorageRoot): Float? {
            val total = root.totalBytes ?: return null
            val free = root.availableBytes ?: return null
            if (total <= 0L) return null
            return ((total - free).toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }
    }
}
