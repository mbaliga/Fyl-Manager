package io.github.mbaliga.fylz.preview

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.coroutines.coroutineContext

data class ZipDocumentSection(
    val name: String,
    val text: String,
)

data class ZipDocumentInspection(
    val formatLabel: String,
    val sections: List<ZipDocumentSection>,
    val entryCount: Int,
    val totalReadBytes: Long,
    val truncated: Boolean,
)

/**
 * Read-only, bounded semantic extraction for ZIP-based office documents and EPUB.
 * It never expands embedded binaries and never writes archive paths to disk.
 */
class ZipDocumentInspector(private val context: Context) {
    suspend fun inspect(uri: Uri, extension: String): ZipDocumentInspection = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: error("Unable to read this document.")
        val sections = mutableListOf<ZipDocumentSection>()
        var entries = 0
        var bytes = 0L
        var truncated = false
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                coroutineContext.ensureActive()
                val entry = zip.nextEntry ?: break
                entries += 1
                require(entries <= MAX_ENTRIES) { "Document container has too many entries." }
                val normalized = entry.name.replace('\\', '/')
                if (!entry.isDirectory && shouldRead(extension, normalized)) {
                    val accepted = readEntry(zip, MAX_ENTRY_BYTES)
                    bytes += accepted.size
                    if (bytes > MAX_TOTAL_BYTES) {
                        truncated = true
                        break
                    }
                    val text = extractText(normalized, accepted)
                    if (text.isNotBlank()) {
                        sections += ZipDocumentSection(sectionTitle(extension, normalized), text.take(MAX_SECTION_CHARS))
                        if (text.length > MAX_SECTION_CHARS) truncated = true
                    }
                    if (sections.size >= MAX_SECTIONS) {
                        truncated = true
                        break
                    }
                }
                zip.closeEntry()
            }
        }
        ZipDocumentInspection(
            formatLabel = label(extension),
            sections = sections,
            entryCount = entries,
            totalReadBytes = bytes,
            truncated = truncated,
        )
    }

    private fun shouldRead(extension: String, name: String): Boolean {
        val lower = name.lowercase()
        return when (extension.lowercase()) {
            "docx", "docm", "dotx" -> lower == "word/document.xml" || lower.startsWith("word/header") ||
                lower.startsWith("word/footer") || lower == "docprops/core.xml"
            "pptx", "pptm", "ppsx" -> lower.startsWith("ppt/slides/slide") && lower.endsWith(".xml") ||
                lower.startsWith("ppt/notesSlides/notesSlide") && lower.endsWith(".xml") || lower == "docprops/core.xml"
            "xlsx", "xlsm" -> lower == "xl/sharedstrings.xml" || lower == "xl/workbook.xml" ||
                lower.startsWith("xl/worksheets/sheet") && lower.endsWith(".xml") || lower == "docprops/core.xml"
            "odt", "ods", "odp", "odg" -> lower == "content.xml" || lower == "styles.xml" || lower == "meta.xml"
            "epub" -> lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm") ||
                lower.endsWith(".ncx") || lower.endsWith(".opf")
            else -> false
        }
    }

    private fun readEntry(zip: ZipInputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 32 * 1024))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "Document XML entry exceeds the safety limit." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun extractText(name: String, bytes: ByteArray): String {
        if (name.endsWith(".html", true) || name.endsWith(".htm", true) || name.endsWith(".xhtml", true)) {
            return decodeXml(bytes).replace(Regex("<[^>]+>"), " ").decodeEntities().normalizeWhitespace()
        }
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            val document = factory.newDocumentBuilder().parse(bytes.inputStream())
            val output = StringBuilder()
            val nodes = document.getElementsByTagName("*")
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                val local = node.localName ?: node.nodeName.substringAfter(':')
                if (local in TEXT_ELEMENTS) {
                    val value = node.textContent?.trim().orEmpty()
                    if (value.isNotBlank()) output.append(value).append('\n')
                }
            }
            if (output.isBlank()) document.documentElement?.textContent.orEmpty() else output.toString()
        }.getOrElse { decodeXml(bytes).replace(Regex("<[^>]+>"), " ") }
            .decodeEntities().normalizeWhitespace()
    }

    private fun decodeXml(bytes: ByteArray): String = bytes.toString(Charsets.UTF_8).take(MAX_ENTRY_BYTES)

    private fun sectionTitle(extension: String, path: String): String = when {
        path.contains("slides/slide", true) -> "Slide ${path.substringAfter("slide").substringBefore('.')}"
        path.contains("worksheets/sheet", true) -> "Sheet ${path.substringAfter("sheet").substringBefore('.')}"
        path.contains("header", true) -> "Header"
        path.contains("footer", true) -> "Footer"
        path.endsWith("meta.xml", true) || path.endsWith("core.xml", true) -> "Metadata"
        extension.equals("epub", true) -> path.substringAfterLast('/')
        else -> "Document content"
    }

    private fun label(extension: String): String = when (extension.lowercase()) {
        "docx", "docm", "dotx" -> "Word document"
        "pptx", "pptm", "ppsx" -> "PowerPoint presentation"
        "xlsx", "xlsm" -> "Excel workbook"
        "odt" -> "OpenDocument text"
        "ods" -> "OpenDocument spreadsheet"
        "odp" -> "OpenDocument presentation"
        "epub" -> "EPUB book"
        else -> "ZIP-based document"
    }

    private fun String.decodeEntities(): String = replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")

    private fun String.normalizeWhitespace(): String = lineSequence()
        .map { it.trim().replace(Regex("\\s+"), " ") }
        .filter(String::isNotBlank)
        .joinToString("\n")

    private companion object {
        const val MAX_ENTRIES = 20_000
        const val MAX_ENTRY_BYTES = 2 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 16L * 1024L * 1024L
        const val MAX_SECTION_CHARS = 200_000
        const val MAX_SECTIONS = 300
        val TEXT_ELEMENTS = setOf("t", "p", "text", "title", "creator", "subject", "description")
    }
}
