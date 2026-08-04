package io.github.mbaliga.fylz.operations

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class DuplicateCleanupResult(
    val retained: Uri,
    val recycled: List<RecycleRecord>,
    val failed: List<DuplicateCleanupFailure>,
    val reclaimedBytes: Long,
)

data class DuplicateCleanupFailure(val uri: Uri, val message: String)

/**
 * Executes a verified duplicate cleanup exclusively through Fylz's non-destructive recycle path.
 * The canonical copy is never touched. Partial success is reported item-by-item and never presented
 * as an atomic transaction.
 */
class DuplicateCleanupService(
    context: Context,
    private val recycleBin: RecycleBinService = RecycleBinService(context.applicationContext),
) {
    private val appContext = context.applicationContext

    suspend fun execute(
        selection: DuplicateCleanupSelection,
        recycleRootUri: Uri,
        stopOnFailure: Boolean = true,
    ): DuplicateCleanupResult = withContext(Dispatchers.IO) {
        val validation = DuplicateCleanupPolicy.validate(selection)
        require(validation.valid) { validation.reason ?: "Invalid duplicate cleanup plan." }
        require(DocumentFile.fromSingleUri(appContext, selection.keep)?.exists() == true) {
            "The retained copy is no longer available. Cleanup was not started."
        }

        val recycled = mutableListOf<RecycleRecord>()
        val failed = mutableListOf<DuplicateCleanupFailure>()
        for (uri in selection.recycle.sortedBy(Uri::toString)) {
            coroutineContext.ensureActive()
            try {
                val document = DocumentFile.fromSingleUri(appContext, uri)
                    ?: error("The duplicate is unavailable.")
                require(document.exists() && document.isFile) { "The duplicate is no longer a readable file." }
                require(document.length() == selection.group.sizeBytes) {
                    "The duplicate size changed after verification; rescan before cleanup."
                }
                recycled += recycleBin.recycle(
                    sourceUri = uri,
                    originalParentUri = document.parentFile?.uri,
                    recycleRootUri = recycleRootUri,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                failed += DuplicateCleanupFailure(uri, failure.message ?: "Unable to recycle this duplicate.")
                if (stopOnFailure) break
            }
        }
        DuplicateCleanupResult(
            retained = selection.keep,
            recycled = recycled,
            failed = failed,
            reclaimedBytes = selection.group.sizeBytes * recycled.size,
        )
    }
}
