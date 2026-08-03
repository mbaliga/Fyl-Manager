package io.github.mbaliga.fylz.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.DigestInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.coroutineContext

class BackupService(
    private val context: Context,
    private val store: BackupStore = BackupStore(context),
) {
    suspend fun runBackup(
        planId: String,
        trigger: BackupTrigger = BackupTrigger.MANUAL,
        onProgress: (BackupProgress) -> Unit = {},
    ): BackupRunRecord = withContext(Dispatchers.IO) {
        val plan = store.plan(planId) ?: error("Backup plan not found.")
        val run = BackupRunRecord(
            planId = plan.id,
            trigger = trigger,
            status = BackupRunStatus.RUNNING,
        )
        store.putRun(run)

        val source = DocumentFile.fromTreeUri(context, Uri.parse(plan.sourceTreeUri))
            ?: return@withContext finish(run, BackupRunStatus.FAILED, "Source folder is unavailable.")
        val destination = DocumentFile.fromTreeUri(context, Uri.parse(plan.destinationTreeUri))
            ?: return@withContext finish(run, BackupRunStatus.FAILED, "Backup destination is unavailable.")
        if (!source.isDirectory || !source.canRead()) {
            return@withContext finish(run, BackupRunStatus.FAILED, "Source folder is not readable.")
        }
        if (!destination.isDirectory || !destination.canWrite()) {
            return@withContext finish(run, BackupRunStatus.FAILED, "Backup destination is not writable.")
        }
        if (source.uri == destination.uri) {
            return@withContext finish(run, BackupRunStatus.FAILED, "Source and backup destination must be different folders.")
        }

        val snapshotId = UUID.randomUUID().toString()
        val incompleteName = ".fylz-backup-incomplete-$snapshotId"
        val staging = destination.createDirectory(incompleteName)
            ?: return@withContext finish(run, BackupRunStatus.FAILED, "Unable to create backup staging folder.")

        try {
            val state = CopyState()
            source.listFiles().sortedBy { it.name.orEmpty().lowercase(Locale.ROOT) }.forEach { child ->
                copyIntoSnapshot(
                    source = child,
                    destination = staging,
                    relativePath = safeSegment(child.name ?: "untitled"),
                    state = state,
                    onProgress = onProgress,
                    depth = 1,
                )
            }
            val createdAt = System.currentTimeMillis()
            val manifest = BackupManifest(
                backupId = snapshotId,
                planId = plan.id,
                sourceTreeUri = plan.sourceTreeUri,
                sourceDisplayName = source.name ?: plan.name,
                createdAtMillis = createdAt,
                entries = state.entries.sortedBy(BackupManifestEntry::relativePath),
            )
            val manifestBytes = encodeManifest(manifest).toByteArray(Charsets.UTF_8)
            val manifestHash = sha256(manifestBytes)
            val manifestFile = staging.createFile("application/json", MANIFEST_FILE)
                ?: error("Unable to create backup manifest.")
            context.contentResolver.openOutputStream(manifestFile.uri, "w")?.use { output ->
                output.write(manifestBytes)
                output.flush()
            } ?: error("Unable to write backup manifest.")

            val finalName = uniqueSnapshotName(
                destination = destination,
                sourceName = source.name ?: plan.name,
                createdAtMillis = createdAt,
            )
            check(staging.renameTo(finalName)) {
                "Backup data is complete, but the provider could not finalize its folder name."
            }
            val snapshot = BackupSnapshotRecord(
                id = snapshotId,
                planId = plan.id,
                snapshotTreeUri = staging.uri.toString(),
                displayName = staging.name ?: finalName,
                sourceDisplayName = source.name ?: plan.name,
                createdAtMillis = createdAt,
                fileCount = state.fileCount,
                directoryCount = state.directoryCount,
                totalBytes = state.totalBytes,
                manifestSha256 = manifestHash,
            )
            store.putSnapshot(snapshot)
            pruneSnapshots(plan)
            if (trigger == BackupTrigger.MEDIA_THRESHOLD) {
                val media = store.mediaState(plan.id)
                store.putMediaState(plan.id, media.copy(pendingNewMedia = 0))
            }
            finish(
                run = run,
                status = BackupRunStatus.SUCCEEDED,
                message = "Backed up ${state.fileCount} files.",
                snapshotId = snapshot.id,
            )
        } catch (cancelled: CancellationException) {
            val removed = staging.delete()
            val status = if (removed) BackupRunStatus.CANCELLED else BackupRunStatus.NEEDS_ATTENTION
            val message = if (removed) "Backup cancelled." else "Backup cancelled; incomplete staging data could not be removed."
            finish(run, status, message)
            throw cancelled
        } catch (failure: Throwable) {
            val removed = staging.delete()
            finish(
                run = run,
                status = if (removed) BackupRunStatus.FAILED else BackupRunStatus.NEEDS_ATTENTION,
                message = if (removed) {
                    failure.message ?: "Backup failed."
                } else {
                    "${failure.message ?: "Backup failed."} Incomplete staging data could not be removed."
                },
            )
        }
    }

    suspend fun restoreSnapshot(
        snapshotId: String,
        destinationTreeUri: Uri,
        onProgress: (BackupProgress) -> Unit = {},
    ): BackupRestoreResult = withContext(Dispatchers.IO) {
        val snapshot = store.snapshots().firstOrNull { it.id == snapshotId }
            ?: return@withContext BackupRestoreResult(BackupRestoreStatus.INVALID_BACKUP, message = "Backup record not found.")
        val snapshotRoot = DocumentFile.fromSingleUri(context, Uri.parse(snapshot.snapshotTreeUri))
            ?: return@withContext BackupRestoreResult(BackupRestoreStatus.SOURCE_UNAVAILABLE, message = "Backup folder is unavailable.")
        val destination = DocumentFile.fromTreeUri(context, destinationTreeUri)
            ?: return@withContext BackupRestoreResult(BackupRestoreStatus.DESTINATION_UNAVAILABLE, message = "Restore destination is unavailable.")
        if (!snapshotRoot.isDirectory || !snapshotRoot.canRead()) {
            return@withContext BackupRestoreResult(BackupRestoreStatus.SOURCE_UNAVAILABLE, message = "Backup folder is not readable.")
        }
        if (!destination.isDirectory || !destination.canWrite()) {
            return@withContext BackupRestoreResult(BackupRestoreStatus.DESTINATION_UNAVAILABLE, message = "Restore destination is not writable.")
        }

        val manifestFile = snapshotRoot.findFile(MANIFEST_FILE)
            ?: return@withContext BackupRestoreResult(BackupRestoreStatus.INVALID_BACKUP, message = "Backup manifest is missing.")
        val manifestBytes = context.contentResolver.openInputStream(manifestFile.uri)?.use { it.readBytes() }
            ?: return@withContext BackupRestoreResult(BackupRestoreStatus.INVALID_BACKUP, message = "Backup manifest is unreadable.")
        if (sha256(manifestBytes) != snapshot.manifestSha256) {
            return@withContext BackupRestoreResult(BackupRestoreStatus.INVALID_BACKUP, message = "Backup manifest verification failed.")
        }
        val manifest = decodeManifest(manifestBytes.toString(Charsets.UTF_8))
            ?: return@withContext BackupRestoreResult(BackupRestoreStatus.INVALID_BACKUP, message = "Backup manifest is malformed.")
        if (manifest.backupId != snapshot.id || manifest.planId != snapshot.planId) {
            return@withContext BackupRestoreResult(BackupRestoreStatus.INVALID_BACKUP, message = "Backup manifest does not match this snapshot.")
        }

        val restoreName = uniqueDirectoryName(destination, "Restored ${snapshot.sourceDisplayName}")
        val restoreRoot = destination.createDirectory(restoreName)
            ?: return@withContext BackupRestoreResult(BackupRestoreStatus.DESTINATION_UNAVAILABLE, message = "Unable to create restore folder.")
        var restoredFiles = 0
        var restoredBytes = 0L
        try {
            val entries = manifest.entries.sortedWith(
                compareBy<BackupManifestEntry> { it.relativePath.count { char -> char == '/' } }
                    .thenByDescending(BackupManifestEntry::directory),
            )
            entries.forEach { entry ->
                coroutineContext.ensureActive()
                validateRelativePath(entry.relativePath)
                val parent = ensureParent(restoreRoot, entry.relativePath.substringBeforeLast('/', ""))
                val name = entry.relativePath.substringAfterLast('/')
                if (entry.directory) {
                    if (parent.findFile(name) == null) {
                        parent.createDirectory(name) ?: error("Unable to create ${entry.relativePath}.")
                    }
                } else {
                    val sourceFile = findRelative(snapshotRoot, entry.relativePath)
                        ?: error("Backup file ${entry.relativePath} is missing.")
                    val target = parent.createFile(entry.mimeType ?: "application/octet-stream", name)
                        ?: error("Unable to restore ${entry.relativePath}.")
                    val actual = copyAndHash(sourceFile.uri, target.uri) { bytes ->
                        onProgress(BackupProgress(name, restoredFiles, restoredBytes + bytes))
                    }
                    if (actual.bytes != entry.sizeBytes || actual.sha256 != entry.sha256) {
                        target.delete()
                        error("Backup file verification failed for ${entry.relativePath}.")
                    }
                    restoredFiles += 1
                    restoredBytes += actual.bytes
                }
            }
            BackupRestoreResult(
                status = BackupRestoreStatus.RESTORED,
                restoredRootUri = restoreRoot.uri.toString(),
                restoredFiles = restoredFiles,
                restoredBytes = restoredBytes,
            )
        } catch (failure: Throwable) {
            val removed = restoreRoot.delete()
            BackupRestoreResult(
                status = if (removed) BackupRestoreStatus.FAILED_ROLLED_BACK else BackupRestoreStatus.FAILED_ROLLBACK_INCOMPLETE,
                restoredRootUri = if (removed) null else restoreRoot.uri.toString(),
                restoredFiles = restoredFiles,
                restoredBytes = restoredBytes,
                message = failure.message,
            )
        }
    }

    fun snapshots(planId: String? = null): List<BackupSnapshotRecord> = store.snapshots(planId)
    fun runs(planId: String? = null): List<BackupRunRecord> = store.runs(planId)

    fun deleteSnapshot(snapshotId: String): Boolean {
        val snapshot = store.snapshots().firstOrNull { it.id == snapshotId } ?: return false
        val file = DocumentFile.fromSingleUri(context, Uri.parse(snapshot.snapshotTreeUri)) ?: return false
        if (!file.delete()) return false
        store.removeSnapshot(snapshotId)
        return true
    }

    private suspend fun copyIntoSnapshot(
        source: DocumentFile,
        destination: DocumentFile,
        relativePath: String,
        state: CopyState,
        onProgress: (BackupProgress) -> Unit,
        depth: Int,
    ) {
        coroutineContext.ensureActive()
        require(depth <= MAX_DEPTH) { "Source folder nesting exceeds the backup safety limit." }
        require(state.entries.size < MAX_ENTRIES) { "Source folder contains too many entries for one backup." }
        validateRelativePath(relativePath)
        if (source.isDirectory) {
            val target = destination.createDirectory(source.name ?: "untitled")
                ?: error("Unable to create $relativePath.")
            state.directoryCount += 1
            state.entries += BackupManifestEntry(
                relativePath = relativePath,
                directory = true,
                mimeType = null,
                sizeBytes = 0L,
                sha256 = null,
                lastModifiedMillis = source.lastModified().takeIf { it > 0L },
            )
            source.listFiles().sortedBy { it.name.orEmpty().lowercase(Locale.ROOT) }.forEach { child ->
                copyIntoSnapshot(
                    source = child,
                    destination = target,
                    relativePath = "$relativePath/${safeSegment(child.name ?: "untitled")}",
                    state = state,
                    onProgress = onProgress,
                    depth = depth + 1,
                )
            }
            return
        }
        require(source.isFile) { "Unsupported source entry: $relativePath" }
        val target = destination.createFile(source.type ?: "application/octet-stream", source.name ?: "untitled")
            ?: error("Unable to create $relativePath.")
        try {
            val result = copyAndHash(source.uri, target.uri) { bytes ->
                onProgress(BackupProgress(source.name ?: relativePath, state.fileCount, state.totalBytes + bytes))
            }
            val targetLength = target.length()
            if (targetLength >= 0L && targetLength != result.bytes) {
                error("Provider reported an incomplete copy for $relativePath.")
            }
            state.fileCount += 1
            state.totalBytes += result.bytes
            state.entries += BackupManifestEntry(
                relativePath = relativePath,
                directory = false,
                mimeType = source.type,
                sizeBytes = result.bytes,
                sha256 = result.sha256,
                lastModifiedMillis = source.lastModified().takeIf { it > 0L },
            )
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        }
    }

    private suspend fun copyAndHash(
        sourceUri: Uri,
        destinationUri: Uri,
        onBytes: (Long) -> Unit,
    ): CopyResult {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = context.contentResolver.openInputStream(sourceUri) ?: error("Unable to read backup data.")
        val output = context.contentResolver.openOutputStream(destinationUri, "w") ?: error("Unable to write backup data.")
        var total = 0L
        DigestInputStream(input, digest).use { source ->
            output.use { target ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    target.write(buffer, 0, count)
                    total += count
                    onBytes(total)
                }
                target.flush()
            }
        }
        return CopyResult(total, digest.digest().toHex())
    }

    private fun pruneSnapshots(plan: BackupPlan) {
        val excess = store.snapshots(plan.id).drop(plan.retentionCount)
        excess.forEach { snapshot ->
            val file = DocumentFile.fromSingleUri(context, Uri.parse(snapshot.snapshotTreeUri))
            if (file?.delete() == true) store.removeSnapshot(snapshot.id)
        }
    }

    private fun finish(
        run: BackupRunRecord,
        status: BackupRunStatus,
        message: String,
        snapshotId: String? = null,
    ): BackupRunRecord = run.copy(
        status = status,
        completedAtMillis = System.currentTimeMillis(),
        message = message,
        snapshotId = snapshotId,
    ).also(store::putRun)

    private fun uniqueSnapshotName(destination: DocumentFile, sourceName: String, createdAtMillis: Long): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US).format(Date(createdAtMillis))
        return uniqueDirectoryName(destination, "Fylz Backup - ${safeSegment(sourceName)} - $stamp")
    }

    private fun uniqueDirectoryName(destination: DocumentFile, base: String): String {
        if (destination.findFile(base) == null) return base
        var index = 2
        while (index <= 9_999) {
            val candidate = "$base ($index)"
            if (destination.findFile(candidate) == null) return candidate
            index += 1
        }
        error("Unable to find an available folder name.")
    }

    private fun ensureParent(root: DocumentFile, relativeParent: String): DocumentFile {
        if (relativeParent.isBlank()) return root
        var current = root
        relativeParent.split('/').forEach { segment ->
            current = current.findFile(segment)?.takeIf(DocumentFile::isDirectory)
                ?: current.createDirectory(segment)
                ?: error("Unable to create restore folder $relativeParent.")
        }
        return current
    }

    private fun findRelative(root: DocumentFile, path: String): DocumentFile? {
        var current: DocumentFile = root
        path.split('/').forEach { segment ->
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    private fun validateRelativePath(path: String) {
        require(path.isNotBlank())
        require(!path.startsWith('/'))
        require('\\' !in path)
        val segments = path.split('/')
        require(segments.none { it.isBlank() || it == "." || it == ".." })
        require(segments.all { it.length <= 255 })
    }

    private fun safeSegment(value: String): String = value
        .replace('/', '_')
        .replace('\\', '_')
        .trim()
        .take(255)
        .ifBlank { "untitled" }

    private fun encodeManifest(value: BackupManifest): String = JSONObject().apply {
        put("schemaVersion", value.schemaVersion)
        put("backupId", value.backupId)
        put("planId", value.planId)
        put("sourceTreeUri", value.sourceTreeUri)
        put("sourceDisplayName", value.sourceDisplayName)
        put("createdAtMillis", value.createdAtMillis)
        put("entries", JSONArray().apply {
            value.entries.forEach { entry ->
                put(JSONObject().apply {
                    put("relativePath", entry.relativePath)
                    put("directory", entry.directory)
                    put("mimeType", entry.mimeType ?: JSONObject.NULL)
                    put("sizeBytes", entry.sizeBytes)
                    put("sha256", entry.sha256 ?: JSONObject.NULL)
                    put("lastModifiedMillis", entry.lastModifiedMillis ?: JSONObject.NULL)
                })
            }
        })
    }.toString()

    private fun decodeManifest(value: String): BackupManifest? = runCatching {
        val root = JSONObject(value)
        val entries = root.getJSONArray("entries")
        BackupManifest(
            schemaVersion = root.getInt("schemaVersion"),
            backupId = root.getString("backupId"),
            planId = root.getString("planId"),
            sourceTreeUri = root.getString("sourceTreeUri"),
            sourceDisplayName = root.getString("sourceDisplayName"),
            createdAtMillis = root.getLong("createdAtMillis"),
            entries = List(entries.length()) { index ->
                val entry = entries.getJSONObject(index)
                BackupManifestEntry(
                    relativePath = entry.getString("relativePath"),
                    directory = entry.getBoolean("directory"),
                    mimeType = entry.optStringOrNull("mimeType"),
                    sizeBytes = entry.getLong("sizeBytes"),
                    sha256 = entry.optStringOrNull("sha256"),
                    lastModifiedMillis = entry.optLongOrNull("lastModifiedMillis"),
                )
            },
        )
    }.getOrNull()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (!has(key) || isNull(key)) null else getLong(key)

    private data class CopyResult(val bytes: Long, val sha256: String)

    private data class CopyState(
        val entries: MutableList<BackupManifestEntry> = mutableListOf(),
        var fileCount: Int = 0,
        var directoryCount: Int = 0,
        var totalBytes: Long = 0L,
    )

    private companion object {
        const val MANIFEST_FILE = ".fylz-backup-manifest.json"
        const val MAX_DEPTH = 128
        const val MAX_ENTRIES = 1_000_000
    }
}
