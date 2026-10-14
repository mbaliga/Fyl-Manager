package io.github.mbaliga.fylz.archive

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.DocumentsContract
import io.github.mbaliga.fylz.data.ArchiveSpacePolicy
import io.github.mbaliga.fylz.decoder.ArchiveLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * One `Uri` in, one **seekable** read-only descriptor out (`docs/agent/DESIGN-M32-SEEKABLE-PFD.md`
 * section 2.3) -- the only way an archive reaches the decoder process, whose engine refuses
 * anything but a regular file. Resolution, in order:
 *
 * 1. `openFileDescriptor(uri, "r")`. A provider that cannot hand out a descriptor at all (a
 *    `FileNotFoundException`, which is also what a sub-range asset raises, an
 *    `UnsupportedOperationException`, a `SecurityException`) falls through to step 3 via
 *    `openInputStream`; if that fails too, [ArchiveSourceException.Unreadable].
 * 2. [isSeekable]: by default `statSize >= 0` -- AOSP's `getStatSize()` is exactly
 *    `S_ISREG(st_mode) ? st_size : -1`, the engine's own `fstat` test without `android.system.Os`.
 *    Seekable is [Resolved.Direct]: **no copy is made**, which is the whole point of M3.2. Not
 *    seekable (a third-party provider that streams through a pipe) closes that descriptor and
 *    stages.
 * 3. **Stage, with the space check first.** The provider's declared `COLUMN_SIZE` (or nothing) is
 *    checked against `limits.maxArchiveBytes` ([ArchiveSourceException.ArchiveTooLarge] before a
 *    byte is copied) and turned into [ArchiveSpacePolicy.stagingRequirement] bytes checked against
 *    [availableCacheBytes] ([ArchiveSourceException.InsufficientSpace], again before a byte is
 *    copied). Then the stream is copied into `cacheDir/archive-work/<uuid>/input` with a hard cap
 *    at `min(declared, maxArchiveBytes)`: a provider that keeps sending past what it declared is a
 *    [ArchiveSourceException.SizeMismatch] (as is one that stops short), past the limit an
 *    [ArchiveSourceException.ArchiveTooLarge]; when the size was unknown, [availableCacheBytes] is
 *    re-checked against the minimum headroom every [spaceRecheckIntervalBytes]. Any failure
 *    deletes the workspace. Success is [Resolved.Staged], whose [Resolved.close] deletes the copy.
 * 4. Workspaces a dead process left under `archive-work/` are swept by [ArchiveCacheSweeper]
 *    (M3.3a moved the 24 h sweep M3.2c ran on the first `resolve()` out of here, next to the two
 *    other archive caches it now keeps within budget).
 *
 * The lambdas are injectable for tests: Robolectric's `createPipe()` is file-backed and would
 * report a size, so the pipe-provider tests inject `isSeekable = { false }`; the space tests
 * inject [availableCacheBytes]. Everything the app can open by `Uri` reaches the engine only
 * through here, so the engine's `NotSeekable` is unreachable in practice.
 */
