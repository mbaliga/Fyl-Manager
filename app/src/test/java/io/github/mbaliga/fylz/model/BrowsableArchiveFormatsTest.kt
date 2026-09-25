package io.github.mbaliga.fylz.model

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `BrowsableArchiveFormats` (docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md section 2.5): the explicit
 * set, matched on the compound extension, case-insensitively, with no `kind` precondition; the
 * excluded families stay out; a directory is never a browsable archive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrowsableArchiveFormatsTest {

    private fun entry(name: String, kind: EntryKind = EntryKind.OTHER) =
        FileEntry(Uri.parse("content://fylz/$name"), name, "application/octet-stream", 1L, 0L, 0, kind)

    @Test
    fun `every listed format opens as a folder, whatever the entry kind says`() {
        val names = listOf(
            "photos.zip", "a.zipx", "lib.jar", "app.apk", "comic.cbz", "x.7z", "comic.cb7",
            "a.tar", "a.tgz", "a.tbz", "a.tbz2", "a.txz", "a.tzst",
            "a.tar.gz", "a.tar.bz2", "a.tar.xz", "a.tar.zst", "a.tar.lz4",
            "disc.iso", "a.cpio", "lib.ar", "pkg.deb", "pkg.rpm", "setup.cab", "a.lha", "a.lzh", "crawl.warc",
        )
        names.forEach { name ->
            assertTrue(name, BrowsableArchiveFormats.matches(name))
            assertTrue(name, entry(name, EntryKind.OTHER).isBrowsableArchive)
            assertTrue(name, entry(name, EntryKind.ARCHIVE).isBrowsableArchive)
        }
        assertTrue(BrowsableArchiveFormats.matches("SHOUTY.ZIP"))
        assertTrue(BrowsableArchiveFormats.matches("Backup.TAR.GZ"))
        assertEquals(27, BrowsableArchiveFormats.extensions.size)
    }

    @Test
    fun `the excluded families and plain files stay closed`() {
        listOf(
            "a.rar", "comic.cbr", // fixtures pending
            "a.arj", "disk.img", "a.dmg", "a.wim", // no libarchive reader in format_all
            "a.xar", // needs libxml2/expat, both off
            "notes.txt.gz", "a.bz2", "a.xz", "a.zst", "a.lz4", // single-file streams: raw is not registered
            "notes.txt", "a.pdf", "photo.jpg", "zip", "archive", ".zip.txt",
        ).forEach { name ->
            assertFalse(name, BrowsableArchiveFormats.matches(name))
            assertFalse(name, entry(name, EntryKind.ARCHIVE).isBrowsableArchive)
        }
    }

    @Test
    fun `a directory is never a browsable archive, even when named like one`() {
        assertFalse(entry("photos.zip", EntryKind.DIRECTORY).isBrowsableArchive)
    }
}
