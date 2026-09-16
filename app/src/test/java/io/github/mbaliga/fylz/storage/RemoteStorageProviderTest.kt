package io.github.mbaliga.fylz.storage

import android.content.Context
import io.github.mbaliga.fylz.network.RemoteConnection
import io.github.mbaliga.fylz.network.RemoteConnectionStore
import io.github.mbaliga.fylz.network.RemoteKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Before this provider existed, a saved SMB/SFTP/S3/WebDAV connection was invisible on the
 * storage home screen: [StorageRootKind.REMOTE] was declared and used for exactly one icon
 * mapping, and nothing ever constructed a [StorageRoot] carrying it. These pin that a saved
 * connection now actually surfaces, and that [RemoteStorageProvider.rootId]/[connectionIdOf]
 * stay each other's inverse -- `openStorageRoot`'s dispatch in FylzV1App depends on that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteStorageProviderTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    // No secret: rootGroups() only ever reads the connection's own metadata, never the vault --
    // giving it one here would pull in the real AndroidKeyStore provider these tests otherwise
    // have no reason to depend on.
    private fun save(connection: RemoteConnection) {
        RemoteConnectionStore(context).save(connection, secret = null)
    }

    @Test
    fun `no connections means no group at all, not an empty one`() {
        val groups = runBlocking { RemoteStorageProvider().rootGroups(context) }
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `a saved connection surfaces as a REMOTE root with no SAF uris`() {
        save(RemoteConnection(id = "nas-1", kind = RemoteKind.SMB, displayName = "Office NAS"))

        val groups = runBlocking { RemoteStorageProvider().rootGroups(context) }

        val root = groups.single().roots.single()
        assertEquals(RemoteStorageProvider.GROUP_REMOTE, groups.single().title)
        assertEquals("Office NAS", root.title)
        assertEquals(RemoteKind.SMB.label, root.subtitle)
        assertEquals(StorageRootKind.REMOTE, root.kind)
        assertNull(root.treeUri)
        assertNull(root.documentUri)
        // Neither uri exists for a remote connection, so opensDirectly must never claim one does
        // -- StorageHomeScreen's click dispatch would otherwise fall through to the SAF picker.
        assertTrue(!root.opensDirectly)
    }

    @Test
    fun `the root id round-trips back to the connection id`() {
        save(RemoteConnection(id = "nas-1", kind = RemoteKind.SFTP, displayName = "Home Server"))

        val root = runBlocking { RemoteStorageProvider().rootGroups(context) }.single().roots.single()

        assertEquals("nas-1", RemoteStorageProvider.connectionIdOf(root.id))
    }

    @Test
    fun `the provider is always ready and asks for no permission`() {
        val provider = RemoteStorageProvider()
        assertTrue(provider.isReady(context))
        assertNull(provider.readinessMessage(context))
        assertNull(provider.permissionIntent(context))
    }
}
