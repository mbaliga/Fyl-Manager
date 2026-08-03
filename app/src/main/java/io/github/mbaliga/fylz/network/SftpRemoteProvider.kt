package io.github.mbaliga.fylz.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileMode
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

data class SftpProviderConfig(
    val id: String,
    val displayName: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: CharArray,
    val pinnedHostKeySha256: String,
    val rootPath: String = "",
) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9._-]{1,80}")))
        require(host.isNotBlank() && port in 1..65535 && username.isNotBlank())
        require(pinnedHostKeySha256.startsWith("SHA256:") && pinnedHostKeySha256.length in 20..120)
    }
}

class SftpRemoteProvider(private val config: SftpProviderConfig) : RemoteProvider {
    override val id = config.id
    override val displayName = config.displayName
    override val capabilities = setOf(
        RemoteCapability.LIST, RemoteCapability.DOWNLOAD, RemoteCapability.UPLOAD,
        RemoteCapability.CREATE_DIRECTORY, RemoteCapability.DELETE,
    )

    override suspend fun list(path: String, continuationToken: String?, limit: Int): RemotePage = withContext(Dispatchers.IO) {
        require(continuationToken == null) { "SFTP listings do not use continuation tokens." }
        require(limit in 1..5_000)
        connect().use { ssh ->
            ssh.newSFTPClient().use { sftp ->
                val entries = sftp.ls(resolve(path)).asSequence()
                    .filterNot { it.name == "." || it.name == ".." }
                    .take(limit)
                    .map { item ->
                        val attributes = item.attributes
                        RemoteObject(
                            key = RemotePathPolicy.child(RemotePathPolicy.normalize(path), item.name),
                            name = item.name,
                            directory = attributes.type == FileMode.Type.DIRECTORY,
                            sizeBytes = attributes.size.takeIf { it >= 0L },
                            modifiedAtMillis = attributes.mtime.toLong().takeIf { it > 0L }?.times(1_000L),
                            mimeType = null,
                        )
                    }.toList()
                RemotePage(entries)
            }
        }
    }

    override suspend fun download(path: String, destination: File, onProgress: (Long, Long?) -> Unit) = withContext(Dispatchers.IO) {
        require(!destination.exists() || destination.isFile)
        destination.parentFile?.mkdirs()
        connect().use { ssh ->
            ssh.newSFTPClient().use { sftp ->
                val remote = resolve(path)
                val expected = runCatching { sftp.stat(remote).size }.getOrNull()
                val partial = File(destination.parentFile, ".${destination.name}.${System.nanoTime()}.part")
                try {
                    sftp.get(remote, partial.absolutePath)
                    onProgress(partial.length(), expected)
                    if (expected != null) require(partial.length() == expected) { "SFTP download is incomplete." }
                    if (destination.exists()) require(destination.delete())
                    check(partial.renameTo(destination)) { "Unable to commit downloaded file." }
                } finally {
                    partial.delete()
                }
            }
        }
    }

    override suspend fun upload(path: String, source: File, mimeType: String) = withContext(Dispatchers.IO) {
        require(source.isFile)
        connect().use { ssh -> ssh.newSFTPClient().use { it.put(source.absolutePath, resolve(path)) } }
    }

    override suspend fun createDirectory(path: String) = withContext(Dispatchers.IO) {
        connect().use { ssh -> ssh.newSFTPClient().use { it.mkdirs(resolve(path)) } }
    }

    override suspend fun delete(path: String, confirmed: Boolean) = withContext(Dispatchers.IO) {
        require(confirmed) { "Remote deletion requires confirmation." }
        connect().use { ssh ->
            ssh.newSFTPClient().use { sftp ->
                val remote = resolve(path)
                val type = sftp.stat(remote).type
                if (type == FileMode.Type.DIRECTORY) sftp.rmdir(remote) else sftp.rm(remote)
            }
        }
    }

    private fun connect(): SSHClient {
        val ssh = SSHClient()
        ssh.addHostKeyVerifier { _, _, key -> fingerprint(key) == config.pinnedHostKeySha256 }
        try {
            ssh.connect(config.host, config.port)
            ssh.authPassword(config.username, config.password.concatToString())
            return ssh
        } catch (failure: Throwable) {
            runCatching { ssh.close() }
            throw failure
        }
    }

    private fun resolve(path: String): String {
        val root = RemotePathPolicy.normalize(config.rootPath)
        val child = RemotePathPolicy.normalize(path)
        return "/" + listOf(root, child).filter(String::isNotEmpty).joinToString("/")
    }

    private fun fingerprint(key: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }
}
