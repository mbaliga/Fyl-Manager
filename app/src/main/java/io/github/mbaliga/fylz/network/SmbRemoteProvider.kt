package io.github.mbaliga.fylz.network

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class SmbProviderConfig(
    val id: String,
    val displayName: String,
    val host: String,
    val port: Int = 445,
    val share: String,
    val domain: String = "",
    val username: String,
    val password: CharArray,
    val rootPath: String = "",
) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9._-]{1,80}")))
        require(host.isNotBlank() && port in 1..65535 && share.isNotBlank() && username.isNotBlank())
        RemotePathPolicy.normalize(rootPath)
    }
}

class SmbRemoteProvider(private val config: SmbProviderConfig) : RemoteProvider, AutoCloseable {
    private val password = config.password.copyOf()
    override val id = config.id
    override val displayName = config.displayName
    override val capabilities = setOf(
        RemoteCapability.LIST, RemoteCapability.DOWNLOAD, RemoteCapability.UPLOAD,
        RemoteCapability.CREATE_DIRECTORY, RemoteCapability.DELETE, RemoteCapability.RANGE_READ,
    )

    override suspend fun list(path: String, continuationToken: String?, limit: Int): RemotePage = withContext(Dispatchers.IO) {
        require(continuationToken == null)
        require(limit in 1..5_000)
        withShare { share ->
            val parent = resolve(path)
            val entries = share.list(parent).asSequence()
                .filterNot { it.fileName == "." || it.fileName == ".." }
                .take(limit)
                .map { item ->
                    val directory = EnumWithValue.EnumUtils.isSet(
                        item.fileAttributes,
                        FileAttributes.FILE_ATTRIBUTE_DIRECTORY,
                    )
                    RemoteObject(
                        key = RemotePathPolicy.child(RemotePathPolicy.normalize(path), item.fileName),
                        name = item.fileName,
                        directory = directory,
                        sizeBytes = item.endOfFile.takeIf { !directory && it >= 0L },
                        modifiedAtMillis = item.lastWriteTime?.toEpochMillis(),
                        mimeType = null,
                    )
                }.toList()
            RemotePage(entries)
        }
    }

    override suspend fun download(path: String, destination: File, onProgress: (Long, Long?) -> Unit) = withContext(Dispatchers.IO) {
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, ".${destination.name}.${System.nanoTime()}.part")
        try {
            withShare { share ->
                share.openFile(
                    resolve(path),
                    setOf(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null,
                ).use { remote ->
                    val expected = remote.fileInformation.standardInformation.endOfFile.takeIf { it >= 0L }
                    remote.inputStream.use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                total += count
                                onProgress(total, expected)
                            }
                            output.fd.sync()
                            if (expected != null) require(total == expected) { "SMB download is incomplete." }
                        }
                    }
                }
            }
            if (destination.exists()) require(destination.delete())
            check(partial.renameTo(destination)) { "Unable to commit SMB download." }
        } finally {
            partial.delete()
        }
    }

    override suspend fun upload(path: String, source: File, mimeType: String) = withContext(Dispatchers.IO) {
        require(source.isFile)
        withShare { share ->
            share.openFile(
                resolve(path),
                setOf(AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                setOf(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
            ).use { remote -> source.inputStream().use { input -> remote.outputStream.use { input.copyTo(it) } } }
        }
    }

    override suspend fun createDirectory(path: String) = withContext(Dispatchers.IO) {
        withShare { it.mkdir(resolve(path)) }
    }

    override suspend fun delete(path: String, confirmed: Boolean) = withContext(Dispatchers.IO) {
        require(confirmed) { "Remote deletion requires confirmation." }
        withShare { share ->
            val remote = resolve(path)
            if (share.folderExists(remote)) share.rmdir(remote, false) else share.rm(remote)
        }
    }

    private inline fun <T> withShare(block: (DiskShare) -> T): T {
        SMBClient().use { client ->
            client.connect(config.host, config.port).use { connection ->
                val authentication = AuthenticationContext(config.username, password.copyOf(), config.domain)
                connection.authenticate(authentication).use { session ->
                    (session.connectShare(config.share) as DiskShare).use { share -> return block(share) }
                }
            }
        }
    }

    private fun resolve(path: String): String = listOf(
        RemotePathPolicy.normalize(config.rootPath),
        RemotePathPolicy.normalize(path),
    ).filter(String::isNotBlank).joinToString("\\")

    override fun close() { password.fill('\u0000') }
}
