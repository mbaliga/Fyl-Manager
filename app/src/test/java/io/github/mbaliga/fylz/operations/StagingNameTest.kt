package io.github.mbaliga.fylz.operations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P0.6: the pure staging-name helpers `FileOperationService`'s copy uses to hide a write in
 * progress from the user until it's verified. */
class StagingNameTest {

    @Test
    fun `carries the operation id, item index and requested name`() {
        val name = stagingName("op-42", 3, "vacation.jpg")
        assertTrue(name.startsWith(STAGING_NAME_PREFIX))
        assertTrue(name.contains("op-42"))
        assertTrue(name.contains("-3-"))
        assertTrue(name.endsWith("vacation.jpg"))
    }

    @Test
    fun `a slash in the requested name is neutralized`() {
        val name = stagingName("op-1", 0, "a/b.txt")
        assertFalse(name.contains('/'))
    }

    @Test
    fun `stays within 255 UTF-8 bytes and is recognized as a staging name`() {
        val longName = "a".repeat(500) + ".txt"
        val name = stagingName("op-1", 0, longName)
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 255)
        assertTrue(isStagingName(name))
    }

    @Test
    fun `truncation to 255 UTF-8 bytes never splits a multi-byte character`() {
        // Each euro sign is 3 UTF-8 bytes; a naive char-count truncation could easily land
        // mid-character depending on the fixed prefix's own byte length.
        val longName = "€".repeat(200) + ".txt"
        val name = stagingName("op-1", 0, longName)
        val bytes = name.toByteArray(Charsets.UTF_8)
        assertTrue(bytes.size <= 255)
        // Round-tripping through UTF-8 decode must reproduce the exact same bytes: a
        // mid-character cut would decode to a replacement character and no longer match.
        assertEquals(name, String(bytes, Charsets.UTF_8))
    }

    @Test
    fun `names outside the staging shape are not recognized`() {
        assertFalse(isStagingName("vacation.jpg"))
        assertFalse(isStagingName(".fylz-trash"))
        assertFalse(isStagingName(".fylz-replaced-abc-name"))
    }
}
