package io.github.mbaliga.fylz.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class SignedModelCatalogTest {
    @Test fun verifiesValidEd25519Catalog() {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val now = 1_800_000_000_000L
        val json = catalog(now).encodeToByteArray()
        val signature = Signature.getInstance("Ed25519").run {
            initSign(pair.private)
            update(json)
            Base64.getEncoder().encodeToString(sign())
        }
        val parsed = SignedModelCatalogVerifier.verifyAndParse(
            json,
            signature,
            Base64.getEncoder().encodeToString(pair.public.encoded),
            now,
        )
        assertEquals("official", parsed.catalogId)
        assertEquals("small-model", parsed.models.single().id)
    }

    @Test fun rejectsTamperedCatalog() {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val now = 1_800_000_000_000L
        val original = catalog(now).encodeToByteArray()
        val signature = Signature.getInstance("Ed25519").run {
            initSign(pair.private)
            update(original)
            Base64.getEncoder().encodeToString(sign())
        }
        val tampered = original.toString(Charsets.UTF_8).replace("small-model", "large-model").encodeToByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            SignedModelCatalogVerifier.verifyAndParse(
                tampered,
                signature,
                Base64.getEncoder().encodeToString(pair.public.encoded),
                now,
            )
        }
    }

    private fun catalog(now: Long) = """
        {
          "schemaVersion": 1,
          "catalogId": "official",
          "issuedAtMillis": $now,
          "expiresAtMillis": ${now + 60_000},
          "models": [{
            "id": "small-model",
            "displayName": "Small model",
            "downloadUrl": "https://example.com/model.bin",
            "sha256": "${"ab".repeat(32)}",
            "expectedBytes": 1024,
            "licenseName": "Apache-2.0",
            "licenseUrl": "https://example.com/license"
          }]
        }
    """.trimIndent()
}
