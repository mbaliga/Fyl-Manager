package io.github.mbaliga.fylz.data

import android.net.Uri
import io.github.mbaliga.fylz.storage.FaultyDocumentsProvider
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import io.github.mbaliga.fylz.storage.VolumeDescriptor
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P0.7: the editor must never silently corrupt a file it can't faithfully round-trip. [DocumentRepository.readText]
 * decodes strictly and reports exactly why a file isn't editable (too large, not UTF-8); [DocumentRepository.writeText]
 * verifies its own write and rolls back to a cached pre-write copy rather than leaving a file half-changed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DocumentRepositoryTextTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var rootDir: File
    private lateinit var faulty: FaultyDocumentsProvider
    private lateinit var repository: DocumentRepository

    @Before
    fun setUp() {
        rootDir = tempFolder.newFolder("root")
        faulty = FaultyDocumentsProvider.install()
        faulty.volumeOverride = listOf(
            VolumeDescriptor(
                rootId = FylzFilesDocumentsProvider.PRIMARY_ROOT_ID,
                title = "Internal storage",
                directory = rootDir,
                primary = true,
                removable = false,
                readOnly = false,
            ),
        )
        repository = DocumentRepository(RuntimeEnvironment.getApplication())
    }

    private fun documentUri(relativePath: String): Uri =
        FylzFilesDocumentsProvider.documentUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, relativePath)

    @Test
    fun `plain ASCII text decodes cleanly and stays editable`() = runBlocking {
        File(rootDir, "notes.txt").writeText("hello world\n")

        val content = repository.readText(documentUri("notes.txt"))

        assertEquals("hello world\n", content.value)
        assertFalse(content.truncated)
        assertTrue(content.encodingOk)
        assertFalse(content.hasBom)
        assertEquals(LineEnding.LF, content.lineEnding)
        assertTrue(content.editable)
    }

    @Test
    fun `a file over the edit size limit is truncated and not editable`() = runBlocking {
        File(rootDir, "big.txt").writeText("a".repeat(TEXT_EDIT_LIMIT_BYTES + 4_096))

        val content = repository.readText(documentUri("big.txt"))

        assertTrue(content.truncated)
        assertFalse(content.editable)
        // Every byte here is single-byte ASCII, so the read must stop at exactly the byte cap,
        // never spill past it into the editor's in-memory buffer.
        assertEquals(TEXT_EDIT_LIMIT_BYTES, content.value.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `a Latin-1 file fails strict decoding and is not editable`() = runBlocking {
        // 0xE9 alone is "e acute" in Latin-1, but it's a UTF-8 lead byte declaring two
        // continuation bytes that never follow -- malformed input a strict decoder must reject.
        val latin1Bytes = "caf".toByteArray(Charsets.US_ASCII) + byteArrayOf(0xE9.toByte())
        File(rootDir, "latin1.txt").writeBytes(latin1Bytes)

        val content = repository.readText(documentUri("latin1.txt"))

        assertFalse(content.encodingOk)
        assertFalse(content.editable)
        assertFalse(content.truncated)
        // Preview still gets a best-effort decode rather than nothing at all.
        assertTrue(content.value.isNotEmpty())
    }

    @Test
    fun `a CRLF file with a BOM round trips byte identical after edit and save`() = runBlocking {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val original = bom + "line one\r\nline two\r\n".toByteArray(Charsets.UTF_8)
        File(rootDir, "crlf.txt").writeBytes(original)

        val content = repository.readText(documentUri("crlf.txt"))
        assertTrue(content.hasBom)
        assertEquals(LineEnding.CRLF, content.lineEnding)
        assertEquals("line one\r\nline two\r\n", content.value)
        assertTrue(content.editable)

        val result = repository.writeText(documentUri("crlf.txt"), content.value, hasBom = content.hasBom)

        assertEquals(SaveResult.Success, result)
        assertArrayEquals(original, File(rootDir, "crlf.txt").readBytes())
    }

    @Test
    fun `a simulated size mismatch restores the original content and reports failure`() = runBlocking {
        val original = "original content, safe and sound\n"
        File(rootDir, "doc.txt").writeText(original)
        faulty.reportedSizeOverride = 999_999L

        val result = repository.writeText(documentUri("doc.txt"), "this write must not stick", hasBom = false)

        assertTrue(result is SaveResult.Failed)
        assertEquals(original, File(rootDir, "doc.txt").readText())
    }
}
