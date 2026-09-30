package io.github.mbaliga.fylz.operations

import android.net.Uri
import io.github.mbaliga.fylz.storage.FaultyDocumentsProvider
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import io.github.mbaliga.fylz.storage.VolumeDescriptor
import io.github.mbaliga.fylz.storage.testing.TreeNode
import io.github.mbaliga.fylz.storage.testing.buildTree
import io.github.mbaliga.fylz.storage.testing.diffTrees
import java.io.File
import kotlinx.coroutines.runBlocking
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
 * P0.3 (defect 3): folders literally named `fylz-trash` (or `fylz-trash (n)`), left behind from
 * before this provider stopped stripping the leading dot from `.fylz-trash`. A name match alone
 * must never be enough to treat a folder as trash -- only a [RecycleRecord] that still points
 * inside it does -- and tidying one must only ever move data forward into the real `.fylz-trash`,
 * never delete anything before its copy is verified.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecycleBinLegacyFolderTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var rootDir: File
    private lateinit var docsDir: File
    private lateinit var store: RecycleBinStore
    private lateinit var service: RecycleBinService

    @Before
    fun setUp() {
        rootDir = tempFolder.newFolder("root")
        docsDir = File(rootDir, "docs").apply { mkdirs() }
        val faulty = FaultyDocumentsProvider.install()
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
        val context = RuntimeEnvironment.getApplication()
        store = RecycleBinStore(context)
        service = RecycleBinService(context, store = store)
    }

    private fun documentUri(relativePath: String): Uri =
        FylzFilesDocumentsProvider.documentUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, relativePath)

    private fun treeUriFor(relativePath: String): Uri =
        FylzFilesDocumentsProvider.treeUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, relativePath)

    /** Directly builds a pre-P0.3 legacy bin on disk (never through the fixed provider, which
     * would now correctly keep the dot) and records a [RecycleRecord] pointing inside it, exactly
     * as the old, buggy `RecycleBinService` would have left behind. */
    private fun buildLegacyBin(folderName: String, itemId: String, displayName: String): File {
        val legacy = File(docsDir, folderName).apply { mkdirs() }
        val container = File(legacy, "$itemId-container").apply { mkdirs() }
        buildTree(container, listOf(TreeNode.DirNode(displayName, listOf(TreeNode.FileNode("a.bin", 100)))))
        store.put(
            RecycleRecord(
                itemId = itemId,
                originalUri = documentUri("docs/$displayName"),
                recycledUri = documentUri("docs/$folderName/${container.name}/$displayName"),
                originalParentUri = documentUri("docs"),
                originalDisplayName = displayName,
                providerAuthority = FylzFilesDocumentsProvider.AUTHORITY,
                sizeBytes = null,
                recycledAtMillis = 0L,
                containerUri = documentUri("docs/$folderName/${container.name}"),
            ),
        )
        return legacy
    }

    @Test
    fun `a name match alone is not a legacy bin`() = runBlocking {
        // No RecycleRecord points inside it: a user's own folder that happens to share the name.
        File(docsDir, "fylz-trash").mkdirs()

        assertTrue(service.legacyRecycleFolders(treeUriFor("docs")).isEmpty())
        assertTrue(File(docsDir, "fylz-trash").isDirectory)
    }

    @Test
    fun `a folder a recycle record points into is found as legacy`() = runBlocking {
        buildLegacyBin("fylz-trash", "legacy-1", "photos")

        val found = service.legacyRecycleFolders(treeUriFor("docs"))

        assertEquals(listOf("fylz-trash"), found.map { it.name })
    }

    @Test
    fun `a disambiguated legacy name is found the same way`() = runBlocking {
        buildLegacyBin("fylz-trash (1)", "legacy-1", "photos")

        val found = service.legacyRecycleFolders(treeUriFor("docs"))

        assertEquals(listOf("fylz-trash (1)"), found.map { it.name })
    }

    @Test
    fun `tidy moves recorded items into the real bin and removes an emptied legacy folder`() = runBlocking {
        buildLegacyBin("fylz-trash", "legacy-1", "photos")
        val snapshot = tempFolder.newFolder("snapshot")
        buildTree(snapshot, listOf(TreeNode.DirNode("photos", listOf(TreeNode.FileNode("a.bin", 100)))))

        val legacy = service.legacyRecycleFolders(treeUriFor("docs")).single()
        service.tidyLegacyRecycleFolder(treeUriFor("docs"), legacy)

        assertFalse(File(docsDir, "fylz-trash").exists())
        val restoredContainer = File(docsDir, ".fylz-trash").listFiles()?.single()
            ?: error("expected one container under the real bin")
        assertEquals(
            emptyList<String>(),
            diffTrees(File(snapshot, "photos"), File(restoredContainer, "photos")),
        )
        val record = store.find("legacy-1")!!
        assertTrue(record.recycledUri.toString().contains(".fylz-trash"))
        assertTrue(record.containerUri!!.toString().contains(".fylz-trash"))
    }

    @Test
    fun `tidy leaves a legacy folder in place if it still has untracked content`() = runBlocking {
        val legacyDir = buildLegacyBin("fylz-trash", "legacy-1", "photos")
        File(legacyDir, "not-tracked-by-any-record.txt").writeText("stray")

        val legacy = service.legacyRecycleFolders(treeUriFor("docs")).single()
        service.tidyLegacyRecycleFolder(treeUriFor("docs"), legacy)

        // The recorded item moved out, but the stray file means the folder is never deleted.
        assertTrue(File(docsDir, "fylz-trash").isDirectory)
        assertTrue(File(legacyDir, "not-tracked-by-any-record.txt").exists())
        assertTrue(File(docsDir, ".fylz-trash").isDirectory)
    }
}
