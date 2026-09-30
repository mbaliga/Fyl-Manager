package io.github.mbaliga.fylz.ui.components

import android.net.Uri
import io.github.mbaliga.fylz.archive.ArchiveDocumentId
import io.github.mbaliga.fylz.model.FolderLocation
import io.github.mbaliga.fylz.model.FolderTab
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `DestinationChooserSheet` offers every open tab's *current* location as a copy/move target;
 * since M3.3 (docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md section 2.5) a tab whose current location
 * is an archive is left out -- it is not writable. The filter is a pure function so it is testable
 * without a Compose harness.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DestinationChooserSheetTest {

    private val treeUri = Uri.parse("content://io.github.mbaliga.fylz.files/tree/primary%3A")
    private val folder = FolderLocation(Uri.parse("content://io.github.mbaliga.fylz.files/tree/primary%3A/document/primary%3ADownload"), "Download")
    private val archiveFile = Uri.parse("content://io.github.mbaliga.fylz.files/tree/primary%3A/document/primary%3ADownload%2Fphotos.zip")

    @Test
    fun `tabs whose current location is an archive are excluded, others kept in order`() {
        val plain = FolderTab(id = "plain", treeUri = treeUri, locations = listOf(folder))
        val insideArchive = FolderTab(
            id = "archive",
            treeUri = treeUri,
            locations = listOf(folder, FolderLocation(ArchiveDocumentId.root(archiveFile).toUri(), "photos.zip")),
        )
        val deepInsideArchive = FolderTab(
            id = "deep",
            treeUri = treeUri,
            locations = listOf(folder, FolderLocation(ArchiveDocumentId.root(archiveFile).entry(2, "2024").toUri(), "2024")),
        )
        val backOut = FolderTab(id = "back", treeUri = treeUri, locations = listOf(folder))
        assertEquals(
            listOf("plain", "back"),
            destinationTabs(listOf(plain, insideArchive, deepInsideArchive, backOut)).map { it.id },
        )
        assertEquals(emptyList<FolderTab>(), destinationTabs(listOf(insideArchive)))
        assertEquals(emptyList<FolderTab>(), destinationTabs(emptyList()))
    }
}
