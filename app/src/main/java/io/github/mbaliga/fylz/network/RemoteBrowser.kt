package io.github.mbaliga.fylz.network

import java.io.File

/**
 * Every operation the UI performs against a saved [RemoteConnection], whatever its backend.
 *
 * `RemoteProvider` unifies SFTP/SMB/S3; WebDAV predates that interface and exposes `WebDavService`
 * directly. This adapter covers all four, so the UI has one call per operation, and it is the single
 * place where remote writes are checked against [RemoteWritePolicy] before any request is made.
 *
 * Secrets: each backend call consumes (and zeroes) the credential array it is given, so write
 * operations that need more than one call take a [secrets] supplier that reads a fresh copy from the
 * vault for every call instead of reusing one array.
 */
class RemoteBrowser(private val webDav: WebDavService = WebDavService()) {

    data class Listing(
        val path: String,
        val entries: List<RemoteObject>,
        val capabilities: Set<RemoteCapability>,
    )

    /**
     * What the UI may offer for [connection]. Writes are offered only when the saved connection has
     * "Allow writes" on, regardless of what a backend reports about itself.
     */
    fun capabilities(connection: RemoteConnection): Set<RemoteCapability> = buildSet {
        add(RemoteCapability.LIST)
        add(RemoteCapability.DOWNLOAD)
        if (connection.writesEnabled) {
            add(RemoteCapability.UPLOAD)
            add(RemoteCapability.CREATE_DIRECTORY)
            add(RemoteCapability.DELETE)
        }
    }

    suspend fun list(
        connection: RemoteConnection,
        path: String,
        secret: CharArray,
    ): Listing = Listing(path, listEntries(connection, path, secret), capabilities(connection))

    /**
     * Downloads one remote file into [destination] (a private local file) with the provider's own
     * verified, atomic write. The caller owns moving it to its final SAF location and deleting it.
     */
    suspend fun download(
        connection: RemoteConnection,
        path: String,
        secret: CharArray,
        destination: File,
        onProgress: (Long, Long?) -> Unit = { _, _ -> },
    ) {
        val safePath = RemotePathPolicy.normalize(path)
        when (connection.kind) {
            RemoteKind.WEBDAV -> webDav.download(webDavConfig(connection), safePath, secret, destination, onProgress)
            RemoteKind.SFTP -> sftp(connection, secret, writes = false).use { it.download(safePath, destination, onProgress) }
            RemoteKind.SMB -> smb(connection, secret, writes = false).use { it.download(safePath, destination, onProgress) }
            RemoteKind.S3 -> s3(connection, secret).use { it.download(safePath, destination, onProgress) }
        }
    }

    /**
     * Uploads [source] as [name] inside [directory]. An existing file is replaced only when
     * [replaceExisting] is true; an existing folder is never replaced. A clash throws
     * [RemoteConflictException] before anything is transferred.
     */
    suspend fun upload(
        connection: RemoteConnection,
        directory: String,
        name: String,
        source: File,
        secrets: () -> CharArray,
        replaceExisting: Boolean,
        mimeType: String = "application/octet-stream",
    ) {
        RemoteWritePolicy.requireWritable(connection)
        RemoteWritePolicy.requireValidName(connection.kind, name)
        require(source.isFile) { "The file to upload is no longer available." }
        val existing = listCompletely(connection, directory, secrets)
        RemoteWritePolicy.checkUpload(connection.kind, existing, name, replaceExisting)
        val target = RemoteWritePolicy.childPath(directory, name)
        when (connection.kind) {
            RemoteKind.WEBDAV -> webDav.upload(
                webDavConfig(connection),
                target,
                secrets(),
                source,
                mimeType,
                overwrite = replaceExisting,
            )
            RemoteKind.SFTP -> sftp(connection, secrets(), writes = true).use {
                it.upload(target, source, mimeType, overwrite = replaceExisting)
            }
            RemoteKind.SMB -> smb(connection, secrets(), writes = true).use {
                it.upload(target, source, mimeType, overwrite = replaceExisting)
            }
            RemoteKind.S3 -> s3(connection, secrets()).use {
                it.upload(target, source, mimeType, overwrite = replaceExisting)
            }
        }
    }

    /** Creates folder [name] inside [directory]. Refuses if anything by that name already exists. */
    suspend fun createDirectory(
        connection: RemoteConnection,
        directory: String,
        name: String,
        secrets: () -> CharArray,
    ) {
        RemoteWritePolicy.requireWritable(connection)
        RemoteWritePolicy.requireValidName(connection.kind, name)
        RemoteWritePolicy.checkNewFolder(connection.kind, listCompletely(connection, directory, secrets), name)
        val target = RemoteWritePolicy.childPath(directory, name)
        when (connection.kind) {
            RemoteKind.WEBDAV -> webDav.createDirectory(webDavConfig(connection), target, secrets())
            RemoteKind.SFTP -> sftp(connection, secrets(), writes = true).use { it.createDirectory(target) }
            RemoteKind.SMB -> smb(connection, secrets(), writes = true).use { it.createDirectory(target) }
            RemoteKind.S3 -> s3(connection, secrets()).use { it.createDirectory(target) }
        }
    }

