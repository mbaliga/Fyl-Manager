package io.github.mbaliga.fylz.ai

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.coroutineContext

data class ModelPackFile(
    val relativePath: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
)

data class ModelPackManifest(
    val schemaVersion: Int,
    val packId: String,
    val version: String,
    val displayName: String,
    val licenseName: String,
    val licenseUrl: String,
    val files: List<ModelPackFile>,
    val signatureBase64: String,
    val signingKeyId: String,
) {
    val totalBytes: Long get() = files.sumOf(ModelPackFile::sizeBytes)
}

data class InstalledModelPack(
    val packId: String,
    val version: String,
    val displayName: String,
    val directory: File,
    val totalBytes: Long,
)

data class ModelPackInstallProgress(
    val currentFile: Int,
    val totalFiles: Int,
    val completedBytes: Long,
    val totalBytes: Long,
)

/**
 * Installs user-approved, signed multi-file model packs transactionally.
 *
 * The manager only handles provenance and bytes. It does not execute models and cannot approve or
 * perform file mutations; model outputs remain proposals for a separate review layer.
 */
class ModelPackManager(context: Context) {
    private val root = File(context.filesDir, "model-packs").apply { mkdirs() }

    fun decodeAndVerifyManifest(
        serialized: String,
        trustedPublicKeys: Map<String, String>,
    ): ModelPackManifest {
        require(serialized.length <= MAX_MANIFEST_CHARS) { "Model-pack manifest exceeds the safety limit." }
        val json = JSONObject(serialized)
        val signature = json.getString("signature")
        val keyId = json.getString("signingKeyId")
        val files = json.getJSONArray("files")
        require(files.length() in 1..MAX_FILES)
        val manifest = ModelPackManifest(
            schemaVersion = json.getInt("schemaVersion"),
            packId = json.getString("packId"),
            version = json.getString("version"),
            displayName = json.getString("displayName"),
            licenseName = json.getString("licenseName"),
            licenseUrl = json.getString("licenseUrl"),
            files = List(files.length()) { index ->
                val value = files.getJSONObject(index)
                ModelPackFile(
                    relativePath = value.getString("relativePath"),
                    downloadUrl = value.getString("downloadUrl"),
                    sha256 = value.getString("sha256").lowercase(),
                    sizeBytes = value.getLong("sizeBytes"),
                )
            },
            signatureBase64 = signature,
            signingKeyId = keyId,
        )
        validate(manifest)
        val key = trustedPublicKeys[keyId] ?: error("The model pack uses an untrusted signing key.")
        check(verifySignature(canonicalPayload(manifest), signature, decodePublicKey(key))) {
            "Model-pack signature verification failed."
        }
        return manifest
    }

    fun installedPacks(): List<InstalledModelPack> = root.listFiles().orEmpty()
        .filter(File::isDirectory)
        .mapNotNull(::readInstalled)
        .sortedBy(InstalledModelPack::displayName)

    fun installed(manifest: ModelPackManifest): Boolean {
        val directory = finalDirectory(manifest)
        if (!directory.isDirectory) return false
        return manifest.files.all { file ->
            val target = safeChild(directory, file.relativePath)
            target.isFile && target.length() == file.sizeBytes && sha256(target) == file.sha256
        }
    }

