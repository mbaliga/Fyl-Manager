package io.github.mbaliga.fylz.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartRuleEvaluatorTest {
    private val file = IndexedFileMetadata(
        uri = "content://docs/report.pdf",
        name = "Quarterly Report.PDF",
        mimeType = "application/pdf",
        sizeBytes = 2_000_000,
        modifiedMillis = 1_725_000_000_000,
        tags = setOf("Finance", "Reviewed"),
    )

    @Test fun textRulesAreCaseInsensitive() {
        assertTrue(matches(SmartRuleField.NAME, SmartRuleOperator.CONTAINS, "quarterly"))
        assertTrue(matches(SmartRuleField.EXTENSION, SmartRuleOperator.EQUALS, ".pdf"))
        assertTrue(matches(SmartRuleField.MIME_TYPE, SmartRuleOperator.STARTS_WITH, "APPLICATION/"))
        assertTrue(matches(SmartRuleField.TAG, SmartRuleOperator.EQUALS, "finance"))
    }

    @Test fun numericRulesDoNotCoerceInvalidValues() {
        assertTrue(matches(SmartRuleField.SIZE_BYTES, SmartRuleOperator.GREATER_THAN, "1000000"))
        assertFalse(matches(SmartRuleField.SIZE_BYTES, SmartRuleOperator.GREATER_THAN, "large"))
        assertFalse(matches(SmartRuleField.SIZE_BYTES, SmartRuleOperator.CONTAINS, "200"))
    }

    @Test fun allAndAnyCombinationsAreDeterministic() {
        val name = rule(SmartRuleField.NAME, SmartRuleOperator.CONTAINS, "report")
        val large = rule(SmartRuleField.SIZE_BYTES, SmartRuleOperator.GREATER_THAN, "1000000")
        val wrong = rule(SmartRuleField.EXTENSION, SmartRuleOperator.EQUALS, "docx")
        assertEquals(listOf(file), SmartRuleEvaluator.filter(listOf(file), listOf(name, large), requireAll = true))
        assertTrue(SmartRuleEvaluator.filter(listOf(file), listOf(name, wrong), requireAll = true).isEmpty())
        assertEquals(listOf(file), SmartRuleEvaluator.filter(listOf(file), listOf(name, wrong), requireAll = false))
    }

    @Test fun disabledRulesNeverMatch() {
        val disabled = rule(SmartRuleField.NAME, SmartRuleOperator.CONTAINS, "report").copy(enabled = false)
        assertFalse(SmartRuleEvaluator.matches(disabled, file))
    }

    private fun matches(field: SmartRuleField, operator: SmartRuleOperator, value: String) =
        SmartRuleEvaluator.matches(rule(field, operator, value), file)

    private fun rule(field: SmartRuleField, operator: SmartRuleOperator, value: String) =
        SmartRule(name = "test", field = field, operator = operator, value = value)
}
