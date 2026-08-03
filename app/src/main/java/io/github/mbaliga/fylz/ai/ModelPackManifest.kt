package io.github.mbaliga.fylz.ai

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

data class ModelPackFile(
    val path: String,
    val url: String,
    val sha256: String,
    val bytes: Long,
) {
    init {
        require(path.matches(Regex("[a-zA-Z0-9._/-]{1,240}")))
        require(!path.startsWith('/') && ".." !in path.split('/'))
        require(url.startsWith("https://"))
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        require(bytes in 1..MAX_MODEL_FILE_BYTES)
    }

    companion object { const val MAX_MODEL_FILE_BYTES = 16L * 1024L * 1024L * 1024L }
}

data class ModelPackManifest(
    val schemaVersion: Int = 1,
    val id: String,
    val version: String,
    val displayName: String,
    val description: String,
    val licenseName: String,
    val licenseUrl: String,
    val runtime: String,
    val minimumRamBytes: Long?,
    val files: List<ModelPackFile>,
) {
    init {
        require(schemaVersion == 1)
        require(id.matches(Regex("[a-zA-Z0-9._-]{1,80}")))
        require(version.matches(Regex("[a-zA-Z0-9._+-]{1,60}")))
        require(displayName.isNotBlank() && displayName.length <= 160)
        require(description.length <= 2_000)
        require(licenseName.isNotBlank() && licenseName.length <= 160)
        require(licenseUrl.startsWith("https://"))
        require(runtime in SUPPORTED_RUNTIMES)
        require(minimumRamBytes == null || minimumRamBytes in 1..128L * 1024L * 1024L * 1024L)
        require(files.isNotEmpty() && files.size <= 64)
        require(files.map(ModelPackFile::path).distinct().size == files.size)
        require(files.sumOf(ModelPackFile::bytes) <= MAX_PACK_BYTES)
    }

    companion object {
        val SUPPORTED_RUNTIMES = setOf("gguf", "onnx", "tflite", "sentencepiece", "custom")
        const val MAX_PACK_BYTES = 32L * 1024L * 1024L * 1024L
    }
}

data class SignedModelPackManifest(
    val manifest: ModelPackManifest,
    val keyId: String,
    val signatureBase64: String,
)

object ModelPackManifestCodec {
    fun parseSigned(value: String): SignedModelPackManifest {
        require(value.toByteArray().size <= MAX_MANIFEST_BYTES)
        val root = JSONObject(value)
        val manifestObject = root.getJSONObject("manifest")
        return SignedModelPackManifest(
            manifest = parseManifest(manifestObject),
            keyId = root.getString("keyId").also { require(it.matches(Regex("[a-zA-Z0-9._-]{1,80}"))) },
            signatureBase64 = root.getString("signature").also { require(it.length <= 512) },
        )
    }

    fun canonicalBytes(manifest: ModelPackManifest): ByteArray = canonicalJson(manifest).toByteArray(StandardCharsets.UTF_8)

    fun canonicalJson(value: ModelPackManifest): String = JSONObject().apply {
        put("schemaVersion", value.schemaVersion)
        put("id", value.id)
        put("version", value.version)
        put("displayName", value.displayName)
        put("description", value.description)
        put("licenseName", value.licenseName)
        put("licenseUrl", value.licenseUrl)
        put("runtime", value.runtime)
        put("minimumRamBytes", value.minimumRamBytes ?: JSONObject.NULL)
        put("files", JSONArray().apply {
            value.files.sortedBy(ModelPackFile::path).forEach { file ->
                put(JSONObject().apply {
                    put("path", file.path)
                    put("url", file.url)
                    put("sha256", file.sha256)
                    put("bytes", file.bytes)
                })
            }
        })
    }.toString()

    private fun parseManifest(value: JSONObject): ModelPackManifest {
        val files = value.getJSONArray("files")
        return ModelPackManifest(
            schemaVersion = value.getInt("schemaVersion"),
            id = value.getString("id"),
            version = value.getString("version"),
            displayName = value.getString("displayName"),
            description = value.optString("description"),
            licenseName = value.getString("licenseName"),
            licenseUrl = value.getString("licenseUrl"),
            runtime = value.getString("runtime"),
            minimumRamBytes = if (value.isNull("minimumRamBytes")) null else value.getLong("minimumRamBytes"),
            files = List(files.length()) { index -> files.getJSONObject(index).let { file -> ModelPackFile(
                path = file.getString("path"), url = file.getString("url"),
                sha256 = file.getString("sha256").lowercase(), bytes = file.getLong("bytes"),
            ) } },
        )
    }

    private const val MAX_MANIFEST_BYTES = 1 * 1024 * 1024
}

class ModelPackTrustStore(private val trustedKeys: Map<String, ByteArray>) {
    fun verify(value: SignedModelPackManifest): Boolean {
        val keyBytes = trustedKeys[value.keyId] ?: return false
        val signatureBytes = runCatching { Base64.decode(value.signatureBase64, Base64.DEFAULT) }.getOrNull() ?: return false
        return runCatching {
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(keyBytes))
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(ModelPackManifestCodec.canonicalBytes(value.manifest))
                verify(signatureBytes)
            }
        }.getOrDefault(false)
    }

    fun fingerprint(keyId: String): String? = trustedKeys[keyId]?.let { bytes ->
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
