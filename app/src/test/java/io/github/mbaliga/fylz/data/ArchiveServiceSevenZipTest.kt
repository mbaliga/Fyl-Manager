package io.github.mbaliga.fylz.data

import android.content.Context
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.sevenz.SevenZFile
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
import java.io.File

/**
 * [ArchiveService.createSevenZip] against the real [FylzFilesDocumentsProvider] -- same idiom
 * [ArchiveServiceTest] uses for [ArchiveService.createZip]. Read back through commons-compress'
 * own [SevenZFile], the exact reader [ExtendedArchiveBrowserService] uses to browse a 7z inside
 * the app, rather than trusted from the writer's own claims about itself: a 7z this app cannot
 * then open with its own reader is not a format Fylz actually supports, regardless of what
 * SevenZOutputFile accepted without complaint.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArchiveServiceSevenZipTest {

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
        DocumentsContract.createDocument(context.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name),
    )

    private fun destination(name: String = "out.7z"): Uri = createFile(rootDocumentUri(), name)

    /** Stages the created archive out of the provider so a plain java.io reader can open it. */
    private fun stageLocally(archiveUri: Uri): File {
        val local = temporaryFolder.newFile()
        context.contentResolver.openInputStream(archiveUri)!!.use { input ->
            local.outputStream().use { output -> input.copyTo(output) }
        }
        return local
    }

    @Test
    fun `round-trips plain files`() = runBlocking {
        val service = ArchiveService(context)
        val root = rootDocumentUri()
        val a = createFile(root, "a.txt", "hello".toByteArray())
        val b = createFile(root, "b.txt", "world".toByteArray())
        val out = destination()

        service.createSevenZip(listOf(a, b), out)

        SevenZFile(stageLocally(out)).use { sevenZ ->
            val entries = sevenZ.entries.associateBy { it.name }
            assertEquals(setOf("a.txt", "b.txt"), entries.keys)
            val bytes = sevenZ.getInputStream(entries.getValue("a.txt")).readBytes()
            assertEquals("hello", String(bytes))
        }
    }

    /**
     * `getContentMethods()` on a READ entry is unreliable -- it reflects a per-entry override,
     * not the archive-wide default `setContentCompression` applies, and was null for every entry
     * in this library version even though LZMA2 was genuinely used. Real compression is verified
     * the only way that cannot lie: a highly repetitive payload has to come out smaller than it
     * went in, or SevenZMethod.LZMA2 quietly regressed to STORE with nothing else here to catch it.
     */
    @Test
    fun `LZMA2 compression actually shrinks a compressible payload`() = runBlocking {
        val service = ArchiveService(context)
        val root = rootDocumentUri()
        val payload = "the quick brown fox jumps over the lazy dog ".repeat(4_000).toByteArray()
        val entry = createFile(root, "repetitive.txt", payload)
        val out = destination()

        service.createSevenZip(listOf(entry), out)

        val archiveBytes = stageLocally(out).length()
        assertTrue(
            "archive is $archiveBytes bytes for a ${payload.size}-byte input -- LZMA2 is not compressing",
            archiveBytes < payload.size / 4,
        )
    }

    @Test
    fun `an empty directory survives as its own entry`() = runBlocking {
        val service = ArchiveService(context)
        val root = rootDocumentUri()
        val photos = createFolder(root, "Photos")
        createFile(photos, "a.jpg", byteArrayOf(1, 2, 3))
        createFolder(photos, "Empty")
        val out = destination()

        service.createSevenZip(listOf(photos), out)

        SevenZFile(stageLocally(out)).use { sevenZ ->
            val entries = sevenZ.entries.associateBy { it.name }
            assertEquals(false, entries.getValue("Photos/a.jpg").isDirectory)
            assertTrue(entries.getValue("Photos/Empty").isDirectory)
        }
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

        service.createSevenZip(listOf(level1), out)

        SevenZFile(stageLocally(out)).use { sevenZ ->
            assertTrue(sevenZ.entries.any { it.name == "Level1/Level2/Level3/deep.txt" })
        }
    }

    @Test
    fun `an encrypted 7z opens with the correct password and rejects the wrong one`() {
        val service = ArchiveService(context)
        val root = rootDocumentUri()
        val secret = createFile(root, "secret.txt", "the treasure is real".toByteArray())
        val out = destination()
        val password = "correct-horse".toCharArray()

        runBlocking { service.createSevenZip(listOf(secret), out, password) }

        val local = stageLocally(out)
        SevenZFile(local, "correct-horse".toCharArray()).use { sevenZ ->
            val entry = sevenZ.entries.first()
            assertEquals("the treasure is real", String(sevenZ.getInputStream(entry).readBytes()))
        }
        assertThrows(Exception::class.java) {
            SevenZFile(local, "wrong-guess".toCharArray()).use { sevenZ ->
                sevenZ.getInputStream(sevenZ.entries.first()).readBytes()
            }
        }
    }
}
