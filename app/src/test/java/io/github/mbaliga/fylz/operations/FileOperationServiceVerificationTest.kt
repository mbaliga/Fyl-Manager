package io.github.mbaliga.fylz.operations

import android.net.Uri
import io.github.mbaliga.fylz.storage.FylzDocumentsProviderTestBase
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import io.github.mbaliga.fylz.storage.testing.TreeNode
import io.github.mbaliga.fylz.storage.testing.buildTree
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment

/**
 * P1.4: [FileOperationService.transfer]'s own wiring of verification -- that a real transfer
 * actually consults [VerifySettings] and [classifyDestination], populates `operation_items.sha256`
 * when it applies, leaves it null when it doesn't, and -- on a genuine mismatch -- fails the item
 * without ever renaming the corrupted staged write to its final name or deleting it (see the
 * comment above the verification step in [FileOperationService.transfer]). The pure decision logic
 * ([shouldVerify], [classifyDestination]) is pinned separately in [DestinationClassifierTest];
 * [verifyChecksum] itself in [ChecksumVerificationTest]. This suite is only about the service
 * actually calling them at the right point, against the real P0.0-hosted provider.
 */
class FileOperationServiceVerificationTest : FylzDocumentsProviderTestBase() {

    private lateinit var verifySettings: VerifySettings
    private lateinit var service: FileOperationService
    private lateinit var sourceDir: File
    private lateinit var destinationDir: File

    @Before
    fun setUpService() {
        sourceDir = File(rootDir, "source").apply { mkdirs() }
        destinationDir = File(rootDir, "destination").apply { mkdirs() }
        verifySettings = VerifySettings(RuntimeEnvironment.getApplication())
        service = FileOperationService(context = RuntimeEnvironment.getApplication(), verifySettings = verifySettings)
    }

    private fun documentUri(relativePath: String): Uri =
        FylzFilesDocumentsProvider.documentUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, relativePath)

    private fun treeUriFor(relativePath: String): Uri =
        FylzFilesDocumentsProvider.treeUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, relativePath)

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `ALWAYS mode populates sha256 with the correct hash after a normal copy`() = runBlocking {
        verifySettings.setMode(VerifyMode.ALWAYS)
        buildTree(sourceDir, listOf(TreeNode.FileNode("photo.jpg", 30_000)))

        service.copy(listOf(documentUri("source/photo.jpg")), treeUriFor("destination"))

        val item = service.operations().single().items.single()
        assertEquals(sha256Of(File(sourceDir, "photo.jpg").readBytes()), item.sha256)
    }

    @Test
    fun `OFF mode never computes sha256`() = runBlocking {
        verifySettings.setMode(VerifyMode.OFF)
        buildTree(sourceDir, listOf(TreeNode.FileNode("photo.jpg", 30_000)))

        service.copy(listOf(documentUri("source/photo.jpg")), treeUriFor("destination"))

        val item = service.operations().single().items.single()
        assertNull(item.sha256)
    }

    @Test
    fun `REMOVABLE_AND_NETWORK skips verification for this device's own internal destination`() = runBlocking {
        verifySettings.setMode(VerifyMode.REMOVABLE_AND_NETWORK)
        buildTree(sourceDir, listOf(TreeNode.FileNode("photo.jpg", 30_000)))

        service.copy(listOf(documentUri("source/photo.jpg")), treeUriFor("destination"))

        val item = service.operations().single().items.single()
        assertNull(item.sha256)
    }

    @Test
    fun `a checksum mismatch fails the item without deleting the staged write or reaching its final name`() {
        verifySettings.setMode(VerifyMode.ALWAYS)
        buildTree(sourceDir, listOf(TreeNode.FileNode("photo.jpg", 30_000)))
        var corrupted = false

        assertThrows(ChecksumMismatchException::class.java) {
            runBlocking {
                service.copy(listOf(documentUri("source/photo.jpg")), treeUriFor("destination")) { progress ->
                    // Flip one byte of the staged write on disk right after the copy finishes
                    // writing it (same length, so the size check the copy already does still
                    // passes) but before the P1.4 checksum step -- the only way to get a
                    // size-preserving mismatch past copyDocument's own verifyFile.
                    if (!corrupted && progress.totalBytes != null && progress.completedBytes >= progress.totalBytes) {
                        corrupted = true
                        val staged = destinationDir.listFiles { file -> isStagingName(file.name) }?.singleOrNull()
                            ?: error("Expected exactly one staged file under the destination")
                        val bytes = staged.readBytes()
                        bytes[0] = bytes[0].inc()
                        staged.writeBytes(bytes)
                    }
                }
            }
        }

        val item = service.operations().single().items.single()
        assertEquals(OperationState.FAILED, item.state)
        assertEquals("ChecksumMismatchException", item.errorCode)
        val survivingStaged = destinationDir.listFiles { file -> isStagingName(file.name) }?.singleOrNull()
        assertTrue("the corrupted staged write must survive for inspection, not be deleted", survivingStaged != null)
        assertTrue("the corrupted write must never reach its final name", !File(destinationDir, "photo.jpg").exists())
    }
}
