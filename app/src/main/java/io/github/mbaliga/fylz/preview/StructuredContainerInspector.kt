package io.github.mbaliga.fylz.preview

import io.github.mbaliga.fylz.core.format.FileFormatRegistry

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

data class StructuredContainerEntry(
    val path: String,
    val uncompressedBytes: Long?,
    val directory: Boolean,
)

data class StructuredContainerInspection(
    val kindLabel: String,
    val title: String?,
    val extractedText: String,
    val entries: List<StructuredContainerEntry>,
    val totalEntries: Int,
    val truncated: Boolean,
    val warnings: List<String>,
)

class StructuredContainerInspector(private val context: Context) {
    suspend fun inspect(uri: Uri, fileName: String): StructuredContainerInspection = withContext(Dispatchers.IO) {
        val extension = FileFormatRegistry.compoundExtension(fileName)
        val entries = mutableListOf<StructuredContainerEntry>()
        val selected = linkedMapOf<String, ByteArray>()
        val warnings = mutableListOf<String>()
        var totalEntries = 0
        var totalExpanded = 0L
        var truncated = false

        val stream = context.contentResolver.openInputStream(uri) ?: error("Unable to read this container.")
        ZipInputStream(stream.buffered()).use { zip ->
            while (true) {
                coroutineContext.ensureActive()
                val entry = zip.nextEntry ?: break
                totalEntries += 1
                if (totalEntries > MAX_ENTRIES) {
                    truncated = true
                    break
                }
                validatePath(entry.name)
                if (entries.size < MAX_VISIBLE_ENTRIES) {
                    entries += StructuredContainerEntry(
                        path = entry.name,
                        uncompressedBytes = entry.size.takeIf { it >= 0 },
                        directory = entry.isDirectory,
                    )
                } else truncated = true

                if (!entry.isDirectory && shouldRead(extension, entry.name)) {
                    val data = readEntry(zip, entry)
                    totalExpanded += data.size
                    require(totalExpanded <= MAX_TOTAL_SELECTED_BYTES) {
                        "Selected document content exceeds the semantic preview limit."
                    }
                    selected[entry.name] = data
                } else {
                    drainEntry(zip, entry)
                }
                zip.closeEntry()
            }
        }

        val semantic = extract(extension, selected, entries)
        if (semantic.second.isNotEmpty()) warnings += semantic.second
        StructuredContainerInspection(
            kindLabel = kindLabel(extension),
            title = semantic.first.title,
            extractedText = semantic.first.text.take(MAX_TEXT_CHARS),
            entries = entries,
            totalEntries = totalEntries,
            truncated = truncated || semantic.first.text.length > MAX_TEXT_CHARS,
            warnings = warnings,
        )
    }

    private fun shouldRead(extension: String, path: String): Boolean {
        val normalized = path.lowercase(Locale.ROOT)
        return when (extension) {
            "docx", "docm", "dotx" -> normalized == "word/document.xml" ||
                normalized.startsWith("docprops/") || normalized == "word/comments.xml" ||
                normalized.startsWith("word/header") || normalized.startsWith("word/footer")
            "xlsx", "xlsm" -> normalized == "xl/sharedstrings.xml" ||
                normalized == "xl/workbook.xml" || normalized.startsWith("xl/worksheets/sheet") ||
                normalized.startsWith("docprops/")
            "pptx", "pptm", "ppsx" -> normalized.startsWith("ppt/slides/slide") ||
                normalized.startsWith("ppt/notesSlides/notesSlide".lowercase(Locale.ROOT)) ||
                normalized.startsWith("docprops/")
            "odt", "ods", "odp", "odg" -> normalized in setOf("content.xml", "meta.xml", "styles.xml")
            "epub" -> normalized == "meta-inf/container.xml" || normalized.endsWith(".opf") ||
                normalized.endsWith(".ncx") || normalized.endsWith(".xhtml") || normalized.endsWith(".html") ||
                normalized.endsWith(".htm")
            "apk", "aab", "jar", "war", "ear", "xapk", "apkm", "cbz" -> false
            else -> normalized.endsWith(".xml") || normalized.endsWith(".json") || normalized.endsWith(".txt")
        }
    }

