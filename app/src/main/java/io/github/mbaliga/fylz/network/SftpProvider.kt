package io.github.mbaliga.fylz.network

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey

data class SftpProviderConfig(
    val id: String,
    val displayName: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: CharArray,
    val pinnedHostKeySha256: String,
    val rootPath: String = "",
    val writesEnabled: Boolean = false,
    val maximumDownloadBytes: Long = 8L * 1024L * 1024L * 1024L,
) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9._-]{1,80}")))
        require(displayName.isNotBlank())
        require(host.isNotBlank() && host.none(Char::isWhitespace))
        require(port in 1..65_535)
        require(username.isNotBlank())
        require(password.isNotEmpty())
        require(normalizeFingerprint(pinnedHostKeySha256).matches(Regex("[A-Za-z0-9+/]{43}"))) {
            "SFTP requires a pinned SHA-256 host-key fingerprint."
        }
        require(maximumDownloadBytes in 1..64L * 1024L * 1024L * 1024L)
    }
}

/** SFTP provider with mandatory SHA-256 host-key pinning and short-lived connections. */
class SftpProvider(private val config: SftpProviderConfig) : RemoteProvider, AutoCloseable {
    override val id: String = config.id
    override val displayName: String = config.displayName
    override val capabilities: Set<RemoteCapability> = buildSet {
        add(RemoteCapability.LIST)
        add(RemoteCapability.DOWNLOAD)
        if (config.writesEnabled) {
            add(RemoteCapability.UPLOAD)
            add(RemoteCapability.CREATE_DIRECTORY)
            add(RemoteCapability.DELETE)
        }
    }

    override suspend fun list(path: String, continuationToken: String?, limit: Int): RemotePage =
        withContext(Dispatchers.IO) {
            require(continuationToken == null) { "SFTP listings are returned as one bounded page." }
            require(limit in 1..5_000)
            withClient { sftp ->
                val entries = sftp.ls(remotePath(path))
                    .asSequence()
                    .filterNot { it.name == "." || it.name == ".." }
                    .take(limit)
                    .map { info ->
                        val directory = info.isDirectory
                        RemoteObject(
                            key = RemotePathPolicy.child(RemotePathPolicy.normalize(path), info.name),
                            name = info.name,
                            directory = directory,
                            sizeBytes = if (directory) null else info.attributes.size.takeIf { it >= 0L },
                            modifiedAtMillis = info.attributes.mtime.takeIf { it > 0L }?.times(1_000L),
                            mimeType = null,
                        )
                    }
                    .toList()
                RemotePage(entries = entries, continuationToken = null)
            }
        }

