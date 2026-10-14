package io.github.mbaliga.fylz.scan

import android.app.Activity
import android.content.IntentSender
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning

/**
 * Abstracts which document-scanning backend Fylz uses behind a seam for a decision Madhav
 * hasn't made yet (P0.13, decision D1): no behavior change today, since [GmsDocumentScannerAdapter]
 * is the only implementation, requesting exactly the same [GmsDocumentScannerOptions] the caller
 * built inline before this. Parsing the result stays out of this interface -- that's generic
 * `ActivityResultContracts` glue via `GmsDocumentScanningResult`, not backend-specific.
 */
interface DocumentScanner {
    fun getStartScanIntent(activity: Activity): Task<IntentSender>
}

class GmsDocumentScannerAdapter : DocumentScanner {
    private val client = GmsDocumentScanning.getClient(
        GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(100)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF,
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build(),
    )

    override fun getStartScanIntent(activity: Activity): Task<IntentSender> =
        client.getStartScanIntent(activity)
}
