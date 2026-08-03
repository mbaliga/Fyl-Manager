package io.github.mbaliga.fylz.ai

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import kotlin.coroutines.coroutineContext

data class ModelPackFile(
    val path: String,
    val sha256: String,
    val sizeBytes: Long,
)

data class SignedModelPackManifest(
    val id: String,
    val version: String,
    val displayName: String,
    val licenseName: String,
    val licenseUrl: String,
    val sourceUrl: String?,
    val files: List<ModelPackFile>,
    val signerKeyId: String,
    val signatureBase64: String,
) {
    val totalBytes: Long get() = files.sumOf(ModelPackFile::sizeBytes)
}

data class TrustedModelSigner(
    val keyId: String,
    val displayName: String,
    val x509PublicKeyBase64: String,
)

data class ModelPackVerification(
    val valid: Boolean,
    val reason: String? = null,
    val manifest: SignedModelPackManifest? = null,
)

/**
 * Verifies Ed25519-signed model-pack manifests and atomically installs caller-supplied files.
 * It never downloads a pack silently and never executes model code.
 */
class ModelPackService(context: Context) {
    private val root = File(context.filesDir, "model-packs").apply { mkdirs() }

    fun parseAndVerify(
        manifestJson: String,
        trustedSigners: Collection<TrustedModelSigner>,
    ): ModelPackVerification = runCatching {
        require(manifestJson.length <= MAX_MANIFEST_CHARS) { "Model-pack manifest is too large." }
        val root = JSONObject(manifestJson)
        require(root.optInt("schemaVersion", 0) == SCHEMA_VERSION) { "Unsupported model-pack manifest version." }
        val signature = root.getString("signature")
        val signerKeyId = root.getString("signerKeyId")
        val filesJson = root.getJSONArray("files")
        require(filesJson.length() in 1..MAX_FILES) { "Model-pack file count is outside the safety limit." }
        val files = List(filesJson.length()) { index ->
            val item = filesJson.getJSONObject(index)
            ModelPackFile(
                path = validatedPath(item.getString("path")),
                sha256 = item.getString("sha256").lowercase().also {
                    require(it.matches(SHA_256)) { "Invalid SHA-256 for a model-pack file." }
                },
                sizeBytes = item.getLong("sizeBytes").also {
                    require(it in 0..MAX_FILE_BYTES) { "A model-pack file exceeds the size limit." }
                },
            )
        }
        require(files.map { it.path.lowercase() }.distinct().size == files.size) {
            "Model-pack paths collide by case."
        }
        require(files.sumOf(ModelPackFile::sizeBytes) <= MAX_PACK_BYTES) { "Model pack exceeds the total-size limit." }
        val manifest = SignedModelPackManifest(
            id = root.getString("id").also(::validateId),
            version = root.getString("version").also(::validateVersion),
            displayName = root.getString("displayName").take(160).also { require(it.isNotBlank()) },
            licenseName = root.getString("licenseName").take(160).also { require(it.isNotBlank()) },
            licenseUrl = root.getString("licenseUrl").also(::validateHttpsUrl),
            sourceUrl = root.optString("sourceUrl").takeIf(String::isNotBlank)?.also(::validateHttpsUrl),
            files = files,
            signerKeyId = signerKeyId,
            signatureBase64 = signature,
        )
        val signer = trustedSigners.firstOrNull { it.keyId == signerKeyId }
            ?: return ModelPackVerification(false, "The model-pack signer is not trusted.", manifest)
        val verified = verifyEd25519(canonicalPayload(manifest), signature, signer.x509PublicKeyBase64)
        if (!verified) ModelPackVerification(false, "Model-pack signature verification failed.", manifest)
        else ModelPackVerification(true, manifest = manifest)
    }.getOrElse { ModelPackVerification(false, it.message ?: "Model-pack manifest is invalid.") }

