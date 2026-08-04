package io.github.mbaliga.fylz.util

import android.provider.DocumentsContract
import io.github.mbaliga.fylz.model.EntryKind

object FileType {
    private val markdownExtensions = setOf("md", "markdown", "mdown", "mkd", "mdx")
    private val textExtensions = setOf(
        "txt", "text", "log", "csv", "tsv", "json", "jsonl", "xml", "yaml", "yml",
        "toml", "ini", "conf", "properties", "gradle", "kts", "kt", "java", "py",
        "js", "jsx", "ts", "tsx", "html", "htm", "css", "scss", "sql", "sh", "zsh",
        "fish", "bat", "ps1", "c", "h", "cpp", "hpp", "rs", "go", "rb", "php",
        "swift", "dart", "diff", "patch", "prompt", "instructions", "agent", "mmd",
        "mermaid", "env", "gitignore", "gitattributes", "dockerfile", "makefile",
    )
    private val archiveExtensions = setOf("zip", "7z", "rar", "tar", "gz", "bz2", "xz", "tgz")

    fun classify(name: String, mimeType: String): EntryKind {
        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) return EntryKind.DIRECTORY

        val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return when {
            extension in markdownExtensions -> EntryKind.MARKDOWN
            mimeType == "application/pdf" || extension == "pdf" -> EntryKind.PDF
            mimeType.startsWith("image/") -> EntryKind.IMAGE
            mimeType.startsWith("audio/") -> EntryKind.AUDIO
            mimeType.startsWith("video/") -> EntryKind.VIDEO
            extension in archiveExtensions || mimeType in setOf(
                "application/zip",
                "application/x-7z-compressed",
                "application/x-rar-compressed",
                "application/x-tar",
                "application/gzip",
            ) -> EntryKind.ARCHIVE
            mimeType.startsWith("text/") || extension in textExtensions || mimeType in setOf(
                "application/json",
                "application/ld+json",
                "application/xml",
                "application/yaml",
                "application/toml",
                "application/javascript",
            ) -> EntryKind.TEXT
            else -> EntryKind.OTHER
        }
    }

    fun isTextPreviewable(kind: EntryKind): Boolean =
        kind == EntryKind.MARKDOWN || kind == EntryKind.TEXT

    fun isEditable(kind: EntryKind): Boolean = isTextPreviewable(kind)
}
