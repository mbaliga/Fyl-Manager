package io.github.mbaliga.fylz.ai

import java.net.URI
import java.security.MessageDigest
import kotlin.math.ceil

data class RedactionRule(val id: String, val pattern: Regex, val replacement: String, val enabled: Boolean = true)

data class AiTransmissionRequest(
    val providerId: String,
    val providerName: String,
    val endpoint: String,
    val model: String,
    val fileName: String,
    val mimeType: String,
    val content: String?,
    val maxCharacters: Int = 24_000,
    val localOnly: Boolean = false,
    val includeFileName: Boolean = true,
    val includeMimeType: Boolean = true,
    val redactionRules: List<RedactionRule> = defaultRedactionRules(),
    val estimatedInputUsdPerMillionTokens: Double? = null,
    val estimatedOutputUsdPerMillionTokens: Double? = null,
    val expectedOutputTokens: Int = 500,
)

data class AiTransmissionPreview(
    val allowed: Boolean,
    val blockReason: String? = null,
    val destinationHost: String? = null,
    val transmittedFileName: String?,
    val transmittedMimeType: String?,
    val transmittedContent: String?,
    val originalCharacters: Int,
    val transmittedCharacters: Int,
    val truncated: Boolean,
    val redactionCounts: Map<String, Int>,
    val estimatedInputTokens: Int,
    val estimatedOutputTokens: Int,
    val estimatedCostUsd: Double?,
    val payloadSha256: String,
)

object AiTransmissionPolicy {
    const val HARD_MAX_CHARACTERS = 200_000
    const val HARD_MAX_OUTPUT_TOKENS = 16_384

    fun preview(request: AiTransmissionRequest): AiTransmissionPreview {
        val endpoint = validateEndpoint(request.endpoint)
        if (request.localOnly) return blocked(request, "Local-only mode is enabled.")
        require(request.maxCharacters in 0..HARD_MAX_CHARACTERS)
        require(request.expectedOutputTokens in 0..HARD_MAX_OUTPUT_TOKENS)
        var transformed = request.content.orEmpty().take(request.maxCharacters)
        val counts = linkedMapOf<String, Int>()
        request.redactionRules.filter(RedactionRule::enabled).forEach { rule ->
            require(rule.id.matches(Regex("[a-zA-Z0-9._-]{1,80}")))
            var matches = 0
            transformed = rule.pattern.replace(transformed) { matches += 1; rule.replacement }
            if (matches > 0) counts[rule.id] = matches
        }
        val fileName = request.fileName.takeIf { request.includeFileName }
        val mimeType = request.mimeType.takeIf { request.includeMimeType }
        val payload = buildString {
            fileName?.let { append("File name: ").append(it).append('\n') }
            mimeType?.let { append("MIME type: ").append(it).append('\n') }
            if (transformed.isNotEmpty()) append("Content:\n").append(transformed)
        }
        val inputTokens = if (payload.isEmpty()) 0 else ceil(payload.length / 4.0).toInt()
        val cost = if (request.estimatedInputUsdPerMillionTokens == null && request.estimatedOutputUsdPerMillionTokens == null) null else
            inputTokens / 1_000_000.0 * (request.estimatedInputUsdPerMillionTokens ?: 0.0) +
                request.expectedOutputTokens / 1_000_000.0 * (request.estimatedOutputUsdPerMillionTokens ?: 0.0)
        return AiTransmissionPreview(
            true, destinationHost = endpoint.host, transmittedFileName = fileName, transmittedMimeType = mimeType,
            transmittedContent = transformed.takeIf(String::isNotEmpty), originalCharacters = request.content?.length ?: 0,
            transmittedCharacters = transformed.length, truncated = (request.content?.length ?: 0) > transformed.length,
            redactionCounts = counts, estimatedInputTokens = inputTokens, estimatedOutputTokens = request.expectedOutputTokens,
            estimatedCostUsd = cost, payloadSha256 = sha256(payload),
        )
    }

    private fun blocked(request: AiTransmissionRequest, reason: String) = AiTransmissionPreview(
        false, reason, transmittedFileName = null, transmittedMimeType = null, transmittedContent = null,
        originalCharacters = request.content?.length ?: 0, transmittedCharacters = 0, truncated = false,
        redactionCounts = emptyMap(), estimatedInputTokens = 0, estimatedOutputTokens = 0, estimatedCostUsd = 0.0,
        payloadSha256 = sha256(""),
    )

    private fun validateEndpoint(value: String): URI {
        val uri = URI(value)
        require(!uri.host.isNullOrBlank() && uri.userInfo == null)
        val loopback = uri.host.lowercase() in setOf("localhost", "127.0.0.1", "::1")
        require(uri.scheme == "https" || loopback && uri.scheme == "http")
        return uri
    }

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    fun defaultRedactionRules() = listOf(
        RedactionRule("email", Regex("(?i)[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}"), "[REDACTED_EMAIL]"),
        RedactionRule("ipv4", Regex("(?<!\\d)(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}(?!\\d)"), "[REDACTED_IP]"),
        RedactionRule("bearer-token", Regex("(?i)bearer\\s+[a-z0-9._~+/-]{12,}={0,2}"), "Bearer [REDACTED_TOKEN]"),
        RedactionRule("private-key", Regex("(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----"), "[REDACTED_PRIVATE_KEY]"),
    )
}
