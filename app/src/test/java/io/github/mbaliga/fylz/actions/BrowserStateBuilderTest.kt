package io.github.mbaliga.fylz.actions

import android.net.Uri
import io.github.mbaliga.fylz.archive.ArchiveDocumentId
import io.github.mbaliga.fylz.browse.SortSpec
import io.github.mbaliga.fylz.model.ClipboardMode
import io.github.mbaliga.fylz.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.FolderLocation
import io.github.mbaliga.fylz.model.FolderTab
import io.github.mbaliga.fylz.model.FylzClipboard
import io.github.mbaliga.fylz.model.PreviewMode
import io.github.mbaliga.fylz.model.ThemeMode
import io.github.mbaliga.fylz.model.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `buildBrowserState` (docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md section 2.8): the assembly that
 * moved out of `FylzV1App.kt`, field for field, plus `locationKind` from the current location.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrowserStateBuilderTest {

    private val treeUri = Uri.parse("content://io.github.mbaliga.fylz.files/tree/primary%3A")
    private val rootUri = Uri.parse("content://io.github.mbaliga.fylz.files/tree/primary%3A/document/primary%3A")
    private val folderUri = Uri.parse("content://io.github.mbaliga.fylz.files/tree/primary%3A/document/primary%3ADownload")
    private val archiveFile = Uri.parse("content://io.github.mbaliga.fylz.files/tree/primary%3A/document/primary%3ADownload%2Fphotos.zip")

    private fun entry(name: String, kind: EntryKind = EntryKind.TEXT) =
        FileEntry(Uri.parse("content://fylz/$name"), name, "text/plain", 1L, 0L, 0, kind)

    private fun inputs(tab: FolderTab?, favourites: List<Uri> = emptyList()) = BrowserStateInputs(
        activeTab = tab,
        activeTabId = tab?.id,
        entries = listOf(entry("a"), entry("b")),
        visibleEntries = listOf(entry("b")),
        selection = listOf(entry("a")),
        selectionOrder = linkedSetOf(entry("a").uri),
        focused = entry("b"),
        clipboard = FylzClipboard(ClipboardMode.CUT, listOf(entry("a"))),
        sortSpec = SortSpec.Default,
        viewMode = ViewMode.GRID,
        previewMode = PreviewMode.FLOATING,
        query = "q",
        searchRecursive = true,
        themeMode = ThemeMode.DARK,
        favouriteUris = favourites,
        legacyBinCount = 2,
        operationsNeedingAttention = 3,
        registryProblemCount = 4,
    )

    @Test
    fun `every field is carried through, with the derived ones computed as FylzV1App did`() {
        val tab = FolderTab(id = "t", treeUri = treeUri, locations = listOf(FolderLocation(rootUri, "Root"), FolderLocation(folderUri, "Download")))
        val state = buildBrowserState(inputs(tab, favourites = listOf(folderUri)))
        assertTrue(state.hasActiveTab)
        assertTrue(state.canNavigateUp)
        assertEquals(listOf("a", "b"), state.entries.map { it.name })
        assertEquals(listOf("b"), state.visibleEntries.map { it.name })
        assertEquals(listOf("a"), state.selection.map { it.name })
        assertEquals(listOf(entry("a").uri), state.selectionOrder)
        assertEquals("b", state.focused?.name)
        assertEquals(ClipboardMode.CUT, state.clipboard?.mode)
        assertEquals(SortSpec.Default, state.sortSpec)
        assertEquals(ViewMode.GRID, state.viewMode)
        assertEquals(PreviewMode.FLOATING, state.previewMode)
        assertEquals("q", state.query)
        assertTrue(state.searchRecursive)
        assertEquals(ThemeMode.DARK, state.themeMode)
        assertTrue("the current folder is a favourite", state.currentFolderIsFavourite)
        assertEquals(2, state.legacyBinCount)
        assertEquals(3, state.operationsNeedingAttention)
        assertEquals("t", state.activeTabId)
        assertEquals(4, state.registryProblemCount)
        assertEquals(LocationKind.FOLDER, state.locationKind)
        assertEquals(1, state.selectionCount)
    }

    @Test
    fun `no tab means no navigation, no favourite, a folder kind`() {
        val state = buildBrowserState(inputs(null, favourites = listOf(folderUri)))
        assertFalse(state.hasActiveTab)
        assertFalse(state.canNavigateUp)
        assertFalse(state.currentFolderIsFavourite)
        assertNull(state.activeTabId)
        assertEquals(LocationKind.FOLDER, state.locationKind)
    }

    @Test
    fun `a favourite elsewhere in the stack does not make the current folder one`() {
        val tab = FolderTab(id = "t", treeUri = treeUri, locations = listOf(FolderLocation(rootUri, "Root"), FolderLocation(folderUri, "Download")))
        assertFalse(buildBrowserState(inputs(tab, favourites = listOf(rootUri))).currentFolderIsFavourite)
        val single = FolderTab(id = "t", treeUri = treeUri, locations = listOf(FolderLocation(rootUri, "Root")))
        val state = buildBrowserState(inputs(single, favourites = listOf(rootUri)))
        assertTrue(state.currentFolderIsFavourite)
        assertFalse(state.canNavigateUp)
    }

    @Test
    fun `an archive location is ARCHIVE, a folder inside it too, and the tree stays the outer one`() {
        val archiveRoot = ArchiveDocumentId.root(archiveFile).toUri()
        val insideArchive = ArchiveDocumentId.root(archiveFile).entry(3, "2024").toUri()
        val tab = FolderTab(
            id = "t",
            treeUri = treeUri,
            locations = listOf(FolderLocation(rootUri, "Root"), FolderLocation(folderUri, "Download"), FolderLocation(archiveRoot, "photos.zip"), FolderLocation(insideArchive, "2024")),
        )
        val state = buildBrowserState(inputs(tab))
        assertEquals(LocationKind.ARCHIVE, state.locationKind)
        assertTrue(state.canNavigateUp)
        assertEquals(LocationKind.ARCHIVE, LocationKind.of(archiveRoot))
        assertEquals(LocationKind.FOLDER, LocationKind.of(folderUri))
        assertEquals(LocationKind.FOLDER, LocationKind.of(null))
        // The archive file itself, as a folder's entry, is still a FOLDER location.
        val onTheFile = FolderTab(id = "t", treeUri = treeUri, locations = listOf(FolderLocation(folderUri, "Download")))
        assertEquals(LocationKind.FOLDER, buildBrowserState(inputs(onTheFile)).locationKind)
    }
}