class ArchiveSource(
    private val context: Context,
    private val limits: ArchiveLimits,
    private val isSeekable: (ParcelFileDescriptor) -> Boolean = { it.statSize >= 0L },
    private val availableCacheBytes: () -> Long? = {
        runCatching { StatFs(context.cacheDir.path).availableBytes }.getOrNull()
    },
    private val spaceRecheckIntervalBytes: Long = SPACE_RECHECK_INTERVAL_BYTES,
) {
    /** A descriptor the engine can read: the caller closes it (and, for a staged copy, the copy). */
    sealed class Resolved : Closeable {
        abstract val pfd: ParcelFileDescriptor

        /** The provider's own descriptor, seekable: nothing was copied. */
        class Direct(override val pfd: ParcelFileDescriptor) : Resolved() {
            override fun close() {
                runCatching { pfd.close() }
            }
        }

        /** A copy under [workspace], because the provider's descriptor could not seek. */
        class Staged(override val pfd: ParcelFileDescriptor, val workspace: File) : Resolved() {
            override fun close() {
                runCatching { pfd.close() }
                workspace.deleteRecursively()
            }
        }
    }

    /** The space this source's cache would have for a staged copy, for callers' own display. */
    internal fun availableCacheBytes(): Long? = availableCacheBytes.invoke()

    /** Resolves [uri] to a seekable descriptor; throws [ArchiveSourceException] and nothing else of its own. */
    @Throws(ArchiveSourceException::class)
    suspend fun resolve(uri: Uri): Resolved = withContext(Dispatchers.IO) {
        val direct = openDescriptor(uri)
        if (direct != null) {
            if (isSeekable(direct)) return@withContext Resolved.Direct(direct)
            runCatching { direct.close() }
        }
        stage(uri)
    }

    /** Step 1: the provider's descriptor, or `null` when it cannot provide one. */
    private fun openDescriptor(uri: Uri): ParcelFileDescriptor? = try {
        context.contentResolver.openFileDescriptor(uri, "r")
    } catch (e: FileNotFoundException) {
        null
    } catch (e: UnsupportedOperationException) {
        null
    } catch (e: SecurityException) {
        null
    }

    /** Step 3. */
    private suspend fun stage(uri: Uri): Resolved.Staged {
        val declared = declaredSize(uri)
        if (declared != null && declared > limits.maxArchiveBytes) {
            throw ArchiveSourceException.ArchiveTooLarge(limits.maxArchiveBytes)
        }
        val required = ArchiveSpacePolicy.stagingRequirement(declared, limits.maxArchiveBytes)
        val available = availableCacheBytes()
        val decision = ArchiveSpacePolicy.evaluate(required, available, "temporary storage")
        if (!decision.allowed) throw ArchiveSourceException.InsufficientSpace(required, available)

        val input = try {
            context.contentResolver.openInputStream(uri)
                ?: throw ArchiveSourceException.Unreadable(FileNotFoundException("The provider returned no stream for $uri"))
        } catch (e: ArchiveSourceException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ArchiveSourceException.Unreadable(e)
        }
        val workspace = File(workRoot(), UUID.randomUUID().toString()).apply { mkdirs() }
        val copy = File(workspace, "input")
        try {
            input.use { stream -> copy.outputStream().use { out -> copyBounded(stream, out, declared) } }
            val pfd = ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY)
            return Resolved.Staged(pfd, workspace)
        } catch (failure: Throwable) {
            workspace.deleteRecursively()
            throw when (failure) {
                is ArchiveSourceException, is kotlinx.coroutines.CancellationException -> failure
                // A provider's stream that fails mid-copy, however it fails (an IOException, or a
                // RuntimeException from a misbehaving provider), leaves the archive unreadable.
                is IOException, is RuntimeException -> ArchiveSourceException.Unreadable(failure)
                else -> failure
            }
        }
    }

    /**
     * The copy loop with its three caps: the declared size (over *or* under is a
     * [ArchiveSourceException.SizeMismatch] -- a provider whose `COLUMN_SIZE` disagrees with its
     * stream is not one to trust), `maxArchiveBytes`, and, when the size was unknown, the
     * periodic headroom re-check.
     */
    private suspend fun copyBounded(input: InputStream, output: java.io.OutputStream, declared: Long?) {
        val cap = minOf(declared ?: Long.MAX_VALUE, limits.maxArchiveBytes)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        var sinceRecheck = 0L
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > cap) {
                if (declared != null && total > declared) throw ArchiveSourceException.SizeMismatch(declared, total)
                throw ArchiveSourceException.ArchiveTooLarge(limits.maxArchiveBytes)
            }
            output.write(buffer, 0, count)
            if (declared == null) {
                sinceRecheck += count
                if (sinceRecheck >= spaceRecheckIntervalBytes) {
                    sinceRecheck = 0L
                    val available = availableCacheBytes()
                    if (available != null && available >= 0L && available < ArchiveSpacePolicy.MIN_TEMPORARY_HEADROOM) {
                        throw ArchiveSourceException.InsufficientSpace(ArchiveSpacePolicy.MIN_TEMPORARY_HEADROOM, available)
                    }
                }
            }
        }
        output.flush()
        if (declared != null && total != declared) throw ArchiveSourceException.SizeMismatch(declared, total)
    }

    /** `DocumentsContract.Document.COLUMN_SIZE` from a one-column query, `null` when the provider does not say. */
    private fun declaredSize(uri: Uri): Long? = runCatching {
        // The Bundle-taking overload: DocumentsProvider's classic five-argument query throws
        // "Pre-Android-O query format not supported" (P0.1), so nothing in this app uses it.
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null as Bundle?, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it >= 0L } else null
            }
    }.getOrNull()

    private fun workRoot(): File = File(context.cacheDir, WORK_DIRECTORY)

    companion object {
        /** Where staged copies live; the same directory `ArchiveService`'s own workspaces use, so
         * the backup/transfer exclusion rules that already name it keep covering it. */
        const val WORK_DIRECTORY = "archive-work"

        /** How often, while copying a stream of unknown size, the cache headroom is re-checked. */
        const val SPACE_RECHECK_INTERVAL_BYTES = 64L * 1024L * 1024L
    }
}

/**
 * Why [ArchiveSource.resolve] could not produce a descriptor. Each is a condition the UI names
 * for the user; none is the engine's verdict about the archive's contents.
 */
sealed class ArchiveSourceException(message: String) : IOException(message) {
    /** Neither a descriptor nor a stream could be opened for the `Uri`. */
    class Unreadable(cause: Throwable) : ArchiveSourceException("Unable to read the archive: ${cause.message ?: cause.javaClass.simpleName}") {
        init {
            initCause(cause)
        }
    }

    /** Staging would need [required] bytes of cache and only [available] (or unknown) is there. */
    class InsufficientSpace(val required: Long, val available: Long?) : ArchiveSourceException(
        "Not enough temporary storage to copy this archive: $required bytes needed" +
            (available?.let { ", $it available" } ?: "") + ".",
    )

    /** The provider's declared size, or the bytes it sent, exceed `ArchiveLimits.maxArchiveBytes`. */
    class ArchiveTooLarge(val limit: Long) : ArchiveSourceException("The archive exceeds the $limit-byte input limit.")

    /** The provider declared [declared] bytes and sent [actual]. */
    class SizeMismatch(val declared: Long, val actual: Long) : ArchiveSourceException(
        "The provider declared $declared bytes but sent $actual; the archive was not copied.",
    )
}
