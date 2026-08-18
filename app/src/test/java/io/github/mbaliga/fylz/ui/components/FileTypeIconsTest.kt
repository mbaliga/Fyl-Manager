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

    @Test
    fun `every generic family has real Vintage and Retro artwork`() {
        val missing = mutableListOf<String>()
        PreviewFamily.entries.forEach { family ->
            listOf(IconStyle.VINTAGE, IconStyle.RETRO).forEach { style ->
                val path = FileTypeIcons.assetPath(extension = "", family = family, style = style)
                if (!exists(path)) missing += "$family/$style -> $path"
            }
        }
        assertTrue("unresolved pixel-pack icons: $missing", missing.isEmpty())
    }

    @Test
    fun `every key in the pixel pack has real Vintage and Retro artwork on disk`() {
        // simple-code ships artwork but no family maps to it (same gap the four-style pack has
        // always had) — checked here directly rather than through assetPath for that reason.
        val pixelKeys = setOf(
            "simple-folder", "simple-empty", "simple-image", "simple-video",
            "simple-audio", "simple-document", "simple-code", "simple-pdf",
            "zip", "sql", "exe",
        )
        val missing = mutableListOf<String>()
        pixelKeys.forEach { key ->
            listOf("vintage", "retro").forEach { slug ->
                if (!exists("filetype/${key}_$slug.svg")) missing += "${key}_$slug.svg"
            }
        }
        assertTrue("missing pixel-pack artwork: $missing", missing.isEmpty())
    }

    @Test
    fun `Vintage and Retro resolve their own generic families directly, not through fallback`() {
        assertEquals(
            "filetype/simple-folder_vintage.svg",
            FileTypeIcons.assetPath("", PreviewFamily.DIRECTORY, IconStyle.VINTAGE),
        )
        assertEquals(
            "filetype/simple-image_retro.svg",
            FileTypeIcons.assetPath("", PreviewFamily.IMAGE, IconStyle.RETRO),
        )
        assertEquals("filetype/zip_vintage.svg", FileTypeIcons.assetPath("zip", PreviewFamily.ARCHIVE, IconStyle.VINTAGE))
        assertEquals("filetype/sql_retro.svg", FileTypeIcons.assetPath("sql", PreviewFamily.DATABASE, IconStyle.RETRO))
        assertEquals("filetype/exe_vintage.svg", FileTypeIcons.assetPath("exe", PreviewFamily.EXECUTABLE, IconStyle.VINTAGE))
    }

    @Test
    fun `a format outside the pixel pack falls back to filled under Vintage and Retro`() {
        assertEquals(
            "filetype/docx_filled.svg",
            FileTypeIcons.assetPath("docx", PreviewFamily.OFFICE, IconStyle.VINTAGE),
        )
        assertEquals(
            "filetype/psd_filled.svg",
            FileTypeIcons.assetPath("psd", PreviewFamily.IMAGE, IconStyle.RETRO),
        )
    }
}
