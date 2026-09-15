package io.github.mbaliga.fylz.pdf

import android.content.Context
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import io.github.mbaliga.fylz.data.ImageExportFormat
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment

/**
 * [PdfToolService.exportPagesAsImages]'s input validation only -- NOT a round-trip through a real
 * page render. `android.graphics.pdf.PdfRenderer` opens a native document the same way
 * `PdfDocument` does (see [PdfToolServiceImagesToPdfTest]'s own KDoc for the confirmed absence of
 * a Robolectric shadow for that family); a real page decode can only be verified on a device. What
 * IS verified here is everything upstream of ever opening a renderer -- the require() checks this
 * method actually owns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfToolServiceExportImagesTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var treeUri: Uri

    @Before
    fun registerProvider() {
        val externalRoot = temporaryFolder.newFolder("external")
        ShadowEnvironment.setExternalStorageDirectory(externalRoot.toPath())
        val providerInfo = ProviderInfo().apply {
            authority = FylzFilesDocumentsProvider.AUTHORITY
            exported = true
            grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
        }
        Robolectric.buildContentProvider(FylzFilesDocumentsProvider::class.java).create(providerInfo)
        treeUri = FylzFilesDocumentsProvider.treeUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID)
    }

    private fun rootDocumentUri(): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    private fun createFolder(parent: Uri, name: String): Uri = requireNotNull(
        DocumentsContract.createDocument(context.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name),
    )

    @Test
    fun `refuses an empty page list`() {
        val service = PdfToolService(context)
        val folder = createFolder(rootDocumentUri(), "out")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.exportPagesAsImages(emptyList(), ImageExportFormat.PNG, folder, "page") }
        }
    }

    @Test
    fun `refuses more pages than the page safety limit`() {
        // The over-limit check runs on the list itself, before any renderer is opened -- these
        // never need to resolve to a real PDF.
        val service = PdfToolService(context)
        val folder = createFolder(rootDocumentUri(), "out")
        val fakeSource = Uri.parse("content://fake/doc.pdf")
        val tooMany = List(PdfToolService.MAX_PAGES + 1) { PdfPageRef(fakeSource, it) }

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.exportPagesAsImages(tooMany, ImageExportFormat.PNG, folder, "page") }
        }
    }
}
