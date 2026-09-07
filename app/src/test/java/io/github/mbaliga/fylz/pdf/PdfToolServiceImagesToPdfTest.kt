package io.github.mbaliga.fylz.pdf

import android.content.Context
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
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
 * [PdfToolService.imagesToPdf]'s input validation only -- NOT a round-trip through a real PDF.
 *
 * `android.graphics.pdf.PdfDocument` carries no Robolectric shadow (confirmed absent from
 * shadows-framework 4.16.1: no `ShadowPdfDocument` anywhere in the jar). Its constructor calls the
 * native `nativeCreateDocument()`, which Robolectric's default native-method handling stubs to
 * `0L` with nothing backing it -- `PdfDocument` itself then reads that as "already closed"
 * (`mNativeDocument == 0` is its own closed sentinel), so `startPage()` throws
 * `IllegalStateException: document is closed!` on the very first page, before any of this
 * method's own logic runs. This is a genuine gap in what this test suite can verify, not a bug:
 * the same class of "cannot confirm without a real device" limit as `PdfRenderer` (already used,
 * untested, elsewhere in this class) and native-binary execution generally. What IS verified here
 * is everything upstream of constructing a `PdfDocument` -- the require() checks this method
 * actually owns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfToolServiceImagesToPdfTest {

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

    private fun createFile(parent: Uri, name: String, mimeType: String): Uri = requireNotNull(
        DocumentsContract.createDocument(context.contentResolver, parent, mimeType, name),
    )

    @Test
    fun `refuses an empty image list`() {
        val service = PdfToolService(context)
        val output = createFile(rootDocumentUri(), "out.pdf", "application/pdf")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.imagesToPdf(emptyList(), output) }
        }
    }

    @Test
    fun `refuses more images than the page safety limit`() {
        // The over-limit check runs on the list itself, before any URI is opened -- these never
        // need to resolve to real files.
        val service = PdfToolService(context)
        val output = createFile(rootDocumentUri(), "out.pdf", "application/pdf")
        val tooMany = List(PdfToolService.MAX_PAGES + 1) { Uri.parse("content://fake/$it") }

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.imagesToPdf(tooMany, output) }
        }
    }
}
