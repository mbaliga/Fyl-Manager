package io.github.mbaliga.fylz.search

import io.github.mbaliga.fylz.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry

/** Where a hit came from, so the results list can say why a file matched. */
enum class SearchMatchSource {
    NAME,
    CONTENT,
}

data class SearchHit(
    val entry: FileEntry,
    val relativePath: String,
    val source: SearchMatchSource,
    /** One line of surrounding text for a content hit; null for name hits. */
    val snippet: String? = null,
)

/**
 * A parsed search query.
 *
 * The old behaviour was `entries.filter { it.name.contains(query, true) }` over the current
 * folder -- honestly labelled "Filter this folder" in the UI. This type is the query half of
 * replacing that with a real recursive search.
 *
 * Supported operators, all optional and combinable with free text:
 *
 * - `ext:pdf` / `ext:jpg,png` -- restrict to extensions
 * - `type:image` -- restrict to an [EntryKind]
 * - `size:>10mb` / `size:<500kb` -- size bound
 * - `content:` or a `"quoted phrase"` -- also search inside text-like files
 *
 * Parsing is pure and unit tested; nothing here touches Android.
 */
data class SearchQuery(
    val terms: List<String> = emptyList(),
    val extensions: Set<String> = emptySet(),
    val kinds: Set<EntryKind> = emptySet(),
    val minSizeBytes: Long? = null,
    val maxSizeBytes: Long? = null,
    val searchContent: Boolean = false,
) {
    val isEmpty: Boolean
        get() = terms.isEmpty() && extensions.isEmpty() && kinds.isEmpty() &&
            minSizeBytes == null && maxSizeBytes == null

    /** True when [entry] satisfies every non-text constraint. Cheap; no I/O. */
    fun matchesMetadata(entry: FileEntry): Boolean {
        if (extensions.isNotEmpty()) {
            val extension = entry.name.substringAfterLast('.', "").lowercase()
            if (extension !in extensions) return false
        }
        if (kinds.isNotEmpty() && entry.kind !in kinds) return false
        val size = entry.sizeBytes
        if (minSizeBytes != null && (size == null || size < minSizeBytes)) return false
        if (maxSizeBytes != null && (size == null || size > maxSizeBytes)) return false
        return true
    }

    /** True when every free-text term appears in the file name. */
    fun matchesName(name: String): Boolean =
        terms.isEmpty() || terms.all { name.contains(it, ignoreCase = true) }

    companion object {
        private val SIZE_PATTERN = Regex("^([<>])\\s*(\\d+(?:\\.\\d+)?)\\s*(b|kb|mb|gb)?$", RegexOption.IGNORE_CASE)

        private val UNIT_MULTIPLIERS = mapOf(
            "b" to 1L,
            "kb" to 1_024L,
            "mb" to 1_024L * 1_024,
            "gb" to 1_024L * 1_024 * 1_024,
        )

        /** Parses raw user input. Never throws: unrecognised tokens degrade to free text. */
        fun parse(raw: String): SearchQuery {
            val terms = mutableListOf<String>()
            val extensions = mutableSetOf<String>()
            val kinds = mutableSetOf<EntryKind>()
            var minSize: Long? = null
            var maxSize: Long? = null
            var searchContent = false

            tokenize(raw).forEach { token ->
                when {
                    token.equals("content:", ignoreCase = true) -> searchContent = true

                    token.startsWith("ext:", ignoreCase = true) ->
                        token.removePrefix("ext:").removePrefix("EXT:")
                            .split(',')
                            .map { it.trim().removePrefix(".").lowercase() }
                            .filter { it.isNotEmpty() }
                            .let(extensions::addAll)

                    token.startsWith("type:", ignoreCase = true) ->
                        parseKind(token.substring("type:".length))?.let(kinds::add)

                    token.startsWith("size:", ignoreCase = true) -> {
                        val match = SIZE_PATTERN.find(token.substring("size:".length).trim())
                        if (match != null) {
                            val (operator, number, unit) = match.destructured
                            val multiplier = UNIT_MULTIPLIERS[unit.lowercase().ifEmpty { "b" }] ?: 1L
                            val bytes = (number.toDouble() * multiplier).toLong()
                            if (operator == ">") minSize = bytes else maxSize = bytes
                        }
                    }

                    token.isNotBlank() -> {
                        terms += token
                        // A quoted phrase is a strong signal the user wants the text inside files,
                        // not just a file called that.
                        if (token.contains(' ')) searchContent = true
                    }
                }
            }

            return SearchQuery(
                terms = terms,
                extensions = extensions,
                kinds = kinds,
                minSizeBytes = minSize,
                maxSizeBytes = maxSize,
                searchContent = searchContent && terms.isNotEmpty(),
            )
        }

        /** Splits on whitespace while keeping `"quoted phrases"` intact. */
        internal fun tokenize(raw: String): List<String> {
            val tokens = mutableListOf<String>()
            val current = StringBuilder()
            var quoted = false
            raw.forEach { char ->
                when {
                    char == '"' -> {
                        quoted = !quoted
                        if (!quoted && current.isNotEmpty()) {
                            tokens += current.toString()
                            current.clear()
                        }
                    }
                    char.isWhitespace() && !quoted -> {
                        if (current.isNotEmpty()) {
                            tokens += current.toString()
                            current.clear()
                        }
                    }
                    else -> current.append(char)
                }
            }
            if (current.isNotEmpty()) tokens += current.toString()
            return tokens
        }

        private fun parseKind(value: String): EntryKind? = when (value.trim().lowercase()) {
            "folder", "dir", "directory" -> EntryKind.DIRECTORY
            "image", "photo", "picture" -> EntryKind.IMAGE
            "video", "movie" -> EntryKind.VIDEO
            "audio", "music", "sound" -> EntryKind.AUDIO
            "pdf" -> EntryKind.PDF
            "archive", "zip" -> EntryKind.ARCHIVE
            "text", "txt" -> EntryKind.TEXT
            "markdown", "md" -> EntryKind.MARKDOWN
            else -> null
        }
    }
}
