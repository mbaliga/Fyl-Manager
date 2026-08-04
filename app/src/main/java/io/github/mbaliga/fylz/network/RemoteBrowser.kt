package io.github.mbaliga.fylz.network

/**
 * One listing operation against a saved [RemoteConnection], regardless of backend.
 *
 * `RemoteProvider` already unifies SFTP/SMB/S3; WebDAV predates that interface and exposes
 * `WebDavService` directly. This adapter covers all four so the UI has exactly one call to make,
 * and it is the piece that finally makes `SftpProvider`, `SmbProvider` and `S3RemoteProvider`
 * reachable from the app.
 *
 * Secrets are read from the vault by the caller, passed in, and zeroed by the provider configs.
 */
class RemoteBrowser(private val webDav: WebDavService = WebDavService()) {

    data class Listing(
        val path: String,
        val entries: List<RemoteObject>,
        val capabilities: Set<RemoteCapability>,
    )

    suspend fun list(
        connection: RemoteConnection,
        path: String,
        secret: CharArray,
    ): Listing = when (connection.kind) {
        RemoteKind.WEBDAV -> {
            val entries = webDav.list(
                WebDavConfig(
                    id = connection.id,
                    displayName = connection.displayName,
                    baseUrl = connection.endpoint,
                    username = connection.username,
                ),
                path,
                secret,
            )
            Listing(
                path = path,
                entries = entries.map { entry ->
                    RemoteObject(
                        key = entry.href,
                        name = entry.name,
                        directory = entry.directory,
                        sizeBytes = entry.sizeBytes,
                        modifiedAtMillis = null,
                        mimeType = null,
                    )
                },
                capabilities = setOf(RemoteCapability.LIST, RemoteCapability.DOWNLOAD),
            )
        }

        RemoteKind.SFTP -> SftpProvider(
            SftpProviderConfig(
                id = connection.id,
                displayName = connection.displayName,
                host = connection.host,
                port = connection.port.takeIf { it > 0 } ?: 22,
                username = connection.username,
                password = secret,
                pinnedHostKeySha256 = connection.hostKeyFingerprint,
                rootPath = "",
                writesEnabled = connection.writesEnabled,
            ),
        ).use { provider ->
            Listing(path, provider.list(path).entries, provider.capabilities)
        }

        RemoteKind.SMB -> SmbProvider(
            SmbProviderConfig(
                id = connection.id,
                displayName = connection.displayName,
                host = connection.host,
                port = connection.port.takeIf { it > 0 } ?: 445,
                share = connection.share,
                domain = connection.domain,
                username = connection.username,
                password = secret,
                rootPath = "",
                writesEnabled = connection.writesEnabled,
            ),
        ).use { provider ->
            Listing(path, provider.list(path).entries, provider.capabilities)
        }

        RemoteKind.S3 -> S3RemoteProvider(
            S3CompatibleConfig(
                id = connection.id,
                displayName = connection.displayName,
                endpoint = connection.endpoint,
                region = connection.region,
                bucket = connection.bucket,
                accessKeyId = connection.username,
            ),
            secret,
        ).use { provider ->
            Listing(path, provider.list(path).entries, provider.capabilities)
        }
    }
}
