package io.github.mbaliga.fylz.network

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

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
    val writesEnabled: Boolean = false,
    val maximumDownloadBytes: Long = 8L * 1024L * 1024L * 1024L,
) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9._-]{1,80}")))
        require(displayName.isNotBlank())
        require(host.isNotBlank() && host.none(Char::isWhitespace))
        require(port in 1..65_535)
        require(share.isNotBlank() && '\\' !in share && '/' !in share)
        require(username.isNotBlank()) { "Anonymous SMB sessions are not supported." }
        require(password.isNotEmpty())
        require(maximumDownloadBytes in 1..64L * 1024L * 1024L * 1024L)
    }
}

/** SMB2/3 provider. SMB signing is mandatory and write operations are opt-in. */
class SmbProvider(private val config: SmbProviderConfig) : RemoteProvider, AutoCloseable {
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
            require(continuationToken == null) { "SMB listings are returned as one bounded page." }
            require(limit in 1..5_000)
            withShare { share ->
                val normalized = smbPath(path)
                val entries = share.list(normalized)
                    .asSequence()
                    .filterNot { it.fileName == "." || it.fileName == ".." }
                    .take(limit)
                    .map { info ->
                        val directory = EnumWithValue.EnumUtils.isSet(
                            info.fileAttributes,
                            FileAttributes.FILE_ATTRIBUTE_DIRECTORY,
                        )
                        RemoteObject(
                            key = RemotePathPolicy.child(RemotePathPolicy.normalize(path), info.fileName),
                            name = info.fileName,
                            directory = directory,
                            sizeBytes = if (directory) null else info.endOfFile.takeIf { it >= 0L },
                            modifiedAtMillis = runCatching { info.lastWriteTime.toEpochMillis() }.getOrNull(),
                            mimeType = null,
                        )
                    }
                    .toList()
                RemotePage(entries, continuationToken = null)
            }
        }

    override suspend fun download(
        path: String,
        destination: File,
        onProgress: (Long, Long?) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val parent = destination.parentFile ?: error("Download destination has no parent.")
        require(parent.isDirectory || parent.mkdirs()) { "Unable to prepare the local download folder." }
        val temporary = File(parent, ".${destination.name}.${System.nanoTime()}.part")
        try {
            withShare { share ->
                openRead(share, smbPath(path)).use { remote ->
                    val expected = runCatching {
                        remote.fileInformation.standardInformation.endOfFile
                    }.getOrNull()
                    expected?.let { require(it <= config.maximumDownloadBytes) { "Remote file exceeds the configured download limit." } }
                    remote.inputStream.use { input ->
                        temporary.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var completed = 0L
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                completed += count
                                require(completed <= config.maximumDownloadBytes) { "SMB download exceeded the configured limit." }
                                output.write(buffer, 0, count)
                                onProgress(completed, expected)
                            }
                        }
                    }
                    expected?.let { require(temporary.length() == it) { "SMB download size verification failed." } }
                }
            }
            if (destination.exists()) require(destination.delete()) { "Unable to replace the local download destination." }
            require(temporary.renameTo(destination)) { "Unable to commit the downloaded file." }
        } finally {
            temporary.delete()
        }
    }

    override suspend fun upload(path: String, source: File, mimeType: String) = withContext(Dispatchers.IO) {
        requireWrites()
        require(source.isFile)
        withShare { share ->
            val target = smbPath(path)
            val temporary = "$target.fylz-upload-${System.nanoTime()}"
            try {
                openWrite(share, temporary).use { remote ->
                    source.inputStream().buffered().use { input ->
                        remote.outputStream.use { output -> input.copyTo(output) }
                    }
                }
                require(share.getFileInformation(temporary).standardInformation.endOfFile == source.length()) {
                    "SMB upload size verification failed."
                }
                if (share.fileExists(target)) share.rm(target)
                share.openFile(
                    temporary,
                    EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
                ).use { it.rename(target, true) }
            } catch (failure: Throwable) {
                runCatching { if (share.fileExists(temporary)) share.rm(temporary) }
                throw failure
            }
        }
    }

    override suspend fun createDirectory(path: String) = withContext(Dispatchers.IO) {
        requireWrites()
        withShare { it.mkdir(smbPath(path)) }
    }

    override suspend fun delete(path: String, confirmed: Boolean) = withContext(Dispatchers.IO) {
        requireWrites()
        require(confirmed) { "Deleting a remote item requires explicit confirmation." }
        withShare { share ->
            val target = smbPath(path)
            if (share.folderExists(target)) share.rmdir(target, false) else share.rm(target)
        }
    }

    private fun openRead(share: DiskShare, path: String): SmbFile = share.openFile(
        path,
        EnumSet.of(AccessMask.GENERIC_READ),
        EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
        SMB2ShareAccess.ALL,
        SMB2CreateDisposition.FILE_OPEN,
        EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
    )

    private fun openWrite(share: DiskShare, path: String): SmbFile = share.openFile(
        path,
        EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.GENERIC_READ),
        EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
        SMB2ShareAccess.ALL,
        SMB2CreateDisposition.FILE_OVERWRITE_IF,
        EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
    )

    private fun smbPath(path: String): String = listOf(
        RemotePathPolicy.normalize(config.rootPath),
        RemotePathPolicy.normalize(path),
    ).filter(String::isNotBlank).joinToString("\\")

    private fun requireWrites() {
        require(config.writesEnabled) { "Writes are disabled for this SMB connection." }
    }

    private inline fun <T> withShare(block: (DiskShare) -> T): T {
        val smbConfig = SmbConfig.builder()
            .withSigningRequired(true)
            .withTimeout(60, TimeUnit.SECONDS)
            .withTransactTimeout(120, TimeUnit.SECONDS)
            .build()
        val client = SMBClient(smbConfig)
        try {
            client.connect(config.host, config.port).use { connection ->
                val auth = AuthenticationContext(
                    config.username,
                    config.password,
                    config.domain,
                )
                connection.authenticate(auth).use { session ->
                    val share = session.connectShare(config.share) as? DiskShare
                        ?: error("The selected SMB share is not a disk share.")
                    share.use { return block(it) }
                }
            }
        } finally {
            runCatching { client.close() }
        }
    }

    override fun close() {
        config.password.fill('\u0000')
    }
}
