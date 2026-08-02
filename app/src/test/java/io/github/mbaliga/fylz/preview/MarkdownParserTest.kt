package io.github.mbaliga.fylz.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {
    @Test
    fun parsesAgentFriendlyMarkdownBlocks() {
        val source = """
            # Build report

            - [x] Compile
            - [ ] Device test

            ```kotlin
            val ready = true
            ```
        """.trimIndent()

        val blocks = MarkdownParser.parse(source)

        assertEquals(MarkdownBlock.Heading(1, "Build report"), blocks.first())
        assertTrue(blocks.contains(MarkdownBlock.Check(true, "Compile")))
        assertTrue(blocks.contains(MarkdownBlock.Check(false, "Device test")))
        assertTrue(blocks.any { it is MarkdownBlock.Code && it.language == "kotlin" })
    }
}
