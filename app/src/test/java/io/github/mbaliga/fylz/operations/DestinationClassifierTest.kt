package io.github.mbaliga.fylz.operations

import android.net.Uri
import io.github.mbaliga.fylz.storage.FylzDocumentsProviderTestBase
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment

/**
 * P1.4: [classifyDestination] against the real P0.0-hosted [FylzFilesDocumentsProvider] (its
 * `removable` branch needs a live provider instance -- see [FylzFilesDocumentsProvider.isRemovableRoot]'s
 * own doc for why `discoverVolumes` alone would not see this test's `volumeOverride`) plus
 * synthetic URIs for the system `ExternalStorageProvider` and an arbitrary third-party authority,
 * neither of which need a real provider at all. [shouldVerify]'s own truth table needs no provider
 * either.
 */
class DestinationClassifierTest : FylzDocumentsProviderTestBase() {

    @Test
    fun `this app's own provider classifies by the volume's removable flag`() {
        provider.volumeOverride = listOf(
            primaryVolume(rootDir),
            primaryVolume(tempFolder.newFolder("sdcard"), rootId = "sdcard-uuid").copy(removable = true),
        )
        val context = RuntimeEnvironment.getApplication()

        assertEquals(
            DestinationKind.INTERNAL,
            classifyDestination(context, FylzFilesDocumentsProvider.treeUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID)),
        )
        assertEquals(
            DestinationKind.REMOVABLE,
            classifyDestination(context, FylzFilesDocumentsProvider.treeUri("sdcard-uuid")),
        )
    }

    @Test
    fun `an unknown root under this app's own provider defaults to internal, not removable`() {
        provider.volumeOverride = listOf(primaryVolume(rootDir))
        val context = RuntimeEnvironment.getApplication()

        // No root named "ghost" exists -- isRemovableRoot returns null, and classifyDestination
        // must not treat "we don't know" as "removable" (the more alarming, verification-forcing
        // answer would be the safe default if this ever changes, but null-safe-and-quiet-INTERNAL
        // is what the current implementation actually does, and this pins it).
        assertEquals(
            DestinationKind.INTERNAL,
            classifyDestination(context, FylzFilesDocumentsProvider.treeUri("ghost")),
        )
    }

    @Test
    fun `the system ExternalStorageProvider's primary root is internal, any other root is removable`() {
        val context = RuntimeEnvironment.getApplication()
        val primary = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3APictures")
        val sdCard = Uri.parse("content://com.android.externalstorage.documents/tree/1234-5678%3A")

        assertEquals(DestinationKind.INTERNAL, classifyDestination(context, primary))
        assertEquals(DestinationKind.REMOVABLE, classifyDestination(context, sdCard))
    }

    @Test
    fun `an unrecognized third-party provider is OTHER`() {
        val context = RuntimeEnvironment.getApplication()
        val cloudProvider = Uri.parse("content://com.example.cloud.documents/tree/root")

        assertEquals(DestinationKind.OTHER, classifyDestination(context, cloudProvider))
    }

    // --- shouldVerify -----------------------------------------------------------------------

    @Test
    fun `OFF never verifies, ALWAYS always verifies, regardless of destination`() {
        for (kind in DestinationKind.entries) {
            assertFalse(shouldVerify(VerifyMode.OFF, kind))
            assertTrue(shouldVerify(VerifyMode.ALWAYS, kind))
        }
    }

    @Test
    fun `REMOVABLE_AND_NETWORK verifies everything except INTERNAL`() {
        assertFalse(shouldVerify(VerifyMode.REMOVABLE_AND_NETWORK, DestinationKind.INTERNAL))
        assertTrue(shouldVerify(VerifyMode.REMOVABLE_AND_NETWORK, DestinationKind.REMOVABLE))
        assertTrue(shouldVerify(VerifyMode.REMOVABLE_AND_NETWORK, DestinationKind.OTHER))
    }
}
