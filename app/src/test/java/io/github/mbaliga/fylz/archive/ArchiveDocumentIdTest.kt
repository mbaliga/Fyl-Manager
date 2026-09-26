package io.github.mbaliga.fylz.archive

import android.net.Uri
import android.provider.DocumentsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.json.JSONObject
import org.robolectric.annotation.Config
import java.util.Base64

/**
 * `ArchiveDocumentId` (docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md section 2.1): the encode/parse
 * round trip, depth = `n.size + 1` with the fifth level refused at construction, and every hostile
 * shape a caller could hand the provider.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArchiveDocumentIdTest {

    private val source = Uri.parse("content://io.github.mbaliga.fylz.files/tree/primary%3A/document/primary%3ADownload%2Fphotos.zip")

    private fun idOf(json: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    @Test
    fun `encode and parse round-trip every field, and the Uri is the non-tree document form`() {
        val id = ArchiveDocumentId(source, listOf("inner1.zip", "d/inner2.zip"), 1234, "2024/a.jpg")
        val encoded = id.encode()
        assertEquals(id, ArchiveDocumentId.parse(encoded))
        val uri = id.toUri()
        assertEquals(ArchiveDocumentId.AUTHORITY, uri.authority)
        assertEquals(listOf("document", encoded), uri.pathSegments)
        assertFalse(DocumentsContract.isTreeUri(uri))
        assertEquals(id, ArchiveDocumentId.parse(uri))
        assertEquals(3, id.depth)
        assertEquals("a.jpg", id.name)
        assertEquals(ArchiveRef(source, listOf("inner1.zip", "d/inner2.zip")), id.archive)
        // The JSON is the documented flat shape (compared as parsed fields: the reference org.json
        // on the test classpath escapes `/` as `\/` where Android's does not; both parse the same).
        val json = JSONObject(String(Base64.getUrlDecoder().decode(encoded)))
        assertEquals(setOf("v", "src", "n", "o", "p"), json.keys().asSequence().toSet())
        assertEquals(1, json.getInt("v"))
        assertEquals(source.toString(), json.getString("src"))
        assertEquals(listOf("inner1.zip", "d/inner2.zip"), List(json.getJSONArray("n").length()) { json.getJSONArray("n").getString(it) })
        assertEquals(1234, json.getInt("o"))
        assertEquals("2024/a.jpg", json.getString("p"))
        assertFalse("no padding", encoded.contains('='))
    }

    @Test
    fun `the root has an empty path, the implicit ordinal, and the archive file's name`() {
        val root = ArchiveDocumentId.root(source)
        assertTrue(root.isRoot)
        assertEquals(ArchiveDocumentId.IMPLICIT_ORDINAL, root.ordinal)
        assertEquals(1, root.depth)
        assertEquals("photos.zip", root.name)
        assertEquals(root, ArchiveDocumentId.parse(root.encode()))
        val nestedRoot = root.entry(7, "d/inner.zip").nestedRoot()
        assertEquals(listOf("d/inner.zip"), nestedRoot.chain)
        assertEquals("inner.zip", nestedRoot.name)
        assertEquals(2, nestedRoot.depth)
    }

    @Test
    fun `depth is the chain length plus one, and the fifth level is refused at construction`() {
        val depth4 = ArchiveDocumentId(source, listOf("l2.zip", "l3.zip", "l4.zip"), 0, "innermost.txt")
        assertEquals(4, depth4.depth)
        assertEquals(depth4, ArchiveDocumentId.parse(depth4.encode()))
        val refused = assertThrows(IllegalArgumentException::class.java) { depth4.nestedRoot() }
        assertEquals(ArchiveDocumentId.DEPTH_REFUSED, refused.message)
        assertEquals("Archives nested deeper than 4 levels cannot be browsed", refused.message)
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveDocumentId(source, listOf("a.zip", "b.zip", "c.zip", "d.zip"), 0, "x")
        }
        // ... and at parse time for a forged id.
        val forged = idOf("""{"v":1,"src":"$source","n":["a.zip","b.zip","c.zip","d.zip"],"o":0,"p":"x"}""")
        assertThrows(IllegalArgumentException::class.java) { ArchiveDocumentId.parse(forged) }
    }

    @Test
    fun `hostile ids are refused`() {
        val cases = mapOf(
            "not base64" to "%%%not-base64%%%",
            "not JSON" to idOf("hello"),
            "wrong version" to idOf("""{"v":2,"src":"$source","n":[],"o":-1,"p":""}"""),
            "missing src" to idOf("""{"v":1,"n":[],"o":-1,"p":""}"""),
            "blank src" to idOf("""{"v":1,"src":"","n":[],"o":-1,"p":""}"""),
            "src on the archive authority" to idOf("""{"v":1,"src":"content://${ArchiveDocumentId.AUTHORITY}/document/x","n":[],"o":-1,"p":""}"""),
            "missing n" to idOf("""{"v":1,"src":"$source","o":-1,"p":""}"""),
            // (`"n":[1]` is not here: org.json coerces a scalar to the string "1", which is then judged
            // as a path like any other and passes -- there is nothing hostile a number can smuggle.)
            "empty chain entry" to idOf("""{"v":1,"src":"$source","n":[""],"o":-1,"p":""}"""),
            "unnormalised chain entry" to idOf("""{"v":1,"src":"$source","n":["./x.zip"],"o":-1,"p":""}"""),
            "ordinal below -1" to idOf("""{"v":1,"src":"$source","n":[],"o":-2,"p":"a"}"""),
            "ordinal not a number" to idOf("""{"v":1,"src":"$source","n":[],"o":"seven","p":"a"}"""),
            "leading slash" to idOf("""{"v":1,"src":"$source","n":[],"o":0,"p":"/a"}"""),
            "trailing slash" to idOf("""{"v":1,"src":"$source","n":[],"o":0,"p":"a/"}"""),
            "double slash" to idOf("""{"v":1,"src":"$source","n":[],"o":0,"p":"a//b"}"""),
            "dot segment" to idOf("""{"v":1,"src":"$source","n":[],"o":0,"p":"a/./b"}"""),
            "dot-dot segment" to idOf("""{"v":1,"src":"$source","n":[],"o":0,"p":"../b"}"""),
            "missing p" to idOf("""{"v":1,"src":"$source","n":[],"o":0}"""),
        )
        for ((name, id) in cases) {
            assertThrows(name, IllegalArgumentException::class.java) { ArchiveDocumentId.parse(id) }
        }
        assertThrows(IllegalArgumentException::class.java) { ArchiveDocumentId.parse(Uri.parse("content://other/document/x")) }
    }

    @Test
    fun `a backslash is an ordinary path character in an id`() {
        // Only ZIP separators are rewritten, and that happens in the tree before an id exists.
        val id = ArchiveDocumentId(source, emptyList(), 3, "odd\\name.txt")
        assertEquals(id, ArchiveDocumentId.parse(id.encode()))
        assertTrue(ArchiveDocumentId.isNormalizedPath("a/b\\c"))
        assertTrue(ArchiveDocumentId.isNormalizedPath(""))
        assertFalse(ArchiveDocumentId.isNormalizedPath("a/"))
    }

    @Test
    fun `isArchiveUri keys on the hard-coded authority`() {
        assertTrue(ArchiveDocumentId.isArchiveUri(ArchiveDocumentId.root(source).toUri()))
        assertFalse(ArchiveDocumentId.isArchiveUri(source))
        assertFalse(ArchiveDocumentId.isArchiveUri(null))
        assertEquals("io.github.mbaliga.fylz.archives", ArchiveDocumentId.AUTHORITY)
    }
}
