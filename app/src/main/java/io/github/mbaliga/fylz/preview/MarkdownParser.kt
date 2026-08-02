package io.github.mbaliga.fylz.preview

sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Bullet(val depth: Int, val text: String) : MarkdownBlock
    data class Numbered(val number: String, val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Code(val language: String?, val text: String) : MarkdownBlock
    data class Check(val checked: Boolean, val text: String) : MarkdownBlock
    data object Rule : MarkdownBlock
}

object MarkdownParser {
    fun parse(source: String): List<MarkdownBlock> {
        val result = mutableListOf<MarkdownBlock>()
        val paragraph = mutableListOf<String>()
        val code = mutableListOf<String>()
        var codeLanguage: String? = null
        var inCode = false

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                result += MarkdownBlock.Paragraph(paragraph.joinToString(" ").trim())
                paragraph.clear()
            }
        }

        fun flushCode() {
            result += MarkdownBlock.Code(codeLanguage, code.joinToString("\n"))
            code.clear()
            codeLanguage = null
        }

        source.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            val trimmed = line.trimStart()

            if (trimmed.startsWith("```")) {
                if (inCode) {
                    flushCode()
                    inCode = false
                } else {
                    flushParagraph()
                    codeLanguage = trimmed.removePrefix("```").trim().ifEmpty { null }
                    inCode = true
                }
                return@forEach
            }

            if (inCode) {
                code += line
                return@forEach
            }

            when {
                line.isBlank() -> flushParagraph()
                trimmed.matches(Regex("^#{1,6}\\s+.+")) -> {
                    flushParagraph()
                    val level = trimmed.takeWhile { it == '#' }.length
                    result += MarkdownBlock.Heading(level, trimmed.drop(level).trim())
                }
                trimmed.matches(Regex("^[-*_]{3,}$")) -> {
                    flushParagraph()
                    result += MarkdownBlock.Rule
                }
                trimmed.matches(Regex("^[-*+]\\s+\\[[ xX]]\\s+.+")) -> {
                    flushParagraph()
                    val checked = trimmed.substringAfter('[').firstOrNull()?.lowercaseChar() == 'x'
                    val text = trimmed.substringAfter(']').trim()
                    result += MarkdownBlock.Check(checked, text)
                }
                trimmed.matches(Regex("^[-*+]\\s+.+")) -> {
                    flushParagraph()
                    val depth = (line.length - trimmed.length) / 2
                    result += MarkdownBlock.Bullet(depth, trimmed.drop(2).trim())
                }
                trimmed.matches(Regex("^\\d+[.)]\\s+.+")) -> {
                    flushParagraph()
                    val token = trimmed.substringBefore(' ').removeSuffix(".").removeSuffix(")")
                    result += MarkdownBlock.Numbered(token, trimmed.substringAfter(' ').trim())
                }
                trimmed.startsWith(">") -> {
                    flushParagraph()
                    result += MarkdownBlock.Quote(trimmed.removePrefix(">").trim())
                }
                else -> paragraph += trimmed
            }
        }

        if (inCode) flushCode() else flushParagraph()
        return result
    }
}
