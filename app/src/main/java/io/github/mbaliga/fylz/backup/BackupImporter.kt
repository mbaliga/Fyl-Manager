package io.github.mbaliga.fylz.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class BackupImporter(
    private val context: Context,
    private val store: BackupStore = BackupStore(context),
) {
    suspend fun importDestination(destinationTreeUri: Uri): BackupImportResult = withContext(Dispatchers.IO) {
        val destination = DocumentFile.fromTreeUri(context, destinationTreeUri)
            ?: return@withContext BackupImportResult(0, 0, 1, 0, listOf("Destination is unavailable."))
        if (!destination.isDirectory || !destination.canRead()) {
            return@withContext BackupImportResult(0, 0, 1, 0, listOf("Destination is not readable."))
        }

        val knownSnapshots = store.snapshots().associateBy(BackupSnapshotRecord::id).toMutableMap()
        val knownPlans = store.plans().associateBy(BackupPlan::id).toMutableMap()
        var importedSnapshots = 0
        var existingSnapshots = 0
        var invalidFolders = 0
        var importedPlans = 0
        val messages = mutableListOf<String>()

        destination.listFiles()
            .filter(DocumentFile::isDirectory)
            .take(MAX_SCAN_FOLDERS)
            .forEach { folder ->
                coroutineContext.ensureActive()
                val manifestFile = folder.findFile(BackupManifestPolicy.MANIFEST_FILE) ?: return@forEach
                val bytes = readBounded(manifestFile.uri) ?: run {
                    invalidFolders += 1
                    messages += "${folder.name ?: "Backup"}: manifest is unreadable or too large."
                    return@forEach
                }
                val manifest = decodeManifest(bytes.toString(Charsets.UTF_8))
                val validation = manifest?.let(BackupManifestPolicy::validate)
                if (manifest == null || validation?.allowed != true) {
                    invalidFolders += 1
                    messages += "${folder.name ?: "Backup"}: ${validation?.reason ?: "invalid manifest"}."
                    return@forEach
                }

                if (knownSnapshots.containsKey(manifest.backupId)) {
                    existingSnapshots += 1
                    return@forEach
                }

                if (!knownPlans.containsKey(manifest.planId)) {
                    val recoveredPlan = BackupPlan(
                        id = manifest.planId,
                        name = "Recovered ${manifest.sourceDisplayName}",
                        sourceTreeUri = manifest.sourceTreeUri,
                        destinationTreeUri = destinationTreeUri.toString(),
                        enabled = false,
                    )
                    store.putPlan(recoveredPlan)
                    knownPlans[recoveredPlan.id] = recoveredPlan
                    importedPlans += 1
                }

                val snapshot = BackupSnapshotRecord(
                    id = manifest.backupId,
                    planId = manifest.planId,
                    snapshotTreeUri = folder.uri.toString(),
                    displayName = folder.name ?: "Recovered backup",
                    sourceDisplayName = manifest.sourceDisplayName,
                    createdAtMillis = manifest.createdAtMillis,
                    fileCount = manifest.entries.count { !it.directory },
                    directoryCount = manifest.entries.count(BackupManifestEntry::directory),
                    totalBytes = manifest.entries.filterNot(BackupManifestEntry::directory).sumOf(BackupManifestEntry::sizeBytes),
                    manifestSha256 = sha256(bytes),
                )
                store.putSnapshot(snapshot)
                knownSnapshots[snapshot.id] = snapshot
                importedSnapshots += 1
            }

        if (destination.listFiles().count(DocumentFile::isDirectory) > MAX_SCAN_FOLDERS) {
            messages += "Only the first $MAX_SCAN_FOLDERS folders were scanned."
        }
        BackupImportResult(importedSnapshots, existingSnapshots, invalidFolders, importedPlans, messages)
    }

    private fun readBounded(uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_MANIFEST_BYTES) { "Manifest exceeds the safety limit." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }.getOrNull()

    private fun decodeManifest(value: String): BackupManifest? = runCatching {
        val root = JSONObject(value)
        val entries = root.getJSONArray("entries")
        require(entries.length() <= BackupManifestPolicy.MAX_ENTRIES)
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

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (!has(key) || isNull(key)) null else getLong(key)

    private companion object {
        const val MAX_MANIFEST_BYTES = 16 * 1024 * 1024
        const val MAX_SCAN_FOLDERS = 10_000
    }
}
