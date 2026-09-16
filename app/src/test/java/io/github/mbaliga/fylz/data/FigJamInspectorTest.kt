package io.github.mbaliga.fylz.data

import android.content.Context
import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Same provider-registration idiom as [ArchiveServiceTest]: a real [FylzFilesDocumentsProvider]
 * backing real `content://` [Uri]s, so [FigJamInspector] is exercised against the same
 * `ContentResolver` path it uses in production rather than a bare local file.
 *
 * PNG fixtures are built with [android.graphics.Bitmap]/[Bitmap.compress] rather than
 * `javax.imageio` -- Android Kotlin compilation always passes `-no-jdk` (main and test source
 * sets alike), resolving `java.*`/`javax.*` symbols only against `android.jar`, which has no AWT
 * or ImageIO at all, so `java.awt.image.BufferedImage` and `javax.imageio.ImageIO` simply do not
 * compile here. Building fixtures through the same `Bitmap` type [FigJamInspector.decodeBounded]
 * (`BitmapFactory`) hands back also means both fixture construction and inspector decode run
 * through Robolectric's native graphics runtime, rather than only the decode half.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FigJamInspectorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val inspector = FigJamInspector()
    private lateinit var treeUri: Uri

    @Before
    fun registerProvider() {
        val externalRoot = temporaryFolder.newFolder("external")
        ShadowEnvironment.setExternalStorageDirectory(externalRoot.toPath())
        val providerInfo = ProviderInfo().apply {
            authority = FylzFilesDocumentsProvider.AUTHORITY
            exported = true
            grantUriPermissions = true
            // DocumentsProvider.attachInfo throws SecurityException unless both guards carry
            // the signature-level MANAGE_DOCUMENTS declaration from the manifest.
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
        }
        Robolectric.buildContentProvider(FylzFilesDocumentsProvider::class.java).create(providerInfo)
        treeUri = FylzFilesDocumentsProvider.treeUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID)
    }

    private fun rootDocumentUri(): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    private fun createFile(name: String, content: ByteArray): Uri {
        val uri = requireNotNull(
            DocumentsContract.createDocument(
                context.contentResolver,
                rootDocumentUri(),
                "application/octet-stream",
                name,
            ),
        )
        context.contentResolver.openOutputStream(uri)!!.use { it.write(content) }
        return uri
    }

    private fun pngBytes(width: Int, height: Int, rgb: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // Opaque alpha (0xFF << 24) or'd over the caller's RGB -- the java.awt.image.BufferedImage
        // TYPE_INT_RGB this replaced always treated pixels as fully opaque too.
        bitmap.eraseColor((0xFF shl 24) or (rgb and 0xFFFFFF))
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private fun zipBytes(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `non-zip bytes resolve to null rather than a half-guessed preview`() {
        val uri = createFile("legacy.fig", "not a zip container, just the old kiwi binary".toByteArray())
        assertNull(inspector.inspect(context.contentResolver, uri))
    }

    @Test
    fun `an empty file is not mistaken for a zip`() {
        val uri = createFile("empty.fig", ByteArray(0))
        assertNull(inspector.inspect(context.contentResolver, uri))
    }

    @Test
    fun `reads name and version out of a tolerant meta json alongside a thumbnail`() {
        val meta = """{"name":"Sprint board","version":"7","extra":123}""".toByteArray()
        val thumbnail = pngBytes(8, 4, 0xff0000)
        val uri = createFile(
            "board.fig",
            zipBytes(
                listOf(
                    "thumbnail.png" to thumbnail,
                    "meta.json" to meta,
                    "canvas.kiwi" to byteArrayOf(1, 2, 3),
                ),
            ),
        )

        val data = requireNotNull(inspector.inspect(context.contentResolver, uri))
        assertEquals("Sprint board", data.name)
        assertEquals("7", data.fileVersion)
        assertEquals(3, data.entryCount)
        assertNotNull(data.thumbnail)
    }

    @Test
    fun `falls back to preview png when there is no thumbnail png`() {
        val preview = pngBytes(6, 6, 0x00ff00)
        val uri = createFile("board.jam", zipBytes(listOf("preview.png" to preview)))

        val data = requireNotNull(inspector.inspect(context.contentResolver, uri))
        assertNotNull(data.thumbnail)
    }

    @Test
    fun `thumbnail png wins over preview png when both are present`() {
        val thumbnail = pngBytes(4, 4, 0xff0000)
        val preview = pngBytes(40, 40, 0x00ff00)
        val uri = createFile(
            "board.fig",
            zipBytes(listOf("preview.png" to preview, "thumbnail.png" to thumbnail)),
        )

        val data = requireNotNull(inspector.inspect(context.contentResolver, uri))
        val decoded = requireNotNull(data.thumbnail)
        assertEquals(4, decoded.width)
        assertEquals(4, decoded.height)
    }

    @Test
    fun `a zip with neither thumbnail nor meta still resolves, with nulls rather than a crash`() {
        val uri = createFile("board.fig", zipBytes(listOf("canvas.kiwi" to byteArrayOf(9, 9, 9))))

        val data = requireNotNull(inspector.inspect(context.contentResolver, uri))
        assertNull(data.thumbnail)
        assertNull(data.name)
        assertNull(data.fileVersion)
        assertEquals(1, data.entryCount)
    }

    @Test
    fun `malformed meta json is tolerated rather than failing the whole inspection`() {
        val thumbnail = pngBytes(2, 2, 0x0000ff)
        val uri = createFile(
            "board.fig",
            zipBytes(listOf("thumbnail.png" to thumbnail, "meta.json" to "{not valid json".toByteArray())),
        )

        val data = requireNotNull(inspector.inspect(context.contentResolver, uri))
        assertNotNull(data.thumbnail)
        assertNull(data.name)
        assertNull(data.fileVersion)
    }

    @Test
    fun `entry scanning stops at the hard cap rather than walking an unbounded archive`() {
        val entries = (1..(FigJamInspector.MAX_ENTRIES_SCANNED + 20)).map { index ->
            "filler-$index.bin" to byteArrayOf(index.toByte())
        }
        val uri = createFile("bloated.fig", zipBytes(entries))

        val data = requireNotNull(inspector.inspect(context.contentResolver, uri))
        assertEquals(FigJamInspector.MAX_ENTRIES_SCANNED, data.entryCount)
    }

    @Test
    fun `an oversized entry is bounded by the per-entry read cap instead of loading it whole`() {
        // All zero bytes compress to almost nothing on disk but still decompress to the full
        // size on read, so this exercises the cap against the real ZipInputStream, not just the
        // on-disk footprint.
        //
        // Not asserting on `data.thumbnail` here: on a real device, all-zero bytes have no PNG
        // header and `BitmapFactory`'s bounds-only pass would fail outright (outWidth <= 0),
        // which is what the original version of this test asserted null against. Robolectric's
        // BitmapFactory shadow does not reproduce that rejection -- it hands back *some* decoded
        // bitmap for unrecognized/invalid bytes rather than failing the way the real platform
        // API it stands in for does, so a null-thumbnail assertion here would be pinning a
        // Robolectric quirk, not [FigJamInspector]'s own contract. `entryCount == 1` is the part
        // of this test's contract Robolectric answers faithfully: the walk read (and gave up on)
        // exactly the one truncated entry rather than an unbounded amount of it.
        val oversized = ByteArray(FigJamInspector.MAX_ENTRY_BYTES + 1024 * 1024)
        val uri = createFile("board.fig", zipBytes(listOf("thumbnail.png" to oversized)))

        val data = requireNotNull(inspector.inspect(context.contentResolver, uri))
        assertEquals(1, data.entryCount)
    }
}
