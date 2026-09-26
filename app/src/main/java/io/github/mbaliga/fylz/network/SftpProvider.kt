package io.github.mbaliga.fylz.network

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.transport.verification.FingerprintVerifier
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
        require(isPlausibleHostKeyFingerprint(pinnedHostKeySha256)) {
            "SFTP requires a pinned host-key fingerprint: SHA-256 (SHA256:xxxx or bare base64) " +
                "or legacy MD5 (aa:bb:cc:...)."
        }
        require(maximumDownloadBytes in 1..64L * 1024L * 1024L * 1024L)
    }
}

/**
 * SFTP provider with mandatory host-key pinning and short-lived connections.
 *
 * Verification (P0.11) delegates to sshj's own [FingerprintVerifier] rather than hand-rolled
 * comparison logic -- see [hostKeyVerifierFor] and [sha256Fingerprint] for why that matters.
 */
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

    override suspend fun upload(path: String, source: File, mimeType: String) = withContext(Dispatchers.IO) {
        requireWrites()
        require(source.isFile)
        withClient { sftp ->
            val target = remotePath(path)
            val temporary = "$target.fylz-upload-${System.nanoTime()}"
            try {
                sftp.put(source.absolutePath, temporary)
                require(sftp.stat(temporary).size == source.length()) { "SFTP upload size verification failed." }
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
        withClient { it.mkdirs(remotePath(path)) }
    }

    override suspend fun delete(path: String, confirmed: Boolean) = withContext(Dispatchers.IO) {
        requireWrites()
        require(confirmed) { "Deleting a remote item requires explicit confirmation." }
        withClient { sftp ->
            val remote = remotePath(path)
            if (sftp.stat(remote).type.name.equals("DIRECTORY", ignoreCase = true)) sftp.rmdir(remote)
            else sftp.rm(remote)
        }
    }

    private fun remotePath(path: String): String {
        val joined = listOf(
            RemotePathPolicy.normalize(config.rootPath),
            RemotePathPolicy.normalize(path),
        ).filter(String::isNotBlank).joinToString("/")
        return if (joined.isBlank()) "." else "/$joined"
    }

    private fun requireWrites() {
        require(config.writesEnabled) { "Writes are disabled for this SFTP connection." }
    }

    private inline fun <T> withClient(block: (net.schmizz.sshj.sftp.SFTPClient) -> T): T {
        val passwordString = config.password.concatToString()
        val ssh = SSHClient()
        try {
            ssh.connectTimeout = 20_000
            ssh.timeout = 60_000
            ssh.addHostKeyVerifier(hostKeyVerifierFor(config.pinnedHostKeySha256))
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

    companion object {
        /**
         * Trust-on-first-use, step 1 (P0.11, defect 11): connects once with no credentials and a
         * verifier that captures the presented host key and always rejects it, so the connection
         * never completes unverified. Host-key exchange happens before authentication, so this
         * needs neither a username nor a password. The caller shows the returned fingerprint to
         * the user and pins it (into a [SftpProviderConfig]) only after they explicitly confirm
         * it -- steps 2-3 are a UI concern, not this provider's.
         */
        suspend fun probeHostKeyFingerprint(host: String, port: Int): String = withContext(Dispatchers.IO) {
            val capturing = CapturingHostKeyVerifier()
            val ssh = SSHClient()
            try {
                ssh.connectTimeout = 20_000
                ssh.addHostKeyVerifier(capturing)
                runCatching { ssh.connect(host, port) }
            } finally {
                runCatching { ssh.disconnect() }
                runCatching { ssh.close() }
            }
            sha256Fingerprint(capturing.capturedKey ?: error("The server did not present a host key."))
        }
    }
}

/** Always rejects -- trust-on-first-use must never let a probe connection complete as trusted --
 * but remembers the key the server presented so the caller can show its fingerprint to the user. */
internal class CapturingHostKeyVerifier : HostKeyVerifier {
    var capturedKey: PublicKey? = null
        private set

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        capturedKey = key
        return false
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
}

/**
 * The OpenSSH SHA-256 fingerprint of [key]: SHA-256 of the SSH wire-format public-key blob
 * (`Buffer.PlainBuffer().putPublicKey(key).compactData`), base64 without padding, `SHA256:`
 * prefixed -- exactly what `ssh-keygen -lf` prints.
 *
 * This is the fix for P0.11, defect 11: [PublicKey.getEncoded] is X.509/DER, a *different*
 * encoding of the same key. Hashing that instead (the pre-fix code) produced a value that could
 * never match a real OpenSSH fingerprint no matter how carefully a user copied one in --
 * host-key pinning was silently unenforceable.
 */
internal fun sha256Fingerprint(key: PublicKey): String {
    val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
    val digest = MessageDigest.getInstance("SHA-256").digest(blob)
    return "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP).trimEnd('=')
}

/**
 * sshj's own [FingerprintVerifier] (P0.11) instead of hand-rolled comparison logic. Accepts a
 * bare or `SHA256:`-prefixed base64 SHA-256 fingerprint, or a legacy colon-hex MD5 one (the
 * format sshj's own `SecurityUtils.getFingerprint` produces), with or without an `MD5:` prefix --
 * whichever shape [config's pinned value][SftpProviderConfig.pinnedHostKeySha256] happens to be
 * in, `FingerprintVerifier.getInstance` requires an explicit `SHA256:`/`SHA1:`/`MD5:` prefix only
 * for the non-MD5-colon-hex forms, so bare base64 is normalized here before delegating.
 */
internal fun hostKeyVerifierFor(fingerprint: String): HostKeyVerifier {
    val trimmed = fingerprint.trim()
    val normalized = when {
        trimmed.startsWith("SHA256:", ignoreCase = true) ||
            trimmed.startsWith("SHA1:", ignoreCase = true) ||
            trimmed.startsWith("MD5:", ignoreCase = true) -> trimmed
        MD5_COLON_HEX.matches(trimmed) -> trimmed
        else -> "SHA256:${trimmed.trimEnd('=')}"
    }
    return FingerprintVerifier.getInstance(normalized)
}

/** A cheap, fail-fast shape check for [SftpProviderConfig]'s `init` block -- not the authoritative
 * validator (that's [hostKeyVerifierFor] via `FingerprintVerifier.getInstance` at connection
 * time), just enough to catch blank or obviously-wrong input before ever attempting a connection. */
internal fun isPlausibleHostKeyFingerprint(value: String): Boolean {
    val trimmed = value.trim()
    val stripped = when {
        trimmed.startsWith("SHA256:", ignoreCase = true) -> trimmed.removePrefix("SHA256:")
        trimmed.startsWith("SHA1:", ignoreCase = true) -> return trimmed.removePrefix("SHA1:").trimEnd('=').isNotBlank()
        trimmed.startsWith("MD5:", ignoreCase = true) -> trimmed.removePrefix("MD5:")
        else -> trimmed
    }
    return MD5_COLON_HEX.matches(stripped) || SHA256_BASE64.matches(stripped.trimEnd('='))
}

private val MD5_COLON_HEX = Regex("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){15}$")
private val SHA256_BASE64 = Regex("^[A-Za-z0-9+/]{40,44}$")
