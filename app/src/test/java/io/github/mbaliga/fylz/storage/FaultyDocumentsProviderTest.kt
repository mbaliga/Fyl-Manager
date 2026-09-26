package io.github.mbaliga.fylz.storage

import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * P0.0: proves the fault-injection double itself behaves as later Phase 0 recovery tests will
 * rely on -- each configured failure mode actually fires, and everything else still works
 * normally through the wrapped real provider.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FaultyDocumentsProviderTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var rootDir: File
    private lateinit var faulty: FaultyDocumentsProvider

    @Before
    fun setUp() {
        rootDir = tempFolder.newFolder("primary")
        faulty = FaultyDocumentsProvider.install()
        faulty.volumeOverride = listOf(
            VolumeDescriptor(
                rootId = FylzFilesDocumentsProvider.PRIMARY_ROOT_ID,
                title = "Internal storage",
                directory = rootDir,
                primary = true,
                removable = false,
                readOnly = false,
            ),
        )
    }

    private fun rootId() = "${FylzFilesDocumentsProvider.PRIMARY_ROOT_ID}:"

    @Test
    fun `unconfigured double behaves like the real provider`() {
        val id = faulty.createDocument(rootId(), "text/plain", "ok.txt")
        val children = faulty.queryChildDocuments(rootId(), null, null as String?)
        assertEquals(1, children.count)
        assertTrue(id.isNotBlank())
    }

    @Test
    fun `refuseCreate throws instead of delegating`() {
        faulty.refuseCreate = true
        assertThrows(FileNotFoundException::class.java) {
            faulty.createDocument(rootId(), "text/plain", "nope.txt")
        }
        assertEquals(0, File(rootDir, "nope.txt").let { if (it.exists()) 1 else 0 })
    }

    @Test
    fun `refuseDelete throws and leaves the file in place`() {
        val id = faulty.createDocument(rootId(), "text/plain", "keep.txt")
        faulty.refuseDelete = true
        assertThrows(FileNotFoundException::class.java) { faulty.deleteDocument(id) }
        assertTrue(File(rootDir, "keep.txt").exists())
    }

    @Test
    fun `refuseRename throws and leaves the original name in place`() {
        val id = faulty.createDocument(rootId(), "text/plain", "a.txt")
        faulty.refuseRename = true
        assertThrows(FileNotFoundException::class.java) { faulty.renameDocument(id, "b.txt") }
        assertTrue(File(rootDir, "a.txt").exists())
    }

    @Test
    fun `throwAfterBytes truncates the underlying file at exactly the configured limit`() {
        val id = faulty.createDocument(rootId(), "application/octet-stream", "big.bin")
        faulty.throwAfterBytes = 4L

        ParcelFileDescriptor.AutoCloseOutputStream(faulty.openDocument(id, "w", null)).use { out ->
            repeat(100) { out.write(ByteArray(4096)) }
        }
        // See throwAfterBytes's own KDoc: Robolectric's ParcelFileDescriptor shadow never signals
        // the caller's write, so the 400 KiB write above completes without error either way.
        // What this double can guarantee is that the real file never received more than the
        // configured limit -- join the background copy first so this doesn't race it.
        val thread = faulty.lastTruncationThread
        thread?.join(THREAD_JOIN_TIMEOUT_MS)
        // If the copy thread is still running, it hasn't reached the limit (or given up on a
        // spurious EOF) within the join timeout -- fail here with that diagnosis instead of a
        // confusing length mismatch below.
        assertFalse("truncation copy thread did not finish within the join timeout", thread?.isAlive ?: false)

        assertEquals(4L, File(rootDir, "big.bin").length())
    }

    @Test
    fun `read mode is unaffected by throwAfterBytes`() {
        val id = faulty.createDocument(rootId(), "text/plain", "readable.txt")
        ParcelFileDescriptor.AutoCloseOutputStream(faulty.openDocument(id, "w", null)).use {
            it.write("hello".toByteArray())
        }
        faulty.throwAfterBytes = 1L

        val bytes = ParcelFileDescriptor.AutoCloseInputStream(faulty.openDocument(id, "r", null))
            .use { it.readBytes() }
        assertEquals("hello", String(bytes))
    }

    private companion object {
        const val THREAD_JOIN_TIMEOUT_MS = 5_000L
    }
}
