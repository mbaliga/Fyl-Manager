package io.github.mbaliga.fylz.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldersAndLaunchTest {
    /** The values of Environment.DIRECTORY_*, in the storage home's order. */
    private val standard = listOf("Download", "DCIM", "Documents", "Pictures", "Movies", "Music")

    @Test
    fun `with access only existing folders are offered, and nothing inside them is counted`() {
        val model = FoldersWidgetModel.from(standard, existing = setOf("Download", "DCIM", "Music"), opensDirectly = true)
        assertEquals(listOf(FolderPad("Download"), FolderPad("DCIM"), FolderPad("Music")), model.pads)
        assertTrue(model.opensDirectly)
        assertFalse(model.needsAccess)
    }

    @Test
    fun `without access no pad is offered, because none could open its folder`() {
        val model = FoldersWidgetModel.from(standard, existing = standard.toSet(), opensDirectly = false)
        assertTrue(model.needsAccess)
        assertTrue(model.pads.isEmpty())
        WidgetSize.entries.forEach { assertTrue(it.name, model.padsFor(it).isEmpty()) }
    }

    @Test
    fun `pads per size collapse to one target below the template minimum`() {
        val model = FoldersWidgetModel.from(standard, existing = standard.toSet(), opensDirectly = true)
        assertEquals(listOf("Download"), model.padsFor(WidgetSize.COMPACT).map { it.directory })
        assertEquals(2, model.padsFor(WidgetSize.SMALL).size)
        assertEquals(4, model.padsFor(WidgetSize.MEDIUM).size)
        assertEquals(6, model.padsFor(WidgetSize.LARGE).size)
    }

    private fun parse(destination: String?, folder: String? = null, volume: String? = null) =
        WidgetLaunch.parse(destination, folder, volume, allowedFolders = standard.toSet(), mountedVolumes = setOf("primary", "1234-ABCD"))

    @Test
    fun `every destination survives a round trip through its extras`() {
        listOf(
            WidgetDestination.Home,
            WidgetDestination.Recovery,
            WidgetDestination.RecycleBin,
            WidgetDestination.Folder("DCIM"),
            WidgetDestination.Volume("1234-ABCD"),
        ).forEach { destination ->
            val extras = WidgetLaunch.extras(destination)
            assertEquals(
                destination,
                parse(extras[WidgetLaunch.EXTRA_DESTINATION], extras[WidgetLaunch.EXTRA_FOLDER], extras[WidgetLaunch.EXTRA_VOLUME]),
            )
        }
    }

    @Test
    fun `untrusted extras that name anything outside the allowed set are ignored`() {
        assertNull(parse(null))
        assertNull(parse("settings"))
        assertNull(parse(WidgetLaunch.FOLDER))
        assertNull(parse(WidgetLaunch.FOLDER, folder = "Android/data"))
        assertNull(parse(WidgetLaunch.FOLDER, folder = "../DCIM"))
        assertNull(parse(WidgetLaunch.FOLDER, folder = "dcim"))
        assertNull(parse(WidgetLaunch.VOLUME))
        assertNull(parse(WidgetLaunch.VOLUME, volume = "5678-EFGH"))
    }
}
