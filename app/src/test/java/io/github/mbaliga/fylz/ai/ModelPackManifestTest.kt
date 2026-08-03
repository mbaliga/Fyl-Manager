package io.github.mbaliga.fylz.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPackManifestTest {
    private fun file(path: String) = ModelPackFile(
        path = path,
        url = "https://models.example/$path",
        sha256 = "a".repeat(64),
        bytes = 1024,
    )

    @Test
    fun canonicalJsonSortsFiles() {
        val manifest = ModelPackManifest(
            id = "embeddings-small",
            version = "1.0.0",
            displayName = "Small embeddings",
            description = "Local semantic search",
            licenseName = "Apache-2.0",
            licenseUrl = "https://example.com/license",
            runtime = "onnx",
            minimumRamBytes = 512L * 1024L * 1024L,
            files = listOf(file("z.onnx"), file("a.json")),
        )
        val json = ModelPackManifestCodec.canonicalJson(manifest)
        assertTrue(json.indexOf("a.json") < json.indexOf("z.onnx"))
        val parsed = ModelPackManifestCodec.parseSigned(
            """{"manifest":$json,"keyId":"release-1","signature":"AA=="}""",
        )
        assertEquals(manifest.id, parsed.manifest.id)
        assertEquals(2, parsed.manifest.files.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTraversalPath() {
        file("../model.onnx")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsecuredDownload() {
        ModelPackFile("model.gguf", "http://example.com/model", "b".repeat(64), 10)
    }
}
