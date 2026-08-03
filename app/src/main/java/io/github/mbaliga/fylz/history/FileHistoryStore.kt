package io.github.mbaliga.fylz.history

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

class FileHistoryStore(private val context: Context) {
    private val root = File(context.filesDir, "file-history")
    private val blobs = File(root, "blobs")
    private val manifest = File(root, "manifest.json")
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun settings(): FileHistorySettings = FileHistorySettings(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        maxVersionsPerFile = preferences.getInt(KEY_VERSIONS, 3).coerceIn(1, 100),
        maxFileBytes = preferences.getLong(KEY_FILE_BYTES, 2L * 1024L * 1024L).coerceAtLeast(1L),
        maxStorageBytes = preferences.getLong(KEY_STORAGE_BYTES, 1024L * 1024L * 1024L).coerceAtLeast(1L),
    )

    fun updateSettings(value: FileHistorySettings) {
        require(value.maxVersionsPerFile in 1..100)
        require(value.maxFileBytes > 0L)
        require(value.maxStorageBytes > 0L)
        preferences.edit()
            .putBoolean(KEY_ENABLED, value.enabled)
            .putInt(KEY_VERSIONS, value.maxVersionsPerFile)
            .putLong(KEY_FILE_BYTES, value.maxFileBytes)
            .putLong(KEY_STORAGE_BYTES, value.maxStorageBytes)
            .apply()
        synchronized(this) { prune(readManifest(), value) }
    }

