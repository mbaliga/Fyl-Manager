package io.github.mbaliga.fylz.ui.places

import android.net.Uri
import io.github.mbaliga.fylz.storage.StorageRoot
import io.github.mbaliga.fylz.storage.StorageRootGroup
import io.github.mbaliga.fylz.storage.StorageRootKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CommonPlacesTest {

    private fun root(title: String, kind: StorageRootKind, direct: Boolean = true) = StorageRoot(
        id = "id:$title",
        title = title,
        kind = kind,
        treeUri = if (direct) Uri.parse("content://t/$title") else null,
        documentUri = if (direct) Uri.parse("content://d/$title") else null,
    )

    private val device = StorageRootGroup("This device", listOf(root("Internal storage", StorageRootKind.INTERNAL)))
    private fun folders(vararg titles: String) =
        StorageRootGroup("Folders", titles.map { root(it, StorageRootKind.STANDARD_DIRECTORY) })

    @Test
    fun `the rail is home, the pinned folders in order, large files, then add`() {
        val places = CommonPlaces.build(listOf(device, folders("Movies", "Documents", "Downloads", "Pictures", "Music")))
        assertEquals(
            listOf("Home", "Downloads", "Documents", "Pictures", "Music", "Movies", "Large files", "Add a location…"),
            places.map { it.label },
        )
    }

    @Test
    fun `camera and unrecognised folders are not pinned`() {
        val places = CommonPlaces.build(listOf(device, folders("Camera (DCIM)", "Podcasts", "Downloads")))
        assertEquals(listOf("Home", "Downloads", "Large files", "Add a location…"), places.map { it.label })
    }

    @Test
    fun `a folder that cannot open directly is not offered`() {
        val picker = StorageRootGroup("Folders", listOf(root("Downloads", StorageRootKind.STANDARD_DIRECTORY, direct = false)))
        val places = CommonPlaces.build(listOf(picker))
        assertFalse(places.any { it.label == "Downloads" })
    }

    @Test
    fun `large files needs an openable internal volume`() {
        val places = CommonPlaces.build(listOf(folders("Downloads")))
        assertFalse(places.any { it.kind == PlaceKind.LARGE_FILES })
        assertNull(CommonPlaces.largeFilesRoot(listOf(folders("Downloads"))))
        assertTrue(CommonPlaces.build(listOf(device)).any { it.kind == PlaceKind.LARGE_FILES })
    }

    @Test
    fun `nothing open focuses home`() {
        val places = CommonPlaces.build(listOf(device, folders("Downloads")))
        assertEquals(CommonPlaces.HOME_ID, CommonPlaces.selectedId(places, null, largeFilesActive = false))
    }

    @Test
    fun `an open pinned folder focuses its own row`() {
        val places = CommonPlaces.build(listOf(device, folders("Downloads")))
        val id = CommonPlaces.selectedId(places, "content://t/Downloads", largeFilesActive = false)
        assertEquals("id:Downloads", id)
    }

    @Test
    fun `the large files search wins over the volume it runs in`() {
        val places = CommonPlaces.build(listOf(device))
        val id = CommonPlaces.selectedId(places, "content://t/Internal storage", largeFilesActive = true)
        assertEquals(CommonPlaces.LARGE_FILES_ID, id)
    }

    @Test
    fun `the large files query parses to a size bound`() {
        val parsed = io.github.mbaliga.fylz.search.SearchQuery.parse(CommonPlaces.LARGE_FILES_QUERY)
        assertEquals(100L * 1024 * 1024, parsed.minSizeBytes)
    }
}
