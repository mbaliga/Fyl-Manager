package io.github.mbaliga.fylz.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class AiProviderConfig(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val model: String,
)

data class AiProposal(
    val summary: String,
    val suggestedFolder: String?,
    val suggestedName: String?,
    val suggestedTags: List<String>,
    val rawResponse: String,
)

/** OpenAI-compatible BYOK proposal client. It never mutates files or executes suggestions. */
class AiClient(private val vault: ApiKeyVault) {
    suspend fun proposeOrganization(
        config: AiProviderConfig,
        preview: AiTransmissionPreview,
        approvedPayloadSha256: String,
    ): AiProposal = withContext(Dispatchers.IO) {
        require(preview.allowed) { preview.blockReason ?: "This transmission is blocked by policy." }
        require(approvedPayloadSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid approval fingerprint." }
        val payload = canonicalPayload(preview)
        val currentHash = sha256(payload)
        require(currentHash == preview.payloadSha256 && currentHash == approvedPayloadSha256) {
            "The approved transmission preview no longer matches the payload. Review it again."
        }
        val endpointUri = validateEndpoint(config.baseUrl)
        require(preview.destinationHost.equals(endpointUri.host, ignoreCase = true)) {
            "The approved destination does not match this provider."
        }
        val key = vault.read(config.id) ?: error("No API key is stored for ${config.displayName}.")
        try {
            val request = JSONObject()
                .put("model", config.model)
                .put("temperature", 0.1)
                .put(
                    "messages",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("role", "system")
                                .put(
                                    "content",
                                    "Return strict JSON with summary, suggestedFolder, suggestedName, and suggestedTags. Do not include commands or claim to modify files.",
                                ),
                        )
                        .put(JSONObject().put("role", "user").put("content", payload)),
                )
            execute(config, key, request)
        } finally {
            key.fill('\u0000')
        }
    }

    /** Compatibility path; new UI should display AiTransmissionPolicy.preview and use the fingerprint overload. */
    @Deprecated("Show and approve an AiTransmissionPreview before transmission")
    suspend fun proposeOrganization(
        config: AiProviderConfig,
        fileName: String,
        mimeType: String,
        boundedText: String?,
        userApprovedTransmission: Boolean,
    ): AiProposal {
        require(userApprovedTransmission) { "Remote analysis requires explicit user approval." }
        val preview = AiTransmissionPolicy.preview(
            AiTransmissionRequest(
                providerId = config.id,
                providerName = config.displayName,
                endpoint = config.baseUrl,
                model = config.model,
                fileName = fileName,
                mimeType = mimeType,
                content = boundedText,
            ),
        )
        return proposeOrganization(config, preview, preview.payloadSha256)
    }

    private suspend fun execute(config: AiProviderConfig, key: CharArray, request: JSONObject): AiProposal {
        val endpoint = config.baseUrl.trimEnd('/') + "/chat/completions"
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${key.concatToString()}")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { output -> output.write(request.toString().encodeToByteArray()) }
            coroutineContext.ensureActive()
            val responseCode = connection.responseCode
            val responseBody = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { reader -> readBounded(reader, MAX_RESPONSE_CHARS) }
                .orEmpty()
            check(responseCode in 200..299) {
                "Provider request failed with HTTP $responseCode. ${responseBody.take(300)}"
            }
            return parseProposal(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun canonicalPayload(preview: AiTransmissionPreview): String = buildString {
        preview.transmittedFileName?.let { append("File name: ").append(it).append('\n') }
        preview.transmittedMimeType?.let { append("MIME type: ").append(it).append('\n') }
        preview.transmittedContent?.let { append("Content:\n").append(it) }
    }

    private fun parseProposal(raw: String): AiProposal {
        val outer = JSONObject(raw)
        val choices = outer.optJSONArray("choices") ?: error("Provider response contains no choices.")
        require(choices.length() in 1..100) { "Provider returned an invalid choice count." }
        val content = choices.getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        require(content.length <= MAX_RESPONSE_CHARS) { "Provider proposal is too large." }
        val proposal = JSONObject(content)
        val tags = proposal.optJSONArray("suggestedTags")
        return AiProposal(
            summary = proposal.optString("summary").take(MAX_FIELD_CHARS),
            suggestedFolder = proposal.optString("suggestedFolder").takeIf(String::isNotBlank)?.take(MAX_FIELD_CHARS),
            suggestedName = proposal.optString("suggestedName").takeIf(String::isNotBlank)?.take(MAX_FIELD_CHARS),
            suggestedTags = buildList {
                if (tags != null) repeat(minOf(tags.length(), MAX_TAGS)) { index ->
                    tags.optString(index).trim().takeIf(String::isNotBlank)?.let { add(it.take(80)) }
                }
            }.distinctBy(String::lowercase),
            rawResponse = raw,
        )
    }

    private fun validateEndpoint(baseUrl: String): URI {
        val uri = URI(baseUrl)
        val local = uri.host?.lowercase() in setOf("localhost", "127.0.0.1", "::1")
        require(uri.scheme == "https" || local && uri.scheme == "http") {
            "Remote AI endpoints must use HTTPS. Plain HTTP is allowed only for localhost."
        }
        require(!uri.host.isNullOrBlank()) { "AI endpoint host is missing." }
        require(uri.userInfo == null && uri.fragment == null) {
            "Credentials and fragments must not be embedded in the endpoint URL."
        }
        return uri
    }

    private fun readBounded(reader: java.io.Reader, maximum: Int): String {
        val output = StringBuilder()
        val buffer = CharArray(8_192)
        while (output.length < maximum) {
            val count = reader.read(buffer, 0, minOf(buffer.size, maximum - output.length))
            if (count < 0) return output.toString()
            output.append(buffer, 0, count)
        }
        if (reader.read() >= 0) error("Provider response exceeds the safety limit.")
        return output.toString()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val MAX_RESPONSE_CHARS = 256_000
        const val MAX_FIELD_CHARS = 500
        const val MAX_TAGS = 20
    }
}
