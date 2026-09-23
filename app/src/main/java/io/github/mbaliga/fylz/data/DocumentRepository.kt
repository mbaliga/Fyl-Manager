package io.github.mbaliga.fylz.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import io.github.mbaliga.fylz.history.FileHistoryReason
import io.github.mbaliga.fylz.history.FileHistoryStore
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.FolderLocation
import io.github.mbaliga.fylz.util.FileType
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** P0.7: editor loads and saves text strictly -- at most [TEXT_EDIT_LIMIT_BYTES] bytes, decoded
 * as UTF-8 with malformed input reported rather than silently replaced, so [DocumentRepository]
 * can tell the editor exactly why a file can't be edited instead of corrupting it on save. */
const val TEXT_EDIT_LIMIT_BYTES: Int = 512 * 1024

enum class LineEnding { LF, CRLF, MIXED, NONE }

data class TextContent(
    val value: String,
    val truncated: Boolean,
    val encodingOk: Boolean,
    val hasBom: Boolean,
    val lineEnding: LineEnding,
) {
    /** False when the editor must refuse to open this content for editing: a truncated read (the
     * file is over [TEXT_EDIT_LIMIT_BYTES]) or a strict-decode failure (not valid UTF-8) both mean
     * writing [value] back would not reproduce the file. Reading it for preview is still fine. */
    val editable: Boolean get() = !truncated && encodingOk
}

sealed interface SaveResult {
    data object Success : SaveResult
    data class Failed(val message: String) : SaveResult
}

class DocumentRepository(context: Context) {
    private val appContext: Context = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val history = FileHistoryStore(appContext)

