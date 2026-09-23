package io.github.mbaliga.fylz.ui

import android.net.Uri
import io.github.mbaliga.fylz.model.ClipboardMode
import io.github.mbaliga.fylz.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.FylzClipboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P0.10: small, independently testable pieces of the "honest actions and small correctness
 * fixes" cleanup -- pulled out of the composable so they don't need a Compose UI test harness
 * this project doesn't have. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FylzV1AppLogicTest {

    @Test
    fun `zip family extensions are recognized, everything else is not`() {
        listOf("photos.zip", "app.zipx", "lib.jar", "game.apk", "comic.cbz").forEach { name ->
            assertTrue(name, isZipFamilyArchive(name))
        }
        listOf("archive.7z", "backup.rar", "data.tar", "notes.txt").forEach { name ->
            assertFalse(name, isZipFamilyArchive(name))
        }
    }

    @Test
    fun `pdf output names actually interpolate the timestamp`() {
        // P0.10: an escaped backslash before ${...} used to produce the literal text "${...}"
        // instead -- valid Kotlin, so it compiled clean and never surfaced except as a wrong name.
        assertEquals("Fylz-pages-1700000000000.pdf", pdfPagesFileName(1_700_000_000_000L))
        assertEquals("Fylz-merged-1700000000000.pdf", pdfMergedFileName(1_700_000_000_000L))
    }

    private fun entry(name: String, uri: String): FileEntry = FileEntry(
        uri = Uri.parse(uri),
        name = name,
        mimeType = "application/pdf",
        sizeBytes = 10,
        lastModifiedMillis = 0,
        flags = 0,
        kind = EntryKind.PDF,
    )

    @Test
    fun `selection order is preserved regardless of folder listing order`() {
        val a = entry("a.pdf", "content://fylz/a")
        val b = entry("b.pdf", "content://fylz/b")
        val c = entry("c.pdf", "content://fylz/c")
        // The folder listing is alphabetical (a, b, c), but the user selected c, then a.
        val entries = listOf(a, b, c)
        val selectionOrder = linkedSetOf(c.uri, a.uri)

        assertEquals(listOf(c, a), orderedBySelection(entries, selectionOrder))
    }

    @Test
    fun `a selected uri no longer present in the listing is skipped, not crashed on`() {
        val a = entry("a.pdf", "content://fylz/a")
        val stale = Uri.parse("content://fylz/gone")

        assertEquals(listOf(a), orderedBySelection(listOf(a), linkedSetOf(stale, a.uri)))
    }

    @Test
    fun `clipboard chip label counts items and names Cut or Copy`() {
        val a = entry("a.pdf", "content://fylz/a")
        val b = entry("b.pdf", "content://fylz/b")

        assertEquals("1 item cut", clipboardChipLabel(FylzClipboard(ClipboardMode.CUT, listOf(a))))
        assertEquals("2 items cut", clipboardChipLabel(FylzClipboard(ClipboardMode.CUT, listOf(a, b))))
        assertEquals("1 item copied", clipboardChipLabel(FylzClipboard(ClipboardMode.COPY, listOf(a))))
        assertEquals("2 items copied", clipboardChipLabel(FylzClipboard(ClipboardMode.COPY, listOf(a, b))))
    }
}
