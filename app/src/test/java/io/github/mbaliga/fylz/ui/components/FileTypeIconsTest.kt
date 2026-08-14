package io.github.mbaliga.fylz.ui.components

import io.github.mbaliga.fylz.core.format.PreviewFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The catalog names files on disk, so the only failure worth guarding is a name that resolves to
 * nothing. These walk every reachable combination rather than spot-checking: a missing asset is
 * silent at runtime (Coil just draws nothing), which is exactly the kind of gap a listing full of
 * blank icons would take a device pass to notice.
 */
class FileTypeIconsTest {

    private val assets = File("src/main/assets")

    private fun exists(path: String) = File(assets, path).isFile

    @Test
    fun `every family resolves to a real asset in every style`() {
        val missing = mutableListOf<String>()
        PreviewFamily.entries.forEach { family ->
            IconStyle.entries.forEach { style ->
                val path = FileTypeIcons.assetPath(extension = "", family = family, style = style)
                if (!exists(path)) missing += "$family/$style -> $path"
            }
        }
        assertTrue("unresolved icons: $missing", missing.isEmpty())
    }

    @Test
    fun `every catalogued key resolves in every style`() {
        val missing = mutableListOf<String>()
        FileTypeIcons.allKeys().forEach { key ->
            IconStyle.entries.forEach { style ->
                // Exact keys resolve through the extension path; generics through the family path.
                val path = FileTypeIcons.assetPath(key, PreviewFamily.BINARY, style)
                val viaKey = if (path.contains(key)) path else "filetype/${key}_${style.slug}.svg"
                if (!exists(viaKey) && !exists("filetype/${key}_filled.svg")) missing += "$key/$style"
            }
        }
        assertTrue("unresolved keys: $missing", missing.isEmpty())
    }

    @Test
    fun `an exact format wins over its family generic`() {
        assertEquals(
            "filetype/docx_gradient.svg",
            FileTypeIcons.assetPath("docx", PreviewFamily.OFFICE, IconStyle.GRADIENT),
        )
    }

    @Test
    fun `an unknown extension falls back to the family generic`() {
        assertEquals(
            "filetype/simple-audio_filled.svg",
            FileTypeIcons.assetPath("weirdaudio", PreviewFamily.AUDIO, IconStyle.FILLED),
        )
    }

    @Test
    fun `styles the pack does not ship fall back to filled rather than vanishing`() {
        // The Design formats and every simple-* generic have no Default artwork.
        assertEquals(
            "filetype/psd_filled.svg",
            FileTypeIcons.assetPath("psd", PreviewFamily.IMAGE, IconStyle.DEFAULT),
        )
        assertEquals(
            "filetype/simple-folder_filled.svg",
            FileTypeIcons.assetPath("", PreviewFamily.DIRECTORY, IconStyle.DEFAULT),
        )
        // A format that DOES ship Default keeps it.
        assertEquals(
            "filetype/pdf_default.svg",
            FileTypeIcons.assetPath("pdf", PreviewFamily.PDF, IconStyle.DEFAULT),
        )
    }
}
