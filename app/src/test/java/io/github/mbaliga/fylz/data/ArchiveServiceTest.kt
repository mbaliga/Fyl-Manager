package io.github.mbaliga.fylz.data

import android.content.Context
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
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
 * [ArchiveService.createZip] against the real [FylzFilesDocumentsProvider] -- same registration
 * idiom as [DocumentRepositoryTest]. Covers the `require(source.isFile)` seam's extension to
 * directories: recursive folder walking with path prefixes, the flat top-level namespace holding
 * across folder-name collisions, an explicit entry for a folder that ends up with no children,
 * and the byte limits still applying to a file reached only through recursion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArchiveServiceTest {

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

    private fun createFile(parent: Uri, name: String, content: ByteArray = ByteArray(0)): Uri {
        val uri = requireNotNull(
            DocumentsContract.createDocument(context.contentResolver, parent, "application/octet-stream", name),
        )
        if (content.isNotEmpty()) {
            context.contentResolver.openOutputStream(uri)!!.use { it.write(content) }
        }
        return uri
    }

    private fun createFolder(parent: Uri, name: String): Uri = requireNotNull(
        DocumentsContract.createDocument(
            context.contentResolver,
            parent,
            DocumentsContract.Document.MIME_TYPE_DIR,
            name,
        ),
    )

    private fun destination(name: String = "out.zip"): Uri = createFile(rootDocumentUri(), name)

    @Test
    fun `still zips plain files flat, unchanged from before folder support`() = runBlocking {
        val service = ArchiveService(context)
        val root = rootDocumentUri()
        val a = createFile(root, "a.txt", "hello".toByteArray())
        val b = createFile(root, "b.txt", "world".toByteArray())
        val out = destination()

        service.createZip(listOf(a, b), out)
        val inspection = service.inspectZip(out)

        assertEquals(setOf("a.txt", "b.txt"), inspection.visibleEntries.map(ArchiveEntryMetadata::name).toSet())
        assertEquals(0, inspection.directoryCount)
    }

    @Test
    fun `zips a folder recursively with path prefixes and an explicit empty directory entry`() = runBlocking {
        val service = ArchiveService(context)
        val root = rootDocumentUri()
        val photos = createFolder(root, "Photos")
        createFile(photos, "a.jpg", byteArrayOf(1, 2, 3))
        createFolder(photos, "Empty")
        val out = destination()

        service.createZip(listOf(photos), out)
        val inspection = service.inspectZip(out)

        val entries = inspection.visibleEntries.associate { it.name to it.directory }
        assertEquals(false, entries["Photos/a.jpg"])
        assertEquals(true, entries["Photos/Empty/"])
        assertEquals(1, inspection.directoryCount)
        assertEquals(1, inspection.fileCount)
    }

    @Test
    fun `preserves nested structure several levels deep`() = runBlocking {
        val service = ArchiveService(context)
        val root = rootDocumentUri()
        val level1 = createFolder(root, "Level1")
        val level2 = createFolder(level1, "Level2")
        val level3 = createFolder(level2, "Level3")
        createFile(level3, "deep.txt", "x".toByteArray())
        val out = destination()

        service.createZip(listOf(level1), out)
        val inspection = service.inspectZip(out)

        assertTrue(inspection.visibleEntries.any { it.name == "Level1/Level2/Level3/deep.txt" })
    }

    @Test
    fun `disambiguates same-named folders from different sources`() = runBlocking {
        val service = ArchiveService(context)
        val root = rootDocumentUri()
        val locationA = createFolder(root, "LocationA")
        val locationB = createFolder(root, "LocationB")
        val photosA = createFolder(locationA, "Photos")
        createFile(photosA, "a.jpg", byteArrayOf(1))
        val photosB = createFolder(locationB, "Photos")
        createFile(photosB, "b.jpg", byteArrayOf(2))
        val out = destination()

        service.createZip(listOf(photosA, photosB), out)
        val inspection = service.inspectZip(out)

        val names = inspection.visibleEntries.map(ArchiveEntryMetadata::name).toSet()
        assertTrue(names.contains("Photos/a.jpg"))
        assertTrue(names.contains("Photos (2)/b.jpg"))
    }

    @Test
    fun `enforces the per file limit for a file nested inside a folder`() {
        val service = ArchiveService(context, extractionLimits = ArchiveExtractionLimits(maxFileBytes = 10))
        val root = rootDocumentUri()
        val folder = createFolder(root, "Folder")
        val nested = createFolder(folder, "Nested")
        createFile(nested, "big.bin", ByteArray(20))
        val out = destination()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.createZip(listOf(folder), out) }
        }
    }

    @Test
    fun `enforces the total limit across files nested inside a folder`() {
        val service = ArchiveService(
            context,
            extractionLimits = ArchiveExtractionLimits(maxFileBytes = 100, maxTotalUncompressedBytes = 15),
        )
        val root = rootDocumentUri()
        val folder = createFolder(root, "Folder")
        createFile(folder, "one.bin", ByteArray(10))
        createFile(folder, "two.bin", ByteArray(10))
        val out = destination()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.createZip(listOf(folder), out) }
        }
    }

    @Test
    fun `rejects folder nesting deeper than the depth cap`() {
        val service = ArchiveService(context)
        val root = rootDocumentUri()
        val top = createFolder(root, "d0")
        var current = top
        repeat(33) { index -> current = createFolder(current, "d${index + 1}") }
        val out = destination()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.createZip(listOf(top), out) }
        }
    }
}