    suspend fun install(
        manifest: ModelPackManifest,
        userConfirmedDownloadAndLicense: Boolean,
        onProgress: (ModelPackInstallProgress) -> Unit = {},
    ): InstalledModelPack = withContext(Dispatchers.IO) {
        require(userConfirmedDownloadAndLicense) {
            "Model-pack download requires explicit confirmation of its size, source and license."
        }
        validate(manifest)
        if (installed(manifest)) return@withContext readInstalled(finalDirectory(manifest))!!

        val staging = File(root, ".${manifest.packId}-${UUID.randomUUID()}.staging")
        require(staging.mkdirs()) { "Unable to create model-pack staging storage." }
        var completedBytes = 0L
        try {
            manifest.files.forEachIndexed { index, descriptor ->
                coroutineContext.ensureActive()
                val target = safeChild(staging, descriptor.relativePath)
                require(target.parentFile?.mkdirs() != false)
                downloadFile(descriptor, target) { fileBytes ->
                    onProgress(
                        ModelPackInstallProgress(
                            currentFile = index + 1,
                            totalFiles = manifest.files.size,
                            completedBytes = completedBytes + fileBytes,
                            totalBytes = manifest.totalBytes,
                        ),
                    )
                }
                check(target.length() == descriptor.sizeBytes) { "Downloaded model-pack file has the wrong size." }
                check(sha256(target) == descriptor.sha256) { "Downloaded model-pack file failed checksum verification." }
                completedBytes += target.length()
            }
            File(staging, MANIFEST_FILE).writeText(encodeInstalledManifest(manifest))
            val final = finalDirectory(manifest)
            if (final.exists()) {
                val previous = File(root, ".${manifest.packId}-${UUID.randomUUID()}.previous")
                check(final.renameTo(previous)) { "Unable to preserve the previous model-pack version." }
                if (!staging.renameTo(final)) {
                    previous.renameTo(final)
                    error("Unable to commit the verified model pack.")
                }
                previous.deleteRecursively()
            } else {
                check(staging.renameTo(final)) { "Unable to commit the verified model pack." }
            }
            readInstalled(final) ?: error("Installed model-pack metadata is unreadable.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    fun remove(packId: String, confirmed: Boolean): Boolean {
        require(confirmed) { "Removing a model pack requires confirmation." }
        require(packId.matches(PACK_ID))
        val candidates = root.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("$packId@") }
        return candidates.all(File::deleteRecursively)
    }

    private fun validate(manifest: ModelPackManifest) {
        require(manifest.schemaVersion == 1)
        require(manifest.packId.matches(PACK_ID))
        require(manifest.version.matches(VERSION))
        require(manifest.displayName.isNotBlank() && manifest.displayName.length <= 160)
        require(manifest.licenseName.isNotBlank() && manifest.licenseName.length <= 160)
        requireHttps(manifest.licenseUrl)
        require(manifest.files.size in 1..MAX_FILES)
        require(manifest.totalBytes in 1..MAX_TOTAL_BYTES)
        val normalized = hashSetOf<String>()
        manifest.files.forEach { file ->
            validateRelativePath(file.relativePath)
            require(normalized.add(file.relativePath.lowercase())) { "Model pack contains duplicate paths." }
            requireHttps(file.downloadUrl)
            require(file.sha256.matches(SHA_256))
            require(file.sizeBytes in 1..MAX_FILE_BYTES)
        }
        require(manifest.signatureBase64.length <= 16_384)
        require(manifest.signingKeyId.matches(PACK_ID))
    }

    private suspend fun downloadFile(
        descriptor: ModelPackFile,
        destination: File,
        onBytes: (Long) -> Unit,
    ) {
        val connection = URL(descriptor.downloadUrl).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.requestMethod = "GET"
            connection.connect()
            check(connection.responseCode in 200..299) { "Model-pack download failed with HTTP ${connection.responseCode}." }
            connection.contentLengthLong.takeIf { it >= 0L }?.let { reported ->
                require(reported == descriptor.sizeBytes) { "Model-pack server reported an unexpected file size." }
            }
            var completed = 0L
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        completed += count
                        require(completed <= descriptor.sizeBytes) { "Model-pack download exceeded its declared size." }
                        output.write(buffer, 0, count)
                        onBytes(completed)
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun canonicalPayload(manifest: ModelPackManifest): ByteArray = JSONObject().apply {
        put("schemaVersion", manifest.schemaVersion)
        put("packId", manifest.packId)
        put("version", manifest.version)
        put("displayName", manifest.displayName)
        put("licenseName", manifest.licenseName)
        put("licenseUrl", manifest.licenseUrl)
        put("signingKeyId", manifest.signingKeyId)
        put("files", JSONArray().apply {
            manifest.files.sortedBy(ModelPackFile::relativePath).forEach { file ->
                put(JSONObject().apply {
                    put("relativePath", file.relativePath)
                    put("downloadUrl", file.downloadUrl)
                    put("sha256", file.sha256)
                    put("sizeBytes", file.sizeBytes)
                })
            }
        })
    }.toString().toByteArray(Charsets.UTF_8)

    private fun verifySignature(payload: ByteArray, signatureBase64: String, key: PublicKey): Boolean = runCatching {
        Signature.getInstance("SHA256withRSA").run {
            initVerify(key)
            update(payload)
            verify(Base64.getDecoder().decode(signatureBase64))
        }
    }.getOrDefault(false)

    private fun decodePublicKey(encoded: String): PublicKey {
        val bytes = Base64.getDecoder().decode(encoded.replace(Regex("-----[^-]+-----|\\s"), ""))
        return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes))
    }

    private fun encodeInstalledManifest(manifest: ModelPackManifest): String = JSONObject().apply {
        put("packId", manifest.packId)
        put("version", manifest.version)
        put("displayName", manifest.displayName)
        put("licenseName", manifest.licenseName)
        put("licenseUrl", manifest.licenseUrl)
        put("totalBytes", manifest.totalBytes)
    }.toString()

    private fun readInstalled(directory: File): InstalledModelPack? = runCatching {
        val value = JSONObject(File(directory, MANIFEST_FILE).readText())
        InstalledModelPack(
            packId = value.getString("packId"),
            version = value.getString("version"),
            displayName = value.getString("displayName"),
            directory = directory,
            totalBytes = value.getLong("totalBytes"),
        )
    }.getOrNull()

    private fun finalDirectory(manifest: ModelPackManifest) = File(root, "${manifest.packId}@${manifest.version}")

    private fun safeChild(parent: File, relativePath: String): File {
        val child = File(parent, relativePath)
        val rootPath = parent.canonicalFile.toPath()
        require(child.canonicalFile.toPath().startsWith(rootPath)) { "Model-pack path escapes its installation directory." }
        return child
    }

    private fun validateRelativePath(value: String) {
        require(value.isNotBlank() && value.length <= 1_024)
        require(!value.startsWith('/') && '\\' !in value)
        val parts = value.split('/')
        require(parts.size <= 32)
        require(parts.none { it.isBlank() || it == "." || it == ".." || it.length > 255 })
    }

    private fun requireHttps(value: String) {
        val uri = URI(value)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        private const val MANIFEST_FILE = "fylz-model-pack.json"
        private const val MAX_MANIFEST_CHARS = 4 * 1024 * 1024
        private const val MAX_FILES = 128
        private const val MAX_FILE_BYTES = 8L * 1024L * 1024L * 1024L
        private const val MAX_TOTAL_BYTES = 16L * 1024L * 1024L * 1024L
        private val PACK_ID = Regex("[a-zA-Z0-9._-]{1,80}")
        private val VERSION = Regex("[a-zA-Z0-9._+-]{1,80}")
        private val SHA_256 = Regex("[a-f0-9]{64}")
    }
}
