package io.github.mbaliga.fylz.storage

import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * P0.0 test harness: hosts the real [FylzFilesDocumentsProvider] over a temporary directory
 * instead of the device's real storage volumes, via the [FylzFilesDocumentsProvider.volumeOverride]
 * test seam. Subclasses get a live provider (`provider`) whose primary root is `rootDir`, and can
 * exercise it exactly as `operations/` code does: through document ids built from
 * [FylzFilesDocumentsProvider.documentIdFor]-shaped strings, or the [FylzFilesDocumentsProvider]
 * companion's [FylzFilesDocumentsProvider.treeUri]/[FylzFilesDocumentsProvider.documentUri].
 *
 * `sdk = [35]`: Robolectric 4.16.1 predates full API 36 shadow support; the app's own tests pin
 * the same level for the same reason (see `PdfPagePlanPolicyTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
abstract class FylzDocumentsProviderTestBase {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    protected lateinit var rootDir: File
    protected lateinit var provider: FylzFilesDocumentsProvider

    /** The root document id for [rootId], in the `<rootId>:` shape this provider uses. */
    protected fun rootDocumentId(rootId: String = FylzFilesDocumentsProvider.PRIMARY_ROOT_ID): String =
        "$rootId:"

    /** The document id for [relativePath] under [rootId] (forward-slash separated, no leading slash). */
    protected fun documentId(
        relativePath: String,
        rootId: String = FylzFilesDocumentsProvider.PRIMARY_ROOT_ID,
    ): String = "$rootId:$relativePath"

    @Before
    fun setUpFylzDocumentsProvider() {
        rootDir = tempFolder.newFolder("primary")
        provider = Robolectric.setupContentProvider(
            FylzFilesDocumentsProvider::class.java,
            FylzFilesDocumentsProvider.AUTHORITY,
        )
        provider.volumeOverride = listOf(primaryVolume(rootDir))
    }

    /** Builds the single-volume override most tests want; override [setUpFylzDocumentsProvider]'s
     * volume list directly (`provider.volumeOverride = ...`) for multi-volume scenarios. */
    protected fun primaryVolume(directory: File, rootId: String = FylzFilesDocumentsProvider.PRIMARY_ROOT_ID) =
        VolumeDescriptor(
            rootId = rootId,
            title = "Internal storage",
            directory = directory,
            primary = true,
            removable = false,
            readOnly = false,
        )
}
