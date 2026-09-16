package io.github.mbaliga.fylz.appearance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FolderFinish]'s slugs are written into user records and read back by later builds, so they are
 * a persistence format, not an implementation detail. What this pins is what a rename would break.
 */
class FolderFinishTest {

    @Test
    fun `every finish round-trips through its slug`() {
        FolderFinish.entries.forEach { finish ->
            assertSame(finish, FolderFinish.fromSlug(finish.slug))
        }
    }

    @Test
    fun `slugs are unique`() {
        val slugs = FolderFinish.entries.map { it.slug }
        assertEquals("two finishes share a slug, so one would decode as the other", slugs.size, slugs.toSet().size)
    }

    /**
     * A record written by a newer build names a finish this one has never heard of. That has to
     * read as "no finish", which draws the theme's own material -- not as a crash, and not as an
     * arbitrary substitute material the user never chose.
     */
    @Test
    fun `an unknown or absent slug resolves to no finish`() {
        assertNull(FolderFinish.fromSlug(null))
        assertNull(FolderFinish.fromSlug("holographic-titanium"))
        assertNull(FolderFinish.fromSlug(""))
    }

    /**
     * DEFAULT's own slug still has to resolve: a folder explicitly set back to Matte is a
     * different state from a folder that never had a finish, and only the former survives a
     * theme change to one whose own material is glass.
     */
    @Test
    fun `matte is a choosable finish, not just the absence of one`() {
        assertSame(FolderFinish.DEFAULT, FolderFinish.fromSlug("default"))
    }

    @Test
    fun `every finish carries a label a picker can show`() {
        FolderFinish.entries.forEach { finish ->
            assertTrue("${finish.name} has no label", finish.label.isNotBlank())
        }
    }
}
