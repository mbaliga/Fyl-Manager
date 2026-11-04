package io.github.mbaliga.fylz.network

import java.io.File
import java.time.Instant

class S3RemoteProvider(
    private val config: S3CompatibleConfig,
    secretAccessKey: CharArray,
    private val service: S3CompatibleService = S3CompatibleService(),
) : RemoteProvider, AutoCloseable {
    private val secret = secretAccessKey.copyOf()

    override val id: String = config.id
    override val displayName: String = config.displayName
    override val capabilities: Set<RemoteCapability> = setOf(
        RemoteCapability.LIST,
        RemoteCapability.DOWNLOAD,
        RemoteCapability.UPLOAD,
        RemoteCapability.CREATE_DIRECTORY,
        RemoteCapability.DELETE,
        RemoteCapability.RANGE_READ,
        RemoteCapability.CHECKSUM,
    )

    override suspend fun list(path: String, continuationToken: String?, limit: Int): RemotePage {
        require(limit in 1..1_000)
        val prefix = RemotePathPolicy.normalize(path).let { if (it.isBlank()) "" else "$it/" }
        val page = service.list(
            config = config,
            prefix = prefix,
            delimiter = "/",
            continuationToken = continuationToken,
            secretAccessKey = secret.copyOf(),
        )
        return RemotePage(
            // The folder's own marker object (key == prefix, written by createDirectory) is the folder,
            // not a child of it; listing it would show a blank row and make every folder look non-empty.
            entries = page.entries.filterNot { it.key == prefix }.take(limit).map { entry ->
                RemoteObject(
                    key = entry.key,
                    name = entry.key.removePrefix(prefix).trimEnd('/').substringAfterLast('/'),
                    directory = entry.directoryPrefix,
                    sizeBytes = entry.sizeBytes,
                    modifiedAtMillis = entry.lastModified?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
                    mimeType = null,
                    etag = entry.etag,
                )
            },
            continuationToken = page.nextContinuationToken,
        )
    }

    override suspend fun download(path: String, destination: File, onProgress: (Long, Long?) -> Unit) =
        service.download(config, RemotePathPolicy.normalize(path), destination, secret.copyOf(), onProgress)

    /**
     * S3-compatible stores have no portable "create only if absent" PUT, so [overwrite] = false is
     * enforced by the caller's full listing check (RemoteBrowser), not here. An object PUT replaces
     * the old object in one step, so there is no window in which both versions are lost.
     */
    override suspend fun upload(path: String, source: File, mimeType: String, overwrite: Boolean) =
        service.upload(config, RemotePathPolicy.normalize(path), source, secret.copyOf(), mimeType)

    override suspend fun createDirectory(path: String) {
        val marker = RemotePathPolicy.normalize(path).trimEnd('/') + "/"
        val empty = File.createTempFile("fylz-s3-directory", ".empty")
        try {
            service.upload(config, marker, empty, secret.copyOf(), "application/x-directory")
        } finally {
            empty.delete()
        }
    }

    override suspend fun delete(path: String, confirmed: Boolean) =
        service.delete(config, RemotePathPolicy.normalize(path), secret.copyOf(), confirmed)

    /**
     * Removes an (empty) folder's marker object. Folder keys end in `/`, which [RemotePathPolicy]
     * strips, so the generic [delete] would address `photos` instead of `photos/` and report success
     * while the folder stayed. The caller checks the folder is empty first: S3 has no folders, only
     * key prefixes, so an object left under the prefix would keep the "folder" visible.
     */
    suspend fun deleteDirectoryMarker(path: String, confirmed: Boolean) {
        val folder = RemotePathPolicy.normalize(path)
        require(folder.isNotBlank()) { "The bucket's top level can't be deleted." }
        service.delete(config, "$folder/", secret.copyOf(), confirmed)
    }

    override fun close() { secret.fill('\u0000') }
}
