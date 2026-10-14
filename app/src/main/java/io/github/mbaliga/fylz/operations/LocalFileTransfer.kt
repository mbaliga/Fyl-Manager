package io.github.mbaliga.fylz.operations

import android.content.Context
import android.os.CancellationSignal
import android.os.FileUtils
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * P1.3 / A5: the fast path for a transfer where both ends are files Fylz's own
 * [FylzFilesDocumentsProvider] already serves. Never goes through a `ContentResolver` stream.
 */
class LocalFileTransfer(private val context: Context) : TransferEngine {

    override fun supports(source: DocNode, destinationDirectory: DocNode): Boolean =
        FylzFilesDocumentsProvider.fileFor(context, source.uri) != null &&
            FylzFilesDocumentsProvider.fileFor(context, destinationDirectory.uri) != null

    /**
     * `File.renameTo` alone, which the kernel only honours within one filesystem -- exactly the
     * "same `StorageVolume`" scope the brief asks for, and exactly why this needs no separate
     * same-volume check of its own: a cross-volume attempt simply returns false, same as any other
     * reason this can't move [source] directly, and the caller falls back to copy-then-delete.
     * Works for a whole directory tree in one call, same as a single file.
     */
    override suspend fun moveFile(
        source: DocNode,
        sourceParent: DocNode?,
        destinationDirectory: DocNode,
        requestedName: String,
    ): DocNode? = withContext(Dispatchers.IO) {
        val sourceFile = FylzFilesDocumentsProvider.fileFor(context, source.uri) ?: return@withContext null
        val destinationDirectoryFile = FylzFilesDocumentsProvider.fileFor(context, destinationDirectory.uri)
            ?: return@withContext null
        val targetFile = File(destinationDirectoryFile, requestedName)
        if (targetFile.exists()) return@withContext null
        if (!sourceFile.renameTo(targetFile)) return@withContext null
        destinationDirectory.findChild(context.contentResolver, requestedName)
    }

    /**
     * [android.os.FileUtils.copy] (sendfile/splice under the hood) into a same-directory temp
     * sibling, `fsync`ed before the commit rename to [requestedName] -- the actual bytes are never
     * routed through this process's own heap the way an `InputStream`/`OutputStream` loop would.
     *
     * Resumable: if a previous attempt's temp file is still there (this call was cancelled, or the
     * process died, without ever reaching the commit rename), the next call picks up from its
     * existing length rather than re-copying bytes already safely on disk -- both streams are
     * positioned there before [FileUtils.copy] starts. A genuine failure (not a cancellation)
     * deletes the temp file instead: resuming after a real I/O error would likely just repeat it.
     */
    override suspend fun copyFile(
        source: DocNode,
        destinationDirectory: DocNode,
        requestedName: String,
        onStaged: (DocNode) -> Unit,
        onProgress: (Long) -> Unit,
    ): DocNode = withContext(Dispatchers.IO) {
        val sourceFile = FylzFilesDocumentsProvider.fileFor(context, source.uri)
            ?: error("${source.name} is not local to this device.")
        val destinationDirectoryFile = FylzFilesDocumentsProvider.fileFor(context, destinationDirectory.uri)
            ?: error("The destination is not local to this device.")
        val targetFile = File(destinationDirectoryFile, requestedName)
        val tempFile = File(destinationDirectoryFile, "$requestedName$TEMP_SUFFIX")
        try {
            val resumeFromBytes = if (tempFile.isFile) tempFile.length() else 0L
            if (resumeFromBytes == 0L) tempFile.createNewFile()
            // Recorded as soon as the temp file exists, same as DocumentsTransfer's own
            // createChild-then-write ordering (P0.6) -- this is what a dead process's orphan
            // leaves behind, not targetFile, which never exists until the commit rename below.
            destinationDirectory.findChild(context.contentResolver, tempFile.name)?.let(onStaged)
            FileInputStream(sourceFile).use { input ->
                if (resumeFromBytes > 0) input.channel.position(resumeFromBytes)
                FileOutputStream(tempFile, /* append = */ resumeFromBytes > 0).use { output ->
                    FileUtils.copy(
                        input.fd,
                        output.fd,
                        /* signal = */ null as CancellationSignal?,
                        // Synchronous: FileUtils.copy already blocks this thread until done, so
                        // there is nothing to gain from a second thread for the listener callback.
                        /* executor = */ Executor { it.run() },
                        /* listener = */ FileUtils.ProgressListener { progressInThisCall ->
                            onProgress(resumeFromBytes + progressInThisCall)
                        },
                    )
                    output.fd.sync()
                }
            }
            check(tempFile.renameTo(targetFile)) { "Could not finish copying ${source.name}." }
        } catch (error: Throwable) {
            if (error !is CancellationException) tempFile.delete()
            throw error
        }
        targetFile.setLastModified(sourceFile.lastModified())
        destinationDirectory.findChild(context.contentResolver, requestedName)
            ?: error("${source.name} was copied but could not be read back.")
    }

    private companion object {
        const val TEMP_SUFFIX = ".fylz-ltmp"
    }
}
