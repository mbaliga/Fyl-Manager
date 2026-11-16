package io.github.mbaliga.fylz.ui.places

import android.net.Uri
import io.github.mbaliga.fylz.storage.StorageRoot
import io.github.mbaliga.fylz.storage.StorageRootGroup
import io.github.mbaliga.fylz.storage.StorageRootKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LauncherModelTest {

    private fun root(title: String, kind: StorageRootKind, total: Long? = null, free: Long? = null) = StorageRoot(
        id = "id:$title", title = title, kind = kind,
        treeUri = Uri.parse("content://t/$title"), documentUri = Uri.parse("content://d/$title"),
        totalBytes = total, availableBytes = free,
    )

    @Test
    fun `volumes lead with internal storage first`() {
        val model = LauncherModel.from(
            listOf(StorageRootGroup("x", listOf(root("SD card", StorageRootKind.REMOVABLE), root("Internal", StorageRootKind.INTERNAL)))),
        )
        assertEquals(listOf("Internal", "SD card"), model.storages.map { it.title })
    }

    @Test
    fun `standard folders follow the order people reach for them`() {
        val titles = listOf("Movies", "Zebra", "Music", "Downloads", "Camera (DCIM)", "Documents", "Pictures", "Alpha")
        val model = LauncherModel.from(listOf(StorageRootGroup("f", titles.map { root(it, StorageRootKind.STANDARD_DIRECTORY) })))
        assertEquals(
            listOf("Downloads", "Documents", "Pictures", "Camera (DCIM)", "Music", "Movies", "Alpha", "Zebra"),
            model.shortcuts.map { it.title },
        )
    }

    @Test
    fun `the same folder reported twice is shown once`() {
        val model = LauncherModel.from(
            listOf(StorageRootGroup("f", listOf(root("Downloads", StorageRootKind.STANDARD_DIRECTORY), root("Downloads", StorageRootKind.STANDARD_DIRECTORY)))),
        )
        assertEquals(1, model.shortcuts.size)
    }

    @Test
    fun `the long tail holds providers, remotes and picker shortcuts`() {
        val model = LauncherModel.from(
            listOf(
                StorageRootGroup(
                    "m",
                    listOf(
                        root("Drive", StorageRootKind.PROVIDER_ROOT),
                        root("NAS", StorageRootKind.REMOTE),
                        root("Pick", StorageRootKind.PICKER_SHORTCUT),
                        root("Internal", StorageRootKind.INTERNAL),
                    ),
                ),
            ),
        )
        assertEquals(listOf("Drive", "NAS", "Pick"), model.more.map { it.title })
        assertEquals(listOf("Internal"), model.storages.map { it.title })
    }

    @Test
    fun `tiles drop the parenthetical`() {
        assertEquals("Camera", LauncherModel.tileLabel(root("Camera (DCIM)", StorageRootKind.STANDARD_DIRECTORY)))
        assertEquals("Downloads", LauncherModel.tileLabel(root("Downloads", StorageRootKind.STANDARD_DIRECTORY)))
    }

    @Test
    fun `used fraction is derived from free and total`() {
        assertEquals(0.75f, LauncherModel.usedFraction(root("a", StorageRootKind.INTERNAL, total = 100, free = 25))!!, 1e-4f)
    }

    @Test
    fun `an unreported or empty volume has no fraction`() {
        assertNull(LauncherModel.usedFraction(root("a", StorageRootKind.INTERNAL)))
        assertNull(LauncherModel.usedFraction(root("a", StorageRootKind.INTERNAL, total = 0, free = 0)))
    }

    @Test
    fun `a volume root reports its own capacity`() {
        val sd = root("SD card", StorageRootKind.REMOVABLE, total = 64, free = 10)
        val groups = listOf(StorageRootGroup("g", listOf(root("Internal", StorageRootKind.INTERNAL, 128, 50), sd)))
        assertEquals("SD card", LauncherModel.volumeFor(sd.treeUri, groups)?.title)
    }

    @Test
    fun `a standard folder reports the internal volume`() {
        val downloads = root("Downloads", StorageRootKind.STANDARD_DIRECTORY)
        val groups = listOf(StorageRootGroup("g", listOf(root("Internal", StorageRootKind.INTERNAL, 128, 50), downloads)))
        assertEquals("Internal", LauncherModel.volumeFor(downloads.treeUri, groups)?.title)
    }

    @Test
    fun `an unknown tree has no volume`() {
        val groups = listOf(StorageRootGroup("g", listOf(root("Internal", StorageRootKind.INTERNAL, 128, 50))))
        assertNull(LauncherModel.volumeFor(Uri.parse("content://cloud/tree"), groups))
        assertNull(LauncherModel.volumeFor(null, groups))
    }

    @Test
    fun `free space above total is clamped`() {
        assertEquals(0f, LauncherModel.usedFraction(root("a", StorageRootKind.INTERNAL, total = 100, free = 500))!!, 1e-4f)
    }
}
