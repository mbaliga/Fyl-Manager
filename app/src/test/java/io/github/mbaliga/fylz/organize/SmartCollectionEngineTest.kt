package io.github.mbaliga.fylz.organize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartCollectionEngineTest {
    private val record = IndexedFileRecord(
        uri = "content://files/design/model.obj",
        name = "Model.obj",
        mimeType = "model/obj",
        extension = "obj",
        sizeBytes = 2_000_000,
        modifiedAtMillis = 2_000,
        indexedAtMillis = 3_000,
        parentUri = "content://files/design",
        rootUri = "content://files",
        tags = setOf("3D", "Work"),
        contentTokens = setOf("mesh", "vehicle"),
    )

    @Test
    fun combinesTextSizeAndTags() {
        val rule = CollectionRule.All(
            listOf(
                CollectionRule.Text(RuleField.EXTENSION, TextOperator.EQUALS, "OBJ"),
                CollectionRule.Number(RuleField.SIZE_BYTES, NumberOperator.GREATER_THAN, 1_000_000),
                CollectionRule.SetMatch(RuleField.TAGS, SetOperator.CONTAINS_ALL, setOf("work", "3d")),
            ),
        )
        assertTrue(SmartCollectionEngine.matches(record, rule))
    }

    @Test
    fun invalidRegexFailsClosed() {
        assertFalse(SmartCollectionEngine.matches(record, CollectionRule.Text(RuleField.NAME, TextOperator.REGEX, "[")))
    }

    @Test
    fun anyAndNotAreComposable() {
        val rule = CollectionRule.Any(
            listOf(
                CollectionRule.Text(RuleField.EXTENSION, TextOperator.EQUALS, "pdf"),
                CollectionRule.Not(CollectionRule.Text(RuleField.NAME, TextOperator.CONTAINS, "draft")),
            ),
        )
        assertTrue(SmartCollectionEngine.matches(record, rule))
    }

    @Test
    fun sortsCollectionDeterministically() {
        val second = record.copy(uri = "2", name = "alpha.obj", modifiedAtMillis = 1_000)
        val collection = SmartCollection("models", "Models", CollectionRule.Text(RuleField.EXTENSION, TextOperator.EQUALS, "obj"), SmartSort.NAME_ASC)
        assertEquals(listOf("alpha.obj", "Model.obj"), SmartCollectionEngine.evaluate(listOf(record, second), collection).map { it.name })
    }
}
