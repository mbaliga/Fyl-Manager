package io.github.mbaliga.fylz.operations

import io.github.mbaliga.fylz.storage.FylzDocumentsProviderTestBase
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import io.github.mbaliga.fylz.storage.testing.TreeNode
import io.github.mbaliga.fylz.storage.testing.buildTree
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment

/**
 * P1.4: [sha256Hex] and [verifyChecksum] against the real P0.0-hosted [FylzFilesDocumentsProvider]
 * -- the same harness [LocalFileTransferTest]/[DocumentsTransferTest] use for P1.3, since
 * verification reads both ends the same way those engines already do: through a live [DocNode]'s
 * content [android.net.Uri], not a bare [java.io.File].
 */
class ChecksumVerificationTest : FylzDocumentsProviderTestBase() {

    private fun node(relativePath: String): DocNode =
        DocNode.load(
            RuntimeEnvironment.getApplication().contentResolver,
            FylzFilesDocumentsProvider.documentUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, relativePath),
        ) ?: error("No document at $relativePath")

    /** Computed independently of [sha256Hex] -- via the same JDK primitive, but without going
     * through a [DocNode]/[android.content.ContentResolver] at all -- so these tests pin the
     * reading and hex-formatting behavior, not just restate the implementation. */
    private fun expectedSha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `sha256Hex matches an independently computed digest of the same bytes`() = runBlocking {
        buildTree(rootDir, listOf(TreeNode.FileNode("source.bin", 50_000)))
        val bytes = File(rootDir, "source.bin").readBytes()

        val hash = sha256Hex(RuntimeEnvironment.getApplication().contentResolver, node("source.bin").uri)

        assertEquals(expectedSha256Hex(bytes), hash)
    }

    @Test
    fun `sha256Hex handles an empty file`() = runBlocking {
        check(File(rootDir, "empty.bin").createNewFile())

        val hash = sha256Hex(RuntimeEnvironment.getApplication().contentResolver, node("empty.bin").uri)

        assertEquals(expectedSha256Hex(ByteArray(0)), hash)
    }

    @Test
    fun `verifyChecksum returns the matching hash for byte-identical files`() = runBlocking {
        buildTree(rootDir, listOf(TreeNode.FileNode("source.bin", 10_000)))
        File(rootDir, "source.bin").copyTo(File(rootDir, "copy.bin"))

        val hash = verifyChecksum(
            RuntimeEnvironment.getApplication().contentResolver,
            node("source.bin"),
            node("copy.bin"),
        )

        assertEquals(expectedSha256Hex(File(rootDir, "source.bin").readBytes()), hash)
    }

    @Test
    fun `verifyChecksum throws when the destination differs from the source, and names the source in the message`() {
        buildTree(rootDir, listOf(TreeNode.FileNode("source.bin", 10_000)))
        val corrupted = File(rootDir, "source.bin").readBytes()
        corrupted[0] = corrupted[0].inc()
        File(rootDir, "corrupt.bin").writeBytes(corrupted)

        val exception = assertThrows(ChecksumMismatchException::class.java) {
            runBlocking {
                verifyChecksum(RuntimeEnvironment.getApplication().contentResolver, node("source.bin"), node("corrupt.bin"))
            }
        }

        assertTrue(exception.message.orEmpty().contains("source.bin"))
    }
}
