package io.github.mbaliga.fylz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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
import io.github.mbaliga.fylz.preview.MarkdownBlock
import io.github.mbaliga.fylz.preview.MarkdownParser

@Composable
fun MarkdownPreview(
    source: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(source) { MarkdownParser.parse(source) }
    SelectionContainer(modifier = modifier) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            blocks.forEach { block ->
                when (block) {
                    is MarkdownBlock.Heading -> HeadingBlock(block)
                    is MarkdownBlock.Paragraph -> Text(
                        text = block.text,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 21.sp,
                    )
                    is MarkdownBlock.Bullet -> Row(
                        modifier = Modifier.padding(start = (block.depth * 12).dp),
                    ) {
                        Text("-", color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(block.text, style = MaterialTheme.typography.bodyMedium)
                    }
                    is MarkdownBlock.Numbered -> Row {
                        Text(
                            text = "${block.number}.",
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(block.text, style = MaterialTheme.typography.bodyMedium)
                    }
                    is MarkdownBlock.Check -> Row {
                        Text(
                            text = if (block.checked) "[x]" else "[ ]",
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(block.text, style = MaterialTheme.typography.bodyMedium)
                    }
                    is MarkdownBlock.Quote -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = block.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is MarkdownBlock.Code -> Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(12.dp),
                    ) {
                        block.language?.let {
                            Text(
                                text = it.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(
                            text = block.text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        )
                    }
                    MarkdownBlock.Rule -> HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun HeadingBlock(block: MarkdownBlock.Heading) {
    val style = when (block.level) {
        1 -> MaterialTheme.typography.headlineMedium
        2 -> MaterialTheme.typography.headlineSmall
        3 -> MaterialTheme.typography.titleLarge
        4 -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.titleSmall
    }
    Text(
        text = block.text,
        style = style,
        fontWeight = FontWeight.SemiBold,
    )
}
