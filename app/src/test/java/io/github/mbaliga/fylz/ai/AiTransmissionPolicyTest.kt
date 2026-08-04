package io.github.mbaliga.fylz.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTransmissionPolicyTest {
    @Test
    fun localOnlyBlocksTransmission() {
        val preview = AiTransmissionPolicy.preview(request(localOnly = true))
        assertFalse(preview.allowed)
        assertNull(preview.transmittedContent)
        assertEquals(0, preview.estimatedInputTokens)
    }

    @Test
    fun redactsSensitiveContentAndShowsDestination() {
        val preview = AiTransmissionPolicy.preview(
            request(content = "Contact me@example.com from 192.168.1.9 with Bearer abcdefghijklmnop"),
        )
        assertTrue(preview.allowed)
        assertEquals("api.example.com", preview.destinationHost)
        assertTrue(preview.transmittedContent!!.contains("[REDACTED_EMAIL]"))
        assertTrue(preview.transmittedContent.contains("[REDACTED_IP]"))
        assertTrue(preview.transmittedContent.contains("[REDACTED_TOKEN]"))
        assertEquals(1, preview.redactionCounts["email"])
        assertEquals(1, preview.redactionCounts["ipv4"])
        assertNotNull(preview.estimatedCostUsd)
    }

    @Test
    fun truncatesBeforeTransmission() {
        val preview = AiTransmissionPolicy.preview(request(content = "x".repeat(100), maxCharacters = 12))
        assertTrue(preview.truncated)
        assertEquals(12, preview.transmittedCharacters)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInsecureRemoteEndpoint() {
        AiTransmissionPolicy.preview(request(endpoint = "http://example.com/v1"))
    }

    private fun request(
        content: String = "hello",
        endpoint: String = "https://api.example.com/v1",
        maxCharacters: Int = 24_000,
        localOnly: Boolean = false,
    ) = AiTransmissionRequest(
        providerId = "test",
        providerName = "Test",
        endpoint = endpoint,
        model = "model",
        fileName = "notes.txt",
        mimeType = "text/plain",
        content = content,
        maxCharacters = maxCharacters,
        localOnly = localOnly,
        estimatedInputUsdPerMillionTokens = 1.0,
        estimatedOutputUsdPerMillionTokens = 2.0,
    )
}
