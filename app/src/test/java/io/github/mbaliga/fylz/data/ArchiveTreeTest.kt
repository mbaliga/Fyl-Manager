package io.github.mbaliga.fylz.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The navigation and safety rules behind browsing inside an archive.
 *
 * All of it is pure: an archive is a flat list of paths, folders are implied by the separators
 * inside them, and both the zip-slip guard and the folder arithmetic can therefore be pinned
 * without a device, a provider or a real archive.
 */
class ArchiveTreeTest {

    @Test
    fun `parent traversal is rejected in every spelling`() {
        assertNull(ArchiveTree.safePath("../etc/passwd"))
        assertNull(ArchiveTree.safePath("docs/../../etc/passwd"))
        assertNull(ArchiveTree.safePath("..\\..\\windows\\system32"))
        assertNull(ArchiveTree.safePath("./."))
        assertNull(ArchiveTree.safePath("C:/windows/system32"))
        assertNull(ArchiveTree.safePath(""))
        assertNull(ArchiveTree.safePath("   "))
        assertNull(ArchiveTree.safePath(null))
    }

    @Test
    fun `an absolute entry name is defused into a relative one rather than trusted`() {
        assertEquals("etc/passwd", ArchiveTree.safePath("/etc/passwd"))
        assertEquals("a/b", ArchiveTree.safePath("a//b"))
        assertEquals("a/b", ArchiveTree.safePath("a\\b"))
    }

    @Test
    fun `an over-deep or over-long name is refused`() {
        val deep = (0..ArchiveTree.MAX_DEPTH).joinToString("/") { "d" }
        assertNull(ArchiveTree.safePath(deep))
        val long = "x".repeat(ArchiveTree.MAX_SEGMENT_LENGTH + 1)
        assertNull(ArchiveTree.safePath("docs/$long"))
    }

    /**
     * The regression this fixes: TAR, cpio and 7z all write a directory record as "docs/", and the
     * previous hand-rolled validator split on '/' and rejected the blank final segment -- throwing
     * out the entire archive's listing over one perfectly ordinary folder entry.
     */
    @Test
    fun `a directory record's trailing separator is normalized, not treated as unsafe`() {
        assertEquals("docs", ArchiveTree.safePath("docs/"))
        assertEquals("docs/img", ArchiveTree.safePath("docs/img/"))
    }

    @Test
    fun `folders that exist only implicitly are still navigable`() {
        val members = listOf(
            ArchiveMember("readme.md", directory = false, sizeBytes = 12L),
            ArchiveMember("src/main/App.kt", directory = false, sizeBytes = 40L),
            ArchiveMember("src/main/Util.kt", directory = false, sizeBytes = 30L),
        )

        val root = ArchiveTree.children(members, "")
        assertEquals(listOf("src", "readme.md"), root.map { it.name })
        assertTrue(root.first().directory)

        val src = ArchiveTree.children(members, "src")
        assertEquals(listOf("main"), src.map { it.name })

        val main = ArchiveTree.children(members, "src/main")
        assertEquals(listOf("App.kt", "Util.kt"), main.map { it.name })
        assertTrue(main.none { it.directory })
    }

    @Test
    fun `a synthesized folder declares no size, because the archive declares none for it`() {
        val members = listOf(ArchiveMember("src/App.kt", directory = false, sizeBytes = 40L))
        assertNull(ArchiveTree.children(members, "").single().sizeBytes)
    }

    @Test
    fun `an explicit directory record does not also appear as a file`() {
        val members = listOf(
            ArchiveMember("docs", directory = true),
            ArchiveMember("docs/a.txt", directory = false, sizeBytes = 1L),
        )
        val root = ArchiveTree.children(members, "")
        assertEquals(1, root.size)
        assertTrue(root.single().directory)
    }

