package io.github.mbaliga.fylz.util

import android.provider.DocumentsContract
import io.github.mbaliga.fylz.model.EntryKind
import io.github.mbaliga.fylz.preview.FileFormatRegistry

object FileType {
    private val archiveExtensions = setOf("zip", "7z", "rar", "tar", "gz", "bz2", "xz", "tgz")

    fun classify(name: String, mimeType: String): EntryKind {
        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) return EntryKind.DIRECTORY

        val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return when {
            // One source of truth (P0.9, defect 10): FileFormatRegistry's own, larger sets --
            // this used to keep a smaller, independently-drifted copy, so e.g. `lua`/`cfg`/`srt`
            // classified as OTHER here even though the registry already treated them as text.
            extension in FileFormatRegistry.markdownExtensions -> EntryKind.MARKDOWN
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
            mimeType.startsWith("text/") || extension in FileFormatRegistry.textExtensions || mimeType in setOf(
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