    private suspend fun readEntry(zip: ZipInputStream, entry: ZipEntry): ByteArray {
        if (entry.size > MAX_ENTRY_BYTES) error("${entry.name} exceeds the semantic preview entry limit.")
        val output = ByteArrayOutputStream(minOf(MAX_ENTRY_BYTES, 32 * 1024))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            coroutineContext.ensureActive()
            val count = zip.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_ENTRY_BYTES) { "${entry.name} expands beyond the semantic preview limit." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private suspend fun drainEntry(zip: ZipInputStream, entry: ZipEntry) {
        if (entry.isDirectory) return
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            coroutineContext.ensureActive()
            val count = zip.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_DRAIN_BYTES) { "${entry.name} exceeds the safe container-scan limit." }
        }
    }

    private fun extract(
        extension: String,
        selected: Map<String, ByteArray>,
        entries: List<StructuredContainerEntry>,
    ): Pair<SemanticResult, List<String>> {
        val warnings = mutableListOf<String>()
        fun xml(path: String): String? = selected[path]?.toString(Charsets.UTF_8)
        val core = selected.entries.firstOrNull { it.key.equals("docProps/core.xml", ignoreCase = true) }
            ?.value?.toString(Charsets.UTF_8)
        val title = core?.let { extractTag(it, "dc:title") ?: extractTag(it, "title") }
        val text = when (extension) {
            "docx", "docm", "dotx" -> selected.filterKeys {
                it.equals("word/document.xml", true) || it.startsWith("word/header", true) ||
                    it.startsWith("word/footer", true) || it.equals("word/comments.xml", true)
            }.values.joinToString("\n\n") { officeXmlText(it.toString(Charsets.UTF_8)) }
            "xlsx", "xlsm" -> extractSpreadsheet(selected)
            "pptx", "pptm", "ppsx" -> selected.filterKeys {
                it.startsWith("ppt/slides/slide", true) || it.startsWith("ppt/notesSlides/notesSlide", true)
            }.toSortedMap(naturalNumericComparator()).values.joinToString("\n\n") {
                officeXmlText(it.toString(Charsets.UTF_8))
            }
            "odt", "ods", "odp", "odg" -> officeXmlText(xml("content.xml").orEmpty())
            "epub" -> selected.filterKeys {
                val lower = it.lowercase(Locale.ROOT)
                lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm")
            }.toSortedMap().entries.joinToString("\n\n") { (path, data) ->
                "# ${path.substringAfterLast('/')}\n${htmlText(data.toString(Charsets.UTF_8))}"
            }
            "apk", "aab" -> packageSummary(entries, "Android package")
            "jar", "war", "ear" -> packageSummary(entries, "Java archive")
            "cbz" -> packageSummary(entries, "Comic book archive")
            else -> selected.entries.joinToString("\n\n") { (path, data) ->
                "# $path\n${plainOrMarkupText(data.toString(Charsets.UTF_8))}"
            }
        }
        if (text.isBlank()) warnings += "No bounded human-readable semantic text was found; the container structure is still shown."
        return SemanticResult(title, text) to warnings
    }

    private fun extractSpreadsheet(selected: Map<String, ByteArray>): String {
        val shared = selected.entries.firstOrNull { it.key.equals("xl/sharedStrings.xml", true) }
            ?.value?.toString(Charsets.UTF_8)
            ?.let(::extractAllTextNodes)
            .orEmpty()
        val sheets = selected.filterKeys { it.startsWith("xl/worksheets/sheet", true) }
            .toSortedMap(naturalNumericComparator())
        return buildString {
            if (shared.isNotEmpty()) {
                append("Shared strings\n")
                shared.take(5_000).forEachIndexed { index, value -> append(index).append(": ").append(value).append('\n') }
                append('\n')
            }
            sheets.forEach { (path, bytes) ->
                append(path.substringAfterLast('/')).append('\n')
                val xml = bytes.toString(Charsets.UTF_8)
                CELL_REGEX.findAll(xml).take(10_000).forEach { match ->
                    val cell = match.groupValues[1]
                    val value = extractTag(match.value, "v")
                    if (value != null) append(cell).append(" = ").append(value).append('\n')
                }
                append('\n')
            }
        }
    }

    private fun packageSummary(entries: List<StructuredContainerEntry>, label: String): String = buildString {
        append(label).append('\n')
        val groups = entries.filterNot(StructuredContainerEntry::directory)
            .groupingBy { it.path.substringAfterLast('.', "(none)").lowercase(Locale.ROOT) }
            .eachCount().entries.sortedByDescending(Map.Entry<String, Int>::value)
        groups.take(30).forEach { append(it.key).append(": ").append(it.value).append('\n') }
    }

    private fun officeXmlText(xml: String): String = plainOrMarkupText(
        xml.replace("</w:p>", "\n", true)
            .replace("</a:p>", "\n", true)
            .replace("</text:p>", "\n", true)
            .replace("</text:h>", "\n", true),
    )

    private fun htmlText(value: String): String = plainOrMarkupText(
        value.replace(BLOCK_END_REGEX, "\n").replace(BREAK_REGEX, "\n"),
    )

    private fun plainOrMarkupText(value: String): String = decodeEntities(
        value.replace(TAG_REGEX, " ")
            .replace(WHITESPACE_REGEX, " ")
            .replace(Regex(" *\\n *"), "\n")
            .trim(),
    )

    private fun extractAllTextNodes(xml: String): List<String> = TEXT_NODE_REGEX.findAll(xml)
        .map { decodeEntities(it.groupValues[1]).trim() }.filter(String::isNotBlank).toList()

    private fun extractTag(xml: String, name: String): String? = Regex(
        "<${Regex.escape(name)}(?:\\s[^>]*)?>(.*?)</${Regex.escape(name)}>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    ).find(xml)?.groupValues?.get(1)?.let(::plainOrMarkupText)?.takeIf(String::isNotBlank)

    private fun decodeEntities(value: String): String = value
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&apos;", "'")
        .replace(NCR_HEX) { it.groupValues[1].toIntOrNull(16)?.let(Character::toChars)?.concatToString().orEmpty() }
        .replace(NCR_DEC) { it.groupValues[1].toIntOrNull()?.let(Character::toChars)?.concatToString().orEmpty() }

    private fun validatePath(path: String) {
        require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path)
        require(path.split('/').none { it == ".." || it.isBlank() })
        require(path.length <= 4_096)
    }

    private fun kindLabel(extension: String): String = when (extension) {
        "docx", "docm", "dotx" -> "Word document"
        "xlsx", "xlsm" -> "Excel workbook"
        "pptx", "pptm", "ppsx" -> "PowerPoint presentation"
        "odt", "ods", "odp", "odg" -> "OpenDocument file"
        "epub" -> "EPUB book"
        "apk", "aab" -> "Android package"
        "jar", "war", "ear" -> "Java package"
        "cbz" -> "Comic archive"
        else -> "Structured ZIP container"
    }

    private fun naturalNumericComparator() = Comparator<String> { first, second ->
        val a = NUMBER_REGEX.find(first)?.value?.toIntOrNull()
        val b = NUMBER_REGEX.find(second)?.value?.toIntOrNull()
        if (a != null && b != null && a != b) a.compareTo(b) else first.compareTo(second)
    }

    private data class SemanticResult(val title: String?, val text: String)

    private companion object {
        const val MAX_ENTRIES = 20_000
        const val MAX_VISIBLE_ENTRIES = 1_000
        const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
        const val MAX_DRAIN_BYTES = 512L * 1024L * 1024L
        const val MAX_TOTAL_SELECTED_BYTES = 32L * 1024L * 1024L
        const val MAX_TEXT_CHARS = 1_000_000
        val TAG_REGEX = Regex("<[^>]+>")
        val BLOCK_END_REGEX = Regex("</(?:p|div|h[1-6]|li|tr|section|article)>", RegexOption.IGNORE_CASE)
        val BREAK_REGEX = Regex("<(?:br|hr)\\s*/?>", RegexOption.IGNORE_CASE)
        val WHITESPACE_REGEX = Regex("[\\t\\x0B\\f\\r ]+")
        val TEXT_NODE_REGEX = Regex("<t(?:\\s[^>]*)?>(.*?)</t>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val CELL_REGEX = Regex("<c[^>]*\\br=\"([^\"]+)\"[^>]*>.*?</c>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val NUMBER_REGEX = Regex("\\d+")
        val NCR_HEX = Regex("&#x([0-9a-fA-F]+);")
        val NCR_DEC = Regex("&#([0-9]+);")
    }
}
