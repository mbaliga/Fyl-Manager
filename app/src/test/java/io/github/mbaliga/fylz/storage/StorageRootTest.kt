package io.github.mbaliga.fylz.storage

import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith

/**
 * `isOnSharedVolume` decides whether a SAF row is a duplicate of something the File backend
 * already serves. Getting it wrong is quietly bad in both directions: too eager and a cloud
 * provider disappears from the home screen; too shy and the padlocked "grant Downloads"
 * rows come back next to the Downloads folder the user can already open.
 */
@RunWith(RobolectricTestRunner::class)
class StorageRootTest {

    private fun root(treeUri: Uri?) = StorageRoot(
        id = "test",
        title = "Test",
        kind = StorageRootKind.STANDARD_DIRECTORY,
        treeUri = treeUri,
        documentUri = treeUri,
    )

    @Test
    fun `a primary-volume subtree is on the shared volume`() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3APictures")
        assertTrue(root(uri).isOnSharedVolume)
    }

    @Test
    fun `the primary volume root itself counts`() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3A")
        assertTrue(root(uri).isOnSharedVolume)
    }

    @Test
    fun `a removable card is NOT the shared volume`() {
        // MANAGE_EXTERNAL_STORAGE does not cover removable media, so these rows must survive.
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/1A2B-3C4D%3ADCIM")
        assertFalse(root(uri).isOnSharedVolume)
    }

    @Test
    fun `a third-party or cloud provider is never the shared volume`() {
        // SAF is the ONLY route to these however broad the file permission is.
        val drive = Uri.parse("content://com.google.android.apps.docs.storage/tree/abc123")
        assertFalse(root(drive).isOnSharedVolume)

        val downloads = Uri.parse("content://com.android.providers.downloads.documents/tree/downloads")
        assertFalse(root(downloads).isOnSharedVolume)
    }

    @Test
    fun `a picker shortcut with no tree uri is not treated as shared`() {
        // Shortcuts carry only an initialUri hint; with nothing to compare they must not be
        // suppressed on a guess.
        assertFalse(root(null).isOnSharedVolume)
    }

    /**
     * `readyToOpen` exists because `opensDirectly` alone answers "does this need a grant" wrong
     * for a REMOTE root -- caught by actually rendering StorageRootRow, which put a "still needs
     * a grant" lock badge on a saved connection that needs no grant at all.
     */
    @Test
    fun `a remote root is ready to open despite carrying no SAF uris`() {
        val remote = StorageRoot(id = "remote:nas-1", title = "Office NAS", kind = StorageRootKind.REMOTE)

        assertFalse(remote.opensDirectly)
        assertTrue(remote.readyToOpen)
    }

    @Test
    fun `a non-remote root without SAF uris still needs a grant`() {
        val shortcut = StorageRoot(id = "shortcut", title = "Add a location", kind = StorageRootKind.PICKER_SHORTCUT)

        assertFalse(shortcut.opensDirectly)
        assertFalse(shortcut.readyToOpen)
    }

    @Test
    fun `a directly-openable root is ready to open`() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3APictures")

        assertTrue(root(uri).readyToOpen)
    }
}
