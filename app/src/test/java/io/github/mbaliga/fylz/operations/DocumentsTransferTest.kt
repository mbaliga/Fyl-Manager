package io.github.mbaliga.fylz.operations

import android.net.Uri
import android.provider.DocumentsContract
import io.github.mbaliga.fylz.storage.FylzDocumentsProviderTestBase
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import io.github.mbaliga.fylz.storage.testing.TreeNode
import io.github.mbaliga.fylz.storage.testing.buildTree
import io.github.mbaliga.fylz.storage.testing.diffTrees
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment

/**
 * P1.3 / A5: [DocumentsTransfer] -- the provider-neutral fallback -- tested in isolation, directly
 * instantiated rather than through [TransferEngines] (which would always prefer [LocalFileTransfer]
 * for this same real provider; see [LocalFileTransferTest]'s own note on why). [FylzFilesDocumentsProvider]
 * never sets `FLAG_SUPPORTS_COPY` on any document (P1.3 leaves that fast path to whichever provider
 * genuinely offers it), so its own `copyFile` calls here exercise the 512 KiB stream fallback, not
 * `DocumentsContract.copyDocument`; it does set `FLAG_SUPPORTS_MOVE`, so `moveFile` exercises the
 * real `DocumentsContract.moveDocument` call end to end.
 */
class DocumentsTransferTest : FylzDocumentsProviderTestBase() {

    private lateinit var engine: DocumentsTransfer
    private lateinit var destinationDir: File

    @Before
    fun setUpEngine() {
        engine = DocumentsTransfer(RuntimeEnvironment.getApplication())
        destinationDir = File(rootDir, "destination").apply { mkdirs() }
    }

    private fun node(relativePath: String): DocNode =
        DocNode.load(
            RuntimeEnvironment.getApplication().contentResolver,
            FylzFilesDocumentsProvider.documentUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, relativePath),
        ) ?: error("No document at $relativePath")

    @Test
    fun `supports is unconditionally true -- the universal fallback`() {
        buildTree(rootDir, listOf(TreeNode.FileNode("source.bin", 10)))
        assertTrue(engine.supports(node("source.bin"), node("destination")))
    }

    @Test
    fun `copyFile falls back to a 512 KiB stream copy when the provider has no FLAG_SUPPORTS_COPY`() = runBlocking {
        buildTree(rootDir, listOf(TreeNode.FileNode("source.bin", 600_000)))
        val sourceFile = File(rootDir, "source.bin")

        val progress = mutableListOf<Long>()
        val copied = engine.copyFile(node("source.bin"), node("destination"), "copy.bin") { progress.add(it) }

        assertEquals(emptyList<String>(), diffTrees(sourceFile, File(destinationDir, "copy.bin")))
        assertEquals("copy.bin", copied.name)
        assertEquals(sourceFile.length(), progress.last())
        // A 600,000-byte file at a 512 KiB (524,288-byte) buffer needs at least two reads.
        assertTrue("expected more than one progress report at a 512 KiB buffer size", progress.size >= 2)
    }

    @Test
    fun `moveFile uses the real DocumentsContract moveDocument path and leaves the source gone`() = runBlocking {
        buildTree(rootDir, listOf(TreeNode.FileNode("source.bin", 5_000)))
        val sourceFile = File(rootDir, "source.bin")
        val expectedBytes = sourceFile.readBytes()
        val root = node("")

        val moved = engine.moveFile(
            source = node("source.bin"),
            sourceParent = root,
            destinationDirectory = node("destination"),
            requestedName = "moved.bin",
        )

        assertTrue(moved != null)
        assertFalse(sourceFile.exists())
        assertEquals("moved.bin", moved!!.name)
        assertEquals(expectedBytes.toList(), File(destinationDir, "moved.bin").readBytes().toList())
    }

    @Test
    fun `moveFile refuses without a known source parent`() = runBlocking {
        buildTree(rootDir, listOf(TreeNode.FileNode("source.bin", 10)))

        val moved = engine.moveFile(
            source = node("source.bin"),
            sourceParent = null,
            destinationDirectory = node("destination"),
            requestedName = "moved.bin",
        )

        assertNull(moved)
        assertTrue(File(rootDir, "source.bin").exists())
    }

    @Test
    fun `moveFile and copyFile's provider fast path both refuse across different authorities`() = runBlocking {
        val foreignRoot = fakeNode(Uri.parse("content://some.other.provider/tree/root/document/root"), isDirectory = true)

        assertNull(
            engine.moveFile(
                source = fakeNode(Uri.parse("content://some.other.provider/tree/root/document/a.bin"), flags = DocumentsContract.Document.FLAG_SUPPORTS_MOVE),
                sourceParent = foreignRoot,
                destinationDirectory = node("destination"),
                requestedName = "a.bin",
            ),
        )
    }

    private fun fakeNode(
        uri: Uri,
        name: String = uri.lastPathSegment.orEmpty(),
        flags: Int = 0,
        isDirectory: Boolean = false,
    ) = DocNode(
        uri = uri,
        documentId = DocumentsContract.getDocumentId(uri),
        name = name,
        mimeType = if (isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream",
        size = null,
        lastModified = null,
        flags = flags,
        isDirectory = isDirectory,
    )
}
