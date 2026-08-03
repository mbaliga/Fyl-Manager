package io.github.mbaliga.fylz.backup

import java.util.UUID

private const val DEFAULT_MEDIA_THRESHOLD = 25
private const val DEFAULT_MEDIA_SCAN_MINUTES = 60
private const val DEFAULT_RETENTION_COUNT = 5

enum class BackupNetworkConstraint {
    NONE,
    CONNECTED,
    UNMETERED,
}

data class BackupConditions(
    val requiresCharging: Boolean = false,
    val requiresDeviceIdle: Boolean = false,
    val requiresBatteryNotLow: Boolean = true,
    val requiresStorageNotLow: Boolean = true,
    val network: BackupNetworkConstraint = BackupNetworkConstraint.NONE,
)

data class BackupSchedule(
    val dailyEnabled: Boolean = false,
    val dailyHour: Int = 2,
    val dailyMinute: Int = 0,
    val mediaCountEnabled: Boolean = false,
    val mediaThreshold: Int = DEFAULT_MEDIA_THRESHOLD,
    val mediaScanIntervalMinutes: Int = DEFAULT_MEDIA_SCAN_MINUTES,
) {
    init {
        require(dailyHour in 0..23)
        require(dailyMinute in 0..59)
        require(mediaThreshold in 1..100_000)
        require(mediaScanIntervalMinutes in 15..10_080)
    }

    val hasAutomaticTrigger: Boolean
        get() = dailyEnabled || mediaCountEnabled
}

data class BackupPlan(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val sourceTreeUri: String,
    val destinationTreeUri: String,
    val enabled: Boolean = false,
    val retentionCount: Int = DEFAULT_RETENTION_COUNT,
    val schedule: BackupSchedule = BackupSchedule(),
    val conditions: BackupConditions = BackupConditions(),
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
) {
    init {
        require(name.isNotBlank())
        require(sourceTreeUri.isNotBlank())
        require(destinationTreeUri.isNotBlank())
        require(retentionCount in 1..100)
    }
}

enum class BackupTrigger {
    MANUAL,
    DAILY_WINDOW,
    MEDIA_THRESHOLD,
}

enum class BackupRunStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    SKIPPED_THRESHOLD,
    SKIPPED_BUSY,
    FAILED,
    CANCELLED,
    NEEDS_ATTENTION,
}

data class BackupRunRecord(
    val id: String = UUID.randomUUID().toString(),
    val planId: String,
    val trigger: BackupTrigger,
    val status: BackupRunStatus,
    val startedAtMillis: Long = System.currentTimeMillis(),
    val completedAtMillis: Long? = null,
    val message: String? = null,
    val snapshotId: String? = null,
)

data class BackupSnapshotRecord(
    val id: String,
    val planId: String,
    val snapshotTreeUri: String,
    val displayName: String,
    val sourceDisplayName: String,
    val createdAtMillis: Long,
    val fileCount: Int,
    val directoryCount: Int,
    val totalBytes: Long,
    val manifestSha256: String,
)

data class BackupManifestEntry(
    val relativePath: String,
    val directory: Boolean,
    val mimeType: String?,
    val sizeBytes: Long,
    val sha256: String?,
    val lastModifiedMillis: Long?,
)

data class BackupManifest(
    val schemaVersion: Int = 1,
    val backupId: String,
    val planId: String,
    val sourceTreeUri: String,
    val sourceDisplayName: String,
    val createdAtMillis: Long,
    val entries: List<BackupManifestEntry>,
)

data class BackupProgress(
    val displayName: String,
    val completedFiles: Int,
    val completedBytes: Long,
)

data class BackupMediaState(
    val initialized: Boolean = false,
    val knownMediaUris: Set<String> = emptySet(),
    val pendingNewMedia: Int = 0,
    val lastScanAtMillis: Long = 0L,
)

data class BackupMediaScanResult(
    val newlyObserved: Int,
    val pendingNewMedia: Int,
    val thresholdReached: Boolean,
    val initialized: Boolean,
)

data class BackupImportResult(
    val importedSnapshots: Int,
    val existingSnapshots: Int,
    val invalidFolders: Int,
    val importedPlans: Int,
    val messages: List<String> = emptyList(),
) {
    val changed: Boolean
        get() = importedSnapshots > 0 || importedPlans > 0
}

enum class BackupRestoreStatus {
    RESTORED,
    INVALID_BACKUP,
    SOURCE_UNAVAILABLE,
    DESTINATION_UNAVAILABLE,
    FAILED_ROLLED_BACK,
    FAILED_ROLLBACK_INCOMPLETE,
}

data class BackupRestoreResult(
    val status: BackupRestoreStatus,
    val restoredRootUri: String? = null,
    val restoredFiles: Int = 0,
    val restoredBytes: Long = 0L,
    val message: String? = null,
) {
    val restored: Boolean
        get() = status == BackupRestoreStatus.RESTORED
}
