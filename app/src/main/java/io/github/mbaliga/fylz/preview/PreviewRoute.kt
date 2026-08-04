package io.github.mbaliga.fylz.preview

enum class PreviewRoute {
    DIRECTORY,
    MARKDOWN,
    STRUCTURED_TEXT,
    PLAIN_TEXT,
    RASTER_IMAGE,
    ANIMATED_IMAGE,
    SVG,
    PDF,
    AUDIO,
    VIDEO,
    ARCHIVE,
    FONT,
    HEX_AND_METADATA,
}

data class PreviewDecision(
    val route: PreviewRoute,
    val trustedMimeType: String,
    val detectedExtension: String,
    val reason: String,
)

object PreviewRouter {
    private val markdown = setOf("md", "markdown", "mdown", "mkd", "mdx")
    private val structured = setOf(
        "json", "jsonl", "yaml", "yml", "toml", "xml", "csv", "tsv", "ini",
        "properties", "gradle", "kts", "kt", "java", "py", "js", "jsx", "ts",
        "tsx", "html", "css", "sql", "sh", "diff", "patch", "log", "mmd",
        "mermaid", "prompt", "instructions", "agent",
    )
    private val animated = setOf("gif", "webp")
    private val raster = setOf("png", "jpg", "jpeg", "bmp", "heif", "heic", "avif")
    private val archives = setOf("zip", "7z", "rar", "tar", "gz", "bz2", "xz", "tgz")
    private val fonts = setOf("ttf", "otf", "woff", "woff2")

    fun decide(name: String, mimeType: String?, signature: ByteArray = byteArrayOf()): PreviewDecision {
        val extension = name.substringAfterLast('.', "").lowercase()
        val mime = mimeType?.lowercase().orEmpty()
        val route = when {
            mime == "vnd.android.document/directory" -> PreviewRoute.DIRECTORY
            signature.isPdf() || mime == "application/pdf" || extension == "pdf" -> PreviewRoute.PDF
            signature.isGif() || extension == "gif" -> PreviewRoute.ANIMATED_IMAGE
            signature.isPng() || signature.isJpeg() || mime.startsWith("image/") && extension != "svg" && extension != "svgz" -> {
                if (extension in animated) PreviewRoute.ANIMATED_IMAGE else PreviewRoute.RASTER_IMAGE
            }
            extension == "svg" || extension == "svgz" || mime == "image/svg+xml" -> PreviewRoute.SVG
            extension in markdown || mime == "text/markdown" -> PreviewRoute.MARKDOWN
            extension in structured || mime in setOf("application/json", "application/xml", "application/yaml", "application/toml") -> PreviewRoute.STRUCTURED_TEXT
            mime.startsWith("text/") -> PreviewRoute.PLAIN_TEXT
            mime.startsWith("audio/") -> PreviewRoute.AUDIO
            mime.startsWith("video/") -> PreviewRoute.VIDEO
            extension in archives || mime.contains("zip") || mime.contains("archive") -> PreviewRoute.ARCHIVE
            extension in fonts || mime.startsWith("font/") -> PreviewRoute.FONT
            extension in raster -> PreviewRoute.RASTER_IMAGE
            else -> PreviewRoute.HEX_AND_METADATA
        }
        return PreviewDecision(route, mime.ifBlank { "application/octet-stream" }, extension, "MIME, extension, and bounded signature routing")
    }

    private fun ByteArray.startsWith(vararg expected: Int): Boolean =
        size >= expected.size && expected.indices.all { this[it].toInt() and 0xff == expected[it] }

    private fun ByteArray.isPdf(): Boolean = startsWith(0x25, 0x50, 0x44, 0x46)
    private fun ByteArray.isGif(): Boolean = startsWith(0x47, 0x49, 0x46, 0x38)
    private fun ByteArray.isPng(): Boolean = startsWith(0x89, 0x50, 0x4e, 0x47)
    private fun ByteArray.isJpeg(): Boolean = startsWith(0xff, 0xd8, 0xff)
}
