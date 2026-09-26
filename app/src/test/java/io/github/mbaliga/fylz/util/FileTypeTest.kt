package io.github.mbaliga.fylz.util

import io.github.mbaliga.fylz.model.EntryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTypeTest {
    @Test
    fun `markdown and agent artifacts are previewable text`() {
        assertEquals(EntryKind.MARKDOWN, FileType.classify("handoff.md", "text/markdown"))
        assertEquals(EntryKind.TEXT, FileType.classify("system.instructions", "application/octet-stream"))
        assertEquals(EntryKind.TEXT, FileType.classify("plan.mermaid", "application/octet-stream"))
        assertTrue(FileType.isTextPreviewable(EntryKind.MARKDOWN))
        assertTrue(FileType.isTextPreviewable(EntryKind.TEXT))
    }

    @Test
    fun `archives and PDFs are identified from extension fallbacks`() {
        assertEquals(EntryKind.ARCHIVE, FileType.classify("bundle.zip", "application/octet-stream"))
        assertEquals(EntryKind.PDF, FileType.classify("scan.pdf", "application/octet-stream"))
    }

    @Test
    fun `media MIME types win without relying on extensions`() {
        assertEquals(EntryKind.IMAGE, FileType.classify("asset", "image/png"))
        assertEquals(EntryKind.AUDIO, FileType.classify("recording", "audio/ogg"))
        assertEquals(EntryKind.VIDEO, FileType.classify("clip", "video/mp4"))
    }

    @Test
    fun `text extensions the registry knows but this class used to miss are now text`() {
        // P0.9, defect 10: this class kept its own, smaller, drifted-apart text-extension set,
        // so these fell through to OTHER (hex preview) even though FileFormatRegistry already
        // classified them as text.
        listOf("script.lua", "app.cfg", "movie.srt", "captions.vtt", "paper.tex", "refs.bib")
            .forEach { name -> assertEquals(name, EntryKind.TEXT, FileType.classify(name, "application/octet-stream")) }
    }
}
