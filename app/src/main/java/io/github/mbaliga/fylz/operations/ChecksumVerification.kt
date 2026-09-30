package io.github.mbaliga.fylz.operations

import android.content.ContentResolver
import android.net.Uri
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Thrown when [verifyChecksum] finds the destination does not match the source. A distinct type,
 * rather than a plain [IllegalStateException]/`check()`, so [FileOperationService.transfer]'s
 * whole-operation catch -- which sets an item's `errorCode` from the failing exception's own class
 * name -- reports something that actually says what went wrong (`ChecksumMismatchException`),
 * same mechanism every other unexpected failure there already uses.
 */
class ChecksumMismatchException(message: String) : Exception(message)

/** [uri]'s SHA-256, streamed so the whole file is never held in memory at once. */
internal suspend fun sha256Hex(resolver: ContentResolver, uri: Uri): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val input = resolver.openInputStream(uri) ?: error("Unable to read $uri for verification")
    input.use { stream ->
        val buffer = ByteArray(CHECKSUM_BUFFER_BYTES)
        while (true) {
            coroutineContext.ensureActive()
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Confirms [written] is byte-for-byte identical to [source] by SHA-256, not just the same length
 * -- the existing size check every copy already gets. P1.4's own reason to exist: a size match
 * does not rule out silent corruption on a flaky SD card or a lossy network path.
 *
 * @return the matching hash, to store in `operation_items.sha256`.
 * @throws ChecksumMismatchException if the hashes differ.
 */
internal suspend fun verifyChecksum(resolver: ContentResolver, source: DocNode, written: DocNode): String {
    val sourceHash = sha256Hex(resolver, source.uri)
    val writtenHash = sha256Hex(resolver, written.uri)
    if (sourceHash != writtenHash) {
        throw ChecksumMismatchException("Verification failed for ${source.name}: the copy does not match the original.")
    }
    return writtenHash
}

private const val CHECKSUM_BUFFER_BYTES = 256 * 1024
