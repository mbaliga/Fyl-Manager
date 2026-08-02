package io.github.mbaliga.fylz.preview

import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.PreviewKind

object PreviewClassifier {
    private val markdownNames = setOf(
        "readme",
        "agents",
        "claude",
        "gemini",
        "copilot-instructions",
        "contributing",
        "changelog",
        "roadmap",
        "security",
        "architecture",
    )

    private val textExtensions = setOf(
        "txt", "log", "json", "jsonl", "ndjson", "ipynb", "yaml", "yml", "toml",
        "xml", "html", "htm", "css", "scss", "sass", "less", "js", "jsx", "mjs",
        "cjs", "ts", "tsx", "kt", "kts", "java", "py", "rb", "rs", "go", "swift",
        "dart", "lua", "php", "r", "scala", "cs", "c", "h", "cpp", "hpp", "sh",
        "zsh", "fish", "bat", "ps1", "ini", "cfg", "conf", "properties", "gradle",
        "lock", "csv", "tsv", "sql", "diff", "patch", "graphql", "gql", "proto",
        "env", "rst", "adoc", "tex", "mmd", "puml", "mermaid", "vue", "svelte",
        "svg", "drawio", "excalidraw", "editorconfig", "gitignore", "gitattributes",
        "gitmodules", "dockerignore", "npmrc",
    )

    private val textNames = setOf(
        "dockerfile",
        "makefile",
        "gemfile",
        "rakefile",
        "procfile",
        "justfile",
        "license",
        "notice",
    )

    private val imageExtensions = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif",
    )

    private val structuredTextMimeTypes = setOf(
        "application/json",
        "application/ld+json",
        "application/x-ndjson",
        "application/xml",
        "application/yaml",
        "application/toml",
        "application/javascript",
        "image/svg+xml",
    )

    private val archiveExtensions = setOf(
        "zip", "7z", "rar", "tar", "gz", "bz2", "xz", "tgz", "tbz2", "txz", "jar", "apk",
    )

    fun classify(entry: FileEntry): PreviewKind {
        if (entry.isDirectory) return PreviewKind.DIRECTORY
        return classify(entry.name, entry.mimeType)
    }

    fun classify(name: String, mimeType: String): PreviewKind {
        val normalizedName = name.lowercase()
        val baseName = normalizedName.substringBeforeLast('.', normalizedName)
        val extension = normalizedName.substringAfterLast('.', "")

        return when {
            extension in setOf("md", "markdown", "mdown", "mkd", "mdx") -> PreviewKind.MARKDOWN
            baseName in markdownNames && extension.isEmpty() -> PreviewKind.MARKDOWN
            mimeType == "text/markdown" -> PreviewKind.MARKDOWN
            extension in archiveExtensions || mimeType in setOf(
                "application/zip",
                "application/x-7z-compressed",
                "application/vnd.rar",
                "application/x-tar",
                "application/gzip",
            ) -> PreviewKind.ARCHIVE
            mimeType == "application/pdf" || extension == "pdf" -> PreviewKind.PDF
            mimeType in structuredTextMimeTypes || extension in textExtensions -> PreviewKind.TEXT
            (mimeType.startsWith("image/") && mimeType != "image/svg+xml") ||
                extension in imageExtensions -> PreviewKind.IMAGE
            mimeType.startsWith("audio/") -> PreviewKind.AUDIO
            mimeType.startsWith("video/") -> PreviewKind.VIDEO
            mimeType.startsWith("text/") ||
                normalizedName in textNames ||
                normalizedName == ".env" ||
                normalizedName.startsWith(".env.") -> PreviewKind.TEXT
            else -> PreviewKind.UNSUPPORTED
        }
    }
}
