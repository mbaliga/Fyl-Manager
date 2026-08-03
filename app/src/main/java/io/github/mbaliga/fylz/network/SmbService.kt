package io.github.mbaliga.fylz.network

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.EnumSet
import kotlin.coroutines.coroutineContext

data class SmbConfig(
    val id: String,
    val displayName: String,
    val host: String,
    val port: Int = 445,
    val share: String,
    val domain: String = "",
    val username: String,
    val requireEncryption: Boolean = false,
) {
    init {
        require(displayName.isNotBlank())
        require(host.isNotBlank() && host.length <= 253)
        require(port in 1..65535)
        require(share.matches(Regex("[^\\\\/:*?\"<>|]{1,80}")))
        require(username.isNotBlank() && username.length <= 256)
        require(domain.length <= 256)
    }
}

data class SmbEntry(
    val path: String,
    val name: String,
    val directory: Boolean,
    val sizeBytes: Long?,
)

/** SMB2/3 adapter. SMB1 is not supported; packet signing is always required. */
class SmbService {
    suspend fun list(config: SmbConfig, path: String, password: CharArray): List<SmbEntry> =
        withShare(config, password) { share ->
            val normalized = validatePath(path)
            val values = share.list(normalized).filterNot { it.fileName == "." || it.fileName == ".." }
            require(values.size <= MAX_LIST_ENTRIES) { "Remote folder contains too many entries." }
            values.map { info ->
                val childPath = join(normalized, info.fileName)
                val directory = runCatching { share.folderExists(childPath) }.getOrDefault(false)
                SmbEntry(
                    path = childPath,
                    name = info.fileName,
                    directory = directory,
                    sizeBytes = if (directory) null else info.endOfFile.takeIf { it >= 0L },
                )
            }
        }

    suspend fun download(
        config: SmbConfig,
        remotePath: String,
        password: CharArray,
        destination: File,
        onProgress: (Long, Long?) -> Unit = { _, _ -> },
    ) = withShare(config, password) { share ->
        val safePath = validatePath(remotePath)
        val parent = destination.parentFile ?: error("Destination has no parent folder.")
        parent.mkdirs()
        val partial = File(parent, ".${destination.name}.${System.nanoTime()}.part")
        try {
            share.openFile(
                safePath,
                EnumSet.of(AccessMask.GENERIC_READ),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
            ).use { remote ->
                val total = runCatching { remote.fileInformation.standardInformation.endOfFile }.getOrNull()
                var completed = 0L
                remote.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            completed += count
                            onProgress(completed, total)
                        }
                        output.fd.sync()
                    }
                }
                if (total != null) check(completed == total) { "SMB download was incomplete." }
            }
            if (destination.exists()) check(destination.delete()) { "Unable to replace the local destination." }
            check(partial.renameTo(destination)) { "Unable to commit the SMB download." }
        } finally {
            partial.delete()
        }
    }

    suspend fun upload(
        config: SmbConfig,
        remotePath: String,
        password: CharArray,
        source: File,
    ) = withShare(config, password) { share ->
        require(source.isFile && source.canRead())
        share.openFile(
            validatePath(remotePath),
            EnumSet.of(AccessMask.GENERIC_WRITE),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        ).use { remote ->
            var offset = 0L
            source.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    val written = remote.write(buffer, offset, 0, count)
                    check(written == count.toLong()) { "SMB server accepted only part of an upload block." }
                    offset += written
                }
            }
            remote.flush()
        }
    }

    suspend fun createDirectory(config: SmbConfig, path: String, password: CharArray) =
        withShare(config, password) { share -> share.mkdir(validatePath(path)) }

    suspend fun delete(
        config: SmbConfig,
        path: String,
        directory: Boolean,
        password: CharArray,
        confirmed: Boolean,
    ) = withShare(config, password) { share ->
        require(confirmed) { "Remote deletion requires confirmation." }
        if (directory) share.rmdir(validatePath(path), false) else share.rm(validatePath(path))
    }

    private suspend fun <T> withShare(
        config: SmbConfig,
        password: CharArray,
        operation: suspend (DiskShare) -> T,
    ): T = withContext(Dispatchers.IO) {
        validateHost(config.host)
        val client = SMBClient(
            SmbConfig.builder()
                .withSigningRequired(true)
                .build(),
        )
        try {
            client.connect(config.host, config.port).use { connection ->
                val authentication = AuthenticationContext(
                    config.username,
                    password.concatToString().toCharArray(),
                    config.domain,
                )
                connection.authenticate(authentication).use { session ->
                    check(session.isSigningRequired) { "The SMB session did not require packet signing." }
                    if (config.requireEncryption) {
                        check(session.shouldEncryptData()) { "The SMB server did not negotiate encrypted transport." }
                    }
                    val remoteShare = session.connectShare(config.share)
                    check(remoteShare is DiskShare) { "The configured SMB share is not a disk share." }
                    remoteShare.use { operation(it) }
                }
            }
        } finally {
            password.fill('\u0000')
            runCatching { client.close() }
        }
    }

    private fun validateHost(value: String) {
        require(value.none { it.isWhitespace() || it == '/' || it == '\\' || it == '@' }) { "Invalid SMB host." }
    }

    private fun validatePath(value: String): String {
        val normalized = value.replace('/', '\\').trimStart('\\')
        require(normalized.length <= MAX_PATH_CHARS && '\u0000' !in normalized)
        require(normalized.split('\\').none { it == ".." }) { "Parent traversal is not allowed in SMB paths." }
        return normalized
    }

    private fun join(parent: String, child: String): String = if (parent.isBlank()) child else "$parent\\$child"

    private companion object {
        const val MAX_LIST_ENTRIES = 100_000
        const val MAX_PATH_CHARS = 32_000
    }
}
