package io.github.mbaliga.fylz.index

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartCollectionEngineTest {
    private val file = IndexedFile(
        uri = "content://file",
        rootUri = "content://root",
        name = "Architecture Model.obj",
        mimeType = "model/obj",
        extension = "obj",
        sizeBytes = 5L * 1024L * 1024L,
        modifiedAtMillis = 1_700_000_000_000L,
        directory = false,
        tags = setOf("3D", "Project Atlas"),
    )

    @Test
    fun combinesRulesWithAll() {
        val collection = SmartCollection(
            name = "Large models",
            rules = listOf(
                SmartRule(RuleField.EXTENSION, RuleOperator.EQUALS, "obj"),
                SmartRule(RuleField.SIZE, RuleOperator.GREATER_THAN, "4 MiB"),
                SmartRule(RuleField.TAG, RuleOperator.CONTAINS, "atlas"),
            ),
        )
        assertTrue(SmartCollectionEngine.matches(file, collection))
    }

    @Test
    fun supportsNegationAndAny() {
        val collection = SmartCollection(
            name = "Not drawings",
            join = RuleJoin.ANY,
            rules = listOf(
                SmartRule(RuleField.EXTENSION, RuleOperator.EQUALS, "dwg", negate = true),
                SmartRule(RuleField.DIRECTORY, RuleOperator.IS, "folder"),
            ),
        )
        assertTrue(SmartCollectionEngine.matches(file, collection))
    }

    @Test
    fun parsesBinaryAndDecimalSizes() {
        assertTrue(SmartCollectionEngine.parseSize("1 MiB") == 1_048_576L)
        assertTrue(SmartCollectionEngine.parseSize("1.5 GB") == 1_500_000_000L)
        assertFalse(SmartCollectionEngine.parseSize("many") != null)
    }
}
