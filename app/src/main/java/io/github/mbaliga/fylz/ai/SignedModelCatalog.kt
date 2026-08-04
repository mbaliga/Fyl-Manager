package io.github.mbaliga.fylz.ai

import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

data class SignedModelCatalog(
    val schemaVersion: Int,
    val catalogId: String,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long?,
    val models: List<LocalModelDescriptor>,
)

/** Verifies a detached Ed25519 signature before parsing a downloadable model catalogue. */
object SignedModelCatalogVerifier {
    const val MAX_CATALOG_BYTES = 2 * 1024 * 1024
    const val MAX_MODELS = 1_000

    fun verifyAndParse(
        canonicalJson: ByteArray,
        signatureBase64: String,
        publicKeyX509Base64: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): SignedModelCatalog {
        require(canonicalJson.size in 1..MAX_CATALOG_BYTES) { "Model catalogue exceeds the safety limit." }
        val signatureBytes = Base64.getDecoder().decode(signatureBase64.trim())
        require(signatureBytes.size == 64) { "Invalid Ed25519 signature length." }
        val publicKey = decodePublicKey(publicKeyX509Base64)
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(publicKey)
        verifier.update(canonicalJson)
        require(verifier.verify(signatureBytes)) { "Model catalogue signature verification failed." }

        val root = JSONObject(canonicalJson.toString(Charsets.UTF_8))
        val schema = root.getInt("schemaVersion")
        require(schema == 1) { "Unsupported model catalogue schema." }
        val catalogId = root.getString("catalogId")
        require(catalogId.matches(Regex("[a-zA-Z0-9._-]{1,100}"))) { "Invalid catalogue id." }
        val issued = root.getLong("issuedAtMillis")
        require(issued <= nowMillis + MAX_CLOCK_SKEW_MILLIS) { "Model catalogue is dated in the future." }
        val expires = root.optLongOrNull("expiresAtMillis")
        require(expires == null || expires >= nowMillis) { "Model catalogue has expired." }
        val array = root.getJSONArray("models")
        require(array.length() <= MAX_MODELS) { "Model catalogue contains too many models." }
        val models = buildList {
            repeat(array.length()) { index -> add(decodeModel(array.getJSONObject(index))) }
        }
        require(models.map(LocalModelDescriptor::id).distinct().size == models.size) {
            "Model catalogue contains duplicate model ids."
        }
        return SignedModelCatalog(schema, catalogId, issued, expires, models)
    }

    private fun decodeModel(value: JSONObject): LocalModelDescriptor {
        val descriptor = LocalModelDescriptor(
            id = value.getString("id"),
            displayName = value.getString("displayName").take(200),
            downloadUrl = value.getString("downloadUrl"),
            sha256 = value.getString("sha256").lowercase(),
            expectedBytes = value.optLongOrNull("expectedBytes"),
            licenseName = value.getString("licenseName").take(200),
            licenseUrl = value.getString("licenseUrl"),
        )
        require(descriptor.id.matches(Regex("[a-zA-Z0-9._-]{1,80}")))
        require(descriptor.sha256.matches(Regex("[a-f0-9]{64}")))
        require(descriptor.downloadUrl.startsWith("https://"))
        require(descriptor.licenseUrl.startsWith("https://"))
        require(descriptor.expectedBytes == null || descriptor.expectedBytes in 1..MAX_MODEL_BYTES)
        return descriptor
    }

    private fun decodePublicKey(value: String): PublicKey {
        val bytes = Base64.getDecoder().decode(value.trim())
        require(bytes.size <= 256) { "Invalid catalogue public key." }
        return KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(bytes))
    }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (!has(key) || isNull(key)) null else getLong(key)

    private const val MAX_CLOCK_SKEW_MILLIS = 10L * 60L * 1_000L
    private const val MAX_MODEL_BYTES = 20L * 1024L * 1024L * 1024L
}
