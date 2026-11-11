package io.github.mbaliga.fylz.network

import org.junit.Assert.assertEquals
import org.junit.Test

class SftpPathsTest {
    @Test
    fun `without a root everything is relative to the login directory`() {
        // The top level and the folders under it must resolve against the same directory.
        assertEquals(".", SftpPaths.remotePath("", ""))
        assertEquals("docs", SftpPaths.remotePath("", "docs"))
        assertEquals("docs/a.txt", SftpPaths.remotePath("", "/docs/a.txt"))
    }

    @Test
    fun `a configured root is absolute`() {
        assertEquals("/srv/data", SftpPaths.remotePath("srv/data", ""))
        assertEquals("/srv/data/docs", SftpPaths.remotePath("/srv/data/", "docs"))
    }
}
