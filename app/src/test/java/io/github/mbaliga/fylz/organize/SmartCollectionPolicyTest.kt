package io.github.mbaliga.fylz.organize

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartCollectionPolicyTest {
    private val file = IndexedFileFacts(
        uri = "content://example/report.pdf",
        name = "Quarterly Report.pdf",
        mimeType = "application/pdf",
        sizeBytes = 2_000_000,
        modifiedAtMillis = 1000,
        tags = setOf("finance", "review"),
        sha256 = "a".repeat(64),
        duplicateGroupId = "group-1",
    )

    @Test fun allPredicatesMustMatch() {
        val rule = SmartCollectionRule(
            name = "Finance PDFs",
            predicates = listOf(
                CollectionPredicate.Text(TextField.EXTENSION, PolicyTextOperator.EQUALS, "pdf"),
                CollectionPredicate.HasTag("Finance"),
                CollectionPredicate.Number(NumberField.SIZE_BYTES, PolicyNumberOperator.GREATER_THAN, 1_000_000),
            ),
        )
        assertTrue(rule.matches(file))
    }

    @Test fun anyModeAcceptsOneMatch() {
        val rule = SmartCollectionRule(
            name = "Interesting",
            mode = MatchMode.ANY,
            predicates = listOf(
                CollectionPredicate.Text(TextField.NAME, PolicyTextOperator.CONTAINS, "missing"),
                CollectionPredicate.IsDuplicate,
            ),
        )
        assertTrue(rule.matches(file))
    }

    @Test fun malformedRegexFailsClosed() {
        val rule = SmartCollectionRule(
            name = "Bad regex",
            predicates = listOf(CollectionPredicate.Text(TextField.NAME, PolicyTextOperator.REGEX, "[")),
        )
        assertFalse(rule.matches(file))
    }

    @Test fun renamePlannerDetectsCaseInsensitiveCollisions() {
        val files = listOf(
            IndexedFileFacts("1", "A.txt", "text/plain", 1, 1),
            IndexedFileFacts("2", "a.txt", "text/plain", 1, 1),
        )
        val plan = BatchRenamePlanner.plan(files, RenameTemplate())
        assertTrue(plan.all { !it.valid })
    }

    @Test fun counterRenamePreservesExtension() {
        val files = listOf(IndexedFileFacts("1", "photo.jpg", "image/jpeg", 1, 1))
        val item = BatchRenamePlanner.plan(
            files,
            RenameTemplate(prefix = "IMG_", includeCounter = true, counterStart = 7, counterPadding = 4),
        ).single()
        assertTrue(item.valid)
        assertTrue(item.after == "IMG_photo0007.jpg")
    }
}
