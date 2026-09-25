package io.github.mbaliga.fylz.integration

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri

enum class FylzExternalOperationKind { RECURSIVE_BACKUP, STAGE_PACKAGE, COPY, SHA256 }
enum class FylzExternalOperationState { QUEUED, RUNNING, SUCCEEDED, FAILED, INTERRUPTED, CANCELLED }

data class FylzExternalOperationRequest(
    val requestId: String,
    val kind: FylzExternalOperationKind,
    val sourceUri: Uri,
    val destinationTreeUri: Uri? = null,
    val destinationRelativePath: String? = null,
    val expectedSha256: String? = null,
) {
    init {
        require(requestId.isNotBlank()) { "Fylz operation request id is required." }
        require(sourceUri.scheme == "content") { "Fylz only accepts opaque content:// source capabilities." }
        require(destinationTreeUri == null || destinationTreeUri.scheme == "content") {
            "Fylz only accepts opaque content:// destination capabilities."
        }
        require(expectedSha256 == null || SHA256.matches(expectedSha256)) { "Expected SHA-256 is invalid." }
        if (kind in setOf(FylzExternalOperationKind.RECURSIVE_BACKUP, FylzExternalOperationKind.STAGE_PACKAGE, FylzExternalOperationKind.COPY)) {
            require(destinationTreeUri != null) { "$kind requires a destination tree." }
        }
        if (kind == FylzExternalOperationKind.STAGE_PACKAGE) {
            require(isSafeRelativePath(destinationRelativePath)) { "Package staging requires a safe destination path." }
            require(expectedSha256 != null) { "Package staging requires an expected SHA-256." }
        }
    }

    companion object { private val SHA256 = Regex("^[a-fA-F0-9]{64}$") }
}

/**
 * Signature-scoped, receipt-bearing cross-app operation contract. Fonebrew sends capabilities and
 * intent; Fylz remains the only component that browses providers or performs user-file I/O.
 */
object FylzOperationContract {
    const val VERSION = 1
    const val PERMISSION_OPERATE = "io.github.mbaliga.fylz.permission.OPERATE"
    const val ACTION_EXECUTE = "io.github.mbaliga.fylz.action.EXECUTE_OPERATION"
    const val ACTION_CANCEL = "io.github.mbaliga.fylz.action.CANCEL_OPERATION"
    const val EXTRA_VERSION = "io.github.mbaliga.fylz.extra.OPERATION_CONTRACT_VERSION"
    const val EXTRA_REQUEST_ID = "io.github.mbaliga.fylz.extra.REQUEST_ID"
    const val EXTRA_KIND = "io.github.mbaliga.fylz.extra.OPERATION_KIND"
    const val EXTRA_SOURCE_URI = "io.github.mbaliga.fylz.extra.SOURCE_URI"
    const val EXTRA_DESTINATION_TREE_URI = "io.github.mbaliga.fylz.extra.DESTINATION_TREE_URI"
    const val EXTRA_DESTINATION_RELATIVE_PATH = "io.github.mbaliga.fylz.extra.DESTINATION_RELATIVE_PATH"
    const val EXTRA_EXPECTED_SHA256 = "io.github.mbaliga.fylz.extra.EXPECTED_SHA256"
    const val EXTRA_CALLBACK = "io.github.mbaliga.fylz.extra.CALLBACK"
    const val EXTRA_STATE = "io.github.mbaliga.fylz.extra.STATE"
    const val EXTRA_COMPLETED_BYTES = "io.github.mbaliga.fylz.extra.COMPLETED_BYTES"
    const val EXTRA_TOTAL_BYTES = "io.github.mbaliga.fylz.extra.TOTAL_BYTES"
    const val EXTRA_SHA256 = "io.github.mbaliga.fylz.extra.SHA256"
    const val EXTRA_OUTPUT_URI = "io.github.mbaliga.fylz.extra.OUTPUT_URI"
    const val EXTRA_ERROR_CODE = "io.github.mbaliga.fylz.extra.ERROR_CODE"

    fun decode(intent: Intent): Pair<FylzExternalOperationRequest, PendingIntent> {
        require(intent.action == ACTION_EXECUTE) { "Unsupported Fylz operation action." }
        require(intent.getIntExtra(EXTRA_VERSION, -1) == VERSION) { "Unsupported Fylz operation contract version." }
        val request = FylzExternalOperationRequest(
            requestId = requireNotNull(intent.getStringExtra(EXTRA_REQUEST_ID)) { "Missing request id." },
            kind = FylzExternalOperationKind.valueOf(requireNotNull(intent.getStringExtra(EXTRA_KIND)) { "Missing operation kind." }),
            sourceUri = Uri.parse(requireNotNull(intent.getStringExtra(EXTRA_SOURCE_URI)) { "Missing source URI." }),
            destinationTreeUri = intent.getStringExtra(EXTRA_DESTINATION_TREE_URI)?.let(Uri::parse),
            destinationRelativePath = intent.getStringExtra(EXTRA_DESTINATION_RELATIVE_PATH),
            expectedSha256 = intent.getStringExtra(EXTRA_EXPECTED_SHA256)?.lowercase(),
        )
        @Suppress("DEPRECATION")
        val callback = intent.getParcelableExtra<PendingIntent>(EXTRA_CALLBACK)
            ?: error("Missing receipt callback.")
        return request to callback
    }

    fun receiptIntent(
        requestId: String,
        state: FylzExternalOperationState,
        completedBytes: Long = 0,
        totalBytes: Long? = null,
        sha256: String? = null,
        outputUri: Uri? = null,
        errorCode: String? = null,
    ): Intent = Intent()
        .putExtra(EXTRA_VERSION, VERSION)
        .putExtra(EXTRA_REQUEST_ID, requestId)
        .putExtra(EXTRA_STATE, state.name)
        .putExtra(EXTRA_COMPLETED_BYTES, completedBytes)
        .putExtra(EXTRA_TOTAL_BYTES, totalBytes ?: -1L)
        .putExtra(EXTRA_SHA256, sha256)
        .putExtra(EXTRA_OUTPUT_URI, outputUri?.toString())
        .putExtra(EXTRA_ERROR_CODE, errorCode)
}

private fun isSafeRelativePath(path: String?): Boolean {
    if (path.isNullOrBlank() || path.startsWith('/') || '\u0000' in path) return false
    return path.replace('\\', '/').split('/').none { it.isBlank() || it == "." || it == ".." }
}

