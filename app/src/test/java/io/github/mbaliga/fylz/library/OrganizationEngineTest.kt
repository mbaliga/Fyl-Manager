package io.github.mbaliga.fylz.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizationEngineTest {
    private val photo = IndexedFileRecord(
        uri = "content://files/photo.jpg",
        displayName = "Holiday Photo.JPG",
        mimeType = "image/jpeg",
        sizeBytes = 4_000_000,
        modifiedAtMillis = 100,
        extension = "jpg",
        parentUri = "content://files/photos",
        tags = setOf("Travel", "Favourite"),
    )

    @Test
    fun allRulesMustMatch() {
        val collection = SmartCollection(
            id = "large-travel-images",
            name = "Large travel images",
            predicates = listOf(
                FileRulePredicate(RuleField.MIME, RuleOperator.STARTS_WITH, "image/"),
                FileRulePredicate(RuleField.SIZE, RuleOperator.GREATER_THAN, "1000000"),
                FileRulePredicate(RuleField.TAG, RuleOperator.EQUALS, "travel"),
            ),
        )
        assertTrue(OrganizationEngine.matches(photo, collection))
    }

    @Test
    fun anyRuleMatches() {
        val collection = SmartCollection(
            id = "attention",
            name = "Attention",
            combination = RuleCombination.ANY,
            predicates = listOf(
                FileRulePredicate(RuleField.EXTENSION, RuleOperator.EQUALS, "pdf"),
                FileRulePredicate(RuleField.TAG, RuleOperator.CONTAINS, "fav"),
            ),
        )
        assertTrue(OrganizationEngine.matches(photo, collection))
    }

    @Test
    fun regexAndNumericRulesAreBoundedAndDeterministic() {
        assertTrue(
            OrganizationEngine.matches(
                photo,
                FileRulePredicate(RuleField.NAME, RuleOperator.MATCHES_REGEX, "holiday.*\\.jpg"),
            ),
        )
        assertFalse(
            OrganizationEngine.matches(
                photo,
                FileRulePredicate(RuleField.SIZE, RuleOperator.LESS_THAN, "10"),
            ),
        )
    }

    @Test
    fun portableMetadataRoundTrips() {
        val source = PortableLibraryMetadata(
            tagsByUri = mapOf(photo.uri to setOf(" Travel ", "Favourite")),
            favourites = setOf(photo.uri),
            collections = listOf(
                SmartCollection(
                    id = "photos",
                    name = "Photos",
                    predicates = listOf(FileRulePredicate(RuleField.MIME, RuleOperator.STARTS_WITH, "image/")),
                ),
            ),
        )
        val decoded = OrganizationEngine.decodePortable(OrganizationEngine.encodePortable(source))
        assertEquals(setOf("Travel", "Favourite"), decoded.tagsByUri[photo.uri])
        assertEquals(source.favourites, decoded.favourites)
        assertEquals(source.collections, decoded.collections)
    }

    @Test
    fun importMergeUnionsTagsAndReplacesCollectionById() {
        val local = PortableLibraryMetadata(
            tagsByUri = mapOf(photo.uri to setOf("Travel")),
            favourites = emptySet(),
            collections = listOf(
                SmartCollection("same", "Old", listOf(FileRulePredicate(RuleField.EXTENSION, RuleOperator.EQUALS, "jpg"))),
            ),
        )
        val imported = PortableLibraryMetadata(
            tagsByUri = mapOf(photo.uri to setOf("Favourite")),
            favourites = setOf(photo.uri),
            collections = listOf(
                SmartCollection("same", "New", listOf(FileRulePredicate(RuleField.MIME, RuleOperator.EQUALS, "image/jpeg"))),
            ),
        )
        val merged = OrganizationEngine.mergePortable(local, imported)
        assertEquals(setOf("Travel", "Favourite"), merged.tagsByUri[photo.uri])
        assertEquals(setOf(photo.uri), merged.favourites)
        assertEquals("New", merged.collections.single().name)
    }
}