    @Test
    fun `child counts cover implicit folders and the root alike`() {
        val members = listOf(
            ArchiveMember("readme.md", directory = false),
            ArchiveMember("src/main/App.kt", directory = false),
            ArchiveMember("src/main/Util.kt", directory = false),
            ArchiveMember("src/test/AppTest.kt", directory = false),
        )
        val counts = ArchiveTree.childCounts(members)
        assertEquals(2, counts[""])
        assertEquals(2, counts["src"])
        assertEquals(2, counts["src/main"])
        assertEquals(1, counts["src/test"])
    }

    @Test
    fun `breadcrumbs walk down from the archive root`() {
        assertEquals(emptyList<ArchiveCrumb>(), ArchiveTree.breadcrumbs(""))
        assertEquals(
            listOf(ArchiveCrumb("src", "src"), ArchiveCrumb("main", "src/main")),
            ArchiveTree.breadcrumbs("src/main"),
        )
    }

    @Test
    fun `going up lands on the parent and stops at the root`() {
        assertEquals("src", ArchiveTree.parentOf("src/main"))
        assertEquals("", ArchiveTree.parentOf("src"))
        assertNull(ArchiveTree.parentOf(""))
    }

    @Test
    fun `only the families with a bundled reader are claimed`() {
        assertEquals(ArchiveFormats.Family.ZIP, ArchiveFormats.familyOf("zip"))
        assertEquals(ArchiveFormats.Family.SEVEN_Z, ArchiveFormats.familyOf("7z"))
        assertEquals(ArchiveFormats.Family.STREAM, ArchiveFormats.familyOf("tar.gz"))
        assertEquals(ArchiveFormats.Family.SINGLE, ArchiveFormats.familyOf("gz"))
        // No bundled decoder exists for any of these, so none of them may be advertised.
        assertNull(ArchiveFormats.familyOf("rar"))
        assertNull(ArchiveFormats.familyOf("zst"))
        assertNull(ArchiveFormats.familyOf("tar.zst"))
        assertNull(ArchiveFormats.familyOf("iso"))
        assertNull(ArchiveFormats.familyOf("dmg"))
    }
}

/** The extracted-member cache has to shrink as well as grow, or previewing an archive leaks disk. */
class ArchiveMemberCachePolicyTest {

    @Test
    fun `an untouched extraction eventually expires`() {
        val now = 10_000_000L
        val files = listOf(
            ArchiveMemberCachePolicy.CachedFile("stale", 10L, now - ArchiveMemberCachePolicy.MAX_AGE_MILLIS - 1L),
            ArchiveMemberCachePolicy.CachedFile("fresh", 10L, now),
        )
        assertEquals(listOf("stale"), ArchiveMemberCachePolicy.evictions(files, now))
    }

    @Test
    fun `the oldest extractions go first when the budget is exceeded`() {
        val now = 10_000_000L
        val files = (1..5).map {
            ArchiveMemberCachePolicy.CachedFile("member$it", 40L, now - (5 - it) * 1_000L)
        }
        val doomed = ArchiveMemberCachePolicy.evictions(files, now, maxBytes = 120L, maxFiles = 10)
        assertEquals(listOf("member1", "member2"), doomed)
    }

    @Test
    fun `a file count over the cap is trimmed oldest-first`() {
        val now = 10_000_000L
        val files = (1..4).map {
            ArchiveMemberCachePolicy.CachedFile("member$it", 1L, now - (5 - it) * 1_000L)
        }
        val doomed = ArchiveMemberCachePolicy.evictions(files, now, maxBytes = 1_000L, maxFiles = 2)
        assertEquals(listOf("member1", "member2"), doomed)
    }

    @Test
    fun `an in-flight extraction is never evicted to make room`() {
        val now = 10_000_000L
        val files = listOf(
            ArchiveMemberCachePolicy.CachedFile("writing.part", 5_000L, now, partial = true),
            ArchiveMemberCachePolicy.CachedFile("done", 5_000L, now - 1_000L),
        )
        val doomed = ArchiveMemberCachePolicy.evictions(files, now, maxBytes = 1L, maxFiles = 1)
        assertEquals(listOf("done"), doomed)
    }
}
