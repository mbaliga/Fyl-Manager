package io.github.mbaliga.fylz.preview

import android.content.Context
import android.content.pm.ProviderInfo
import android.graphics.Color
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
 * Same real-round-trip footing as `PdfAnnotationServiceTest`: a stroke burned in by
 * [DxfAnnotationService] is read back with [GeometryPreviewParser] -- the same reader this app
 * uses for every DXF preview -- rather than a hand-rolled second parser.
 *
 * [GeometryPreviewParser.parseDxf] itself does not check which SECTION an entity's group codes
 * fall in (it scans every `0`-coded record file-wide), so a round trip through it alone cannot
 * catch a splice landing in the wrong place -- `splices before both ENDSEC and EOF` below checks
 * that directly against the written text instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DxfAnnotationServiceTest {

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

    private fun createFile(name: String, text: String? = null): Uri {
        val uri = requireNotNull(
            DocumentsContract.createDocument(context.contentResolver, rootDocumentUri(), "application/dxf", name),
        )
        if (text != null) {
            context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }
        return uri
    }

    private fun readText(uri: Uri): String =
        context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }.toString(Charsets.UTF_8)

    /** One LINE entity from (0,0) to (100,100): a 100x100 world bounding box. */
    private fun minimalDxf() = """
        0
        SECTION
        2
        ENTITIES
        0
        LINE
        8
        0
        10
        0.0
        20
        0.0
        30
        0.0
        11
        100.0
        21
        100.0
        31
        0.0
        0
        ENDSEC
        0
        EOF
    """.trimIndent()

    private fun oneStroke() = listOf(
        DxfInkStroke(listOf(Offset(10f, 10f), Offset(50f, 50f), Offset(90f, 10f)), Color.RED, 4f),
    )

    @Test
    fun `burns a stroke as a real POLYLINE that GeometryPreviewParser reads back`() {
        val fixture = minimalDxf()
        val source = createFile("source.dxf", fixture)
        val destination = createFile("out.dxf")

        runBlocking {
            DxfAnnotationService.annotateDrawing(context, source, "source.dxf", Size(100f, 100f), oneStroke(), destination)
        }

        val before = GeometryPreviewParser.parse("source.dxf", fixture.toByteArray(Charsets.UTF_8))
        val after = GeometryPreviewParser.parse("out.dxf", readText(destination).toByteArray(Charsets.UTF_8))
        assertEquals(before.sourcePrimitiveCount + 1, after.sourcePrimitiveCount)
        assertTrue(after.vertices.size > before.vertices.size)
        assertTrue(after.edges.size > before.edges.size)
    }

    @Test
    fun `splices before both ENDSEC and EOF`() {
        val source = createFile("source0.dxf", minimalDxf())
        val destination = createFile("out0.dxf")

        runBlocking {
            DxfAnnotationService.annotateDrawing(context, source, "source0.dxf", Size(100f, 100f), oneStroke(), destination)
        }

        val output = readText(destination)
        val polylineAt = output.indexOf("POLYLINE")
        assertTrue(polylineAt >= 0)
        assertTrue(polylineAt < output.indexOf("ENDSEC"))
        assertTrue(polylineAt < output.lastIndexOf("EOF"))
    }

    @Test
    fun `the source drawing is left untouched -- annotation always writes a new file`() {
        val sourceText = minimalDxf()
        val source = createFile("source2.dxf", sourceText)
        val destination = createFile("out2.dxf")

        runBlocking {
            DxfAnnotationService.annotateDrawing(context, source, "source2.dxf", Size(100f, 100f), oneStroke(), destination)
        }

        assertEquals(sourceText, readText(source))
    }

    @Test
    fun `rejects an empty stroke list`() {
        val source = createFile("source3.dxf", minimalDxf())
        val destination = createFile("out3.dxf")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                DxfAnnotationService.annotateDrawing(context, source, "source3.dxf", Size(100f, 100f), emptyList(), destination)
            }
        }
    }

    @Test
    fun `rejects an unmeasured canvas`() {
        val source = createFile("source4.dxf", minimalDxf())
        val destination = createFile("out4.dxf")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                DxfAnnotationService.annotateDrawing(context, source, "source4.dxf", Size.Zero, oneStroke(), destination)
            }
        }
    }

    @Test
    fun `refuses a dxf with no ENTITIES section`() {
        // Real geometry (so GeometryPreviewParser.parse itself succeeds -- parseDxf reads any
        // group-0 entity record regardless of which section, if any, it nominally sits in), but
        // with no "0/SECTION" + "2/ENTITIES" wrapper at all for entitiesSectionEnd to find.
        val noEntitiesSection = """
            0
            LINE
            8
            0
            10
            0.0
            20
            0.0
            30
            0.0
            11
            100.0
            21
            100.0
            31
            0.0
            0
            EOF
        """.trimIndent()
        val source = createFile("source5.dxf", noEntitiesSection)
        val destination = createFile("out5.dxf")

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                DxfAnnotationService.annotateDrawing(context, source, "source5.dxf", Size(100f, 100f), oneStroke(), destination)
            }
        }
        assertTrue(failure.message.orEmpty().contains("ENTITIES"))
    }

    @Test
    fun `maps canvas coordinates into DXF world space, flipping y`() {
        val source = createFile("source6.dxf", minimalDxf())
        val destination = createFile("out6.dxf")
        val stroke = listOf(DxfInkStroke(listOf(Offset(0f, 0f), Offset(100f, 100f)), Color.BLUE, 2f))

        runBlocking {
            DxfAnnotationService.annotateDrawing(context, source, "source6.dxf", Size(100f, 100f), stroke, destination)
        }

        // A canvas-space (0,0) top-left point must land near the drawing's own world-space top
        // (y close to 100, the bounding box's own max), not 0 -- confirming the flip happened.
        val output = readText(destination)
        val yValues = Regex("""(?m)^\s*20\s*\n\s*([0-9.]+)""").findAll(output).map { it.groupValues[1].toFloat() }.toList()
        assertTrue("expected a y near 100 among $yValues", yValues.any { it > 90f })
    }
}
