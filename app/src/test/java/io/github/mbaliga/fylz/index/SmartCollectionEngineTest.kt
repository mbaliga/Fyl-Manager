package io.github.mbaliga.fylz.index

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartCollectionEngineTest {
    private val file = IndexedFile(
        uri = "content://docs/report",
        rootUri = "content://docs/root",
        name = "Quarterly Report.pdf",
        mimeType = "application/pdf",
        extension = "pdf",
        sizeBytes = 12L * 1024L * 1024L,
        modifiedAtMillis = 1_800_000_000_000L,
        directory = false,
        tags = setOf("Finance", "Reviewed"),
    )

    @Test
    fun combinesNameSizeAndExtensionRules() {
        val collection = SmartCollection(
            name = "Large quarterly reports",
            rules = listOf(
                SmartRule(RuleField.NAME, RuleOperator.CONTAINS, "quarterly"),
                SmartRule(RuleField.SIZE, RuleOperator.GREATER_THAN, "10 MiB"),
                SmartRule(RuleField.EXTENSION, RuleOperator.EQUALS, "pdf"),
            ),
        )
        assertTrue(SmartCollectionEngine.matches(file, collection))
    }

    @Test
    fun supportsNegationAndAnyJoin() {
        val collection = SmartCollection(
            name = "Documents except drafts",
            join = RuleJoin.ANY,
            rules = listOf(
                SmartRule(RuleField.EXTENSION, RuleOperator.EQUALS, "docx"),
                SmartRule(RuleField.NAME, RuleOperator.CONTAINS, "draft", negate = true),
            ),
        )
        assertTrue(SmartCollectionEngine.matches(file, collection))
    }

    @Test
    fun textContentRulesNeverMatchBecauseNoContentIsSampled() {
        val collection = SmartCollection(
            name = "Mentions revenue",
            rules = listOf(SmartRule(RuleField.TEXT_CONTENT, RuleOperator.CONTAINS, "revenue")),
        )
        assertFalse(SmartCollectionEngine.matches(file, collection))
    }

    @Test
    fun directoryRuleDoesNotMatchFile() {
        val collection = SmartCollection(
            name = "Folders",
            rules = listOf(SmartRule(RuleField.DIRECTORY, RuleOperator.IS, "true")),
        )
        assertFalse(SmartCollectionEngine.matches(file, collection))
    }

    @Test
    fun parsesBinaryAndDecimalSizes() {
        assertTrue(SmartCollectionEngine.parseSize("1.5 MiB") == 1_572_864L)
        assertTrue(SmartCollectionEngine.parseSize("2 MB") == 2_000_000L)
        assertNull(SmartCollectionEngine.parseSize("many"))
    }
}
