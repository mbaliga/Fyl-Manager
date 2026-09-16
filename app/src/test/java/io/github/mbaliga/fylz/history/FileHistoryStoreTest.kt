package io.github.mbaliga.fylz.history

import android.content.Context
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import io.github.mbaliga.fylz.storage.FakeSafDocumentsProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * `capture`'s `enabled` gate must not swallow the [FileHistoryReason.BEFORE_RESTORE] safety
 * snapshot that `restore()` takes of the file it is about to overwrite -- that snapshot is the
 * only way a failed or unverified restore can roll back, and it has nothing to do with whether
 * the user opted into ongoing version retention. Reuses [FakeSafDocumentsProvider] (as
 * [io.github.mbaliga.fylz.storage.FakeSafBackendContractTest] does) rather than a bare `file://`
 * URI, because `queryMetadata`'s `ContentResolver.query` needs a real provider to answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileHistoryStoreTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var provider: FakeSafDocumentsProvider

    @Before
    fun registerProvider() {
        val providerInfo = ProviderInfo().apply {
            authority = FakeSafDocumentsProvider.AUTHORITY
            exported = true
            grantUriPermissions = true
            // DocumentsProvider.attachInfo throws SecurityException unless both guards carry
            // the signature-level MANAGE_DOCUMENTS declaration.
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
        }
        provider = Robolectric.buildContentProvider(FakeSafDocumentsProvider::class.java)
            .create(providerInfo)
            .get()
    }

    @After
    fun resetKnobs() {
        FakeSafDocumentsProvider.resetKnobs()
    }

    private fun store() = FileHistoryStore(context)

    private fun seedDocument(name: String, content: String): Uri {
        val documentId = provider.seedFile(
            FakeSafDocumentsProvider.ROOT_A_DOC_ID,
            name,
            content.toByteArray(),
            System.currentTimeMillis(),
        )
        return DocumentsContract.buildDocumentUri(FakeSafDocumentsProvider.AUTHORITY, documentId)
    }

    private fun readDocument(uri: Uri): String =
        context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }.decodeToString()

    private fun writeDocument(uri: Uri, content: String) {
        context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(content.toByteArray()) }
    }

    @Test
    fun `a before-restore capture succeeds while history is disabled`() = runBlocking {
        val store = store()
        val uri = seedDocument("notes.txt", "content")
        check(!store.settings().enabled) { "history must be off by default for this to be a real test" }

        val result = store.capture(uri, FileHistoryReason.BEFORE_RESTORE)

        assertEquals(FileHistoryCaptureStatus.CAPTURED, result.status)
        assertNotNull(result.version)
    }

    @Test
    fun `an observed capture is still refused while history is disabled`() = runBlocking {
        val store = store()
        val uri = seedDocument("notes.txt", "content")
        check(!store.settings().enabled)

        val result = store.capture(uri, FileHistoryReason.OBSERVED)

        // The BEFORE_RESTORE exception must not widen into "the toggle no longer applies" --
        // every other reason stays gated.
        assertEquals(FileHistoryCaptureStatus.DISABLED, result.status)
    }

    @Test
    fun `restore succeeds with the current version even though history is disabled`() = runBlocking {
        val store = store()
        val uri = seedDocument("notes.txt", "version-one")
        store.updateSettings(store.settings().copy(enabled = true))
        val saved = store.capture(uri, FileHistoryReason.OBSERVED)
        check(saved.status == FileHistoryCaptureStatus.CAPTURED)
        writeDocument(uri, "version-two")
        // Turning history off must not disable restore: capture(BEFORE_RESTORE) still has to run
        // so the current ("version-two") bytes are not lost if the restore write fails partway.
        store.updateSettings(store.settings().copy(enabled = false))

        val result = store.restore(saved.version!!.id, uri)

        assertEquals(FileHistoryRestoreStatus.RESTORED, result.status)
        assertEquals("version-one", readDocument(uri))
        val safetySnapshot = store.versions(uri).firstOrNull { it.reason == FileHistoryReason.BEFORE_RESTORE }
        assertNotNull("the pre-restore state must have been preserved for rollback", safetySnapshot)
    }
}
