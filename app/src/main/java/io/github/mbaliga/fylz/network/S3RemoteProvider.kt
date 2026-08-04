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
            entries = page.entries.take(limit).map { entry ->
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

    override suspend fun upload(path: String, source: File, mimeType: String) =
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

    override fun close() { secret.fill('\u0000') }
}
