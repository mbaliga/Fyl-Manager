package io.github.mbaliga.fylz.operations

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class DuplicateGroup(val sha256: String, val sizeBytes: Long, val items: List<Uri>)

data class BatchRenamePlan(val source: Uri, val oldName: String, val newName: String)

class FileTools(private val context: Context) {
    suspend fun findDuplicates(
        uris: List<Uri>,
        maxBytesPerFile: Long = 2L * 1024L * 1024L * 1024L,
    ): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        val files = uris.mapNotNull { uri ->
            DocumentFile.fromSingleUri(context, uri)
                ?.takeIf { it.isFile && it.exists() && it.length() in 0..maxBytesPerFile }
                ?.let { uri to it.length() }
        }
        files.groupBy { it.second }
            .filterValues { it.size > 1 }
            .values
            .flatMap { sameSize ->
                sameSize.groupBy { (uri, _) -> sha256(uri) }
                    .filterValues { it.size > 1 }
                    .map { (hash, items) ->
                        DuplicateGroup(hash, items.first().second, items.map { it.first })
                    }
            }
            .sortedByDescending(DuplicateGroup::sizeBytes)
    }

    fun planBatchRename(
        items: List<Pair<Uri, String>>,
        prefix: String,
        startAt: Int = 1,
        padding: Int = 2,
    ): List<BatchRenamePlan> {
        require(items.isNotEmpty())
        require(prefix.isNotBlank())
        require(startAt >= 0)
        require(padding in 1..8)
        return items.mapIndexed { index, (uri, oldName) ->
            val extension = oldName.substringAfterLast('.', "").takeIf { oldName.contains('.') }
            val base = "$prefix${(startAt + index).toString().padStart(padding, '0')}"
            BatchRenamePlan(uri, oldName, if (extension == null) base else "$base.$extension")
        }
    }

    suspend fun executeBatchRename(plans: List<BatchRenamePlan>): List<Uri> =
        withContext(Dispatchers.IO) {
            plans.map { plan ->
                coroutineContext.ensureActive()
                val document = DocumentFile.fromSingleUri(context, plan.source)
                    ?: error("Unable to open ${plan.oldName}.")
                require(document.renameTo(plan.newName)) { "Unable to rename ${plan.oldName}." }
                document.uri
            }
        }

    private suspend fun sha256(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = context.contentResolver.openInputStream(uri)
            ?: error("Unable to read a file for duplicate detection.")
        input.use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                coroutineContext.ensureActive()
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
