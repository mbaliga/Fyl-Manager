package io.github.mbaliga.fylz.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class WebDavPathsTest {
    private val base = "https://dav.example.com/remote.php/dav/files/me"

    @Test
    fun `paths resolve under the base and are percent encoded`() {
        assertEquals("https://dav.example.com/remote.php/dav/files/me/", WebDavPaths.resolve(base, ""))
        assertEquals(
            "https://dav.example.com/remote.php/dav/files/me/My%20Docs/a%23b%3F.txt",
            WebDavPaths.resolve(base, "My Docs/a#b?.txt"),
        )
        assertEquals(
            "https://dav.example.com/remote.php/dav/files/me/caf%C3%A9/",
            WebDavPaths.resolve(base, "café", collection = true),
        )
    }

    @Test
    fun `traversal is rejected`() {
        listOf("../x", "a/../../x", "./x").forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { WebDavPaths.resolve(base, bad) }
        }
    }

    @Test
    fun `a name that looks like another url stays a file name on the same host`() {
        val resolved = URI(WebDavPaths.resolve(base, "https:/evil.example/x"))
        assertEquals("dav.example.com", resolved.host)
        assertTrue(resolved.rawPath.startsWith("/remote.php/dav/files/me/"))
        assertEquals(null, resolved.rawQuery)
    }

    @Test
    fun `encoded dot segments are literal names, never traversal`() {
        val resolved = URI(WebDavPaths.resolve(base, "%2e%2e/x"))
        assertTrue(resolved.rawPath.startsWith("/remote.php/dav/files/me/%252e%252e/"))
    }

    @Test
    fun `the folder's own propfind entry is recognised in both href forms`() {
        val url = WebDavPaths.resolve(base, "My Docs", collection = true)
        assertTrue(WebDavPaths.isSameResource(url, "/remote.php/dav/files/me/My%20Docs/"))
        assertTrue(WebDavPaths.isSameResource(url, "https://dav.example.com/remote.php/dav/files/me/My%20Docs"))
        assertFalse(WebDavPaths.isSameResource(url, "/remote.php/dav/files/me/My%20Docs/a.txt"))
    }
}
