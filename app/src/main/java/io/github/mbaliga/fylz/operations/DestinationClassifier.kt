package io.github.mbaliga.fylz.operations

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider

/** What kind of storage a transfer's destination root lives on, for [shouldVerify] (P1.4). */
internal enum class DestinationKind {
    /** This device's own built-in storage -- either through [FylzFilesDocumentsProvider]'s own
     * primary volume, or through the system `ExternalStorageProvider`'s `primary` root. */
    INTERNAL,

    /** An SD card or USB drive -- either through [FylzFilesDocumentsProvider]'s own non-primary
     * volumes, or through the system `ExternalStorageProvider`'s non-`primary` roots. */
    REMOVABLE,

    /** Any other provider this app has no specific knowledge of: a third-party SAF provider (a
     * cloud storage app), or, once a network destination can ever reach [FileOperationService] at
     * all (today none can -- see `docs/agent/PROGRESS.md`'s P1.4 notes), an SFTP/SMB/WebDAV/S3
     * mount. Treated the same as [REMOVABLE] by [shouldVerify]'s "Removable and network" mode:
     * neither is this device's own trusted local disk. */
    OTHER,
}

/**
 * Classifies [treeUri]'s own root. Two providers get specific treatment -- this app's own
 * [FylzFilesDocumentsProvider] (checked against [FylzFilesDocumentsProvider.isRemovableRoot],
 * which -- unlike calling `discoverVolumes` directly -- goes through the live provider instance,
 * so it also respects a test's `volumeOverride`) and the system's `ExternalStorageProvider` (whose
 * `primary:...` vs `<uuid>:...` document-id convention [FylzFilesDocumentsProvider] deliberately
 * mirrors, per its own class doc -- see [FylzFilesDocumentsProvider.PRIMARY_ROOT_ID]). Everything
 * else is [DestinationKind.OTHER].
 */
internal fun classifyDestination(context: Context, treeUri: Uri): DestinationKind {
    val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()?.substringBefore(':')
    return when (treeUri.authority) {
        FylzFilesDocumentsProvider.AUTHORITY -> {
            val removable = rootId?.let { id -> FylzFilesDocumentsProvider.isRemovableRoot(context, id) }
            if (removable == true) DestinationKind.REMOVABLE else DestinationKind.INTERNAL
        }
        EXTERNAL_STORAGE_PROVIDER_AUTHORITY -> {
            if (rootId == FylzFilesDocumentsProvider.PRIMARY_ROOT_ID) DestinationKind.INTERNAL else DestinationKind.REMOVABLE
        }
        else -> DestinationKind.OTHER
    }
}

/** Whether [destinationKind] should be checksum-verified under [mode]. Pure, and the only part of
 * this decision that doesn't need a real provider to test. */
internal fun shouldVerify(mode: VerifyMode, destinationKind: DestinationKind): Boolean = when (mode) {
    VerifyMode.OFF -> false
    VerifyMode.ALWAYS -> true
    VerifyMode.REMOVABLE_AND_NETWORK -> destinationKind != DestinationKind.INTERNAL
}

private const val EXTERNAL_STORAGE_PROVIDER_AUTHORITY = "com.android.externalstorage.documents"
