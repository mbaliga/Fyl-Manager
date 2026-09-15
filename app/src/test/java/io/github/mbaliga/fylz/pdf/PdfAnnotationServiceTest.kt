package io.github.mbaliga.fylz.pdf

import android.content.Context
import android.content.pm.ProviderInfo
import android.graphics.Color
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
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
 * Same real-round-trip footing as [PdfTextExtractorTest]: pdfbox-android's write API is pure
 * Java/Kotlin, so a stroke burned in by [PdfAnnotationService] and read back with PDFBox's own
 * [PDFStreamParser] exercises the real content-stream append, not a stub.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfAnnotationServiceTest {

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
        // See PdfTextExtractorTest's own note: Standard14Fonts/PDPage construction touches the
        // same resource loader PdfAnnotationService's callee (PDPageContentStream) relies on.
        PDFBoxResourceLoader.init(context)
    }

    private fun rootDocumentUri(): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    private fun createFile(name: String): Uri = requireNotNull(
        DocumentsContract.createDocument(context.contentResolver, rootDocumentUri(), "application/pdf", name),
    )

    private fun writeBlankPdf(uri: Uri, width: Float = 200f, height: Float = 200f, protect: Boolean = false) {
        PDDocument().use { document ->
            document.addPage(PDPage(PDRectangle(width, height)))
            if (protect) {
                // An empty user password: the PDF opens with no password prompt (as most
                // permission-restricted PDFs do) but PDDocument.isEncrypted is still true, which is
                // the case annotatePage's own require() guards against. A PDF that instead demands
                // a real password to even open fails inside PDDocument.load itself, before
                // annotatePage's own code runs at all -- a different, earlier failure this service
                // doesn't need to special-case since the caller's runCatching already surfaces it.
                val policy = StandardProtectionPolicy("owner", "", AccessPermission())
                document.protect(policy)
            }
            context.contentResolver.openOutputStream(uri, "w")!!.use { document.save(it) }
        }
    }

    private fun operatorNames(uri: Uri, pageIndex: Int = 0): List<String> {
        context.contentResolver.openInputStream(uri)!!.use { input ->
            PDDocument.load(input).use { document ->
                val parser = PDFStreamParser(document.getPage(pageIndex))
                parser.parse()
                return parser.tokens.filterIsInstance<Operator>().map { it.name }
            }
        }
    }

    private fun oneStroke() = listOf(
        PdfInkStroke(
            points = listOf(Offset(10f, 10f), Offset(50f, 50f), Offset(90f, 10f)),
            colorArgb = Color.RED,
            strokeWidthPx = 4f,
        ),
    )

    @Test
    fun `burns a stroke as real drawing operators into the page's own content stream`() {
        val source = createFile("source.pdf")
        writeBlankPdf(source)
        val destination = createFile("annotated.pdf")

        runBlocking {
            PdfAnnotationService.annotatePage(context, source, 0, Size(200f, 200f), oneStroke(), destination)
        }

        val ops = operatorNames(destination)
        assertTrue("moveTo missing from $ops", "m" in ops)
        assertTrue("lineTo missing from $ops", "l" in ops)
        assertTrue("stroke missing from $ops", "S" in ops)
    }

    @Test
    fun `the source document is left untouched -- annotation always writes a new file`() {
        val source = createFile("source2.pdf")
        writeBlankPdf(source)
        val destination = createFile("annotated2.pdf")

        runBlocking {
            PdfAnnotationService.annotatePage(context, source, 0, Size(200f, 200f), oneStroke(), destination)
        }

        assertTrue(operatorNames(source).isEmpty())
    }

    @Test
    fun `rejects an empty stroke list`() {
        val source = createFile("source3.pdf")
        writeBlankPdf(source)
        val destination = createFile("out3.pdf")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { PdfAnnotationService.annotatePage(context, source, 0, Size(200f, 200f), emptyList(), destination) }
        }
    }

    @Test
    fun `rejects an unmeasured canvas`() {
        val source = createFile("source4.pdf")
        writeBlankPdf(source)
        val destination = createFile("out4.pdf")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { PdfAnnotationService.annotatePage(context, source, 0, Size.Zero, oneStroke(), destination) }
        }
    }

    @Test
    fun `rejects a page index outside the document`() {
        val source = createFile("source5.pdf")
        writeBlankPdf(source)
        val destination = createFile("out5.pdf")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { PdfAnnotationService.annotatePage(context, source, 5, Size(200f, 200f), oneStroke(), destination) }
        }
    }

    @Test
    fun `refuses to annotate an encrypted PDF`() {
        val source = createFile("source6.pdf")
        writeBlankPdf(source, protect = true)
        val destination = createFile("out6.pdf")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { PdfAnnotationService.annotatePage(context, source, 0, Size(200f, 200f), oneStroke(), destination) }
        }
    }

    @Test
    fun `maps canvas coordinates into PDF point space, flipping y`() {
        val source = createFile("source7.pdf")
        writeBlankPdf(source, width = 100f, height = 100f)
        val destination = createFile("out7.pdf")
        val stroke = listOf(PdfInkStroke(listOf(Offset(0f, 0f), Offset(100f, 100f)), Color.BLUE, 2f))

        runBlocking {
            PdfAnnotationService.annotatePage(context, source, 0, Size(100f, 100f), stroke, destination)
        }

        context.contentResolver.openInputStream(destination)!!.use { input ->
            PDDocument.load(input).use { document ->
                val parser = PDFStreamParser(document.getPage(0))
                parser.parse()
                val numbers = parser.tokens.filterIsInstance<com.tom_roush.pdfbox.cos.COSNumber>().map { it.floatValue() }
                // A canvas-space (0,0) top-left point must land at the page's own top-left, i.e.
                // y = mediaBox height (100), not 0 -- confirming the flip actually happened.
                assertTrue("expected a y-coordinate near the page's own height in $numbers", numbers.any { it > 90f })
            }
        }
    }
}
