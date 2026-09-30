package io.github.mbaliga.fylz.operations

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `TargetPlanning.kt`'s [NameIndex] (M3.4 section 2.2 step 6): one listing, case-aware lookups, reservations and Keep-both names. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NameIndexTest {

    private fun node(name: String) = DocNode(Uri.parse("content://t/$name"), name, name, "text/plain", 1L, 1L, 0, isDirectory = false)

    @Test
    fun `finds by exact name on a case-sensitive destination and by folded name on a case-insensitive one`() {
        val sensitive = NameIndex(listOf(node("Readme.txt"), node("photo.jpg")), caseInsensitive = false)
        assertEquals("Readme.txt", sensitive.find("Readme.txt")?.name)
        assertNull(sensitive.find("readme.txt"))
        assertTrue(sensitive.contains("photo.jpg"))
        assertFalse(sensitive.contains("PHOTO.jpg"))
        assertEquals(2, sensitive.size)

        val insensitive = NameIndex(listOf(node("Readme.txt")), caseInsensitive = true)
        assertEquals("Readme.txt", insensitive.find("README.TXT")?.name)
        assertTrue(insensitive.contains("readme.txt"))
    }

    @Test
    fun `uniqueName picks the first free numbered name, reserves it, and never hands it out twice`() {
        val index = NameIndex(listOf(node("a.txt"), node("a (2).txt"), node("dir")), caseInsensitive = false)
        assertEquals("a (3).txt", index.uniqueName("a.txt"))
        assertEquals("a (4).txt", index.uniqueName("a.txt"))
        assertEquals("dir (2)", index.uniqueName("dir"))
        assertEquals("a name with no dot is numbered whole", ".hidden (2)", index.uniqueName(".hidden"))
        index.reserve("b.txt")
        assertTrue(index.contains("b.txt"))
        assertNull("a reservation is not a listed child", index.find("b.txt"))
        assertEquals("b (2).txt", index.uniqueName("b.txt"))
    }

    @Test
    fun `a reservation on a case-insensitive index blocks case variants too`() {
        val index = NameIndex(emptyList(), caseInsensitive = true)
        index.reserve("Photo.JPG")
        assertTrue(index.contains("photo.jpg"))
        assertEquals("photo (2).jpg", index.uniqueName("photo.jpg"))
    }
}
