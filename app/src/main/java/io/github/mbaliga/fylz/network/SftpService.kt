package io.github.mbaliga.fylz.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import kotlin.coroutines.coroutineContext

data class SftpConfig(
    val id: String,
    val displayName: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val pinnedHostKeySha256: String,
) {
    init {
        require(displayName.isNotBlank())
        require(host.isNotBlank() && host.length <= 253)
        require(port in 1..65535)
        require(username.isNotBlank() && username.length <= 256)
        require(pinnedHostKeySha256.matches(Regex("SHA256:[A-Za-z0-9+/]{43}=?"))) {
            "Host key fingerprint must use OpenSSH SHA256 format."
        }
    }
}

data class SftpEntry(
    val path: String,
    val name: String,
    val directory: Boolean,
    val sizeBytes: Long?,
    val modifiedAtSeconds: Long?,
)

/** Password-authenticated SFTP with mandatory SHA-256 host-key pinning. */
class SftpService {
    suspend fun list(config: SftpConfig, path: String, password: CharArray): List<SftpEntry> =
        withClient(config, password) { ssh ->
            ssh.newSFTPClient().use { sftp ->
                sftp.ls(validatePath(path)).asSequence()
                    .filterNot { it.name == "." || it.name == ".." }
                    .take(MAX_LIST_ENTRIES + 1)
                    .toList()
                    .also { require(it.size <= MAX_LIST_ENTRIES) { "Remote folder contains too many entries." } }
                    .map(::toEntry)
            }
        }

    suspend fun download(
        config: SftpConfig,
        remotePath: String,
        password: CharArray,
        destination: File,
    ) = withClient(config, password) { ssh ->
        val safePath = validatePath(remotePath)
        val parent = destination.parentFile ?: error("Destination has no parent folder.")
        parent.mkdirs()
        val partial = File(parent, ".${destination.name}.${System.nanoTime()}.part")
        try {
            ssh.newSFTPClient().use { sftp ->
                sftp.get(safePath, partial.absolutePath)
            }
            coroutineContext.ensureActive()
            if (destination.exists()) check(destination.delete()) { "Unable to replace the local destination." }
            check(partial.renameTo(destination)) { "Unable to commit the downloaded file." }
        } finally {
            partial.delete()
        }
    }

    suspend fun upload(
        config: SftpConfig,
        remotePath: String,
        password: CharArray,
        source: File,
    ) = withClient(config, password) { ssh ->
        require(source.isFile && source.canRead())
        ssh.newSFTPClient().use { sftp ->
            sftp.put(source.absolutePath, validatePath(remotePath))
        }
    }

    suspend fun createDirectory(config: SftpConfig, remotePath: String, password: CharArray) =
        withClient(config, password) { ssh ->
            ssh.newSFTPClient().use { it.mkdirs(validatePath(remotePath)) }
        }

    suspend fun delete(
        config: SftpConfig,
        remotePath: String,
        directory: Boolean,
        password: CharArray,
        confirmed: Boolean,
    ) = withClient(config, password) { ssh ->
        require(confirmed) { "Remote deletion requires confirmation." }
        ssh.newSFTPClient().use { sftp ->
            if (directory) sftp.rmdir(validatePath(remotePath)) else sftp.rm(validatePath(remotePath))
        }
    }

    private suspend fun <T> withClient(
        config: SftpConfig,
        password: CharArray,
        operation: suspend (SSHClient) -> T,
    ): T = withContext(Dispatchers.IO) {
        validateHost(config.host)
        val ssh = SSHClient()
        ssh.addHostKeyVerifier(PinnedHostKeyVerifier(config.pinnedHostKeySha256))
        try {
            ssh.connect(config.host, config.port)
            ssh.authPassword(config.username, password.concatToString())
            coroutineContext.ensureActive()
            operation(ssh)
        } finally {
            password.fill('\u0000')
            runCatching { ssh.disconnect() }
            runCatching { ssh.close() }
        }
    }

    private fun validateHost(host: String) {
        require(host.none { it.isWhitespace() || it == '/' || it == '\\' || it == '@' }) { "Invalid SFTP host." }
        // Resolve only after syntax validation; private/local hosts remain allowed because SFTP is
        // commonly used on LANs and the pinned server key is the trust boundary.
        runCatching { InetAddress.getAllByName(host) }.getOrElse { error("Unable to resolve SFTP host.") }
    }

    private fun validatePath(path: String): String {
        require(path.isNotBlank() && path.length <= MAX_PATH_CHARS)
        require('\u0000' !in path)
        require(path.split('/').none { it == ".." }) { "Parent traversal is not allowed in SFTP paths." }
        return path
    }

    private fun toEntry(value: RemoteResourceInfo): SftpEntry = SftpEntry(
        path = value.path,
        name = value.name,
        directory = value.isDirectory,
        sizeBytes = value.attributes.size.takeIf { it >= 0L },
        modifiedAtSeconds = value.attributes.mtime.toLong().takeIf { it > 0L },
    )

    private class PinnedHostKeyVerifier(private val expected: String) : HostKeyVerifier {
        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean = fingerprint(key) == expected

        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()

        private fun fingerprint(key: PublicKey): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded)
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }
    }

    private companion object {
        const val MAX_LIST_ENTRIES = 100_000
        const val MAX_PATH_CHARS = 4_096
    }
}
