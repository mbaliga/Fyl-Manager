package io.github.mbaliga.fylz.storage

import android.content.ContentResolver
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [StorageBackendContractTest] bound to [FakeSafDocumentsProvider] with every quirk dial turned
 * to the setting the File backend cannot reach: a case-insensitive name space, a second tree
 * that moves must fail against, and the ability to serve a null `LAST_MODIFIED`.
 *
 * Together with [FileBackendContractTest] this covers both sides of every capability gate in
 * the shared suite, so no contract test is dead code: each `assumeTrue` skip on one class runs
 * for real on the other.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FakeSafBackendContractTest : StorageBackendContractTest() {

    private lateinit var fakeFixture: FakeSafFixture

    override val fixture: BackendFixture get() = fakeFixture

    @Before
    fun registerProvider() {
        // Knobs first: createDocument consults them, so they must be in force before any
        // contract test touches the provider.
        FakeSafDocumentsProvider.caseInsensitiveNames = true
        FakeSafDocumentsProvider.nullLastModified = false
        FakeSafDocumentsProvider.failCrossTreeMove = true

        val providerInfo = ProviderInfo().apply {
            authority = FakeSafDocumentsProvider.AUTHORITY
            exported = true
            grantUriPermissions = true
            // DocumentsProvider.attachInfo throws SecurityException unless both guards carry
            // the signature-level MANAGE_DOCUMENTS declaration.
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
        }
        val provider = Robolectric.buildContentProvider(FakeSafDocumentsProvider::class.java)
            .create(providerInfo)
            .get()
        fakeFixture = FakeSafFixture(provider)
    }

    @After
    fun resetKnobs() {
        FakeSafDocumentsProvider.resetKnobs()
    }

    /** Out-of-band access = direct node-tree mutation on the provider instance. */
    private class FakeSafFixture(private val provider: FakeSafDocumentsProvider) : BackendFixture {

        override val resolver: ContentResolver = RuntimeEnvironment.getApplication().contentResolver

        override val treeUri: Uri = DocumentsContract.buildTreeDocumentUri(
            FakeSafDocumentsProvider.AUTHORITY,
            FakeSafDocumentsProvider.ROOT_A_DOC_ID,
        )

        override val secondTreeUri: Uri = DocumentsContract.buildTreeDocumentUri(
            FakeSafDocumentsProvider.AUTHORITY,
            FakeSafDocumentsProvider.ROOT_B_DOC_ID,
        )

        override val caseInsensitiveNames: Boolean
            get() = FakeSafDocumentsProvider.caseInsensitiveNames

        override val canServeNullLastModified: Boolean = true

        override val failsCrossTreeMove: Boolean
            get() = FakeSafDocumentsProvider.failCrossTreeMove

        override fun seedFile(name: String, content: ByteArray) {
            provider.seedFile(
                FakeSafDocumentsProvider.ROOT_A_DOC_ID,
                name,
                content,
                System.currentTimeMillis(),
            )
        }

        override fun seedFolder(name: String) {
            provider.seedDirectory(FakeSafDocumentsProvider.ROOT_A_DOC_ID, name)
        }

        override fun deleteOutOfBand(name: String) {
            provider.removeOutOfBand(FakeSafDocumentsProvider.ROOT_A_DOC_ID, name)
        }

        override fun setLastModifiedOutOfBand(name: String, epochMillis: Long): Boolean {
            provider.setLastModifiedOutOfBand(FakeSafDocumentsProvider.ROOT_A_DOC_ID, name, epochMillis)
            return true
        }

        override fun seedFileWithNullLastModified(name: String) {
            provider.seedFile(FakeSafDocumentsProvider.ROOT_A_DOC_ID, name, ByteArray(0), lastModified = null)
        }

        override fun makeReadOnly(name: String): Boolean {
            provider.setWritableOutOfBand(FakeSafDocumentsProvider.ROOT_A_DOC_ID, name, false)
            return true
        }
    }
}
