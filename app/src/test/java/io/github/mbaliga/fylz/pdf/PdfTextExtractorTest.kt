package io.github.mbaliga.fylz.pdf

import android.content.Context
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
 * Unlike [PdfToolServiceExportImagesTest]'s disclosed gap, this IS a real round trip:
 * `com.tom-roush:pdfbox-android` re-implements PDF parsing entirely in Java/Kotlin -- it does not
 * lean on Android's native `PdfRenderer`/`PdfDocument` the way the rest of this app's PDF code
 * does, which is the whole reason it was chosen for text extraction in the first place. So a PDF
 * built with PDFBox's own `PDPageContentStream` and read back with [PdfTextExtractor] exercises
 * real parsing, not a stub.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfTextExtractorTest {

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
        // Standard14Fonts' static font-metrics load (triggered the moment any PDType1Font
        // constant is first touched, by this test's own writePdf helper) goes through this same
        // resource loader PdfTextExtractor itself lazily initializes -- unlike extract(), which
        // only runs after that lazy init, writePdf uses PDFBox's write API directly and needs it
        // done first.
        PDFBoxResourceLoader.init(context)
    }

    private fun rootDocumentUri(): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    private fun createFile(name: String): Uri = requireNotNull(
        DocumentsContract.createDocument(context.contentResolver, rootDocumentUri(), "application/pdf", name),
    )

    private fun writePdf(uri: Uri, vararg lines: String) {
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            if (lines.isNotEmpty()) {
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font.HELVETICA, 12f)
                    stream.newLineAtOffset(50f, 700f)
                    lines.forEach { line ->
                        stream.showText(line)
                        stream.newLineAtOffset(0f, -14f)
                    }
                    stream.endText()
                }
            }
            context.contentResolver.openOutputStream(uri, "w")!!.use { document.save(it) }
        }
    }

    @Test
    fun `extracts real text from a PDF's own text layer`() {
        val uri = createFile("sample.pdf")
        writePdf(uri, "Hello from Fylz", "Second line of text")

        val extracted = PdfTextExtractor.extract(context, uri)

        requireNotNull(extracted)
        assertTrue(extracted.contains("Hello from Fylz"))
        assertTrue(extracted.contains("Second line of text"))
    }

    @Test
    fun `a page with no text yields a null sample, not an empty string`() {
        val uri = createFile("blank.pdf")
        writePdf(uri)

        assertNull(PdfTextExtractor.extract(context, uri))
    }
}
