package io.github.mbaliga.fylz.storage

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowEnvironment
import java.io.File

/**
 * `Environment.isExternalStorageManager()` shadowed to "granted".
 *
 * Robolectric 4.16.1 ships no shadow for it and the framework implementation throws off-device.
 * Extending [ShadowEnvironment] rather than starting fresh is load-bearing: `@Config(shadows=...)`
 * replaces the built-in shadow map entry for [Environment] wholesale, and losing the inherited
 * `getExternalStorageDirectory` redirection would unpin the volume fallback these tests anchor
 * to a [TemporaryFolder].
 */
@Implements(Environment::class)
class ShadowFullAccessEnvironment : ShadowEnvironment() {
    companion object {
        @JvmStatic
        @Implementation
        fun isExternalStorageManager(): Boolean = true
    }
}

/**
 * The File backend's launch surface (WP-0.7, File-fixture-only; not part of the shared
 * provider contract).
 *
 * [FileStorageProvider.rootGroups] is what makes a fresh launch show real locations instead of
 * "Open a folder to begin": the primary volume must lead, and the "Folders" group must list
 * exactly the standard directories that exist on disk -- a device without a Movies folder must
 * not get a dead row.
 *
 * Kept as its own class so the full-access shadow above cannot leak into the contract runs,
 * which must not inherit a "granted" lie they never asked for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ShadowFullAccessEnvironment::class])
class FileStorageProviderRootGroupsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var externalRoot: File

    @Before
    fun anchorExternalStorage() {
        externalRoot = temporaryFolder.newFolder("external")
        ShadowEnvironment.setExternalStorageDirectory(externalRoot.toPath())
        // Two standard directories exist; the other four deliberately do not.
        check(File(externalRoot, Environment.DIRECTORY_DOWNLOADS).mkdirs())
        check(File(externalRoot, Environment.DIRECTORY_DCIM).mkdirs())
    }

    @Test
    fun `the primary volume leads the groups and opens directly`() {
        val groups = runBlocking { FileStorageProvider().rootGroups(context) }

        val device = groups.first()
        assertEquals(FileStorageProvider.GROUP_DEVICE, device.title)
        val volume = device.roots.single()
        assertEquals(StorageRootKind.INTERNAL, volume.kind)
        assertTrue(volume.opensDirectly)
        assertEquals(FylzFilesDocumentsProvider.AUTHORITY, volume.treeUri?.authority)
        assertEquals(externalRoot.absolutePath, volume.subtitle)
    }

    @Test
    fun `only standard directories that exist are listed as folders`() {
        val groups = runBlocking { FileStorageProvider().rootGroups(context) }

        val folders = groups.single { it.title == FileStorageProvider.GROUP_FOLDERS }
        assertEquals(listOf("Downloads", "Camera (DCIM)"), folders.roots.map { it.title })
        assertTrue(folders.roots.all { it.kind == StorageRootKind.STANDARD_DIRECTORY })
        assertTrue(folders.roots.all { it.opensDirectly })
    }

    @Test
    fun `no removable group appears without removable volumes`() {
        val groups = runBlocking { FileStorageProvider().rootGroups(context) }

        assertTrue(groups.none { it.title == FileStorageProvider.GROUP_REMOVABLE })
    }

    @Test
    fun `the granted permission makes the provider ready with nothing to ask`() {
        val provider = FileStorageProvider()

        assertTrue(provider.isReady(context))
        assertNull(provider.readinessMessage(context))
        assertNull(provider.permissionIntent(context))
    }
}
