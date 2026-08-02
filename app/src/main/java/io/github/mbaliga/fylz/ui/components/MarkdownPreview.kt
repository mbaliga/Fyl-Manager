package io.github.mbaliga.fylz.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private sealed interface MarkdownBlock {
    data class Heading(val level: Int, val value: String) : MarkdownBlock
    data class Paragraph(val value: String) : MarkdownBlock
    data class Bullet(val value: String, val ordered: Boolean) : MarkdownBlock
    data class Task(val value: String, val checked: Boolean) : MarkdownBlock
    data class Quote(val value: String) : MarkdownBlock
    data class Code(val language: String?, val value: String) : MarkdownBlock
    data object Rule : MarkdownBlock
}

@Composable
fun MarkdownPreview(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(markdown) { parseMarkdown(markdown) }
    SelectionContainer(modifier) {
        LazyColumn(
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(blocks) { block ->
                when (block) {
                    is MarkdownBlock.Heading -> Text(
                        text = block.value,
                        style = when (block.level) {
                            1 -> MaterialTheme.typography.headlineSmall
                            2 -> MaterialTheme.typography.titleLarge
                            3 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleSmall
                        },
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = if (block.level <= 2) 8.dp else 2.dp),
                    )
                    is MarkdownBlock.Paragraph -> Text(
                        text = block.value,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    is MarkdownBlock.Bullet -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (block.ordered) "1." else "•")
                        Text(block.value, style = MaterialTheme.typography.bodyMedium)
                    }
                    is MarkdownBlock.Task -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Checkbox(checked = block.checked, onCheckedChange = null)
                        Text(
                            text = block.value,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    is MarkdownBlock.Quote -> Row {
                        Box(
                            Modifier
                                .padding(end = 12.dp)
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(horizontal = 2.dp, vertical = 18.dp),
                        )
                        Text(
                            text = block.value,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is MarkdownBlock.Code -> Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                                MaterialTheme.shapes.small,
                            )
                            .padding(14.dp),
                    ) {
                        block.language?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        Text(
                            text = block.value,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                    }
                    MarkdownBlock.Rule -> HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun MonospaceTextPreview(
    text: String,
    modifier: Modifier = Modifier,
) {
    SelectionContainer(modifier) {
        LazyColumn(contentPadding = PaddingValues(18.dp)) {
            item {
                Text(
                    text = text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}

private fun parseMarkdown(markdown: String): List<MarkdownBlock> {
    val output = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    val code = mutableListOf<String>()
    var codeLanguage: String? = null
    var inCode = false

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            output += MarkdownBlock.Paragraph(paragraph.joinToString(" ").trim())
            paragraph.clear()
        }
    }

    fun flushCode() {
        output += MarkdownBlock.Code(codeLanguage, code.joinToString("\n"))
        code.clear()
        codeLanguage = null
    }

    markdown.lineSequence().forEach { rawLine ->
        val line = rawLine.trimEnd()
        if (line.trimStart().startsWith("```")) {
            if (inCode) flushCode() else {
                flushParagraph()
                codeLanguage = line.trim().removePrefix("```").trim().ifBlank { null }
            }
            inCode = !inCode
            return@forEach
        }
        if (inCode) {
            code += rawLine
            return@forEach
        }

        val trimmed = line.trim()
        when {
            trimmed.isBlank() -> flushParagraph()
            trimmed.matches(Regex("^(-{3,}|_{3,}|\\*{3,})$")) -> {
                flushParagraph()
                output += MarkdownBlock.Rule
            }
            trimmed.startsWith("#") && trimmed.contains(' ') -> {
                val marks = trimmed.takeWhile { it == '#' }
                if (marks.length <= 6) {
                    flushParagraph()
                    output += MarkdownBlock.Heading(marks.length, trimmed.drop(marks.length).trim())
                } else paragraph += trimmed
            }
            trimmed.matches(Regex("^[-*+] \\[[ xX]] .+")) -> {
                flushParagraph()
                val checked = trimmed.substringAfter('[').firstOrNull()?.lowercaseChar() == 'x'
                output += MarkdownBlock.Task(trimmed.substringAfter("] "), checked)
            }
            trimmed.matches(Regex("^[-*+] .+")) -> {
                flushParagraph()
                output += MarkdownBlock.Bullet(trimmed.drop(2), ordered = false)
            }
            trimmed.matches(Regex("^\\d+[.)] .+")) -> {
                flushParagraph()
                output += MarkdownBlock.Bullet(trimmed.substringAfter(' '), ordered = true)
            }
            trimmed.startsWith(">") -> {
                flushParagraph()
                output += MarkdownBlock.Quote(trimmed.removePrefix(">").trim())
            }
            else -> paragraph += trimmed
        }
    }
    flushParagraph()
    if (inCode || code.isNotEmpty()) flushCode()
    return output
}
