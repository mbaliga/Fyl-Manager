package io.github.mbaliga.fylz.operations

import android.net.Uri
import java.util.UUID

enum class FileOperationType {
    COPY,
    MOVE,
    RECYCLE,
    RESTORE,
    PERMANENT_DELETE,
    RENAME,
    CREATE_DIRECTORY,
    CREATE_FILE,
    ARCHIVE,
    EXTRACT,
}

enum class OperationState {
    QUEUED,
    PREFLIGHT,
    RUNNING,
    PAUSED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    NEEDS_ATTENTION,
}

enum class ConflictPolicy {
    ASK,
    KEEP_BOTH,
    REPLACE,
    SKIP,
}

data class OperationItem(
    val id: String = UUID.randomUUID().toString(),
    val source: Uri,
    val destination: Uri? = null,
    val displayName: String,
    val expectedBytes: Long? = null,
    val completedBytes: Long = 0,
    val state: OperationState = OperationState.QUEUED,
    val errorCode: String? = null,
)

data class FileOperation(
    val id: String = UUID.randomUUID().toString(),
    val type: FileOperationType,
    val items: List<OperationItem>,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.ASK,
    val state: OperationState = OperationState.QUEUED,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = createdAtMillis,
) {
    val totalBytes: Long? = items.mapNotNull { it.expectedBytes }.takeIf { it.size == items.size }?.sum()
    val completedBytes: Long = items.sumOf { it.completedBytes }
    val progress: Float? = totalBytes?.takeIf { it > 0 }?.let { completedBytes.toFloat() / it.toFloat() }
}
