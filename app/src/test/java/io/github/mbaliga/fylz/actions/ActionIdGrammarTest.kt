package io.github.mbaliga.fylz.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same grammar, same cases as `core/crates/fylz-actions`'s Rust test (design §2.2/§3). */
class ActionIdGrammarTest {

    @Test
    fun `valid ids parse`() {
        listOf(
            "fylz.copy",
            "fylz.copy-to",
            "fylz.select.clear",
            "fylz.customisation.problems",
            "user.resize-for-web",
            "acme.my-bundle.action",
            "fylz.sort.folders-first",
            "fylz.7zip",
        ).forEach { id ->
            assertTrue(id, ActionId.isValid(id))
            assertEquals(id, ActionId.parse(id).value)
        }
    }

    @Test
    fun `invalid ids are rejected`() {
        listOf(
            "",
            "fylz",
            "Fylz.Copy",
            "fylz.",
            ".copy",
            "fylz..copy",
            "fylz.-copy",
            "fylz.copy-",
            "fylz.co py",
            "fylz.co_py",
        ).forEach { id -> assertFalse(id, ActionId.isValid(id)) }
    }

    @Test
    fun `parse throws on an invalid id`() {
        assertTrue(
            runCatching { ActionId.parse("not valid") }.isFailure,
        )
    }

    @Test
    fun `every built-in id is valid and every id is unique`() {
        val ids = BuiltInActions.all().map { it.def.id }
        ids.forEach { assertTrue(it.value, ActionId.isValid(it.value)) }
        assertEquals(ids.size, ids.toSet().size)
    }
}