    override suspend fun download(
        path: String,
        destination: File,
        onProgress: (Long, Long?) -> Unit,
    ) = withContext(Dispatchers.IO) {
        require(!destination.exists() || destination.isFile)
        val parent = destination.parentFile ?: error("Download destination has no parent.")
        require(parent.isDirectory || parent.mkdirs()) { "Unable to prepare the local download folder." }
        val temporary = File(parent, ".${destination.name}.${System.nanoTime()}.part")
        try {
            withClient { sftp ->
                val remote = remotePath(path)
                val expectedSize = sftp.stat(remote).size.takeIf { it >= 0L }
                expectedSize?.let {
                    require(it <= config.maximumDownloadBytes) { "Remote file exceeds the configured download limit." }
                }
                sftp.get(remote, temporary.absolutePath)
                val actualSize = temporary.length()
                require(actualSize <= config.maximumDownloadBytes) { "Downloaded file exceeds the configured limit." }
                expectedSize?.let { require(actualSize == it) { "SFTP download size verification failed." } }
                onProgress(actualSize, expectedSize)
            }
            if (destination.exists()) require(destination.delete()) { "Unable to replace the local download destination." }
            require(temporary.renameTo(destination)) { "Unable to commit the downloaded file." }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    override suspend fun upload(path: String, source: File, mimeType: String, overwrite: Boolean) = withContext(Dispatchers.IO) {
        requireWrites()
        require(source.isFile)
        withClient { sftp ->
            RemoteWritePolicy.requireDeletablePath(path) // never write onto the connection root itself
            val target = remotePath(path)
            val temporary = "$target${RemoteWritePolicy.TEMP_UPLOAD_MARKER}${System.nanoTime()}"
            // Once the old file is gone, the verified temporary copy is the only good copy left, so it
            // must survive any later failure. Deleting it there would lose both versions.
            fun refuseClash() {
                val existing = sftp.statExistence(target) ?: return
                val isFolder = existing.type.name == "DIRECTORY"
                if (isFolder || !overwrite) throw RemoteConflictException(target.substringAfterLast('/'), isFolder)
            }
            refuseClash() // before transferring anything
            var originalRemoved = false
            try {
                sftp.put(source.absolutePath, temporary)
                require(sftp.stat(temporary).size == source.length()) { "SFTP upload size verification failed." }
                refuseClash() // again: something may have appeared at the target during the transfer
                if (sftp.statExistence(target) != null) {
                    sftp.rm(target)
                    originalRemoved = true
                }
                sftp.rename(temporary, target)
            } catch (failure: Throwable) {
                if (originalRemoved) {
                    throw IllegalStateException(
                        "The old file was removed but the new copy could not be renamed into place. " +
                            "Your upload is kept on the server as ${temporary.substringAfterLast('/')}.",
                        failure,
                    )
                }
                runCatching { sftp.rm(temporary) }
                throw failure
            }
        }
    }

    override suspend fun createDirectory(path: String) = withContext(Dispatchers.IO) {
        requireWrites()
        RemoteWritePolicy.requireDeletablePath(path)
        withClient { it.mkdir(remotePath(path)) }
    }

    override suspend fun delete(path: String, confirmed: Boolean) = withContext(Dispatchers.IO) {
        requireWrites()
        require(confirmed) { "Deleting a remote item requires explicit confirmation." }
        RemoteWritePolicy.requireDeletablePath(path)
        withClient { sftp ->
            val remote = remotePath(path)
            // lstat, not stat: a symlink to a folder is removed as a link, never by entering its target.
            if (sftp.lstat(remote).type.name.equals("DIRECTORY", ignoreCase = true)) sftp.rmdir(remote)
            else sftp.rm(remote)
        }
    }

    private fun remotePath(path: String): String = SftpPaths.remotePath(config.rootPath, path)

    private fun requireWrites() {
        require(config.writesEnabled) { "Writes are disabled for this SFTP connection." }
    }

    private inline fun <T> withClient(block: (net.schmizz.sshj.sftp.SFTPClient) -> T): T {
        val passwordString = config.password.concatToString()
        val ssh = SSHClient()
        try {
            ssh.connectTimeout = 20_000
            ssh.timeout = 60_000
            ssh.addHostKeyVerifier(PinnedHostKeyVerifier(config.pinnedHostKeySha256))
            ssh.connect(config.host, config.port)
            ssh.authPassword(config.username, passwordString)
            return ssh.newSFTPClient().use(block)
        } finally {
            runCatching { ssh.disconnect() }
            runCatching { ssh.close() }
        }
    }

    override fun close() {
        config.password.fill('\u0000')
    }

    private class PinnedHostKeyVerifier(expected: String) : HostKeyVerifier {
        private val expectedFingerprint = normalizeFingerprint(expected)

        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val actual = Base64.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(key.encoded),
                Base64.NO_WRAP,
            ).trimEnd('=')
            return MessageDigest.isEqual(
                actual.toByteArray(Charsets.US_ASCII),
                expectedFingerprint.toByteArray(Charsets.US_ASCII),
            )
        }

        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
    }
}

private fun normalizeFingerprint(value: String): String = value
    .trim()
    .removePrefix("SHA256:")
    .trimEnd('=')

/**
 * Maps a connection-relative path onto the SFTP server.
 *
 * With no root configured, everything resolves relative to the login directory, the same place a
 * blank path lists. (Previously a blank path listed the login directory while every other path was
 * made absolute, so opening a folder at the top level showed `/name` instead of `~/name`.) A
 * configured root is absolute, so everything under it is too.
 */
internal object SftpPaths {
    fun remotePath(rootPath: String, path: String): String {
        val root = RemotePathPolicy.normalize(rootPath)
        val relative = RemotePathPolicy.normalize(path)
        return when {
            root.isBlank() && relative.isBlank() -> "."
            root.isBlank() -> relative
            relative.isBlank() -> "/$root"
            else -> "/$root/$relative"
        }
    }
}
