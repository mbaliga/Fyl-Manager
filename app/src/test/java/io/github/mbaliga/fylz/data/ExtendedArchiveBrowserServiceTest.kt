package io.github.mbaliga.fylz.data

import android.content.Context
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import org.junit.rules.TemporaryFolder

/**
 * Regression coverage for the tar.gz/tar.bz2/tar.xz routing bug: [ExtendedArchiveBrowserService.list]
 * used to derive its extension with a plain `substringAfterLast('.')`, which reduced a compound
 * name like "backup.tar.gz" to the bare "gz" and misrouted it into [ExtendedArchiveBrowserService]'s
 * single-inner-file path instead of walking the tar for a real per-entry listing. Same
 * [FylzFilesDocumentsProvider] + Robolectric harness [ArchiveServiceTest] already uses, so `list()`
 * runs against a real content:// [Uri] rather than a mocked stream.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExtendedArchiveBrowserServiceTest {

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

    private fun createFile(name: String, content: ByteArray): Uri {
        val uri = requireNotNull(
            DocumentsContract.createDocument(context.contentResolver, rootDocumentUri(), "application/octet-stream", name),
        )
        context.contentResolver.openOutputStream(uri)!!.use { it.write(content) }
        return uri
    }

    private fun gzipTarBytes(entries: Map<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        GzipCompressorOutputStream(bytes).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                entries.forEach { (name, content) ->
                    tar.putArchiveEntry(TarArchiveEntry(name).apply { size = content.size.toLong() })
                    tar.write(content)
                    tar.closeArchiveEntry()
                }
            }
        }
        return bytes.toByteArray()
    }

    private fun bzip2TarBytes(entries: Map<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        BZip2CompressorOutputStream(bytes).use { bzip2 ->
            TarArchiveOutputStream(bzip2).use { tar ->
                entries.forEach { (name, content) ->
                    tar.putArchiveEntry(TarArchiveEntry(name).apply { size = content.size.toLong() })
                    tar.write(content)
                    tar.closeArchiveEntry()
                }
            }
        }
        return bytes.toByteArray()
    }

    @Test
    fun `a real tar gz lists its actual entries, not a fabricated single-entry summary`() = runBlocking {
        val service = ExtendedArchiveBrowserService(context)
        val bytes = gzipTarBytes(mapOf("a.txt" to "hello".toByteArray(), "b.txt" to "world".toByteArray()))
        val uri = createFile("project-backup.tar.gz", bytes)

        val listing = service.list(uri, "project-backup.tar.gz")

        assertFalse("a tar.gz's real contents must not collapse to one compressed-stream entry", listing.compressedSingleStream)
        assertEquals(setOf("a.txt", "b.txt"), listing.entries.map { it.name }.toSet())
    }

    @Test
    fun `tar bz2 also lists its actual entries`() = runBlocking {
        val service = ExtendedArchiveBrowserService(context)
        val bytes = bzip2TarBytes(mapOf("only.txt" to "contents".toByteArray()))
        val uri = createFile("archive.tar.bz2", bytes)

        val listing = service.list(uri, "archive.tar.bz2")

        assertFalse(listing.compressedSingleStream)
        assertEquals(listOf("only.txt"), listing.entries.map { it.name })
    }

    @Test
    fun `the plain tgz shorthand still lists its actual entries -- pinning existing behaviour`() = runBlocking {
        val service = ExtendedArchiveBrowserService(context)
        val bytes = gzipTarBytes(mapOf("only.txt" to "contents".toByteArray()))
        val uri = createFile("archive.tgz", bytes)

        val listing = service.list(uri, "archive.tgz")

        assertFalse(listing.compressedSingleStream)
        assertEquals(listOf("only.txt"), listing.entries.map { it.name })
    }

    @Test
    fun `a lone gz file with no tar layer still reports as a single compressed stream`() = runBlocking {
        val service = ExtendedArchiveBrowserService(context)
        val bytes = ByteArrayOutputStream().apply {
            GzipCompressorOutputStream(this).use { it.write("just text, no tar".toByteArray()) }
        }.toByteArray()
        val uri = createFile("notes.txt.gz", bytes)

        val listing = service.list(uri, "notes.txt.gz")

        assertTrue(listing.compressedSingleStream)
        assertEquals(1, listing.entries.size)
    }
}
