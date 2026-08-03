package io.github.mbaliga.fylz.network

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey
import kotlin.coroutines.coroutineContext

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
        require(normalizeFingerprint(pinnedHostKeySha256).matches(Regex("[A-Za-z0-9+/]{43}=?"))) {
            "SFTP requires a pinned SHA-256 host-key fingerprint."
        }
        require(maximumDownloadBytes in 1..64L * 1024L * 1024L * 1024L)
    }
}

/**
 * SFTP-backed provider with mandatory host-key pinning.
 *
 * A new connection is established and closed for every operation. This is less efficient than a
 * connection pool but avoids retaining credentials, sessions or dead sockets in the app process.
 */
class SftpProvider(private val config: SftpProviderConfig) : RemoteProvider, AutoCloseable {
    override val id: String = config.id
    override val displayName: String = config.displayName
    override val capabilities: Set<RemoteCapability> = buildSet {
        add(RemoteCapability.LIST)
        add(RemoteCapability.DOWNLOAD)
        add(RemoteCapability.RANGE_READ)
        if (config.writesEnabled) {
            add(RemoteCapability.UPLOAD)
            add(RemoteCapability.CREATE_DIRECTORY)
            add(RemoteCapability.DELETE)
        }
    }

    override suspend fun list(path: String, continuationToken: String?, limit: Int): RemotePage =
        withContext(Dispatchers.IO) {
            require(continuationToken == null) { "SFTP pagination tokens are not supported." }
            require(limit in 1..5_000)
            withClient { _, sftp ->
                val remote = remotePath(path).ifBlank { "." }
                val entries = sftp.ls(remote)
                    .asSequence()
                    .filterNot { it.name == "." || it.name == ".." }
                    .take(limit + 1)
                    .toList()
                RemotePage(
                    entries = entries.take(limit).map { info ->
                        val directory = info.isDirectory
                        RemoteObject(
                            key = RemotePathPolicy.child(RemotePathPolicy.normalize(path), info.name),
                            name = info.name,
                            directory = directory,
                            sizeBytes = if (directory) null else info.attributes.size.takeIf { it >= 0L },
                            modifiedAtMillis = info.attributes.mtime.takeIf { it > 0L }?.times(1_000L),
                            mimeType = null,
                        )
                    },
                    continuationToken = if (entries.size > limit) "more" else null,
                )
            }
        }

    override suspend fun download(
        path: String,
        destination: File,
        onProgress: (Long, Long?) -> Unit,
    ) = withContext(Dispatchers.IO) {
        require(!destination.exists() || destination.isFile)
        val temporary = File(destination.parentFile ?: error("Download destination has no parent."), ".${destination.name}.${System.nanoTime()}.part")
        try {
            withClient { _, sftp ->
                val remote = remotePath(path)
                val size = sftp.stat(remote).size.takeIf { it >= 0L }
                size?.let { require(it <= config.maximumDownloadBytes) { "Remote file exceeds the configured download limit." } }
                sftp.open(remote).use { remoteFile ->
                    remoteFile.new RemoteFileInputStream().use { input ->
                        temporary.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var completed = 0L
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                completed += count
                                require(completed <= config.maximumDownloadBytes) { "Remote file exceeded the download limit while streaming." }
                                output.write(buffer, 0, count)
                                onProgress(completed, size)
                            }
                        }
                    }
                }
            }
            if (destination.exists()) require(destination.delete()) { "Unable to replace the local download destination." }
            require(temporary.renameTo(destination)) { "Unable to commit the downloaded file." }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    override suspend fun upload(path: String, source: File, mimeType: String) = withContext(Dispatchers.IO) {
        requireWrites()
        require(source.isFile)
        withClient { _, sftp ->
            val target = remotePath(path)
            val temporary = "$target.fylz-upload-${System.nanoTime()}"
            try {
                sftp.put(source.absolutePath, temporary)
                val remoteSize = sftp.stat(temporary).size
                require(remoteSize == source.length()) { "SFTP upload size verification failed." }
                runCatching { sftp.rm(target) }
                sftp.rename(temporary, target)
            } catch (failure: Throwable) {
                runCatching { sftp.rm(temporary) }
                throw failure
            }
        }
    }

    override suspend fun createDirectory(path: String) = withContext(Dispatchers.IO) {
        requireWrites()
        withClient { _, sftp -> sftp.mkdirs(remotePath(path)) }
    }

    override suspend fun delete(path: String, confirmed: Boolean) = withContext(Dispatchers.IO) {
        requireWrites()
        require(confirmed) { "Deleting a remote item requires explicit confirmation." }
        withClient { _, sftp ->
            val remote = remotePath(path)
            val attributes = sftp.stat(remote)
            if (attributes.type.name.equals("DIRECTORY", ignoreCase = true)) sftp.rmdir(remote) else sftp.rm(remote)
        }
    }

    private fun remotePath(path: String): String = listOf(
        RemotePathPolicy.normalize(config.rootPath),
        RemotePathPolicy.normalize(path),
    ).filter(String::isNotBlank).joinToString("/").let { if (it.isBlank()) "." else "/$it" }

    private fun requireWrites() {
        require(config.writesEnabled) { "Writes are disabled for this SFTP connection." }
    }

    private inline fun <T> withClient(block: (SSHClient, net.schmizz.sshj.sftp.SFTPClient) -> T): T {
        val password = config.password.concatToString()
        val ssh = SSHClient()
        try {
            ssh.connectTimeout = 20_000
            ssh.timeout = 60_000
            ssh.addHostKeyVerifier(PinnedHostKeyVerifier(config.pinnedHostKeySha256))
            ssh.connect(config.host, config.port)
            ssh.authPassword(config.username, password)
            ssh.newSFTPClient().use { sftp -> return block(ssh, sftp) }
        } finally {
            runCatching { ssh.disconnect() }
            runCatching { ssh.close() }
            password.toCharArray().fill('\u0000')
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
                expectedFingerprint.trimEnd('=').toByteArray(Charsets.US_ASCII),
            )
        }

        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
    }
}

private fun normalizeFingerprint(value: String): String = value
    .trim()
    .removePrefix("SHA256:")
    .replace("=", "")