    suspend fun capture(uri: Uri, reason: FileHistoryReason): FileHistoryCaptureResult =
        withContext(Dispatchers.IO) {
            val settings = settings()
            if (!settings.enabled) return@withContext FileHistoryCaptureResult(FileHistoryCaptureStatus.DISABLED)
            val metadata = queryMetadata(uri)
                ?: return@withContext FileHistoryCaptureResult(FileHistoryCaptureStatus.UNREADABLE)
            if (metadata.sizeBytes < 0L || metadata.sizeBytes > settings.maxFileBytes) {
                return@withContext FileHistoryCaptureResult(FileHistoryCaptureStatus.TOO_LARGE)
            }
            if (metadata.sizeBytes > settings.maxStorageBytes) {
                return@withContext FileHistoryCaptureResult(FileHistoryCaptureStatus.NO_SPACE)
            }

            root.mkdirs()
            blobs.mkdirs()
            val temp = File(root, ".capture-${UUID.randomUUID()}")
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > settings.maxFileBytes) {
                                return@withContext FileHistoryCaptureResult(FileHistoryCaptureStatus.TOO_LARGE)
                            }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                } ?: return@withContext FileHistoryCaptureResult(FileHistoryCaptureStatus.UNREADABLE)

                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                synchronized(this@FileHistoryStore) {
                    val versions = readManifest().toMutableList()
                    val sourceKey = uri.toString()
                    versions.firstOrNull { it.sourceKey == sourceKey && it.sha256 == hash }?.let { duplicate ->
                        return@synchronized FileHistoryCaptureResult(
                            FileHistoryCaptureStatus.DUPLICATE,
                            version = duplicate,
                        )
                    }
                    val blobName = "${UUID.randomUUID()}.blob"
                    val blob = File(blobs, blobName)
                    check(temp.renameTo(blob)) { "Unable to commit the history snapshot." }
                    val version = FileHistoryVersion(
                        sourceKey = sourceKey,
                        sourceUri = uri.toString(),
                        displayName = metadata.displayName,
                        mimeType = metadata.mimeType,
                        sizeBytes = blob.length(),
                        sha256 = hash,
                        blobName = blobName,
                        reason = reason,
                    )
                    versions += version
                    prune(versions, settings)
                    FileHistoryCaptureResult(FileHistoryCaptureStatus.CAPTURED, version)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                FileHistoryCaptureResult(FileHistoryCaptureStatus.ERROR, message = failure.message)
            } finally {
                temp.delete()
            }
        }

    fun versions(uri: Uri): List<FileHistoryVersion> = synchronized(this) {
        readManifest().filter { it.sourceKey == uri.toString() }
            .sortedByDescending(FileHistoryVersion::capturedAtMillis)
    }

    fun allVersions(): List<FileHistoryVersion> = synchronized(this) {
        readManifest().sortedByDescending(FileHistoryVersion::capturedAtMillis)
    }

    fun usage(): FileHistoryUsage = synchronized(this) {
        val versions = readManifest()
        FileHistoryUsage(
            totalBytes = versions.sumOf(FileHistoryVersion::sizeBytes),
            versionCount = versions.size,
            fileCount = versions.map(FileHistoryVersion::sourceKey).distinct().size,
            maxBytes = settings().maxStorageBytes,
        )
    }

    suspend fun restore(versionId: String, targetUri: Uri): FileHistoryRestoreResult =
        withContext(Dispatchers.IO) {
            val version = synchronized(this@FileHistoryStore) {
                readManifest().firstOrNull { it.id == versionId }
            } ?: return@withContext FileHistoryRestoreResult(FileHistoryRestoreStatus.VERSION_NOT_FOUND)
            val blob = File(blobs, version.blobName)
            if (!blob.isFile) {
                return@withContext FileHistoryRestoreResult(FileHistoryRestoreStatus.SNAPSHOT_MISSING)
            }
            if (queryMetadata(targetUri) == null) {
                return@withContext FileHistoryRestoreResult(FileHistoryRestoreStatus.TARGET_UNREADABLE)
            }

            val safety = capture(targetUri, FileHistoryReason.BEFORE_RESTORE)
            if (!safety.hasRestorableSnapshot) {
                return@withContext FileHistoryRestoreResult(
                    FileHistoryRestoreStatus.CURRENT_VERSION_NOT_PRESERVED,
                    "The current file could not be preserved, so restore was not attempted.",
                )
            }
            val rollback = safety.version ?: return@withContext FileHistoryRestoreResult(
                FileHistoryRestoreStatus.CURRENT_VERSION_NOT_PRESERVED,
            )

            if (!writeBlob(blob, targetUri)) {
                return@withContext FileHistoryRestoreResult(FileHistoryRestoreStatus.WRITE_FAILED)
            }
            if (sha256(targetUri) == version.sha256) {
                return@withContext FileHistoryRestoreResult(FileHistoryRestoreStatus.RESTORED)
            }

            val rollbackBlob = File(blobs, rollback.blobName)
            val rolledBack = rollbackBlob.isFile && writeBlob(rollbackBlob, targetUri) &&
                sha256(targetUri) == rollback.sha256
            FileHistoryRestoreResult(
                if (rolledBack) {
                    FileHistoryRestoreStatus.VERIFICATION_FAILED_ROLLED_BACK
                } else {
                    FileHistoryRestoreStatus.VERIFICATION_FAILED_ROLLBACK_FAILED
                },
                if (rolledBack) {
                    "The restored bytes did not verify. The previous file was restored."
                } else {
                    "The restored bytes did not verify and automatic rollback also failed."
                },
            )
        }

    fun delete(versionId: String): Boolean = synchronized(this) {
        val versions = readManifest().toMutableList()
        val version = versions.firstOrNull { it.id == versionId } ?: return@synchronized false
        versions.remove(version)
        File(blobs, version.blobName).delete()
        writeManifest(versions)
        true
    }

    fun clear(): Int = synchronized(this) {
        val versions = readManifest()
        versions.forEach { File(blobs, it.blobName).delete() }
        writeManifest(emptyList())
        versions.size
    }

    private fun prune(input: List<FileHistoryVersion>, settings: FileHistorySettings): List<FileHistoryVersion> {
        val retained = input.groupBy(FileHistoryVersion::sourceKey).values.flatMap { group ->
            group.sortedByDescending(FileHistoryVersion::capturedAtMillis).take(settings.maxVersionsPerFile)
        }.toMutableList()
        input.filterNot { it in retained }.forEach { File(blobs, it.blobName).delete() }
        retained.sortBy(FileHistoryVersion::capturedAtMillis)
        var total = retained.sumOf(FileHistoryVersion::sizeBytes)
        while (total > settings.maxStorageBytes && retained.isNotEmpty()) {
            val removed = retained.removeAt(0)
            total -= removed.sizeBytes
            File(blobs, removed.blobName).delete()
        }
        retained.sortByDescending(FileHistoryVersion::capturedAtMillis)
        writeManifest(retained)
        return retained
    }

    private fun readManifest(): List<FileHistoryVersion> = runCatching {
        if (!manifest.isFile) return emptyList()
        val array = JSONArray(manifest.readText())
        List(array.length()) { index ->
            val value = array.getJSONObject(index)
            FileHistoryVersion(
                id = value.getString("id"),
                sourceKey = value.getString("sourceKey"),
                sourceUri = value.getString("sourceUri"),
                displayName = value.getString("displayName"),
                mimeType = value.optString("mimeType").takeIf(String::isNotBlank),
                capturedAtMillis = value.getLong("capturedAtMillis"),
                sizeBytes = value.getLong("sizeBytes"),
                sha256 = value.getString("sha256"),
                blobName = value.getString("blobName"),
                reason = FileHistoryReason.valueOf(value.getString("reason")),
            )
        }.filter { File(blobs, it.blobName).isFile }
    }.getOrElse { emptyList() }

    private fun writeManifest(versions: List<FileHistoryVersion>) {
        root.mkdirs()
        val array = JSONArray()
        versions.forEach { version ->
            array.put(JSONObject().apply {
                put("id", version.id)
                put("sourceKey", version.sourceKey)
                put("sourceUri", version.sourceUri)
                put("displayName", version.displayName)
                put("mimeType", version.mimeType ?: "")
                put("capturedAtMillis", version.capturedAtMillis)
                put("sizeBytes", version.sizeBytes)
                put("sha256", version.sha256)
                put("blobName", version.blobName)
                put("reason", version.reason.name)
            })
        }
        val temp = File(root, "manifest-${UUID.randomUUID()}.tmp")
        val backup = File(root, "manifest.bak")
        FileOutputStream(temp).use { output ->
            output.write(array.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        backup.delete()
        if (manifest.exists() && !manifest.renameTo(backup)) {
            temp.delete()
            error("Unable to preserve existing file history metadata.")
        }
        if (!temp.renameTo(manifest)) {
            if (backup.exists()) backup.renameTo(manifest)
            temp.delete()
            error("Unable to commit file history metadata.")
        }
        backup.delete()
    }

    private fun writeBlob(blob: File, targetUri: Uri): Boolean = runCatching {
        val output = context.contentResolver.openOutputStream(targetUri, "w") ?: return false
        output.use { destination ->
            blob.inputStream().use { source -> source.copyTo(destination) }
            destination.flush()
        }
        true
    }.getOrDefault(false)

    private fun sha256(uri: Uri): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = context.contentResolver.openInputStream(uri) ?: return null
        input.use { source ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun queryMetadata(uri: Uri): Metadata? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            Metadata(
                displayName = if (nameIndex >= 0) cursor.getString(nameIndex) ?: "untitled" else "untitled",
                sizeBytes = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L,
                mimeType = context.contentResolver.getType(uri),
            )
        }
    }

    private data class Metadata(val displayName: String, val sizeBytes: Long, val mimeType: String?)

    private companion object {
        const val PREFS = "fylz_file_history_settings"
        const val KEY_ENABLED = "enabled"
        const val KEY_VERSIONS = "versions"
        const val KEY_FILE_BYTES = "file_bytes"
        const val KEY_STORAGE_BYTES = "storage_bytes"
    }
}
