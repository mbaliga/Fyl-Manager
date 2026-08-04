package io.github.mbaliga.fylz.network

import java.io.File

enum class RemoteCapability {
    LIST,
    DOWNLOAD,
    UPLOAD,
    CREATE_DIRECTORY,
    MOVE,
    COPY,
    DELETE,
    RENAME,
    RANGE_READ,
    CHECKSUM,
}

data class RemoteObject(
    val key: String,
    val name: String,
    val directory: Boolean,
    val sizeBytes: Long?,
    val modifiedAtMillis: Long?,
    val mimeType: String?,
    val etag: String? = null,
)

data class RemotePage(
    val entries: List<RemoteObject>,
    val continuationToken: String? = null,
)

interface RemoteProvider {
    val id: String
    val displayName: String
    val capabilities: Set<RemoteCapability>

    suspend fun list(path: String, continuationToken: String? = null, limit: Int = 500): RemotePage
    suspend fun download(path: String, destination: File, onProgress: (Long, Long?) -> Unit = { _, _ -> })
    suspend fun upload(path: String, source: File, mimeType: String = "application/octet-stream")
    suspend fun createDirectory(path: String)
    suspend fun delete(path: String, confirmed: Boolean)
}

object RemotePathPolicy {
    fun normalize(path: String): String {
        val normalized = path.replace('\\', '/').trimStart('/')
        require(normalized.length <= 4_096) { "Remote path is too long." }
        val segments = normalized.split('/').filter(String::isNotBlank)
        require(segments.none { it == "." || it == ".." || it.any { char -> char.code in 0..31 || char.code == 127 } }) {
            "Remote path contains unsafe segments."
        }
        return segments.joinToString("/")
    }

    fun child(parent: String, name: String): String {
        require(name.isNotBlank() && '/' !in name && '\\' !in name)
        return listOf(normalize(parent), name).filter(String::isNotBlank).joinToString("/")
    }
}