    fun persistTreePermission(treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.takePersistableUriPermission(treeUri, flags) }
    }

    suspend fun rootLocation(treeUri: Uri): FolderLocation = withContext(Dispatchers.IO) {
        val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        FolderLocation(
            uri = rootDocumentUri,
            name = resolveDisplayName(rootDocumentUri)
                ?: DocumentsContract.getTreeDocumentId(treeUri).substringAfterLast('/'),
        )
    }

    suspend fun listChildren(treeUri: Uri, folderUri: Uri): List<FileEntry> =
        withContext(Dispatchers.IO) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                DocumentsContract.getDocumentId(folderUri),
            )
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_FLAGS,
            )

            val entries = mutableListOf<FileEntry>()
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val flagsIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_FLAGS)

                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(idIndex)
                    val name = cursor.getString(nameIndex) ?: "Untitled"
                    val mimeType = cursor.getString(mimeIndex) ?: "application/octet-stream"
                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                    val isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                    entries += FileEntry(
                        uri = documentUri,
                        name = name,
                        mimeType = mimeType,
                        // COLUMN_SIZE is meaningless for a directory, but some OEM documents
                        // providers report the raw filesystem entry size anyway (a few KiB of
                        // junk — observed as "3.4 KiB" on every folder on a RedMagic). Our own
                        // FylzFilesDocumentsProvider correctly reports null; normalize foreign
                        // providers to the same contract so the UI never renders nonsense.
                        sizeBytes = cursor.longOrNull(sizeIndex).takeUnless { isDirectory },
                        lastModifiedMillis = cursor.longOrNull(modifiedIndex),
                        flags = cursor.intOrZero(flagsIndex),
                        kind = FileType.classify(name, mimeType),
                    )
                }
            }

            entries.sortedWith(
                compareByDescending<FileEntry> { it.isDirectory }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
            )
        }

    suspend fun createDirectory(parentUri: Uri, name: String): Uri = withContext(Dispatchers.IO) {
        require(name.isNotBlank()) { "Folder name is required." }
        DocumentsContract.createDocument(
            resolver,
            parentUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            name.trim(),
        ) ?: error("The provider could not create the folder.")
    }

    suspend fun createFile(parentUri: Uri, name: String, mimeType: String): Uri =
        withContext(Dispatchers.IO) {
            require(name.isNotBlank()) { "File name is required." }
            DocumentsContract.createDocument(resolver, parentUri, mimeType, name.trim())
                ?: error("The provider could not create the file.")
        }

    suspend fun rename(uri: Uri, newName: String): Uri = withContext(Dispatchers.IO) {
        require(newName.isNotBlank()) { "A new name is required." }
        DocumentsContract.renameDocument(resolver, uri, newName.trim())
            ?: error("The provider could not rename the item.")
    }

    suspend fun copyStream(sourceUri: Uri, destinationUri: Uri): Long = withContext(Dispatchers.IO) {
        history.capture(destinationUri, FileHistoryReason.BEFORE_WRITE)
        val input = resolver.openInputStream(sourceUri) ?: error("Unable to read the source.")
        val output = resolver.openOutputStream(destinationUri, "w")
            ?: error("Unable to write the destination.")
        var total = 0L
        input.use { source ->
            output.use { destination ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    destination.write(buffer, 0, count)
                    total += count
                }
                destination.flush()
            }
        }
        total
    }

    suspend fun readSignature(uri: Uri, maxBytes: Int = 64): ByteArray =
        withContext(Dispatchers.IO) {
            require(maxBytes in 1..4_096)
            val input = resolver.openInputStream(uri) ?: return@withContext byteArrayOf()
            input.use { stream ->
                val buffer = ByteArray(maxBytes)
                val count = stream.read(buffer)
                if (count <= 0) byteArrayOf() else buffer.copyOf(count)
            }
        }

    /** Reads at most [maxBytes] raw bytes and strictly decodes them as UTF-8: a leading BOM is
     * detected and stripped from [TextContent.value] (callers that want it preserved on save pass
     * [TextContent.hasBom] back to [writeText]), and malformed input is reported rather than
     * silently replaced, so [TextContent.editable] can tell the caller a save would corrupt the
     * file before it ever tries. A truncated read never lets the last, possibly-incomplete UTF-8
     * character sequence reach the decoder as garbage. */
    suspend fun readText(uri: Uri, maxBytes: Int = TEXT_EDIT_LIMIT_BYTES): TextContent =
        withContext(Dispatchers.IO) {
            val input = resolver.openInputStream(uri)
                ?: error("The selected provider did not return a readable stream.")
            val (rawBytes, truncated) = input.use { stream -> readBounded(stream, maxBytes) }
            val hasBom = rawBytes.size >= UTF8_BOM.size &&
                UTF8_BOM.indices.all { rawBytes[it] == UTF8_BOM[it] }
            val contentBytes = if (hasBom) rawBytes.copyOfRange(UTF8_BOM.size, rawBytes.size) else rawBytes
            val safeBytes = if (truncated) trimIncompleteUtf8Tail(contentBytes) else contentBytes
            val (value, encodingOk) = decodeUtf8(safeBytes)
            TextContent(
                value = value,
                truncated = truncated,
                encodingOk = encodingOk,
                hasBom = hasBom,
                lineEnding = detectLineEnding(value),
            )
        }

    /** Writes [value] back as UTF-8 (re-adding the BOM first when [hasBom] is true, so a file that
     * had one round-trips byte-identical), verifying the write by re-querying the provider's
     * reported size afterward. A pre-write copy is kept in the cache for the duration of the save
     * (separate from [FileHistoryStore], which is an opt-in, permanent, size-capped feature and
     * not a substitute for this always-on safety net): a failed write or a size mismatch restores
     * it and reports the failure, rather than leaving the file corrupted or silently wrong. Never
     * throws -- every outcome, including an unexpected exception, comes back as a [SaveResult]. */
    suspend fun writeText(uri: Uri, value: String, hasBom: Boolean = false): SaveResult =
        withContext(Dispatchers.IO) {
            try {
                history.capture(uri, FileHistoryReason.BEFORE_WRITE)
                val payloadBytes = (if (hasBom) UTF8_BOM_PREFIX + value else value).toByteArray(Charsets.UTF_8)
                val safetyCopy = runCatching { cacheSafetyCopy(uri) }.getOrNull()
                try {
                    val output = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
                        ?: resolver.openOutputStream(uri, "w")
                    if (output == null) {
                        SaveResult.Failed("The selected provider did not return a writable stream.")
                    } else {
                        val writeOutcome = runCatching { output.use { it.write(payloadBytes) } }
                        if (writeOutcome.isFailure) {
                            safetyCopy?.let { runCatching { restoreFromCache(uri, it) } }
                            SaveResult.Failed(
                                writeOutcome.exceptionOrNull()?.message ?: "Unable to save the file.",
                            )
                        } else {
                            val actualBytes = querySize(uri)
                            if (actualBytes != null && actualBytes != payloadBytes.size.toLong()) {
                                val restored = safetyCopy?.let {
                                    runCatching { restoreFromCache(uri, it) }.isSuccess
                                } == true
                                SaveResult.Failed(
                                    if (restored) {
                                        "The save didn't verify, so your previous content was restored."
                                    } else {
                                        "The save didn't verify, and the previous content could not be restored."
                                    },
                                )
                            } else {
                                SaveResult.Success
                            }
                        }
                    }
                } finally {
                    safetyCopy?.delete()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SaveResult.Failed(e.message ?: "Unable to save the file.")
            }
        }

    suspend fun resolveDisplayName(uri: Uri): String? = withContext(Dispatchers.IO) {
        resolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    /** Copies [uri]'s current content into a fresh file under the app cache, to be restored by
     * [restoreFromCache] if the write that follows fails or doesn't verify. Caller deletes it once
     * the save is settled either way -- it's a transient hold for this one save, not history. */
    private fun cacheSafetyCopy(uri: Uri): File {
        val dir = File(appContext.cacheDir, "editor-safety").apply { mkdirs() }
        val file = File.createTempFile("save-", ".tmp", dir)
        val input = resolver.openInputStream(uri)
        if (input == null) {
            file.delete()
            error("Unable to read the current file for a safety copy.")
        }
        input.use { source -> file.outputStream().use { destination -> source.copyTo(destination) } }
        return file
    }

    private fun restoreFromCache(uri: Uri, cacheFile: File) {
        val output = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: resolver.openOutputStream(uri, "w")
            ?: error("The selected provider did not return a writable stream.")
        output.use { destination -> cacheFile.inputStream().use { it.copyTo(destination) } }
    }

    private fun querySize(uri: Uri): Long? =
        // The Bundle-based overload, not the legacy 5-arg one: DocumentsProvider's own legacy
        // query() unconditionally throws ("Pre-Android-O query format not supported.") when
        // invoked directly rather than through ContentProvider's real bridging -- see DocNode's
        // own queries, which hit the exact same thing.
        resolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_SIZE),
            null as android.os.Bundle?,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }

    private fun android.database.Cursor.longOrNull(index: Int): Long? =
        if (index < 0 || isNull(index)) null else getLong(index)

    private fun android.database.Cursor.intOrZero(index: Int): Int =
        if (index < 0 || isNull(index)) 0 else getInt(index)

    companion object {
        private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        // Derived from the byte form above rather than a string literal, so the source file
        // itself never has to contain a literal BOM character.
        private val UTF8_BOM_PREFIX = String(UTF8_BOM, Charsets.UTF_8)

        /** Reads at most [maxBytes] from [stream], returning what was read and whether more was
         * available (the read stopped because the cap was hit, not because the stream ended). */
        private fun readBounded(stream: InputStream, maxBytes: Int): Pair<ByteArray, Boolean> {
            val buffer = ByteArrayOutputStream(minOf(maxBytes, 65_536))
            val chunk = ByteArray(8_192)
            var total = 0
            var truncated = false
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) break
                val remaining = maxBytes - total
                if (remaining <= 0) {
                    truncated = true
                    break
                }
                val accepted = minOf(read, remaining)
                buffer.write(chunk, 0, accepted)
                total += accepted
                if (accepted < read) {
                    truncated = true
                    break
                }
            }
            return buffer.toByteArray() to truncated
        }

        /** Drops a trailing UTF-8 sequence cut short by a byte-capped read, so [decodeUtf8] never
         * sees a lead byte with fewer continuation bytes than it declares -- which strict decoding
         * would otherwise (correctly, but misleadingly) report as malformed input. */
        private fun trimIncompleteUtf8Tail(bytes: ByteArray): ByteArray {
            var start = bytes.size
            var continuationBytes = 0
            while (start > 0 && (bytes[start - 1].toInt() and 0xC0) == 0x80) {
                start--
                continuationBytes++
                if (continuationBytes >= 3) break // a valid lead byte is at most 3 bytes back
            }
            if (start == 0) return bytes
            val lead = bytes[start - 1].toInt() and 0xFF
            val expectedLength = when {
                lead and 0x80 == 0x00 -> 1
                lead and 0xE0 == 0xC0 -> 2
                lead and 0xF0 == 0xE0 -> 3
                lead and 0xF8 == 0xF0 -> 4
                else -> return bytes // not a valid lead byte either; leave it for strict decode to flag
            }
            return if (start - 1 + expectedLength > bytes.size) bytes.copyOfRange(0, start - 1) else bytes
        }

        /** Strict UTF-8 decode first; on malformed or unmappable input, falls back to a lenient,
         * replacement-character decode so preview still has something to show, with [Pair.second]
         * (`encodingOk`) reporting which one actually happened. */
        private fun decodeUtf8(bytes: ByteArray): Pair<String, Boolean> {
            val strict = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return try {
                strict.decode(ByteBuffer.wrap(bytes)).toString() to true
            } catch (e: CharacterCodingException) {
                val lenient = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                lenient.decode(ByteBuffer.wrap(bytes)).toString() to false
            }
        }

        private fun detectLineEnding(value: String): LineEnding {
            var sawCrLf = false
            var sawLoneLf = false
            var i = 0
            while (i < value.length) {
                when {
                    value[i] == '\r' && i + 1 < value.length && value[i + 1] == '\n' -> {
                        sawCrLf = true
                        i += 2
                    }
                    value[i] == '\n' -> {
                        sawLoneLf = true
                        i += 1
                    }
                    else -> i += 1
                }
            }
            return when {
                sawCrLf && sawLoneLf -> LineEnding.MIXED
                sawCrLf -> LineEnding.CRLF
                sawLoneLf -> LineEnding.LF
                else -> LineEnding.NONE
            }
        }
    }
}
