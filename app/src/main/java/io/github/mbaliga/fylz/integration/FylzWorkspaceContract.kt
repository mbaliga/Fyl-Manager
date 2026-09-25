package io.github.mbaliga.fylz.integration

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Stable handoff from Fylz to development tools such as Fonebrew.
 *
 * Fylz remains the owner of browsing, storage providers, mounts/remotes and file operations.
 * Consumers receive only a user-granted SAF tree URI plus capability metadata. They must not
 * reproduce Fylz's filesystem/provider logic or convert document IDs into guessed filesystem paths.
 */
object FylzWorkspaceContract {
    const val CONTRACT_VERSION = 1
    const val EXTRA_CONTRACT_VERSION = "io.github.mbaliga.fylz.extra.CONTRACT_VERSION"
    const val EXTRA_WORKSPACE_URI = "io.github.mbaliga.fylz.extra.WORKSPACE_URI"
    const val EXTRA_DISPLAY_NAME = "io.github.mbaliga.fylz.extra.DISPLAY_NAME"
    const val EXTRA_READ_ONLY = "io.github.mbaliga.fylz.extra.READ_ONLY"

    /** Standard Android picker request. DocumentsUI may surface Fylz plus every other provider. */
    fun createPickWorkspaceIntent(initialUri: Uri? = null): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
            initialUri?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }

    /**
     * Validate a returned workspace grant before a consumer persists it.
     * The URI stays opaque; provider-specific document IDs are never interpreted as paths.
     */
    fun isUsableTreeUri(uri: Uri?): Boolean =
        uri != null &&
            uri.scheme == "content" &&
            !uri.authority.isNullOrBlank() &&
            runCatching { DocumentsContract.getTreeDocumentId(uri) }.isSuccess
}

/**
 * Serializable cross-app description of a Fylz-managed workspace.
 * [treeUri] is intentionally a String so this neutral payload can also be mirrored by non-Android
 * constellation clients without depending on android.net.Uri.
 */
data class FylzWorkspaceHandle(
    val contractVersion: Int = FylzWorkspaceContract.CONTRACT_VERSION,
    val treeUri: String,
    val displayName: String? = null,
    val readOnly: Boolean = false,
) {
    init {
        require(contractVersion == FylzWorkspaceContract.CONTRACT_VERSION) {
            "Unsupported Fylz workspace contract version: $contractVersion"
        }
        require(treeUri.startsWith("content://")) { "Fylz workspace handles must contain a content:// tree URI." }
    }
}
