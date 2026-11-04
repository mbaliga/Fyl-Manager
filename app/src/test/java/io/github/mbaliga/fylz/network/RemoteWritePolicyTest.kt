package io.github.mbaliga.fylz.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteWritePolicyTest {
    private fun file(name: String) = RemoteObject(name, name, directory = false, sizeBytes = 1, modifiedAtMillis = null, mimeType = null)
    private fun folder(name: String) = RemoteObject("$name/", name, directory = true, sizeBytes = null, modifiedAtMillis = null, mimeType = null)
    private fun connection(writes: Boolean) = RemoteConnection(
        id = "c", kind = RemoteKind.SFTP, displayName = "Box", host = "h", port = 22, username = "u",
        share = "", domain = "", endpoint = "", region = "", bucket = "", hostKeyFingerprint = "fp",
        writesEnabled = writes,
    )

    @Test
    fun `writes are refused unless the connection allows them`() {
        assertThrows(IllegalArgumentException::class.java) { RemoteWritePolicy.requireWritable(connection(false)) }
        RemoteWritePolicy.requireWritable(connection(true))
    }

    @Test
    fun `names must be one safe path segment`() {
        listOf("", "  ", ".", "..", "a/b", "a\\b", " lead", "trail ", "bad\u0007", "x".repeat(256), "a.fylz-upload-1")
            .forEach { assertNotNull("expected '$it' to be rejected", RemoteWritePolicy.nameProblem(RemoteKind.SFTP, it)) }
        listOf("report.pdf", "Photos 2026", "naïve – ünïcödé", ".hidden", "a:b")
            .forEach { assertNull("expected '$it' to be allowed", RemoteWritePolicy.nameProblem(RemoteKind.SFTP, it)) }
    }

    @Test
    fun `windows shares also reject reserved characters and a trailing dot`() {
        listOf("a:b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b", "name.")
            .forEach { assertNotNull("expected '$it' to be rejected on SMB", RemoteWritePolicy.nameProblem(RemoteKind.SMB, it)) }
        assertNull(RemoteWritePolicy.nameProblem(RemoteKind.SMB, "report.pdf"))
    }

    @Test
    fun `upload never silently replaces a file`() {
        val existing = listOf(file("a.txt"))
        val clash = assertThrows(RemoteConflictException::class.java) {
            RemoteWritePolicy.checkUpload(RemoteKind.SFTP, existing, "a.txt", replaceExisting = false)
        }
        assertFalse(clash.existingIsDirectory)
        RemoteWritePolicy.checkUpload(RemoteKind.SFTP, existing, "a.txt", replaceExisting = true)
        RemoteWritePolicy.checkUpload(RemoteKind.SFTP, existing, "b.txt", replaceExisting = false)
    }

    @Test
    fun `upload never replaces a folder even when replacing is allowed`() {
        val clash = assertThrows(RemoteConflictException::class.java) {
            RemoteWritePolicy.checkUpload(RemoteKind.WEBDAV, listOf(folder("docs")), "docs", replaceExisting = true)
        }
        assertTrue(clash.existingIsDirectory)
    }

    @Test
    fun `smb clashes ignore case and other backends do not`() {
        val existing = listOf(file("Report.PDF"))
        assertNotNull(RemoteWritePolicy.conflict(RemoteKind.SMB, existing, "report.pdf"))
        assertNull(RemoteWritePolicy.conflict(RemoteKind.SFTP, existing, "report.pdf"))
        assertNull(RemoteWritePolicy.conflict(RemoteKind.S3, existing, "report.pdf"))
    }

    @Test
    fun `new folder refuses any existing name`() {
        assertThrows(RemoteConflictException::class.java) {
            RemoteWritePolicy.checkNewFolder(RemoteKind.SFTP, listOf(file("x")), "x")
        }
        RemoteWritePolicy.checkNewFolder(RemoteKind.SFTP, listOf(file("x")), "y")
    }

    @Test
    fun `the connection root can never be deleted`() {
        listOf("", "/", "//", " / ").forEach { root ->
            assertThrows(IllegalArgumentException::class.java) { RemoteWritePolicy.requireDeletablePath(root) }
        }
        RemoteWritePolicy.requireDeletablePath("docs/a.txt")
    }

    @Test
    fun `only empty folders are deleted`() {
        assertThrows(IllegalStateException::class.java) { RemoteWritePolicy.requireEmptyFolder("docs", listOf(file("a"))) }
        RemoteWritePolicy.requireEmptyFolder("docs", emptyList())
    }

    @Test
    fun `child paths are joined and normalised`() {
        assertEquals("a.txt", RemoteWritePolicy.childPath("", "a.txt"))
        assertEquals("docs/a.txt", RemoteWritePolicy.childPath("/docs/", "a.txt"))
        assertThrows(IllegalArgumentException::class.java) { RemoteWritePolicy.childPath("docs", "../x") }
    }
}
