package io.github.mbaliga.fylz.storage

import android.content.ContentResolver
import android.content.pm.ProviderInfo
import android.net.Uri
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import java.io.File

/**
 * [StorageBackendContractTest] bound to the real [FylzFilesDocumentsProvider].
 *
 * This is the production `java.io.File` backend under the shared contract, not a stand-in:
 * every URI the app hands to its SAF-shaped services is served by this exact class, so the
 * invariants pinned here (disambiguation on create, fail-on-conflict rename/move, recursive
 * delete, name sanitation) are the ones the browsing UI, recycle bin and file operations
 * actually rest on.
 *
 * The provider's "primary" root is anchored to a per-test [TemporaryFolder]:
 * `ShadowStorageManager` is deliberately left unpopulated so `discoverVolumes` takes its
 * `Environment.getExternalStorageDirectory()` fallback, which Robolectric redirects into the
 * temp directory.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileBackendContractTest : StorageBackendContractTest() {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var fileFixture: FileBackendFixture

    override val fixture: BackendFixture get() = fileFixture

    @Before
    fun registerProvider() {
        val externalRoot = temporaryFolder.newFolder("external")
        ShadowEnvironment.setExternalStorageDirectory(externalRoot.toPath())
        val providerInfo = ProviderInfo().apply {
            authority = FylzFilesDocumentsProvider.AUTHORITY
            exported = true
            grantUriPermissions = true
            // DocumentsProvider.attachInfo throws SecurityException unless both guards carry
            // the signature-level MANAGE_DOCUMENTS declaration from the manifest.
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
        }
        Robolectric.buildContentProvider(FylzFilesDocumentsProvider::class.java).create(providerInfo)
        fileFixture = FileBackendFixture(externalRoot)
    }

    /**
     * Out-of-band access = direct `java.io.File` mutation of the directory the provider serves,
     * which is exactly how downloads, MTP and other apps change the world behind this provider.
     */
    private class FileBackendFixture(private val root: File) : BackendFixture {

        override val resolver: ContentResolver = RuntimeEnvironment.getApplication().contentResolver

        override val treeUri: Uri =
            FylzFilesDocumentsProvider.treeUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID)

        /**
         * Probed, not hardcoded: the provider's collision behavior follows the filesystem, and
         * while CI's tmpfs/ext4 are case-sensitive, a developer running this suite on APFS is
         * not. The probe keeps the knob honest either way.
         */
        override val caseInsensitiveNames: Boolean = run {
            val probe = File(root, ".fylz-case-probe")
            probe.createNewFile()
            val insensitive = File(root, ".FYLZ-CASE-PROBE").exists()
            probe.delete()
            insensitive
        }

        /** `java.io.File.lastModified()` is a primitive long; this backend cannot serve null. */
        override val canServeNullLastModified: Boolean = false

        /**
         * One temp root is one filesystem, so the `renameTo`-only move refusal cannot be
         * provoked deterministically here; the cross-tree contract runs on the fake instead.
         */
        override val failsCrossTreeMove: Boolean = false

        override fun seedFile(name: String, content: ByteArray) {
            File(root, name).writeBytes(content)
        }

        override fun seedFolder(name: String) {
            check(File(root, name).mkdir()) { "unable to seed folder $name" }
        }

        override fun deleteOutOfBand(name: String) {
            check(File(root, name).deleteRecursively()) { "unable to delete $name out-of-band" }
        }

        override fun setLastModifiedOutOfBand(name: String, epochMillis: Long): Boolean =
            File(root, name).setLastModified(epochMillis)

        override fun makeReadOnly(name: String): Boolean {
            val target = File(root, name)
            target.setWritable(false, false)
            // Self-probing: as root the call is accepted yet ineffective, so the only
            // trustworthy signal is what canWrite() -- the value the provider's FLAG
            // computation reads -- reports afterwards.
            return !target.canWrite()
        }

        override fun seedHugeFile(name: String, sizeBytes: Long): Boolean {
            // setLength allocates no blocks on ext4/tmpfs; length() still reports it all.
            java.io.RandomAccessFile(File(root, name), "rw").use { it.setLength(sizeBytes) }
            return true
        }
    }
}
