package io.github.mbaliga.fylz.openwith

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat

/** A file another app handed to Fylz: where it is and what its sender said about it. */
data class IncomingFile(
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
    /** Null when the provider does not say. */
    val sizeBytes: Long?,
)

/**
 * Reading the files out of an incoming intent. Pure Intent parsing is in [urisFrom] so the rules
 * (which actions carry files, where) live in one place.
 */
object IncomingFiles {

    /**
     * The URIs an intent asks Fylz to open: VIEW/EDIT carry one in `data`, SEND one in
     * `EXTRA_STREAM`, SEND_MULTIPLE a list there. `ClipData` is the fallback, since some senders
     * only fill that. Order is preserved, duplicates dropped.
     */
    fun urisFrom(intent: Intent?): List<Uri> {
        intent ?: return emptyList()
        val found = LinkedHashSet<Uri>()
        when (intent.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> intent.data?.let(found::add)
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(found::add)
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(found::addAll)
            else -> return emptyList()
        }
        if (found.isEmpty()) intent.clipData?.let { clip -> found.addAll(clip.uris()) }
        return found.filter { it.scheme == "content" || it.scheme == "file" }
    }

    private fun ClipData.uris(): List<Uri> = (0 until itemCount).mapNotNull { getItemAt(it).uri }

    /** Resolves name, size and type for [uri]; always returns something usable. */
    fun resolve(context: Context, uri: Uri): IncomingFile {
        var name: String? = null
        var size: Long? = null
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex).takeIf { it >= 0 }
                    }
                }
        }
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val fallbackName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Untitled"
        return IncomingFile(uri, name?.takeIf { it.isNotBlank() } ?: fallbackName, mime, size)
    }
}