    suspend fun install(
        manifest: SignedModelPackManifest,
        sourceFiles: Map<String, File>,
        userAcceptedLicense: Boolean,
        onProgress: (completedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        require(userAcceptedLicense) { "Installing a model pack requires explicit licence acceptance." }
        require(sourceFiles.keys == manifest.files.map(ModelPackFile::path).toSet()) {
            "The supplied files do not match the signed manifest."
        }
        val staging = File(root, ".install-${manifest.id}-${UUID.randomUUID()}")
        require(staging.mkdirs()) { "Unable to create model-pack staging storage." }
        var completed = 0L
        try {
            manifest.files.forEach { expected ->
                coroutineContext.ensureActive()
                val source = sourceFiles.getValue(expected.path)
                require(source.isFile && source.length() == expected.sizeBytes) {
                    "${expected.path} does not match its declared size."
                }
                val destination = File(staging, expected.path)
                require(destination.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                    "Unsafe model-pack path."
                }
                destination.parentFile?.mkdirs()
                val digest = MessageDigest.getInstance("SHA-256")
                source.inputStream().use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            completed += count
                            require(completed <= manifest.totalBytes) { "Model-pack input exceeds its signed size." }
                            onProgress(completed, manifest.totalBytes)
                        }
                        output.fd.sync()
                    }
                }
                require(digest.digest().toHex() == expected.sha256) {
                    "${expected.path} failed checksum verification."
                }
            }
            File(staging, MANIFEST_FILE).writeText(encodeInstalledManifest(manifest), Charsets.UTF_8)
            val destination = File(root, "${manifest.id}-${manifest.version}")
            if (destination.exists()) require(deleteRecursivelySafe(destination)) { "Unable to replace the existing model pack." }
            require(staging.renameTo(destination)) { "Unable to commit the verified model pack." }
            destination
        } finally {
            if (staging.exists()) deleteRecursivelySafe(staging)
        }
    }

    fun installedPacks(): List<File> = root.listFiles().orEmpty()
        .filter { it.isDirectory && File(it, MANIFEST_FILE).isFile }
        .sortedBy(File::name)

    fun remove(packDirectory: File, confirmed: Boolean): Boolean {
        require(confirmed) { "Removing a model pack requires confirmation." }
        require(packDirectory.parentFile?.canonicalFile == root.canonicalFile) { "Invalid model-pack location." }
        return deleteRecursivelySafe(packDirectory)
    }

    private fun verifyEd25519(payload: ByteArray, signatureBase64: String, publicKeyBase64: String): Boolean = runCatching {
        val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
            X509EncodedKeySpec(Base64.decode(publicKeyBase64, Base64.DEFAULT)),
        )
        Signature.getInstance("Ed25519").run {
            initVerify(publicKey)
            update(payload)
            verify(Base64.decode(signatureBase64, Base64.DEFAULT))
        }
    }.getOrDefault(false)

    private fun canonicalPayload(value: SignedModelPackManifest): ByteArray = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("id", value.id)
        put("version", value.version)
        put("displayName", value.displayName)
        put("licenseName", value.licenseName)
        put("licenseUrl", value.licenseUrl)
        put("sourceUrl", value.sourceUrl ?: JSONObject.NULL)
        put("signerKeyId", value.signerKeyId)
        put("files", JSONArray().apply {
            value.files.sortedBy(ModelPackFile::path).forEach { file ->
                put(JSONObject().apply {
                    put("path", file.path)
                    put("sha256", file.sha256)
                    put("sizeBytes", file.sizeBytes)
                })
            }
        })
    }.toString().toByteArray(Charsets.UTF_8)

    private fun encodeInstalledManifest(value: SignedModelPackManifest): String = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("id", value.id)
        put("version", value.version)
        put("displayName", value.displayName)
        put("licenseName", value.licenseName)
        put("licenseUrl", value.licenseUrl)
        put("sourceUrl", value.sourceUrl ?: JSONObject.NULL)
        put("signerKeyId", value.signerKeyId)
        put("signature", value.signatureBase64)
        put("files", JSONArray().apply {
            value.files.forEach { file ->
                put(JSONObject().put("path", file.path).put("sha256", file.sha256).put("sizeBytes", file.sizeBytes))
            }
        })
    }.toString()

    private fun validatedPath(value: String): String {
        require(value.isNotBlank() && !value.startsWith('/') && '\\' !in value)
        val segments = value.split('/')
        require(segments.none { it.isBlank() || it == "." || it == ".." })
        require(segments.size <= 16 && segments.all { it.length <= 160 })
        return value
    }

    private fun validateId(value: String) {
        require(value.matches(Regex("[a-zA-Z0-9._-]{1,80}"))) { "Invalid model-pack id." }
    }

    private fun validateVersion(value: String) {
        require(value.matches(Regex("[a-zA-Z0-9._+-]{1,80}"))) { "Invalid model-pack version." }
    }

    private fun validateHttpsUrl(value: String) {
        val uri = java.net.URI(value)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "Model-pack URLs must use HTTPS and must not embed credentials."
        }
    }

    private fun deleteRecursivelySafe(file: File): Boolean {
        if (!file.exists()) return true
        if (file.isDirectory) file.listFiles().orEmpty().forEach { child -> if (!deleteRecursivelySafe(child)) return false }
        return file.delete()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val MANIFEST_FILE = "manifest.json"
        const val MAX_MANIFEST_CHARS = 2_000_000
        const val MAX_FILES = 2_000
        const val MAX_FILE_BYTES = 8L * 1024L * 1024L * 1024L
        const val MAX_PACK_BYTES = 16L * 1024L * 1024L * 1024L
        val SHA_256 = Regex("[0-9a-f]{64}")
    }
}