    /**
     * Permanently deletes [entry] from [directory]. The caller must have shown the high-friction
     * confirmation first ([confirmed]). Folders are deleted only when empty, on every backend.
     */
    suspend fun delete(
        connection: RemoteConnection,
        directory: String,
        entry: RemoteObject,
        secrets: () -> CharArray,
        confirmed: Boolean,
    ) {
        require(confirmed) { "Deleting a remote item requires explicit confirmation." }
        RemoteWritePolicy.requireWritable(connection)
        val target = RemoteWritePolicy.childPath(directory, entry.name)
        RemoteWritePolicy.requireDeletablePath(target)
        if (entry.directory && connection.kind != RemoteKind.WEBDAV) {
            RemoteWritePolicy.requireEmptyFolder(entry.name, listEntries(connection, target, secrets()))
        }
        when (connection.kind) {
            RemoteKind.WEBDAV -> {
                // A WebDAV DELETE on a folder removes everything inside it, and the server will not
                // refuse a non-empty one. So inspect the target itself immediately before deleting:
                // its current type (the listing on screen may be stale), whether it is empty now, and
                // its ETag, which the DELETE then carries as If-Match so the server refuses it if the
                // item changed in between. Without an ETag the window is only this request's latency.
                val config = webDavConfig(connection)
                val current = webDav.inspect(config, target, secrets())
                if (current.collection) RemoteWritePolicy.requireEmptyFolder(entry.name, current.children)
                webDav.delete(
                    config,
                    target,
                    secrets(),
                    confirmed = true,
                    collection = current.collection,
                    ifMatch = current.etag,
                )
            }
            RemoteKind.SFTP -> sftp(connection, secrets(), writes = true).use { it.delete(target, confirmed = true) }
            RemoteKind.SMB -> smb(connection, secrets(), writes = true).use { it.delete(target, confirmed = true) }
            RemoteKind.S3 -> s3(connection, secrets()).use { provider ->
                if (entry.directory) provider.deleteDirectoryMarker(target, confirmed = true)
                else provider.delete(target, confirmed = true)
            }
        }
    }

    private suspend fun listEntries(
        connection: RemoteConnection,
        path: String,
        secret: CharArray,
    ): List<RemoteObject> = when (connection.kind) {
        RemoteKind.WEBDAV -> webDav.list(webDavConfig(connection), path, secret).map { entry ->
            RemoteObject(
                key = entry.href,
                name = entry.name,
                directory = entry.directory,
                sizeBytes = entry.sizeBytes,
                modifiedAtMillis = null,
                mimeType = null,
            )
        }
        RemoteKind.SFTP -> sftp(connection, secret, writes = false).use { it.list(path).entries }
        RemoteKind.SMB -> smb(connection, secret, writes = false).use { it.list(path).entries }
        RemoteKind.S3 -> s3(connection, secret).use { it.list(path).entries }
    }

    /**
     * Every entry in [directory], for safety checks that must not miss a name. Ordinary listings are
     * capped at one page; a clash check against a truncated page could wave an overwrite through.
     * SFTP and SMB return one bounded page, so a page that reaches the cap is refused rather than
     * trusted. S3 is paged to the end.
     */
    private suspend fun listCompletely(
        connection: RemoteConnection,
        directory: String,
        secrets: () -> CharArray,
    ): List<RemoteObject> = when (connection.kind) {
        RemoteKind.WEBDAV -> listEntries(connection, directory, secrets())
        RemoteKind.SFTP -> sftp(connection, secrets(), writes = false).use {
            it.list(directory, limit = MAX_CHECK_ENTRIES).entries.also(::requireNotTruncated)
        }
        RemoteKind.SMB -> smb(connection, secrets(), writes = false).use {
            it.list(directory, limit = MAX_CHECK_ENTRIES).entries.also(::requireNotTruncated)
        }
        RemoteKind.S3 -> s3(connection, secrets()).use { provider ->
            val all = mutableListOf<RemoteObject>()
            var token: String? = null
            var pages = 0
            do {
                val page = provider.list(directory, token, limit = S3_PAGE)
                all += page.entries
                token = page.continuationToken
                // Some gateways answer list-type=2 with a V1-style page: IsTruncated but no token.
                // Treating that as the whole folder would let an upload replace a file on page 2.
                check(!(page.truncated && token.isNullOrEmpty())) {
                    "This server didn’t return the whole folder, so Fylz can’t check for name clashes safely."
                }
                check(++pages <= MAX_S3_PAGES || token == null) {
                    "This folder is too large to check for name clashes safely."
                }
            } while (token != null)
            all
        }
    }

    private fun requireNotTruncated(entries: List<RemoteObject>) {
        check(entries.size < MAX_CHECK_ENTRIES) { "This folder is too large to check for name clashes safely." }
    }

    private fun webDavConfig(connection: RemoteConnection) = WebDavConfig(
        id = connection.id,
        displayName = connection.displayName,
        baseUrl = connection.endpoint,
        username = connection.username,
    )

    private fun sftp(connection: RemoteConnection, secret: CharArray, writes: Boolean) = SftpProvider(
        SftpProviderConfig(
            id = connection.id,
            displayName = connection.displayName,
            host = connection.host,
            port = connection.port.takeIf { it > 0 } ?: 22,
            username = connection.username,
            password = secret,
            pinnedHostKeySha256 = connection.hostKeyFingerprint,
            rootPath = "",
            writesEnabled = writes && connection.writesEnabled,
        ),
    )

    private fun smb(connection: RemoteConnection, secret: CharArray, writes: Boolean) = SmbProvider(
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
            writesEnabled = writes && connection.writesEnabled,
        ),
    )

    /** S3RemoteProvider keeps its own copy of the key, so the vault copy handed in is zeroed here. */
    private fun s3(connection: RemoteConnection, secret: CharArray) = try {
        S3RemoteProvider(
            S3CompatibleConfig(
                id = connection.id,
                displayName = connection.displayName,
                endpoint = connection.endpoint,
                region = connection.region,
                bucket = connection.bucket,
                accessKeyId = connection.username,
            ),
            secret,
        )
    } finally {
        secret.fill('\u0000')
    }

    private companion object {
        const val MAX_CHECK_ENTRIES = 5_000
        const val S3_PAGE = 1_000
        const val MAX_S3_PAGES = 100
    }
}
