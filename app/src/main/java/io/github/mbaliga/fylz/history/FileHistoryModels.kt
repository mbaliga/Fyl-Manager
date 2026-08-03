package io.github.mbaliga.fylz.history

import java.util.UUID

private const val MEBIBYTE = 1024L * 1024L
private const val GIBIBYTE = 1024L * 1024L * 1024L

data class FileHistorySettings(
    val enabled: Boolean = false,
    val maxVersionsPerFile: Int = 3,
    val maxFileBytes: Long = 2L * MEBIBYTE,
    val maxStorageBytes: Long = 1L * GIBIBYTE,
)

enum class FileHistoryReason {
    OBSERVED,
    BEFORE_WRITE,
    BEFORE_REPLACE,
    BEFORE_RESTORE,
}

data class FileHistoryVersion(
    val id: String = UUID.randomUUID().toString(),
    val sourceKey: String,
    val sourceUri: String,
    val displayName: String,
    val mimeType: String?,
    val capturedAtMillis: Long = System.currentTimeMillis(),
    val sizeBytes: Long,
    val sha256: String,
    val blobName: String,
    val reason: FileHistoryReason,
)

enum class FileHistoryCaptureStatus {
    CAPTURED,
    DUPLICATE,
    DISABLED,
    TOO_LARGE,
    NO_SPACE,
    UNREADABLE,
    ERROR,
}

data class FileHistoryCaptureResult(
    val status: FileHistoryCaptureStatus,
    val version: FileHistoryVersion? = null,
    val message: String? = null,
) {
    val preserved: Boolean
        get() = status == FileHistoryCaptureStatus.CAPTURED ||
            status == FileHistoryCaptureStatus.DUPLICATE ||
            status == FileHistoryCaptureStatus.DISABLED

    val hasRestorableSnapshot: Boolean
        get() = (status == FileHistoryCaptureStatus.CAPTURED ||
            status == FileHistoryCaptureStatus.DUPLICATE) && version != null
}

enum class FileHistoryRestoreStatus {
    RESTORED,
    VERSION_NOT_FOUND,
    SNAPSHOT_MISSING,
    TARGET_UNREADABLE,
    CURRENT_VERSION_NOT_PRESERVED,
    WRITE_FAILED,
    VERIFICATION_FAILED_ROLLED_BACK,
    VERIFICATION_FAILED_ROLLBACK_FAILED,
}

data class FileHistoryRestoreResult(
    val status: FileHistoryRestoreStatus,
    val message: String? = null,
) {
    val restored: Boolean
        get() = status == FileHistoryRestoreStatus.RESTORED
}

data class FileHistoryUsage(
    val totalBytes: Long,
    val versionCount: Int,
    val fileCount: Int,
    val maxBytes: Long,
)
