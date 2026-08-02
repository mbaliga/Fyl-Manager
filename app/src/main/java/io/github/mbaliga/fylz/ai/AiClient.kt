package io.github.mbaliga.fylz.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
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

/**
 * Minimal OpenAI-compatible BYOK client.
 *
 * It returns proposals only. It never renames, moves, deletes, uploads, or mutates a file. Callers
 * must show exactly what metadata/content will be sent and obtain explicit approval before calling.
 */
class AiClient(private val vault: ApiKeyVault) {
    suspend fun proposeOrganization(
        config: AiProviderConfig,
        fileName: String,
        mimeType: String,
        boundedText: String?,
        userApprovedTransmission: Boolean,
    ): AiProposal = withContext(Dispatchers.IO) {
        require(userApprovedTransmission) { "Remote analysis requires explicit user approval." }
        validateEndpoint(config.baseUrl)
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
                        .put(
                            JSONObject()
                                .put("role", "user")
                                .put(
                                    "content",
                                    buildString {
                                        append("File name: ").append(fileName).append('\n')
                                        append("MIME type: ").append(mimeType).append('\n')
                                        if (boundedText != null) {
                                            append("Bounded content:\n")
                                            append(boundedText.take(MAX_REMOTE_CHARS))
                                        }
                                    },
                                ),
                        ),
                )

            val endpoint = config.baseUrl.trimEnd('/') + "/chat/completions"
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 20_000
                connection.readTimeout = 60_000
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer ${key.concatToString()}")
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { output ->
                    output.write(request.toString().encodeToByteArray())
                }
                coroutineContext.ensureActive()
                val responseCode = connection.responseCode
                val responseBody = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText().take(MAX_RESPONSE_CHARS) }
                    .orEmpty()
                check(responseCode in 200..299) {
                    "Provider request failed with HTTP $responseCode. ${responseBody.take(300)}"
                }
                parseProposal(responseBody)
            } finally {
                connection.disconnect()
            }
        } finally {
            key.fill('\u0000')
        }
    }

    private fun parseProposal(raw: String): AiProposal {
        val outer = JSONObject(raw)
        val content = outer
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val proposal = JSONObject(content)
        val tags = proposal.optJSONArray("suggestedTags")
        return AiProposal(
            summary = proposal.optString("summary").take(MAX_FIELD_CHARS),
            suggestedFolder = proposal.optString("suggestedFolder")
                .takeIf(String::isNotBlank)
                ?.take(MAX_FIELD_CHARS),
            suggestedName = proposal.optString("suggestedName")
                .takeIf(String::isNotBlank)
                ?.take(MAX_FIELD_CHARS),
            suggestedTags = buildList {
                if (tags != null) {
                    for (index in 0 until minOf(tags.length(), MAX_TAGS)) {
                        tags.optString(index).takeIf(String::isNotBlank)?.let { add(it.take(80)) }
                    }
                }
            },
            rawResponse = raw,
        )
    }

    private fun validateEndpoint(baseUrl: String) {
        val uri = URI(baseUrl)
        val local = uri.host in setOf("localhost", "127.0.0.1", "::1")
        require(uri.scheme == "https" || local && uri.scheme == "http") {
            "Remote AI endpoints must use HTTPS. Plain HTTP is allowed only for localhost."
        }
        require(!uri.host.isNullOrBlank()) { "AI endpoint host is missing." }
        require(uri.userInfo == null) { "Credentials must not be embedded in the endpoint URL." }
    }

    private companion object {
        const val MAX_REMOTE_CHARS = 24_000
        const val MAX_RESPONSE_CHARS = 256_000
        const val MAX_FIELD_CHARS = 500
        const val MAX_TAGS = 20
    }
}
