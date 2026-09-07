package io.github.mbaliga.fylz.storage

import android.net.Uri
import io.github.mbaliga.fylz.core.model.ItemCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Contract tests for the storage capability adapter.
 *
 * The parts that need a live `Context` (volume enumeration, permission state, `ContentResolver`
 * queries) are exercised on device; what is pinned here is the URI scheme every downstream
 * SAF-shaped consumer depends on, and the "can this row open without a picker" rule the home
 * surface branches on.
 */
@RunWith(RobolectricTestRunner::class)
class StorageModelsTest {

    @Test
    fun `a root with both URIs opens directly`() {
        val root = StorageRoot(
            id = "file:primary",
            title = "Internal storage",
            kind = StorageRootKind.INTERNAL,
            treeUri = Uri.parse("content://io.github.mbaliga.fylz.files/tree/primary%3A"),
            documentUri = Uri.parse("content://io.github.mbaliga.fylz.files/tree/primary%3A/document/primary%3A"),
        )
        assertTrue(root.opensDirectly)
    }

    @Test
    fun `a picker shortcut does not open directly`() {
        val shortcut = StorageRoot(
            id = "saf:Download",
            title = "Downloads",
            kind = StorageRootKind.STANDARD_DIRECTORY,
            initialUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"),
        )
        assertFalse(shortcut.opensDirectly)
    }

    @Test
    fun `a root missing its document uri does not open directly`() {
        val partial = StorageRoot(
            id = "broken",
            title = "Broken",
            kind = StorageRootKind.PROVIDER_ROOT,
            treeUri = Uri.parse("content://example/tree/x"),
        )
        assertFalse(partial.opensDirectly)
    }

    @Test
    fun `file provider tree uris are valid document tree uris`() {
        val treeUri = FylzFilesDocumentsProvider.treeUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID)
        assertEquals(FylzFilesDocumentsProvider.AUTHORITY, treeUri.authority)
        assertEquals(
            "primary:",
            android.provider.DocumentsContract.getTreeDocumentId(treeUri),
        )
    }

    @Test
    fun `file provider document uris round trip a nested path`() {
        val documentUri = FylzFilesDocumentsProvider.documentUri(
            FylzFilesDocumentsProvider.PRIMARY_ROOT_ID,
            "Download",
        )
        assertEquals(
            "primary:Download",
            android.provider.DocumentsContract.getDocumentId(documentUri),
        )
    }

    @Test
    fun `the SAF provider never claims picker-free browsing`() {
        // The one distinction that must differ between the two backends: SAF cannot enumerate
        // storage without a user grant, which is precisely why the File backend exists. It now
        // lives as a plain boolean rather than an ItemCapability -- see StorageProvider's KDoc.
        assertFalse(SafStorageProvider().browseWithoutPicker)
        assertFalse(SafStorageProvider().wholeVolume)
        assertTrue(FileStorageProvider().browseWithoutPicker)
        assertTrue(FileStorageProvider().wholeVolume)
    }

    @Test
    fun `no provider ever claims the secure-erase capability`() {
        // Fylz's "Shred" action promises only what software can honestly guarantee -- never
        // forensic, unrecoverable erasure. This is the executable form of that promise.
        assertFalse(ItemCapability.SECURE_ERASE_CLAIM in SafStorageProvider().capabilities)
        assertFalse(ItemCapability.SECURE_ERASE_CLAIM in FileStorageProvider().capabilities)
    }

    @Test
    fun `the SAF provider is always ready and asks for nothing`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val saf = SafStorageProvider()
        assertTrue(saf.isReady(context))
        assertEquals(null, saf.readinessMessage(context))
        assertEquals(null, saf.permissionIntent(context))
    }

    @Test
    fun `both providers agree on the standard directory list`() {
        // FileStorageProvider builds its "Folders" group from SafStorageProvider's list; if they
        // ever diverged the two backends would disagree about what "Downloads" means.
        val names = SafStorageProvider.STANDARD_DIRECTORIES.map { it.second }
        assertTrue("Download" in names)
        assertTrue("DCIM" in names)
        assertTrue("Documents" in names)
        assertTrue("Pictures" in names)
        assertTrue("Movies" in names)
        assertTrue("Music" in names)
    }
}
